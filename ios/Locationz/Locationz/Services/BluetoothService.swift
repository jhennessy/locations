import Foundation
import CoreBluetooth
import Combine

/// Compact BLE position payload exchanged between peers.
struct BLEPosition: Codable {
    let uid: Int      // user id
    let un: String    // username
    let did: Int      // device id
    let lat: Double
    let lon: Double
    let alt: Double?
    let acc: Double?
    let spd: Double?
    let ts: String    // ISO 8601 timestamp
}

/// A nearby peer discovered via BLE.
struct PeerPosition: Identifiable {
    let id: Int       // device_id
    let userId: Int
    let username: String
    let latitude: Double
    let longitude: Double
    let altitude: Double?
    let accuracy: Double?
    let speed: Double?
    let timestamp: Date
    var lastSeen: Date

    var isStale: Bool { Date().timeIntervalSince(lastSeen) > 120 }
}

// MARK: - UUIDs

private let serviceUUID = CBUUID(string: "A1B2C3D4-E5F6-7890-ABCD-1234567890AB")
private let positionCharacteristicUUID = CBUUID(string: "A1B2C3D4-E5F6-7890-ABCD-1234567890AC")

/// BLE mesh for peer-to-peer position sharing.
///
/// Advertises a GATT service whose single characteristic contains the device's
/// current position as compact JSON. Scans for nearby peers, connects, reads
/// their position characteristic, and caches the result.
///
/// When the device has internet, discovered peer positions are relayed to the
/// server via the position relay endpoint.
@MainActor
class BluetoothService: NSObject, ObservableObject {
    static let shared = BluetoothService()

    // MARK: Published state

    @Published var isRunning = false
    @Published var peers: [Int: PeerPosition] = [:]  // keyed by device_id
    @Published var bleStatus: String = "Off"

    // MARK: Dependencies

    private let api = APIService.shared

    // MARK: BLE managers

    private var peripheralManager: CBPeripheralManager?
    private var centralManager: CBCentralManager?
    private var positionCharacteristic: CBMutableCharacteristic?

    // MARK: Current position (set by LocationService)

    var currentPosition: BLEPosition?

    // MARK: Scan scheduling

    private let scanDurationForeground: TimeInterval = 3
    private let scanIntervalForeground: TimeInterval = 15
    private let scanDurationBackground: TimeInterval = 3
    private let scanIntervalBackground: TimeInterval = 30

    private var scanTimer: Timer?
    private var scanStopTimer: Timer?
    private var isInBackground = false

    // MARK: Connection tracking

    private var discoveredPeripherals: [UUID: CBPeripheral] = [:]
    private var connectingPeripherals: Set<UUID> = []

    // MARK: Lifecycle

    func start() {
        guard !isRunning else { return }
        isRunning = true
        bleStatus = "Starting..."

        peripheralManager = CBPeripheralManager(
            delegate: self,
            queue: nil,
            options: [CBPeripheralManagerOptionRestoreIdentifierKey: "ch.codelook.locationz.peripheral"]
        )
        centralManager = CBCentralManager(
            delegate: self,
            queue: nil,
            options: [CBCentralManagerOptionRestoreIdentifierKey: "ch.codelook.locationz.central"]
        )

        Log.location.notice("BluetoothService started")
    }

    func stop() {
        isRunning = false
        bleStatus = "Off"

        peripheralManager?.stopAdvertising()
        peripheralManager?.removeAllServices()
        centralManager?.stopScan()
        scanTimer?.invalidate()
        scanStopTimer?.invalidate()

        for (_, peripheral) in discoveredPeripherals {
            centralManager?.cancelPeripheralConnection(peripheral)
        }
        discoveredPeripherals.removeAll()
        connectingPeripherals.removeAll()
        peers.removeAll()

        peripheralManager = nil
        centralManager = nil

        Log.location.notice("BluetoothService stopped")
    }

    func setBackground(_ background: Bool) {
        isInBackground = background
        if isRunning {
            restartScanCycle()
        }
    }

    // MARK: - GATT Server (Peripheral)

    private func setupPeripheral() {
        let characteristic = CBMutableCharacteristic(
            type: positionCharacteristicUUID,
            properties: [.read],
            value: nil,
            permissions: [.readable]
        )
        positionCharacteristic = characteristic

        let service = CBMutableService(type: serviceUUID, primary: true)
        service.characteristics = [characteristic]

        peripheralManager?.add(service)
    }

    private func startAdvertising() {
        peripheralManager?.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [serviceUUID],
            CBAdvertisementDataLocalNameKey: "Locationz",
        ])
        Log.location.debug("BLE advertising started")
    }

    private func currentPositionData() -> Data? {
        guard let pos = currentPosition else { return nil }
        return try? JSONEncoder().encode(pos)
    }

    // MARK: - Scanner (Central)

    private func restartScanCycle() {
        scanTimer?.invalidate()
        scanStopTimer?.invalidate()

        let interval = isInBackground ? scanIntervalBackground : scanIntervalForeground
        scanTimer = Timer.scheduledTimer(withTimeInterval: interval, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.startScan()
            }
        }
        startScan()
    }

    private func startScan() {
        guard let central = centralManager, central.state == .poweredOn else { return }
        central.scanForPeripherals(withServices: [serviceUUID], options: [
            CBCentralManagerScanOptionAllowDuplicatesKey: false,
        ])
        bleStatus = "Scanning..."

        let duration = isInBackground ? scanDurationBackground : scanDurationForeground
        scanStopTimer?.invalidate()
        scanStopTimer = Timer.scheduledTimer(withTimeInterval: duration, repeats: false) { [weak self] _ in
            Task { @MainActor in
                self?.stopScan()
            }
        }
    }

    private func stopScan() {
        centralManager?.stopScan()
        let peerCount = peers.values.filter { !$0.isStale }.count
        bleStatus = peerCount > 0 ? "\(peerCount) peer\(peerCount == 1 ? "" : "s")" : "No peers"
        pruneStalePeers()
    }

    private func pruneStalePeers() {
        let staleThreshold: TimeInterval = 300 // 5 minutes
        let now = Date()
        peers = peers.filter { now.timeIntervalSince($0.value.lastSeen) < staleThreshold }
    }

    // MARK: - Peer position relay

    /// Upload discovered BLE peer positions to the server.
    func relayPeersToServer(relayDeviceId: Int) async {
        let freshPeers = peers.values.filter { !$0.isStale }
        guard !freshPeers.isEmpty else { return }

        do {
            try await api.relayPeerPositions(
                relayDeviceId: relayDeviceId,
                positions: freshPeers.map { peer in
                    ServerRelayPosition(
                        deviceId: peer.id,
                        latitude: peer.latitude,
                        longitude: peer.longitude,
                        altitude: peer.altitude,
                        accuracy: peer.accuracy,
                        speed: peer.speed,
                        timestamp: ISO8601DateFormatter().string(from: peer.timestamp)
                    )
                }
            )
            Log.network.notice("Relayed \(freshPeers.count) peer positions to server")
        } catch {
            Log.network.error("Failed to relay peer positions: \(error.localizedDescription)")
        }
    }
}

// MARK: - CBPeripheralManagerDelegate

extension BluetoothService: CBPeripheralManagerDelegate {
    nonisolated func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        Task { @MainActor in
            switch peripheral.state {
            case .poweredOn:
                setupPeripheral()
                startAdvertising()
            case .poweredOff:
                bleStatus = "BLE Off"
            case .unauthorized:
                bleStatus = "Unauthorized"
            default:
                bleStatus = "Unavailable"
            }
        }
    }

    nonisolated func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        Task { @MainActor in
            if request.characteristic.uuid == positionCharacteristicUUID {
                if let data = currentPositionData() {
                    request.value = data.subdata(in: request.offset..<data.count)
                    peripheral.respond(to: request, withResult: .success)
                } else {
                    peripheral.respond(to: request, withResult: .attributeNotFound)
                }
            } else {
                peripheral.respond(to: request, withResult: .requestNotSupported)
            }
        }
    }

    nonisolated func peripheralManager(_ peripheral: CBPeripheralManager, willRestoreState dict: [String: Any]) {
        Log.location.notice("BLE peripheral state restored")
    }
}

// MARK: - CBCentralManagerDelegate

extension BluetoothService: CBCentralManagerDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        Task { @MainActor in
            if central.state == .poweredOn && isRunning {
                restartScanCycle()
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                                     advertisementData: [String: Any], rssi RSSI: NSNumber) {
        Task { @MainActor in
            let id = peripheral.identifier
            guard !connectingPeripherals.contains(id) else { return }
            discoveredPeripherals[id] = peripheral
            connectingPeripherals.insert(id)
            peripheral.delegate = self
            central.connect(peripheral, options: nil)
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        Task { @MainActor in
            peripheral.discoverServices([serviceUUID])
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        Task { @MainActor in
            connectingPeripherals.remove(peripheral.identifier)
            discoveredPeripherals.removeValue(forKey: peripheral.identifier)
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        Task { @MainActor in
            connectingPeripherals.remove(peripheral.identifier)
            discoveredPeripherals.removeValue(forKey: peripheral.identifier)
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        Log.location.notice("BLE central state restored")
    }
}

// MARK: - CBPeripheralDelegate

extension BluetoothService: CBPeripheralDelegate {
    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        Task { @MainActor in
            guard let services = peripheral.services else {
                centralManager?.cancelPeripheralConnection(peripheral)
                return
            }
            for service in services where service.uuid == serviceUUID {
                peripheral.discoverCharacteristics([positionCharacteristicUUID], for: service)
            }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        Task { @MainActor in
            guard let characteristics = service.characteristics else {
                centralManager?.cancelPeripheralConnection(peripheral)
                return
            }
            for char in characteristics where char.uuid == positionCharacteristicUUID {
                peripheral.readValue(for: char)
            }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        Task { @MainActor in
            defer {
                centralManager?.cancelPeripheralConnection(peripheral)
            }

            guard let data = characteristic.value,
                  let pos = try? JSONDecoder().decode(BLEPosition.self, from: data) else {
                return
            }

            let formatter = ISO8601DateFormatter()
            formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            let ts = formatter.date(from: pos.ts) ?? Date()

            let peer = PeerPosition(
                id: pos.did,
                userId: pos.uid,
                username: pos.un,
                latitude: pos.lat,
                longitude: pos.lon,
                altitude: pos.alt,
                accuracy: pos.acc,
                speed: pos.spd,
                timestamp: ts,
                lastSeen: Date()
            )
            peers[pos.did] = peer
            Log.location.debug("BLE peer: \(pos.un) device=\(pos.did) at \(pos.lat),\(pos.lon)")
        }
    }
}
