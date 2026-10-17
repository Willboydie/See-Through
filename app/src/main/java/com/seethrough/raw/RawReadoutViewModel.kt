package com.seethrough.raw

import android.app.Application
import androidx.lifecycle.AndroidViewModel

class RawReadoutViewModel(app: Application) : AndroidViewModel(app) {
    private val archive = TrainingArchive(app)
    val wifi = WifiScanHub(app)
    val training = WifiTrainingSet()

    init {
        archive.load()?.let(training::restore)
        training.onCommit = {
            try {
                archive.save(training.snapshot())
            } catch (error: Exception) {
                training.fail(error.message ?: "Could not save the sample")
            }
        }
        wifi.onNetworks = training::observe
    }

    fun start() {
        wifi.start()
    }

    fun stop() {
        wifi.stop()
    }

    fun labelRoom(room: Int) {
        training.label(room)
    }

    fun upload() {
        if (training.readout.value.sampleCount == 0) {
            training.fail("Label a room before uploading.")
            return
        }
        try {
            training.markStored(archive.storeExport(training.protocolJson()))
        } catch (error: Exception) {
            training.fail(error.message ?: "Could not store the file")
        }
    }

    override fun onCleared() {
        stop()
    }
}
