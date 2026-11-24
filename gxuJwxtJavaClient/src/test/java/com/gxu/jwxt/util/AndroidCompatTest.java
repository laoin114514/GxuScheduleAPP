package com.gxu.jwxt.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AndroidCompatTest {

    @Test
    void mapOfKeepsEntriesAndIsUnmodifiable() {
        Map<String, String> map = AndroidCompat.mapOf("xnm", "2026", "xqm", "3");

        assertEquals(2, map.size());
        assertEquals("2026", map.get("xnm"));
        assertEquals("3", map.get("xqm"));
        assertThrows(UnsupportedOperationException.class, () -> map.put("a", "b"));
    }

    @Test
    void mapOfRejectsNullAndDuplicateKeysLikeMapOf() {
        assertThrows(NullPointerException.class, () -> AndroidCompat.mapOf("a", null));
        assertThrows(NullPointerException.class, () -> AndroidCompat.mapOf(null, "b"));
        assertThrows(IllegalArgumentException.class, () -> AndroidCompat.mapOf("a", "1", "a", "2"));
        assertThrows(IllegalArgumentException.class, () -> AndroidCompat.mapOf("a"));
    }

    @Test
    void listOfIsUnmodifiableAndRejectsNull() {
        List<String> list = AndroidCompat.listOf("a", "b");

        assertEquals(List.of("a", "b"), list);
        assertThrows(UnsupportedOperationException.class, () -> list.add("c"));
        assertThrows(NullPointerException.class, () -> AndroidCompat.listOf("a", null));
        assertTrue(AndroidCompat.listOf().isEmpty());
    }

    @Test
    void urlEncodeMatchesUtf8FormEncoding() {
        assertEquals("%E8%AF%BE%E8%A1%A8", AndroidCompat.urlEncode("课表"));
        assertEquals("a+b%26c%3Dd", AndroidCompat.urlEncode("a b&c=d"));
    }

    @Test
    void isBlankMatchesJava11Semantics() {
        assertTrue(AndroidCompat.isBlank(null));
        assertTrue(AndroidCompat.isBlank(""));
        assertTrue(AndroidCompat.isBlank(" \t\r\n"));
        assertTrue(AndroidCompat.isBlank("\u3000\u3000"));
        assertFalse(AndroidCompat.isBlank("null"));
        assertFalse(AndroidCompat.isBlank(" a "));
        // Character.isWhitespace 不把不换行空格视为空白，与 JDK String.isBlank 一致
        assertFalse(AndroidCompat.isBlank("\u00A0"));
    }
}
