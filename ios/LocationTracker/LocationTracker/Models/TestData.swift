import Foundation

/// Sample data for SwiftUI previews and testing.
/// Mirrors the server-side GPS test fixtures (San Francisco commute).
enum TestData {
    // MARK: - GPS Trace (San Francisco commute)
    // Sources for realistic GPS test data:
    // - Microsoft GeoLife: https://www.microsoft.com/en-us/download/details.aspx?id=52367
    // - OpenStreetMap traces: https://www.openstreetmap.org/traces/
    // - Grab-Posisi (with accuracy): https://engineering.grab.com/grab-posisi
    // - UCI GPS Trajectories: https://archive.ics.uci.edu/dataset/354/gps+trajectories

    static let sampleVisits: [VisitInfo] = [
        VisitInfo(
            id: 1,
            deviceId: 1,
            placeId: 1,
            latitude: 37.7615,
            longitude: -122.4240,
            arrival: "2024-01-15T08:00:00",
            departure: "2024-01-15T08:12:00",
            durationSeconds: 720,
            address: "742 Valencia St, San Francisco, CA 94110"
        ),
        VisitInfo(
            id: 2,
            deviceId: 1,
            placeId: 2,
            latitude: 37.7655,
            longitude: -122.4195,
            arrival: "2024-01-15T08:17:30",
            departure: "2024-01-15T08:25:30",
            durationSeconds: 480,
            address: "Ritual Coffee Roasters, 1026 Valencia St, San Francisco, CA"
        ),
        VisitInfo(
            id: 3,
            deviceId: 1,
            placeId: 3,
            latitude: 37.7738,
            longitude: -122.4128,
            arrival: "2024-01-15T08:38:30",
            departure: "2024-01-15T09:11:30",
            durationSeconds: 1980,
            address: "Mission District Office, 18th St, San Francisco, CA"
        ),
    ]

    static let samplePlaces: [PlaceInfo] = [
        PlaceInfo(
            id: 1,
            latitude: 37.7615,
            longitude: -122.4240,
            name: "Home",
            address: "742 Valencia St, San Francisco, CA 94110",
            visitCount: 47,
            totalDurationSeconds: 432_000
        ),
        PlaceInfo(
            id: 2,
            latitude: 37.7655,
            longitude: -122.4195,
            name: "Ritual Coffee",
            address: "1026 Valencia St, San Francisco, CA",
            visitCount: 23,
            totalDurationSeconds: 28_800
        ),
        PlaceInfo(
            id: 3,
            latitude: 37.7738,
            longitude: -122.4128,
            name: "Office",
            address: "Mission District Office, 18th St, San Francisco, CA",
            visitCount: 19,
            totalDurationSeconds: 345_600
        ),
        PlaceInfo(
            id: 4,
            latitude: 37.7850,
            longitude: -122.4094,
            name: nil,
            address: "Whole Foods Market, 399 4th St, San Francisco, CA",
            visitCount: 5,
            totalDurationSeconds: 5_400
        ),
    ]

    static let sampleDevices: [DeviceInfo] = [
        DeviceInfo(id: 1, name: "iPhone 15 Pro", identifier: "iphone15-abc123", lastSeen: "2024-01-15T09:11:30"),
        DeviceInfo(id: 2, name: "iPad Air", identifier: "ipad-xyz789", lastSeen: "2024-01-14T18:30:00"),
    ]
}
