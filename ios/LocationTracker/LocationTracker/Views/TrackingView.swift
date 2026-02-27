import SwiftUI
import MapKit
import CoreLocation

struct TrackingView: View {
    @ObservedObject var locationService = LocationService.shared
    @ObservedObject private var bluetooth = BluetoothService.shared

    @State private var cameraPosition: MapCameraPosition = .automatic
    @State private var serverPositions: [APIService.ServerPosition] = []
    @State private var positionTimer: Timer?
    @State private var peersExpanded = false

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

                // BLE peer markers (green)
                ForEach(bluetooth.peers) { peer in
                    Annotation(peer.username, coordinate: CLLocationCoordinate2D(latitude: peer.latitude, longitude: peer.longitude)) {
                        Image(systemName: "mappin.circle.fill")
                            .foregroundStyle(.green)
                            .padding(4)
                            .background(.white)
                            .clipShape(Circle())
                            .shadow(radius: 2)
                    }
                }

                // Server position markers (orange), filtering out own device
                ForEach(filteredServerPositions) { pos in
                    Annotation(pos.username ?? "Device \(pos.device_id)", coordinate: CLLocationCoordinate2D(latitude: pos.latitude, longitude: pos.longitude)) {
                        Image(systemName: "mappin.circle.fill")
                            .foregroundStyle(.orange)
                            .padding(4)
                            .background(.white)
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

                    // BLE peer count indicator
                    if bluetooth.isRunning {
                        HStack(spacing: 4) {
                            Image(systemName: "antenna.radiowaves.left.and.right")
                                .foregroundStyle(bluetooth.connectedPeerCount > 0 ? .green : .secondary)
                            Text("\(bluetooth.connectedPeerCount)")
                                .font(.caption.bold())
                                .foregroundStyle(bluetooth.connectedPeerCount > 0 ? .green : .secondary)
                        }
                    }

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

                // Nearby people section
                if !allNearbyPeople.isEmpty {
                    Divider()

                    Button {
                        withAnimation { peersExpanded.toggle() }
                    } label: {
                        HStack {
                            Image(systemName: "person.2.fill")
                                .foregroundStyle(.primary)
                            Text("Nearby (\(allNearbyPeople.count))")
                                .font(.subheadline.bold())
                                .foregroundStyle(.primary)
                            Spacer()
                            Image(systemName: peersExpanded ? "chevron.up" : "chevron.down")
                                .foregroundStyle(.secondary)
                                .font(.caption)
                        }
                    }
                    .buttonStyle(.plain)

                    if peersExpanded {
                        ForEach(allNearbyPeople, id: \.id) { person in
                            HStack(spacing: 10) {
                                Circle()
                                    .fill(person.source == .ble ? .green : .orange)
                                    .frame(width: 8, height: 8)

                                VStack(alignment: .leading, spacing: 2) {
                                    HStack(spacing: 4) {
                                        Text(person.name)
                                            .font(.subheadline.bold())
                                        Text(person.source == .ble ? "BLE" : "Server")
                                            .font(.caption2)
                                            .padding(.horizontal, 4)
                                            .padding(.vertical, 1)
                                            .background(person.source == .ble ? Color.green.opacity(0.2) : Color.orange.opacity(0.2))
                                            .cornerRadius(4)
                                    }
                                    HStack(spacing: 8) {
                                        if let dist = person.distance {
                                            Text(formatDistance(dist))
                                                .font(.caption)
                                                .foregroundStyle(.secondary)
                                        }
                                        Text(person.timeAgo)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                }

                                Spacer()

                                if let acc = person.accuracy {
                                    Text("\(Int(acc))m")
                                        .font(.caption.monospaced())
                                        .foregroundStyle(.secondary)
                                }
                            }
                            .padding(.vertical, 2)
                        }
                    }
                }
            }
            .padding()
            .background(.ultraThinMaterial)
        }
        .onAppear {
            startPositionTimer()
        }
        .onDisappear {
            positionTimer?.invalidate()
            positionTimer = nil
        }
    }

    private var filteredServerPositions: [APIService.ServerPosition] {
        let ownDeviceId = UserDefaults.standard.object(forKey: "selected_device_id") as? Int
        return serverPositions.filter { $0.device_id != ownDeviceId && !$0.is_stale }
    }

    private func startPositionTimer() {
        fetchPositions()
        positionTimer?.invalidate()
        positionTimer = Timer.scheduledTimer(withTimeInterval: 15, repeats: true) { _ in
            fetchPositions()
        }
    }

    private func fetchPositions() {
        Task {
            serverPositions = await APIService.shared.fetchAllPositions()
        }
    }

    // MARK: - Nearby People

    enum PeerSource { case ble, server }

    struct NearbyPerson {
        let id: String
        let name: String
        let source: PeerSource
        let distance: Double?
        let accuracy: Double?
        let timestamp: Date
        let timeAgo: String
    }

    private var allNearbyPeople: [NearbyPerson] {
        let myLocation = locationService.lastLocation
        let ownDeviceId = UserDefaults.standard.object(forKey: "selected_device_id") as? Int

        var people: [NearbyPerson] = []

        // BLE peers
        for peer in bluetooth.peers where !peer.isStale {
            let dist = myLocation.map { loc in
                let peerLoc = CLLocation(latitude: peer.latitude, longitude: peer.longitude)
                return loc.distance(from: peerLoc)
            }
            people.append(NearbyPerson(
                id: "ble-\(peer.deviceId)",
                name: peer.username,
                source: .ble,
                distance: dist,
                accuracy: peer.accuracy,
                timestamp: peer.timestamp,
                timeAgo: relativeTime(from: peer.discoveredAt)
            ))
        }

        // Server positions (exclude own device and those already shown via BLE)
        let bleDeviceIds = Set(bluetooth.peers.map(\.deviceId))
        for pos in filteredServerPositions where !bleDeviceIds.contains(pos.device_id) {
            let dist = myLocation.map { loc in
                let posLoc = CLLocation(latitude: pos.latitude, longitude: pos.longitude)
                return loc.distance(from: posLoc)
            }
            let ts: Date
            if let tsStr = pos.timestamp {
                ts = ISO8601DateFormatter().date(from: tsStr) ?? Date()
            } else {
                ts = Date()
            }
            people.append(NearbyPerson(
                id: "srv-\(pos.device_id)",
                name: pos.username ?? pos.device_name ?? "Device \(pos.device_id)",
                source: .server,
                distance: dist,
                accuracy: pos.accuracy,
                timestamp: ts,
                timeAgo: relativeTime(from: ts)
            ))
        }

        return people.sorted { ($0.distance ?? .greatestFiniteMagnitude) < ($1.distance ?? .greatestFiniteMagnitude) }
    }

    private func relativeTime(from date: Date) -> String {
        let seconds = Int(Date().timeIntervalSince(date))
        if seconds < 60 { return "\(seconds)s ago" }
        let minutes = seconds / 60
        if minutes < 60 { return "\(minutes)m ago" }
        return "\(minutes / 60)h ago"
    }

    private func formatDistance(_ meters: Double) -> String {
        if meters < 1000 {
            return "\(Int(meters))m"
        }
        return String(format: "%.1fkm", meters / 1000)
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
}
