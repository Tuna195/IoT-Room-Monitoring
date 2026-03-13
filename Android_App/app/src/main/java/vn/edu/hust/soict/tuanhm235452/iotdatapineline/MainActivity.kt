
package vn.edu.hust.soict.tuanhm235452.iotdatapineline

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var homeFragment: HomeFragment
    private lateinit var chartFragment: ChartFragment
    private var activeFragment: Fragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Xin quyền & Chạy Service (Giữ nguyên)
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
        val intent = Intent(this, MqttService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)

        // 2. KHỞI TẠO FRAGMENT (QUAN TRỌNG: Chỉ tạo nếu chưa có)
        if (savedInstanceState == null) {
            // Lần đầu mở App -> Tạo mới
            homeFragment = HomeFragment()
            chartFragment = ChartFragment()

            supportFragmentManager.beginTransaction().apply {
                add(R.id.fragment_container, chartFragment, "chart").hide(chartFragment)
                add(R.id.fragment_container, homeFragment, "home")
                commit()
            }
            activeFragment = homeFragment
        } else {
            // App được khôi phục (xoay màn hình...) -> Tìm lại cái cũ, KHÔNG tạo mới
            homeFragment = supportFragmentManager.findFragmentByTag("home") as HomeFragment
            chartFragment = supportFragmentManager.findFragmentByTag("chart") as ChartFragment

            // Xác định cái nào đang hiện
            activeFragment = if (homeFragment.isHidden) chartFragment else homeFragment
        }

        // 3. Xử lý chuyển Tab
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { showFragment(homeFragment); true }
                R.id.nav_chart -> { showFragment(chartFragment); true }
                else -> false
            }
        }
    }

    private fun showFragment(fragment: Fragment) {
        if (activeFragment != fragment) {
            supportFragmentManager.beginTransaction().hide(activeFragment!!).show(fragment).commit()
            activeFragment = fragment
        }
    }
}