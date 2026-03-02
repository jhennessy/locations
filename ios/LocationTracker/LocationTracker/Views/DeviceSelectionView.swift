import SwiftUI

struct DeviceSelectionView: View {
    @EnvironmentObject var api: APIService
    @ObservedObject var locationService = LocationService.shared

    @State private var devices: [DeviceInfo] = []
    @State private var isLoading = true
    @State private var errorMessage: String?
    @State private var showAddDevice = false
    @State private var newDeviceName = ""
    @State private var newDeviceIdentifier = ""

    var body: some View {
        NavigationStack {
            Group {
                if isLoading {
                    ProgressView("Loading devices...")
                } else if devices.isEmpty {
                    ContentUnavailableView(
                        "No Devices",
                        systemImage: "iphone.slash",
                        description: Text("Register a device to start tracking locations.")
                    )
                } else {
                    List {
                        ForEach(devices) { device in
                            deviceRow(device)
                        }
                        .onDelete(perform: deleteDevice)
                    }
                }
            }
            .navigationTitle("Select Device")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button(action: { showAddDevice = true }) {
                        Image(systemName: "plus")
                    }
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button("Logout") {
                        locationService.stopTracking()
                        locationService.deviceId = nil
                        Task { await api.logout() }
                    }
                }
            }
            .task {
                await loadDevices()
            }
            .refreshable {
                await loadDevices()
            }
            .alert("Add Device", isPresented: $showAddDevice) {
                TextField("Device Name", text: $newDeviceName)
                TextField("Unique Identifier", text: $newDeviceIdentifier)
                Button("Cancel", role: .cancel) {
                    newDeviceName = ""
                    newDeviceIdentifier = ""
                }
                Button("Add") {
                    Task { await addDevice() }
                }
            } message: {
                Text("Enter a name and unique identifier for this device.")
            }
        }
    }

    private func deviceRow(_ device: DeviceInfo) -> some View {
        Button(action: { selectDevice(device) }) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text(device.name)
                        .font(.headline)
                    Text("ID: \(device.identifier)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    if let lastSeen = device.lastSeen {
                        Text("Last seen: \(lastSeen)")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }

                Spacer()

                if locationService.deviceId == device.id {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(.green)
                }
            }
        }
        .foregroundStyle(.primary)
    }

    private func selectDevice(_ device: DeviceInfo) {
        locationService.deviceId = device.id
    }

    private func loadDevices() async {
        isLoading = true
        do {
            devices = try await api.fetchDevices()
        } catch {
            errorMessage = error.localizedDescription
        }
        isLoading = false
    }

    private func addDevice() async {
        guard !newDeviceName.isEmpty, !newDeviceIdentifier.isEmpty else { return }
        do {
            let device = try await api.createDevice(name: newDeviceName, identifier: newDeviceIdentifier)
            devices.append(device)
            newDeviceName = ""
            newDeviceIdentifier = ""
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func deleteDevice(at offsets: IndexSet) {
        for index in offsets {
            let device = devices[index]
            Task {
                do {
                    try await api.deleteDevice(id: device.id)
                    await MainActor.run {
                        devices.remove(at: index)
                        if locationService.deviceId == device.id {
                            locationService.deviceId = nil
                            locationService.stopTracking()
                        }
                    }
                } catch {
                    errorMessage = error.localizedDescription
                }
            }
        }
    }
}
