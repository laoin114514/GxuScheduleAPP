package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;

/** 指定学年学期可用的考试场次。 */
public class ExamSession {
    @SerializedName("KSMCDMB_ID") private String id;
    @SerializedName("KSMC") private String name;
    @SerializedName("KSXS") private String form;
    @SerializedName("KSXZ") private String nature;
    @SerializedName("SFBKBJ") private String makeUp;
    @SerializedName("SFKCFPKC") private String courseScheduling;

    public String getId() { return id; }
    public String getName() { return name; }
    public String getForm() { return form; }
    public String getNature() { return nature; }
    public boolean isMakeUp() { return "1".equals(makeUp); }
    public boolean isCourseScheduling() { return "1".equals(courseScheduling); }
}
