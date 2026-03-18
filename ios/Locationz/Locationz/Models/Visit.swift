import Foundation

struct VisitInfo: Codable, Identifiable {
    let id: Int
    let deviceId: Int
    let placeId: Int
    let latitude: Double
    let longitude: Double
    let arrival: String
    let departure: String
    let durationSeconds: Int
    let address: String?

    enum CodingKeys: String, CodingKey {
        case id, latitude, longitude, arrival, departure, address
        case deviceId = "device_id"
        case placeId = "place_id"
        case durationSeconds = "duration_seconds"
    }

    var formattedDuration: String {
        let minutes = durationSeconds / 60
        if minutes < 60 {
            return "\(minutes)m"
        }
        let hours = minutes / 60
        let remainingMin = minutes % 60
        if hours < 24 {
            return "\(hours)h \(remainingMin)m"
        }
        let days = hours / 24
        let remainingHrs = hours % 24
        return "\(days)d \(remainingHrs)h"
    }

    private static let isoFormatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    private static func parseDate(_ str: String) -> Date? {
        // Try with fractional seconds first, then without, then with Z appended
        if let d = isoFormatter.date(from: str) { return d }
        let basic = ISO8601DateFormatter()
        basic.formatOptions = [.withInternetDateTime]
        if let d = basic.date(from: str) { return d }
        // Server sends naive UTC without Z suffix
        if let d = basic.date(from: str + "Z") { return d }
        return nil
    }

    var arrivalDate: Date? { Self.parseDate(arrival) }
    var departureDate: Date? { Self.parseDate(departure) }

    var displayLocation: String {
        address ?? String(format: "%.5f, %.5f", latitude, longitude)
    }
}
