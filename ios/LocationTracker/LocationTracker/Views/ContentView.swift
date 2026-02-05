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

                    VisitsView()
                        .tabItem {
                            Label("Visits", systemImage: "mappin.and.ellipse")
                        }

                    FrequentPlacesView()
                        .tabItem {
                            Label("Places", systemImage: "star.fill")
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
