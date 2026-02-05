import SwiftUI

/// Defers view creation until it appears on screen.
struct LazyView<Content: View>: View {
    let build: () -> Content
    init(_ build: @autoclosure @escaping () -> Content) {
        self.build = build
    }
    var body: Content { build() }
}

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

                    LazyView(VisitsView())
                        .tabItem {
                            Label("Visits", systemImage: "mappin.and.ellipse")
                        }

                    LazyView(FrequentPlacesView())
                        .tabItem {
                            Label("Places", systemImage: "star.fill")
                        }

                    LazyView(SettingsView())
                        .tabItem {
                            Label("Settings", systemImage: "gear")
                        }
                }
            }
        }
    }
}
