package com.codeflow

import android.app.Application
import com.codeflow.transfer.ConnectionManager

class CodeFlowApp : Application() {

    companion object {
        const val SERVICE_UUID = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
        const val SERVICE_NAME = "CodeFlow Transfer"
        const val DISCOVERY_PORT = 53317
        const val TRANSFER_PORT = 53318
        const val MULTICAST_ADDRESS = "224.0.0.167"

        lateinit var instance: CodeFlowApp
            private set

        fun getAppContext(): Application = instance
    }

    val connectionManager: ConnectionManager by lazy {
        ConnectionManager(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
