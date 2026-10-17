package com.seethrough.raw

data class WifiNetwork(
    val name: String,
    val bssid: String,
    val signal: Int,
    val rssi: Int,
    val frequencyMhz: Int,
    val connected: Boolean,
)

data class WifiScanState(
    val wifiEnabled: Boolean = false,
    val locationEnabled: Boolean = false,
    val fineLocationGranted: Boolean = false,
    val nearbyWifiGranted: Boolean = false,
    val session: String = "idle",
    val lastEventClock: String = "",
    val networks: List<WifiNetwork> = emptyList(),
)

data class AccessPointRecord(
    val bssid: String,
    val name: String,
)

data class TrainingSample(
    val room: Int,
    val capturedAt: String,
    val strengthByBssid: Map<String, Int>,
)

data class TrainingSnapshot(
    val accessPoints: List<AccessPointRecord>,
    val samples: List<TrainingSample>,
)

data class TrainingRow(
    val index: Int,
    val name: String,
    val bssid: String,
    val signal: Int?,
)

data class TrainingReadout(
    val rows: List<TrainingRow> = emptyList(),
    val sampleCount: Int = 0,
    val roomCounts: List<Int> = List(5) { 0 },
    val lastRoom: Int? = null,
    val message: String = "",
    val storedFile: String = "",
)
