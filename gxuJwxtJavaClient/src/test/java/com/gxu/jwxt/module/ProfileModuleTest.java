package com.gxu.jwxt.module;

import com.gxu.jwxt.model.StudentProfile;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/** 个人信息页面 HTML 解析兜底（JSON 接口返回空包时使用）。 */
class ProfileModuleTest {

    @Test
    void parsesFieldsFromProfilePage() throws Exception {
        String html = "<html><body>"
                + "<input type='hidden' name='xnm' value='2026'/>"
                + "<input type='hidden' name='xqm' value='3'/>"
                + "<p class='form-control-static'>20240001</p>"
                + "<div class='col-sm-8' id='col_xh'>20240001</div>"
                + "<div class='col-sm-8' id='col_xm'>张三</div>"
                + "<div class='col-sm-8' id='col_njdm_id'>2024</div>"
                + "<div class='col-sm-8' id='col_bh_id'>计算机241</div>"
                + "<div class='col-sm-8' id='col_zyh_id'>计算机科学与技术(0711)</div>"
                + "<div class='col-sm-8' id='col_jg_id'>计算机与电子信息学院</div>"
                + "<div class='col-sm-8' id='col_xjztdm'>在读</div>"
                + "<div class='col-sm-8' id='col_xslbdm'>普通本科生</div>"
                + "</body></html>";

        StudentProfile p = ProfileModule.parseProfilePage(html);

        assertEquals("20240001", p.getStudentId());
        assertEquals("张三", p.getName());
        assertEquals("2024", p.getGrade());
        assertEquals("计算机241", p.getClassName());
        assertEquals("计算机科学与技术(0711)", p.getMajor());
        assertEquals("计算机与电子信息学院", p.getCollege());
        assertEquals("在读", p.getStatus());
        assertEquals("普通本科生", p.getStudentType());
        assertEquals("2026", p.getSchoolYear());
        assertEquals("3", p.getTerm());
    }

    @Test
    void fallsBackToHiddenXhId() throws Exception {
        String html = "<html><body>"
                + "<input type='hidden' name='xh_id' value='20240002'/>"
                + "<div id='col_xm'>李四</div>"
                + "</body></html>";

        StudentProfile p = ProfileModule.parseProfilePage(html);

        assertEquals("20240002", p.getStudentId());
        assertEquals("李四", p.getName());
    }

    @Test
    void throwsWhenNoStudentId() {
        String html = "<html><body><div id='col_xm'>未知</div></body></html>";
        assertThrows(IOException.class, () -> ProfileModule.parseProfilePage(html));
    }
}
