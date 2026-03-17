import Foundation

struct DeviceInfo: Codable, Identifiable {
    let id: Int
    let name: String
    let identifier: String
    let lastSeen: String?

    enum CodingKeys: String, CodingKey {
        case id, name, identifier
        case lastSeen = "last_seen"
    }
}

struct DeviceCreateRequest: Codable {
    let name: String
    let identifier: String
}
