import Foundation
import CoreLocation

struct LocationPoint: Codable {
    let latitude: Double
    let longitude: Double
    let altitude: Double?
    let horizontalAccuracy: Double?
    let verticalAccuracy: Double?
    let speed: Double?
    let course: Double?
    let timestamp: String

    enum CodingKeys: String, CodingKey {
        case latitude, longitude, altitude, speed, course, timestamp
        case horizontalAccuracy = "horizontal_accuracy"
        case verticalAccuracy = "vertical_accuracy"
    }

    init(from clLocation: CLLocation) {
        self.latitude = clLocation.coordinate.latitude
        self.longitude = clLocation.coordinate.longitude
        self.altitude = clLocation.altitude
        self.horizontalAccuracy = clLocation.horizontalAccuracy
        self.verticalAccuracy = clLocation.verticalAccuracy
        self.speed = clLocation.speed >= 0 ? clLocation.speed : nil
        self.course = clLocation.course >= 0 ? clLocation.course : nil

        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        self.timestamp = formatter.string(from: clLocation.timestamp)
    }
}

struct LocationBatch: Codable {
    let deviceId: Int
    let locations: [LocationPoint]

    enum CodingKeys: String, CodingKey {
        case deviceId = "device_id"
        case locations
    }
}

struct BatchResponse: Codable {
    let received: Int
    let batchId: String

    enum CodingKeys: String, CodingKey {
        case received
        case batchId = "batch_id"
    }
}
