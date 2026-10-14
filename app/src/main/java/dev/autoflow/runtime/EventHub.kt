package dev.autoflow.runtime

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

data class BatteryState(val percent: Int, val plugged: Boolean)
data class NotificationEvent(val packageName: String, val appName: String, val title: String, val text: String)
data class BluetoothDeviceEvent(val connected: Boolean, val name: String)

/** Filled by [AutoFlowNotificationListener]; triggers subscribe while they wait. */
object NotificationBus {
    private val _events = MutableSharedFlow<NotificationEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<NotificationEvent> = _events.asSharedFlow()
    fun post(e: NotificationEvent) { _events.tryEmit(e) }
}

/**
 * Device events as cold flows. Each one registers its receiver/callback only while a trigger is
 * collecting it and unregisters as soon as the trigger fires or the flow stops, which keeps idle
 * battery use near zero. State flows (battery, Wi-Fi, headphones...) emit the current state first.
 */
class EventHub(private val context: Context) {

    private fun broadcasts(vararg actions: String): Flow<Intent> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                trySend(intent)
            }
        }
        val filter = IntentFilter().apply { actions.forEach { addAction(it) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        awaitClose { context.unregisterReceiver(receiver) }
    }

    /** Sticky: the current level arrives right away. */
    fun battery(): Flow<BatteryState> = broadcasts(Intent.ACTION_BATTERY_CHANGED).map { i ->
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        BatteryState(level * 100 / scale, i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
    }

    /** "on", "off" and "unlocked" as they happen. */
    fun screenEvents(): Flow<String> =
        broadcasts(Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_PRESENT).map {
            when (it.action) {
                Intent.ACTION_SCREEN_ON -> "on"
                Intent.ACTION_SCREEN_OFF -> "off"
                else -> "unlocked"
            }
        }

    val isScreenUnlocked: Boolean
        get() = context.getSystemService(PowerManager::class.java).isInteractive &&
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /** Wi-Fi radio on/off. Sticky. */
    fun wifiEnabled(): Flow<Boolean> = broadcasts(WifiManager.WIFI_STATE_CHANGED_ACTION)
        .map { it.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN) }
        .map { it == WifiManager.WIFI_STATE_ENABLED || it == WifiManager.WIFI_STATE_ENABLING }
        .distinctUntilChanged()

    /** Connected to a Wi-Fi network or not. */
    fun wifiConnected(): Flow<Boolean> = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val wifiNetworks = mutableSetOf<Network>()
        fun current() = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        trySend(current())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wifiNetworks += network
                trySend(true)
            }
            override fun onLost(network: Network) {
                wifiNetworks -= network
                trySend(wifiNetworks.isNotEmpty())
            }
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        cm.registerNetworkCallback(request, callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    private val btAdapter: BluetoothAdapter?
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    /** Bluetooth radio on/off; current state first. */
    @SuppressLint("MissingPermission")
    fun bluetoothEnabled(): Flow<Boolean> = flow {
        val adapter = btAdapter ?: throw dev.autoflow.core.engine.BlockException("This device has no Bluetooth")
        emit(adapter.isEnabled)
        broadcasts(BluetoothAdapter.ACTION_STATE_CHANGED).collect { i ->
            when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> emit(true)
                BluetoothAdapter.STATE_OFF -> emit(false)
            }
        }
    }.distinctUntilChanged()

    /** Devices connecting and disconnecting, as they happen. Needs the Nearby devices permission. */
    @SuppressLint("MissingPermission")
    fun bluetoothDevices(): Flow<BluetoothDeviceEvent> =
        broadcasts(BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED).map { i ->
            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            val name = runCatching { device?.name }.getOrNull() ?: device?.address ?: "Unknown device"
            BluetoothDeviceEvent(i.action == BluetoothDevice.ACTION_ACL_CONNECTED, name)
        }

    /** Wired, USB or Bluetooth headphones present; current state first. */
    fun headphones(): Flow<Boolean> = callbackFlow {
        val am = context.getSystemService(AudioManager::class.java)
        fun present() = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADPHONE_TYPES }
        trySend(present())
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { trySend(present()) }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { trySend(present()) }
        }
        am.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
        awaitClose { am.unregisterAudioDeviceCallback(cb) }
    }.distinctUntilChanged()

    /**
     * Apps coming to the foreground. Android has no broadcast for this, so it polls usage events:
     * every 2 s while the screen is on, and only every 15 s while it's off (no app can open then).
     */
    fun appsOpened(): Flow<String> = flow {
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        var since = System.currentTimeMillis()
        val event = UsageEvents.Event()
        while (true) {
            delay(if (power.isInteractive) 2_000 else 15_000)
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(since, now) ?: continue
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                @Suppress("DEPRECATION")
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && event.packageName != context.packageName) {
                    emit(event.packageName)
                }
            }
            since = now
        }
    }

    fun notifications(): Flow<NotificationEvent> = NotificationBus.events

    /**
     * Waits until [states] *changes into* a state that [matches]. With [immediate], a state that
     * already matches when we start counts too. The first emission is taken as the current state.
     */
    suspend fun <T> awaitTransition(states: Flow<T>, immediate: Boolean, matches: (T) -> Boolean): T {
        var previous: Boolean? = null
        return states.first { s ->
            val m = matches(s)
            val fire = if (previous == null) immediate && m else m && previous == false
            previous = m
            fire
        }
    }

    companion object {
        val HEADPHONE_TYPES: Set<Int> = buildSet {
            add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(AudioDeviceInfo.TYPE_USB_HEADSET)
            if (Build.VERSION.SDK_INT >= 31) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }
}
