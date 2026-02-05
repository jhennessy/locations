import XCTest
@testable import LocationTracker

/// Tests for model decoding and computed properties using the test fixture data.
final class ModelTests: XCTestCase {

    // MARK: - VisitInfo tests

    func testVisitDecodingFromJSON() throws {
        let json = """
        {
            "id": 1,
            "device_id": 1,
            "place_id": 1,
            "latitude": 37.7615,
            "longitude": -122.4240,
            "arrival": "2024-01-15T08:00:00",
            "departure": "2024-01-15T08:12:00",
            "duration_seconds": 720,
            "address": "742 Valencia St, San Francisco, CA"
        }
        """.data(using: .utf8)!

        let visit = try JSONDecoder().decode(VisitInfo.self, from: json)
        XCTAssertEqual(visit.id, 1)
        XCTAssertEqual(visit.deviceId, 1)
        XCTAssertEqual(visit.placeId, 1)
        XCTAssertEqual(visit.durationSeconds, 720)
        XCTAssertEqual(visit.formattedDuration, "12m")
        XCTAssertEqual(visit.displayLocation, "742 Valencia St, San Francisco, CA")
    }

    func testVisitDurationFormatting() {
        // Less than an hour
        let shortVisit = TestData.sampleVisits[0]  // 720s = 12m
        XCTAssertEqual(shortVisit.formattedDuration, "12m")

        // More than an hour
        let longVisit = TestData.sampleVisits[2]  // 1980s = 33m
        XCTAssertEqual(longVisit.formattedDuration, "33m")
    }

    func testVisitDisplayLocationFallback() throws {
        let json = """
        {
            "id": 99,
            "device_id": 1,
            "place_id": 1,
            "latitude": 37.7615,
            "longitude": -122.4240,
            "arrival": "2024-01-15T08:00:00",
            "departure": "2024-01-15T08:12:00",
            "duration_seconds": 720,
            "address": null
        }
        """.data(using: .utf8)!

        let visit = try JSONDecoder().decode(VisitInfo.self, from: json)
        XCTAssertTrue(visit.displayLocation.contains("37.76150"))
    }

    // MARK: - PlaceInfo tests

    func testPlaceDecodingFromJSON() throws {
        let json = """
        {
            "id": 1,
            "latitude": 37.7615,
            "longitude": -122.4240,
            "name": "Home",
            "address": "742 Valencia St",
            "visit_count": 47,
            "total_duration_seconds": 432000
        }
        """.data(using: .utf8)!

        let place = try JSONDecoder().decode(PlaceInfo.self, from: json)
        XCTAssertEqual(place.id, 1)
        XCTAssertEqual(place.name, "Home")
        XCTAssertEqual(place.visitCount, 47)
        XCTAssertEqual(place.displayName, "Home")
    }

    func testPlaceDisplayNameFallsBackToAddress() throws {
        let json = """
        {
            "id": 4,
            "latitude": 37.785,
            "longitude": -122.409,
            "name": null,
            "address": "Whole Foods Market",
            "visit_count": 5,
            "total_duration_seconds": 5400
        }
        """.data(using: .utf8)!

        let place = try JSONDecoder().decode(PlaceInfo.self, from: json)
        XCTAssertEqual(place.displayName, "Whole Foods Market")
    }

    func testPlaceDisplayNameFallsBackToCoords() throws {
        let json = """
        {
            "id": 5,
            "latitude": 37.785,
            "longitude": -122.409,
            "name": null,
            "address": null,
            "visit_count": 1,
            "total_duration_seconds": 300
        }
        """.data(using: .utf8)!

        let place = try JSONDecoder().decode(PlaceInfo.self, from: json)
        XCTAssertTrue(place.displayName.contains("37.78500"))
    }

    func testPlaceDurationFormatting() {
        let place = TestData.samplePlaces[0]  // 432000s = 5 days
        XCTAssertTrue(place.formattedTotalTime.contains("d"))
    }

    // MARK: - DeviceInfo tests

    func testDeviceDecodingFromJSON() throws {
        let json = """
        {
            "id": 1,
            "name": "iPhone 15 Pro",
            "identifier": "iphone15-abc123",
            "last_seen": "2024-01-15T09:11:30"
        }
        """.data(using: .utf8)!

        let device = try JSONDecoder().decode(DeviceInfo.self, from: json)
        XCTAssertEqual(device.name, "iPhone 15 Pro")
        XCTAssertEqual(device.identifier, "iphone15-abc123")
    }

    // MARK: - LocationBatch tests

    func testLocationBatchEncoding() throws {
        let batch = LocationBatch(
            deviceId: 1,
            locations: [
                LocationPoint(
                    latitude: 37.7615,
                    longitude: -122.4240,
                    altitude: 22.3,
                    horizontalAccuracy: 8.0,
                    verticalAccuracy: nil,
                    speed: 0.0,
                    course: nil,
                    timestamp: "2024-01-15T08:00:00-08:00"
                )
            ]
        )

        let data = try JSONEncoder().encode(batch)
        let json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        XCTAssertEqual(json["device_id"] as? Int, 1)
        let locations = json["locations"] as! [[String: Any]]
        XCTAssertEqual(locations.count, 1)
        XCTAssertEqual(locations[0]["latitude"] as? Double, 37.7615)
    }

    // MARK: - TestData fixture validation

    func testSampleVisitsAreValid() {
        XCTAssertEqual(TestData.sampleVisits.count, 3)
        for visit in TestData.sampleVisits {
            XCTAssertGreaterThanOrEqual(visit.durationSeconds, 300)
            XCTAssertNotNil(visit.address)
        }
    }

    func testSamplePlacesAreRankedByVisitCount() {
        let counts = TestData.samplePlaces.map(\.visitCount)
        XCTAssertEqual(counts, counts.sorted(by: >))
    }
}
