package com.seethrough.raw

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class TrainingArchive(private val context: Context) {
    private val workingCopy = File(context.filesDir, "wifi-training.json")

    fun load(): TrainingSnapshot? {
        if (!workingCopy.exists()) return null
        return try {
            TrainingProtocol.decode(workingCopy.readText())
        } catch (_: Exception) {
            null
        }
    }

    fun save(snapshot: TrainingSnapshot) {
        workingCopy.writeText(TrainingProtocol.encode(snapshot))
    }

    fun storeExport(json: String): String {
        val name = fileName()
        return try {
            storeInDownloads(name, json)
            name
        } catch (_: Exception) {
            val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val file = File(directory, name)
            file.writeText(json)
            file.absolutePath
        }
    }

    private fun storeInDownloads(name: String, json: String) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create the download")
        try {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(json.toByteArray(Charsets.UTF_8))
            } ?: error("Could not write the download")
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (error: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
    }

    private fun fileName(): String {
        val stamp = FILE_STAMP.format(LocalDateTime.now())
        return "seethrough-wifi-train-$stamp.json"
    }

    companion object {
        private val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
    }
}
