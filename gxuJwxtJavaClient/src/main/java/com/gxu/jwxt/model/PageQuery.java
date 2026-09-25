package com.gxu.jwxt.model;

import java.util.HashMap;
import java.util.Map;

/** 标准分页查询参数 */
public class PageQuery {
    private int page = 1;
    private int pageSize = 100;
    private String sortName = "";
    private String sortOrder = "asc";

    public PageQuery() {}

    public PageQuery(int page, int pageSize) {
        this.page = Math.max(1, page);
        this.pageSize = Math.max(1, Math.min(pageSize, 500));
    }

    public PageQuery(int page, int pageSize, String sortName, String sortOrder) {
        this(page, pageSize);
        this.sortName = sortName != null ? sortName : "";
        this.sortOrder = sortOrder != null ? sortOrder : "asc";
    }

    // ---- getters/setters ----

    public int getPage() { return page; }
    public void setPage(int page) { this.page = Math.max(1, page); }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = Math.max(1, Math.min(pageSize, 500)); }
    public String getSortName() { return sortName; }
    public void setSortName(String sortName) { this.sortName = sortName != null ? sortName : ""; }
    public String getSortOrder() { return sortOrder; }
    public void setSortOrder(String sortOrder) { this.sortOrder = sortOrder != null ? sortOrder : "asc"; }

    /**
     * 转换为表单参数 Map
     */
    public Map<String, String> toMap() {
        return toMap(Map.of());
    }

    public Map<String, String> toMap(Map<String, String> extras) {
        String ts = String.valueOf(System.currentTimeMillis());
        Map<String, String> m = new HashMap<>();
        m.put("_search", "false");
        m.put("nd", ts);
        m.put("queryModel.showCount", String.valueOf(pageSize));
        m.put("queryModel.currentPage", String.valueOf(page));
        m.put("queryModel.sortName", sortName);
        m.put("queryModel.sortOrder", sortOrder);
        m.put("time", ts);
        if (extras != null) {
            m.putAll(extras);
        }
        return m;
    }
}
