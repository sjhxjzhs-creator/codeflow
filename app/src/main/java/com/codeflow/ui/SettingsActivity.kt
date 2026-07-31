package com.codeflow.ui

import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.codeflow.CodeFlowApp
import com.codeflow.R
import com.codeflow.databinding.ActivitySettingsBinding
import com.codeflow.transfer.ConnectionManager
import com.codeflow.util.AppLog

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var connectionManager: ConnectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        connectionManager = (application as CodeFlowApp).connectionManager

        setupToolbar()
        updateDeviceInfo()
        setupDiscoverableButton()
        updateVersion()
        setupContactAuthor()
        setupGithubLink()
        setupQuickAppLink()
        setupPersonalization()
    }

    override fun onResume() {
        super.onResume()
        refreshLog()
    }

    // ==================== 个性化 ====================

    private fun prefs() = getSharedPreferences("bchat_prefs", Context.MODE_PRIVATE)

    private fun savedThemeMode(): Int =
        prefs().getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    private fun persistThemeMode(mode: Int) {
        prefs().edit().putInt("theme_mode", mode).apply()
    }

    private fun themeModeLabel(mode: Int): String = when (mode) {
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM -> "跟随系统"
        AppCompatDelegate.MODE_NIGHT_NO -> "浅色"
        else -> "深色"
    }

    private fun nextThemeMode(current: Int): Int = when (current) {
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM -> AppCompatDelegate.MODE_NIGHT_NO
        AppCompatDelegate.MODE_NIGHT_NO -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    private fun setupPersonalization() {
        // 外观模式：跟随系统 -> 浅色 -> 深色 循环
        binding.tvThemeValue.text = themeModeLabel(savedThemeMode())
        binding.rowTheme.setOnClickListener {
            val next = nextThemeMode(savedThemeMode())
            persistThemeMode(next)
            AppCompatDelegate.setDefaultNightMode(next)
            binding.tvThemeValue.text = themeModeLabel(next)
        }
        setupLogSection()
    }

    // ==================== 日志 ====================

    private var logExpanded = false

    private fun setupLogSection() {
        binding.rowLog.setOnClickListener { toggleLog() }
        binding.btnClearLog.setOnClickListener {
            AppLog.clear()
            refreshLog()
        }
        binding.btnCopyLog.setOnClickListener {
            val log = AppLog.snapshot()
            if (log.isEmpty()) {
                Toast.makeText(this, "暂无日志可复制", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText("Bchat 日志", log.joinToString("\n"))
            )
            Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
        }
        refreshLog()
    }

    private fun toggleLog() {
        logExpanded = !logExpanded
        binding.logPanel.visibility = if (logExpanded) View.VISIBLE else View.GONE
        binding.logButtons.visibility = if (logExpanded) View.VISIBLE else View.GONE
        if (logExpanded) refreshLog()
    }

    private fun refreshLog() {
        val log = AppLog.snapshot()
        binding.tvAppLog.text = if (log.isEmpty()) {
            "暂无日志。\n连接异常时的关键步骤会记录在这里。"
        } else {
            log.joinToString("\n")
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun updateDeviceInfo() {
        val deviceName = connectionManager.getDeviceName()
        binding.tvDeviceName.text = "设备名称: $deviceName"

        val ip = connectionManager.getNetworkDiscovery().getLocalIpAddress()
        val port = CodeFlowApp.TRANSFER_PORT
        binding.tvIpAddress.text = if (ip != null) "IP地址: $ip" else "IP地址: 获取中..."
        binding.tvPort.text = "端口: $port"
    }

    private fun setupDiscoverableButton() {
        binding.btnDiscoverable.setOnClickListener {
            makeDiscoverable()
        }
    }

    private fun makeDiscoverable() {
        val bt = BluetoothAdapter.getDefaultAdapter()
        if (bt == null || !bt.isEnabled) {
            Toast.makeText(this, R.string.enable_bluetooth, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 60)
        }
        discoverableLauncher.launch(intent)
    }

    private val discoverableLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_CANCELED) {
            Toast.makeText(this, "已取消可被发现", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "设备将在 ${result.resultCode} 秒内可被发现", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupContactAuthor() {
        binding.cardContact.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://b23.tv/6Ih36mU"))
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupGithubLink() {
        binding.cardGithub.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/sjhxjzhs-creator/codeflow"))
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupQuickAppLink() {
        binding.cardQuickApp.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://monkeycode-ai.com/?ic=019fabe0-d0ac-73d4-9279-4374ebb4fd70"))
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateVersion() {
        try {
            val pkg = packageManager.getPackageInfo(packageName, 0)
            binding.tvAppVersion.text = "应用版本: v${pkg.versionName} (${pkg.versionCode})"
        } catch (_: Exception) {
            binding.tvAppVersion.text = "应用版本: v1.0"
        }
    }
}
