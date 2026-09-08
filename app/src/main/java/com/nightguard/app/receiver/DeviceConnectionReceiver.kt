package com.nightguard.app.receiver

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import com.nightguard.app.data.TimelineRepository
import com.nightguard.app.data.db.EventType
import com.nightguard.app.util.PermissionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Logs connections the phone itself makes -- Bluetooth devices it pairs with
 * or connects to, and Wi-Fi networks it joins. This intentionally does not
 * scan for or log other nearby devices/networks that the phone never
 * connects to; that would be about detecting who else is nearby rather than
 * what this device did, which is a different (and much more invasive) kind
 * of monitoring than the rest of NightGuard does.
 */
class DeviceConnectionReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> logBluetooth(context, intent, "connected")
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> logBluetooth(context, intent, "disconnected")
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                if (bondState == BluetoothDevice.BOND_BONDED) logBluetooth(context, intent, "paired")
            }
            WifiManager.NETWORK_STATE_CHANGED_ACTION -> logWifi(context)
        }
    }

    private fun logBluetooth(context: Context, intent: Intent, action: String) {
        val device: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
        val name = if (PermissionUtils.hasBluetoothConnectAccess(context)) {
            runCatching { device.name }.getOrNull()
        } else null
        val label = name ?: device.address ?: "unknown device"
        scope.launch {
            TimelineRepository(context.applicationContext).log(
                type = EventType.DEVICE_CONNECTION,
                detail = "Bluetooth $action: $label"
            )
        }
    }

    private fun logWifi(context: Context) {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val info = wifiManager.connectionInfo ?: return
        if (info.networkId == -1) return // not currently associated with a network
        // SSID reads back as a quoted string ("MyNetwork") unless location permission
        // is missing, in which case Android returns the literal string "<unknown ssid>".
        val ssid = info.ssid?.trim('"') ?: "unknown network"
        scope.launch {
            TimelineRepository(context.applicationContext).log(
                type = EventType.DEVICE_CONNECTION,
                detail = "Wi-Fi connected: $ssid"
            )
        }
    }

    companion object {
        fun intentFilter(): IntentFilter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }

        fun register(context: Context, receiver: DeviceConnectionReceiver) {
            ContextCompat.registerReceiver(context, receiver, intentFilter(), ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }
}
