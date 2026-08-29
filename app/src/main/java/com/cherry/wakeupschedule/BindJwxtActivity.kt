package com.cherry.wakeupschedule

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputLayout
import com.cherry.wakeupschedule.service.JwxtAccountManager
import com.cherry.wakeupschedule.service.JwxtAuthManager
import com.cherry.wakeupschedule.service.JwxtLoginStep
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import com.cherry.wakeupschedule.ui.theme.setupPageHeader
import kotlinx.coroutines.launch

class BindJwxtActivity : AppCompatActivity() {

    /** 步骤卡片里一行（图标 + 文案）的三态：未开始灰点 → 转圈 → 打勾/叉号 */
    private class StepRow(
        val root: View,
        val dot: View,
        val spinner: View,
        val check: View,
        val fail: View,
        val label: TextView,
        val baseText: String,
        val runningText: String,
        val successText: String
    )

    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var tvStatus: TextView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var inputLayoutPassword: TextInputLayout
    private lateinit var groupForm: View
    private lateinit var cardSteps: View
    private lateinit var tvBrandTitle: TextView
    private lateinit var tvBrandSubtitle: TextView
    private lateinit var stepRows: List<StepRow>

    private var isPasswordUpdateMode = false

    /** 已收到完成回调的步骤下标，用于收尾时判断课表是否导入成功 */
    private val reportedSteps = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyToTheme(this)
        setContentView(R.layout.activity_bind_jwxt)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        etUsername = findViewById(R.id.et_username)
        etPassword = findViewById(R.id.et_password)
        btnLogin = findViewById(R.id.btn_login)
        tvStatus = findViewById(R.id.tv_status)
        toolbar = findViewById(R.id.toolbar)
        inputLayoutPassword = findViewById(R.id.input_layout_password)
        groupForm = findViewById(R.id.group_form)
        cardSteps = findViewById(R.id.card_steps)
        tvBrandTitle = findViewById(R.id.tv_brand_title)
        tvBrandSubtitle = findViewById(R.id.tv_brand_subtitle)
        stepRows = listOf(
            StepRow(
                findViewById(R.id.step_row_login), findViewById(R.id.step_login_dot),
                findViewById(R.id.step_login_spinner), findViewById(R.id.step_login_check),
                findViewById(R.id.step_login_fail), findViewById(R.id.step_login_text),
                "登录教务账号", "正在登录教务账号…", "登录成功"
            ),
            StepRow(
                findViewById(R.id.step_row_profile), findViewById(R.id.step_profile_dot),
                findViewById(R.id.step_profile_spinner), findViewById(R.id.step_profile_check),
                findViewById(R.id.step_profile_fail), findViewById(R.id.step_profile_text),
                "获取个人信息", "正在获取个人信息…", "获取个人信息成功"
            ),
            StepRow(
                findViewById(R.id.step_row_semester), findViewById(R.id.step_semester_dot),
                findViewById(R.id.step_semester_spinner), findViewById(R.id.step_semester_check),
                findViewById(R.id.step_semester_fail), findViewById(R.id.step_semester_text),
                "获取学期信息", "正在获取学期信息…", "获取学期信息成功"
            ),
            StepRow(
                findViewById(R.id.step_row_schedule), findViewById(R.id.step_schedule_dot),
                findViewById(R.id.step_schedule_spinner), findViewById(R.id.step_schedule_check),
                findViewById(R.id.step_schedule_fail), findViewById(R.id.step_schedule_text),
                "导入本学期课表", "正在导入本学期课表…", "课表导入完成"
            )
        )
        setupPageHeader(toolbar, "绑定教务系统")

        isPasswordUpdateMode = JwxtAccountManager.isBound()
        if (isPasswordUpdateMode) {
            toolbar.title = "更新登录密码"
            tvBrandTitle.text = "更新登录密码"
            tvBrandSubtitle.text = "验证新密码后，本机保存的凭据将同步更新"
            etUsername.setText(JwxtAccountManager.getUsername())
            etUsername.isFocusable = false
            etUsername.isFocusableInTouchMode = false
            etUsername.isClickable = false
            etUsername.isLongClickable = false
            etUsername.setTextIsSelectable(false)
            inputLayoutPassword.hint = "新密码"
            btnLogin.text = "验证并更新"
        }

        btnLogin.setOnClickListener {
            val password = etPassword.text.toString().trim()
            val username = if (isPasswordUpdateMode) {
                JwxtAccountManager.getUsername()
            } else {
                etUsername.text.toString().trim()
            }

            if (username.isEmpty() || password.isEmpty()) {
                tvStatus.text = if (isPasswordUpdateMode) "请输入新密码" else "请输入学号和密码"
                tvStatus.visibility = View.VISIBLE
                return@setOnClickListener
            }

            doLogin(username, password)
        }
    }

    private fun doLogin(username: String, password: String) {
        tvStatus.visibility = View.GONE
        btnLogin.isEnabled = false

        // 表单让位给步骤卡片
        groupForm.visibility = View.GONE
        cardSteps.visibility = View.VISIBLE
        resetSteps()

        lifecycleScope.launch {
            if (isPasswordUpdateMode) {
                // 更新密码只有一步：验证凭据
                stepRows.drop(1).forEach { it.root.visibility = View.GONE }
                setStepRunning(0, "正在验证新密码…")
                val result = JwxtAuthManager.updatePassword(password)
                handleResult(result, scheduleImported = true)
            } else {
                setStepRunning(0)
                val result = JwxtAuthManager.login(username, password) { step ->
                    val index = when (step) {
                        JwxtLoginStep.LOGIN -> 0
                        JwxtLoginStep.PROFILE -> 1
                        JwxtLoginStep.SEMESTER -> 2
                        JwxtLoginStep.SCHEDULE -> 3
                    }
                    runOnUiThread {
                        if (!reportedSteps.add(index)) return@runOnUiThread
                        setStepSuccess(index)
                        if (index + 1 < stepRows.size) setStepRunning(index + 1)
                    }
                }
                handleResult(result, scheduleImported = 3 in reportedSteps)
            }
        }
    }

    private fun handleResult(result: Result<String>, scheduleImported: Boolean) {
        result.onSuccess {
            val message = when {
                isPasswordUpdateMode -> "密码已更新"
                scheduleImported -> "绑定成功！"
                else -> "绑定成功，但课表导入失败，可在课表页刷新"
            }
            Toast.makeText(this@BindJwxtActivity, message, Toast.LENGTH_SHORT).show()
            finish()
        }.onFailure { e ->
            // 回到表单让用户修改重试，错误原因带步骤上下文
            cardSteps.visibility = View.GONE
            groupForm.visibility = View.VISIBLE
            btnLogin.isEnabled = true
            tvStatus.text = e.message ?: "登录失败，请检查账号密码"
            tvStatus.visibility = View.VISIBLE
        }
    }

    private fun resetSteps() {
        reportedSteps.clear()
        stepRows.forEach { row ->
            row.root.visibility = View.VISIBLE
            row.dot.visibility = View.VISIBLE
            row.spinner.visibility = View.GONE
            row.check.visibility = View.GONE
            row.fail.visibility = View.GONE
            row.label.text = row.baseText
        }
    }

    private fun setStepRunning(index: Int, text: String = stepRows[index].runningText) {
        val row = stepRows[index]
        row.dot.visibility = View.GONE
        row.spinner.visibility = View.VISIBLE
        row.label.text = text
    }

    private fun setStepSuccess(index: Int) {
        val row = stepRows[index]
        row.spinner.visibility = View.GONE
        row.check.visibility = View.VISIBLE
        row.label.text = row.successText
    }
}
