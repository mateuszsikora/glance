package com.glance.dashboard

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class DashboardPagerAdapter(
    activity: FragmentActivity,
    private val urls: List<String>,
    private val allowedNavigationOrigins: List<String>
) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = urls.size

    override fun createFragment(position: Int): Fragment {
        return WebViewFragment.newInstance(urls[position], allowedNavigationOrigins)
    }
}
