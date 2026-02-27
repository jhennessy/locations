import Foundation
import CoreLocation
import Combine

/// Manages background location tracking with buffered uploads.
///
/// Collects location updates into a buffer and uploads them in batches
/// to minimize power consumption and data usage.
class LocationService: NSObject, ObservableObject, CLLocationManagerDelegate {
    static let shared = LocationService()

    private let locationManager = CLLocationManager()
    private let api = APIService.shared
    private let bluetooth = BluetoothService.shared
    private var lastPositionUploadTime: Date = .distantPast

    /// Buffered location points waiting to be uploaded.
    @Published var buffer: [LocationPoint] = []
    @Published var isTracking = false
    @Published var lastLocation: CLLocation?
    @Published var authorizationStatus: CLAuthorizationStatus = .notDetermined
    @Published var uploadError: String?

    /// The device ID to report locations for.
    var deviceId: Int? {
        didSet {
            if let id = deviceId {
                UserDefaults.standard.set(id, forKey: "selected_device_id")
            } else {
                UserDefaults.standard.removeObject(forKey: "selected_device_id")
            }
        }
    }

    // MARK: - Configuration

    /// Number of points to buffer before attempting upload.
    var batchSize = 10

    /// Maximum time (seconds) to wait before flushing the buffer regardless of count.
    var maxBufferAge: TimeInterval = 300 // 5 minutes

    private var flushTimer: Timer?

    // MARK: - Init

    override init() {
        super.init()
        self.deviceId = UserDefaults.standard.object(forKey: "selected_device_id") as? Int
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.allowsBackgroundLocationUpdates = true
        locationManager.pausesLocationUpdatesAutomatically = false
        locationManager.distanceFilter = 10 // meters
        authorizationStatus = locationManager.authorizationStatus
    }

    // MARK: - Permissions

    func requestPermission() {
        locationManager.requestAlwaysAuthorization()
    }

    // MARK: - Tracking control

    func startTracking() {
        guard deviceId != nil else { return }
        locationManager.startUpdatingLocation()
        isTracking = true
        startFlushTimer()
        bluetooth.start()
    }

    func stopTracking() {
        locationManager.stopUpdatingLocation()
        isTracking = false
        flushTimer?.invalidate()
        flushTimer = nil
        bluetooth.stop()
        // Flush remaining buffer
        Task { await flushBuffer() }
    }

    func handleEnterBackground() {
        bluetooth.isBackground = true
    }

    func handleEnterForeground() {
        bluetooth.isBackground = false
    }

    // MARK: - Timer

    private func startFlushTimer() {
        flushTimer?.invalidate()
        flushTimer = Timer.scheduledTimer(withTimeInterval: maxBufferAge, repeats: true) { [weak self] _ in
            Task { [weak self] in
                await self?.flushBuffer()
            }
        }
    }

    // MARK: - CLLocationManagerDelegate

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let _ = deviceId else { return }

        for location in locations {
            // Skip invalid readings
            guard location.horizontalAccuracy >= 0 else { continue }

            let point = LocationPoint(from: location)
            buffer.append(point)
            lastLocation = location

            // Update BLE position for nearby peer sharing
            if let deviceId = UserDefaults.standard.object(forKey: "selected_device_id") as? Int {
                bluetooth.currentPosition = BLEPosition(
                    uid: UserDefaults.standard.integer(forKey: "user_id"),
                    un: UserDefaults.standard.string(forKey: "username") ?? "",
                    did: deviceId,
                    lat: location.coordinate.latitude,
                    lon: location.coordinate.longitude,
                    alt: location.altitude,
                    acc: location.horizontalAccuracy,
                    spd: location.speed >= 0 ? location.speed : nil,
                    ts: location.timestamp.timeIntervalSince1970
                )
            }

            // Periodic position upload every 15s
            if Date().timeIntervalSince(lastPositionUploadTime) >= 15,
               let deviceId = UserDefaults.standard.object(forKey: "selected_device_id") as? Int {
                lastPositionUploadTime = Date()
                Task {
                    await APIService.shared.updatePosition(
                        deviceId: deviceId,
                        latitude: location.coordinate.latitude,
                        longitude: location.coordinate.longitude,
                        altitude: location.altitude,
                        accuracy: location.horizontalAccuracy,
                        speed: location.speed >= 0 ? location.speed : nil,
                        timestamp: location.timestamp
                    )
                }
            }
        }

        // Flush if buffer is full
        if buffer.count >= batchSize {
            Task { await flushBuffer() }
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        authorizationStatus = manager.authorizationStatus
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        print("Location error: \(error.localizedDescription)")
    }

    // MARK: - Buffer flush

    @MainActor
    func flushBuffer() async {
        guard let deviceId = deviceId, !buffer.isEmpty else { return }

        let pointsToUpload = buffer
        buffer.removeAll()

        do {
            let response = try await api.uploadLocations(deviceId: deviceId, locations: pointsToUpload)
            uploadError = nil
            print("Uploaded \(response.received) points (batch: \(response.batchId))")
            // Relay BLE peer positions to server on successful flush
            if let deviceId = UserDefaults.standard.object(forKey: "selected_device_id") as? Int {
                Task { await bluetooth.relayPeersToServer(relayDeviceId: deviceId) }
            }
        } catch {
            // Put points back in buffer for retry
            buffer.insert(contentsOf: pointsToUpload, at: 0)
            uploadError = error.localizedDescription
            print("Upload failed: \(error.localizedDescription)")
        }
    }
}
