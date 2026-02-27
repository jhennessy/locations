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
import ch.codelook.locationtracker.domain.repository.PositionRepository
import com.google.android.gms.location.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import kotlin.math.max

@AndroidEntryPoint
class LocationTrackingService : Service() {

    companion object {
        const val TAG = "LocationTrackingService"
        const val NOTIFICATION_CHANNEL_ID = "location_tracking"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "stop_tracking"
        const val ACTION_GEOFENCE_EXIT = "geofence_exit"

        private const val GETTING_FIX_INTERVAL_MS = 2000L
        private const val EXCELLENT_FIX_M = 15f
        private const val GOOD_FIX_M = 50f
        private const val SETTLING_WINDOW_MS = 15000L
        private const val FIX_TIMEOUT_MS = 30000L
        private const val CONTINUOUS_INTERVAL_MS = 5000L
        private const val CONTINUOUS_MIN_DISTANCE_M = 10f
        private const val MIN_BUFFER_INTERVAL_MS = 3000L
        private const val MIN_BUFFER_DISTANCE_M = 5f
        private const val GEOFENCE_REQUEST_ID = "sleep_geofence"
    }

    @Inject lateinit var preferencesManager: PreferencesManager
    @Inject lateinit var locationRepository: LocationRepository
    @Inject lateinit var bufferManager: BufferManager
    @Inject lateinit var positionRepository: PositionRepository

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var geofencingClient: GeofencingClient
    private var bluetoothMesh: BluetoothMeshService? = null
    private var lastPositionUploadTime = 0L

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var locationCallback: LocationCallback? = null
    private var flushTimer: Job? = null
    private var fixTimeoutJob: Job? = null
    private var settlingJob: Job? = null
    private var started = false
    private var bestFixDuringSettling: Location? = null

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

    val blePeers: StateFlow<List<PeerPosition>>
        get() = bluetoothMesh?.peers ?: MutableStateFlow(emptyList())
    val blePeerCount: StateFlow<Int>
        get() = bluetoothMesh?.peerCount ?: MutableStateFlow(0)

    private var lastBufferedLocation: Location? = null
    private var lastBufferedTime = 0L

    private val chargingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    _isCharging.value = true
                    if (_isTracking.value && _trackingMode.value == TrackingMode.SLEEPING) {
                        beginGettingFix("Charger connected")
                    }
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    _isCharging.value = false
                    if (_isTracking.value && _trackingMode.value == TrackingMode.CONTINUOUS) {
                        beginGettingFix("Charger disconnected")
                    }
                }
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

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(chargingReceiver, filter)
        checkChargingState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTracking()
                return START_NOT_STICKY
            }
            ACTION_GEOFENCE_EXIT -> {
                if (_isTracking.value && _trackingMode.value == TrackingMode.SLEEPING) {
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
            startBluetoothMesh()
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
        bluetoothMesh?.stop()
        flushTimer?.cancel()
        fixTimeoutJob?.cancel()
        settlingJob?.cancel()

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

    // --- Tracking mode transitions ---

    private fun beginGettingFix(reason: String) {
        Log.d(TAG, "Getting fix: $reason")
        _trackingMode.value = TrackingMode.GETTING_FIX
        updateNotification("Getting GPS fix...")
        bestFixDuringSettling = null

        stopLocationUpdates()
        removeGeofence()
        fixTimeoutJob?.cancel()
        settlingJob?.cancel()

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, GETTING_FIX_INTERVAL_MS)
            .setMinUpdateIntervalMillis(GETTING_FIX_INTERVAL_MS)
            .build()

        startLocationUpdates(request) { location ->
            _currentLocation.value = location
            _lastFixAccuracy.value = location.accuracy.toDouble()
            if (location.hasSpeed()) _lastSpeed.value = location.speed.toDouble()
            updateBLEPosition(location)
            maybeUploadPosition(location)

            val accuracy = location.accuracy
            if (accuracy <= EXCELLENT_FIX_M) {
                // Excellent fix — transition immediately
                settlingJob?.cancel()
                fixTimeoutJob?.cancel()
                addLocationToBuffer(location, notes = "Fix: ${accuracy.toInt()}m ($reason)")
                transitionFromFix(location)
            } else if (accuracy <= GOOD_FIX_M) {
                // Good fix — start settling window if not already
                if (bestFixDuringSettling == null || accuracy < (bestFixDuringSettling?.accuracy ?: Float.MAX_VALUE)) {
                    bestFixDuringSettling = location
                }
                if (settlingJob == null || settlingJob?.isActive != true) {
                    settlingJob = serviceScope.launch {
                        delay(SETTLING_WINDOW_MS)
                        val best = bestFixDuringSettling ?: location
                        addLocationToBuffer(best, notes = "Settled fix: ${best.accuracy.toInt()}m ($reason)")
                        transitionFromFix(best)
                    }
                }
            }
        }

        fixTimeoutJob = serviceScope.launch {
            delay(FIX_TIMEOUT_MS)
            if (_trackingMode.value == TrackingMode.GETTING_FIX) {
                val best = bestFixDuringSettling ?: _currentLocation.value
                if (best != null) {
                    addLocationToBuffer(best, notes = "Timeout fix: ${best.accuracy.toInt()}m ($reason)")
                }
                transitionFromFix(best ?: _currentLocation.value)
            }
        }
    }

    private fun transitionFromFix(location: Location?) {
        fixTimeoutJob?.cancel()
        settlingJob?.cancel()
        stopLocationUpdates()

        if (_isCharging.value) {
            switchToContinuous()
        } else {
            switchToSleeping(location)
        }
    }

    private fun switchToSleeping(location: Location?) {
        Log.d(TAG, "Switching to sleeping")
        _trackingMode.value = TrackingMode.SLEEPING
        updateNotification("Sleeping")

        location?.let { setupGeofence(it) }
    }

    private fun switchToContinuous() {
        Log.d(TAG, "Switching to continuous")
        _trackingMode.value = TrackingMode.CONTINUOUS
        updateNotification("Continuous tracking")

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, CONTINUOUS_INTERVAL_MS)
            .setMinUpdateIntervalMillis(CONTINUOUS_INTERVAL_MS)
            .setMinUpdateDistanceMeters(CONTINUOUS_MIN_DISTANCE_M)
            .build()

        startLocationUpdates(request) { location ->
            _currentLocation.value = location
            _lastFixAccuracy.value = location.accuracy.toDouble()
            if (location.hasSpeed()) _lastSpeed.value = location.speed.toDouble()
            updateBLEPosition(location)
            maybeUploadPosition(location)
            maybeBufferLocation(location)
        }
    }

    // --- Geofence ---

    @Suppress("MissingPermission")
    private fun setupGeofence(location: Location) {
        val accuracy = location.accuracy.toDouble()
        val speed = if (location.hasSpeed()) location.speed.toDouble() else 0.0
        val radius = max(20.0, max(accuracy * 1.5, speed * 10.0)).toFloat()
        _geofenceRadius.value = radius.toDouble()

        val geofence = Geofence.Builder()
            .setRequestId(GEOFENCE_REQUEST_ID)
            .setCircularRegion(location.latitude, location.longitude, radius)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
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
            Log.d(TAG, "Geofence set: ${radius.toInt()}m at ${location.latitude}, ${location.longitude}")
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

    // --- Charging ---

    private fun checkChargingState() {
        val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        _isCharging.value = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    // --- Bluetooth Mesh ---

    private fun startBluetoothMesh() {
        bluetoothMesh = BluetoothMeshService(this, positionRepository)
        bluetoothMesh?.start()
    }

    private fun updateBLEPosition(location: Location) {
        val deviceId = preferencesManager.selectedDeviceId
        if (deviceId <= 0) return

        bluetoothMesh?.currentPosition = BLEPosition(
            uid = 0, // set by server from auth token
            un = preferencesManager.username ?: "",
            did = deviceId,
            lat = location.latitude,
            lon = location.longitude,
            alt = location.altitude,
            acc = location.accuracy.toDouble(),
            spd = if (location.hasSpeed()) location.speed.toDouble() else null,
            ts = location.time / 1000.0
        )
    }

    private fun maybeUploadPosition(location: Location) {
        val now = System.currentTimeMillis()
        if (now - lastPositionUploadTime < 15000) return
        lastPositionUploadTime = now

        val deviceId = preferencesManager.selectedDeviceId
        if (deviceId <= 0) return

        val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        serviceScope.launch {
            positionRepository.updatePosition(
                deviceId = deviceId,
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = location.altitude,
                accuracy = location.accuracy.toDouble(),
                speed = if (location.hasSpeed()) location.speed.toDouble() else null,
                timestamp = isoFormatter.format(Date(location.time))
            )
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

        val batchSize = preferencesManager.batchSize
        if (preferencesManager.aggressiveUpload || bufferManager.size >= batchSize) {
            serviceScope.launch { flushBuffer() }
        }
    }

    private suspend fun flushBuffer() {
        val pointsToSend = bufferManager.getAndClearAll()
        if (pointsToSend.isEmpty()) return
        _bufferCount.value = 0

        val deviceId = preferencesManager.selectedDeviceId
        if (deviceId <= 0) {
            bufferManager.insertAtFront(pointsToSend)
            _bufferCount.value = bufferManager.size
            return
        }

        val result = locationRepository.uploadLocations(deviceId, pointsToSend)
        result.fold(
            onSuccess = {
                Log.d(TAG, "Uploaded ${pointsToSend.size} points (batch: ${it.batchId})")
                _lastError.value = null
                // Relay BLE peers on successful flush
                val relayDeviceId = preferencesManager.selectedDeviceId
                if (relayDeviceId > 0) {
                    serviceScope.launch {
                        bluetoothMesh?.relayPeersToServer(relayDeviceId)
                    }
                }
            },
            onFailure = { e ->
                Log.e(TAG, "Upload failed: ${e.message}")
                _lastError.value = e.message
                bufferManager.insertAtFront(pointsToSend)
                _bufferCount.value = bufferManager.size
                bufferManager.saveToDisk()
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
