package com.gxu.jwxt.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.gxu.jwxt.JwxtClient;
import com.gxu.jwxt.model.GradeDetail;
import com.gxu.jwxt.model.GradeEntry;
import com.gxu.jwxt.model.PageQuery;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 成绩详情接口的真实链路测试：登录 → 拉成绩列表 → 逐条查详情 → 解析。
 *
 * <p>凭据只从环境变量读取，未设置时自动跳过，不影响默认 {@code ./gradlew test}：</p>
 * <pre>
 * JWXT_USERNAME=... JWXT_PASSWORD=... ./gradlew test --tests "com.gxu.jwxt.module.GradeDetailIntegrationTest"
 * </pre>
 */
class GradeDetailIntegrationTest {

    /**
     * 采样条数：默认 6 条，够覆盖「有分项拆分」与「无分项拆分」两种形态。
     * 可用 {@code JWXT_GRADE_DETAIL_SAMPLE} 调大以做全量校验。
     */
    private static final int SAMPLE = sampleSize();

    private static int sampleSize() {
        String raw = System.getenv("JWXT_GRADE_DETAIL_SAMPLE");
        if (raw == null || raw.isBlank()) return 6;
        try {
            return Math.max(1, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return 6;
        }
    }

    @Test
    void fetchesRealGradeDetails() throws Exception {
        String username = System.getenv("JWXT_USERNAME");
        String password = System.getenv("JWXT_PASSWORD");
        assumeTrue(username != null && !username.isBlank() && password != null && !password.isBlank(),
            "未设置 JWXT_USERNAME / JWXT_PASSWORD，跳过真实接口测试");

        JwxtClient client = new JwxtClient(username, password);
        client.login();
        try {
            List<GradeEntry> entries = client.grades().all(new PageQuery(1, 100)).getItems();
            assertFalse(entries.isEmpty(), "成绩列表不应为空");

            List<GradeEntry> sampled = new ArrayList<>();
            for (GradeEntry entry : entries) {
                if (entry.getClassId() != null && !entry.getClassId().isBlank()
                    && entry.getStudentId() != null && !entry.getStudentId().isBlank()) {
                    sampled.add(entry);
                }
                if (sampled.size() >= SAMPLE) break;
            }
            assertFalse(sampled.isEmpty(), "找不到带 jxb_id / xh_id 的成绩条目");

            int withTotal = 0;
            for (GradeEntry entry : sampled) {
                GradeDetail detail = client.grades().detail(entry);
                assertNotNull(detail, "detail 不应为 null: " + entry.getCourseName());
                // 详情页回显课程名，应与列表一致
                assertEquals(entry.getCourseName(), detail.getCourseName(),
                    "课程名回显不一致: " + detail);

                if (detail.getTotalScore() != null) {
                    withTotal++;
                    // 总评与列表 cj 应当一致（数值成绩按数值比较，容忍 93 与 93.0 写法差异）
                    if (isNumeric(entry.getScore()) && isNumeric(detail.getTotalScore())) {
                        assertEquals(Double.parseDouble(entry.getScore()),
                            Double.parseDouble(detail.getTotalScore()), 0.0001,
                            "总评与列表成绩不一致: " + detail);
                    } else {
                        assertEquals(entry.getScore(), detail.getTotalScore(),
                            "总评与列表成绩不一致: " + detail);
                    }
                }
            }
            assertTrue(withTotal > 0, "采样中没有任何一条解析出总评成绩，解析器可能失配");
        } finally {
            client.logout();
        }
    }

    private static boolean isNumeric(String value) {
        return value != null && value.trim().matches("\\d+(?:\\.\\d+)?");
    }
}
