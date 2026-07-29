package com.cherry.wakeupschedule

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cherry.wakeupschedule.service.AccountRepository
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

        btnLogin.setOnClickListener {
            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (username.isEmpty() || password.isEmpty()) {
                tvStatus.text = "请输入学号和密码"
                tvStatus.visibility = View.VISIBLE
                return@setOnClickListener
            }

            doBind(username, password)
        }
    }

    private fun doBind(username: String, password: String) {
        tvStatus.visibility = View.GONE
        pbLoading.visibility = View.VISIBLE
        btnLogin.isEnabled = false

        lifecycleScope.launch {
            val result = JwxtAuthManager.testLogin(username, password)
            pbLoading.visibility = View.GONE
            btnLogin.isEnabled = true

            result.onSuccess {
                val repo = AccountRepository.getInstance(this@BindJwxtActivity)
                val account = repo.bindAccount(username, password)
                val intent = Intent(this@BindJwxtActivity, MainActivity::class.java).apply {
                    putExtra("init_account_id", account.id)
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
                Toast.makeText(this@BindJwxtActivity, "绑定成功！正在同步数据...", Toast.LENGTH_SHORT).show()
                finish()
            }.onFailure { e ->
                tvStatus.text = e.message ?: "登录失败，请检查账号密码"
                tvStatus.visibility = View.VISIBLE
            }
        }
    }
}
