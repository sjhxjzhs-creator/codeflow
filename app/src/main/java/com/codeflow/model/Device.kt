package com.codeflow.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

enum class ConnectionType {
    BLUETOOTH,
    WIFI
}

enum class DeviceStatus {
    ONLINE,
    OFFLINE,
    CONNECTING
}

@Parcelize
data class Device(
    val id: String,
    val name: String,
    val connectionType: ConnectionType,
    val ipAddress: String? = null,
    val port: Int? = null,
    val bluetoothAddress: String? = null,
    val status: DeviceStatus = DeviceStatus.ONLINE
) : Parcelable
