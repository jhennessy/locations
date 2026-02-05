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

    func logout() {
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
}
