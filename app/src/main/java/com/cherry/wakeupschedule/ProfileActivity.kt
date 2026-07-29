package com.cherry.wakeupschedule

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.cherry.wakeupschedule.service.JwxtAccountManager
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import com.gxu.jwxt.model.StudentProfile

class ProfileActivity : AppCompatActivity() {

    private lateinit var scrollProfile: ScrollView
    private lateinit var layoutProfile: LinearLayout
    private lateinit var tvError: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyToTheme(this)
        setContentView(R.layout.activity_profile)

        scrollProfile = findViewById(R.id.scroll_profile)
        layoutProfile = findViewById(R.id.layout_profile)
        tvError = findViewById(R.id.tv_error)

        // 仅从数据库读取
        val profile = JwxtAccountManager.getProfile()
        if (profile != null) {
            displayProfile(profile)
            scrollProfile.visibility = View.VISIBLE
            tvError.visibility = View.GONE
        } else {
            tvError.text = "未绑定教务账号或个人信息未获取\n\n请先在「我的」页面绑定教务系统账号"
            tvError.visibility = View.VISIBLE
            scrollProfile.visibility = View.GONE
        }
    }

    private fun displayProfile(p: StudentProfile) {
        layoutProfile.removeAllViews()

        addHeader("${p.name ?: "未知"}", "${p.studentId ?: ""}")

        addSection("基本信息")
        addRow("性别", p.gender)
        addRow("出生日期", p.birthDate)
        addRow("民族", p.ethnicity)
        addRow("政治面貌", p.politicalStatus)
        addRow("身份证号", p.idNumber?.let { maskIdCard(it) })

        addSection("学籍信息")
        addRow("学院", p.college)
        addRow("专业", p.major)
        addRow("班级", p.className)
        addRow("年级", p.grade)
        addRow("培养层次", p.eduLevel)
        addRow("培养方式", p.eduMode)
        addRow("学制", p.schoolingLength)
        addRow("学籍状态", p.status)
        addRow("是否在校", p.isAtSchool)

        addSection("入学信息")
        addRow("入学日期", p.enrollDate)
        addRow("招生年度", p.admitYear)
        addRow("招生专业", p.admitMajor)
        addRow("招生学院", p.admitCollege)
        addRow("考生号", p.examNumber)
        addRow("毕业中学", p.highSchool)
        addRow("入学总分", p.entranceScore)
        addRow("生源地", p.hometown)
    }

    private fun addHeader(name: String, studentId: String) {
        val tvName = TextView(this).apply {
            text = name
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            val typedValue = android.util.TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)
            setTextColor(typedValue.data)
            gravity = Gravity.CENTER
        }
        val tvId = TextView(this).apply {
            text = "学号: $studentId"
            textSize = 14f
            val typedValue = android.util.TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, typedValue, true)
            setTextColor(typedValue.data)
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 24)
        }
        layoutProfile.addView(tvName)
        layoutProfile.addView(tvId)
    }

    private fun addSection(title: String) {
        val tv = TextView(this).apply {
            text = title
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            val typedValue = android.util.TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)
            setTextColor(typedValue.data)
            setPadding(0, 16, 0, 8)
        }
        layoutProfile.addView(tv)
    }

    private fun addRow(label: String, value: String?) {
        val displayValue = if (value.isNullOrBlank()) "—" else value
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6, 0, 6)
        }
        val tvLabel = TextView(this).apply {
            text = label
            textSize = 14f
            val typedValue = android.util.TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, typedValue, true)
            setTextColor(typedValue.data)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.4f)
        }
        val tvValue = TextView(this).apply {
            text = displayValue
            textSize = 14f
            val typedValue = android.util.TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, typedValue, true)
            setTextColor(typedValue.data)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f)
        }
        row.addView(tvLabel)
        row.addView(tvValue)
        layoutProfile.addView(row)
    }

    private fun maskIdCard(id: String): String {
        if (id.length < 8) return id
        return id.substring(0, 4) + "**********" + id.substring(id.length - 4)
    }
}
