import SwiftUI

@main
struct LocationTrackerApp: App {
    @StateObject private var api = APIService.shared

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(api)
        }
    }
}
