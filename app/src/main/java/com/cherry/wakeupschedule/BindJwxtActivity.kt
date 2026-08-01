package com.cherry.wakeupschedule

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputLayout
import com.cherry.wakeupschedule.service.JwxtAccountManager
import com.cherry.wakeupschedule.service.JwxtAuthManager
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import kotlinx.coroutines.launch

class BindJwxtActivity : AppCompatActivity() {

    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var tvStatus: TextView
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvTitle: TextView
    private lateinit var inputLayoutPassword: TextInputLayout

    private var isPasswordUpdateMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyToTheme(this)
        setContentView(R.layout.activity_bind_jwxt)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        etUsername = findViewById(R.id.et_username)
        etPassword = findViewById(R.id.et_password)
        btnLogin = findViewById(R.id.btn_login)
        tvStatus = findViewById(R.id.tv_status)
        pbLoading = findViewById(R.id.pb_loading)
        tvTitle = findViewById(R.id.tv_title)
        inputLayoutPassword = findViewById(R.id.input_layout_password)
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        isPasswordUpdateMode = JwxtAccountManager.isBound()
        if (isPasswordUpdateMode) {
            tvTitle.text = "更新登录密码"
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
        pbLoading.visibility = View.VISIBLE
        btnLogin.isEnabled = false

        lifecycleScope.launch {
            val result = if (isPasswordUpdateMode) {
                JwxtAuthManager.updatePassword(password)
            } else {
                JwxtAuthManager.login(username, password)
            }
            pbLoading.visibility = View.GONE
            btnLogin.isEnabled = true

            result.onSuccess {
                val message = if (isPasswordUpdateMode) "密码已更新" else "绑定成功！"
                Toast.makeText(this@BindJwxtActivity, message, Toast.LENGTH_SHORT).show()
                finish()
            }.onFailure { e ->
                tvStatus.text = e.message ?: "登录失败，请检查账号密码"
                tvStatus.visibility = View.VISIBLE
            }
        }
    }
}
