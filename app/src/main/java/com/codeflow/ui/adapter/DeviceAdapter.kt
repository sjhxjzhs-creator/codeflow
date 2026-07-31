package com.codeflow.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.codeflow.R
import com.codeflow.databinding.ItemDeviceBinding
import com.codeflow.model.ConnectionType
import com.codeflow.model.Device
import com.codeflow.model.DeviceStatus

class DeviceAdapter(
    private val onDeviceClick: (Device) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

    private val devices = mutableListOf<Device>()

    fun submitList(newDevices: List<Device>) {
        devices.clear()
        devices.addAll(newDevices)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDeviceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size

    inner class ViewHolder(private val binding: ItemDeviceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(device: Device) {
            binding.tvDeviceName.text = device.name
            binding.tvDeviceInfo.text = when (device.connectionType) {
                ConnectionType.BLUETOOTH -> device.bluetoothAddress ?: binding.root.context.getString(R.string.device_info_bt)
                ConnectionType.WIFI -> "${device.ipAddress}:${device.port}" ?: binding.root.context.getString(R.string.device_info_wifi)
            }
            val online = device.status == DeviceStatus.ONLINE || device.status == DeviceStatus.CONNECTING
            binding.tvConnectionType.text = if (online) "在线" else "离线"
            binding.tvConnectionType.setBackgroundResource(
                if (online) R.drawable.bg_status_online else R.drawable.bg_status_offline
            )
            binding.ivDeviceIcon.setImageResource(
                when (device.connectionType) {
                    ConnectionType.BLUETOOTH -> R.drawable.ic_bluetooth
                    ConnectionType.WIFI -> R.drawable.ic_wifi
                }
            )
            binding.root.setOnClickListener { onDeviceClick(device) }
        }
    }
}
