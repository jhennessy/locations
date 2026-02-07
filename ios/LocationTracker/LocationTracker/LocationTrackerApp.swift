import SwiftUI

@main
struct LocationTrackerApp: App {
    @StateObject private var api = APIService.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(api)
                .onChange(of: scenePhase) { _, newPhase in
                    switch newPhase {
                    case .active:
                        Log.lifecycle.info("App became active")
                        LocationService.shared.handleForegroundTransition()
                    case .inactive:
                        Log.lifecycle.info("App became inactive")
                    case .background:
                        Log.lifecycle.info("App entering background")
                        LocationService.shared.handleBackgroundTransition()
                    @unknown default:
                        Log.lifecycle.warning("Unknown scene phase")
                    }
                }
        }
    }
}
