package ch.codelook.locationtracker.ui.tracking

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.location.Location
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.codelook.locationtracker.data.api.models.ServerPosition
import ch.codelook.locationtracker.data.preferences.PreferencesManager
import ch.codelook.locationtracker.domain.repository.PositionRepository
import ch.codelook.locationtracker.service.LocationTrackingService
import ch.codelook.locationtracker.service.PeerPosition
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TrackingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val positionRepository: PositionRepository
) : ViewModel() {

    var isTracking by mutableStateOf(false)
    var trackingMode by mutableStateOf("Stopped")
    var bufferCount by mutableStateOf(0)
    var currentLocation by mutableStateOf<Location?>(null)
    var lastError by mutableStateOf<String?>(null)
    var isCharging by mutableStateOf(false)
    var lastFixAccuracy by mutableStateOf(50.0)
    var lastSpeed by mutableStateOf(0.0)
    var geofenceRadius by mutableStateOf(20.0)
    var blePeers by mutableStateOf<List<PeerPosition>>(emptyList())
    var blePeerCount by mutableStateOf(0)
    var serverPositions by mutableStateOf<List<ServerPosition>>(emptyList())

    val batchSize: Int get() = preferencesManager.batchSize
    val deviceName: String? get() = preferencesManager.selectedDeviceName
    val selectedDeviceId: Int get() = preferencesManager.selectedDeviceId

    private var service: LocationTrackingService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as LocationTrackingService.LocalBinder
            service = localBinder.getService()
            bound = true
            observeService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    fun bindService() {
        val intent = Intent(context, LocationTrackingService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbindService() {
        if (bound) {
            context.unbindService(connection)
            bound = false
        }
    }

    private fun observeService() {
        val svc = service ?: return
        viewModelScope.launch {
            svc.isTracking.collectLatest { isTracking = it }
        }
        viewModelScope.launch {
            svc.trackingMode.collectLatest { trackingMode = it.displayName }
        }
        viewModelScope.launch {
            svc.bufferCount.collectLatest { bufferCount = it }
        }
        viewModelScope.launch {
            svc.currentLocation.collectLatest { currentLocation = it }
        }
        viewModelScope.launch {
            svc.lastError.collectLatest { lastError = it }
        }
        viewModelScope.launch {
            svc.isCharging.collectLatest { isCharging = it }
        }
        viewModelScope.launch {
            svc.lastFixAccuracy.collectLatest { lastFixAccuracy = it }
        }
        viewModelScope.launch {
            svc.lastSpeed.collectLatest { lastSpeed = it }
        }
        viewModelScope.launch {
            svc.geofenceRadius.collectLatest { geofenceRadius = it }
        }
        viewModelScope.launch {
            svc.blePeers.collectLatest { blePeers = it }
        }
        viewModelScope.launch {
            svc.blePeerCount.collectLatest { blePeerCount = it }
        }
        // Poll server positions every 15s
        viewModelScope.launch {
            while (isActive) {
                fetchServerPositions()
                delay(15000)
            }
        }
    }

    private suspend fun fetchServerPositions() {
        positionRepository.fetchAllPositions().onSuccess { positions ->
            serverPositions = positions.filter {
                !it.isStale && it.deviceId != selectedDeviceId
            }
        }
    }

    fun startTracking() {
        val intent = Intent(context, LocationTrackingService::class.java)
        context.startForegroundService(intent)
        bindService()
    }

    fun stopTracking() {
        service?.stopTracking()
        isTracking = false
        trackingMode = "Stopped"
    }

    fun flushNow() {
        service?.flushNow()
    }
}
