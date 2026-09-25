package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;
import java.util.Collections;
import java.util.List;

/** 空闲教室筛选使用的教学楼和节次。 */
public class CampusPeriodOptions {
    @SerializedName("lhList") private List<CampusBuilding> buildings;
    @SerializedName("jcList") private List<CampusPeriod> periods;

    public List<CampusBuilding> getBuildings() { return buildings != null ? buildings : Collections.emptyList(); }
    public List<CampusPeriod> getPeriods() { return periods != null ? periods : Collections.emptyList(); }
}
