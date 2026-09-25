package com.gxu.jwxt;

import com.gxu.jwxt.exceptions.LoginException;
import com.gxu.jwxt.model.PageQuery;
import com.gxu.jwxt.model.Term;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 补充探针测试：验证 StructuredApiIntegrationTest 未覆盖的封装接口重载与边界行为。
 *
 * <p>凭据只从环境变量读取，不写入仓库。运行方式：</p>
 * <pre>
 * JWXT_USERNAME=... JWXT_PASSWORD=... ./gradlew test --tests "com.gxu.jwxt.ApiProbeTest"
 * </pre>
 */
class ApiProbeTest {

    private static final String YEAR = "2025";
    private static final Term TERM = Term.SPRING;

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("需要设置环境变量 " + name);
        }
        return value;
    }

    @Test
    void paginationAndDeprecatedOverloads() throws Exception {
        JwxtClient client = new JwxtClient(requireEnv("JWXT_USERNAME"), requireEnv("JWXT_PASSWORD"));
        client.login();
        try {
            // 分页重载：显式 PageQuery
            assertFalse(client.profile().exams(new PageQuery(1, 20)).getItems().isEmpty());
            assertFalse(client.profile().courseSelections(new PageQuery(1, 20)).getItems().isEmpty());
            assertFalse(client.grades().term(YEAR, TERM, new PageQuery(1, 20)).getItems().isEmpty());
            assertFalse(client.grades().all(new PageQuery(1, 20)).getItems().isEmpty());
            assertFalse(client.exams().schedules(YEAR, TERM, new PageQuery(1, 20)).getItems().isEmpty());
            assertFalse(client.exams().unscheduledCourses(YEAR, TERM, new PageQuery(1, 20)).getItems().isEmpty());
            assertFalse(client.classrooms().search(YEAR, TERM, "1", "", "", new PageQuery(1, 20)).getItems().isEmpty());
            assertFalse(client.selections().confirmations(YEAR, TERM, new PageQuery(1, 20)).getItems().isEmpty());
            assertFalse(client.selections().roster(YEAR, TERM, "", "", new PageQuery(1, 20)).getItems().isEmpty());
            assertFalse(client.teachingPlans().plans(YEAR, TERM, new PageQuery(1, 20)).getItems().isEmpty());
            assertFalse(client.onlineLearning().addresses(YEAR, TERM, new PageQuery(1, 20)).getItems().isEmpty());

            // 废弃的 String 学期重载
            assertFalse(client.schedule().personal(YEAR, "12").getAllCourses().isEmpty());
            assertFalse(client.schedule().teacher(YEAR, "12", "20140104").getCourses().isEmpty());
            assertFalse(client.schedule().classDetail(YEAR, "12", "24071101", "2024", "0711").getWeeks().isEmpty());
        } finally {
            client.logout();
        }
    }

    @Test
    void booleanApisReturnUsableValues() throws Exception {
        JwxtClient client = new JwxtClient(requireEnv("JWXT_USERNAME"), requireEnv("JWXT_PASSWORD"));
        client.login();
        try {
            // 布尔接口：不仅不抛异常，还要返回可用值
            client.schedule().isTermOpen(YEAR, TERM); // 不抛异常即可
            client.selections().isConfirmed(YEAR, TERM); // 不抛异常即可
            assertTrue(client.selections().selectedCourseCount(YEAR, TERM) >= 0);
            assertTrue(client.profile().canViewExpandedClasses()); // 本人账号应具备扩班资格
        } finally {
            client.logout();
        }
    }

    @Test
    void reloginRecoversAfterLogout() throws Exception {
        JwxtClient client = new JwxtClient(requireEnv("JWXT_USERNAME"), requireEnv("JWXT_PASSWORD"));
        client.login();
        assertTrue(client.isLoggedIn());
        assertFalse(client.schedule().personal(YEAR, TERM).getAllCourses().isEmpty());

        // 模拟会话失效：调用 relogin 后仍能正常查询
        client.relogin();
        assertTrue(client.isLoggedIn());
        assertFalse(client.schedule().personal(YEAR, TERM).getAllCourses().isEmpty());
        client.logout();
        assertFalse(client.isLoggedIn());
    }

    @Test
    void wrongPasswordRejected() {
        assertThrows(LoginException.class, () -> {
            JwxtClient client = new JwxtClient(requireEnv("JWXT_USERNAME"), "definitely-wrong-password-" + System.nanoTime());
            client.login();
        });
    }
}
