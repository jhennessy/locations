import Foundation

enum APIError: LocalizedError {
    case invalidURL
    case httpError(Int, String)
    case decodingError
    case noToken
    case networkError(Error)

    var errorDescription: String? {
        switch self {
        case .invalidURL:
            return "Invalid URL"
        case .httpError(let code, let message):
            return "HTTP \(code): \(message)"
        case .decodingError:
            return "Failed to decode response"
        case .noToken:
            return "Not authenticated"
        case .networkError(let error):
            return error.localizedDescription
        }
    }
}

@MainActor
class APIService: ObservableObject {
    static let shared = APIService()

    // MARK: - Change this to your server's address
    var baseURL = "http://localhost:8080"

    @Published var token: String? {
        didSet {
            if let token = token {
                UserDefaults.standard.set(token, forKey: "auth_token")
            } else {
                UserDefaults.standard.removeObject(forKey: "auth_token")
            }
        }
    }

    @Published var currentUser: TokenResponse?

    var isAuthenticated: Bool { token != nil }

    init() {
        self.token = UserDefaults.standard.string(forKey: "auth_token")
    }

    // MARK: - Generic request helpers

    private func makeRequest(path: String, method: String, body: Data? = nil, authenticated: Bool = true) async throws -> Data {
        guard let url = URL(string: "\(baseURL)\(path)") else {
            throw APIError.invalidURL
        }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        if authenticated {
            guard let token = token else { throw APIError.noToken }
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        if let body = body {
            request.httpBody = body
        }

        let (data, response): (Data, URLResponse)
        do {
            (data, response) = try await URLSession.shared.data(for: request)
        } catch {
            throw APIError.networkError(error)
        }

        guard let httpResponse = response as? HTTPURLResponse else {
            throw APIError.httpError(0, "Invalid response")
        }

        if httpResponse.statusCode == 204 {
            return Data()
        }

        guard (200...299).contains(httpResponse.statusCode) else {
            let message = String(data: data, encoding: .utf8) ?? "Unknown error"
            throw APIError.httpError(httpResponse.statusCode, message)
        }

        return data
    }

    // MARK: - Auth

    func login(username: String, password: String) async throws {
        let body = try JSONEncoder().encode(LoginRequest(username: username, password: password))
        let data = try await makeRequest(path: "/api/login", method: "POST", body: body, authenticated: false)
        let response = try JSONDecoder().decode(TokenResponse.self, from: data)
        self.token = response.token
        self.currentUser = response
    }

    func register(username: String, email: String, password: String) async throws {
        let body = try JSONEncoder().encode(RegisterRequest(username: username, email: email, password: password))
        let data = try await makeRequest(path: "/api/register", method: "POST", body: body, authenticated: false)
        let response = try JSONDecoder().decode(TokenResponse.self, from: data)
        self.token = response.token
        self.currentUser = response
    }

    func logout() async {
        _ = try? await makeRequest(path: "/api/logout", method: "POST")
        self.token = nil
        self.currentUser = nil
    }

    // MARK: - Devices

    func fetchDevices() async throws -> [DeviceInfo] {
        let data = try await makeRequest(path: "/api/devices", method: "GET")
        return try JSONDecoder().decode([DeviceInfo].self, from: data)
    }

    func createDevice(name: String, identifier: String) async throws -> DeviceInfo {
        let body = try JSONEncoder().encode(DeviceCreateRequest(name: name, identifier: identifier))
        let data = try await makeRequest(path: "/api/devices", method: "POST", body: body)
        return try JSONDecoder().decode(DeviceInfo.self, from: data)
    }

    func deleteDevice(id: Int) async throws {
        _ = try await makeRequest(path: "/api/devices/\(id)", method: "DELETE")
    }

    // MARK: - Locations

    func uploadLocations(deviceId: Int, locations: [LocationPoint]) async throws -> BatchResponse {
        let batch = LocationBatch(deviceId: deviceId, locations: locations)
        let body = try JSONEncoder().encode(batch)
        let data = try await makeRequest(path: "/api/locations", method: "POST", body: body)
        return try JSONDecoder().decode(BatchResponse.self, from: data)
    }

    // MARK: - Visits

    func fetchVisits(deviceId: Int, limit: Int = 100) async throws -> [VisitInfo] {
        let data = try await makeRequest(path: "/api/visits/\(deviceId)?limit=\(limit)", method: "GET")
        return try JSONDecoder().decode([VisitInfo].self, from: data)
    }

    // MARK: - Places

    func fetchPlaces() async throws -> [PlaceInfo] {
        let data = try await makeRequest(path: "/api/places", method: "GET")
        return try JSONDecoder().decode([PlaceInfo].self, from: data)
    }

    func fetchFrequentPlaces(limit: Int = 20) async throws -> [PlaceInfo] {
        let data = try await makeRequest(path: "/api/places/frequent?limit=\(limit)", method: "GET")
        return try JSONDecoder().decode([PlaceInfo].self, from: data)
    }

    // MARK: - Positions

    struct PositionPointRequest: Codable {
        let latitude: Double
        let longitude: Double
        let altitude: Double?
        let accuracy: Double?
        let speed: Double?
        let timestamp: String
    }

    struct PositionBatchRequest: Codable {
        let device_id: Int
        let positions: [PositionPointRequest]
    }

    struct ServerPosition: Codable, Identifiable {
        let device_id: Int
        let device_name: String?
        let user_id: Int
        let username: String?
        let latitude: Double
        let longitude: Double
        let altitude: Double?
        let accuracy: Double?
        let speed: Double?
        let timestamp: String?
        let is_stale: Bool

        var id: Int { device_id }
    }

    struct ServerRelayPosition: Codable {
        let device_id: Int
        let latitude: Double
        let longitude: Double
        let altitude: Double?
        let accuracy: Double?
        let speed: Double?
        let timestamp: String
    }

    struct RelayBatchRequest: Codable {
        let relay_device_id: Int
        let positions: [ServerRelayPosition]
    }

    func updatePosition(deviceId: Int, latitude: Double, longitude: Double, altitude: Double?, accuracy: Double?, speed: Double?, timestamp: Date) async {
        let formatter = ISO8601DateFormatter()
        let batch = PositionBatchRequest(device_id: deviceId, positions: [
            PositionPointRequest(
                latitude: latitude, longitude: longitude,
                altitude: altitude, accuracy: accuracy, speed: speed,
                timestamp: formatter.string(from: timestamp)
            )
        ])
        let body = try? JSONEncoder().encode(batch)
        _ = try? await makeRequest(path: "/api/positions", method: "POST", body: body)
    }

    func fetchAllPositions() async -> [ServerPosition] {
        guard let data = try? await makeRequest(path: "/api/positions", method: "GET") else { return [] }
        return (try? JSONDecoder().decode([ServerPosition].self, from: data)) ?? []
    }

    func relayPeerPositions(relayDeviceId: Int, positions: [ServerRelayPosition]) async {
        let batch = RelayBatchRequest(relay_device_id: relayDeviceId, positions: positions)
        let body = try? JSONEncoder().encode(batch)
        _ = try? await makeRequest(path: "/api/positions/relay", method: "POST", body: body)
    }
}
