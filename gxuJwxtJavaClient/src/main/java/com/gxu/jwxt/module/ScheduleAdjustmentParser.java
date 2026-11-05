package com.gxu.jwxt.module;

import com.google.gson.Gson;
import com.gxu.jwxt.model.ScheduleAdjustment;

import java.util.Arrays;
import java.util.List;

/**
 * 「查看教学班调课记录」弹层 HTML 的解析器。
 *
 * <p>接口 {@code GET /jwglxt/kbdy/xskbdy_cxJxbtkjlView.html} 返回的是 HTML，
 * 调课记录以 JS 字面量内嵌在页面里：</p>
 * <pre>
 *   var modelList= eval([{"ttkxx_id":"...","tklxmc":"调课","xzcd":"7周",...}, ...]);
 * </pre>
 *
 * <p>解析策略：定位 {@code modelList} 之后的 {@code eval}，再从 {@code eval} 后第一个
 * {@code '['} 开始做<b>括号配对</b>取到匹配的 {@code ']'}（跳过 JSON 字符串内部的括号
 * 与反斜杠转义），最后交给 Gson 反序列化为 {@link ScheduleAdjustment} 数组再转列表。
 * 页面结构缺失、括号不配对或 JSON 畸形时统一返回空列表，绝不抛异常 —— 调课标记只是
 * 锦上添花，解析失败应降级为「无调课标签」而不是让整次课表导入失败。</p>
 *
 * <p><b>为什么用数组而不是 {@code TypeToken<List<...>>}：</b>R8 会把匿名 TypeToken 子类
 * 做类合并，合并后泛型签名丢失，TypeToken 构造时抛 "Missing type parameter"，
 * 表现为 release 包一刷新课表就闪退。数组反序列化不依赖泛型签名，天然免疫该问题
 * （与线上 v0.5.8 的修复保持一致）。</p>
 */
public final class ScheduleAdjustmentParser {

    private static final Gson GSON = new Gson();

    private ScheduleAdjustmentParser() {}

    /**
     * 从调课记录弹层 HTML 中解析调课记录。
     *
     * @param html 页面内容，可为 null / 空
     * @return 调课记录列表；未找到 modelList 或解析失败时返回空列表（不返回 null）
     */
    public static List<ScheduleAdjustment> parse(String html) {
        if (html == null || html.trim().isEmpty()) return List.of();

        String json = extractModelListJson(html);
        if (json == null) return List.of();

        try {
            ScheduleAdjustment[] records = GSON.fromJson(json, ScheduleAdjustment[].class);
            return records != null ? Arrays.asList(records) : List.of();
        } catch (RuntimeException e) {
            // JsonSyntaxException / IllegalStateException 等：坏数据按无记录处理
            return List.of();
        }
    }

    /**
     * 提取 {@code modelList = eval([...])} 中最外层 JSON 数组的原始文本。
     *
     * @return 形如 {@code [{...},{...}]} 的文本；找不到时返回 null
     */
    private static String extractModelListJson(String html) {
        int modelIndex = html.indexOf("modelList");
        if (modelIndex < 0) return null;

        int evalIndex = html.indexOf("eval", modelIndex);
        if (evalIndex < 0) return null;

        int start = html.indexOf('[', evalIndex);
        if (start < 0) return null;

        int end = matchingBracket(html, start);
        if (end < 0) return null;

        return html.substring(start, end + 1);
    }

    /**
     * 从 {@code start} 处的 {@code '['} 开始做括号配对。
     *
     * <p>只统计字符串外的 {@code '['}/{@code ']'}：JSON 字符串内的方括号、以及
     * 被反斜杠转义的引号都不参与配对。找不到配对时返回 -1。</p>
     */
    private static int matchingBracket(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }
}
