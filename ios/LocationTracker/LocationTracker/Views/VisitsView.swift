import SwiftUI
import MapKit

struct VisitsView: View {
    @EnvironmentObject var api: APIService
    @ObservedObject var locationService = LocationService.shared

    @State private var visits: [VisitInfo] = []
    @State private var isLoading = true
    @State private var errorMessage: String?
    @State private var cameraPosition: MapCameraPosition = .automatic

    var body: some View {
        NavigationStack {
            Group {
                if isLoading {
                    ProgressView("Loading visits...")
                } else if visits.isEmpty {
                    ContentUnavailableView(
                        "No Visits Yet",
                        systemImage: "mappin.slash",
                        description: Text("Visits are detected when you stay in one place for at least 5 minutes.")
                    )
                } else {
                    ScrollView {
                        VStack(spacing: 16) {
                            // Map showing visit locations
                            Map(position: $cameraPosition) {
                                ForEach(visits) { visit in
                                    Annotation(
                                        visit.formattedDuration,
                                        coordinate: .init(
                                            latitude: visit.latitude,
                                            longitude: visit.longitude
                                        )
                                    ) {
                                        Image(systemName: "mappin.circle.fill")
                                            .foregroundStyle(.red)
                                            .font(.title2)
                                    }
                                }
                            }
                            .frame(height: 300)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                            .padding(.horizontal)

                            // Visit list
                            LazyVStack(spacing: 8) {
                                ForEach(visits) { visit in
                                    visitRow(visit)
                                }
                            }
                            .padding(.horizontal)
                        }
                    }
                }
            }
            .navigationTitle("Visits")
            .task {
                await loadVisits()
            }
            .refreshable {
                await loadVisits()
            }
        }
    }

    private func visitRow(_ visit: VisitInfo) -> some View {
        HStack(alignment: .top, spacing: 12) {
            // Duration badge
            VStack {
                Text(visit.formattedDuration)
                    .font(.caption.bold())
                    .foregroundStyle(.white)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(.blue, in: Capsule())
            }
            .frame(width: 64)

            VStack(alignment: .leading, spacing: 4) {
                Text(visit.displayLocation)
                    .font(.subheadline)
                    .lineLimit(2)

                if let arrival = visit.arrivalDate {
                    Text(arrival, format: .dateTime.month().day().hour().minute())
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            Spacer()
        }
        .padding()
        .background(Color(.systemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .shadow(color: .black.opacity(0.05), radius: 2, y: 1)
    }

    private func loadVisits() async {
        guard let deviceId = locationService.deviceId else {
            isLoading = false
            return
        }
        isLoading = true
        do {
            visits = try await api.fetchVisits(deviceId: deviceId)
            if let first = visits.first {
                cameraPosition = .region(MKCoordinateRegion(
                    center: .init(latitude: first.latitude, longitude: first.longitude),
                    latitudinalMeters: 2000,
                    longitudinalMeters: 2000
                ))
            }
        } catch {
            errorMessage = error.localizedDescription
        }
        isLoading = false
    }
}

#Preview("With Visits") {
    let service = LocationService.shared
    service.deviceId = 1
    return VisitsView()
        .environmentObject(APIService.shared)
        .onAppear {
            // Preview uses sample data injected directly
        }
}

#Preview("Empty") {
    VisitsView()
        .environmentObject(APIService.shared)
}
