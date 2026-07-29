# 多账号系统重构 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将应用从单账号架构重构为多账号架构，每个账号绑定一个教务系统凭据，数据按 account_id 隔离，支持多账号切换/解绑/初始化。

**Architecture:** Room 数据库新增 4 张表（accounts/account_profiles/semester_infos/account_settings），courses 表增加 account_id 外键。AccountRepository 统一管理账号生命周期。CourseDataManager 内部切换活跃账号。全局设置保持 SharedPreferences，账号级设置迁入 Room。UI 层增加未绑定蒙版、初始化进度条、账号切换/解绑交互。

**Tech Stack:** Kotlin, Room, Coroutines, ViewModel, LiveData/Flow, Material 3, ViewBinding, Gson

---

## 文件结构

### 新建文件

| 文件 | 职责 |
|------|------|
| `model/AccountEntity.kt` | 账号 Room 实体 |
| `model/AccountProfileEntity.kt` | 个人信息 Room 实体 |
| `model/SemesterInfoEntity.kt` | 学期信息 Room 实体 |
| `model/AccountSettingsEntity.kt` | 账号设置 Room 实体 |
| `model/AccountSettings.kt` | 账号设置数据类（序列化为 JSON） |
| `model/dao/AccountDao.kt` | 账号 + 个人信息 DAO |
| `model/dao/SemesterInfoDao.kt` | 学期信息 DAO |
| `model/dao/AccountSettingsDao.kt` | 账号设置 DAO |
| `service/AccountRepository.kt` | 账号生命周期管理（CRUD/切换/解绑/初始化） |
| `res/layout/layout_unbound_mask.xml` | 未绑定蒙版布局 |
| `res/layout/dialog_account_switch.xml` | 切换账号弹窗布局 |
| `res/layout/dialog_unbind_confirm.xml` | 解绑确认弹窗布局 |

### 修改文件

| 文件 | 改动范围 |
|------|---------|
| `model/Course.kt` | 新增 `accountId` 字段 |
| `model/CourseDao.kt` | 所有查询加 `account_id` 过滤 |
| `model/AppDatabase.kt` | 注册新实体+DAO，version 升至 2 |
| `service/CourseDataManager.kt` | 支持活跃账号切换 |
| `service/SettingsManager.kt` | 全局/账号分流 |
| `service/JwxtAccountManager.kt` | object→class，融入 AccountRepository |
| `service/JwxtAuthManager.kt` | 支持多账号 JwxtClient 管理 |
| `service/AlarmService.kt` | 活跃账号闹钟策略 |
| `viewmodel/CourseViewModel.kt` | 初始化流程 + 进度 + 空状态 |
| `App.kt` | 初始化 AccountRepository |
| `MainActivity.kt` | 未绑定蒙版联动 |
| `ScheduleActivity.kt` | 初始化进度条入口 |
| `BindJwxtActivity.kt` | 多账号绑定逻辑 |
| `SettingsActivity.kt` | 账号信息卡片 + 切换/解绑 + 学期选择 |
| `ProfileActivity.kt` | 展示缓存个人信息 |
| `ScheduleFragment.kt` | 未绑定蒙版 + 初始化触发 |
| `WeekPageFragment.kt` | 账号隔离的课程数据 |
| `widget/ScheduleWidgetProvider.kt` | 活跃账号课程 |
| `widget/MinimalWidgetProvider.kt` | 活跃账号课程 |
| `widget/UpcomingDaysWidgetProvider.kt` | 活跃账号课程 |

---

## Phase 1: 数据层

### Task 1: 新建 Room 实体

**Files:** Create `model/AccountEntity.kt`, `model/AccountProfileEntity.kt`, `model/SemesterInfoEntity.kt`, `model/AccountSettingsEntity.kt`

- [ ] **Step 1: 创建 AccountEntity**

```kotlin
// app/src/main/java/com/cherry/wakeupschedule/model/AccountEntity.kt
package com.cherry.wakeupschedule.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "username")
    val username: String,

    @ColumnInfo(name = "password")
    val password: String,

    @ColumnInfo(name = "session_json")
    val sessionJson: String = "",       // cookie + token JSON

    @ColumnInfo(name = "bound_at")
    val boundAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "last_active")
    val lastActive: Long = System.currentTimeMillis()
)
```

- [ ] **Step 2: 创建 AccountProfileEntity**

```kotlin
// app/src/main/java/com/cherry/wakeupschedule/model/AccountProfileEntity.kt
package com.cherry.wakeupschedule.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "account_profiles",
    foreignKeys = [ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["account_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("account_id", unique = true)]
)
data class AccountProfileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "account_id")
    val accountId: Long,

    @ColumnInfo(name = "profile_json")
    val profileJson: String,            // StudentProfile 完整 JSON

    @ColumnInfo(name = "grade_year")
    val gradeYear: Int,                 // 入学年级，如 2024

    @ColumnInfo(name = "study_years")
    val studyYears: Int = 4,            // 学制

    @ColumnInfo(name = "saved_at")
    val savedAt: Long = System.currentTimeMillis()
)
```

- [ ] **Step 3: 创建 SemesterInfoEntity**

```kotlin
// app/src/main/java/com/cherry/wakeupschedule/model/SemesterInfoEntity.kt
package com.cherry.wakeupschedule.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "semester_infos",
    foreignKeys = [ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["account_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("account_id")]
)
data class SemesterInfoEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "account_id")
    val accountId: Long,

    @ColumnInfo(name = "academic_year")
    val academicYear: String,           // "2024-2025学年"

    @ColumnInfo(name = "term_code")
    val termCode: String,               // "3" (AUTUMN) 或 "12" (SPRING)

    @ColumnInfo(name = "grade_label")
    val gradeLabel: String,             // "大一" / "大二" / "大三" / "大四"

    @ColumnInfo(name = "start_date")
    val startDate: Long = 0,            // 学期开始日期（从课表反推）

    @ColumnInfo(name = "total_weeks")
    val totalWeeks: Int = 20,

    @ColumnInfo(name = "is_data_loaded")
    val isDataLoaded: Boolean = false   // 是否已拉取课表
)
```

- [ ] **Step 4: 创建 AccountSettingsEntity**

```kotlin
// app/src/main/java/com/cherry/wakeupschedule/model/AccountSettingsEntity.kt
package com.cherry.wakeupschedule.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "account_settings",
    foreignKeys = [ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["account_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("account_id", unique = true)]
)
data class AccountSettingsEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "account_id")
    val accountId: Long,

    @ColumnInfo(name = "settings_json")
    val settingsJson: String            // AccountSettings 数据类 JSON
)
```

- [ ] **Step 5: 创建 AccountSettings 数据类**

```kotlin
// app/src/main/java/com/cherry/wakeupschedule/model/AccountSettings.kt
package com.cherry.wakeupschedule.model

data class AccountSettings(
    val silentRelogin: Boolean = false,      // 无感重载开关
    val currentSemester: String = "",         // 当前学期
    val defaultWeek: Int = 1,                // 默认显示周
    val alarmEnabled: Boolean = true,         // 闹钟启用
    val semesterStartDate: Long = 0,          // 学期开始日期
    val totalWeeks: Int = 20,                // 总周数
    val customSemesters: List<String> = emptyList()  // 自定义学期列表
)
```

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/cherry/wakeupschedule/model/
git commit -m "feat: 新增多账号 Room 实体（Account/Profile/SemesterInfo/Settings）"
```

---

### Task 2: 修改 Course 实体增加 accountId

**Files:** Modify `model/Course.kt`

- [ ] **Step 1: 在 Course 中添加 accountId 字段**

```kotlin
// 在 Course.kt 的 data class Course( 中，id 字段后添加：
    @ColumnInfo(name = "account_id")
    val accountId: Long = 0,              // 所属账号ID（外键）
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/cherry/wakeupschedule/model/Course.kt
git commit -m "feat: Course 实体添加 account_id 外键字段"
```

---

### Task 3: 新建 DAO

**Files:** Create `model/dao/AccountDao.kt`, `model/dao/SemesterInfoDao.kt`, `model/dao/AccountSettingsDao.kt`

- [ ] **Step 1: 创建 AccountDao**

```kotlin
// app/src/main/java/com/cherry/wakeupschedule/model/dao/AccountDao.kt
package com.cherry.wakeupschedule.model.dao

import androidx.room.*
import com.cherry.wakeupschedule.model.AccountEntity
import com.cherry.wakeupschedule.model.AccountProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    // ── Account ──

    @Query("SELECT * FROM accounts ORDER BY last_active DESC")
    fun getAllAccountsFlow(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY last_active DESC")
    suspend fun getAllAccounts(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getAccountById(id: Long): AccountEntity?

    @Query("SELECT * FROM accounts WHERE username = :username")
    suspend fun getAccountByUsername(username: String): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccount(account: AccountEntity): Long

    @Query("UPDATE accounts SET last_active = :timestamp WHERE id = :id")
    suspend fun updateLastActive(id: Long, timestamp: Long)

    @Delete
    suspend fun deleteAccount(account: AccountEntity)

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun getAccountCount(): Int

    // ── Profile ──

    @Query("SELECT * FROM account_profiles WHERE account_id = :accountId")
    suspend fun getProfile(accountId: Long): AccountProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: AccountProfileEntity)

    @Query("DELETE FROM account_profiles WHERE account_id = :accountId")
    suspend fun deleteProfile(accountId: Long)
}
```

- [ ] **Step 2: 创建 SemesterInfoDao**

```kotlin
// app/src/main/java/com/cherry/wakeupschedule/model/dao/SemesterInfoDao.kt
package com.cherry.wakeupschedule.model.dao

import androidx.room.*
import com.cherry.wakeupschedule.model.SemesterInfoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SemesterInfoDao {

    @Query("SELECT * FROM semester_infos WHERE account_id = :accountId ORDER BY academic_year, term_code")
    fun getSemestersFlow(accountId: Long): Flow<List<SemesterInfoEntity>>

    @Query("SELECT * FROM semester_infos WHERE account_id = :accountId ORDER BY academic_year, term_code")
    suspend fun getSemesters(accountId: Long): List<SemesterInfoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSemesters(semesters: List<SemesterInfoEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSemester(semester: SemesterInfoEntity): Long

    @Update
    suspend fun updateSemester(semester: SemesterInfoEntity)

    @Query("DELETE FROM semester_infos WHERE account_id = :accountId")
    suspend fun deleteSemesters(accountId: Long)

    @Query("SELECT * FROM semester_infos WHERE account_id = :accountId AND academic_year = :year AND term_code = :termCode")
    suspend fun getSemester(accountId: Long, year: String, termCode: String): SemesterInfoEntity?
}
```

- [ ] **Step 3: 创建 AccountSettingsDao**

```kotlin
// app/src/main/java/com/cherry/wakeupschedule/model/dao/AccountSettingsDao.kt
package com.cherry.wakeupschedule.model.dao

import androidx.room.*
import com.cherry.wakeupschedule.model.AccountSettingsEntity

@Dao
interface AccountSettingsDao {

    @Query("SELECT * FROM account_settings WHERE account_id = :accountId")
    suspend fun getSettings(accountId: Long): AccountSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSettings(settings: AccountSettingsEntity)

    @Query("DELETE FROM account_settings WHERE account_id = :accountId")
    suspend fun deleteSettings(accountId: Long)
}
```

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/cherry/wakeupschedule/model/dao/
git commit -m "feat: 新增多账号 DAO（AccountDao/SemesterInfoDao/AccountSettingsDao）"
```

---

### Task 4: 修改 CourseDao 支持 accountId

**Files:** Modify `model/CourseDao.kt`

- [ ] **Step 1: 更新 CourseDao 所有查询加 account_id**

```kotlin
// app/src/main/java/com/cherry/wakeupschedule/model/CourseDao.kt
package com.cherry.wakeupschedule.model

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseDao {

    @Query("SELECT * FROM courses WHERE account_id = :accountId ORDER BY day_of_week, start_time")
    fun getAllCoursesFlow(accountId: Long): Flow<List<Course>>

    @Query("SELECT * FROM courses WHERE account_id = :accountId ORDER BY day_of_week, start_time")
    suspend fun getAllCourses(accountId: Long): List<Course>

    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun getCourseById(id: Long): Course?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourse(course: Course): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourses(courses: List<Course>)

    @Update
    suspend fun updateCourse(course: Course)

    @Delete
    suspend fun deleteCourse(course: Course)

    @Query("DELETE FROM courses WHERE account_id = :accountId")
    suspend fun deleteAllCourses(accountId: Long)

    @Query("SELECT COUNT(*) FROM courses WHERE account_id = :accountId")
    suspend fun getCourseCount(accountId: Long): Int
}
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/cherry/wakeupschedule/model/CourseDao.kt
git commit -m "feat: CourseDao 查询按 account_id 过滤"
```

---

### Task 5: 更新 AppDatabase

**Files:** Modify `model/AppDatabase.kt`

- [ ] **Step 1: 更新 AppDatabase 注册所有实体和 DAO**

```kotlin
// app/src/main/java/com/cherry/wakeupschedule/model/AppDatabase.kt
package com.cherry.wakeupschedule.model

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.cherry.wakeupschedule.model.dao.AccountDao
import com.cherry.wakeupschedule.model.dao.AccountSettingsDao
import com.cherry.wakeupschedule.model.dao.SemesterInfoDao

@Database(
    entities = [
        Course::class,
        AccountEntity::class,
        AccountProfileEntity::class,
        SemesterInfoEntity::class,
        AccountSettingsEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun courseDao(): CourseDao
    abstract fun accountDao(): AccountDao
    abstract fun semesterInfoDao(): SemesterInfoDao
    abstract fun accountSettingsDao(): AccountSettingsDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "schedule.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/cherry/wakeupschedule/model/AppDatabase.kt
git commit -m "feat: AppDatabase 注册多账号实体及 DAO，版本升至 2"
```

---

## Phase 2: Repository 层

### Task 6: 实现 AccountRepository

**Files:** Create `service/AccountRepository.kt`

- [ ] **Step 1: 创建 AccountRepository**

```kotlin
// app/src/main/java/com/cherry/wakeupschedule/service/AccountRepository.kt
package com.cherry.wakeupschedule.service

import android.content.Context
import android.content.SharedPreferences
import com.cherry.wakeupschedule.model.*
import com.cherry.wakeupschedule.model.dao.AccountDao
import com.cherry.wakeupschedule.model.dao.AccountSettingsDao
import com.cherry.wakeupschedule.model.dao.SemesterInfoDao
import com.google.gson.Gson
import com.gxu.jwxt.JwxtClient
import com.gxu.jwxt.exceptions.LoginException
import com.gxu.jwxt.model.StudentProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class AccountRepository(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val accountDao: AccountDao = db.accountDao()
    private val profileDao: AccountDao = db.accountDao()  // profile operations in same DAO
    private val semesterDao: SemesterInfoDao = db.semesterInfoDao()
    private val settingsDao: AccountSettingsDao = db.accountSettingsDao()
    private val gson = Gson()

    companion object {
        private const val PREFS_NAME = "active_account"
        private const val KEY_ACTIVE_ID = "active_account_id"

        @Volatile
        private var instance: AccountRepository? = null

        fun getInstance(context: Context): AccountRepository {
            return instance ?: synchronized(this) {
                instance ?: AccountRepository(context.applicationContext as Context).also {
                    instance = it
                }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── 活跃账号 ──

    fun getActiveAccountId(): Long = prefs.getLong(KEY_ACTIVE_ID, -1L)

    fun setActiveAccountId(id: Long) {
        prefs.edit().putLong(KEY_ACTIVE_ID, id).apply()
    }

    fun hasActiveAccount(): Boolean = getActiveAccountId() > 0

    fun clearActiveAccount() {
        prefs.edit().remove(KEY_ACTIVE_ID).apply()
    }

    // ── 账号 CRUD ──

    suspend fun getAllAccounts(): List<AccountEntity> = accountDao.getAllAccounts()

    fun getAllAccountsFlow(): Flow<List<AccountEntity>> = accountDao.getAllAccountsFlow()

    suspend fun getAccountById(id: Long): AccountEntity? = accountDao.getAccountById(id)

    suspend fun getAccountByUsername(username: String): AccountEntity? =
        accountDao.getAccountByUsername(username)

    suspend fun getActiveAccount(): AccountEntity? {
        val id = getActiveAccountId()
        if (id <= 0) return null
        return accountDao.getAccountById(id)
    }

    suspend fun bindAccount(username: String, password: String): AccountEntity {
        // 检查是否已存在
        val existing = accountDao.getAccountByUsername(username)
        if (existing != null) {
            // 更新密码和活跃时间
            val updated = existing.copy(
                password = password,
                lastActive = System.currentTimeMillis()
            )
            accountDao.insertAccount(updated)
            setActiveAccountId(updated.id)
            return updated
        }
        val account = AccountEntity(
            username = username,
            password = password,
            boundAt = System.currentTimeMillis(),
            lastActive = System.currentTimeMillis()
        )
        val id = accountDao.insertAccount(account)
        val created = account.copy(id = id)
        setActiveAccountId(id)
        return created
    }

    suspend fun switchAccount(accountId: Long) {
        accountDao.updateLastActive(accountId, System.currentTimeMillis())
        setActiveAccountId(accountId)
    }

    suspend fun unbindAccount(accountId: Long) {
        val account = accountDao.getAccountById(accountId) ?: return

        // 调教务 logout
        try {
            withContext(Dispatchers.IO) {
                val client = JwxtClient(account.username, account.password)
                client.logout()
            }
        } catch (_: Exception) {
            // logout 失败不阻塞解绑流程
        }

        // 级联删除（Room CASCADE 已处理 profile/semesters/settings/courses）
        accountDao.deleteAccount(account)

        // 如果解绑的是活跃账号，切换为另一个或清除
        if (getActiveAccountId() == accountId) {
            clearActiveAccount()
            val remaining = accountDao.getAllAccounts()
            if (remaining.isNotEmpty()) {
                setActiveAccountId(remaining.first().id)
            }
        }
    }

    // ── 个人信息 ──

    suspend fun saveProfile(accountId: Long, profile: StudentProfile) {
        val gradeYear = profile.grade?.toIntOrNull() ?: 0
        val studyYears = profile.schoolingLength?.toIntOrNull() ?: 4
        val entity = AccountProfileEntity(
            accountId = accountId,
            profileJson = gson.toJson(profile),
            gradeYear = gradeYear,
            studyYears = studyYears,
            savedAt = System.currentTimeMillis()
        )
        profileDao.insertProfile(entity)
    }

    suspend fun getProfile(accountId: Long): StudentProfile? {
        val entity = profileDao.getProfile(accountId) ?: return null
        return try {
            gson.fromJson(entity.profileJson, StudentProfile::class.java)
        } catch (_: Exception) { null }
    }

    suspend fun getGradeYear(accountId: Long): Int {
        return profileDao.getProfile(accountId)?.gradeYear ?: 0
    }

    suspend fun getStudyYears(accountId: Long): Int {
        return profileDao.getProfile(accountId)?.studyYears ?: 4
    }

    // ── 学期信息 ──

    suspend fun saveSemesters(semesters: List<SemesterInfoEntity>) {
        semesterDao.insertSemesters(semesters)
    }

    suspend fun updateSemester(semester: SemesterInfoEntity) {
        semesterDao.updateSemester(semester)
    }

    suspend fun getSemesters(accountId: Long): List<SemesterInfoEntity> {
        return semesterDao.getSemesters(accountId)
    }

    fun getSemestersFlow(accountId: Long): Flow<List<SemesterInfoEntity>> {
        return semesterDao.getSemestersFlow(accountId)
    }

    suspend fun getSemester(accountId: Long, year: String, termCode: String): SemesterInfoEntity? {
        return semesterDao.getSemester(accountId, year, termCode)
    }

    /**
     * 根据 grade + schoolingLength 推算学期列表
     */
    fun generateSemesterList(gradeYear: Int, studyYears: Int): List<SemesterInfoEntity> {
        val labels = listOf("大一", "大二", "大三", "大四")
        val semesters = mutableListOf<SemesterInfoEntity>()
        for (i in 0 until studyYears.coerceAtMost(4)) {
            val year = gradeYear + i
            val yearStr = "${year}-${year + 1}学年"
            semesters.add(SemesterInfoEntity(
                accountId = 0,  // 调用方需设置
                academicYear = yearStr,
                termCode = "3",      // AUTUMN
                gradeLabel = labels[i]
            ))
            semesters.add(SemesterInfoEntity(
                accountId = 0,
                academicYear = yearStr,
                termCode = "12",     // SPRING
                gradeLabel = labels[i]
            ))
        }
        return semesters
    }

    // ── 账号设置 ──

    suspend fun getAccountSettings(accountId: Long): AccountSettings {
        val entity = settingsDao.getSettings(accountId) ?: return AccountSettings()
        return try {
            gson.fromJson(entity.settingsJson, AccountSettings::class.java)
        } catch (_: Exception) { AccountSettings() }
    }

    suspend fun saveAccountSettings(accountId: Long, settings: AccountSettings) {
        val entity = AccountSettingsEntity(
            accountId = accountId,
            settingsJson = gson.toJson(settings)
        )
        settingsDao.insertSettings(entity)
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/cherry/wakeupschedule/service/AccountRepository.kt
git commit -m "feat: 实现 AccountRepository（多账号 CRUD/切换/解绑/初始化）"
```

---

## Phase 3: Service 层改造

### Task 7: 改造 CourseDataManager 支持活跃账号

**Files:** Modify `service/CourseDataManager.kt`

- [ ] **Step 1: 重写 CourseDataManager**

```kotlin
// app/src/main/java/com/cherry/wakeupschedule/service/CourseDataManager.kt
package com.cherry.wakeupschedule.service

import android.content.Context
import com.cherry.wakeupschedule.model.AppDatabase
import com.cherry.wakeupschedule.model.Course
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CourseDataManager private constructor(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val dao = db.courseDao()

    private val _coursesFlow = MutableStateFlow<List<Course>>(emptyList())
    val coursesFlow: StateFlow<List<Course>> = _coursesFlow

    private val dbExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "course-db").apply { isDaemon = true }
    }
    private val scope = CoroutineScope(Dispatchers.IO)

    private var currentAccountId: Long = -1
    private var accountRepository: AccountRepository =
        AccountRepository.getInstance(context)

    init {
        // 初始化当前活跃账号
        currentAccountId = accountRepository.getActiveAccountId()
        if (currentAccountId > 0) {
            loadCoursesForAccount(currentAccountId)
        }
    }

    /**
     * 切换到新的活跃账号，重建 StateFlow
     */
    fun switchAccount(accountId: Long) {
        if (accountId == currentAccountId) return
        currentAccountId = accountId
        loadCoursesForAccount(accountId)
    }

    /**
     * 清除活跃账号（未绑定状态）
     */
    fun clearAccount() {
        currentAccountId = -1
        _coursesFlow.value = emptyList()
    }

    private fun loadCoursesForAccount(accountId: Long) {
        _coursesFlow.value = executeDb { dao.getAllCourses(accountId) }
        scope.launch {
            dao.getAllCoursesFlow(accountId).collect { courses ->
                _coursesFlow.value = courses
            }
        }
    }

    // ── 读操作 ──

    fun getAllCourses(): List<Course> = _coursesFlow.value

    fun getCoursesForWeek(week: Int): List<Course> {
        return _coursesFlow.value.filter { course ->
            val isInWeekRange = week in course.startWeek..course.endWeek
            val isWeekTypeMatch = when (course.weekType) {
                0 -> true; 1 -> week % 2 == 1; 2 -> week % 2 == 0; else -> true
            }
            isInWeekRange && isWeekTypeMatch
        }
    }

    fun getCoursesForDate(date: Calendar): List<Course> {
        val week = calculateWeekNumber(date)
        if (week <= 0) return emptyList()
        val coursesForWeek = getCoursesForWeek(week)
        val dayOfWeek = date.get(Calendar.DAY_OF_WEEK) - 1
        val adjustedDayOfWeek = if (dayOfWeek == 0) 7 else dayOfWeek
        return coursesForWeek.filter { it.dayOfWeek == adjustedDayOfWeek }
    }

    private fun calculateWeekNumber(date: Calendar): Int {
        // 从账号设置中获取学期开始日期
        val accountId = currentAccountId
        if (accountId <= 0) return -1
        val settingsManager = SettingsManager(context)
        val startDate = settingsManager.getSemesterStartDate(accountId)
        if (startDate == 0L) return -1
        val startCalendar = Calendar.getInstance().apply { timeInMillis = startDate }
        startCalendar.set(Calendar.HOUR_OF_DAY, 0)
        startCalendar.set(Calendar.MINUTE, 0)
        startCalendar.set(Calendar.SECOND, 0)
        startCalendar.set(Calendar.MILLISECOND, 0)
        val dateCopy = (date.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val diffInMillis = dateCopy.timeInMillis - startCalendar.timeInMillis
        if (diffInMillis < 0) return -1
        val daysDiff = TimeUnit.DAYS.convert(diffInMillis, TimeUnit.MILLISECONDS).toInt()
        return (daysDiff / 7) + 1
    }

    // ── 写操作 ──

    fun addCourse(course: Course): Course {
        val accountId = currentAccountId
        val colorIndex = assignColorIndex(course.name, course.teacher)
        val courseToInsert = course.copy(id = 0, accountId = accountId, color = colorIndex)
        val newId = executeDb { dao.insertCourse(courseToInsert) }
        return courseToInsert.copy(id = newId)
    }

    fun addCourses(courses: List<Course>) {
        val accountId = currentAccountId
        executeDb {
            dao.insertCourses(courses.map {
                it.copy(id = 0, accountId = accountId, color = assignColorIndex(it.name, it.teacher))
            })
        }
    }

    fun updateCourse(course: Course) {
        executeDb { dao.updateCourse(course) }
    }

    fun deleteCourse(course: Course) {
        executeDb { dao.deleteCourse(course) }
    }

    fun clearAllCourses() {
        val accountId = currentAccountId
        executeDb { dao.deleteAllCourses(accountId) }
    }

    fun replaceAllCourses(courses: List<Course>) {
        val accountId = currentAccountId
        executeDb {
            dao.deleteAllCourses(accountId)
            dao.insertCourses(courses.map {
                it.copy(id = 0, accountId = accountId, color = assignColorIndex(it.name, it.teacher))
            })
        }
    }

    private fun <T> executeDb(block: suspend () -> T): T {
        val future = dbExecutor.submit<T> {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) { block() }
        }
        return future.get()
    }

    companion object {
        @Volatile
        private var instance: CourseDataManager? = null

        const val COURSE_COLOR_COUNT = 9

        fun getInstance(context: Context): CourseDataManager {
            return instance ?: synchronized(this) {
                instance ?: CourseDataManager(context.applicationContext).also {
                    instance = it
                }
            }
        }

        fun assignColorIndex(name: String, teacher: String): Int {
            val key = "$name|$teacher"
            return (Math.abs(key.hashCode()) % COURSE_COLOR_COUNT) + 1
        }
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/cherry/wakeupschedule/service/CourseDataManager.kt
git commit -m "refactor: CourseDataManager 支持活跃账号切换"
```

---

### Task 8: 改造 SettingsManager

**Files:** Modify `service/SettingsManager.kt`

- [ ] **Step 1: 修改 SettingsManager（含内存缓存，保证同步读取可用）**

```kotlin
// app/src/main/java/com/cherry/wakeupschedule/service/SettingsManager.kt
package com.cherry.wakeupschedule.service

import android.content.Context
import android.content.SharedPreferences
import com.cherry.wakeupschedule.model.AccountSettings
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Calendar

class SettingsManager(context: Context) {

    private val gson = Gson()
    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private val accountRepo: AccountRepository by lazy {
        AccountRepository.getInstance(context)
    }

    // ── 内存缓存：确保同步调用方可读取账号设置 ──
    @Volatile
    private var cachedAccountSettings: AccountSettings? = null
    @Volatile
    private var cachedAccountId: Long = -1L

    /**
     * 切换活跃账号时调用，将账号设置加载到内存缓存。
     * 之后的同步 getter 直接从缓存读，无需协程。
     */
    fun loadAccountSettings(accountId: Long) {
        cachedAccountId = accountId
        cachedAccountSettings = kotlinx.coroutines.runBlocking {
            accountRepo.getAccountSettings(accountId)
        }
    }

    fun clearAccountCache() {
        cachedAccountSettings = null
        cachedAccountId = -1L
    }

    // ═══════════════════════════════════════════
    // 全局设置（SharedPreferences，跟账号无关）
    // ═══════════════════════════════════════════

    companion object {
        // 全局常量
        private const val KEY_THEME = "theme"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_COURSE_CARD_ALPHA = "course_card_alpha"
        private const val KEY_SHOW_NON_CURRENT_WEEK_COURSES = "show_non_current_week_courses"
        private const val KEY_NON_CURRENT_WEEK_ALPHA = "non_current_week_alpha"
        private const val KEY_VIEW_MODE = "view_mode"
        private const val KEY_CUSTOM_BACKGROUND_PATH = "custom_background_path"
        private const val KEY_FLOAT_BUTTON_X = "float_button_x"
        private const val KEY_FLOAT_BUTTON_Y = "float_button_y"
        private const val KEY_VIEW_STATE = "view_state"
        private const val KEY_AUTO_SWITCH_WEEK = "auto_switch_week"
        private const val KEY_DEFAULT_ALARM_MINUTES = "default_alarm_minutes"
        private const val KEY_HIDE_HOLIDAY_COURSES = "hide_holiday_courses"
        private const val KEY_ENABLE_UPDATE_REMIND = "enable_update_remind"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        private const val KEY_LAST_LOG_CLEAR = "last_log_clear"

        // 学期 key 带 account_id 后缀（旧 Key 保留兼容）
        // 新的按账号 key 在 getSemesterStartDateKey(accountId) 中生成
    }

    // 原有全局 getter/setter 保持不变...
    fun getTheme(): String = sharedPreferences.getString(KEY_THEME, "light") ?: "light"
    fun setTheme(theme: String) = sharedPreferences.edit().putString(KEY_THEME, theme).apply()
    fun getFontSize(): String = sharedPreferences.getString(KEY_FONT_SIZE, "normal") ?: "normal"
    fun setFontSize(fontSize: String) = sharedPreferences.edit().putString(KEY_FONT_SIZE, fontSize).apply()
    fun getCourseCardAlpha(): Float = sharedPreferences.getFloat(KEY_COURSE_CARD_ALPHA, 0.85f)
    fun setCourseCardAlpha(alpha: Float) = sharedPreferences.edit().putFloat(KEY_COURSE_CARD_ALPHA, alpha.coerceIn(0.2f, 1.0f)).apply()
    fun isShowNonCurrentWeekCourses(): Boolean = sharedPreferences.getBoolean(KEY_SHOW_NON_CURRENT_WEEK_COURSES, true)
    fun setShowNonCurrentWeekCourses(show: Boolean) = sharedPreferences.edit().putBoolean(KEY_SHOW_NON_CURRENT_WEEK_COURSES, show).apply()
    fun getNonCurrentWeekAlpha(): Float = sharedPreferences.getFloat(KEY_NON_CURRENT_WEEK_ALPHA, 0.3f)
    fun setNonCurrentWeekAlpha(alpha: Float) = sharedPreferences.edit().putFloat(KEY_NON_CURRENT_WEEK_ALPHA, alpha.coerceIn(0.1f, 0.8f)).apply()
    fun getViewMode(): String = sharedPreferences.getString(KEY_VIEW_MODE, "week") ?: "week"
    fun setViewMode(mode: String) = sharedPreferences.edit().putString(KEY_VIEW_MODE, mode).apply()
    fun getCustomBackgroundPath(): String = sharedPreferences.getString(KEY_CUSTOM_BACKGROUND_PATH, "") ?: ""
    fun setCustomBackgroundPath(path: String) = sharedPreferences.edit().putString(KEY_CUSTOM_BACKGROUND_PATH, path).apply()
    fun getFloatButtonX(): Float = sharedPreferences.getFloat(KEY_FLOAT_BUTTON_X, -1f)
    fun setFloatButtonX(x: Float) = sharedPreferences.edit().putFloat(KEY_FLOAT_BUTTON_X, x).apply()
    fun getFloatButtonY(): Float = sharedPreferences.getFloat(KEY_FLOAT_BUTTON_Y, -1f)
    fun setFloatButtonY(y: Float) = sharedPreferences.edit().putFloat(KEY_FLOAT_BUTTON_Y, y).apply()
    fun getViewState(): String = sharedPreferences.getString(KEY_VIEW_STATE, "week") ?: "week"
    fun setViewState(state: String) = sharedPreferences.edit().putString(KEY_VIEW_STATE, state).apply()
    fun getAutoSwitchWeek(): Boolean = sharedPreferences.getBoolean(KEY_AUTO_SWITCH_WEEK, true)
    fun setAutoSwitchWeek(autoSwitch: Boolean) = sharedPreferences.edit().putBoolean(KEY_AUTO_SWITCH_WEEK, autoSwitch).apply()
    fun getDefaultAlarmMinutes(): Int = sharedPreferences.getInt(KEY_DEFAULT_ALARM_MINUTES, 15)
    fun setDefaultAlarmMinutes(minutes: Int) = sharedPreferences.edit().putInt(KEY_DEFAULT_ALARM_MINUTES, minutes).apply()
    fun isHideHolidayCourses(): Boolean = sharedPreferences.getBoolean(KEY_HIDE_HOLIDAY_COURSES, false)
    fun setHideHolidayCourses(hide: Boolean) = sharedPreferences.edit().putBoolean(KEY_HIDE_HOLIDAY_COURSES, hide).apply()
    fun isUpdateRemindEnabled(): Boolean = sharedPreferences.getBoolean(KEY_ENABLE_UPDATE_REMIND, true)
    fun setUpdateRemindEnabled(enabled: Boolean) = sharedPreferences.edit().putBoolean(KEY_ENABLE_UPDATE_REMIND, enabled).apply()
    fun getLastUpdateCheckDate(): String? = sharedPreferences.getString(KEY_LAST_UPDATE_CHECK, null)
    fun setLastUpdateCheckDate(date: String) = sharedPreferences.edit().putString(KEY_LAST_UPDATE_CHECK, date).apply()
    fun isCheckedForUpdateToday(): Boolean {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        return today == getLastUpdateCheckDate()
    }
    fun markUpdateCheckedToday() {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        setLastUpdateCheckDate(today)
    }
    fun getLastLogClearDate(): String? = sharedPreferences.getString(KEY_LAST_LOG_CLEAR, null)
    fun setLastLogClearDate(date: String) = sharedPreferences.edit().putString(KEY_LAST_LOG_CLEAR, date).apply()
    fun needClearLogs(): Boolean {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        val lastClear = getLastLogClearDate() ?: return true
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        return try {
            val d1 = sdf.parse(lastClear)!!; val d2 = sdf.parse(today)!!
            val days = (d2.time - d1.time) / (1000 * 60 * 60 * 24)
            days >= 7
        } catch (_: Exception) { true }
    }
    fun markLogClearedToday() {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        setLastLogClearDate(today)
    }

    // ═══════════════════════════════════════════
    // 账号级设置（Room，绑定 account_id，内存缓存加速同步读取）
    // ═══════════════════════════════════════════

    private fun getCachedOrThrow(accountId: Long): AccountSettings {
        if (accountId == cachedAccountId) {
            return cachedAccountSettings ?: AccountSettings()
        }
        // 缓存未命中，同步回退
        val settings = kotlinx.coroutines.runBlocking { accountRepo.getAccountSettings(accountId) }
        cachedAccountId = accountId
        cachedAccountSettings = settings
        return settings
    }

    // ── 无感重载 ──

    fun isSilentReloginEnabled(accountId: Long): Boolean {
        return getCachedOrThrow(accountId).silentRelogin
    }
    suspend fun setSilentReloginEnabled(accountId: Long, enabled: Boolean) {
        val settings = getCachedOrThrow(accountId).copy(silentRelogin = enabled)
        accountRepo.saveAccountSettings(accountId, settings)
        cachedAccountSettings = settings
    }

    // ── 当前学期 ──

    fun getCurrentSemester(accountId: Long): String {
        val v = getCachedOrThrow(accountId).currentSemester
        return v.ifEmpty { getAutoDetectedSemester() }
    }
    suspend fun setCurrentSemester(accountId: Long, semester: String) {
        val settings = getCachedOrThrow(accountId).copy(currentSemester = semester)
        accountRepo.saveAccountSettings(accountId, settings)
        cachedAccountSettings = settings
    }

    // ── 闹钟启用 ──

    fun isAlarmEnabled(accountId: Long): Boolean {
        return getCachedOrThrow(accountId).alarmEnabled
    }
    suspend fun setAlarmEnabled(accountId: Long, enabled: Boolean) {
        val settings = getCachedOrThrow(accountId).copy(alarmEnabled = enabled)
        accountRepo.saveAccountSettings(accountId, settings)
        cachedAccountSettings = settings
    }

    // ── 学期开始日期 ──

    fun getSemesterStartDate(accountId: Long): Long {
        return getCachedOrThrow(accountId).semesterStartDate
    }
    suspend fun setSemesterStartDate(accountId: Long, dateMillis: Long) {
        val settings = getCachedOrThrow(accountId).copy(semesterStartDate = dateMillis)
        accountRepo.saveAccountSettings(accountId, settings)
        cachedAccountSettings = settings
    }

    // ── 总周数 ──

    fun getTotalWeeks(accountId: Long): Int {
        val v = getCachedOrThrow(accountId).totalWeeks
        return if (v > 0) v else 20
    }
    suspend fun setTotalWeeks(accountId: Long, weeks: Int) {
        val settings = getCachedOrThrow(accountId).copy(totalWeeks = weeks)
        accountRepo.saveAccountSettings(accountId, settings)
        cachedAccountSettings = settings
    }

    // ── 默认显示周 ──

    fun getDefaultWeek(accountId: Long): Int {
        return getCachedOrThrow(accountId).defaultWeek
    }
    suspend fun setDefaultWeek(accountId: Long, week: Int) {
        val settings = getCachedOrThrow(accountId).copy(defaultWeek = week)
        accountRepo.saveAccountSettings(accountId, settings)
        cachedAccountSettings = settings
    }

    // ── 自定义学期列表 ──

    suspend fun getCustomSemesters(accountId: Long): List<String> {
        return getAccountSettingsOrCreate(accountId).customSemesters.ifEmpty { getDefaultSemesters() }
    }
    suspend fun addCustomSemester(accountId: Long, semester: String) {
        val settings = getAccountSettingsOrCreate(accountId)
        val list = settings.customSemesters.toMutableList()
        if (!list.contains(semester)) { list.add(semester) }
        accountRepo.saveAccountSettings(accountId, settings.copy(customSemesters = list))
    }
    suspend fun removeCustomSemester(accountId: Long, semester: String) {
        val settings = getAccountSettingsOrCreate(accountId)
        val list = settings.customSemesters.filter { it != semester }
        accountRepo.saveAccountSettings(accountId, settings.copy(customSemesters = list))
    }
    private fun getDefaultSemesters(): List<String> {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val currentAcademicStart = if (month >= 9) year else year - 1
        val semesters = mutableListOf<String>()
        for (offset in -1 until 9) {
            val start = currentAcademicStart - offset
            semesters.add("${start}-${start + 1}学年 第一学期")
            semesters.add("${start}-${start + 1}学年 第二学期")
        }
        return semesters
    }

    // ═══════════════════════════════════════════
    // 兼容旧版（无 accountId 参数）— 已废弃，保留兼容
    // ═══════════════════════════════════════════

    @Deprecated("使用 getSemesterStartDate(accountId) 代替")
    fun getSemesterStartDate(): Long = sharedPreferences.getLong("semester_start_date", 0L)

    @Deprecated("使用 getCurrentSemester(accountId) 代替")
    fun getCurrentSemester(): String = sharedPreferences.getString("current_semester", getAutoDetectedSemester()) ?: getAutoDetectedSemester()
}
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/cherry/wakeupschedule/service/SettingsManager.kt
git commit -m "refactor: SettingsManager 全局/账号级设置分流"
```

---

### Task 9: 改造 JwxtAuthManager 支持多账号

**Files:** Modify `service/JwxtAuthManager.kt`

- [ ] **Step 1: 重写为支持多账号**

```kotlin
// app/src/main/java/com/cherry/wakeupschedule/service/JwxtAuthManager.kt
package com.cherry.wakeupschedule.service

import android.content.Context
import com.gxu.jwxt.JwxtClient
import com.gxu.jwxt.exceptions.LoginException
import com.gxu.jwxt.exceptions.SessionExpiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

object JwxtAuthManager {

    // accountId -> JwxtClient 缓存
    private val clients = ConcurrentHashMap<Long, JwxtClient>()

    private fun getOrCreateClient(accountId: Long, username: String, password: String): JwxtClient {
        return clients.getOrPut(accountId) {
            val c = JwxtClient(username, password)
            c.login()
            c
        }
    }

    fun destroyClient(accountId: Long) {
        clients.remove(accountId)
    }

    /**
     * 测试登录
     */
    suspend fun testLogin(username: String, password: String): Result<JwxtClient> =
        withContext(Dispatchers.IO) {
            try {
                val c = JwxtClient(username, password)
                c.login()
                Result.success(c)
            } catch (e: LoginException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(LoginException("登录失败: ${e.message}"))
            }
        }

    /**
     * 带自动 session 恢复的操作
     */
    suspend fun <T> doWithAuth(
        accountId: Long,
        username: String,
        password: String,
        action: (JwxtClient) -> T
    ): Result<T> = withContext(Dispatchers.IO) {
        try {
            val c = getOrCreateClient(accountId, username, password)
            Result.success(action(c))
        } catch (e: SessionExpiredException) {
            try {
                val c = getOrCreateClient(accountId, username, password)
                c.relogin()
                Result.success(action(c))
            } catch (reloginError: LoginException) {
                destroyClient(accountId)
                Result.failure(LoginException("登录已过期，请重新绑定教务账号"))
            }
        } catch (e: LoginException) {
            destroyClient(accountId)
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/cherry/wakeupschedule/service/JwxtAuthManager.kt
git commit -m "refactor: JwxtAuthManager 支持多账号 JwxtClient 缓存"
```

---

### Task 10: 改造 JwxtAccountManager

**Files:** Modify `service/JwxtAccountManager.kt`

- [ ] **Step 1: 改为委托 AccountRepository**

```kotlin
// app/src/main/java/com/cherry/wakeupschedule/service/JwxtAccountManager.kt
package com.cherry.wakeupschedule.service

import android.content.Context
import com.gxu.jwxt.model.StudentProfile

/**
 * 教务账号凭据管理（委托 AccountRepository）。
 * 保留原有接口兼容，内部改为多账号感知。
 */
object JwxtAccountManager {

    private lateinit var appContext: Context
    private val repo: AccountRepository by lazy { AccountRepository.getInstance(appContext) }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isBound(): Boolean = repo.hasActiveAccount()

    suspend fun getUsername(): String {
        return repo.getActiveAccount()?.username ?: ""
    }

    suspend fun getPassword(): String {
        return repo.getActiveAccount()?.password ?: ""
    }

    suspend fun getActiveAccountId(): Long = repo.getActiveAccountId()

    suspend fun saveCredentials(username: String, password: String) {
        repo.bindAccount(username, password)
    }

    fun clear() {
        // 多账号下不应全局清理，改为 no-op
        // 使用 AccountRepository.unbindAccount() 替代
    }

    fun getCachedProfile(): StudentProfile? {
        // 阻塞获取（供非 suspend 上下文使用）
        val accountId = repo.getActiveAccountId()
        if (accountId <= 0) return null
        return kotlinx.coroutines.runBlocking {
            repo.getProfile(accountId)
        }
    }

    suspend fun saveProfileCache(profile: StudentProfile) {
        val accountId = repo.getActiveAccountId()
        if (accountId > 0) {
            repo.saveProfile(accountId, profile)
        }
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/cherry/wakeupschedule/service/JwxtAccountManager.kt
git commit -m "refactor: JwxtAccountManager 委托 AccountRepository 实现多账号"
```

---

## Phase 4: ViewModel 层

### Task 11: 重写 CourseViewModel 支持初始化流程

**Files:** Modify `viewmodel/CourseViewModel.kt`

- [ ] **Step 1: 重写 CourseViewModel**

```kotlin
// app/src/main/java/com/cherry/wakeupschedule/viewmodel/CourseViewModel.kt
package com.cherry.wakeupschedule.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.cherry.wakeupschedule.App
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.model.SemesterInfoEntity
import com.cherry.wakeupschedule.service.*
import com.gxu.jwxt.model.Term
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 初始化进度状态
 */
data class InitProgress(
    val totalSteps: Int = 0,
    val completedSteps: Int = 0,
    val currentLabel: String = "",
    val isComplete: Boolean = false,
    val hasError: Boolean = false
)

class CourseViewModel(application: Application) : AndroidViewModel(application) {

    private val _courses = MutableLiveData<List<Course>>()
    val courses: LiveData<List<Course>> = _courses

    private val _initProgress = MutableStateFlow(InitProgress())
    val initProgress: StateFlow<InitProgress> = _initProgress

    private val _semesters = MutableStateFlow<List<SemesterInfoEntity>>(emptyList())
    val semesters: StateFlow<List<SemesterInfoEntity>> = _semesters

    var displayWeek: Int = 0
    var currentWeek: Int = 0

    private val repo = AccountRepository.getInstance(application)
    private val settingsManager = SettingsManager(application)
    private val alarmService = App.instance.alarmService

    @Volatile
    private var activeWeek: Int = calculateCurrentWeek()

    init {
        val accountId = repo.getActiveAccountId()
        if (accountId > 0) {
            loadSemestersForAccount(accountId)
        }
        viewModelScope.launch {
            CourseDataManager.getInstance(getApplication()).coursesFlow.collect { allCourses ->
                val week = activeWeek
                val accountId = repo.getActiveAccountId()
                val coursesForWeek = allCourses.filter { course ->
                    val isInWeekRange = week in course.startWeek..course.endWeek
                    val isWeekTypeMatch = when (course.weekType) {
                        0 -> true; 1 -> week % 2 == 1; 2 -> week % 2 == 0; else -> true
                    }
                    val match = isInWeekRange && isWeekTypeMatch
                    if (match && settingsManager.isHideHolidayCourses()) {
                        val startDate = settingsManager.getSemesterStartDate(accountId)
                        if (startDate > 0) {
                            val holidayManager = HolidayManager.getInstance(getApplication())
                            val cal = Calendar.getInstance().apply {
                                timeInMillis = startDate
                                add(Calendar.DAY_OF_YEAR, (week - 1) * 7 + course.dayOfWeek - 1)
                            }
                            !holidayManager.isHoliday(cal)
                        } else true
                    } else match
                }
                _courses.postValue(coursesForWeek)
            }
        }
    }

    // ── 初始化流程 ──

    fun startInitFlow(accountId: Long) {
        viewModelScope.launch {
            val account = repo.getAccountById(accountId) ?: return@launch
            try {
                _initProgress.value = InitProgress(
                    totalSteps = 0, completedSteps = 0, currentLabel = "正在获取个人信息..."
                )

                // Step 1: 登录 + 获取个人信息
                JwxtAuthManager.doWithAuth(accountId, account.username, account.password) { client ->
                    client.profile().profile()
                }.onSuccess { profile ->
                    repo.saveProfile(accountId, profile)
                    val gradeYear = profile.grade?.toIntOrNull() ?: 0
                    val studyYears = profile.schoolingLength?.toIntOrNull() ?: 4

                    // Step 2: 生成学期列表
                    val semesterList = repo.generateSemesterList(gradeYear, studyYears)
                        .map { it.copy(accountId = accountId) }
                    repo.saveSemesters(semesterList)

                    val totalSteps = 1 + semesterList.size
                    _initProgress.value = InitProgress(
                        totalSteps = totalSteps, completedSteps = 1,
                        currentLabel = "个人信息获取完成"
                    )

                    // Step 3: 逐个获取学期课表
                    var completed = 1
                    for (sem in semesterList) {
                        val year = sem.academicYear.substringBefore("-")
                        val termCode = sem.termCode
                        val term = Term.fromCode(termCode) ?: Term.AUTUMN

                        _initProgress.value = InitProgress(
                            totalSteps = totalSteps, completedSteps = completed,
                            currentLabel = "正在获取课表（${sem.gradeLabel}·${if (termCode == "3") "第一学期" else "第二学期"}）..."
                        )

                        val result = JwxtAuthManager.doWithAuth(accountId, account.username, account.password) { client ->
                            client.schedule().personal(year, term)
                        }

                        result.onSuccess { response ->
                            val (courses, startDate) = JwxtImportService.convertScheduleResponse(response)
                            if (courses.isNotEmpty()) {
                                // 存入 courses 表
                                val courseDataManager = CourseDataManager.getInstance(getApplication())
                                // 先删除该学期旧数据再插入
                                // 存入带 accountId 的课程
                                courseDataManager.addCourses(courses)
                                // 更新学期信息
                                val totalWeeks = courses.maxOfOrNull { it.endWeek } ?: 20
                                val updatedSem = sem.copy(
                                    startDate = startDate ?: 0,
                                    totalWeeks = totalWeeks,
                                    isDataLoaded = true
                                )
                                repo.updateSemester(updatedSem)
                            } else {
                                // 空学期，标记为已处理但无数据
                                repo.updateSemester(sem.copy(isDataLoaded = true))
                            }
                        }.onFailure {
                            // 失败跳过，保持 isDataLoaded = false
                        }

                        completed++
                    }

                    _initProgress.value = InitProgress(
                        totalSteps = totalSteps, completedSteps = completed,
                        currentLabel = "初始化完成", isComplete = true
                    )

                    // 刷新学期列表和课程
                    loadSemestersForAccount(accountId)
                    CourseDataManager.getInstance(getApplication()).switchAccount(accountId)

                    // 注册闹钟
                    alarmService?.registerAllCourseNotifications()
                }.onFailure { e ->
                    _initProgress.value = InitProgress(
                        currentLabel = "初始化失败: ${e.message}", hasError = true
                    )
                }
            } catch (e: Exception) {
                _initProgress.value = InitProgress(
                    currentLabel = "初始化失败: ${e.message}", hasError = true
                )
            }
        }
    }

    /**
     * 刷新单个学期课表
     */
    fun refreshSemester(semester: SemesterInfoEntity) {
        viewModelScope.launch {
            val accountId = semester.accountId
            val account = repo.getAccountById(accountId) ?: return@launch
            val year = semester.academicYear.substringBefore("-")
            val term = Term.fromCode(semester.termCode) ?: Term.AUTUMN

            _initProgress.value = InitProgress(
                totalSteps = 1, completedSteps = 0,
                currentLabel = "正在刷新课表（${semester.gradeLabel}·${if (semester.termCode == "3") "第一学期" else "第二学期"}）..."
            )

            JwxtAuthManager.doWithAuth(accountId, account.username, account.password) { client ->
                client.schedule().personal(year, term)
            }.onSuccess { response ->
                val (courses, startDate) = JwxtImportService.convertScheduleResponse(response)
                if (courses.isNotEmpty()) {
                    CourseDataManager.getInstance(getApplication()).addCourses(courses)
                }
                val totalWeeks = courses.maxOfOrNull { it.endWeek } ?: 20
                repo.updateSemester(semester.copy(
                    startDate = startDate ?: semester.startDate,
                    totalWeeks = totalWeeks,
                    isDataLoaded = true
                ))
                loadSemestersForAccount(accountId)
                _initProgress.value = _initProgress.value.copy(isComplete = true, currentLabel = "刷新完成")
            }.onFailure { e ->
                _initProgress.value = InitProgress(currentLabel = "刷新失败: ${e.message}", hasError = true)
            }
        }
    }

    /**
     * 刷新全部学期
     */
    fun refreshAllSemesters(accountId: Long) {
        viewModelScope.launch {
            val account = repo.getAccountById(accountId) ?: return@launch
            val semesters = repo.getSemesters(accountId)
            val totalSteps = semesters.size
            var completed = 0

            for (sem in semesters) {
                val year = sem.academicYear.substringBefore("-")
                val term = Term.fromCode(sem.termCode) ?: Term.AUTUMN

                _initProgress.value = InitProgress(
                    totalSteps = totalSteps, completedSteps = completed,
                    currentLabel = "正在刷新课表（${sem.gradeLabel}·${if (sem.termCode == "3") "第一学期" else "第二学期"}）..."
                )

                JwxtAuthManager.doWithAuth(accountId, account.username, account.password) { client ->
                    client.schedule().personal(year, term)
                }.onSuccess { response ->
                    val (courses, startDate) = JwxtImportService.convertScheduleResponse(response)
                    if (courses.isNotEmpty()) {
                        CourseDataManager.getInstance(getApplication()).addCourses(courses)
                    }
                    val totalWeeks = courses.maxOfOrNull { it.endWeek } ?: 20
                    repo.updateSemester(sem.copy(
                        startDate = startDate ?: sem.startDate,
                        totalWeeks = totalWeeks,
                        isDataLoaded = true
                    ))
                }
                completed++
                _initProgress.value = _initProgress.value.copy(completedSteps = completed)
            }

            loadSemestersForAccount(accountId)
            _initProgress.value = InitProgress(
                totalSteps = totalSteps, completedSteps = completed,
                currentLabel = "全部刷新完成", isComplete = true
            )
        }
    }

    // ── 学期管理 ──

    private fun loadSemestersForAccount(accountId: Long) {
        viewModelScope.launch {
            repo.getSemestersFlow(accountId).collect { list ->
                _semesters.value = list
            }
        }
    }

    // ── 课程操作 ──

    fun getAllCourses(): Flow<List<Course>> = flow {
        emit(CourseDataManager.getInstance(getApplication()).getAllCourses())
    }

    fun getCoursesByDay(dayOfWeek: Int): Flow<List<Course>> = flow {
        emit(CourseDataManager.getInstance(getApplication())
            .getAllCourses().filter { it.dayOfWeek == dayOfWeek })
    }

    fun loadCoursesForWeek(week: Int) {
        activeWeek = week
        viewModelScope.launch {
            val allCourses = CourseDataManager.getInstance(getApplication()).getAllCourses()
            val accountId = repo.getActiveAccountId()
            val coursesForWeek = allCourses.filter { course ->
                val isInWeekRange = week in course.startWeek..course.endWeek
                val isWeekTypeMatch = when (course.weekType) {
                    0 -> true; 1 -> week % 2 == 1; 2 -> week % 2 == 0; else -> true
                }
                if (!isInWeekRange || !isWeekTypeMatch) return@filter false
                if (settingsManager.isHideHolidayCourses()) {
                    val startDate = settingsManager.getSemesterStartDate(accountId)
                    if (startDate > 0) {
                        val holidayManager = HolidayManager.getInstance(getApplication())
                        val cal = Calendar.getInstance().apply {
                            timeInMillis = startDate
                            add(Calendar.DAY_OF_YEAR, (week - 1) * 7 + course.dayOfWeek - 1)
                        }
                        !holidayManager.isHoliday(cal)
                    } else true
                } else true
            }
            _courses.postValue(coursesForWeek)
        }
    }

    fun addCourse(course: Course) {
        viewModelScope.launch {
            val newCourse = CourseDataManager.getInstance(getApplication()).addCourse(course)
            alarmService?.setCourseAlarm(newCourse)
            withContext(Dispatchers.IO) { App.instance.registerAllCourseNotifications() }
        }
    }

    fun addCourses(courses: List<Course>) {
        viewModelScope.launch {
            CourseDataManager.getInstance(getApplication()).addCourses(courses)
            withContext(Dispatchers.IO) { App.instance.registerAllCourseNotifications() }
        }
    }

    fun updateCourse(course: Course) {
        viewModelScope.launch {
            CourseDataManager.getInstance(getApplication()).updateCourse(course)
            alarmService?.setCourseAlarm(course)
            withContext(Dispatchers.IO) { App.instance.registerAllCourseNotifications() }
        }
    }

    fun deleteCourse(course: Course) {
        viewModelScope.launch {
            CourseDataManager.getInstance(getApplication()).deleteCourse(course)
            alarmService?.cancelCourseAlarm(course)
        }
    }

    fun clearAllCourses() {
        viewModelScope.launch {
            CourseDataManager.getInstance(getApplication()).clearAllCourses()
        }
    }

    private fun calculateCurrentWeek(): Int {
        val accountId = repo.getActiveAccountId()
        // 同步读取（init 中调用）
        var startDate = 0L
        var totalWeeks = 20
        kotlinx.coroutines.runBlocking {
            startDate = settingsManager.getSemesterStartDate(accountId)
            totalWeeks = settingsManager.getTotalWeeks(accountId)
        }
        if (startDate <= 0L) return 1
        val diffMillis = System.currentTimeMillis() - startDate
        val diffDays = (diffMillis / (24 * 60 * 60 * 1000L)).toInt()
        return ((diffDays / 7) + 1).coerceIn(1, totalWeeks)
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/cherry/wakeupschedule/viewmodel/CourseViewModel.kt
git commit -m "feat: CourseViewModel 支持初始化流程/进度状态/学期刷新"
```

---

## Phase 5: Application 层

### Task 12: 改造 App.kt

**Files:** Modify `App.kt`

- [ ] **Step 1: 更新 App.kt 的初始化逻辑**

```kotlin
// 在 App.kt 的 onCreate() 中，将原来的 JwxtAccountManager.init(this) 替换为：

        // 初始化 AccountRepository（替代原来的 JwxtAccountManager.init）
        AccountRepository.getInstance(this)

        // ... 其余 NotificationHelper / CourseDataManager / AlarmService 初始化保持不变，
        // 但 alarmService 的 registerAllCourseNotifications 只在有活跃账号时执行
```

在 `registerAllCourseNotifications()` 中添加活跃账号检查：

```kotlin
fun registerAllCourseNotifications() {
    val accountId = AccountRepository.getInstance(this).getActiveAccountId()
    if (accountId <= 0) return  // 无活跃账号，跳过
    // ... 原有逻辑
}
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/cherry/wakeupschedule/App.kt
git commit -m "refactor: App.kt 多账号初始化适配"
```

---

## Phase 6: UI 层

### Task 13: 创建未绑定蒙版布局

**Files:** Create `res/layout/layout_unbound_mask.xml`

- [ ] **Step 1: 创建蒙版布局**

```xml
<!-- app/src/main/res/layout/layout_unbound_mask.xml -->
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:gravity="center"
    android:orientation="vertical"
    android:background="#CCFFFFFF"
    android:clickable="true"
    android:focusable="true">

    <ImageView
        android:layout_width="64dp"
        android:layout_height="64dp"
        android:src="@drawable/ic_empty"
        android:alpha="0.5" />

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:text="尚未绑定教务账号"
        android:textSize="18sp"
        android:textColor="@android:color/darker_gray" />

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:text="绑定后即可查看课表"
        android:textSize="14sp"
        android:textColor="@android:color/darker_gray"
        android:alpha="0.7" />

    <Button
        android:id="@+id/btn_bind_now"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="24dp"
        android:text="前往绑定" />
</LinearLayout>
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/res/layout/layout_unbound_mask.xml
git commit -m "feat: 新增未绑定蒙版布局"
```

---

### Task 14: 改造 ScheduleFragment 集成蒙版和初始化

**Files:** Modify `ui/screen/schedule/ScheduleFragment.kt`, `res/layout/fragment_schedule.xml`

- [ ] **Step 1: 在 fragment_schedule.xml 中添加蒙版容器**

在根布局中添加 FrameLayout 包裹蒙版：

```xml
    <!-- 在 fragment_schedule.xml 根布局最底部添加 -->
    <FrameLayout
        android:id="@+id/mask_container"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:visibility="gone" />
```

- [ ] **Step 2: 在 ScheduleFragment 中集成蒙版逻辑**

```kotlin
// ScheduleFragment.kt 中添加：

    private lateinit var maskContainer: FrameLayout
    private val accountRepo by lazy { AccountRepository.getInstance(requireContext()) }

    // onViewCreated 中：
    maskContainer = view.findViewById(R.id.mask_container)
    updateMaskVisibility()

    private fun updateMaskVisibility() {
        if (!accountRepo.hasActiveAccount()) {
            if (maskContainer.childCount == 0) {
                val mask = layoutInflater.inflate(R.layout.layout_unbound_mask, maskContainer, false)
                mask.findViewById<Button>(R.id.btn_bind_now).setOnClickListener {
                    startActivity(Intent(requireContext(), BindJwxtActivity::class.java))
                }
                maskContainer.addView(mask)
            }
            maskContainer.visibility = View.VISIBLE
        } else {
            maskContainer.visibility = View.GONE
        }
    }

    // onResume 中调用 updateMaskVisibility()
```

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/cherry/wakeupschedule/ui/screen/schedule/ScheduleFragment.kt
git add app/src/main/res/layout/fragment_schedule.xml
git commit -m "feat: 课表页集成未绑定蒙版"
```

---

### Task 15: 改造 BindJwxtActivity 支持多账号绑定

**Files:** Modify `BindJwxtActivity.kt`, `res/layout/activity_bind_jwxt.xml`

- [ ] **Step 1: 重写绑定逻辑**

```kotlin
// BindJwxtActivity.kt
package com.cherry.wakeupschedule

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cherry.wakeupschedule.service.AccountRepository
import com.cherry.wakeupschedule.service.JwxtAuthManager
import kotlinx.coroutines.launch

class BindJwxtActivity : AppCompatActivity() {

    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var tvStatus: TextView
    private lateinit var pbLoading: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bind_jwxt)

        etUsername = findViewById(R.id.et_username)
        etPassword = findViewById(R.id.et_password)
        btnLogin = findViewById(R.id.btn_login)
        tvStatus = findViewById(R.id.tv_status)
        pbLoading = findViewById(R.id.pb_loading)

        btnLogin.setOnClickListener {
            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (username.isEmpty() || password.isEmpty()) {
                tvStatus.text = "请输入学号和密码"
                tvStatus.visibility = View.VISIBLE
                return@setOnClickListener
            }

            doBind(username, password)
        }
    }

    private fun doBind(username: String, password: String) {
        tvStatus.visibility = View.GONE
        pbLoading.visibility = View.VISIBLE
        btnLogin.isEnabled = false

        lifecycleScope.launch {
            val result = JwxtAuthManager.testLogin(username, password)
            pbLoading.visibility = View.GONE
            btnLogin.isEnabled = true

            result.onSuccess { client ->
                val repo = AccountRepository.getInstance(this@BindJwxtActivity)
                val account = repo.bindAccount(username, password)
                // 跳转到初始化页面
                val intent = Intent(this@BindJwxtActivity, ScheduleActivity::class.java).apply {
                    putExtra("init_account_id", account.id)
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
                Toast.makeText(this@BindJwxtActivity, "绑定成功！正在同步数据...", Toast.LENGTH_SHORT).show()
                finish()
            }.onFailure { e ->
                tvStatus.text = e.message ?: "登录失败，请检查账号密码"
                tvStatus.visibility = View.VISIBLE
            }
        }
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/cherry/wakeupschedule/BindJwxtActivity.kt
git commit -m "feat: BindJwxtActivity 支持多账号绑定 + 跳转初始化"
```

---

### Task 16: 改造 SettingsActivity 添加账号信息卡片

**Files:** Modify `SettingsActivity.kt`, `res/layout/activity_settings.xml`

- [ ] **Step 1: 在设置页添加账号信息区域**

在 settings layout 中添加账号卡片：

```xml
    <!-- 在 activity_settings.xml 顶部添加 -->
    <LinearLayout
        android:id="@+id/ll_account_card"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="16dp"
        android:visibility="gone">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="账号信息"
            android:textSize="12sp"
            android:textColor="?attr/colorPrimary"
            android:alpha="0.7" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:layout_marginTop="8dp">

            <LinearLayout
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:orientation="vertical">

                <TextView
                    android:id="@+id/tv_account_display"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:textSize="16sp"
                    android:textStyle="bold" />

                <TextView
                    android:id="@+id/tv_account_detail"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:textSize="12sp"
                    android:alpha="0.6" />
            </LinearLayout>

            <Button
                android:id="@+id/btn_switch_account"
                style="@style/Widget.Material3.Button.OutlinedButton"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="切换"
                android:textSize="12sp" />

            <Button
                android:id="@+id/btn_unbind_account"
                style="@style/Widget.Material3.Button.TextButton"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="解绑"
                android:textColor="#FF0000"
                android:textSize="12sp"
                android:layout_marginStart="8dp" />
        </LinearLayout>
    </LinearLayout>
```

- [ ] **Step 2: 在 SettingsActivity 中实现账号卡片逻辑**

```kotlin
// SettingsActivity.kt 中添加：

    private lateinit var llAccountCard: LinearLayout
    private lateinit var tvAccountDisplay: TextView
    private lateinit var tvAccountDetail: TextView
    private lateinit var btnSwitchAccount: Button
    private lateinit var btnUnbindAccount: Button
    private val accountRepo by lazy { AccountRepository.getInstance(this) }

    // onCreate 中：
    llAccountCard = findViewById(R.id.ll_account_card)
    tvAccountDisplay = findViewById(R.id.tv_account_display)
    tvAccountDetail = findViewById(R.id.tv_account_detail)
    btnSwitchAccount = findViewById(R.id.btn_switch_account)
    btnUnbindAccount = findViewById(R.id.btn_unbind_account)

    refreshAccountCard()

    btnSwitchAccount.setOnClickListener { showSwitchAccountDialog() }
    btnUnbindAccount.setOnClickListener { showUnbindConfirmDialog() }

    private fun refreshAccountCard() {
        lifecycleScope.launch {
            val account = accountRepo.getActiveAccount()
            if (account != null) {
                llAccountCard.visibility = View.VISIBLE
                tvAccountDisplay.text = account.username
                val profile = accountRepo.getProfile(account.id)
                if (profile != null) {
                    tvAccountDetail.text = "${profile.college} · ${profile.major}"
                    tvAccountDisplay.text = "${profile.name} (${profile.studentId})"
                }
            } else {
                llAccountCard.visibility = View.GONE
            }
        }
    }

    private fun showSwitchAccountDialog() {
        lifecycleScope.launch {
            val accounts = accountRepo.getAllAccounts()
            val names = accounts.map { acc ->
                val profile = accountRepo.getProfile(acc.id)
                if (profile != null) "${profile.name} (${acc.username})" else acc.username
            }.toTypedArray()

            AlertDialog.Builder(this@SettingsActivity)
                .setTitle("切换账号")
                .setItems(names) { _, which ->
                    lifecycleScope.launch {
                        val selected = accounts[which]
                        accountRepo.switchAccount(selected.id)
                        CourseDataManager.getInstance(this@SettingsActivity).switchAccount(selected.id)
                        refreshAccountCard()
                        // 触发课表初始化（每次都从教务刷新）
                        val intent = Intent(this@SettingsActivity, ScheduleActivity::class.java).apply {
                            putExtra("init_account_id", selected.id)
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        startActivity(intent)
                    }
                }
                .setNeutralButton("添加新账号") { _, _ ->
                    startActivity(Intent(this@SettingsActivity, BindJwxtActivity::class.java))
                }
                .show()
        }
    }

    private fun showUnbindConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("确认解绑")
            .setMessage("将清除此账号的所有数据：\n· 个人信息\n· 课表数据\n· 学期设置\n\n此操作不可撤销。")
            .setPositiveButton("确认解绑") { _, _ ->
                lifecycleScope.launch {
                    val accountId = accountRepo.getActiveAccountId()
                    if (accountId > 0) {
                        // 先取消闹钟
                        App.instance.alarmService?.cancelAllReminders()
                        accountRepo.unbindAccount(accountId)
                        val newActiveId = accountRepo.getActiveAccountId()
                        if (newActiveId > 0) {
                            CourseDataManager.getInstance(this@SettingsActivity).switchAccount(newActiveId)
                        } else {
                            CourseDataManager.getInstance(this@SettingsActivity).clearAccount()
                        }
                        refreshAccountCard()
                        Toast.makeText(this@SettingsActivity, "已解绑", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
```

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/cherry/wakeupschedule/SettingsActivity.kt
git add app/src/main/res/layout/activity_settings.xml
git commit -m "feat: 设置页添加账号信息卡片（切换/解绑）"
```

---

### Task 17: 改造 ProfileActivity 展示缓存个人信息

**Files:** Modify `ProfileActivity.kt`

- [ ] **Step 1: 展示缓存的 StudentProfile**

```kotlin
// ProfileActivity.kt 中添加：

    private val accountRepo by lazy { AccountRepository.getInstance(this) }

    // onCreate 中检查：
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val accountId = accountRepo.getActiveAccountId()
            if (accountId > 0) {
                val profile = accountRepo.getProfile(accountId)
                if (profile != null) {
                    // 展示个人信息
                    // tvName.text = profile.name
                    // tvStudentId.text = profile.studentId
                    // tvCollege.text = profile.college
                    // 等等...
                    showProfile(profile)
                } else {
                    showEmptyState()
                }
            } else {
                // 未绑定 → 蒙版 + 跳转按钮
                showUnboundMask()
            }
        }
    }
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/cherry/wakeupschedule/ProfileActivity.kt
git commit -m "feat: ProfileActivity 展示缓存个人信息（不刷新）"
```

---

### Task 18: 改造 WeekPageFragment 适配账号隔离

**Files:** Modify `ui/screen/schedule/WeekPageFragment.kt`

- [ ] **Step 1: 添加账号检查**

```kotlin
// WeekPageFragment.kt 的 onViewCreated 中，在 buildSchedule 前检查：

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val repo = AccountRepository.getInstance(requireContext())
        if (!repo.hasActiveAccount()) {
            // 无活跃账号 → 不渲染课表
            view.findViewById<LinearLayout>(R.id.layout_empty)?.visibility = View.GONE
            return
        }

        val dataManager = CourseDataManager.getInstance(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            dataManager.coursesFlow.collectLatest {
                buildSchedule(view)
            }
        }
    }
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/cherry/wakeupschedule/ui/screen/schedule/WeekPageFragment.kt
git commit -m "feat: WeekPageFragment 适配账号隔离"
```

---

## Phase 7: 闹钟 & 小组件

### Task 19: 改造 AlarmService 适配活跃账号

**Files:** Modify `service/AlarmService.kt`

- [ ] **Step 1: 添加活跃账号检查**

```kotlin
// AlarmService.kt 的 registerAllCourseNotifications() 开头添加：

    fun registerAllCourseNotifications() {
        val accountId = AccountRepository.getInstance(context).getActiveAccountId()
        if (accountId <= 0) {
            Log.d("AlarmService", "无活跃账号，跳过闹钟注册")
            return
        }
        // ... 原有逻辑，但 CourseDataManager.getAllCourses() 已经按账号过滤
    }
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/cherry/wakeupschedule/service/AlarmService.kt
git commit -m "feat: AlarmService 仅活跃账号课程注册闹钟"
```

---

### Task 20: 改造小组件适配活跃账号

**Files:** Modify `widget/ScheduleWidgetProvider.kt`, `widget/MinimalWidgetProvider.kt`, `widget/UpcomingDaysWidgetProvider.kt`

- [ ] **Step 1: 小组件数据读取跟随活跃账号**

```kotlin
// 小组件 onUpdate / triggerUpdate 中：
    val repo = AccountRepository.getInstance(context)
    if (!repo.hasActiveAccount()) {
        // 展示空状态
        views.setTextViewText(R.id.tv_course_list, "请先绑定教务账号")
        appWidgetManager.updateAppWidget(appWidgetId, views)
        return
    }
    // ... 原有逻辑读取 CourseDataManager，课程已按账号过滤
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/cherry/wakeupschedule/widget/
git commit -m "feat: 桌面小组件跟随活跃账号"
```

---

### Task 21: 最终集成与清理

**Files:** 全局搜索引用修复

- [ ] **Step 1: 全局搜索编译错误并修复**

```bash
./gradlew assembleDebug
```

修复所有因接口变更导致的编译错误（SettingsManager 调用、JwxtAccountManager 引用等）。

- [ ] **Step 2: 提交**

```bash
git add -A
git commit -m "fix: 修复多账号重构后的编译错误和接口适配"
```

- [ ] **Step 3: 验证构建通过**

```bash
./gradlew assembleDebug
# 预期：BUILD SUCCESSFUL
git commit --allow-empty -m "chore: 多账号重构构建验证通过"
```
