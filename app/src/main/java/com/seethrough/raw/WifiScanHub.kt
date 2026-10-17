package com.seethrough.raw

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class WifiScanHub(private val context: Context) {
    private val wifiManager = context.getSystemService(WifiManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val refresh = Runnable { requestScan() }

    val state = mutableStateOf(WifiScanState())
    var onNetworks: (List<WifiNetwork>) -> Unit = {}

    private var running = false
    private var receiverRegistered = false
    private var scanCallbackRegistered = false
    private var lastScanRequestElapsed = 0L

    private val scanCallback = object : WifiManager.ScanResultsCallback() {
        override fun onScanResultsAvailable() {
            publishScanResults()
            scheduleRefresh()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshConditions()
            if (!running) return
            when (intent.action) {
                WifiManager.WIFI_STATE_CHANGED_ACTION,
                WifiManager.NETWORK_STATE_CHANGED_ACTION,
                LocationManager.MODE_CHANGED_ACTION,
                -> {
                    publishScanResults()
                    requestScan()
                }
                WifiManager.RSSI_CHANGED_ACTION -> publishScanResults()
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        registerReceiver()
        refreshConditions()
        ensureScanCallback()
        publishScanResults()
        requestScan()
        scheduleRefresh()
    }

    fun stop() {
        if (!running) return
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        if (receiverRegistered) {
            context.unregisterReceiver(receiver)
            receiverRegistered = false
        }
        if (scanCallbackRegistered) {
            wifiManager.unregisterScanResultsCallback(scanCallback)
            scanCallbackRegistered = false
        }
    }

    fun onPermissionsUpdated() {
        refreshConditions()
        if (!running) return
        ensureScanCallback()
        publishScanResults()
        requestScan()
    }

    private fun publishScanResults() {
        refreshConditions()
        val networks: List<WifiNetwork>
        val session: String
        if (!state.value.wifiEnabled) {
            networks = emptyList()
            session = "wi-fi is off"
        } else if (!hasScanPermission()) {
            networks = emptyList()
            session = "permission needed"
        } else {
            val results = try {
                wifiManager.scanResults
            } catch (_: SecurityException) {
                emptyList()
            }
            val link = readLink()
            networks = withConnected(
                results.map { result -> toNetwork(result, link) },
                link,
            ).sortedByDescending { it.signal }
            session = if (networks.isEmpty()) "no networks in the latest scan" else "${networks.size} networks"
        }
        state.value = state.value.copy(
            networks = networks,
            session = session,
            lastEventClock = clockNow(),
        )
        onNetworks(networks)
    }

    private fun withConnected(networks: List<WifiNetwork>, link: WifiInfo?): List<WifiNetwork> {
        val bssid = link?.bssid ?: return networks
        if (bssid.isBlank() || bssid == "02:00:00:00:00:00") return networks
        val rssi = link.rssi
        if (rssi >= 0) return networks
        val matched = networks.map { network ->
            if (network.bssid.equals(bssid, ignoreCase = true)) {
                network.copy(signal = signalScore(rssi), rssi = rssi, connected = true)
            } else {
                network
            }
        }
        if (matched.any { it.connected }) return matched
        @Suppress("DEPRECATION")
        return matched + WifiNetwork(
            name = networkName(link.ssid ?: "", bssid),
            bssid = bssid,
            signal = signalScore(rssi),
            rssi = rssi,
            frequencyMhz = link.frequency,
            connected = true,
        )
    }

    private fun toNetwork(result: ScanResult, link: WifiInfo?): WifiNetwork {
        val bssid = result.BSSID.orEmpty()
        val rssi = result.level
        return WifiNetwork(
            name = networkName(scanSsid(result), bssid),
            bssid = bssid,
            signal = signalScore(rssi),
            rssi = rssi,
            frequencyMhz = result.frequency,
            connected = link != null && bssid.equals(link.bssid, ignoreCase = true),
        )
    }

    private fun requestScan() {
        if (!running || !state.value.wifiEnabled || !hasScanPermission()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastScanRequestElapsed < SCAN_GAP_MS) return
        lastScanRequestElapsed = now
        try {
            @Suppress("DEPRECATION")
            val accepted = wifiManager.startScan()
            if (!accepted && state.value.networks.isEmpty()) {
                state.value = state.value.copy(session = "scan throttled", lastEventClock = clockNow())
            }
        } catch (error: SecurityException) {
            state.value = state.value.copy(
                session = error.message ?: "scan permission denied",
                lastEventClock = clockNow(),
            )
        }
    }

    private fun scheduleRefresh() {
        mainHandler.removeCallbacks(refresh)
        if (running) {
            mainHandler.postDelayed(refresh, REFRESH_MS)
        }
    }

    private fun refreshConditions() {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val nearby = Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.NEARBY_WIFI_DEVICES)
        state.value = state.value.copy(
            wifiEnabled = wifiManager.wifiState == WifiManager.WIFI_STATE_ENABLED,
            locationEnabled = locationManager.isLocationEnabled,
            fineLocationGranted = fine,
            nearbyWifiGranted = nearby,
        )
    }

    private fun readLink(): WifiInfo? {
        val network = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        return capabilities.transportInfo as? WifiInfo
    }

    private fun ensureScanCallback() {
        if (scanCallbackRegistered || !hasScanPermission()) return
        try {
            wifiManager.registerScanResultsCallback(context.mainExecutor, scanCallback)
            scanCallbackRegistered = true
        } catch (_: SecurityException) {
            state.value = state.value.copy(session = "cannot register for scan results")
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.RSSI_CHANGED_ACTION)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        receiverRegistered = true
    }

    private fun hasScanPermission(): Boolean {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val nearby = Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.NEARBY_WIFI_DEVICES)
        return fine || nearby
    }

    private fun granted(permission: String): Boolean {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun scanSsid(result: ScanResult): String {
        return if (Build.VERSION.SDK_INT >= 33) {
            result.wifiSsid?.toString()?.trim('"') ?: ""
        } else {
            @Suppress("DEPRECATION")
            result.SSID ?: ""
        }
    }

    private fun networkName(ssid: String, bssid: String): String {
        val cleaned = ssid.trim().trim('"')
        if (cleaned.isNotEmpty() && !cleaned.equals("<unknown ssid>", ignoreCase = true)) {
            return cleaned
        }
        return if (bssid.isBlank()) "Hidden network" else "Hidden · $bssid"
    }

    private fun clockNow(): String = CLOCK.format(LocalTime.now())

    companion object {
        private const val MIN_RSSI = -100f
        private const val MAX_RSSI = -30f
        private const val SCAN_GAP_MS = 8_000L
        private const val REFRESH_MS = 8_000L
        private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

        fun signalScore(rssi: Int): Int {
            val clamped = rssi.toFloat().coerceIn(MIN_RSSI, MAX_RSSI)
            val fraction = (clamped - MIN_RSSI) / (MAX_RSSI - MIN_RSSI)
            return (1f + fraction * 999f).toInt().coerceIn(1, 1000)
        }

        fun requiredPermissions(): Array<String> = buildList {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }.toTypedArray()
    }
}
