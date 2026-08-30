package com.cherry.wakeupschedule.ui.theme

import androidx.appcompat.app.AppCompatActivity
import com.cherry.wakeupschedule.R
import com.google.android.material.appbar.MaterialToolbar

/**
 * 统一独立页面的头部：标题 + 返回按钮。
 *
 * - 标题居中、主色加粗（对齐工具页顶栏），返回图标由系统 homeAsUp 提供。
 * - 默认点击返回按钮执行 finish()；需要自定义行为时传 [onBack]。
 */
fun AppCompatActivity.setupPageHeader(
    toolbar: MaterialToolbar,
    title: String,
    onBack: (() -> Unit)? = null
) {
    setSupportActionBar(toolbar)
    supportActionBar?.apply {
        setDisplayHomeAsUpEnabled(true)
        this.title = title
    }
    toolbar.setTitleCentered(true)
    toolbar.setTitleTextAppearance(this, R.style.AppToolbarTitle)
    toolbar.setNavigationOnClickListener { (onBack ?: { finish() }).invoke() }
}
