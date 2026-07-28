package com.cherry.wakeupschedule.ui.adapter

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.cherry.wakeupschedule.model.Course
import com.cherry.wakeupschedule.ui.screen.schedule.WeekPageFragment

class WeekPagerAdapter(
    fragment: Fragment,
    private val totalWeeks: Int
) : FragmentStateAdapter(fragment) {

    private var courses: List<Course> = emptyList()

    override fun getItemCount(): Int = totalWeeks

    override fun createFragment(position: Int): Fragment {
        val week = position + 1
        return WeekPageFragment.newInstance(week, courses)
    }

    fun updateCourses(newCourses: List<Course>) {
        courses = newCourses
        notifyDataSetChanged()
    }
}
