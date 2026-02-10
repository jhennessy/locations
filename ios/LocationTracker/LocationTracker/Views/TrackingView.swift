import SwiftUI
import MapKit

struct TrackingView: View {
    @ObservedObject var locationService = LocationService.shared

    @State private var cameraPosition: MapCameraPosition = .automatic

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
    }

    private var trackingModeIcon: String {
        switch locationService.trackingMode {
        case .gettingFix: return "antenna.radiowaves.left.and.right"
        case .sleeping: return "moon.zzz.fill"
        }
    }

    private var trackingModeColor: Color {
        switch locationService.trackingMode {
        case .gettingFix: return .blue
        case .sleeping: return .orange
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
}
