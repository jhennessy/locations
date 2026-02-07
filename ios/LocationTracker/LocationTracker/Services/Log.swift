import os

/// Centralized persistent logging via os.Logger.
///
/// Usage: `Log.location.info("Got fix: \(accuracy)m")`
///
/// View on Mac with device connected:
///   log stream --predicate 'subsystem == "com.locationtracker.app"' --level debug
///
/// Export for analysis:
///   sudo log collect --device --last 1h --output ~/Desktop/location-logs.logarchive
///   log show ~/Desktop/location-logs.logarchive --predicate 'subsystem == "com.locationtracker.app"' --level debug
enum Log {
    private static let subsystem = "com.locationtracker.app"

    /// Location tracking, mode changes, CLLocationManager events
    static let location = Logger(subsystem: subsystem, category: "location")

    /// Motion detection (CMMotionActivityManager)
    static let motion = Logger(subsystem: subsystem, category: "motion")

    /// Network uploads, API calls
    static let network = Logger(subsystem: subsystem, category: "network")

    /// App lifecycle (foreground, background, termination)
    static let lifecycle = Logger(subsystem: subsystem, category: "lifecycle")

    /// Buffer persistence (save/load to disk)
    static let buffer = Logger(subsystem: subsystem, category: "buffer")
}
