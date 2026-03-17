import SwiftUI
import MapKit

struct TrackingView: View {
    @ObservedObject var locationService = LocationService.shared
    @ObservedObject var bluetoothService = BluetoothService.shared

    @State private var cameraPosition: MapCameraPosition = .automatic
    @State private var serverPositions: [ServerPosition] = []
    @State private var positionFetchTimer: Timer?

    var body: some View {
        VStack(spacing: 0) {
            // Map
            Map(position: $cameraPosition) {
                if let location = locationService.lastLocation {
                    Annotation("Current", coordinate: location.coordinate) {
                        Image(systemName: "location.fill")
                            .foregroundStyle(.blue)
                            .padding(6)
                            .background(.white)
                            .clipShape(Circle())
                            .shadow(radius: 2)
                    }
                }

                // BLE peer positions (green)
                ForEach(Array(bluetoothService.peers.values)) { peer in
                    if !peer.isStale {
                        Annotation(peer.username, coordinate: CLLocationCoordinate2D(
                            latitude: peer.latitude, longitude: peer.longitude
                        )) {
                            Image(systemName: "antenna.radiowaves.left.and.right")
                                .foregroundStyle(.white)
                                .padding(5)
                                .background(.green)
                                .clipShape(Circle())
                                .shadow(radius: 2)
                        }
                    }
                }

                // Server positions (orange) — exclude own device
                ForEach(serverPositions.filter { $0.deviceId != locationService.deviceId }) { pos in
                    Annotation(pos.username, coordinate: CLLocationCoordinate2D(
                        latitude: pos.latitude, longitude: pos.longitude
                    )) {
                        Image(systemName: pos.isStale ? "clock" : "figure.stand")
                            .foregroundStyle(.white)
                            .padding(5)
                            .background(pos.isStale ? .gray : .orange)
                            .clipShape(Circle())
                            .shadow(radius: 2)
                    }
                }
            }
            .frame(maxHeight: .infinity)
            .onChange(of: locationService.lastLocation) { _, newValue in
                if let loc = newValue {
                    withAnimation {
                        cameraPosition = .region(MKCoordinateRegion(
                            center: loc.coordinate,
                            latitudinalMeters: 500,
                            longitudinalMeters: 500
                        ))
                    }
                }
            }

            // Status panel
            VStack(spacing: 12) {
                // Tracking toggle
                HStack {
                    Image(systemName: locationService.isTracking ? "location.fill" : "location.slash")
                        .foregroundStyle(locationService.isTracking ? .green : .secondary)

                    Text(locationService.isTracking ? "Tracking Active" : "Tracking Paused")
                        .font(.headline)

                    Spacer()

                    Toggle("", isOn: Binding(
                        get: { locationService.isTracking },
                        set: { newValue in
                            if newValue {
                                locationService.requestPermission()
                                locationService.startTracking()
                            } else {
                                locationService.stopTracking()
                            }
                        }
                    ))
                    .labelsHidden()
                }

                // Tracking state
                if locationService.isTracking {
                    HStack(spacing: 8) {
                        Image(systemName: trackingModeIcon)
                            .foregroundStyle(trackingModeColor)
                        Text(locationService.trackingMode.description)
                            .font(.subheadline.bold())
                            .foregroundStyle(trackingModeColor)
                        if locationService.trackingMode == .sleeping {
                            Text("·")
                                .foregroundStyle(.secondary)
                            Text("Fence: \(Int(locationService.geofenceRadius))m")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            if locationService.lastSpeed > 0.5 {
                                Text("·")
                                    .foregroundStyle(.secondary)
                                Text(String(format: "%.0f km/h", locationService.lastSpeed * 3.6))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        if locationService.trackingMode == .continuous {
                            Text("·")
                                .foregroundStyle(.secondary)
                            Text("10m filter")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        if locationService.isCharging {
                            Text("·")
                                .foregroundStyle(.secondary)
                            Image(systemName: "bolt.fill")
                                .foregroundStyle(.green)
                                .font(.caption)
                            Text("Charging")
                                .font(.caption)
                                .foregroundStyle(.green)
                        }
                    }

                }

                // Bluetooth mesh section
                Divider()

                HStack {
                    Image(systemName: "antenna.radiowaves.left.and.right")
                        .foregroundStyle(bluetoothService.isRunning ? .blue : .secondary)

                    Text("Bluetooth Mesh")
                        .font(.headline)

                    if bluetoothService.isRunning {
                        Text(bluetoothService.bleStatus)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }

                    Spacer()

                    Toggle("", isOn: Binding(
                        get: { locationService.bleEnabled },
                        set: { locationService.bleEnabled = $0 }
                    ))
                    .labelsHidden()
                }

                if bluetoothService.isRunning {
                    // Peer list
                    let activePeers = bluetoothService.peers.values
                        .sorted { $0.username < $1.username }

                    if activePeers.isEmpty {
                        HStack(spacing: 6) {
                            ProgressView()
                                .scaleEffect(0.7)
                            Text("Scanning for nearby devices...")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    } else {
                        ForEach(Array(activePeers)) { peer in
                            HStack(spacing: 10) {
                                Image(systemName: peer.isStale ? "circle.dotted" : "circle.fill")
                                    .font(.caption2)
                                    .foregroundStyle(peer.isStale ? Color.secondary : Color.green)

                                VStack(alignment: .leading, spacing: 2) {
                                    Text(peer.username)
                                        .font(.subheadline)
                                    Text("Device \(peer.id)")
                                        .font(.caption2)
                                        .foregroundStyle(.secondary)
                                }

                                Spacer()

                                if let myLocation = locationService.lastLocation {
                                    let peerLocation = CLLocation(latitude: peer.latitude, longitude: peer.longitude)
                                    let distance = myLocation.distance(from: peerLocation)
                                    Text(formatDistance(distance))
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }

                                Text(peer.timestamp, format: .dateTime.hour().minute())
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }

                    // Server positions
                    let otherPositions = serverPositions.filter { $0.deviceId != locationService.deviceId }
                    if !otherPositions.isEmpty {
                        Divider()
                        HStack(spacing: 6) {
                            Image(systemName: "globe")
                                .foregroundStyle(.orange)
                                .font(.caption)
                            Text("\(otherPositions.count) device\(otherPositions.count == 1 ? "" : "s") on server")
                                .font(.caption)
                                .foregroundStyle(.orange)
                        }
                    }
                }

                // Stats
                HStack(spacing: 24) {
                    statItem(title: "Buffered", value: "\(locationService.buffer.count)")
                    statItem(title: "Batch Size", value: "\(locationService.batchSize)")

                    if let loc = locationService.lastLocation {
                        statItem(
                            title: "Accuracy",
                            value: String(format: "%.0fm", loc.horizontalAccuracy)
                        )
                    }
                }

                // Upload error
                if let error = locationService.uploadError {
                    HStack {
                        Image(systemName: "exclamationmark.triangle")
                            .foregroundStyle(.orange)
                        Text(error)
                            .font(.caption)
                            .foregroundStyle(.orange)
                    }
                }

                // Manual flush button
                if !locationService.buffer.isEmpty {
                    Button("Upload Now (\(locationService.buffer.count) points)") {
                        Task { await locationService.flushBuffer() }
                    }
                    .buttonStyle(.bordered)
                }

                // Coordinates display
                if let loc = locationService.lastLocation {
                    Text(String(
                        format: "%.6f, %.6f",
                        loc.coordinate.latitude,
                        loc.coordinate.longitude
                    ))
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                }
            }
            .padding()
            .background(.ultraThinMaterial)
        }
        .onAppear { startPositionFetch() }
        .onDisappear { stopPositionFetch() }
    }

    // MARK: - Position fetch timer

    private func startPositionFetch() {
        fetchServerPositions()
        positionFetchTimer = Timer.scheduledTimer(withTimeInterval: 15, repeats: true) { _ in
            fetchServerPositions()
        }
    }

    private func stopPositionFetch() {
        positionFetchTimer?.invalidate()
        positionFetchTimer = nil
    }

    private func fetchServerPositions() {
        Task {
            if let positions = try? await APIService.shared.fetchAllPositions() {
                serverPositions = positions
            }
        }
    }

    // MARK: - Helpers

    private var trackingModeIcon: String {
        switch locationService.trackingMode {
        case .gettingFix: return "antenna.radiowaves.left.and.right"
        case .sleeping: return "moon.zzz.fill"
        case .continuous: return "arrow.triangle.swap"
        }
    }

    private var trackingModeColor: Color {
        switch locationService.trackingMode {
        case .gettingFix: return .blue
        case .sleeping: return .orange
        case .continuous: return .green
        }
    }

    private func statItem(title: String, value: String) -> some View {
        VStack(spacing: 2) {
            Text(value)
                .font(.title3.bold())
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private func formatDistance(_ meters: Double) -> String {
        if meters < 1000 {
            return String(format: "%.0f m", meters)
        } else {
            return String(format: "%.1f km", meters / 1000)
        }
    }
}
