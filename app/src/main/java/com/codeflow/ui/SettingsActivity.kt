package com.codeflow.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.codeflow.CodeFlowApp
import com.codeflow.R
import com.codeflow.databinding.ActivitySettingsBinding
import com.codeflow.transfer.ConnectionManager

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

    private fun updateVersion() {
        try {
            val pkg = packageManager.getPackageInfo(packageName, 0)
            binding.tvAppVersion.text = "应用版本: v${pkg.versionName} (${pkg.versionCode})"
        } catch (_: Exception) {
            binding.tvAppVersion.text = "应用版本: v1.0"
        }
    }
}
