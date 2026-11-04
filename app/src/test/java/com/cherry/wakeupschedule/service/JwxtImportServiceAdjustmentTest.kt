package com.cherry.wakeupschedule.service

import com.cherry.wakeupschedule.model.Course
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.gxu.jwxt.model.ClassScheduleResponse
import com.gxu.jwxt.model.ScheduleAdjustment
import com.gxu.jwxt.model.ScheduleResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2 调课标志导入测试（纯 JVM）：用「教学班调课记录」把调课精确匹配到具体课次/周次。
 *
 * 背景：v1 按教学班级标志 `jxbsftkbj` 给整门课所有行打「调课」标签，导致
 * 「某周课被调，整学期都显示调课」。v2 的 [Course.adjustWeeksBitmap] 只记录
 * 真正被调整的周次。
 *
 * 匹配规则（与 Lead 用真实抓包在 Python 里跑通的结果一致）：
 * - 只匹配同一教学班（jxb_id）的记录与课表行，跳过停课（无现时间）；
 * - 候选行：现星期 == 行星期、现节次 == 行节次、且现周次位图 ∩ 行周次位图非空；
 * - 记录带现教师/现教室时优先取对得上的行，否则取周次交集最大的行（并列都取）；
 * - 匹配不到任何行就**不标**（宁缺勿错）。
 *
 * 下列样例结构取自 2026-11-04 真实账号抓包（课程/教师名做了脱敏改写，
 * 时间、周次、匹配关系保持真实）。
 */
class JwxtImportServiceAdjustmentTest {

    private val gson = Gson()

    // ── JSON 构造工具 ─────────────────────────────────────────────

    /** 一条 kbList 理论课表行 */
    private fun kbRow(
        classId: String,
        teacher: String,
        day: String,
        period: String,
        weeks: String,
        room: String = "教室",
        name: String = "数据库原理",
        flag: String = "1",
    ): String =
        """{"kcmc":"$name","jxbmc":"$name-0001","jxb_id":"$classId","xm":"$teacher","cdmc":"$room","xqj":"$day","jc":"$period","jcs":"${period.replace("节", "")}","zcd":"$weeks","jxbsftkbj":"$flag"}"""

    /** 一条 sjkList 实践课行（没有星期/节次，只有起止教学周） */
    private fun practiceRow(
        classId: String,
        teacher: String,
        weeks: String,
        name: String = "数据结构综合实践",
    ): String =
        """{"kcmc":"$name","jxbmc":"$name-0001","jxb_id":"$classId","jsxm":"$teacher","sfsjk":"1","qsjsz":"$weeks","jxbsftkbj":"1"}"""

    /** 个人课表响应；visible 对应顶层 xskbsfxstkzt */
    private fun schedule(
        vararg rows: String,
        visible: String = "1",
        practiceRows: List<String> = emptyList(),
    ): String =
        """{"xskbsfxstkzt":"$visible","kbList":[${rows.joinToString(",")}],"sjkList":[${practiceRows.joinToString(",")}]}"""

    /** 一条调课记录；停课不传 current 系列即可 */
    private fun adjustRecord(
        classId: String,
        type: String,
        yzcd: String? = null,
        yxqj: String? = null,
        yjc: String? = null,
        yjsxm: String? = null,
        ycdmc: String? = null,
        xzcd: String? = null,
        xxqj: String? = null,
        xjc: String? = null,
        xjsxm: String? = null,
        xcdmc: String? = null,
        remark: String? = null,
    ): String {
        val fields = mutableListOf<String>()
        fun add(key: String, value: String?) {
            if (value != null) fields.add("\"$key\":\"$value\"")
        }
        add("jxb_id", classId)
        add("tklxmc", type)
        add("yzcd", yzcd)
        add("yxqj", yxqj)
        add("yjc", yjc)
        add("yjsxm", yjsxm)
        add("ycdmc", ycdmc)
        add("xzcd", xzcd)
        add("xxqj", xxqj)
        add("xjc", xjc)
        add("xjsxm", xjsxm)
        add("xcdmc", xcdmc)
        add("tksm", remark)
        add("xnm", "2026")
        add("xqm", "3")
        return fields.joinToString(",", "{", "}")
    }

    /** 教学班 ID → 调课记录列表 的 JSON（与 fetch 流程交给 convert 的结构一致） */
    private fun adjustmentMap(vararg entries: Pair<String, List<String>>): String =
        entries.joinToString(",", "{", "}") { (classId, records) ->
            "\"$classId\":[${records.joinToString(",")}]"
        }

    // ── 断言工具 ─────────────────────────────────────────────────

    private fun coursesOf(scheduleJson: String, adjustmentsJson: String = "{}"): List<Course> {
        val response = gson.fromJson(scheduleJson, ScheduleResponse::class.java)
        val records: Map<String, List<ScheduleAdjustment>> = gson.fromJson(
            adjustmentsJson,
            object : TypeToken<Map<String, List<ScheduleAdjustment>>>() {}.type,
        )
        return JwxtImportService.convertScheduleResponse(response, records).first
    }

    private fun find(
        courses: List<Course>,
        teacher: String,
        day: Int,
        start: Int,
        room: String,
    ): Course = courses.single {
        it.teacher == teacher && it.dayOfWeek == day && it.startTime == start && it.classroom == room
    }

    private fun weeksOf(course: Course): List<Int> = Course.bitmapToWeekList(course.adjustWeeksBitmap)

    private fun adjustedRows(courses: List<Course>): List<Course> = courses.filter { it.isAdjusted }

    // ══════════ 基准 1：2026-2027 第1学期（当前学期） ══════════

    @Test
    fun `当前学期_只给实际被调课的那一行那一周打标`() {
        // 真实结构：同一教学班两行，龙紫璐覆盖 1-4,6-8(双),9-17 周（不含第7周），
        // 胡刚是第7周被调来的那一行；调课记录 原7周一7-8龙紫璐 → 现7周一7-8胡刚。
        val scheduleJson = schedule(
            kbRow("JXB-0024", "龙紫璐", "1", "7-8节", "1-4周,6-8周(双),9-17周", "西2-501", "大学生就业与创业指导"),
            kbRow("JXB-0024", "胡刚", "1", "7-8节", "第7周", "西2-501", "大学生就业与创业指导"),
        )
        val adjustmentsJson = adjustmentMap(
            "JXB-0024" to listOf(
                adjustRecord(
                    "JXB-0024", "调课",
                    yzcd = "7周", yxqj = "一", yjc = "第7-8节", yjsxm = "龙紫璐", ycdmc = "西2-501",
                    xzcd = "7周", xxqj = "一", xjc = "第7-8节", xjsxm = "胡刚", xcdmc = "西2-501",
                )
            )
        )

        val courses = coursesOf(scheduleJson, adjustmentsJson)
        assertEquals(2, courses.size)

        val hu = find(courses, "胡刚", 1, 7, "西2-501")
        assertTrue("被调来的那一行应标记调课", hu.isAdjusted)
        assertEquals(Course.bitmapFromRange(7, 7), hu.adjustWeeksBitmap)
        assertEquals(listOf(7), weeksOf(hu))
        assertTrue(hu.isAdjustedInWeek(7))
        assertFalse("第6周没有调课", hu.isAdjustedInWeek(6))
        assertFalse("第8周没有调课", hu.isAdjustedInWeek(8))

        val long = find(courses, "龙紫璐", 1, 7, "西2-501")
        assertFalse("原教师行不应被标记（v1 的整班误标已修复）", long.isAdjusted)
        assertEquals(0L, long.adjustWeeksBitmap)

        assertEquals(1, adjustedRows(courses).size)
    }

    @Test
    fun `同一时段多行都覆盖该周时_按现教师精确选中被调课行`() {
        // 构造：龙紫璐行也覆盖第 7 周（真实数据里不含，这里用来验证教师消歧规则）
        val scheduleJson = schedule(
            kbRow("JXB-0024", "龙紫璐", "1", "7-8节", "1-17周", "西2-501", "大学生就业与创业指导"),
            kbRow("JXB-0024", "胡刚", "1", "7-8节", "第7周", "西2-501", "大学生就业与创业指导"),
        )
        val adjustmentsJson = adjustmentMap(
            "JXB-0024" to listOf(
                adjustRecord(
                    "JXB-0024", "调课",
                    yzcd = "7周", yxqj = "一", yjc = "第7-8节", yjsxm = "龙紫璐", ycdmc = "西2-501",
                    xzcd = "7周", xxqj = "一", xjc = "第7-8节", xjsxm = "胡刚", xcdmc = "西2-501",
                )
            )
        )

        val courses = coursesOf(scheduleJson, adjustmentsJson)
        assertTrue(find(courses, "胡刚", 1, 7, "西2-501").isAdjusted)
        assertFalse(
            "两行都覆盖第7周时，现教师能对上的胡刚行优先，龙紫璐行不标",
            find(courses, "龙紫璐", 1, 7, "西2-501").isAdjusted,
        )
        assertEquals(1, adjustedRows(courses).size)
    }

    // ══════════ 基准 2：2025-2026 第2学期（大二下） ══════════

    @Test
    fun `大二下_数据库原理与形势与政策_只标记补课调课实际出现的行`() {
        // 数据库原理两个教学班 + 形势与政策：停课 4/6周二3-4（无对应行）、补课 4周日11-12、
        // 补课 5周四3-4、调课 13周四→14周二、补课 6周日11-12、形势与政策 12-13 周六5-8 换教师。
        val scheduleJson = schedule(
            // 数据库原理-0001混合式教学班
            kbRow("JXB-MIX", "蒙祖强", "2", "3-4节", "2-3周,5-9周(单),10-13周", "西综合308", "数据库原理"),
            kbRow("JXB-MIX", "蒙祖强", "4", "3-4节", "第5周", "东10B-404", "数据库原理"),
            kbRow("JXB-MIX", "蒙祖强", "4", "9-10节", "2-11周", "雨课堂1", "数据库原理"),
            kbRow("JXB-MIX", "蒙祖强", "7", "11-12节", "第4周", "东10B-404", "数据库原理"),
            // 数据库原理-0001A
            kbRow("JXB-A", "蒙祖强", "2", "3-4节", "第14周", "计电202", "数据库原理"),
            kbRow("JXB-A", "蒙祖强", "4", "3-4节", "7-12周", "西综合313", "数据库原理"),
            kbRow("JXB-A", "蒙祖强", "7", "11-12节", "第6周", "西综合313", "数据库原理"),
            // 形势与政策（4）
            kbRow("JXB-XS", "蓝波涛,金顺南,赵双良", "6", "5-8节", "12-13周", "大礼堂", "形势与政策（4）"),
        )
        val adjustmentsJson = adjustmentMap(
            "JXB-MIX" to listOf(
                // 补课：第4周 周日 11-12节 东10B-404
                adjustRecord(
                    "JXB-MIX", "补课",
                    xzcd = "4周", xxqj = "日", xjc = "第11-12节", xjsxm = "蒙祖强", xcdmc = "东10B-404",
                ),
                // 停课：第4周 周二 3-4节（课表里本来就没有这行，跳过）
                adjustRecord(
                    "JXB-MIX", "停课",
                    yzcd = "4周", yxqj = "二", yjc = "第3-4节", yjsxm = "蒙祖强", ycdmc = "西综合308",
                ),
                // 停课：第6周 周二 3-4节
                adjustRecord(
                    "JXB-MIX", "停课",
                    yzcd = "6周", yxqj = "二", yjc = "第3-4节", yjsxm = "蒙祖强", ycdmc = "西综合308",
                ),
                // 补课：第5周 周四 3-4节 东10B-404
                adjustRecord(
                    "JXB-MIX", "补课",
                    xzcd = "5周", xxqj = "四", xjc = "第3-4节", xjsxm = "蒙祖强", xcdmc = "东10B-404",
                ),
            ),
            "JXB-A" to listOf(
                // 调课：13周 周四 3-4 → 14周 周二 3-4 计电202
                adjustRecord(
                    "JXB-A", "调课",
                    yzcd = "13周", yxqj = "四", yjc = "第3-4节", yjsxm = "蒙祖强", ycdmc = "西综合313",
                    xzcd = "14周", xxqj = "二", xjc = "第3-4节", xjsxm = "蒙祖强", xcdmc = "计电202",
                ),
                // 停课：第6周 周二 3-4节
                adjustRecord(
                    "JXB-A", "停课",
                    yzcd = "6周", yxqj = "二", yjc = "第3-4节", yjsxm = "蒙祖强", ycdmc = "西综合313",
                ),
                // 补课：第6周 周日 11-12节 西综合313
                adjustRecord(
                    "JXB-A", "补课",
                    xzcd = "6周", xxqj = "日", xjc = "第11-12节", xjsxm = "蒙祖强", xcdmc = "西综合313",
                ),
            ),
            "JXB-XS" to listOf(
                // 调课：12-13周 周六 5-8 仅教师变更（熊浩→李响）
                adjustRecord(
                    "JXB-XS", "调课",
                    yzcd = "12-13周", yxqj = "六", yjc = "第5-8节", yjsxm = "熊浩", ycdmc = "大礼堂",
                    xzcd = "12-13周", xxqj = "六", xjc = "第5-8节", xjsxm = "李响", xcdmc = "大礼堂",
                ),
                // 另一条同时间记录（李响→蓝波涛），周次与上一行取并集
                adjustRecord(
                    "JXB-XS", "调课",
                    yzcd = "12-13周", yxqj = "六", yjc = "第5-8节", yjsxm = "李响", ycdmc = "大礼堂",
                    xzcd = "12-13周", xxqj = "六", xjc = "第5-8节", xjsxm = "蓝波涛", xcdmc = "大礼堂",
                ),
            ),
        )

        val courses = coursesOf(scheduleJson, adjustmentsJson)
        assertEquals(8, courses.size)
        assertEquals("大二下真实结构应恰好标 5 行", 5, adjustedRows(courses).size)

        // 1) 数据库原理 星期二3-4 第14周 计电202 → {14}
        assertEquals(listOf(14), weeksOf(find(courses, "蒙祖强", 2, 3, "计电202")))
        // 2) 数据库原理 星期四3-4 第5周 东10B-404 → {5}
        assertEquals(listOf(5), weeksOf(find(courses, "蒙祖强", 4, 3, "东10B-404")))
        // 3) 形势与政策（4） 星期六5-8 12-13周 大礼堂 → {12,13}（两条记录并集）
        assertEquals(listOf(12, 13), weeksOf(find(courses, "蓝波涛,金顺南,赵双良", 6, 5, "大礼堂")))
        // 4) 数据库原理 星期日11-12 第4周 东10B-404 → {4}
        assertEquals(listOf(4), weeksOf(find(courses, "蒙祖强", 7, 11, "东10B-404")))
        // 5) 数据库原理 星期日11-12 第6周 西综合313 → {6}
        assertEquals(listOf(6), weeksOf(find(courses, "蒙祖强", 7, 11, "西综合313")))

        // 停课对应的原时间行、以及无关行都不标
        assertFalse("停课没有现时间行，不标", find(courses, "蒙祖强", 2, 3, "西综合308").isAdjusted)
        assertFalse(find(courses, "蒙祖强", 4, 9, "雨课堂1").isAdjusted)
        assertFalse(find(courses, "蒙祖强", 4, 3, "西综合313").isAdjusted)

        // 按周判断的语义：形势与政策只在 12/13 周被调课
        val xs = find(courses, "蓝波涛,金顺南,赵双良", 6, 5, "大礼堂")
        assertTrue(xs.isAdjustedInWeek(12))
        assertTrue(xs.isAdjustedInWeek(13))
        assertFalse(xs.isAdjustedInWeek(11))
        assertFalse(xs.isAdjustedInWeek(14))
    }

    // ══════════ 基准 3：2025-2026 第1学期（乒乓球）══════════

    @Test
    fun `乒乓球_匹配不到现时间行的调课记录不标记_能匹配的按周标记`() {
        val scheduleJson = schedule(
            kbRow("JXB-PP", "刘鲁奇", "5", "7-8节", "5-6周,8-10周(双),11-13周(单),14-16周", "北乒乓球1", "乒乓球"),
            kbRow("JXB-PP", "刘鲁奇", "6", "7-8节", "第8周", "北乒乓球1", "乒乓球"),
            kbRow("JXB-PP", "刘鲁奇", "6", "9-10节", "12-13周", "雨课堂1", "乒乓球"),
            kbRow("JXB-PP", "刘鲁奇", "7", "7-8节", "第14周", "北乒乓球1", "乒乓球"),
            kbRow("JXB-PP", "刘鲁奇", "4", "9-10节", "12-13周", "北乒乓球1", "乒乓球"),
        )
        val adjustmentsJson = adjustmentMap(
            "JXB-PP" to listOf(
                // 调课：原13周 周六7-8 → 现14周 周日7-8 → 命中「日7-8 第14周」行
                adjustRecord(
                    "JXB-PP", "调课",
                    yzcd = "13周", yxqj = "六", yjc = "第7-8节", yjsxm = "刘鲁奇", ycdmc = "北乒乓球1",
                    xzcd = "14周", xxqj = "日", xjc = "第7-8节", xjsxm = "刘鲁奇", xcdmc = "北乒乓球1",
                ),
                // 调课：原7周 周五7-8 → 现8周 周六7-8（现教师与行教师不同 → 退回周次交集匹配）
                adjustRecord(
                    "JXB-PP", "调课",
                    yzcd = "7周", yxqj = "五", yjc = "第7-8节", yjsxm = "邹华", ycdmc = "北乒乓球1",
                    xzcd = "8周", xxqj = "六", xjc = "第7-8节", xjsxm = "邹华", xcdmc = "北乒乓球1",
                ),
                // 调课：原12周 周五7-8 → 现13周 周六7-8；kbList 里没有「六7-8 第13周」行 → 不标
                adjustRecord(
                    "JXB-PP", "调课",
                    yzcd = "12周", yxqj = "五", yjc = "第7-8节", yjsxm = "刘鲁奇", ycdmc = "北乒乓球1",
                    xzcd = "13周", xxqj = "六", xjc = "第7-8节", xjsxm = "刘鲁奇", xcdmc = "北乒乓球1",
                ),
            )
        )

        val courses = coursesOf(scheduleJson, adjustmentsJson)
        assertEquals(2, adjustedRows(courses).size)
        assertEquals(listOf(8), weeksOf(find(courses, "刘鲁奇", 6, 7, "北乒乓球1")))
        assertEquals(listOf(14), weeksOf(find(courses, "刘鲁奇", 7, 7, "北乒乓球1")))
        // 匹配不到现时间行的记录不标记
        assertFalse(find(courses, "刘鲁奇", 5, 7, "北乒乓球1").isAdjusted)
        assertFalse(find(courses, "刘鲁奇", 6, 9, "雨课堂1").isAdjusted)
        assertFalse(find(courses, "刘鲁奇", 4, 9, "北乒乓球1").isAdjusted)
    }

    // ══════════ 停课 / 关闭开关 / 无记录 / 实践课 ══════════

    @Test
    fun `只有停课记录时全部不标且不崩`() {
        val scheduleJson = schedule(
            kbRow("JXB-DS", "田阳", "4", "3-4节", "7-12周", "6B-103", "数据结构"),
            kbRow("JXB-DS", "田阳", "5", "3-4节", "7-12周", "计电202", "数据结构"),
        )
        val adjustmentsJson = adjustmentMap(
            "JXB-DS" to listOf(
                adjustRecord(
                    "JXB-DS", "停课",
                    yzcd = "13周", yxqj = "四", yjc = "第3-4节", yjsxm = "田阳", ycdmc = "6B-103",
                )
            )
        )

        val courses = coursesOf(scheduleJson, adjustmentsJson)
        assertEquals(2, courses.size)
        assertTrue(adjustedRows(courses).isEmpty())
    }

    @Test
    fun `顶层不显示调课状态时即使有调课记录也不标`() {
        val scheduleJson = schedule(
            kbRow("JXB-0024", "胡刚", "1", "7-8节", "第7周", "西2-501", "大学生就业与创业指导"),
            visible = "0",
        )
        val adjustmentsJson = adjustmentMap(
            "JXB-0024" to listOf(
                adjustRecord(
                    "JXB-0024", "调课",
                    xzcd = "7周", xxqj = "一", xjc = "第7-8节", xjsxm = "胡刚", xcdmc = "西2-501",
                )
            )
        )

        val courses = coursesOf(scheduleJson, adjustmentsJson)
        assertFalse(courses.single().isAdjusted)
        assertEquals(0L, courses.single().adjustWeeksBitmap)
    }

    @Test
    fun `没有调课记录时即使教学班带调课标志也不标（v1 语义已废弃）`() {
        val scheduleJson = schedule(
            kbRow("JXB-0024", "胡刚", "1", "7-8节", "第7周", "西2-501", "大学生就业与创业指导", flag = "1"),
        )

        // 不传记录 map（等价于调课记录接口失败/无记录）
        val courses = coursesOf(scheduleJson)
        assertFalse("没有具体调课记录时不再按 jxbsftkbj 整行打标", courses.single().isAdjusted)
        assertEquals(0L, courses.single().adjustWeeksBitmap)

        // 传了空 map 也一样
        val withEmptyMap = coursesOf(scheduleJson, "{}")
        assertFalse(withEmptyMap.single().isAdjusted)
    }

    @Test
    fun `实践课条目不参与调课匹配`() {
        val scheduleJson = schedule(
            kbRow("JXB-P", "田阳", "1", "1-2节", "1-16周", "6B-103", "数据结构"),
            practiceRows = listOf(practiceRow("JXB-P", "田阳", "1-16周")),
        )
        val adjustmentsJson = adjustmentMap(
            "JXB-P" to listOf(
                adjustRecord(
                    "JXB-P", "补课",
                    xzcd = "4周", xxqj = "日", xjc = "第11-12节", xjsxm = "田阳", xcdmc = "6B-103",
                )
            )
        )

        val courses = coursesOf(scheduleJson, adjustmentsJson)
        assertEquals(2, courses.size)
        val practice = courses.single { it.isPractice }
        assertFalse(practice.isAdjusted)
        assertEquals(0L, practice.adjustWeeksBitmap)
        assertFalse(practice.isAdjustedInWeek(4))
    }

    @Test
    fun `班级课表响应没有调课记录_一律不标`() {
        val json =
            """{"kbList":[${kbRow("JXB-A", "蒙祖强", "2", "3-4节", "第14周", "计电202", "数据库原理")}]}"""
        val classResponse = gson.fromJson(json, ClassScheduleResponse::class.java)
        val courses = JwxtImportService.convertClassScheduleResponse(classResponse).first

        assertEquals(1, courses.size)
        assertFalse(courses.single().isAdjusted)
        assertEquals(0L, courses.single().adjustWeeksBitmap)
    }

    @Test
    fun `isAdjustedInWeek 只命中被调课的周且越界周返回 false`() {
        val course = Course(
            name = "数据库原理",
            teacher = "蒙祖强",
            classroom = "计电202",
            dayOfWeek = 2,
            startTime = 3,
            endTime = 4,
            adjustWeeksBitmap = Course.bitmapFromRange(1, 1) or Course.bitmapFromRange(64, 64),
        )

        assertTrue(course.isAdjustedInWeek(1))
        assertTrue(course.isAdjustedInWeek(64))
        assertFalse(course.isAdjustedInWeek(2))
        assertFalse(course.isAdjustedInWeek(0))
        assertFalse(course.isAdjustedInWeek(65))
    }
}
