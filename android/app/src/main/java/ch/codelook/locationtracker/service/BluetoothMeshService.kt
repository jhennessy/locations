package ch.codelook.locationtracker.service

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import ch.codelook.locationtracker.data.api.models.RelayedPosition
import ch.codelook.locationtracker.domain.repository.PositionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.*

@Serializable
data class BLEPosition(
    val uid: Int,
    val un: String,
    val did: Int,
    val lat: Double,
    val lon: Double,
    val alt: Double? = null,
    val acc: Double? = null,
    val spd: Double? = null,
    val ts: Double
)

data class PeerPosition(
    val id: String,
    val userId: Int,
    val username: String,
    val deviceId: Int,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val accuracy: Double?,
    val speed: Double?,
    val timestamp: Date,
    val discoveredAt: Date
) {
    val isStale: Boolean
        get() = (System.currentTimeMillis() - discoveredAt.time) > 300_000
}

class BluetoothMeshService(
    private val context: Context,
    private val positionRepository: PositionRepository
) {
    companion object {
        private const val TAG = "BluetoothMesh"
        val SERVICE_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-7890-ABCD-1234567890AB")
        val POSITION_CHAR_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-7890-ABCD-1234567890AC")
        private const val SCAN_DURATION_MS = 3000L
        private const val SCAN_INTERVAL_FOREGROUND_MS = 15000L
        private const val SCAN_INTERVAL_BACKGROUND_MS = 30000L
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var bluetoothManager: BluetoothManager? = null
    private var gattServer: BluetoothGattServer? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var scanJob: Job? = null
    private var isAdvertising = false

    private val discoveredDevices = mutableSetOf<String>()

    var currentPosition: BLEPosition? = null
    var isBackground = false

    private val _peers = MutableStateFlow<List<PeerPosition>>(emptyList())
    val peers: StateFlow<List<PeerPosition>> = _peers

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount

    @SuppressLint("MissingPermission")
    fun start() {
        if (_isRunning.value) return
        Log.i(TAG, "Starting Bluetooth mesh service")
        _isRunning.value = true

        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: run {
            Log.w(TAG, "Bluetooth not available")
            return
        }

        bleAdvertiser = adapter.bluetoothLeAdvertiser
        bleScanner = adapter.bluetoothLeScanner

        startGattServer()
        startAdvertising()
        startScanCycle()
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        Log.i(TAG, "Stopping Bluetooth mesh service")
        _isRunning.value = false
        scanJob?.cancel()

        try { bleScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        try {
            if (isAdvertising) {
                bleAdvertiser?.stopAdvertising(advertiseCallback)
                isAdvertising = false
            }
        } catch (_: Exception) {}
        try { gattServer?.close() } catch (_: Exception) {}

        gattServer = null
        discoveredDevices.clear()
        _peers.value = emptyList()
        _peerCount.value = 0
        scope.coroutineContext.cancelChildren()
    }

    @SuppressLint("MissingPermission")
    private fun startGattServer() {
        val characteristic = BluetoothGattCharacteristic(
            POSITION_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)

        gattServer = bluetoothManager?.openGattServer(context, gattServerCallback)
        gattServer?.addService(service)
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(false)
            .build()

        try {
            bleAdvertiser?.startAdvertising(settings, data, advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start advertising", e)
        }
    }

    private fun startScanCycle() {
        scanJob?.cancel()
        scanJob = scope.launch {
            while (isActive) {
                performScan()
                val interval = if (isBackground) SCAN_INTERVAL_BACKGROUND_MS else SCAN_INTERVAL_FOREGROUND_MS
                delay(interval)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun performScan() {
        val scanner = bleScanner ?: return
        discoveredDevices.clear()

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            delay(SCAN_DURATION_MS)
            scanner.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Scan error", e)
        }

        pruneStalePeers()
    }

    private fun pruneStalePeers() {
        val current = _peers.value.filter { !it.isStale }
        _peers.value = current
        _peerCount.value = current.size
    }

    suspend fun relayPeersToServer(relayDeviceId: Int) {
        val currentPeers = _peers.value.filter { !it.isStale }
        if (currentPeers.isEmpty()) return

        val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val relayPositions = currentPeers.map { peer ->
            RelayedPosition(
                deviceId = peer.deviceId,
                latitude = peer.latitude,
                longitude = peer.longitude,
                altitude = peer.altitude,
                accuracy = peer.accuracy,
                speed = peer.speed,
                timestamp = isoFormatter.format(peer.timestamp)
            )
        }

        positionRepository.relayPositions(relayDeviceId, relayPositions)
    }

    // --- GATT Server Callback ---

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            if (characteristic?.uuid == POSITION_CHAR_UUID) {
                val position = currentPosition
                if (position != null) {
                    val data = json.encodeToString(position).toByteArray()
                    val responseData = if (offset < data.size) {
                        data.copyOfRange(offset, data.size)
                    } else {
                        ByteArray(0)
                    }
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, responseData)
                } else {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
            }
        }
    }

    // --- Advertise Callback ---

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            Log.i(TAG, "Advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            Log.e(TAG, "Advertising failed: $errorCode")
        }
    }

    // --- Scan Callback ---

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val address = device.address
            if (discoveredDevices.contains(address)) return
            discoveredDevices.add(address)

            Log.d(TAG, "Discovered peer: $address")
            connectAndReadPosition(device)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectAndReadPosition(device: BluetoothDevice) {
        device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt?.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    gatt?.close()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    gatt?.close()
                    return
                }

                val service = gatt?.getService(SERVICE_UUID)
                val characteristic = service?.getCharacteristic(POSITION_CHAR_UUID)
                if (characteristic != null) {
                    gatt.readCharacteristic(characteristic)
                } else {
                    gatt?.close()
                }
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int
            ) {
                gatt?.close()

                if (status != BluetoothGatt.GATT_SUCCESS || characteristic?.value == null) return

                try {
                    val blePos = json.decodeFromString<BLEPosition>(String(characteristic.value))
                    val peer = PeerPosition(
                        id = device.address,
                        userId = blePos.uid,
                        username = blePos.un,
                        deviceId = blePos.did,
                        latitude = blePos.lat,
                        longitude = blePos.lon,
                        altitude = blePos.alt,
                        accuracy = blePos.acc,
                        speed = blePos.spd,
                        timestamp = Date((blePos.ts * 1000).toLong()),
                        discoveredAt = Date()
                    )

                    val updated = _peers.value.toMutableList()
                    updated.removeAll { it.deviceId == peer.deviceId }
                    updated.add(peer)
                    _peers.value = updated
                    _peerCount.value = updated.size

                    Log.i(TAG, "Got position from ${blePos.un} (device ${blePos.did})")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode BLE position", e)
                }
            }
        })
    }
}
