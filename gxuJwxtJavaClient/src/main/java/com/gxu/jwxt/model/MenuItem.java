package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;
import java.util.Collections;
import java.util.List;

/** 教务系统菜单树节点。 */
public class MenuItem {
    @SerializedName("name") private String name;
    @SerializedName("gnmkdm") private String functionCode;
    @SerializedName("url") private String url;
    @SerializedName(value = "children", alternate = {"childMenus", "menus"})
    private List<MenuItem> children;

    public String getName() { return name; }
    public String getFunctionCode() { return functionCode; }
    public String getUrl() { return url; }
    public List<MenuItem> getChildren() { return children != null ? children : Collections.emptyList(); }
}
