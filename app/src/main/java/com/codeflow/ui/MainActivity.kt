package com.codeflow.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.codeflow.R
import com.codeflow.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val fragments = listOf(
        DeviceFragment(),
        SessionFragment(),
        SettingsFragment()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = fragments.size
            override fun createFragment(position: Int): Fragment = fragments[position]
        }
        binding.viewPager.isUserInputEnabled = true

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_device -> { binding.viewPager.setCurrentItem(0, true); true }
                R.id.nav_session -> { binding.viewPager.setCurrentItem(1, true); true }
                R.id.nav_settings -> { binding.viewPager.setCurrentItem(2, true); true }
                else -> false
            }
        }

        binding.viewPager.registerOnPageChangeCallback(object :
            androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val menuItem = when (position) {
                    0 -> R.id.nav_device
                    1 -> R.id.nav_session
                    else -> R.id.nav_settings
                }
                binding.bottomNav.menu.findItem(menuItem).isChecked = true
            }
        })

        binding.viewPager.setCurrentItem(0, false)
    }
}
