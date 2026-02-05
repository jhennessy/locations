import Foundation

struct PlaceInfo: Codable, Identifiable {
    let id: Int
    let latitude: Double
    let longitude: Double
    let name: String?
    let address: String?
    let visitCount: Int
    let totalDurationSeconds: Int

    enum CodingKeys: String, CodingKey {
        case id, latitude, longitude, name, address
        case visitCount = "visit_count"
        case totalDurationSeconds = "total_duration_seconds"
    }

    var displayName: String {
        name ?? address ?? String(format: "%.5f, %.5f", latitude, longitude)
    }

    var formattedTotalTime: String {
        formatDuration(totalDurationSeconds)
    }

    var formattedAvgTime: String {
        guard visitCount > 0 else { return "-" }
        return formatDuration(totalDurationSeconds / visitCount)
    }

    private func formatDuration(_ seconds: Int) -> String {
        let minutes = seconds / 60
        if minutes < 60 { return "\(minutes)m" }
        let hours = minutes / 60
        let remainingMin = minutes % 60
        if hours < 24 { return "\(hours)h \(remainingMin)m" }
        let days = hours / 24
        let remainingHrs = hours % 24
        return "\(days)d \(remainingHrs)h"
    }
}
