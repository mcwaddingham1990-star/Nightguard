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
 * or connects to, Wi-Fi networks it joins, and (best-effort, see
 * logTetherStateChange) when this phone's own hotspot/tethering state changes.
 * This intentionally does not scan for or log other nearby devices/networks
 * that the phone never connects to; that would be about detecting who else
 * is nearby rather than what this device did, which is a different (and much
 * more invasive) kind of monitoring than the rest of NightGuard does.
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
            TETHER_STATE_CHANGED_ACTION -> logTetherStateChange(context)
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

    /**
     * Best-effort: TETHER_STATE_CHANGED is an undocumented broadcast (no public
     * ConnectivityManager constant, extra key names vary/aren't stable across Android
     * versions), so this only logs "something about tethering changed," not on/off state
     * or which devices connected. Getting a real per-client device list for this phone's
     * own hotspot requires the NETWORK_SETTINGS/TETHER_PRIVILEGED permission, which is
     * signature/system-only and not grantable to a normal installed app -- there's no way
     * around that from here. Devices *this* phone connects to as a client (including
     * joining someone else's hotspot) are already covered by logWifi()/logBluetooth() above.
     */
    private fun logTetherStateChange(context: Context) {
        scope.launch {
            TimelineRepository(context.applicationContext).log(
                type = EventType.DEVICE_CONNECTION,
                detail = "Wi-Fi hotspot/tethering state changed on this device"
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
        // Undocumented but long-stable broadcast action for tethering state changes;
        // no public ConnectivityManager constant exists for it.
        private const val TETHER_STATE_CHANGED_ACTION = "android.net.conn.TETHER_STATE_CHANGED"

        fun intentFilter(): IntentFilter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(TETHER_STATE_CHANGED_ACTION)
        }

        fun register(context: Context, receiver: DeviceConnectionReceiver) {
            ContextCompat.registerReceiver(context, receiver, intentFilter(), ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }
}
