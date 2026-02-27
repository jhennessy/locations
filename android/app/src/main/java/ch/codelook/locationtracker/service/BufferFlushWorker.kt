package ch.codelook.locationtracker.service

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ch.codelook.locationtracker.data.preferences.PreferencesManager
import ch.codelook.locationtracker.domain.repository.LocationRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class BufferFlushWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val locationRepository: LocationRepository,
    private val preferencesManager: PreferencesManager,
    private val bufferManager: BufferManager
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "BufferFlushWorker"
    }

    override suspend fun doWork(): Result {
        val deviceId = preferencesManager.selectedDeviceId
        if (deviceId == -1) {
            Log.d(TAG, "No device selected, skipping flush")
            return Result.success()
        }

        val points = bufferManager.getAndClearAll()
        if (points.isEmpty()) {
            Log.d(TAG, "Buffer empty, nothing to flush")
            return Result.success()
        }

        Log.d(TAG, "Flushing ${points.size} buffered points")

        return try {
            val result = locationRepository.uploadLocations(deviceId, points)
            if (result.isSuccess) {
                Log.d(TAG, "Successfully flushed ${points.size} points")
                Result.success()
            } else {
                Log.w(TAG, "Upload failed, re-buffering points")
                bufferManager.insertAtFront(points)
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload error, re-buffering points", e)
            bufferManager.insertAtFront(points)
            Result.retry()
        }
    }
}
