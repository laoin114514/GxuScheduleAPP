package com.cherry.wakeupschedule.service

import com.gxu.jwxt.module.ScheduleAdjustmentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「查看教学班调课记录」弹层 HTML 解析测试。
 *
 * 真实页面结构（2026-11-04 抓包，已脱敏）形如：
 * ```
 * var modelList= eval([{"ttkxx_id":"...","jxb_id":"...","tklxmc":"调课",...}]);
 * ```
 * 解析器需要先截出 `eval(...)` 里的 JSON 数组（括号配对时跳过字符串内的括号/转义），
 * 再交给 Gson；结构缺失或畸形时降级为空列表，不能抛异常。
 */
class ScheduleAdjustmentParserTest {

    private fun html(modelList: String): String = """
        <!DOCTYPE html>
        <html>
        <head><meta charset="utf-8"><title>调课记录</title></head>
        <body>
        <script type="text/javascript">
            jQuery(function(){
                var modelList= eval($modelList);
                var SubGrid = jQuery.extend(true, {}, TempSubGrid, {data:modelList});
                jQuery("#subGrid").loadJqGrid(SubGrid);
            });
        </script>
        </body>
        </html>
    """.trimIndent()

    @Test
    fun `解析调课记录字段_含中文与 null 字段`() {
        val records = ScheduleAdjustmentParser.parse(
            html(
                """
                [{"ttkxx_id":"5DF3E4D2358F6AB8E063020410ACFA11",
                  "ttk_id":"5DF3D3AC22BB2021E063030410ACBDB0",
                  "jxb_id":"544B045E1BAFB76AE063030410AC38A3",
                  "kcmc":"大学生就业与创业指导","jxbmc":"大学生就业与创业指导-0024",
                  "tklxmc":"调课","tklxdm":"01",
                  "yzcd":"7周","yxqj":"一","yjc":"第7-8节",
                  "yjsxm":"龙紫璐","ycdmc":"西2-501",
                  "xzcd":"7周","xxqj":"一","xjc":"第7-8节",
                  "xjsxm":"胡刚","xcdmc":"西2-501",
                  "yylb":"政治公务","tkyy":"因龙老师参加区国创赛工作与课程冲突",
                  "tksm":null,"xnm":"2026","xqm":"3"}]
                """.trimIndent()
            )
        )

        assertEquals(1, records.size)
        val r = records[0]
        assertEquals("544B045E1BAFB76AE063030410AC38A3", r.classId)
        assertEquals("大学生就业与创业指导", r.courseName)
        assertEquals("大学生就业与创业指导-0024", r.className)
        assertEquals("调课", r.adjustmentTypeName)
        assertEquals("01", r.adjustmentTypeCode)
        assertEquals("7周", r.originalWeeks)
        assertEquals("一", r.originalWeekday)
        assertEquals("第7-8节", r.originalPeriod)
        assertEquals("龙紫璐", r.originalTeacher)
        assertEquals("西2-501", r.originalClassroom)
        assertEquals("7周", r.currentWeeks)
        assertEquals("一", r.currentWeekday)
        assertEquals("第7-8节", r.currentPeriod)
        assertEquals("胡刚", r.currentTeacher)
        assertEquals("西2-501", r.currentClassroom)
        assertEquals("政治公务", r.reasonCategory)
        assertNull("tksm 为 null 时应保持 null", r.remark)
        assertEquals("2026", r.schoolYear)
        assertEquals("3", r.term)
    }

    @Test
    fun `解析停课与补课记录_现字段为 null 时按停课识别`() {
        val records = ScheduleAdjustmentParser.parse(
            html(
                """
                [{"jxb_id":"J1","kcmc":"数据库原理","tklxmc":"停课","tklxdm":"03",
                  "yzcd":"6周","yxqj":"二","yjc":"第3-4节","yjsxm":"蒙祖强","ycdmc":"西综合308",
                  "xzcd":null,"xxqj":null,"xjc":null,"xjsxm":null,"xcdmc":null,
                  "xnm":"2025","xqm":"12"},
                 {"jxb_id":"J1","kcmc":"数据库原理","tklxmc":"补课","tklxdm":"02",
                  "yzcd":null,"yxqj":null,"yjc":null,"yjsxm":null,"ycdmc":null,
                  "xzcd":"5周","xxqj":"四","xjc":"第3-4节","xjsxm":"蒙祖强","xcdmc":"东10B-404",
                  "xnm":"2025","xqm":"12"}]
                """.trimIndent()
            )
        )

        assertEquals(2, records.size)
        assertTrue("停课记录应识别为 isCancellation", records[0].isCancellation)
        assertNull(records[0].currentWeeks)
        assertEquals("停课", records[0].adjustmentTypeName)
        assertEquals("6周", records[0].originalWeeks)

        assertEquals(false, records[1].isCancellation)
        assertEquals("补课", records[1].adjustmentTypeName)
        assertEquals("5周", records[1].currentWeeks)
        assertNull(records[1].originalWeeks)
    }

    @Test
    fun `字符串里的方括号与转义引号不影响括号配对`() {
        val records = ScheduleAdjustmentParser.parse(
            html(
                """
                [{"jxb_id":"J1","tklxmc":"调课","tkyy":"因参加[临时]会议调整 \"课程时间\"",
                  "xzcd":"12-13周","xxqj":"六","xjc":"第5-8节","xjsxm":"李响","xcdmc":"大礼堂"}]
                """.trimIndent()
            )
        )

        assertEquals(1, records.size)
        assertEquals("因参加[临时]会议调整 \"课程时间\"", records[0].reason)
        assertEquals("12-13周", records[0].currentWeeks)
    }

    @Test
    fun `空 HTML 与 null 返回空列表`() {
        assertTrue(ScheduleAdjustmentParser.parse(null).isEmpty())
        assertTrue(ScheduleAdjustmentParser.parse("").isEmpty())
        assertTrue(ScheduleAdjustmentParser.parse("   ").isEmpty())
    }

    @Test
    fun `页面没有 modelList 时返回空列表`() {
        assertTrue(
            ScheduleAdjustmentParser.parse("<html><body>暂无调课记录</body></html>").isEmpty()
        )
        // 有 modelList 字样但没有 eval([...]) 的赋值形态
        assertTrue(
            ScheduleAdjustmentParser.parse(
                "<script>var modelList = [];\nvar modelListEmpty = true;</script>"
            ).isEmpty()
        )
    }

    @Test
    fun `modelList 数组畸形或括号不配对时返回空列表而不抛异常`() {
        // 括号不配对
        assertTrue(
            ScheduleAdjustmentParser.parse(
                html("""[{"jxb_id":"J1","tklxmc":"调课"}""")
            ).isEmpty()
        )
        // 括号配对但 JSON 语法错误
        assertTrue(
            ScheduleAdjustmentParser.parse(
                html("""[{"jxb_id":}]""")
            ).isEmpty()
        )
    }
}
