package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;

/** 校区内可筛选的教学楼。 */
public class CampusBuilding {
    @SerializedName("XQH_ID") private String campusId;
    @SerializedName("JXLDM") private String code;
    @SerializedName("JXLMC") private String name;

    public String getCampusId() { return campusId; }
    public String getCode() { return code; }
    public String getName() { return name; }
}
