package com.cherry.wakeupschedule

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.cherry.wakeupschedule.service.ImportService
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import com.cherry.wakeupschedule.viewmodel.CourseViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SchoolImportActivity : AppCompatActivity() {

    private lateinit var importService: ImportService
    private lateinit var viewModel: CourseViewModel

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleFileImport(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyToTheme(this)
        setContentView(R.layout.activity_school_import)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 设置工具栏
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "导入课程表"

        importService = ImportService(this)
        viewModel = ViewModelProvider(this)[CourseViewModel::class.java]

        setupClickListeners()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun setupClickListeners() {
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_school_import).setOnClickListener {
            openSchoolSelection()
        }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_file_import).setOnClickListener {
            checkPermissionAndOpenFilePicker()
        }
    }

    private fun openSchoolSelection() {
        val intent = Intent(this, SchoolListActivity::class.java)
        startActivity(intent)
    }

    private fun checkPermissionAndOpenFilePicker() {
        // Android 10+ 使用分区存储，不需要权限检查
        openFilePicker()
    }

    private fun openFilePicker() {
        filePickerLauncher.launch("*/*")
    }

    private fun handleFileImport(uri: Uri) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val success = importService.importFromFile(uri)
                if (success) {
                    // 刷新课程数据
                    viewModel.refreshCourses()
                    Toast.makeText(this@SchoolImportActivity, "文件导入成功 ✓", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this@SchoolImportActivity, "导入失败，请检查文件格式", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@SchoolImportActivity, "导入错误: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
