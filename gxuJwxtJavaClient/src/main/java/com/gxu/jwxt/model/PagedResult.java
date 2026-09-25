package com.gxu.jwxt.model;

import com.google.gson.annotations.SerializedName;
import java.util.Collections;
import java.util.List;

/** 教务系统 jqGrid 列表响应。 */
public class PagedResult<T> {

    @SerializedName("items")
    private List<T> items;

    @SerializedName("totalResult")
    private int totalResult;

    @SerializedName("currentPage")
    private int currentPage;

    @SerializedName("pageSize")
    private int pageSize;

    @SerializedName("totalPage")
    private int totalPage;

    public List<T> getItems() { return items != null ? items : Collections.emptyList(); }
    public int getTotalResult() { return totalResult; }
    public int getCurrentPage() { return currentPage; }
    public int getPageSize() { return pageSize; }
    public int getTotalPage() { return totalPage; }
}
