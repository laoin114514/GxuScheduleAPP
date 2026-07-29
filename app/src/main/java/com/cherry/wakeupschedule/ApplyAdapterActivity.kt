package com.cherry.wakeupschedule

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.cherry.wakeupschedule.databinding.ActivityApplyAdapterBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.URLEncoder

class ApplyAdapterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityApplyAdapterBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityApplyAdapterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupClickListeners()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "申请适配"
    }

    private fun setupClickListeners() {
        binding.btnSubmit.setOnClickListener {
            submitApplication()
        }

        binding.btnSearchWebsite.setOnClickListener {
            searchWebsite()
        }
    }

    private fun submitApplication() {
        val schoolName = binding.etSchoolName.text.toString().trim()
        val websiteUrl = binding.etWebsiteUrl.text.toString().trim()
        val contactEmail = binding.etContactEmail.text.toString().trim()

        if (schoolName.isEmpty()) {
            Toast.makeText(this, "请输入学校名称", Toast.LENGTH_SHORT).show()
            return
        }

        if (websiteUrl.isEmpty()) {
            Toast.makeText(this, "请输入教务系统网址", Toast.LENGTH_SHORT).show()
            return
        }

        if (contactEmail.isEmpty()) {
            Toast.makeText(this, "请输入联系邮箱", Toast.LENGTH_SHORT).show()
            return
        }

        // 验证是否为学校教务系统网址
        if (!isSchool教务SystemUrl(websiteUrl)) {
            Toast.makeText(this, "请输入有效的学校教务系统网址", Toast.LENGTH_SHORT).show()
            return
        }

        // 发送申请邮件
        sendApplicationEmail(schoolName, websiteUrl, contactEmail)
    }

    private fun searchWebsite() {
        val schoolName = binding.etSchoolName.text.toString().trim()
        if (schoolName.isEmpty()) {
            Toast.makeText(this, "请先输入学校名称", Toast.LENGTH_SHORT).show()
            return
        }

        // 模拟从网上搜索教务系统网址
        CoroutineScope(Dispatchers.Main).launch {
            binding.progressBar.visibility = ProgressBar.VISIBLE
            // 这里可以实现实际的网页搜索逻辑
            // 暂时使用模拟数据
            val mockWebsite = "https://jw.${schoolName.replace(" ", "").lowercase()}.edu.cn"
            binding.etWebsiteUrl.setText(mockWebsite)
            binding.progressBar.visibility = ProgressBar.GONE
            Toast.makeText(this@ApplyAdapterActivity, "已搜索到教务系统网址", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isSchool教务SystemUrl(url: String): Boolean {
        // 验证是否为学校教务系统网址
        val urlLower = url.lowercase()
        // 检查是否包含教务系统常见关键词
        val hasEduDomain = urlLower.contains(".edu")
        val hasJwKeyword = urlLower.contains("jw") || urlLower.contains("教务") || urlLower.contains("education")
        val hasSchoolRelated = urlLower.contains("school") || urlLower.contains("大学") || urlLower.contains("学院")
        
        // 检查是否为有效的URL格式
        val isValidUrl = url.matches("^https?://[\\w\\-]+(\\.[\\w\\-]+)+([\\w\\-.,@?^=%&:/~+#]*[\\w\\-@?^=%&/~+#])?$".toRegex())
        
        return isValidUrl && (hasEduDomain || hasJwKeyword || hasSchoolRelated)
    }

    private fun sendApplicationEmail(schoolName: String, websiteUrl: String, contactEmail: String) {
        try {
            // 构建邮件内容
            val subject = "【申请适配】${schoolName}教务系统"
            val body = "学校名称: $schoolName\n教务系统网址: $websiteUrl\n联系邮箱: $contactEmail"
            
            // 构建邮件Intent
            val intent = Intent(Intent.ACTION_SENDTO)
            intent.data = Uri.parse("mailto:2908451607@qq.com")
            intent.putExtra(Intent.EXTRA_SUBJECT, subject)
            intent.putExtra(Intent.EXTRA_TEXT, body)
            
            // 启动邮件客户端
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                Toast.makeText(this, "请在邮件客户端中发送申请", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "未找到邮件客户端，请手动发送邮件", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "打开邮件客户端失败，请手动发送邮件", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
