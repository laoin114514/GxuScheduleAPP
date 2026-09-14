package com.cherry.wakeupschedule.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 教务课表周次字符串解析测试。
 */
class JwxtImportServiceWeekTest {

    private fun bitmapOf(vararg ws: Int): Long =
        ws.fold(0L) { acc, w -> acc or (1L shl (w - 1)) }

    private fun parse(weeks: String): Long = JwxtImportService.parseWeekBitmap(weeks)

    @Test
    fun `带第前缀的单周能解析`() {
        assertEquals(bitmapOf(3), parse("第3周"))
    }

    @Test
    fun `带第前缀的区间能解析`() {
        assertEquals(bitmapOf(*((1..16).toList().toIntArray())), parse("第1-16周"))
    }

    @Test
    fun `带第前缀的组合能解析`() {
        val expected = bitmapOf(*((1..5).toList().toIntArray())) or
            bitmapOf(*((7..11).filter { it % 2 == 1 }.toIntArray())) or
            bitmapOf(*((12..16).toList().toIntArray()))
        assertEquals(expected, parse("第1-5周,第7-11周(单),第12-16周"))
    }

    @Test
    fun `原有格式不受影响`() {
        assertEquals(bitmapOf(14), parse("14周"))
        assertEquals(bitmapOf(1, 3, 5, 7, 9, 11), parse("1-11周(单)"))
        assertEquals(bitmapOf(6, 8), parse("6-8周(双)"))
    }

    @Test
    fun `全角符号与数字能解析`() {
        assertEquals(bitmapOf(1, 2, 3, 4, 5), parse("１－５周"))
        assertEquals(bitmapOf(1, 3, 5), parse("1、3、5周"))
        assertEquals(bitmapOf(1, 3, 5), parse("1，3，5周"))
        assertEquals(bitmapOf(1, 2, 3), parse("1～3周"))
        assertEquals(bitmapOf(1, 2, 3), parse("1至3周"))
        assertEquals(bitmapOf(1, 3, 5), parse("1—5周(单)"))
    }

    @Test
    fun `反向区间也能按顺序展开`() {
        assertEquals(bitmapOf(3, 4, 5), parse("5-3周"))
    }

    @Test
    fun `无法识别的字符串返回零`() {
        assertEquals(0L, parse(""))
        assertEquals(0L, parse("周"))
        assertEquals(0L, parse("abc"))
    }

    @Test
    fun `周次位掩码回退解析`() {
        assertEquals(bitmapOf(1, 2, 3, 4), JwxtImportService.parseWeekMask("1111000000000000"))
    }

    @Test
    fun `非零一串的掩码直接放弃`() {
        assertEquals(0L, JwxtImportService.parseWeekMask("12"))
        assertEquals(0L, JwxtImportService.parseWeekMask("abc"))
        assertEquals(0L, JwxtImportService.parseWeekMask(null))
        assertEquals(0L, JwxtImportService.parseWeekMask(""))
    }
}
