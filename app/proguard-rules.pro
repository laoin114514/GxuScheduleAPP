# ─────────────────────────────────────────────────────────────
# 西大课栈 R8 规则
#
# 本项目启用 minifyEnabled + shrinkResources，反射面只有两处：
#   1. Gson —— 反序列化教务 JSON / 本地歌曲式配置，按字段名反射
#   2. Navigation —— nav_graph.xml 按类名字符串反射实例化 Fragment
# Room 生成的 *_Impl 由 room-runtime 自带的 consumer 规则覆盖，
# 此处只做兜底加固。
# ─────────────────────────────────────────────────────────────

# ── 属性保留 ──
# Gson 的 TypeToken<List<Course>> 依赖 Signature 泛型信息，删掉会导致
# 泛型擦除后退化成 LinkedTreeMap
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# ── Gson TypeToken ──
# R8 会把「同形状」的匿名 TypeToken 子类做类合并（线上 v0.5.7 release 包
# 刷新课表闪退的根因）：合并后泛型签名丢失，TypeToken 构造时抛
# "Missing type parameter."，静态初始化直接崩。这里禁止合并/混淆所有
# TypeToken 子类；代码侧也尽量改用 TypeToken.getParameterized(...) 或数组反序列化。
-keep class * extends com.google.gson.reflect.TypeToken { *; }
-keep class com.google.gson.reflect.TypeToken { *; }

# ── Gson 反射面 ──
# 教务客户端模型全部由 Gson 按字段名反序列化，字段名不能混淆
-keep class com.gxu.jwxt.model.** { *; }
-keep class com.gxu.jwxt.model.*$* { *; }

# Course 既是 Room 实体也是 Gson 的 DTO（CourseDataManager 的备份导入）
-keep class com.cherry.wakeupschedule.model.** { *; }

# TimeTableManager 用 TypeToken<List<TimeSlot>> 读时间表配置
-keep class com.cherry.wakeupschedule.service.TimeTableManager$TimeSlot { *; }

# 通用兜底：任何标了 @SerializedName 的字段保留其名字映射
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ── Navigation ──
# nav_graph.xml 里的 android:name 是字符串，R8 看不到引用关系。
# FragmentNavigator 最终走 Class.newInstance()，所以必须保名 + 保无参构造。
-keep class com.cherry.wakeupschedule.ui.screen.** extends androidx.fragment.app.Fragment {
    <init>();
}

# ── Room ──
# Room 通过 Class.forName("<Database>_Impl") 拿实现类
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# ── jsoup ──
# jsoup 对可选编码/网络库有编译期依赖，运行时不存在，忽略告警
-dontwarn org.jsoup.**
-dontwarn javax.annotation.**
