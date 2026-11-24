package com.gxu.jwxt.util;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Android 低版本 API 兼容垫片。
 *
 * <p>本模块是普通 Java 库（java 插件），不是 Android library：javac 按 JDK 17 编译，
 * 不会校验方法在 Android 各 API level 上是否存在。可 README 声明的最低版本是
 * Android 8.0（API 26），因此下列 Java 9/11 才加入 Android 的方法不能直接用：</p>
 *
 * <ul>
 *   <li>{@code List.of / Map.of} —— Android 11（API 30）才有；</li>
 *   <li>{@code String.isBlank} —— Android 13（API 33）才有；</li>
 *   <li>{@code URLEncoder.encode(String, Charset)} —— Android 13（API 33）才有。</li>
 * </ul>
 *
 * <p>直接调用在低版本设备上会抛 {@link NoSuchMethodError}，而且桌面 JVM 单测完全
 * 测不出来（JDK 里这些方法都存在）。2026-11 的 0.5.12-beta.5 在 MIUI 14（Android 12）
 * 上「点刷新报 No static method encode(...)」就是这么来的。本类只用 Android API 1
 * 就有的方法实现等价语义，模块内统一走这里。</p>
 */
public final class AndroidCompat {

    private AndroidCompat() {
    }

    /**
     * 等价于 {@code Map.of(keyValues...)}：返回不可变 Map，拒绝 null 键/值。
     *
     * @param keyValues 交替出现的键和值，必须成对
     */
    public static <K, V> Map<K, V> mapOf(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("mapOf 的键值必须成对出现");
        }
        Map<K, V> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            @SuppressWarnings("unchecked")
            K key = (K) keyValues[i];
            @SuppressWarnings("unchecked")
            V value = (V) keyValues[i + 1];
            if (key == null || value == null) {
                throw new NullPointerException("mapOf 不接受 null 键或值");
            }
            if (map.containsKey(key)) {
                throw new IllegalArgumentException("mapOf 出现重复键：" + key);
            }
            map.put(key, value);
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * 等价于 {@code List.of(items...)}：返回不可变 List，拒绝 null 元素。
     */
    @SafeVarargs
    public static <T> List<T> listOf(T... items) {
        List<T> list = new ArrayList<>(items.length);
        for (T item : items) {
            if (item == null) {
                throw new NullPointerException("listOf 不接受 null 元素");
            }
            list.add(item);
        }
        return Collections.unmodifiableList(list);
    }

    /**
     * 等价于 {@code URLEncoder.encode(value, StandardCharsets.UTF_8)}。
     *
     * <p>旧重载要求显式处理 {@link UnsupportedEncodingException}；UTF-8 是 JVM
     * 规范强制支持的编码，不可能缺失，因此转成 {@link AssertionError}。</p>
     */
    public static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError("JVM 必须支持 UTF-8", e);
        }
    }

    /**
     * 等价于 {@link String#isBlank()}（Java 11）：null 视为空白，空白字符按
     * {@link Character#isWhitespace(char)} 判定（与 JDK 实现一致，含全角空格）。
     */
    public static boolean isBlank(String value) {
        if (value == null || value.isEmpty()) return true;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isWhitespace(value.charAt(i))) return false;
        }
        return true;
    }
}
