import CoreBluetooth
import CoreLocation
import os.log

struct BLEPosition: Codable {
    let uid: Int      // user id
    let un: String    // username
    let did: Int      // device id
    let lat: Double
    let lon: Double
    let alt: Double?
    let acc: Double?
    let spd: Double?
    let ts: Double    // unix timestamp
}

struct PeerPosition: Identifiable {
    let id: String // peripheral identifier
    let userId: Int
    let username: String
    let deviceId: Int
    let latitude: Double
    let longitude: Double
    let altitude: Double?
    let accuracy: Double?
    let speed: Double?
    let timestamp: Date
    let discoveredAt: Date

    var isStale: Bool {
        Date().timeIntervalSince(discoveredAt) > 300
    }
}

class BluetoothService: NSObject, ObservableObject {
    static let shared = BluetoothService()

    private let logger = Logger(subsystem: "ch.codelook.locationz", category: "Bluetooth")

    static let serviceUUID = CBUUID(string: "A1B2C3D4-E5F6-7890-ABCD-1234567890AB")
    static let positionCharUUID = CBUUID(string: "A1B2C3D4-E5F6-7890-ABCD-1234567890AC")

    private var peripheralManager: CBPeripheralManager?
    private var centralManager: CBCentralManager?
    private var positionCharacteristic: CBMutableCharacteristic?

    @Published var peers: [PeerPosition] = []
    @Published var isRunning = false
    @Published var connectedPeerCount = 0

    var currentPosition: BLEPosition?
    var isBackground = false

    private var scanTimer: Timer?
    private var discoveredPeripherals: [UUID: CBPeripheral] = [:]

    private override init() {
        super.init()
    }

    func start() {
        guard !isRunning else { return }
        logger.info("Starting Bluetooth service")
        isRunning = true

        peripheralManager = CBPeripheralManager(
            delegate: self, queue: nil,
            options: [CBPeripheralManagerOptionRestoreIdentifierKey: "ch.codelook.locationz.peripheral"]
        )
        centralManager = CBCentralManager(
            delegate: self, queue: nil,
            options: [CBCentralManagerOptionRestoreIdentifierKey: "ch.codelook.locationz.central"]
        )
    }

    func stop() {
        logger.info("Stopping Bluetooth service")
        isRunning = false
        scanTimer?.invalidate()
        scanTimer = nil

        centralManager?.stopScan()
        for (_, peripheral) in discoveredPeripherals {
            centralManager?.cancelPeripheralConnection(peripheral)
        }
        discoveredPeripherals.removeAll()

        if peripheralManager?.isAdvertising == true {
            peripheralManager?.stopAdvertising()
        }
        peripheralManager?.removeAllServices()

        peripheralManager = nil
        centralManager = nil
        peers.removeAll()
    }

    private func startAdvertising() {
        guard let pm = peripheralManager, pm.state == .poweredOn else { return }

        let characteristic = CBMutableCharacteristic(
            type: Self.positionCharUUID,
            properties: [.read],
            value: nil,
            permissions: [.readable]
        )
        positionCharacteristic = characteristic

        let service = CBMutableService(type: Self.serviceUUID, primary: true)
        service.characteristics = [characteristic]

        pm.removeAllServices()
        pm.add(service)
    }

    private func startScanCycle() {
        scanTimer?.invalidate()
        let interval: TimeInterval = isBackground ? 30 : 15
        scanTimer = Timer.scheduledTimer(withTimeInterval: interval, repeats: true) { [weak self] _ in
            self?.performScan()
        }
        performScan()
    }

    private func performScan() {
        guard let cm = centralManager, cm.state == .poweredOn else { return }
        logger.debug("Starting BLE scan")
        cm.scanForPeripherals(withServices: [Self.serviceUUID], options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])

        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in
            self?.centralManager?.stopScan()
            self?.pruneStalePeers()
        }
    }

    private func pruneStalePeers() {
        peers.removeAll { $0.isStale }
    }

    func relayPeersToServer(relayDeviceId: Int) async {
        let currentPeers = peers.filter { !$0.isStale }
        guard !currentPeers.isEmpty else { return }

        let relayPositions = currentPeers.map { peer in
            APIService.ServerRelayPosition(
                device_id: peer.deviceId,
                latitude: peer.latitude,
                longitude: peer.longitude,
                altitude: peer.altitude,
                accuracy: peer.accuracy,
                speed: peer.speed,
                timestamp: ISO8601DateFormatter().string(from: peer.timestamp)
            )
        }

        await APIService.shared.relayPeerPositions(relayDeviceId: relayDeviceId, positions: relayPositions)
    }
}

// MARK: - CBPeripheralManagerDelegate
extension BluetoothService: CBPeripheralManagerDelegate {
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        if peripheral.state == .poweredOn {
            startAdvertising()
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error = error {
            logger.error("Failed to add service: \(error.localizedDescription)")
            return
        }
        peripheral.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [Self.serviceUUID],
            CBAdvertisementDataLocalNameKey: "LocationTracker"
        ])
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        if request.characteristic.uuid == Self.positionCharUUID {
            if let position = currentPosition,
               let data = try? JSONEncoder().encode(position) {
                request.value = data
                peripheral.respond(to: request, withResult: .success)
            } else {
                peripheral.respond(to: request, withResult: .attributeNotFound)
            }
        }
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error = error {
            logger.error("Advertising failed: \(error.localizedDescription)")
        } else {
            logger.info("Advertising started")
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, willRestoreState dict: [String: Any]) {
        logger.info("Peripheral manager restoring state")
    }
}

// MARK: - CBCentralManagerDelegate
extension BluetoothService: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn {
            startScanCycle()
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let id = peripheral.identifier
        guard discoveredPeripherals[id] == nil else { return }

        logger.debug("Discovered peer: \(id)")
        discoveredPeripherals[id] = peripheral
        peripheral.delegate = self
        central.connect(peripheral, options: nil)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        logger.debug("Connected to peer: \(peripheral.identifier)")
        peripheral.discoverServices([Self.serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        discoveredPeripherals.removeValue(forKey: peripheral.identifier)
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        logger.info("Central manager restoring state")
    }
}

// MARK: - CBPeripheralDelegate
extension BluetoothService: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services else { return }
        for service in services where service.uuid == Self.serviceUUID {
            peripheral.discoverCharacteristics([Self.positionCharUUID], for: service)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let characteristics = service.characteristics else { return }
        for char in characteristics where char.uuid == Self.positionCharUUID {
            peripheral.readValue(for: char)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        defer {
            centralManager?.cancelPeripheralConnection(peripheral)
            discoveredPeripherals.removeValue(forKey: peripheral.identifier)
        }

        guard let data = characteristic.value else { return }

        do {
            let blePos = try JSONDecoder().decode(BLEPosition.self, from: data)
            let peer = PeerPosition(
                id: peripheral.identifier.uuidString,
                userId: blePos.uid,
                username: blePos.un,
                deviceId: blePos.did,
                latitude: blePos.lat,
                longitude: blePos.lon,
                altitude: blePos.alt,
                accuracy: blePos.acc,
                speed: blePos.spd,
                timestamp: Date(timeIntervalSince1970: blePos.ts),
                discoveredAt: Date()
            )

            DispatchQueue.main.async {
                self.peers.removeAll { $0.deviceId == peer.deviceId }
                self.peers.append(peer)
                self.connectedPeerCount = self.peers.count
            }
            logger.info("Got position from peer \(blePos.un) (device \(blePos.did))")
        } catch {
            logger.error("Failed to decode BLE position: \(error.localizedDescription)")
        }
    }
}
