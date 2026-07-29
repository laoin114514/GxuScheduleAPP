package com.cherry.wakeupschedule

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cherry.wakeupschedule.service.JwxtAccountManager
import com.cherry.wakeupschedule.service.JwxtAuthManager
import kotlinx.coroutines.launch

class BindJwxtActivity : AppCompatActivity() {

    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var tvStatus: TextView
    private lateinit var pbLoading: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bind_jwxt)

        etUsername = findViewById(R.id.et_username)
        etPassword = findViewById(R.id.et_password)
        btnLogin = findViewById(R.id.btn_login)
        tvStatus = findViewById(R.id.tv_status)
        pbLoading = findViewById(R.id.pb_loading)

        applyThemeBackgrounds()

        // 如果已绑定，预填用户名
        if (JwxtAccountManager.isBound()) {
            etUsername.setText(JwxtAccountManager.getUsername())
            etPassword.setText(JwxtAccountManager.getPassword())
        }

        btnLogin.setOnClickListener {
            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (username.isEmpty() || password.isEmpty()) {
                tvStatus.text = "请输入学号和密码"
                tvStatus.visibility = View.VISIBLE
                return@setOnClickListener
            }

            doLogin(username, password)
        }
    }

    private fun applyThemeBackgrounds() {
        // 获取主题颜色
        val surfaceVariant = TypedValue().let { tv ->
            theme.resolveAttribute(com.google.android.material.R.attr.colorSurfaceVariant, tv, true)
            tv.data
        }
        val outlineColor = TypedValue().let { tv ->
            theme.resolveAttribute(com.google.android.material.R.attr.colorOutline, tv, true)
            tv.data
        }
        val primaryColor = TypedValue().let { tv ->
            theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true)
            tv.data
        }

        // 输入框背景：圆角 + surfaceVariant 填充 + outline 描边
        val density = resources.displayMetrics.density
        etUsername.background = GradientDrawable().apply {
            setColor(surfaceVariant)
            cornerRadius = 8f * density
            setStroke((1f * density).toInt(), outlineColor)
        }
        etPassword.background = GradientDrawable().apply {
            setColor(surfaceVariant)
            cornerRadius = 8f * density
            setStroke((1f * density).toInt(), outlineColor)
        }

        // 按钮背景：primaryColor 填充 + 圆角
        btnLogin.background = GradientDrawable().apply {
            setColor(primaryColor)
            cornerRadius = 8f * density
        }
    }

    private fun doLogin(username: String, password: String) {
        tvStatus.visibility = View.GONE
        pbLoading.visibility = View.VISIBLE
        btnLogin.isEnabled = false

        lifecycleScope.launch {
            val result = JwxtAuthManager.login(username, password)
            pbLoading.visibility = View.GONE
            btnLogin.isEnabled = true

            result.onSuccess {
                Toast.makeText(this@BindJwxtActivity, "绑定成功！", Toast.LENGTH_SHORT).show()
                finish()
            }.onFailure { e ->
                tvStatus.text = e.message ?: "登录失败，请检查账号密码"
                tvStatus.visibility = View.VISIBLE
            }
        }
    }
}
