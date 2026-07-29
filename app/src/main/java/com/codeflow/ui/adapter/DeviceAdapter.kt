package com.codeflow.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.codeflow.R
import com.codeflow.databinding.ItemDeviceBinding
import com.codeflow.model.ConnectionType
import com.codeflow.model.Device

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
            binding.tvConnectionType.text = when (device.connectionType) {
                ConnectionType.BLUETOOTH -> binding.root.context.getString(R.string.connection_type_bluetooth)
                ConnectionType.WIFI -> binding.root.context.getString(R.string.connection_type_wifi)
            }
            binding.ivDeviceIcon.setImageResource(
                when (device.connectionType) {
                    ConnectionType.BLUETOOTH -> android.R.drawable.ic_menu_compass
                    ConnectionType.WIFI -> android.R.drawable.ic_menu_manage
                }
            )
            binding.root.setOnClickListener { onDeviceClick(device) }
        }
    }
}
