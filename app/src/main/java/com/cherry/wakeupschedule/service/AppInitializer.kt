package com.cherry.wakeupschedule.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 冷启动后台初始化器。
 *
 * 背景：Application.onCreate 原先串行执行 JwxtAccountManager.init / SemesterManager.init /
 * CourseDataManager 首次加载，全部是同步 Room 查询（首次查询还会触发 SQLite 建库 + 迁移链），
 * 在低端机上要占掉几十到上百毫秒的主线程时间；而这期间界面只是一张空白过渡页（Splash 400ms）。
 *
 * 现在这批 I/O 全部挪到一条后台线程执行，与闪屏展示窗口、页面类加载/布局 inflate 重叠。
 * 依赖这批缓存的入口（MainActivity、冷启动直接恢复的其他页面、小组件广播接收器）
 * 在读取前置位调用 [awaitReady] 兜底：
 * - 初始化已完成：一次 volatile 读，零成本；
 * - 未完成：阻塞到初始化结束（主线程有超时保护），数据可见性与旧实现完全一致。
 *
 * 死锁约束（必须守住）：
 * 1. 初始化线程自身调用 [awaitReady] 立即返回 —— 否则自己等自己；
 * 2. 初始化线程绝不向主线程 post 任务、也不等待任何依赖主线程的锁，
 *    因此主线程在 [awaitReady] 上等待时，初始化线程一定能继续推进。
 */
object AppInitializer {

    private const val TAG = "AppInitializer"

    /**
     * 主线程兜底等待上限。正常初始化只需几十~几百毫秒，这里给足余量，
     * 只是为了避免初始化线程异常卡死时把主线程永久拖住（旧实现在同一位置同样会卡）。
     */
    private const val MAIN_THREAD_WAIT_TIMEOUT_MS = 10_000L

    private val lock = Any()
    private val readyLatch = CountDownLatch(1)

    @Volatile
    private var ready = false

    @Volatile
    private var started = false

    /** 初始化所在线程：它自己不得等待，否则自己等自己直接死锁 */
    @Volatile
    private var initThread: Thread? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 就绪后要在主线程执行一次的回调（当前只有 App 的每秒小组件 tick） */
    private val readyCallbacks = mutableListOf<() -> Unit>()

    /**
     * 初始化完成后在初始化线程执行的「启动期收尾任务」（当前是小组件闹钟链的建立）。
     *
     * 这些任务只碰 AlarmManager / AppWidgetManager 的 binder 调用，不需要主线程；
     * 放在这里可以省掉 App.onCreate 主线程上的 8~10 次 binder 往返（低端机上每次 ~0.1~0.3ms）。
     * 时序上无行为影响：15/30 分钟级兜底闹钟最早也要几十分钟后才可能触发。
     */
    @Volatile
    private var afterInitTask: (() -> Unit)? = null

    /** 是否已完成初始化（失败也会置位，保证等待方不会被永久挂住）。热路径用。 */
    val isReady: Boolean
        get() = ready

    /**
     * 启动后台初始化，幂等（仅 Application.onCreate 调用）。
     * 调用前应把必须留在主线程的初始化（主题等）做完，避免两边争抢同一份 SharedPreferences。
     */
    fun start(context: Context, afterInit: (() -> Unit)? = null) {
        val app = context.applicationContext
        synchronized(lock) {
            if (started) return
            started = true
            afterInitTask = afterInit
        }
        val thread = Thread({ runInit(app) }, "app-init").apply { isDaemon = true }
        initThread = thread
        thread.start()
    }

    /**
     * 等待初始化完成，可在任意线程调用。
     *
     * @return 调用返回时是否已就绪。未就绪（初始化线程自身 / 超时 / 尚未启动）时返回 false，
     * 调用方无需分支处理 —— 各 Manager 会保持和"未初始化"一致的空缓存语义。
     */
    fun awaitReady(): Boolean {
        if (ready) return true
        // 初始化线程自己绝不能等：直接放行，否则立即死锁
        if (Thread.currentThread() === initThread) return false
        if (!started) return false
        val isMainThread = Looper.myLooper() == Looper.getMainLooper()
        return try {
            // 只有主线程设超时（避免初始化异常卡死时把主线程永久挂住）；后台线程等待没有 UI 风险
            val ok = if (isMainThread) {
                readyLatch.await(MAIN_THREAD_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } else {
                readyLatch.await()
                true
            }
            if (!ok) Log.e(TAG, "主线程等待后台初始化超时（${MAIN_THREAD_WAIT_TIMEOUT_MS}ms），按未就绪继续")
            ok
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /**
     * 初始化完成后在主线程执行一次回调；若已就绪则立即投递到主线程。
     * 用于"每秒小组件 tick"这类必须等数据就绪才能开始的常驻任务。
     */
    fun runOnMainWhenReady(callback: () -> Unit) {
        // 注册与触发在同一把锁里二选一：已就绪则自己投递，否则入列由 runInit 统一投递。
        // 不存在「入列后 runInit 刚好取完队列」的丢失窗口，也不会重复执行。
        val alreadyReady = synchronized(lock) {
            if (ready) {
                true
            } else {
                readyCallbacks.add(callback)
                false
            }
        }
        if (alreadyReady) mainHandler.post { callback() }
    }

    private fun runInit(app: Context) {
        try {
            // SharedPreferences 预热（只读一次触发文件加载，不改任何状态）：
            // BaseActivity.attachBaseContext / ThemeManager.init 会在主线程读 m3_theme，
            // 提前在后台读进内存后主线程首次读就是缓存命中；若主线程抢先读，
            // SharedPreferences 内部会用同一把锁串行化，最坏退化成原来的开销，不会更慢。
            ThemeManager.getPaletteIndex(app)

            // 顺序与原 App.onCreate 保持一致：教务账号（首次查询顺带完成建库/迁移）→
            // CookieJar → 学期缓存 → 课程缓存。全部在后台线程跑。
            JwxtAccountManager.init(app)
            RoomCookieJar.init(app)
            SemesterManager.init(app)
            CourseDataManager.getInstance(app)
            Log.d(TAG, "后台初始化完成")
        } catch (t: Throwable) {
            // 兜底所有异常：失败也要放行等待方，否则主线程会一直等到超时。
            // 旧实现是在 App.onCreate 里直接抛出（DB 损坏时崩溃）；这里改为记录后放行，
            // 让应用以空数据启动，不再因启动期 I/O 失败直接闪退。
            Log.e(TAG, "后台初始化失败", t)
        } finally {
            // 置 ready 与取走待执行回调在同一把锁里完成，保证等待方/回调注册方看到一致状态：
            // 先完成的注册会入列被这里取走；晚完成的注册会看到 ready=true 自己投递。
            val callbacks = synchronized(lock) {
                ready = true
                val pending = readyCallbacks.toList()
                readyCallbacks.clear()
                pending
            }
            readyLatch.countDown()
            // 统一回主线程执行：调用方（App）启动 Handler tick 需要主线程
            for (callback in callbacks) mainHandler.post { callback() }
            // 启动期收尾任务（小组件闹钟链建立）：只在初始化线程跑，不占用主线程；
            // 独立 try/catch，避免收尾任务异常影响"已就绪"状态
            try {
                afterInitTask?.invoke()
            } catch (t: Throwable) {
                Log.e(TAG, "初始化收尾任务失败", t)
            }
        }
    }
}
