package ch.codelook.locationtracker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import ch.codelook.locationtracker.MainActivity
import ch.codelook.locationtracker.R
import ch.codelook.locationtracker.data.api.models.LocationPoint
import ch.codelook.locationtracker.data.preferences.PreferencesManager
import ch.codelook.locationtracker.domain.repository.LocationRepository
import com.google.android.gms.location.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class LocationTrackingService : Service() {

    companion object {
        const val TAG = "LocationTrackingService"
        const val NOTIFICATION_CHANNEL_ID = "location_tracking"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "stop_tracking"
        const val EXTRA_ACTIVITY_TYPE = "activity_type"
        const val EXTRA_TRANSITION_TYPE = "transition_type"

        private const val MOVING_INTERVAL_MS = 5000L
        private const val MOVING_FASTEST_INTERVAL_MS = 3000L
        private const val MOVING_MIN_DISTANCE_M = 10f
        private const val GETTING_FIX_INTERVAL_MS = 2000L
        private const val STATIONARY_CHECK_INTERVAL_MS = 60000L
        private const val GOOD_FIX_ACCURACY_M = 50f
        private const val MAX_FIX_WAIT_MS = 30000L
        private const val STATIONARY_DELAY_MS = 120000L
        private const val MIN_BUFFER_INTERVAL_MS = 3000L
        private const val MIN_BUFFER_DISTANCE_M = 5f
    }

    @Inject lateinit var preferencesManager: PreferencesManager
    @Inject lateinit var locationRepository: LocationRepository

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var activityRecognitionClient: ActivityRecognitionClient

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var locationCallback: LocationCallback? = null
    private var flushTimer: Job? = null
    private var fixTimeoutJob: Job? = null
    private var stationaryDelayJob: Job? = null
    private var started = false

    enum class TrackingMode(val displayName: String) {
        GETTING_FIX("Getting Fix"),
        STATIONARY("Stationary"),
        MOVING("Moving")
    }

    // Observable state
    private val _trackingMode = MutableStateFlow(TrackingMode.GETTING_FIX)
    val trackingMode: StateFlow<TrackingMode> = _trackingMode

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation

    private val _motionActivity = MutableStateFlow("Unknown")
    val motionActivity: StateFlow<String> = _motionActivity

    private val _bufferCount = MutableStateFlow(0)
    val bufferCount: StateFlow<Int> = _bufferCount

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking

    private val buffer = mutableListOf<LocationPoint>()
    private var lastMotionDetectedTime = 0L
    private var lastBufferedLocation: Location? = null
    private var lastBufferedTime = 0L

    inner class LocalBinder : Binder() {
        fun getService(): LocationTrackingService = this@LocationTrackingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        activityRecognitionClient = ActivityRecognition.getClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            return START_NOT_STICKY
        }

        // Handle activity transition forwarded from receiver
        if (intent?.hasExtra(EXTRA_ACTIVITY_TYPE) == true) {
            val activityType = intent.getIntExtra(EXTRA_ACTIVITY_TYPE, -1)
            val transitionType = intent.getIntExtra(EXTRA_TRANSITION_TYPE, -1)
            if (activityType >= 0) {
                handleActivityTransition(activityType, transitionType)
            }
            return START_STICKY
        }

        // Only start tracking once
        if (!started) {
            started = true
            startForeground(NOTIFICATION_ID, createNotification("Starting..."))
            _isTracking.value = true
            beginGettingFix("Service started")
            startActivityRecognition()
            startFlushTimer()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        stopTracking()
        serviceScope.cancel()
        super.onDestroy()
    }

    fun stopTracking() {
        _isTracking.value = false
        started = false
        stopLocationUpdates()
        stopActivityRecognition()
        flushTimer?.cancel()
        fixTimeoutJob?.cancel()
        stationaryDelayJob?.cancel()

        if (buffer.isNotEmpty()) {
            serviceScope.launch { flushBuffer() }
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun flushNow() {
        serviceScope.launch { flushBuffer() }
    }

    // --- Tracking mode transitions ---

    private fun beginGettingFix(reason: String) {
        Log.d(TAG, "Getting fix: $reason")
        _trackingMode.value = TrackingMode.GETTING_FIX
        recordStateChange("Getting fix: $reason")
        updateNotification("Getting GPS fix...")

        stopLocationUpdates()
        fixTimeoutJob?.cancel()

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, GETTING_FIX_INTERVAL_MS)
            .setMinUpdateIntervalMillis(GETTING_FIX_INTERVAL_MS)
            .build()

        startLocationUpdates(request) { location ->
            _currentLocation.value = location
            if (location.accuracy <= GOOD_FIX_ACCURACY_M) {
                completeStationaryTransition(location.accuracy)
            }
        }

        fixTimeoutJob = serviceScope.launch {
            delay(MAX_FIX_WAIT_MS)
            if (_trackingMode.value == TrackingMode.GETTING_FIX) {
                val acc = _currentLocation.value?.accuracy ?: Float.MAX_VALUE
                completeStationaryTransition(acc)
            }
        }
    }

    private fun completeStationaryTransition(accuracy: Float) {
        Log.d(TAG, "Transitioning to stationary (accuracy: ${accuracy}m)")
        fixTimeoutJob?.cancel()
        _trackingMode.value = TrackingMode.STATIONARY
        recordStateChange("Stationary (fix accuracy: ${accuracy.toInt()}m)")
        updateNotification("Stationary")

        stopLocationUpdates()

        // Low-power location monitoring
        val request = LocationRequest.Builder(Priority.PRIORITY_LOW_POWER, STATIONARY_CHECK_INTERVAL_MS)
            .setMinUpdateIntervalMillis(STATIONARY_CHECK_INTERVAL_MS)
            .build()

        startLocationUpdates(request) { location ->
            _currentLocation.value = location
        }
    }

    private fun switchToMoving(reason: String) {
        if (_trackingMode.value == TrackingMode.MOVING) return
        Log.d(TAG, "Moving: $reason")
        fixTimeoutJob?.cancel()
        stationaryDelayJob?.cancel()
        _trackingMode.value = TrackingMode.MOVING
        recordStateChange("Moving: $reason")
        updateNotification("Moving")

        stopLocationUpdates()

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, MOVING_INTERVAL_MS)
            .setMinUpdateIntervalMillis(MOVING_FASTEST_INTERVAL_MS)
            .setMinUpdateDistanceMeters(MOVING_MIN_DISTANCE_M)
            .build()

        startLocationUpdates(request) { location ->
            _currentLocation.value = location
            maybeBufferLocation(location)
        }
    }

    // --- Location updates ---

    @Suppress("MissingPermission")
    private fun startLocationUpdates(request: LocationRequest, onLocation: (Location) -> Unit) {
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { onLocation(it) }
            }
        }
        locationCallback = callback

        try {
            fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission denied", e)
            _lastError.value = "Location permission required"
        }
    }

    private fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            locationCallback = null
        }
    }

    // --- Activity Recognition ---

    @Suppress("MissingPermission")
    private fun startActivityRecognition() {
        val transitions = listOf(
            DetectedActivity.WALKING,
            DetectedActivity.RUNNING,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.IN_VEHICLE,
            DetectedActivity.STILL
        ).flatMap { activityType ->
            listOf(
                ActivityTransition.Builder()
                    .setActivityType(activityType)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build()
            )
        }

        val request = ActivityTransitionRequest(transitions)

        val intent = Intent(this, ActivityRecognitionReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        try {
            activityRecognitionClient.requestActivityTransitionUpdates(request, pendingIntent)
        } catch (e: SecurityException) {
            Log.e(TAG, "Activity recognition permission denied", e)
        }
    }

    private fun stopActivityRecognition() {
        val intent = Intent(this, ActivityRecognitionReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        try {
            activityRecognitionClient.removeActivityTransitionUpdates(pendingIntent)
        } catch (_: Exception) {}
    }

    fun handleActivityTransition(activityType: Int, transitionType: Int) {
        if (transitionType != ActivityTransition.ACTIVITY_TRANSITION_ENTER) return

        val activityName = when (activityType) {
            DetectedActivity.WALKING -> "Walking"
            DetectedActivity.RUNNING -> "Running"
            DetectedActivity.ON_BICYCLE -> "Cycling"
            DetectedActivity.IN_VEHICLE -> "Driving"
            DetectedActivity.STILL -> "Still"
            else -> "Unknown"
        }

        _motionActivity.value = activityName

        val isMoving = activityType in listOf(
            DetectedActivity.WALKING,
            DetectedActivity.RUNNING,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.IN_VEHICLE
        )

        if (isMoving) {
            lastMotionDetectedTime = System.currentTimeMillis()
            if (_trackingMode.value != TrackingMode.MOVING) {
                stationaryDelayJob?.cancel()
                switchToMoving(activityName.lowercase())
            }
        } else if (activityType == DetectedActivity.STILL) {
            if (_trackingMode.value == TrackingMode.MOVING) {
                stationaryDelayJob?.cancel()
                stationaryDelayJob = serviceScope.launch {
                    delay(STATIONARY_DELAY_MS)
                    if (_trackingMode.value == TrackingMode.MOVING) {
                        beginGettingFix("No motion for ${STATIONARY_DELAY_MS / 1000}s")
                    }
                }
            }
        }
    }

    // --- Buffer management ---

    private fun maybeBufferLocation(location: Location) {
        val now = System.currentTimeMillis()
        val last = lastBufferedLocation
        val timeSinceLast = now - lastBufferedTime

        // Skip if too soon and too close to last buffered point
        if (last != null && timeSinceLast < MIN_BUFFER_INTERVAL_MS) {
            val distance = location.distanceTo(last)
            if (distance < MIN_BUFFER_DISTANCE_M) {
                return
            }
        }

        lastBufferedLocation = location
        lastBufferedTime = now
        addLocationToBuffer(location)
    }

    private fun addLocationToBuffer(location: Location, notes: String? = null) {
        val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val point = LocationPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = location.altitude,
            horizontalAccuracy = location.accuracy.toDouble(),
            verticalAccuracy = if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters.toDouble() else null,
            speed = if (location.hasSpeed()) location.speed.toDouble() else null,
            course = if (location.hasBearing()) location.bearing.toDouble() else null,
            timestamp = isoFormatter.format(Date(location.time)),
            notes = notes
        )

        synchronized(buffer) {
            buffer.add(point)
            _bufferCount.value = buffer.size
        }

        if (buffer.size >= preferencesManager.batchSize) {
            serviceScope.launch { flushBuffer() }
        }
    }

    private fun recordStateChange(description: String) {
        val location = _currentLocation.value ?: return
        addLocationToBuffer(location, notes = description)
    }

    private suspend fun flushBuffer() {
        val pointsToSend: List<LocationPoint>
        synchronized(buffer) {
            if (buffer.isEmpty()) return
            pointsToSend = buffer.toList()
            buffer.clear()
            _bufferCount.value = 0
        }

        val deviceId = preferencesManager.selectedDeviceId
        if (deviceId <= 0) return

        val result = locationRepository.uploadLocations(deviceId, pointsToSend)
        result.fold(
            onSuccess = {
                Log.d(TAG, "Uploaded ${pointsToSend.size} points (batch: ${it.batchId})")
                _lastError.value = null
            },
            onFailure = { e ->
                Log.e(TAG, "Upload failed: ${e.message}")
                _lastError.value = e.message
                // Re-add failed points to buffer
                synchronized(buffer) {
                    buffer.addAll(0, pointsToSend)
                    _bufferCount.value = buffer.size
                }
            }
        )
    }

    private fun startFlushTimer() {
        flushTimer?.cancel()
        flushTimer = serviceScope.launch {
            while (isActive) {
                delay(preferencesManager.maxBufferAgeSec * 1000L)
                flushBuffer()
            }
        }
    }

    // --- Notification ---

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.tracking_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.tracking_channel_description)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, LocationTrackingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.tracking_notification_title))
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_tracking_notification)
            .setContentIntent(pendingIntent)
            .addAction(0, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val notification = createNotification(status)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
}
