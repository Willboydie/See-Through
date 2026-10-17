package com.seethrough.raw

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel

class FloorplanViewModel(app: Application) : AndroidViewModel(app) {
    val wifi = WifiScanHub(app)
    val uiRoom = mutableStateOf<Int?>(null)
    val problem = mutableStateOf("")

    private val locator: RoomLocator? = load(app)

    init {
        locator?.let { locator ->
            wifi.onNetworks = { networks ->
                if (locator.onScan(strengthsByBssid(networks))) {
                    uiRoom.value = locator.uiRoom
                }
            }
        }
    }

    fun start() {
        wifi.start()
    }

    fun stop() {
        wifi.stop()
    }

    override fun onCleared() {
        stop()
    }

    private fun load(app: Application): RoomLocator? {
        return try {
            val text = app.assets.open(RoomForest.ASSET).bufferedReader().use { it.readText() }
            RoomLocator(RoomForest.read(text))
        } catch (error: Exception) {
            problem.value = when (error) {
                is IllegalArgumentException -> error.message ?: MODEL_PROBLEM
                else -> MODEL_PROBLEM
            }
            null
        }
    }

    private companion object {
        const val MODEL_PROBLEM = "The room model could not be read. Retrain with ml/room_forest.py and rebuild the app."
    }
}
