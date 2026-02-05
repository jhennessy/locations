import SwiftUI

struct ContentView: View {
    @EnvironmentObject var api: APIService
    @ObservedObject var locationService = LocationService.shared

    var body: some View {
        Group {
            if !api.isAuthenticated {
                LoginView()
            } else if locationService.deviceId == nil {
                DeviceSelectionView()
            } else {
                TabView {
                    TrackingView()
                        .tabItem {
                            Label("Tracking", systemImage: "location.fill")
                        }

                    SettingsView()
                        .tabItem {
                            Label("Settings", systemImage: "gear")
                        }
                }
            }
        }
    }
}
