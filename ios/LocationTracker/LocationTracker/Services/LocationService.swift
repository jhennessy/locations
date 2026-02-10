import Foundation
import CoreLocation
import Combine

/// Tracking mode: two-state geofence system.
enum TrackingMode: String {
    case gettingFix = "Getting Fix"
    case sleeping = "Sleeping"

    var description: String { rawValue }
}

/// Manages background location tracking with geofence-based power optimization.
///
/// Two states:
/// - Getting Fix: Full GPS to acquire a good reading.
/// - Sleeping: GPS off, geofence active. On geofence exit, gets a new fix.
///
/// The geofence radius is dynamic: max(20m, lastFixAccuracy * 2.5).
/// Behavior is identical in foreground and background.
/// Region monitoring survives app termination and can relaunch after jetsam.
class LocationService: NSObject, ObservableObject, CLLocationManagerDelegate {
    static let shared = LocationService()

    private let locationManager = CLLocationManager()
    private let api = APIService.shared

    /// Buffered location points waiting to be uploaded.
    @Published var buffer: [LocationPoint] = []
    @Published var isTracking = false
    @Published var lastLocation: CLLocation?
    @Published var authorizationStatus: CLAuthorizationStatus = .notDetermined
    @Published var uploadError: String?
    @Published var trackingMode: TrackingMode = .sleeping

    /// Accuracy (metres) of the last good GPS fix, used to compute geofence radius.
    @Published var lastFixAccuracy: Double = 50.0

    /// The device ID to report locations for.
    @Published var deviceId: Int? {
        didSet {
            if let id = deviceId {
                UserDefaults.standard.set(id, forKey: "selected_device_id")
            } else {
                UserDefaults.standard.removeObject(forKey: "selected_device_id")
            }
        }
    }

    // MARK: - Configuration

    /// When enabled, upload every point immediately (batch size 1, 30s max age).
    @Published var aggressiveUpload: Bool {
        didSet {
            UserDefaults.standard.set(aggressiveUpload, forKey: "aggressive_upload")
            if aggressiveUpload {
                batchSize = 1
                maxBufferAge = 30
            }
        }
    }

    /// Number of points to buffer before attempting upload.
    var batchSize = 10

    /// Maximum time (seconds) to wait before flushing the buffer regardless of count.
    var maxBufferAge: TimeInterval = 300 // 5 minutes

    /// Accuracy threshold (metres) to consider a GPS fix "good enough" before going to sleep.
    private let goodFixAccuracy: Double = 50.0

    /// Maximum time (seconds) to wait for a good fix before giving up and going to sleep anyway.
    private let maxFixWait: TimeInterval = 30.0

    /// Computed geofence radius: 2.5x the last fix accuracy, minimum 20m.
    var geofenceRadius: Double {
        max(20.0, lastFixAccuracy * 2.5)
    }

    /// Identifier for the single monitored geofence region.
    private let geofenceIdentifier = "ch.codelook.locationz.geofence"

    // Timer-free state tracking
    private var lastFlushTime: Date = Date()
    private var fixStartTime: Date?

    private var pendingSleepReason: String?

    /// Whether the app is currently in the background. Updated by lifecycle handlers.
    /// Defaults to `true` so that background relaunches (geofence) are safe —
    /// the foreground handler sets it to `false` when the UI appears.
    private var isInBackground = true

    // MARK: - Buffer persistence

    private static let bufferFileURL: URL = {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("location_buffer.json")
    }()

    private func saveBuffer() {
        guard !buffer.isEmpty else { return }
        do {
            let data = try JSONEncoder().encode(buffer)
            try data.write(to: Self.bufferFileURL, options: .atomic)
            Log.buffer.debug("Saved \(self.buffer.count) points to disk")
        } catch {
            Log.buffer.error("Failed to save buffer: \(error.localizedDescription)")
        }
    }

    private func loadBuffer() {
        guard FileManager.default.fileExists(atPath: Self.bufferFileURL.path) else { return }
        do {
            let data = try Data(contentsOf: Self.bufferFileURL)
            let points = try JSONDecoder().decode([LocationPoint].self, from: data)
            buffer.insert(contentsOf: points, at: 0)
            Log.buffer.notice("Restored \(points.count) points from disk")
            try? FileManager.default.removeItem(at: Self.bufferFileURL)
        } catch {
            Log.buffer.error("Failed to load buffer: \(error.localizedDescription)")
        }
    }

    private func deleteBufferFile() {
        try? FileManager.default.removeItem(at: Self.bufferFileURL)
    }

    // MARK: - Init

    override init() {
        let aggressive = UserDefaults.standard.bool(forKey: "aggressive_upload")
        self.aggressiveUpload = aggressive
        super.init()
        if aggressive {
            batchSize = 1
            maxBufferAge = 30
        }
        self.deviceId = UserDefaults.standard.object(forKey: "selected_device_id") as? Int
        locationManager.delegate = self
        locationManager.allowsBackgroundLocationUpdates = true
        locationManager.showsBackgroundLocationIndicator = true
        locationManager.pausesLocationUpdatesAutomatically = false
        locationManager.activityType = .other
        authorizationStatus = locationManager.authorizationStatus

        // Restore any buffered points from a previous session
        loadBuffer()

        // Auto-resume tracking if it was active before (works for both GUI launch and
        // background relaunch by geofence after jetsam).
        if UserDefaults.standard.bool(forKey: "tracking_enabled"), deviceId != nil {
            Log.location.notice("Auto-resuming tracking from previous session (bg: \(self.isInBackground))")
            startTracking()
        }
    }

    // MARK: - Permissions

    func requestPermission() {
        locationManager.requestAlwaysAuthorization()
    }

    // MARK: - Tracking control

    func startTracking() {
        guard deviceId != nil else {
            Log.location.warning("Cannot start tracking: no device ID")
            return
        }
        isTracking = true
        UserDefaults.standard.set(true, forKey: "tracking_enabled")
        lastFlushTime = Date()

        Log.location.notice("Starting tracking (buffer: \(self.buffer.count) points, background: \(self.isInBackground))")
        recordStateChange("Tracking started (bg: \(isInBackground))")

        // Get a good fix first, then go to sleep with a geofence
        beginGettingFix(reason: "Tracking started")
    }

    func stopTracking() {
        Log.location.notice("Stopping tracking (buffer: \(self.buffer.count) points)")

        isTracking = false
        UserDefaults.standard.set(false, forKey: "tracking_enabled")
        fixStartTime = nil
        pendingSleepReason = nil

        locationManager.stopUpdatingLocation()
        removeGeofence()

        recordStateChange("Tracking stopped")

        // Flush remaining buffer
        Task { await flushBuffer() }
    }

    // MARK: - Lifecycle

    func handleBackgroundTransition() {
        isInBackground = true
        Log.lifecycle.notice("Background — mode: \(self.trackingMode.rawValue), buffer: \(self.buffer.count), tracking: \(self.isTracking)")
        if isTracking {
            recordStateChange("App → background (mode: \(trackingMode.rawValue))")
        }

        saveBuffer()
        if !buffer.isEmpty {
            Task { await flushBuffer() }
        }
    }

    func handleForegroundTransition() {
        isInBackground = false
        Log.lifecycle.notice("Foreground — mode: \(self.trackingMode.rawValue), buffer: \(self.buffer.count), tracking: \(self.isTracking)")
        if isTracking {
            recordStateChange("App → foreground (mode: \(trackingMode.rawValue))")
        }
    }

    // MARK: - Mode switching

    /// Start full GPS to acquire a good fix, then transition to sleeping.
    private func beginGettingFix(reason: String) {
        trackingMode = .gettingFix
        pendingSleepReason = reason
        fixStartTime = Date()

        // Turn on full GPS temporarily
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.distanceFilter = kCLDistanceFilterNone
        removeGeofence()
        locationManager.startUpdatingLocation()

        Log.location.notice("→ Getting fix: \(reason)")
        recordStateChange("→ Getting fix: \(reason)")
    }

    /// Called when we get a good fix (or timeout) — set geofence and go to sleep.
    private func completeSleepTransition(accuracy: Double, accuracyLabel: String) {
        fixStartTime = nil
        let reason = pendingSleepReason ?? "unknown"
        pendingSleepReason = nil

        // Store accuracy for dynamic geofence radius calculation
        lastFixAccuracy = accuracy

        trackingMode = .sleeping

        // Stop continuous GPS
        locationManager.stopUpdatingLocation()

        // Set geofence with dynamic radius based on fix accuracy
        setupGeofence()

        let radiusStr = String(format: "%.0f", geofenceRadius)
        Log.location.notice("→ Sleeping (\(accuracyLabel)): \(reason). Geofence r=\(radiusStr)m active.")
        recordStateChange("→ Sleeping (\(accuracyLabel)): \(reason)")

        saveBuffer()
    }

    // MARK: - Region monitoring (geofence)

    /// Set up a geofence around the current position. Radius is dynamic based on fix accuracy.
    private func setupGeofence() {
        guard let location = lastLocation ?? locationManager.location else {
            Log.location.warning("Cannot set up geofence: no location available")
            return
        }

        // Remove old fence first (only one at a time)
        removeGeofence()

        let region = CLCircularRegion(
            center: location.coordinate,
            radius: geofenceRadius,
            identifier: geofenceIdentifier
        )
        region.notifyOnExit = true
        region.notifyOnEntry = false

        locationManager.startMonitoring(for: region)
        let acc = String(format: "%.0f", location.horizontalAccuracy)
        Log.location.notice("Geofence set: \(location.coordinate.latitude, format: .fixed(precision: 5)), \(location.coordinate.longitude, format: .fixed(precision: 5)) r=\(Int(self.geofenceRadius))m acc=\(acc)m")
        recordStateChange("Geofence set r=\(Int(geofenceRadius))m acc=\(acc)m")
    }

    /// Remove any active geofence region.
    private func removeGeofence() {
        for region in locationManager.monitoredRegions {
            if region.identifier == geofenceIdentifier {
                locationManager.stopMonitoring(for: region)
                Log.location.debug("Geofence removed")
            }
        }
    }

    // MARK: - State change recording

    private func recordStateChange(_ description: String) {
        guard let location = lastLocation ?? locationManager.location else { return }

        let point = LocationPoint(from: location, notes: description)
        buffer.append(point)
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

            Log.location.debug("Location: \(location.coordinate.latitude, format: .fixed(precision: 5)), \(location.coordinate.longitude, format: .fixed(precision: 5)) acc=\(location.horizontalAccuracy, format: .fixed(precision: 0))m mode=\(self.trackingMode.rawValue)")

            // If we're waiting for a good fix, check if this one qualifies
            if trackingMode == .gettingFix && location.horizontalAccuracy <= goodFixAccuracy {
                Log.location.notice("Good fix acquired: \(location.horizontalAccuracy, format: .fixed(precision: 0))m")
                completeSleepTransition(
                    accuracy: location.horizontalAccuracy,
                    accuracyLabel: String(format: "%.0fm accuracy", location.horizontalAccuracy)
                )
            }
        }

        // Check fix timeout
        if trackingMode == .gettingFix, let start = fixStartTime,
           Date().timeIntervalSince(start) >= maxFixWait {
            let fallbackAccuracy = lastLocation?.horizontalAccuracy ?? lastFixAccuracy
            Log.location.warning("Fix timeout after \(Int(self.maxFixWait))s, using accuracy \(fallbackAccuracy, format: .fixed(precision: 0))m")
            completeSleepTransition(
                accuracy: fallbackAccuracy,
                accuracyLabel: "timeout after \(Int(maxFixWait))s"
            )
        }

        // Flush: immediately when backgrounded (we only have ~10s), otherwise on thresholds
        let timeSinceFlush = Date().timeIntervalSince(lastFlushTime)
        if isInBackground || buffer.count >= batchSize || timeSinceFlush >= maxBufferAge {
            lastFlushTime = Date()
            Task { await flushBuffer() }
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let newStatus = manager.authorizationStatus
        authorizationStatus = newStatus
        Log.location.notice("Authorization changed: \(String(describing: newStatus))")
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Log.location.error("CLLocationManager error: \(error.localizedDescription)")
    }

    func locationManagerDidPauseLocationUpdates(_ manager: CLLocationManager) {
        Log.location.warning("iOS PAUSED location updates")
        recordStateChange("iOS paused location updates")
        if isTracking && trackingMode == .gettingFix {
            manager.startUpdatingLocation()
            Log.location.notice("Resumed location updates after iOS pause (getting fix)")
        }
    }

    func locationManagerDidResumeLocationUpdates(_ manager: CLLocationManager) {
        Log.location.notice("iOS resumed location updates")
    }

    func locationManager(_ manager: CLLocationManager, didExitRegion region: CLRegion) {
        guard region.identifier == geofenceIdentifier, isTracking else { return }

        Log.location.notice("Geofence exit detected (bg: \(self.isInBackground), mode: \(self.trackingMode.rawValue))")
        recordStateChange("Geofence exit (bg: \(isInBackground))")

        beginGettingFix(reason: "Geofence exit")
    }

    func locationManager(_ manager: CLLocationManager, monitoringDidFailFor region: CLRegion?, withError error: Error) {
        Log.location.error("Region monitoring failed for \(region?.identifier ?? "nil"): \(error.localizedDescription)")
    }

    // MARK: - Buffer flush

    @MainActor
    func flushBuffer() async {
        guard let deviceId = deviceId, !buffer.isEmpty else { return }

        let pointsToUpload = buffer
        buffer.removeAll()

        Log.network.notice("Uploading \(pointsToUpload.count) points...")

        do {
            let response = try await api.uploadLocations(deviceId: deviceId, locations: pointsToUpload)
            uploadError = nil
            deleteBufferFile()
            Log.network.notice("Uploaded \(response.received) points (batch: \(response.batchId), visits: \(response.visitsDetected))")
        } catch {
            // Put points back in buffer for retry
            buffer.insert(contentsOf: pointsToUpload, at: 0)
            uploadError = error.localizedDescription
            saveBuffer()
            Log.network.error("Upload failed (\(pointsToUpload.count) points): \(error.localizedDescription)")
        }
    }
}
