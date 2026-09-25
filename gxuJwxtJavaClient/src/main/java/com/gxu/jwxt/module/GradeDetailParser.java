package com.gxu.jwxt.module;

import com.gxu.jwxt.model.GradeDetail;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * 「查看成绩详情」（cjcx_cxCjxqGjh.html）返回 HTML 的解析器。
 *
 * <p>实测页面结构固定为一张三列表格（{@code table#subtab}）：</p>
 * <pre>
 *   成绩分项        成绩分项比例   成绩
 *   【 平时成绩 】   40%          94
 *   【 期末成绩 】   60%          89
 *   【 总评 】                     91
 * </pre>
 *
 * <p>另有「无分项拆分」形态，只有【 总评成绩 】100% 与【 总评 】两行；页面顶部
 * {@code span.red2} 回显课程名，底部给出「本课程期末强制达标线为 N 分」。</p>
 *
 * <p>为兼容其它教务部署，结构化字段解析不到时会退回通用的 label→value 表格配对
 * 与全文本正则；仍然解析不到的字段留空，由调用方降级为列表接口已有的字段展示。</p>
 */
public final class GradeDetailParser {

    /** 「本课程期末强制达标线为 60 分」；未设置时数字为空 */
    private static final Pattern PASS_LINE = Pattern.compile("达标线为\\s*(\\d+(?:\\.\\d+)?)\\s*分");
    private static final Pattern RATIO = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%");
    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    /** "平时成绩(30%)" 这类把比例写在同一个单元格里的写法 */
    private static final Pattern USUAL_INLINE_RATIO =
        Pattern.compile("平时[^()（）%]{0,12}[（(]\\s*(\\d+(?:\\.\\d+)?)\\s*%\\s*[)）]");
    private static final Pattern FINAL_INLINE_RATIO =
        Pattern.compile("期末[^()（）%]{0,12}[（(]\\s*(\\d+(?:\\.\\d+)?)\\s*%\\s*[)）]");

    private static final String[] USUAL_KEYS = {"平时", "过程", "阶段"};
    private static final String[] FINAL_KEYS = {"期末", "末考"};
    private static final String[] TOTAL_KEYS = {"总评", "总成绩", "综合成绩", "最终成绩"};
    /** 表头/分项名识别用（含「分项」「成绩」这类通用词） */
    private static final String[] LABEL_KEYS = {
        "分项", "成绩", "平时", "期末", "总评", "综合", "最终", "过程", "阶段", "末考"
    };
    private static final String[] RATIO_KEYS = {"比例", "占比"};

    private GradeDetailParser() {}

    /**
     * 解析详情页 HTML。
     *
     * @param html               页面内容，可为空
     * @param fallbackCourseName 兜底课程名（列表接口已拿到，页面未回显时使用）
     */
    public static GradeDetail parse(String html, String fallbackCourseName) {
        GradeDetail detail = new GradeDetail();
        detail.setCourseName(normalizeText(fallbackCourseName));
        if (html == null || html.trim().isEmpty()) return detail;

        Document doc = Jsoup.parse(html);

        // 详情页会回显课程名，优先采用
        String pageName = normalizeText(textOf(doc.selectFirst("span.red2")));
        if (!pageName.isEmpty()) detail.setCourseName(pageName);

        // 主路径：三列成绩分项表
        int recognized = parseComponentTable(detail, doc);
        if (recognized == 0) {
            // 兜底：其它部署的 label→value 结构
            Map<String, String> pairs = collectPairs(doc);
            for (Map.Entry<String, String> entry : pairs.entrySet()) {
                applyPair(detail, entry.getKey(), entry.getValue());
                detail.addRow(entry.getKey(), entry.getValue());
            }
        }

        // 比例兜底：处理无法配对成表格的写法
        applyTextFallback(detail, doc.text());

        // 期末强制达标线（未设置时页面只留空位）
        detail.setPassLine(parsePassLine(doc.text()));
        return detail;
    }

    // ── 主路径：三列成绩分项表 ──────────────────────────────

    private static int parseComponentTable(GradeDetail detail, Document doc) {
        Element table = doc.selectFirst("table#subtab");
        if (table == null) table = findComponentTable(doc);
        if (table == null) return 0;

        int recognized = 0;
        for (Element row : table.select("tr")) {
            List<String> cells = cellTexts(row);
            if (cells.size() < 2) continue;

            int labelIndex = indexOfLabel(cells);
            if (labelIndex < 0) continue;          // 没有分项名：数据行或噪声
            if (allLabelCells(cells)) continue;    // 表头：整行都是字段名

            String label = stripDecoration(cells.get(labelIndex));
            if (label.isEmpty()) continue;

            String ratio = null;
            String score = null;
            List<String> values = new ArrayList<>();
            for (int i = 0; i < cells.size(); i++) {
                if (i == labelIndex || cells.get(i).isEmpty()) continue;
                String cell = cells.get(i);
                values.add(cell);
                if (cell.contains("%")) {
                    if (ratio == null) ratio = firstRatio(cell);
                } else if (score == null) {
                    score = firstNumber(cell);
                }
            }

            applyComponent(detail, label, ratio, score);
            detail.addRow(label, String.join(" ", values));
            recognized++;
        }
        return recognized;
    }

    /** 分项名所在列：优先带【】的单元格，其次含已知字段名的单元格。 */
    private static int indexOfLabel(List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            String cell = cells.get(i);
            if (cell.isEmpty()) continue;
            if (cell.contains("【") || cell.contains("】")) return i;
            if (containsAny(cell, LABEL_KEYS) && firstNumber(cell) == null) return i;
        }
        return -1;
    }

    /** 整行都是字段名（表头行），且不含数字。 */
    private static boolean allLabelCells(List<String> cells) {
        boolean sawLabel = false;
        for (String cell : cells) {
            if (cell.isEmpty()) continue;
            if (!containsAny(cell, LABEL_KEYS) || firstNumber(cell) != null) return false;
            sawLabel = true;
        }
        return sawLabel;
    }

    /** 找出含【】分项名的表格（不依赖固定 id）。 */
    private static Element findComponentTable(Document doc) {
        for (Element table : doc.select("table")) {
            for (Element row : table.select("tr")) {
                List<String> cells = cellTexts(row);
                if (!cells.isEmpty() && (cells.get(0).contains("【") || cells.get(0).contains("】"))) {
                    return table;
                }
            }
        }
        return null;
    }

    private static void applyComponent(GradeDetail detail, String label, String ratio, String score) {
        if (containsAny(label, USUAL_KEYS)) {
            detail.setUsualScore(keep(detail.getUsualScore(), score));
            detail.setUsualRatio(keep(detail.getUsualRatio(), ratio));
        } else if (containsAny(label, FINAL_KEYS)) {
            detail.setFinalScore(keep(detail.getFinalScore(), score));
            detail.setFinalRatio(keep(detail.getFinalRatio(), ratio));
        } else if (isTotalComponent(label)) {
            // 「总评成绩」是 100% 权重的分项（实测可能与列表成绩有小数差），
            // 仅在页面没有单独的「总评」行时兜底
            detail.setTotalScore(keep(detail.getTotalScore(), score));
        } else if (containsAny(label, TOTAL_KEYS)) {
            // 「总评」才是最终成绩，出现时覆盖兜底值
            detail.setTotalScore(score);
        }
    }

    /** 「总评成绩」是分项名，与最终成绩「总评」不是一回事（实测两者可能就差在小数上）。 */
    private static boolean isTotalComponent(String label) {
        return label.contains("总评成绩");
    }

    // ── 兜底路径：通用 label→value 配对 ──────────────────────

    private static Map<String, String> collectPairs(Document doc) {
        Map<String, String> pairs = new LinkedHashMap<>();
        // 表格：同一行相邻单元格两两配对（label, value, label, value ...）
        for (Element row : doc.select("tr")) {
            Elements cells = row.select("th,td");
            if (cells.size() < 2) continue;
            for (int i = 0; i + 1 < cells.size(); i += 2) {
                putPair(pairs, cells.get(i).text(), cells.get(i + 1).text());
            }
        }
        // 表单式：label 与紧随其后的兄弟节点
        for (Element label : doc.select("label")) {
            Element next = label.nextElementSibling();
            if (next != null) putPair(pairs, label.text(), next.text());
        }
        return pairs;
    }

    private static void putPair(Map<String, String> pairs, String rawLabel, String rawValue) {
        String label = normalize(rawLabel);
        String value = normalize(rawValue);
        if (label.isEmpty() || value.isEmpty()) return;
        if (label.equals(value)) return;
        pairs.putIfAbsent(label, value);
    }

    private static void applyPair(GradeDetail detail, String label, String value) {
        // 表头行（如「平时成绩 | 期末成绩」两格都是字段名）不是数据，跳过
        if (isKnownLabel(label) && isKnownLabel(value)) return;

        // 「平时成绩占比 | 30%」这类：label 指明比例
        if (containsAny(label, RATIO_KEYS)) {
            if (containsAny(label, USUAL_KEYS) || containsAny(label, FINAL_KEYS)) {
                applyComponent(detail, label, firstRatio(value), null);
            } else {
                // label 形如「成绩比例」，value 形如「平时30% 期末70%」
                Integer usual = ratioNear(value, USUAL_KEYS);
                Integer finals = ratioNear(value, FINAL_KEYS);
                if (usual != null) detail.setUsualRatio(keep(detail.getUsualRatio(), String.valueOf(usual)));
                if (finals != null) detail.setFinalRatio(keep(detail.getFinalRatio(), String.valueOf(finals)));
            }
            return;
        }

        boolean valueIsRatio = value.contains("%");
        applyComponent(detail, label,
            valueIsRatio ? firstRatio(value) : null,
            valueIsRatio ? null : firstNumber(value));
    }

    /** 全文本兜底：处理「平时:30%」「平时成绩(30%)」这类无法配对成表格的写法 */
    private static void applyTextFallback(GradeDetail detail, String rawText) {
        String text = normalize(rawText);
        if (text.isEmpty()) return;

        if (detail.getUsualRatio() == null) {
            detail.setUsualRatio(matchGroup(USUAL_INLINE_RATIO, text));
        }
        if (detail.getFinalRatio() == null) {
            detail.setFinalRatio(matchGroup(FINAL_INLINE_RATIO, text));
        }
        if (detail.getUsualRatio() == null) {
            Integer r = ratioNear(text, USUAL_KEYS);
            if (r != null) detail.setUsualRatio(String.valueOf(r));
        }
        if (detail.getFinalRatio() == null) {
            Integer r = ratioNear(text, FINAL_KEYS);
            if (r != null) detail.setFinalRatio(String.valueOf(r));
        }
    }

    // ── 小工具 ──────────────────────────────────────────────

    private static List<String> cellTexts(Element row) {
        List<String> out = new ArrayList<>();
        for (Element cell : row.select("td,th")) {
            out.add(normalizeCell(cell.text()));
        }
        return out;
    }

    /** 折叠空白与 &nbsp;，保留单元格内的其它字符 */
    private static String normalizeCell(String raw) {
        if (raw == null) return "";
        return raw.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    /** 去掉【】、冒号与全部空白 */
    private static String stripDecoration(String raw) {
        if (raw == null) return "";
        return raw.replace("【", "").replace("】", "")
            .replace("：", "").replace(":", "")
            .replaceAll("\\s+", "").trim();
    }

    private static String normalizeText(String raw) {
        if (raw == null) return "";
        return raw.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String normalize(String raw) {
        if (raw == null) return "";
        return raw.replace('\u00A0', ' ')
            .replace("：", "")
            .replace(":", "")
            .replaceAll("\\s+", "")
            .trim();
    }

    private static String parsePassLine(String rawText) {
        if (rawText == null) return null;
        Matcher m = PASS_LINE.matcher(rawText.replace('\u00A0', ' '));
        return m.find() ? m.group(1) : null;
    }

    private static String textOf(Element element) {
        return element != null ? element.text() : "";
    }

    private static String keep(String current, String candidate) {
        if (current != null && !current.trim().isEmpty()) return current;
        return candidate;
    }

    private static String matchGroup(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String firstRatio(String value) {
        return matchGroup(RATIO, value);
    }

    private static String firstNumber(String value) {
        return matchGroup(NUMBER, value);
    }

    /** 在文本中找到关键词后，取其后 20 个字符内出现的第一个百分比数字 */
    private static Integer ratioNear(String text, String[] keys) {
        for (String key : keys) {
            int idx = text.indexOf(key);
            if (idx < 0) continue;
            int end = Math.min(text.length(), idx + key.length() + 20);
            String ratio = firstRatio(text.substring(idx, end));
            if (ratio != null) return parseIntSafe(ratio);
        }
        return null;
    }

    private static Integer parseIntSafe(String ratio) {
        try {
            return (int) Math.round(Double.parseDouble(ratio));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean containsAny(String text, String[] keys) {
        if (text == null) return false;
        for (String key : keys) {
            if (text.contains(key)) return true;
        }
        return false;
    }

    private static boolean isKnownLabel(String text) {
        return containsAny(text, LABEL_KEYS);
    }
}
