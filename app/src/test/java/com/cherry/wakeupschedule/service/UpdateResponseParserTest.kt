package com.cherry.wakeupschedule.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateResponseParserTest {

    @Test
    fun `解析最新版本响应`() {
        val raw = """
            {"code":0,"data":{
              "versionCode":1707,
              "versionName":"1.7.7",
              "changelog":"## 更新内容\n- 修复若干问题",
              "minSdk":26,
              "files":[{"abi":"universal","url":"https://cdn.example.com/apk/1707/u.apk"}]}}
        """.trimIndent()
        val info = UpdateResponseParser.parse(raw)
        assertEquals(1707, info?.versionCode)
        assertEquals("1.7.7", info?.versionName)
        assertEquals("https://cdn.example.com/apk/1707/u.apk", info?.downloadUrl)
        assertTrue(info?.changelog?.contains("更新内容") == true)
    }

    @Test
    fun `解析失败时返回 null`() {
        assertNull(UpdateResponseParser.parse("not json"))
        assertNull(UpdateResponseParser.parse("""{"code":1,"message":"oops"}"""))
        assertNull(UpdateResponseParser.parse("""{"code":0,"data":{}}"""))
    }

    @Test
    fun `版本比较用 versionCode`() {
        assertTrue(UpdateResponseParser.isNewer(1707, 1706))
        assertFalse(UpdateResponseParser.isNewer(1706, 1706))
        assertFalse(UpdateResponseParser.isNewer(1705, 1706))
    }
}
