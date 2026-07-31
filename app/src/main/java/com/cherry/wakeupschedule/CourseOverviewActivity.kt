package com.cherry.wakeupschedule

import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cherry.wakeupschedule.adapter.CourseOverviewAdapter
import com.cherry.wakeupschedule.service.CourseDataManager
import com.cherry.wakeupschedule.service.SettingsManager
import com.cherry.wakeupschedule.ui.theme.ThemeManager
import com.google.android.material.appbar.MaterialToolbar
import java.util.Calendar

class CourseOverviewActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var rvCourses: RecyclerView
    private lateinit var tvSummary: TextView
    private lateinit var tvThisWeek: TextView
    private lateinit var adapter: CourseOverviewAdapter

    private val courseColors = intArrayOf(
        Color.parseColor("#E53935"), Color.parseColor("#1E88E5"), Color.parseColor("#43A047"),
        Color.parseColor("#FDD835"), Color.parseColor("#F4511E"), Color.parseColor("#8E24AA"),
        Color.parseColor("#D81B60"), Color.parseColor("#00ACC1"), Color.parseColor("#FFB300"),
        Color.parseColor("#5E35B1"), Color.parseColor("#3949AB"), Color.parseColor("#039BE5"),
        Color.parseColor("#7CB342"), Color.parseColor("#C0CA33"), Color.parseColor("#FB8C00"),
        Color.parseColor("#AB47BC"), Color.parseColor("#E91E63"), Color.parseColor("#00897B"),
        Color.parseColor("#5C6BC0"), Color.parseColor("#26A69A"), Color.parseColor("#66BB6A"),
        Color.parseColor("#6D4C41"), Color.parseColor("#757575"), Color.parseColor("#546E7A")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyToTheme(this)
        setContentView(R.layout.activity_course_overview)

        toolbar = findViewById(R.id.toolbar)
        rvCourses = findViewById(R.id.rv_courses)
        tvSummary = findViewById(R.id.tv_summary)
        tvThisWeek = findViewById(R.id.tv_this_week)

        // 设置导航按钮颜色为主题前景色
        val tv = android.util.TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, tv, true)
        toolbar.navigationIcon?.setTint(tv.data)
        toolbar.setNavigationOnClickListener { finish() }

        val courses = CourseDataManager.getInstance(this).getAllCourses()
        val sortedCourses = courses.sortedWith(compareBy({ it.dayOfWeek }, { it.startTime }))

        // 更新统计摘要
        val currentWeek = getCurrentWeek()
        val thisWeekCourses = sortedCourses.filter { course ->
            course.isActiveInWeek(currentWeek)
        }
        tvSummary.text = "共 ${sortedCourses.size} 门课程"
        tvThisWeek.text = "本周 ${thisWeekCourses.size} 门"

        adapter = CourseOverviewAdapter(this, sortedCourses, courseColors)
        rvCourses.layoutManager = LinearLayoutManager(this)
        rvCourses.adapter = adapter
    }

    private fun getCurrentWeek(): Int {
        val now = Calendar.getInstance()
        val startDate = SettingsManager(this).getSemesterStartDate()
        if (startDate <= 0L) return now.get(Calendar.WEEK_OF_YEAR)

        val diffMillis = now.timeInMillis - startDate
        val diffDays = (diffMillis / (1000 * 60 * 60 * 24)).toInt()
        return (diffDays / 7) + 1
    }
}