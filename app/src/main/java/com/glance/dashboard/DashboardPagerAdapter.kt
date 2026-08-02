package com.glance.dashboard

import android.annotation.SuppressLint
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class DashboardPagerAdapter(
    activity: FragmentActivity,
    urls: List<String>,
    private val allowedNavigationOrigins: List<String>
) : FragmentStateAdapter(activity) {

    private var urls: List<String> = urls
    private var generation = 1L

    override fun getItemCount(): Int = urls.size

    override fun createFragment(position: Int): Fragment {
        return WebViewFragment.newInstance(urls[position], allowedNavigationOrigins)
    }

    override fun getItemId(position: Int): Long {
        return (generation shl GENERATION_SHIFT) or position.toLong()
    }

    override fun containsItem(itemId: Long): Boolean {
        val itemGeneration = itemId ushr GENERATION_SHIFT
        val itemPosition = (itemId and POSITION_MASK).toInt()
        return itemGeneration == generation && itemPosition in urls.indices
    }

    @SuppressLint("NotifyDataSetChanged")
    fun replaceUrls(newUrls: List<String>) {
        if (urls == newUrls) return
        urls = newUrls
        generation++
        notifyDataSetChanged()
    }

    companion object {
        private const val GENERATION_SHIFT = 32
        private const val POSITION_MASK = 0xFFFF_FFFFL
    }
}
