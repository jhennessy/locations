import Foundation
import CoreLocation
import CoreMotion
import Combine

/// Tracking mode based on detected motion.
enum TrackingMode: String {
    case gettingFix = "Getting Fix"
    case stationary = "Stationary"
    case moving = "Moving"

    var description: String { rawValue }
}

/// Manages background location tracking with motion-aware power optimization.
///
/// When stationary, uses significant location changes only (low power).
/// When motion is detected, switches to full GPS tracking.
/// Before entering stationary mode, acquires a good GPS fix first.
/// State transitions are recorded as location points with descriptive notes.
///
/// Background wake strategy:
/// - SLC wakes the app for ~10 seconds. During this window we buffer the
///   location and flush to the server, but do NOT switch to continuous GPS
///   (iOS won't grant enough execution time). We stay on SLC.
/// - Continuous GPS only runs when the app is in the foreground.
class LocationService: NSObject, ObservableObject, CLLocationManagerDelegate {
    static let shared = LocationService()

    private let locationManager = CLLocationManager()
    private let motionManager = CMMotionActivityManager()
    private let motionQueue = OperationQueue()
    private let api = APIService.shared

    /// Buffered location points waiting to be uploaded.
    @Published var buffer: [LocationPoint] = []
    @Published var isTracking = false
    @Published var lastLocation: CLLocation?
    @Published var authorizationStatus: CLAuthorizationStatus = .notDetermined
    @Published var uploadError: String?
    @Published var trackingMode: TrackingMode = .stationary
    @Published var lastMotionActivity: String = "Unknown"

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

    /// Number of points to buffer before attempting upload.
    var batchSize = 10

    /// Maximum time (seconds) to wait before flushing the buffer regardless of count.
    var maxBufferAge: TimeInterval = 300 // 5 minutes

    /// How long to wait after last motion before switching to stationary mode.
    private let stationaryDelay: TimeInterval = 120 // 2 minutes

    /// Accuracy threshold (metres) to consider a GPS fix "good enough" before going to sleep.
    private let goodFixAccuracy: Double = 50.0

    /// Maximum time (seconds) to wait for a good fix before giving up and going stationary anyway.
    private let maxFixWait: TimeInterval = 30.0

    // Timer-free state tracking
    private var lastFlushTime: Date = Date()
    private var lastMovingActivityTime: Date?
    private var fixStartTime: Date?

    private var pendingStationaryReason: String?
    private var isMotionAvailable: Bool { CMMotionActivityManager.isActivityAvailable() }

    /// Whether the app is currently in the background. Updated by lifecycle handlers.
    private var isInBackground = false

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
        super.init()
        self.deviceId = UserDefaults.standard.object(forKey: "selected_device_id") as? Int
        locationManager.delegate = self
        locationManager.allowsBackgroundLocationUpdates = true
        locationManager.showsBackgroundLocationIndicator = true
        locationManager.pausesLocationUpdatesAutomatically = false
        locationManager.activityType = .other
        authorizationStatus = locationManager.authorizationStatus

        motionQueue.name = "com.locationtracker.motion"
        motionQueue.maxConcurrentOperationCount = 1

        // Restore any buffered points from a previous session
        loadBuffer()

        // Auto-resume tracking if it was active before
        if UserDefaults.standard.bool(forKey: "tracking_enabled"), deviceId != nil {
            Log.location.notice("Auto-resuming tracking from previous session")
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

        // Get a good fix first, then go to stationary mode
        beginGettingFix(reason: "Tracking started")
        startMotionUpdates()
    }

    func stopTracking() {
        Log.location.notice("Stopping tracking (buffer: \(self.buffer.count) points)")

        isTracking = false
        UserDefaults.standard.set(false, forKey: "tracking_enabled")
        lastMovingActivityTime = nil
        fixStartTime = nil
        pendingStationaryReason = nil

        locationManager.stopUpdatingLocation()
        locationManager.stopMonitoringSignificantLocationChanges()
        motionManager.stopActivityUpdates()

        recordStateChange("Tracking stopped")

        // Flush remaining buffer
        Task { await flushBuffer() }
    }

    // MARK: - Lifecycle

    func handleBackgroundTransition() {
        isInBackground = true
        Log.lifecycle.notice("Background — mode: \(self.trackingMode.rawValue), buffer: \(self.buffer.count), tracking: \(self.isTracking)")

        // If we're in moving mode when backgrounding, switch to stationary/SLC
        // so iOS doesn't suspend us without a wake mechanism.
        if isTracking && trackingMode == .moving {
            Log.location.notice("Backgrounding while moving → getting fix then SLC")
            beginGettingFix(reason: "App backgrounded while moving")
        }

        saveBuffer()
        if !buffer.isEmpty {
            Task { await flushBuffer() }
        }
    }

    func handleForegroundTransition() {
        isInBackground = false
        Log.lifecycle.notice("Foreground — mode: \(self.trackingMode.rawValue), buffer: \(self.buffer.count), tracking: \(self.isTracking)")

        // If we're stationary but motion was detected while backgrounded,
        // now we can safely switch to continuous GPS.
        if isTracking && trackingMode == .stationary {
            Log.location.notice("Foregrounded while stationary → getting fix")
            beginGettingFix(reason: "App foregrounded")
        }
    }

    // MARK: - Motion detection

    private func startMotionUpdates() {
        guard isMotionAvailable else {
            // No motion hardware — fall back to full GPS always
            Log.motion.warning("Motion detection unavailable, using continuous GPS")
            switchToMoving(reason: "Motion detection unavailable, using continuous GPS")
            return
        }

        motionManager.startActivityUpdates(to: motionQueue) { [weak self] activity in
            guard let self, let activity, self.isTracking else { return }
            DispatchQueue.main.async {
                self.handleMotionActivity(activity)
            }
        }
        Log.motion.notice("Started motion updates")
    }

    private func handleMotionActivity(_ activity: CMMotionActivity) {
        let activityName = describeActivity(activity)
        lastMotionActivity = activityName

        let isMoving = activity.walking || activity.running || activity.cycling || activity.automotive

        if isMoving {
            Log.motion.debug("Activity: \(activityName) → moving")
            // Reset stationary countdown
            lastMovingActivityTime = nil

            if trackingMode != .moving {
                // Don't switch to continuous GPS when backgrounded —
                // iOS only gives ~10s of execution after an SLC wake,
                // not enough for sustained GPS tracking.
                if isInBackground {
                    Log.motion.notice("Motion detected while backgrounded (\(activityName)) — staying on SLC")
                    return
                }
                // Cancel any pending fix acquisition too
                cancelPendingFix()
                switchToMoving(reason: "Motion detected: \(activityName)")
            }
        } else if activity.stationary && trackingMode == .moving {
            Log.motion.debug("Activity: \(activityName) → stationary detected, starting countdown")
            // Start the stationary countdown if not already started
            if lastMovingActivityTime == nil {
                lastMovingActivityTime = Date()
            }
        } else {
            Log.motion.debug("Activity: \(activityName) (no mode change)")
        }
    }

    private func describeActivity(_ activity: CMMotionActivity) -> String {
        var parts: [String] = []
        if activity.stationary { parts.append("stationary") }
        if activity.walking { parts.append("walking") }
        if activity.running { parts.append("running") }
        if activity.cycling { parts.append("cycling") }
        if activity.automotive { parts.append("automotive") }
        if activity.unknown { parts.append("unknown") }
        let confidence: String
        switch activity.confidence {
        case .high: confidence = "high"
        case .medium: confidence = "medium"
        case .low: confidence = "low"
        @unknown default: confidence = "?"
        }
        return "\(parts.joined(separator: "+")) (\(confidence) confidence)"
    }

    // MARK: - Mode switching

    /// Start full GPS to acquire a good fix, then transition to stationary.
    private func beginGettingFix(reason: String) {
        trackingMode = .gettingFix
        pendingStationaryReason = reason
        fixStartTime = Date()

        // Turn on full GPS temporarily
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.distanceFilter = kCLDistanceFilterNone
        locationManager.stopMonitoringSignificantLocationChanges()
        locationManager.startUpdatingLocation()

        Log.location.notice("→ Getting fix: \(reason)")
        recordStateChange("→ Getting fix: \(reason)")
    }

    /// Called when we get a good fix (or timeout) — finalize the switch to stationary.
    private func completeStationaryTransition(accuracy: String) {
        fixStartTime = nil
        let reason = pendingStationaryReason ?? "unknown"
        pendingStationaryReason = nil

        trackingMode = .stationary

        // Stop continuous GPS — it wastes battery when stationary and iOS will
        // terminate us if we use degraded accuracy (sees it as idle).
        locationManager.stopUpdatingLocation()

        // SLC wakes us from suspension AND can relaunch after termination.
        locationManager.startMonitoringSignificantLocationChanges()

        Log.location.notice("→ Stationary (\(accuracy)): \(reason). SLC monitoring active.")
        recordStateChange("→ Stationary (\(accuracy)): \(reason)")

        saveBuffer()
    }

    private func cancelPendingFix() {
        fixStartTime = nil
        pendingStationaryReason = nil
    }

    private func switchToMoving(reason: String) {
        let previousMode = trackingMode
        trackingMode = .moving
        lastMovingActivityTime = nil

        // Full GPS tracking
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.distanceFilter = 10
        locationManager.stopMonitoringSignificantLocationChanges()
        locationManager.startUpdatingLocation()

        if previousMode != .moving {
            Log.location.notice("→ Moving (was \(previousMode.rawValue)): \(reason)")
            recordStateChange("→ Moving: \(reason)")
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
                completeStationaryTransition(
                    accuracy: String(format: "%.0fm accuracy", location.horizontalAccuracy)
                )
            }
        }

        // Check fix timeout (replaces fixTimeoutTimer)
        if trackingMode == .gettingFix, let start = fixStartTime,
           Date().timeIntervalSince(start) >= maxFixWait {
            Log.location.warning("Fix timeout after \(Int(self.maxFixWait))s")
            completeStationaryTransition(accuracy: "timeout after \(Int(maxFixWait))s")
        }

        // Check stationary transition (replaces stationaryTimer)
        if trackingMode == .moving, let motionTime = lastMovingActivityTime,
           Date().timeIntervalSince(motionTime) >= stationaryDelay {
            Log.location.notice("Stationary countdown elapsed (\(Int(self.stationaryDelay))s)")
            lastMovingActivityTime = nil
            beginGettingFix(reason: "Stationary for \(Int(stationaryDelay))s")
        }

        // SLC wake while stationary — buffer and flush, stay on SLC
        if trackingMode == .stationary {
            Log.location.notice("SLC wake: received \(locations.count) location(s) while stationary (bg: \(self.isInBackground))")
            saveBuffer()
        }

        // Flush if buffer is full or maxBufferAge elapsed (replaces flushTimer)
        let timeSinceFlush = Date().timeIntervalSince(lastFlushTime)
        if buffer.count >= batchSize || timeSinceFlush >= maxBufferAge {
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
        Log.location.warning("iOS PAUSED location updates — resuming immediately")
        recordStateChange("iOS paused location updates")
        if isTracking && trackingMode != .stationary {
            manager.startUpdatingLocation()
            Log.location.notice("Resumed location updates after iOS pause")
        }
    }

    func locationManagerDidResumeLocationUpdates(_ manager: CLLocationManager) {
        Log.location.notice("iOS resumed location updates")
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
