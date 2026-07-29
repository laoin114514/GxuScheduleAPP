package com.cherry.wakeupschedule

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cherry.wakeupschedule.service.AccountRepository
import com.gxu.jwxt.model.StudentProfile
import kotlinx.coroutines.launch

class ProfileActivity : AppCompatActivity() {

    private val accountRepo by lazy { AccountRepository.getInstance(this) }

    private lateinit var btnRefresh: Button
    private lateinit var pbLoading: ProgressBar
    private lateinit var scrollProfile: ScrollView
    private lateinit var layoutProfile: LinearLayout
    private lateinit var tvError: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        btnRefresh = findViewById(R.id.btn_refresh)
        pbLoading = findViewById(R.id.pb_loading)
        scrollProfile = findViewById(R.id.scroll_profile)
        layoutProfile = findViewById(R.id.layout_profile)
        tvError = findViewById(R.id.tv_error)

        btnRefresh.setOnClickListener {
            pbLoading.visibility = View.VISIBLE
            lifecycleScope.launch {
                val accountId = accountRepo.getActiveAccountId()
                if (accountId > 0) {
                    val profile = accountRepo.getProfile(accountId)
                    if (profile != null) {
                        showProfile(profile)
                    } else {
                        showEmptyState()
                    }
                }
                pbLoading.visibility = View.GONE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val accountId = accountRepo.getActiveAccountId()
            if (accountId > 0) {
                // 有活跃账号 → 展示缓存的个人信息（不刷新）
                val profile = accountRepo.getProfile(accountId)
                if (profile != null) {
                    showProfile(profile)
                } else {
                    showEmptyState()
                }
            } else {
                // 未绑定 → 蒙版 + 跳转按钮
                showUnboundMask()
            }
        }
    }

    private fun showProfile(profile: StudentProfile) {
        pbLoading.visibility = View.GONE
        tvError.visibility = View.GONE
        removeUnboundMask()
        layoutProfile.removeAllViews()

        addHeader(profile.getName() ?: "未知", profile.getStudentId() ?: "")

        addSection("基本信息")
        addRow("性别", profile.getGender())
        addRow("出生日期", profile.getBirthDate())
        addRow("民族", profile.getEthnicity())
        addRow("政治面貌", profile.getPoliticalStatus())
        addRow("身份证号", profile.getIdNumber()?.let { maskIdCard(it) })

        addSection("学籍信息")
        addRow("学院", profile.getCollege())
        addRow("专业", profile.getMajor())
        addRow("班级", profile.getClassName())
        addRow("年级", profile.getGrade())
        addRow("培养层次", profile.getEduLevel())
        addRow("培养方式", profile.getEduMode())
        addRow("学制", profile.getSchoolingLength())
        addRow("学籍状态", profile.getStatus())
        addRow("是否在校", profile.getIsAtSchool())

        addSection("入学信息")
        addRow("入学日期", profile.getEnrollDate())
        addRow("招生年度", profile.getAdmitYear())
        addRow("招生专业", profile.getAdmitMajor())
        addRow("招生学院", profile.getAdmitCollege())
        addRow("考生号", profile.getExamNumber())
        addRow("毕业中学", profile.getHighSchool())
        addRow("入学总分", profile.getEntranceScore())
        addRow("生源地", profile.getHometown())

        scrollProfile.visibility = View.VISIBLE
    }

    private fun showEmptyState() {
        pbLoading.visibility = View.GONE
        scrollProfile.visibility = View.GONE
        removeUnboundMask()
        tvError.text = "暂无个人信息"
        tvError.visibility = View.VISIBLE
    }

    private fun showUnboundMask() {
        pbLoading.visibility = View.GONE
        scrollProfile.visibility = View.GONE
        tvError.visibility = View.GONE

        val rootLayout = scrollProfile.parent as? ViewGroup ?: return

        // 避免重复添加蒙版
        val existingMask = rootLayout.findViewWithTag<View>("unbound_mask")
        if (existingMask != null) {
            existingMask.visibility = View.VISIBLE
            return
        }

        val mask = layoutInflater.inflate(R.layout.layout_unbound_mask, rootLayout, false)
        mask.tag = "unbound_mask"
        mask.findViewById<Button>(R.id.btn_bind_now).setOnClickListener {
            startActivity(Intent(this, BindJwxtActivity::class.java))
        }
        rootLayout.addView(mask)
    }

    private fun removeUnboundMask() {
        val rootLayout = scrollProfile.parent as? ViewGroup ?: return
        val mask = rootLayout.findViewWithTag<View>("unbound_mask")
        if (mask != null) {
            mask.visibility = View.GONE
        }
    }

    private fun addHeader(name: String, studentId: String) {
        val tvName = TextView(this).apply {
            text = name
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        val tvId = TextView(this).apply {
            text = "学号: $studentId"
            textSize = 14f
            setTextColor(0xFFAAAAAA.toInt())
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
            setTextColor(0xFF7C4DFF.toInt())
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
            setTextColor(0xFFAAAAAA.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.4f)
        }
        val tvValue = TextView(this).apply {
            text = displayValue
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
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
