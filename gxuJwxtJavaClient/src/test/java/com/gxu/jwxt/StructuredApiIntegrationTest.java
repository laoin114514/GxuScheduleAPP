package com.gxu.jwxt;

import com.gxu.jwxt.model.Term;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实账号集成测试。凭据只从环境变量读取，不写入仓库。
 */
class StructuredApiIntegrationTest {

    private static final String YEAR = "2025";
    private static final Term TERM = Term.SPRING;

    @Test
    void testedReadOnlyApisReturnStructuredData() throws Exception {
        JwxtClient client = new JwxtClient(requireEnv("JWXT_USERNAME"), requireEnv("JWXT_PASSWORD"));
        client.login();
        try {
            assertFalse(client.menu().items().isEmpty());

            assertNotNull(client.profile().profile().getStudentId());
            assertFalse(client.profile().classList().isEmpty());
            assertFalse(client.profile().terms(YEAR).isEmpty());
            assertFalse(client.profile().exams().getItems().isEmpty());
            assertFalse(client.profile().courseSelections().getItems().isEmpty());
            client.profile().canViewExpandedClasses();

            assertFalse(client.schedule().personal(YEAR, TERM).getAllCourses().isEmpty());
            assertFalse(client.schedule().teacher(YEAR, TERM, "20140104").getCourses().isEmpty());
            assertNotNull(client.schedule().practice(YEAR, TERM).getPracticeCourses());
            assertFalse(client.schedule().timePeriods(YEAR, TERM, "1").isEmpty());
            assertFalse(client.schedule().timeSegments(YEAR, TERM, "1").isEmpty());
            assertFalse(client.schedule().timeModels(YEAR, TERM, "1").isEmpty());
            assertFalse(client.schedule().periodLevels(YEAR, TERM, "1").isEmpty());
            assertFalse(client.schedule().classDetail(YEAR, TERM, "24071101", "2024", "0711").getWeeks().isEmpty());
            assertFalse(client.schedule().weeks(YEAR, TERM).isEmpty());
            assertFalse(client.schedule().classDisplayFields(YEAR, TERM).isEmpty());
            client.schedule().isTermOpen(YEAR, TERM);

            assertFalse(client.grades().term(YEAR, TERM).getItems().isEmpty());
            assertFalse(client.grades().all().getItems().isEmpty());
            assertTrue(client.grades().count(YEAR, TERM).getCourseCount() >= 0);
            assertFalse(client.grades().creditStatistics().isEmpty());
            assertFalse(client.grades().projectCategories(YEAR, TERM).isEmpty());

            assertFalse(client.exams().schedules(YEAR, TERM).getItems().isEmpty());
            assertFalse(client.exams().unscheduledCourses(YEAR, TERM).getItems().isEmpty());
            assertFalse(client.exams().sessions(YEAR, TERM).isEmpty());

            assertFalse(client.classrooms().search(YEAR, TERM, "1", "", "").getItems().isEmpty());
            assertFalse(client.classrooms().periodOptions(YEAR, TERM, "1").getBuildings().isEmpty());
            assertNotNull(client.classrooms().airConditioningPeriods(YEAR, TERM, "1"));
            assertNotNull(client.classrooms().currentWeek(YEAR, TERM, "1").getCurrent());
            assertFalse(client.classrooms().dates(YEAR, TERM, "1,2,3,4,5,6,7", "1").isEmpty());

            assertFalse(client.selections().confirmations(YEAR, TERM).getItems().isEmpty());
            assertTrue(client.selections().selectedCourseCount(YEAR, TERM) >= 0);
            assertNotNull(client.selections().creditSummary(YEAR, TERM).getSelectedCredits());
            client.selections().isConfirmed(YEAR, TERM);
            assertFalse(client.selections().roster(YEAR, TERM, "", "").getItems().isEmpty());
            assertNotNull(client.selections().regularSelectionStatus().getState());
            assertNotNull(client.selections().preselectionStatus().getState());

            var plans = client.teachingPlans().plans(YEAR, TERM);
            assertFalse(plans.getItems().isEmpty());
            assertFalse(client.onlineLearning().addresses(YEAR, TERM).getItems().isEmpty());

            assertFalse(client.dictionary().classes("2024").isEmpty());
            assertFalse(client.dictionary().majors("2024").isEmpty());
            assertFalse(client.dictionary().majorDirections("0711").isEmpty());
        } finally {
            client.logout();
        }
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("运行集成测试需要设置环境变量 " + name);
        }
        return value;
    }
}
