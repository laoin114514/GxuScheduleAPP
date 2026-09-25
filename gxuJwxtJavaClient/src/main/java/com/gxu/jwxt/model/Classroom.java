package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;

/** 可查询教室。 */
public class Classroom {
    @SerializedName("cd_id") private String id;
    @SerializedName("cdbh") private String code;
    @SerializedName("cdmc") private String name;
    @SerializedName("cdlb_id") private String typeId;
    @SerializedName("cdlbmc") private String typeName;
    @SerializedName("jxlmc") private String buildingName;
    @SerializedName("lh") private String buildingCode;
    @SerializedName("zws") private String seats;
    @SerializedName("jgmc") private String department;
    @SerializedName("sfkjy") private String borrowable;
    @SerializedName("xqh_id") private String campusId;

    public String getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getTypeId() { return typeId; }
    public String getTypeName() { return typeName; }
    public String getBuildingName() { return buildingName; }
    public String getBuildingCode() { return buildingCode; }
    public String getSeats() { return seats; }
    public String getDepartment() { return department; }
    public boolean isBorrowable() { return "1".equals(borrowable); }
    public String getCampusId() { return campusId; }
}
