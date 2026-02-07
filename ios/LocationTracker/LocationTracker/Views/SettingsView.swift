import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var api: APIService
    @ObservedObject var locationService = LocationService.shared

    @State private var serverURL: String = APIService.shared.baseURL
    @State private var batchSize: Double = Double(LocationService.shared.batchSize)
    @State private var flushInterval: Double = LocationService.shared.maxBufferAge

    var body: some View {
        NavigationStack {
            Form {
                Section("Server") {
                    TextField("Server URL", text: $serverURL)
                        .textContentType(.URL)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .onSubmit {
                            api.baseURL = serverURL
                        }
                        .onChange(of: serverURL) { _, newValue in
                            api.baseURL = newValue
                        }
                }

                Section("Upload") {
                    Toggle("Aggressive upload", isOn: $locationService.aggressiveUpload)

                    VStack(alignment: .leading) {
                        Text("Batch size: \(Int(batchSize)) points")
                        Slider(value: $batchSize, in: 1...50, step: 1)
                            .onChange(of: batchSize) { _, newValue in
                                locationService.batchSize = Int(newValue)
                            }
                    }
                    .disabled(locationService.aggressiveUpload)
                    .opacity(locationService.aggressiveUpload ? 0.5 : 1)

                    VStack(alignment: .leading) {
                        Text("Max buffer age: \(Int(flushInterval))s")
                        Slider(value: $flushInterval, in: 30...1800, step: 30)
                            .onChange(of: flushInterval) { _, newValue in
                                locationService.maxBufferAge = newValue
                            }
                    }
                    .disabled(locationService.aggressiveUpload)
                    .opacity(locationService.aggressiveUpload ? 0.5 : 1)
                }

                Section("Device") {
                    Button("Change Device") {
                        locationService.stopTracking()
                        locationService.deviceId = nil
                    }
                }

                Section("Account") {
                    if let user = api.currentUser {
                        Text("Logged in as \(user.username)")
                            .foregroundStyle(.secondary)
                    }

                    Button("Logout", role: .destructive) {
                        locationService.stopTracking()
                        locationService.deviceId = nil
                        api.logout()
                    }
                }

                Section("Info") {
                    LabeledContent("Buffer", value: "\(locationService.buffer.count) points")
                    LabeledContent("Status", value: locationService.isTracking ? "Tracking" : "Stopped")
                    LabeledContent("Permission", value: permissionText)
                }
            }
            .navigationTitle("Settings")
        }
    }

    private var permissionText: String {
        switch locationService.authorizationStatus {
        case .authorizedAlways: return "Always"
        case .authorizedWhenInUse: return "When In Use"
        case .denied: return "Denied"
        case .restricted: return "Restricted"
        case .notDetermined: return "Not Set"
        @unknown default: return "Unknown"
        }
    }
}
