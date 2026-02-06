package ch.codelook.locationtracker.service

import android.content.Context
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
    private val preferencesManager: PreferencesManager
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        // This worker acts as a safety net.
        // The actual buffer is managed by LocationTrackingService.
        // If the service is running, we don't need to do anything here
        // as the service handles its own flush timer.
        return Result.success()
    }
}
