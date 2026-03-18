import SwiftUI
import MapKit

struct FrequentPlacesView: View {
    @EnvironmentObject var api: APIService

    @State private var places: [PlaceInfo] = []
    @State private var isLoading = true
    @State private var errorMessage: String?
    @State private var cameraPosition: MapCameraPosition = .automatic
    @State private var expandedPlaceId: Int?
    @State private var placeVisits: [Int: [VisitInfo]] = [:]
    @State private var loadingVisitsFor: Int?

    var body: some View {
        NavigationStack {
            ScrollView {
                if isLoading {
                    ProgressView("Loading places...")
                        .frame(maxWidth: .infinity, minHeight: 300)
                } else if places.isEmpty {
                    ContentUnavailableView(
                        "No Frequent Places",
                        systemImage: "star.slash",
                        description: Text("Places visited two or more times will appear here.")
                    )
                    .frame(maxWidth: .infinity, minHeight: 400)
                } else {
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
                                            .fill(expandedPlaceId == place.id ? .blue : .orange)
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
                                    .onTapGesture {
                                        handlePlaceTap(place)
                                    }
                            }
                        }
                        .padding(.horizontal)
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
        VStack(spacing: 0) {
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

                Image(systemName: expandedPlaceId == place.id ? "chevron.up" : "chevron.down")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding()

            // Expanded visits section
            if expandedPlaceId == place.id {
                Divider()

                if loadingVisitsFor == place.id {
                    ProgressView()
                        .padding()
                } else if let visits = placeVisits[place.id], !visits.isEmpty {
                    VStack(spacing: 0) {
                        ForEach(visits) { visit in
                            visitRow(visit)
                            if visit.id != visits.last?.id {
                                Divider().padding(.leading, 16)
                            }
                        }
                    }
                } else {
                    Text("No visits found")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .padding()
                }
            }
        }
        .background(Color(.systemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .shadow(color: .black.opacity(0.05), radius: 2, y: 1)
    }

    private func visitRow(_ visit: VisitInfo) -> some View {
        HStack(spacing: 12) {
            Text(visit.formattedDuration)
                .font(.caption.bold())
                .foregroundStyle(.white)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(.blue, in: Capsule())

            VStack(alignment: .leading, spacing: 2) {
                if let arrival = visit.arrivalDate {
                    Text(arrival, format: .dateTime.weekday(.abbreviated).month(.abbreviated).day())
                        .font(.caption.bold())
                }
                HStack(spacing: 4) {
                    if let arrival = visit.arrivalDate {
                        Text(arrival, format: .dateTime.hour().minute())
                    }
                    if let departure = visit.departureDate {
                        Text("–")
                        Text(departure, format: .dateTime.hour().minute())
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }

            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }

    private func handlePlaceTap(_ place: PlaceInfo) {
        withAnimation {
            if expandedPlaceId == place.id {
                expandedPlaceId = nil
            } else {
                expandedPlaceId = place.id
                cameraPosition = .region(MKCoordinateRegion(
                    center: .init(latitude: place.latitude, longitude: place.longitude),
                    latitudinalMeters: 2000,
                    longitudinalMeters: 2000
                ))

                // Load visits if not already cached
                if placeVisits[place.id] == nil {
                    loadingVisitsFor = place.id
                    Task {
                        do {
                            let visits = try await api.fetchPlaceVisits(placeId: place.id)
                            placeVisits[place.id] = visits
                        } catch {
                            placeVisits[place.id] = []
                        }
                        loadingVisitsFor = nil
                    }
                }
            }
        }
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
