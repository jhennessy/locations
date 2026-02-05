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

    var arrivalDate: Date? {
        ISO8601DateFormatter().date(from: arrival)
    }

    var departureDate: Date? {
        ISO8601DateFormatter().date(from: departure)
    }

    var displayLocation: String {
        address ?? String(format: "%.5f, %.5f", latitude, longitude)
    }
}
