package ch.codelook.locationtracker.service

import android.content.Context
import ch.codelook.locationtracker.data.api.models.LocationPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BufferManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val lock = Any()
    private val buffer = mutableListOf<LocationPoint>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val bufferFile: File
        get() = File(context.filesDir, "location_buffer.json")

    init {
        loadFromDisk()
    }

    val size: Int get() = synchronized(lock) { buffer.size }

    fun add(point: LocationPoint) {
        synchronized(lock) { buffer.add(point) }
    }

    fun getAndClearAll(): List<LocationPoint> {
        synchronized(lock) {
            val copy = buffer.toList()
            buffer.clear()
            deleteFile()
            return copy
        }
    }

    fun insertAtFront(points: List<LocationPoint>) {
        synchronized(lock) { buffer.addAll(0, points) }
    }

    fun saveToDisk() {
        synchronized(lock) {
            if (buffer.isEmpty()) return
            try {
                val data = json.encodeToString(buffer.toList())
                bufferFile.writeText(data)
            } catch (_: Exception) {}
        }
    }

    private fun loadFromDisk() {
        try {
            if (!bufferFile.exists()) return
            val data = bufferFile.readText()
            val points = json.decodeFromString<List<LocationPoint>>(data)
            synchronized(lock) { buffer.addAll(0, points) }
            bufferFile.delete()
        } catch (_: Exception) {}
    }

    private fun deleteFile() {
        try { bufferFile.delete() } catch (_: Exception) {}
    }
}
