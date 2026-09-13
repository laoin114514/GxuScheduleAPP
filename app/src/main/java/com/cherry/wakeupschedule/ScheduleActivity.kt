package com.cherry.wakeupschedule

import android.os.Bundle
import com.cherry.wakeupschedule.databinding.ActivityScheduleBinding

class ScheduleActivity : BaseActivity() {
    
    private lateinit var binding: ActivityScheduleBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScheduleBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupScheduleView()
    }
    
    private fun setupScheduleView() {
        // 设置课程表界面
    }
}