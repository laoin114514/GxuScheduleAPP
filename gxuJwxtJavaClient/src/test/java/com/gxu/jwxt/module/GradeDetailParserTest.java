package com.gxu.jwxt.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gxu.jwxt.model.GradeDetail;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** {@link GradeDetailParser} 的纯 HTML 解析测试（不触网）。 */
class GradeDetailParserTest {

    // ── 真实页面结构（2026-09 实测抓取，值已脱敏，DOM 结构保持一致）──

    @Test
    void parsesRealBreakdownPage() throws IOException {
        GradeDetail detail = GradeDetailParser.parse(fixture("detail_breakdown.html"), "兜底课程名");

        // 页面回显的课程名优先于兜底值
        assertEquals("示例课程甲", detail.getCourseName());
        assertEquals("88", detail.getUsualScore());
        assertEquals("76", detail.getFinalScore());
        assertEquals("80", detail.getTotalScore());
        assertEquals("40", detail.getUsualRatio());
        assertEquals("60", detail.getFinalRatio());
        // 页面未设置达标线时留空
        assertNull(detail.getPassLine());
        assertTrue(detail.hasBreakdown());
        assertEquals(3, detail.getRows().size());
        assertEquals("平时成绩", detail.getRows().get(0).getLabel());
    }

    @Test
    void parsesRealTotalOnlyPage() throws IOException {
        GradeDetail detail = GradeDetailParser.parse(fixture("detail_total_only.html"), "兜底课程名");

        assertEquals("示例课程乙", detail.getCourseName());
        assertEquals("85", detail.getTotalScore());
        // 无分项拆分：平时/期末应为空，而不是被误填
        assertNull(detail.getUsualScore());
        assertNull(detail.getFinalScore());
        assertFalse(detail.hasBreakdown());
        assertEquals("0", detail.getPassLine());
    }

    @Test
    void prefersExactTotalOverTotalComponent() {
        // 实测「军事理论」：总评成绩(100%) 93.1，但最终总评与列表成绩都是 93
        String html = "<html><body><table id=\"subtab\"><tbody>"
            + "<tr><td>【 总评成绩 】</td><td>100%&nbsp;</td><td>93.1&nbsp;</td></tr>"
            + "<tr><td>【 总评 】</td><td>&nbsp;</td><td>93&nbsp;</td></tr>"
            + "</tbody></table></body></html>";

        GradeDetail detail = GradeDetailParser.parse(html, "军事理论");

        assertEquals("93", detail.getTotalScore());
        assertFalse(detail.hasBreakdown());
    }

    @Test
    void parsesDecimalScores() {
        String html = "<html><body><table id=\"subtab\"><thead><tr>"
            + "<td>成绩分项</td><td>成绩分项比例</td><td>成绩</td></tr></thead><tbody>"
            + "<tr><td>【 平时成绩 】</td><td>60%&nbsp;</td><td>98.6&nbsp;</td></tr>"
            + "<tr><td>【 期末成绩 】</td><td>40%&nbsp;</td><td>100&nbsp;</td></tr>"
            + "<tr><td>【 总评 】</td><td>&nbsp;</td><td>99&nbsp;</td></tr>"
            + "</tbody></table></body></html>";

        GradeDetail detail = GradeDetailParser.parse(html, "线性代数");

        assertEquals("98.6", detail.getUsualScore());
        assertEquals("100", detail.getFinalScore());
        assertEquals("99", detail.getTotalScore());
        assertEquals("60", detail.getUsualRatio());
        assertEquals("40", detail.getFinalRatio());
    }

    // ── 合成用例与降级路径 ──────────────────────────────────

    @Test
    void parsesTwoColumnFallbackLayout() {
        String html = "<html><body><table>"
            + "<tr><td>平时成绩</td><td>88</td></tr>"
            + "<tr><td>期末成绩</td><td>76</td></tr>"
            + "<tr><td>总评成绩</td><td>80</td></tr>"
            + "<tr><td>平时成绩占比</td><td>30%</td></tr>"
            + "<tr><td>期末成绩占比</td><td>70%</td></tr>"
            + "</table></body></html>";

        GradeDetail detail = GradeDetailParser.parse(html, "计算机网络原理");

        assertEquals("计算机网络原理", detail.getCourseName());
        assertEquals("88", detail.getUsualScore());
        assertEquals("76", detail.getFinalScore());
        assertEquals("80", detail.getTotalScore());
        assertEquals("30", detail.getUsualRatio());
        assertEquals("70", detail.getFinalRatio());
        assertTrue(detail.hasBreakdown());
    }

    @Test
    void parsesInlineRatioInsideSingleCell() {
        String html = "<div><span>平时成绩(30%)</span><span>82</span>"
            + "<span>期末成绩(70%)</span><span>91</span></div>";

        GradeDetail detail = GradeDetailParser.parse(html, "大学物理");

        assertEquals("30", detail.getUsualRatio());
        assertEquals("70", detail.getFinalRatio());
    }

    @Test
    void skipsHeaderRowWhereAllCellsAreLabels() {
        // 表头行「平时成绩 | 期末成绩 | 总评成绩」不能被当成 平时=期末成绩 的数据
        String html = "<table><tr><td>平时成绩</td><td>期末成绩</td><td>总评成绩</td></tr>"
            + "<tr><td>88</td><td>76</td><td>80</td></tr></table>";

        GradeDetail detail = GradeDetailParser.parse(html, "线性代数");

        assertNull(detail.getUsualScore());
        assertNull(detail.getFinalScore());
        assertNull(detail.getTotalScore());
    }

    @Test
    void handlesBlankAndMalformedHtml() {
        GradeDetail blank = GradeDetailParser.parse(null, "高等数学");
        assertEquals("高等数学", blank.getCourseName());
        assertFalse(blank.hasBreakdown());

        // 会话过期返回登录页：能解析但拿不到成绩字段，不应崩溃
        GradeDetail loginPage = GradeDetailParser.parse(
            "<html><body><form><input name=\"csrftoken\"/><input name=\"yhm\"/></form></body></html>",
            "高等数学");
        assertEquals("高等数学", loginPage.getCourseName());
        assertFalse(loginPage.hasBreakdown());
        assertNull(loginPage.getPassLine());
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = GradeDetailParserTest.class.getResourceAsStream("/grade-detail/" + name)) {
            assertNotNull(in, "缺少测试夹具: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
