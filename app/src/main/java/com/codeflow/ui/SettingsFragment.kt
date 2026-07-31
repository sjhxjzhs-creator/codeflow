package com.codeflow.ui

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.codeflow.CodeFlowApp
import com.codeflow.R
import com.codeflow.databinding.FragmentSettingsBinding
import com.codeflow.transfer.ConnectionManager

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val discoverableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_CANCELED) {
            Toast.makeText(requireContext(), "已取消可被发现", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "设备将在 ${result.data?.getIntExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 60)} 秒内可被发现", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val connectionManager = (requireActivity().application as CodeFlowApp).connectionManager

        updateDeviceInfo(connectionManager)
        updateVersion()
        setupDiscoverable()
        setupAboutLinks()
        updateThemeValue()

        binding.swGlass.isChecked = true
        binding.rowTheme.setOnClickListener {
            val current = currentThemeMode()
            val next = if (current == AppCompatDelegate.MODE_NIGHT_NO) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
            AppCompatDelegate.setDefaultNightMode(next)
            persistThemeMode(next)
            updateThemeValue()
        }
    }

    private fun currentThemeMode(): Int {
        return prefs().getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_NO)
    }

    private fun persistThemeMode(mode: Int) {
        prefs().edit().putInt("theme_mode", mode).apply()
    }

    private fun prefs(): android.content.SharedPreferences {
        return requireContext().getSharedPreferences("bchat_prefs", Context.MODE_PRIVATE)
    }

    private fun updateThemeValue() {
        binding.tvThemeValue.text =
            if (currentThemeMode() == AppCompatDelegate.MODE_NIGHT_YES) "深色" else "浅色"
    }

    private fun updateDeviceInfo(connectionManager: ConnectionManager) {
        val deviceName = connectionManager.getDeviceName()
        binding.tvDeviceName.text = "设备名称: $deviceName"

        val ip = connectionManager.getNetworkDiscovery().getLocalIpAddress()
        val port = CodeFlowApp.TRANSFER_PORT
        binding.tvIpAddress.text = if (ip != null) "IP地址: $ip" else "IP地址: 获取中..."
        binding.tvPort.text = "端口: $port"
    }

    private fun setupDiscoverable() {
        binding.btnDiscoverable.setOnClickListener {
            makeDiscoverable()
        }
    }

    private fun makeDiscoverable() {
        val bt = BluetoothAdapter.getDefaultAdapter()
        if (bt == null || !bt.isEnabled) {
            Toast.makeText(requireContext(), R.string.enable_bluetooth, Toast.LENGTH_SHORT).show()
            return
        }
        discoverableLauncher.launch(
            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 60)
            }
        )
    }

    private fun setupAboutLinks() {
        binding.cardAbout.setOnClickListener {
            try {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/sjhxjzhs-creator/codeflow")
                )
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(requireContext(), "无法打开链接", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateVersion() {
        try {
            val pkg = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            binding.tvAppVersion.text = "应用版本: v${pkg.versionName} (${pkg.versionCode})"
        } catch (_: Exception) {
            binding.tvAppVersion.text = "应用版本: v1.0"
        }
    }
}
