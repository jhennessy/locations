import SwiftUI
import MapKit

struct FrequentPlacesView: View {
    @EnvironmentObject var api: APIService

    @State private var places: [PlaceInfo] = []
    @State private var isLoading = true
    @State private var errorMessage: String?
    @State private var cameraPosition: MapCameraPosition = .automatic

    var body: some View {
        NavigationStack {
            Group {
                if isLoading {
                    ProgressView("Loading places...")
                } else if places.isEmpty {
                    ContentUnavailableView(
                        "No Frequent Places",
                        systemImage: "star.slash",
                        description: Text("Places visited two or more times will appear here.")
                    )
                } else {
                    ScrollView {
                        VStack(spacing: 16) {
                            // Map with all known places
                            Map(position: $cameraPosition) {
                                ForEach(places) { place in
                                    Annotation(
                                        place.displayName,
                                        coordinate: .init(
                                            latitude: place.latitude,
                                            longitude: place.longitude
                                        )
                                    ) {
                                        ZStack {
                                            Circle()
                                                .fill(.orange)
                                                .frame(width: 30, height: 30)
                                            Text("\(place.visitCount)")
                                                .font(.caption.bold())
                                                .foregroundStyle(.white)
                                        }
                                    }
                                }
                            }
                            .frame(height: 300)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                            .padding(.horizontal)

                            // Ranked list
                            LazyVStack(spacing: 8) {
                                ForEach(Array(places.enumerated()), id: \.element.id) { index, place in
                                    placeRow(place, rank: index + 1)
                                }
                            }
                            .padding(.horizontal)
                        }
                    }
                }
            }
            .navigationTitle("Frequent Places")
            .task {
                await loadPlaces()
            }
            .refreshable {
                await loadPlaces()
            }
        }
    }

    private func placeRow(_ place: PlaceInfo, rank: Int) -> some View {
        HStack(spacing: 12) {
            // Rank badge
            Text("#\(rank)")
                .font(.headline)
                .foregroundStyle(.secondary)
                .frame(width: 36)

            VStack(alignment: .leading, spacing: 4) {
                Text(place.displayName)
                    .font(.subheadline.bold())
                    .lineLimit(2)

                if let address = place.address, place.name != nil {
                    Text(address)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                HStack(spacing: 16) {
                    Label("\(place.visitCount) visits", systemImage: "arrow.counterclockwise")
                    Label(place.formattedTotalTime, systemImage: "clock")
                    Label("avg \(place.formattedAvgTime)", systemImage: "timer")
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }

            Spacer()
        }
        .padding()
        .background(Color(.systemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .shadow(color: .black.opacity(0.05), radius: 2, y: 1)
    }

    private func loadPlaces() async {
        isLoading = true
        do {
            places = try await api.fetchFrequentPlaces()
            if let first = places.first {
                cameraPosition = .region(MKCoordinateRegion(
                    center: .init(latitude: first.latitude, longitude: first.longitude),
                    latitudinalMeters: 5000,
                    longitudinalMeters: 5000
                ))
            }
        } catch {
            errorMessage = error.localizedDescription
        }
        isLoading = false
    }
}

#Preview("With Places") {
    FrequentPlacesView()
        .environmentObject(APIService.shared)
}

#Preview("Empty") {
    FrequentPlacesView()
        .environmentObject(APIService.shared)
}
