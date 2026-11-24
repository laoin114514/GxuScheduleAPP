package com.gxu.jwxt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守门测试：main 源码只允许使用 Android 8.0（API 26）就存在的方法。
 *
 * <p>本模块是普通 Java 库，JVM 单测跑在 JDK 17/21 上，所有 Java 9/11 API 都"能用"，
 * 因此 {@code List.of}、{@code String.isBlank}、{@code URLEncoder.encode(String, Charset)}
 * 这类低版本 Android 缺失的方法在 CI 里完全测不出来，直到用户在真机上点刷新才炸
 * （0.5.12-beta.5 在 MIUI 14 / Android 12 上就是 NoSuchMethodError）。这里直接扫源码，
 * 把问题拦在提交阶段。</p>
 */
class AndroidApiLevelCompatTest {

    /** 低版本 Android 缺失、且本项目曾经踩过的 API 写法。 */
    private static final Pattern BANNED = Pattern.compile(
            // Java 10：URLEncoder.encode(String, Charset)，Android 13（API 33）才有
            "URLEncoder\\.encode\\(.*StandardCharsets\\."
            // Java 11：String.isBlank，Android 13（API 33）才有
            + "|\\.isBlank\\(\\)"
            // Java 9：List/Map/Set.of，Android 11（API 30）才有
            + "|\\b(?:List|Map|Set)\\.of\\("
            // Java 11 其他 String 便捷方法（Android 13 才有）
            + "|\\.(?:strip|stripLeading|stripTrailing|repeat|lines|formatted)\\(");

    @Test
    void mainSourcesAvoidApisMissingOnAndroid8() throws IOException {
        Path sourceRoot = Paths.get("src", "main", "java");
        assertTrue(Files.isDirectory(sourceRoot), "找不到 main 源码目录：" + sourceRoot.toAbsolutePath());

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                if (file.getFileName().toString().equals("AndroidCompat.java")) continue;
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    String trimmed = line.trim();
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue;
                    if (BANNED.matcher(line).find()) {
                        offenders.add(sourceRoot.relativize(file) + ":" + (i + 1) + "  " + trimmed);
                    }
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "以下代码使用了 Android 8.0（minSdk 26）不存在的方法，低版本真机会 NoSuchMethodError。\n"
                        + "请改用 com.gxu.jwxt.util.AndroidCompat 或 Collections 的等价实现：\n  "
                        + String.join("\n  ", offenders));
    }
}
