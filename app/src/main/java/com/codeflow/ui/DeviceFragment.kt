package com.codeflow.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.codeflow.CodeFlowApp
import com.codeflow.R
import com.codeflow.databinding.FragmentDeviceBinding
import com.codeflow.model.ConnectionType
import com.codeflow.model.Device
import com.codeflow.transfer.ConnectionManager
import com.codeflow.ui.adapter.DeviceAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DeviceFragment : Fragment() {

    private var _binding: FragmentDeviceBinding? = null
    private val binding get() = _binding!!

    private lateinit var connectionManager: ConnectionManager
    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var prefs: android.content.SharedPreferences

    private var isConnecting = false
    private var isBluetoothMode = false
    private var pendingDevice: Device? = null
    private val discoveredDeviceIds = mutableSetOf<String>()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            refreshDevices()
        } else {
            Toast.makeText(requireContext(), R.string.permission_required, Toast.LENGTH_LONG).show()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (BluetoothAdapter.getDefaultAdapter()?.isEnabled == true) {
            refreshDevices()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDeviceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        connectionManager = (requireActivity().application as CodeFlowApp).connectionManager
        prefs = requireContext().getSharedPreferences("bchat_prefs", android.content.Context.MODE_PRIVATE)

        setupUI()
        observeState()
        toggleMode(isBluetooth = false)
        requestPermissions()
    }

    private fun setupUI() {
        deviceAdapter = DeviceAdapter { device ->
            if (!isConnecting) {
                isConnecting = true
                initiateConnection(device)
            }
        }
        binding.rvDevices.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = deviceAdapter
        }
    }

    private fun toggleMode(isBluetooth: Boolean) {
        this.isBluetoothMode = isBluetooth
        isConnecting = false
        binding.tvSubtitle.text =
            if (isBluetooth) "设备发现：蓝牙" else "设备发现：WiFi 局域网"
        refreshDevices()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            connectionManager.getBluetoothDevices().collectLatest { devices ->
                discoveredDeviceIds.addAll(devices.map { it.id })
                if (isBluetoothMode) {
                    deviceAdapter.submitList(devices)
                    updateEmptyView(devices.isEmpty(), "未发现蓝牙设备")
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            connectionManager.getNetworkDevices().collectLatest { devices ->
                discoveredDeviceIds.addAll(devices.map { it.id })
                if (!isBluetoothMode) {
                    deviceAdapter.submitList(devices)
                    updateEmptyView(devices.isEmpty(), "未发现局域网设备")
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            connectionManager.connectionState.collectLatest { state ->
                when (state) {
                    ConnectionManager.ConnectionState.CONNECTING,
                    ConnectionManager.ConnectionState.AWAITING_ACCEPT -> {
                        binding.progressBar.visibility = View.VISIBLE
                    }
                    ConnectionManager.ConnectionState.CONNECTED -> {
                        binding.progressBar.visibility = View.GONE
                        isConnecting = false
                        pendingDevice?.let { saveFriend(it) }
                        pendingDevice = null
                        startActivity(Intent(requireContext(), TransferActivity::class.java))
                    }
                    ConnectionManager.ConnectionState.DISCONNECTED -> {
                        binding.progressBar.visibility = View.GONE
                        isConnecting = false
                    }
                    else -> {}
                }
            }
        }

        connectionManager.onConnectionRequest = { remoteName, _ ->
            if (isAdded) {
                requireActivity().runOnUiThread {
                    val dialogView = layoutInflater.inflate(R.layout.dialog_connection_request, null)
                    val viewBinding = com.codeflow.databinding.DialogConnectionRequestBinding.bind(dialogView)
                    viewBinding.tvMessage.text = requireContext().getString(R.string.connection_request_msg, remoteName)

                    com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setView(dialogView)
                        .setCancelable(false)
                        .show()
                        .apply {
                            viewBinding.btnReject.setOnClickListener {
                                connectionManager.rejectConnection()
                                isConnecting = false
                                dismiss()
                            }
                            viewBinding.btnAccept.setOnClickListener {
                                connectionManager.acceptConnection()
                                dismiss()
                            }
                        }
                }
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ))
        } else {
            @Suppress("DEPRECATION")
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            requestPermissionLauncher.launch(notGranted.toTypedArray())
        } else {
            refreshDevices()
        }
    }

    private fun refreshDevices() {
        if (isBluetoothMode) {
            val bt = connectionManager.getBluetoothDiscovery()
            if (!bt.isAvailable()) {
                Toast.makeText(requireContext(), "该设备不支持蓝牙", Toast.LENGTH_SHORT).show()
                return
            }
            if (!bt.isEnabled()) {
                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                return
            }
            connectionManager.stopNetworkDiscovery()
            connectionManager.startBluetoothDiscovery()
            connectionManager.startBluetoothServer()
        } else {
            connectionManager.stopBluetoothDiscovery()
            connectionManager.startNetworkDiscovery()
            connectionManager.startNetworkServer()
        }
        binding.progressBar.visibility = View.VISIBLE
    }

    private fun initiateConnection(device: Device) {
        pendingDevice = device
        when (device.connectionType) {
            ConnectionType.BLUETOOTH -> connectionManager.connectViaBluetooth(device)
            ConnectionType.WIFI -> connectionManager.connectViaNetwork(device)
        }
    }

    private fun saveFriend(device: Device) {
        try {
            val friendsJson = prefs.getString("saved_friends", null)
            val friends = if (friendsJson != null) {
                com.google.gson.Gson().fromJson(
                    friendsJson, object : com.google.gson.reflect.TypeToken<List<Device>>() {}.type
                ) ?: mutableListOf<Device>()
            } else {
                mutableListOf<Device>()
            }
            if (friends.none { it.id == device.id }) {
                friends.add(device)
                prefs.edit().putString("saved_friends", com.google.gson.Gson().toJson(friends)).apply()
            }
        } catch (_: Exception) {
        }
    }

    private fun updateEmptyView(isEmpty: Boolean, hint: String? = null) {
        binding.tvEmptyHint.text = hint ?: requireContext().getString(R.string.no_devices)
        binding.tvEmptyHint.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvDevices.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.progressBar.visibility = View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
