import Foundation

struct LoginRequest: Codable {
    let username: String
    let password: String
}

struct RegisterRequest: Codable {
    let username: String
    let email: String
    let password: String
}

struct TokenResponse: Codable {
    let token: String
    let userId: Int
    let username: String

    enum CodingKeys: String, CodingKey {
        case token
        case userId = "user_id"
        case username
    }
}
