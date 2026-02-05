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
class LocationService: NSObject, ObservableObject, CLLocationManagerDelegate {
    static let shared = LocationService()

    private let locationManager = CLLocationManager()
    private let motionManager = CMMotionActivityManager()
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

    private var flushTimer: Timer?
    private var stationaryTimer: Timer?
    private var fixTimeoutTimer: Timer?
    private var pendingStationaryReason: String?
    private var isMotionAvailable: Bool { CMMotionActivityManager.isActivityAvailable() }

    // MARK: - Init

    override init() {
        super.init()
        self.deviceId = UserDefaults.standard.object(forKey: "selected_device_id") as? Int
        locationManager.delegate = self
        locationManager.allowsBackgroundLocationUpdates = true
        locationManager.showsBackgroundLocationIndicator = true
        locationManager.pausesLocationUpdatesAutomatically = false
        authorizationStatus = locationManager.authorizationStatus

        // Auto-resume tracking if it was active before
        if UserDefaults.standard.bool(forKey: "tracking_enabled"), deviceId != nil {
            startTracking()
        }
    }

    // MARK: - Permissions

    func requestPermission() {
        locationManager.requestAlwaysAuthorization()
    }

    // MARK: - Tracking control

    func startTracking() {
        guard deviceId != nil else { return }
        isTracking = true
        UserDefaults.standard.set(true, forKey: "tracking_enabled")
        startFlushTimer()

        // Get a good fix first, then go to stationary mode
        beginGettingFix(reason: "Tracking started")
        startMotionUpdates()
    }

    func stopTracking() {
        isTracking = false
        UserDefaults.standard.set(false, forKey: "tracking_enabled")
        flushTimer?.invalidate()
        flushTimer = nil
        stationaryTimer?.invalidate()
        stationaryTimer = nil
        fixTimeoutTimer?.invalidate()
        fixTimeoutTimer = nil
        pendingStationaryReason = nil

        locationManager.stopUpdatingLocation()
        locationManager.stopMonitoringSignificantLocationChanges()
        motionManager.stopActivityUpdates()

        recordStateChange("Tracking stopped")

        // Flush remaining buffer
        Task { await flushBuffer() }
    }

    // MARK: - Motion detection

    private func startMotionUpdates() {
        guard isMotionAvailable else {
            // No motion hardware — fall back to full GPS always
            switchToMoving(reason: "Motion detection unavailable, using continuous GPS")
            return
        }

        motionManager.startActivityUpdates(to: .main) { [weak self] activity in
            guard let self, let activity, self.isTracking else { return }
            self.handleMotionActivity(activity)
        }
    }

    private func handleMotionActivity(_ activity: CMMotionActivity) {
        let activityName = describeActivity(activity)
        lastMotionActivity = activityName

        let isMoving = activity.walking || activity.running || activity.cycling || activity.automotive

        if isMoving {
            // Cancel any pending stationary transition
            stationaryTimer?.invalidate()
            stationaryTimer = nil

            if trackingMode != .moving {
                // Cancel any pending fix acquisition too
                cancelPendingFix()
                switchToMoving(reason: "Motion detected: \(activityName)")
            }
        } else if activity.stationary && trackingMode == .moving {
            // Don't switch immediately — wait for stationaryDelay to confirm
            if stationaryTimer == nil {
                stationaryTimer = Timer.scheduledTimer(withTimeInterval: stationaryDelay, repeats: false) { [weak self] _ in
                    guard let self, self.isTracking else { return }
                    self.beginGettingFix(reason: "Stationary for \(Int(self.stationaryDelay))s")
                    self.stationaryTimer = nil
                }
            }
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

        // Turn on full GPS temporarily
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.distanceFilter = kCLDistanceFilterNone
        locationManager.stopMonitoringSignificantLocationChanges()
        locationManager.startUpdatingLocation()

        recordStateChange("→ Getting fix: \(reason)")

        // Safety timeout — don't run GPS forever waiting for a good fix
        fixTimeoutTimer?.invalidate()
        fixTimeoutTimer = Timer.scheduledTimer(withTimeInterval: maxFixWait, repeats: false) { [weak self] _ in
            guard let self, self.trackingMode == .gettingFix else { return }
            self.completeStationaryTransition(accuracy: "timeout after \(Int(self.maxFixWait))s")
        }
    }

    /// Called when we get a good fix (or timeout) — finalize the switch to stationary.
    private func completeStationaryTransition(accuracy: String) {
        fixTimeoutTimer?.invalidate()
        fixTimeoutTimer = nil
        let reason = pendingStationaryReason ?? "unknown"
        pendingStationaryReason = nil

        trackingMode = .stationary

        // Low-power mode: significant changes only
        locationManager.stopUpdatingLocation()
        locationManager.startMonitoringSignificantLocationChanges()

        recordStateChange("→ Stationary (\(accuracy)): \(reason)")
    }

    private func cancelPendingFix() {
        fixTimeoutTimer?.invalidate()
        fixTimeoutTimer = nil
        pendingStationaryReason = nil
    }

    private func switchToMoving(reason: String) {
        let previousMode = trackingMode
        trackingMode = .moving

        // Full GPS tracking
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.distanceFilter = 10
        locationManager.stopMonitoringSignificantLocationChanges()
        locationManager.startUpdatingLocation()

        if previousMode != .moving {
            recordStateChange("→ Moving: \(reason)")
        }
    }

    // MARK: - State change recording

    private func recordStateChange(_ description: String) {
        guard let location = lastLocation ?? locationManager.location else { return }

        let point = LocationPoint(from: location, notes: description)
        buffer.append(point)

        print("[LocationService] \(description)")
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

            // If we're waiting for a good fix, check if this one qualifies
            if trackingMode == .gettingFix && location.horizontalAccuracy <= goodFixAccuracy {
                completeStationaryTransition(
                    accuracy: String(format: "%.0fm accuracy", location.horizontalAccuracy)
                )
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
        print("[LocationService] Location error: \(error.localizedDescription)")
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
            print("[LocationService] Uploaded \(response.received) points (batch: \(response.batchId))")
        } catch {
            // Put points back in buffer for retry
            buffer.insert(contentsOf: pointsToUpload, at: 0)
            uploadError = error.localizedDescription
            print("[LocationService] Upload failed: \(error.localizedDescription)")
        }
    }
}
