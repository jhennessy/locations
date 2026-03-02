package ch.codelook.locationtracker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.BatteryManager
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

/**
 * Foreground location service using a geofence-based state machine matching iOS:
 *
 *  - **GettingFix**: High-accuracy GPS at 1s. Excellent fix (<=15m) transitions immediately;
 *    good fix (<=50m) starts a 15s settling window; 30s hard timeout.
 *  - **Sleeping**: GPS off, geofence at max(20m, accuracy*1.5, speed*10). Wakes on exit.
 *  - **Continuous**: 10m distance filter, 5s interval — only when charging.
 */
@AndroidEntryPoint
class LocationTrackingService : Service() {

    companion object {
        const val TAG = "LocationTrackingService"
        const val NOTIFICATION_CHANNEL_ID = "location_tracking"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "stop_tracking"
        const val ACTION_GEOFENCE_EXIT = "geofence_exit"

        private const val GETTING_FIX_INTERVAL_MS = 1000L
        private const val CONTINUOUS_INTERVAL_MS = 5000L
        private const val CONTINUOUS_MIN_DISTANCE_M = 10f
        private const val GOOD_FIX_ACCURACY_M = 50f
        private const val EXCELLENT_FIX_ACCURACY_M = 15f
        private const val MAX_FIX_WAIT_MS = 30000L
        private const val SETTLING_DURATION_MS = 15000L
        private const val GEOFENCE_REQUEST_ID = "ch.codelook.locationz.geofence"
        private const val MIN_BUFFER_INTERVAL_MS = 3000L
        private const val MIN_BUFFER_DISTANCE_M = 5f
    }

    @Inject lateinit var preferencesManager: PreferencesManager
    @Inject lateinit var locationRepository: LocationRepository
    @Inject lateinit var bufferManager: BufferManager

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var geofencingClient: GeofencingClient

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var locationCallback: LocationCallback? = null
    private var flushTimer: Job? = null
    private var fixTimeoutJob: Job? = null
    private var started = false

    // Settling state
    private var settlingStartTime: Long = 0L
    private var bestSettlingAccuracy: Float = Float.MAX_VALUE

    enum class TrackingMode(val displayName: String) {
        GETTING_FIX("Getting Fix"),
        SLEEPING("Sleeping"),
        CONTINUOUS("Continuous")
    }

    // Observable state
    private val _trackingMode = MutableStateFlow(TrackingMode.GETTING_FIX)
    val trackingMode: StateFlow<TrackingMode> = _trackingMode

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation

    private val _bufferCount = MutableStateFlow(0)
    val bufferCount: StateFlow<Int> = _bufferCount

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking

    private val _isCharging = MutableStateFlow(false)
    val isCharging: StateFlow<Boolean> = _isCharging

    private val _lastFixAccuracy = MutableStateFlow(50.0)
    val lastFixAccuracy: StateFlow<Double> = _lastFixAccuracy

    private val _lastSpeed = MutableStateFlow(0.0)
    val lastSpeed: StateFlow<Double> = _lastSpeed

    private val _geofenceRadius = MutableStateFlow(20.0)
    val geofenceRadius: StateFlow<Double> = _geofenceRadius

    private var lastBufferedLocation: Location? = null
    private var lastBufferedTime = 0L
    private var lastFlushTime = System.currentTimeMillis()

    // Charging detection
    private val chargingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val wasCharging = _isCharging.value
            _isCharging.value = isDeviceCharging()
            if (wasCharging != _isCharging.value) {
                if (_isCharging.value) handlePluggedIn() else handleUnplugged()
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): LocationTrackingService = this@LocationTrackingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        geofencingClient = LocationServices.getGeofencingClient(this)
        createNotificationChannel()

        _isCharging.value = isDeviceCharging()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(chargingReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTracking()
                return START_NOT_STICKY
            }
            ACTION_GEOFENCE_EXIT -> {
                if (_isTracking.value) {
                    Log.d(TAG, "Geofence exit → getting fix")
                    recordStateChange("Geofence exit")
                    beginGettingFix("Geofence exit")
                }
                return START_STICKY
            }
        }

        if (!started) {
            started = true
            startForeground(NOTIFICATION_ID, createNotification("Starting..."))
            _isTracking.value = true
            preferencesManager.trackingEnabled = true
            _bufferCount.value = bufferManager.size
            beginGettingFix("Service started")
            startFlushTimer()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        try { unregisterReceiver(chargingReceiver) } catch (_: Exception) {}
        stopTracking()
        serviceScope.cancel()
        super.onDestroy()
    }

    fun stopTracking() {
        _isTracking.value = false
        preferencesManager.trackingEnabled = false
        started = false
        stopLocationUpdates()
        removeGeofence()
        flushTimer?.cancel()
        fixTimeoutJob?.cancel()

        bufferManager.saveToDisk()
        if (bufferManager.size > 0) {
            serviceScope.launch { flushBuffer() }
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun flushNow() {
        serviceScope.launch { flushBuffer() }
    }

    // --- Charging ---

    private fun isDeviceCharging(): Boolean {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        return bm.isCharging
    }

    private fun handlePluggedIn() {
        if (!_isTracking.value) return
        Log.d(TAG, "Charger connected")
        recordStateChange("Charger connected")
        when (_trackingMode.value) {
            TrackingMode.SLEEPING -> startContinuousMode("Charger connected")
            TrackingMode.GETTING_FIX -> {} // will route to continuous after fix
            TrackingMode.CONTINUOUS -> {}
        }
    }

    private fun handleUnplugged() {
        if (!_isTracking.value) return
        Log.d(TAG, "Charger disconnected")
        recordStateChange("Charger disconnected")
        if (_trackingMode.value == TrackingMode.CONTINUOUS) {
            beginGettingFix("Charger disconnected")
        }
    }

    // --- Tracking mode transitions ---

    private fun beginGettingFix(reason: String) {
        Log.d(TAG, "→ Getting fix: $reason")
        _trackingMode.value = TrackingMode.GETTING_FIX
        settlingStartTime = 0L
        bestSettlingAccuracy = Float.MAX_VALUE
        recordStateChange("→ Getting fix: $reason")
        updateNotification("Getting GPS fix...")

        stopLocationUpdates()
        removeGeofence()
        fixTimeoutJob?.cancel()

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, GETTING_FIX_INTERVAL_MS)
            .setMinUpdateIntervalMillis(GETTING_FIX_INTERVAL_MS)
            .build()

        startLocationUpdates(request) { location ->
            _currentLocation.value = location
            _lastSpeed.value = if (location.hasSpeed()) location.speed.toDouble() else 0.0
            maybeBufferLocation(location)

            if (_trackingMode.value != TrackingMode.GETTING_FIX) return@startLocationUpdates

            val accuracy = location.accuracy

            if (accuracy <= EXCELLENT_FIX_ACCURACY_M) {
                Log.d(TAG, "Excellent fix: ${accuracy}m — skipping settling")
                if (_isCharging.value) {
                    startContinuousMode("Excellent fix while charging")
                } else {
                    completeSleepTransition(accuracy.toDouble(), "${accuracy.toInt()}m excellent")
                }
            } else if (accuracy <= GOOD_FIX_ACCURACY_M) {
                if (settlingStartTime == 0L) {
                    settlingStartTime = System.currentTimeMillis()
                    bestSettlingAccuracy = accuracy
                    Log.d(TAG, "Settling started: ${accuracy}m")
                } else if (accuracy < bestSettlingAccuracy) {
                    bestSettlingAccuracy = accuracy
                }

                val elapsed = System.currentTimeMillis() - settlingStartTime
                if (elapsed >= SETTLING_DURATION_MS) {
                    Log.d(TAG, "Settling complete: ${bestSettlingAccuracy}m")
                    if (_isCharging.value) {
                        startContinuousMode("Settled fix while charging")
                    } else {
                        completeSleepTransition(
                            bestSettlingAccuracy.toDouble(),
                            "${bestSettlingAccuracy.toInt()}m settled"
                        )
                    }
                }
            }
        }

        fixTimeoutJob = serviceScope.launch {
            delay(MAX_FIX_WAIT_MS)
            if (_trackingMode.value == TrackingMode.GETTING_FIX) {
                val fallback = if (bestSettlingAccuracy < Float.MAX_VALUE) bestSettlingAccuracy.toDouble()
                    else _currentLocation.value?.accuracy?.toDouble() ?: 50.0
                Log.d(TAG, "Fix timeout, using ${fallback}m")
                if (_isCharging.value) {
                    startContinuousMode("Fix timeout while charging")
                } else {
                    completeSleepTransition(fallback, "timeout")
                }
            }
        }
    }

    private fun completeSleepTransition(accuracy: Double, label: String) {
        fixTimeoutJob?.cancel()
        settlingStartTime = 0L
        bestSettlingAccuracy = Float.MAX_VALUE
        _lastFixAccuracy.value = accuracy

        _trackingMode.value = TrackingMode.SLEEPING
        stopLocationUpdates()

        val radius = computeGeofenceRadius()
        _geofenceRadius.value = radius
        setupGeofence(radius)

        Log.d(TAG, "→ Sleeping ($label). Geofence r=${radius.toInt()}m")
        recordStateChange("→ Sleeping ($label)")
        updateNotification("Sleeping (fence: ${radius.toInt()}m)")

        bufferManager.saveToDisk()
    }

    private fun startContinuousMode(reason: String) {
        fixTimeoutJob?.cancel()
        settlingStartTime = 0L
        bestSettlingAccuracy = Float.MAX_VALUE

        _trackingMode.value = TrackingMode.CONTINUOUS
        removeGeofence()

        stopLocationUpdates()

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, CONTINUOUS_INTERVAL_MS)
            .setMinUpdateDistanceMeters(CONTINUOUS_MIN_DISTANCE_M)
            .build()

        startLocationUpdates(request) { location ->
            _currentLocation.value = location
            _lastSpeed.value = if (location.hasSpeed()) location.speed.toDouble() else 0.0
            maybeBufferLocation(location)
        }

        Log.d(TAG, "→ Continuous: $reason")
        recordStateChange("→ Continuous: $reason")
        updateNotification("Continuous (10m filter)")
    }

    private fun computeGeofenceRadius(): Double {
        val acc = _lastFixAccuracy.value
        val spd = _lastSpeed.value
        return maxOf(20.0, acc * 1.5, spd * 10.0)
    }

    // --- Geofence ---

    @Suppress("MissingPermission")
    private fun setupGeofence(radius: Double) {
        val location = _currentLocation.value ?: return
        removeGeofence()

        val geofence = Geofence.Builder()
            .setRequestId(GEOFENCE_REQUEST_ID)
            .setCircularRegion(location.latitude, location.longitude, radius.toFloat())
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofence(geofence)
            .build()

        val intent = Intent(this, GeofenceBroadcastReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        try {
            geofencingClient.addGeofences(request, pendingIntent)
            Log.d(TAG, "Geofence set: ${location.latitude},${location.longitude} r=${radius.toInt()}m")
        } catch (e: SecurityException) {
            Log.e(TAG, "Geofence permission denied", e)
        }
    }

    private fun removeGeofence() {
        try {
            geofencingClient.removeGeofences(listOf(GEOFENCE_REQUEST_ID))
        } catch (_: Exception) {}
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

    // --- Buffer management ---

    private fun maybeBufferLocation(location: Location) {
        val now = System.currentTimeMillis()
        val last = lastBufferedLocation
        val timeSinceLast = now - lastBufferedTime

        if (last != null && timeSinceLast < MIN_BUFFER_INTERVAL_MS) {
            val distance = location.distanceTo(last)
            if (distance < MIN_BUFFER_DISTANCE_M) return
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

        bufferManager.add(point)
        _bufferCount.value = bufferManager.size

        // Check flush thresholds
        val batchSize = if (preferencesManager.aggressiveUpload) 1 else preferencesManager.batchSize
        val maxAge = if (preferencesManager.aggressiveUpload) 30000L else preferencesManager.maxBufferAgeSec * 1000L
        val timeSinceFlush = System.currentTimeMillis() - lastFlushTime

        if (bufferManager.size >= batchSize || timeSinceFlush >= maxAge) {
            lastFlushTime = System.currentTimeMillis()
            serviceScope.launch { flushBuffer() }
        }
    }

    private fun recordStateChange(description: String) {
        val location = _currentLocation.value ?: return
        addLocationToBuffer(location, notes = description)
    }

    private suspend fun flushBuffer() {
        val pointsToSend = bufferManager.getAndClearAll()
        if (pointsToSend.isEmpty()) return
        _bufferCount.value = 0

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
                bufferManager.insertAtFront(pointsToSend)
                bufferManager.saveToDisk()
                _bufferCount.value = bufferManager.size
            }
        )
    }

    private fun startFlushTimer() {
        flushTimer?.cancel()
        flushTimer = serviceScope.launch {
            while (isActive) {
                val maxAge = if (preferencesManager.aggressiveUpload) 30000L
                    else preferencesManager.maxBufferAgeSec * 1000L
                delay(maxAge)
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
