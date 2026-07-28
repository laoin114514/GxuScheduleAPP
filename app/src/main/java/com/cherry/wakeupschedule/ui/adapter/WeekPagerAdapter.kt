package com.cherry.wakeupschedule.ui.adapter

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.cherry.wakeupschedule.ui.screen.schedule.WeekPageFragment

class WeekPagerAdapter(
    fragment: Fragment,
    private val totalWeeks: Int
) : FragmentStateAdapter(fragment) {

    override fun getItemCount(): Int = totalWeeks

    override fun createFragment(position: Int): Fragment {
        return WeekPageFragment.newInstance(position + 1)
    }
}
