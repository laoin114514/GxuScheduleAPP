package com.gxu.jwxt.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gxu.jwxt.model.ScheduleAdjustment;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link ScheduleAdjustmentParser} 的纯 HTML 解析测试（不触网）。
 *
 * <p>重点覆盖「数组反序列化」这条路径：线上 v0.5.7 的 release 包因为匿名
 * {@code TypeToken<List<...>>} 被 R8 类合并、泛型签名丢失，一刷新课表就在
 * 解析调课记录时闪退。这里改用 {@code ScheduleAdjustment[].class} 反序列化，
 * 不依赖泛型签名；本测试保证换实现后解析结果仍然正确。</p>
 */
class ScheduleAdjustmentParserTest {

    @Test
    void parsesModelListFromEvalLiteral() {
        String html = "<html><script type=\"text/javascript\">\n"
            + "var modelList= eval([\n"
            + "{\"ttkxx_id\":\"1001\",\"jxb_id\":\"JXB-1\",\"kcmc\":\"高等数学A(上)\","
            + "\"tklxmc\":\"调课\",\"tklxdm\":\"01\",\"yzcd\":\"7周\",\"yxqj\":\"一\","
            + "\"yjc\":\"第1-2节\",\"xzcd\":\"8周\",\"xxqj\":\"二\",\"xjc\":\"第3-4节\"},\n"
            + "{\"ttkxx_id\":\"1002\",\"jxb_id\":\"JXB-2\",\"kcmc\":\"大学英语(三)\","
            + "\"tklxmc\":\"停课\",\"tklxdm\":\"03\",\"yzcd\":\"9周\",\"yxqj\":\"三\"}\n"
            + "]);\n"
            + "</script></html>";

        List<ScheduleAdjustment> records = ScheduleAdjustmentParser.parse(html);

        assertEquals(2, records.size());
        ScheduleAdjustment first = records.get(0);
        assertEquals("高等数学A(上)", first.getCourseName());
        assertEquals("调课", first.getAdjustmentTypeName());
        assertEquals("7周", first.getOriginalWeeks());
        assertEquals("8周", first.getCurrentWeeks());
        // 停课记录只有原字段，现字段为 null
        ScheduleAdjustment second = records.get(1);
        assertEquals("停课", second.getAdjustmentTypeName());
        assertEquals("9周", second.getOriginalWeeks());
        assertNull(second.getCurrentWeeks());
    }

    @Test
    void bracketsInsideJsonStringsDoNotBreakMatching() {
        // 调课说明里带方括号：括号配对必须跳过字符串内部的 '[' ']'
        String html = "var modelList= eval([{\"ttkxx_id\":\"1\",\"kcmc\":\"课程[实验]\","
            + "\"tklxmc\":\"调课\",\"tksm\":\"教室调整[临时]\"}]);";

        List<ScheduleAdjustment> records = ScheduleAdjustmentParser.parse(html);

        assertEquals(1, records.size());
        assertEquals("课程[实验]", records.get(0).getCourseName());
        assertEquals("教室调整[临时]", records.get(0).getRemark());
    }

    @Test
    void returnsEmptyWhenModelListMissing() {
        assertTrue(ScheduleAdjustmentParser.parse("<html>no records here</html>").isEmpty());
        assertTrue(ScheduleAdjustmentParser.parse("").isEmpty());
        assertTrue(ScheduleAdjustmentParser.parse(null).isEmpty());
    }

    @Test
    void returnsEmptyForMalformedJsonInsteadOfThrowing() {
        String html = "var modelList= eval([{\"ttkxx_id\":\"1\",,}]);";
        assertTrue(ScheduleAdjustmentParser.parse(html).isEmpty());
    }
}
