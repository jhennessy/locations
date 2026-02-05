"""Tests for the GPS processing pipeline: filtering, visit detection, place snapping."""

import datetime
from unittest.mock import patch

import pytest

from processing import (
    filter_gps_errors,
    detect_visits,
    merge_nearby_visits,
    haversine_m,
    snap_to_place,
    process_device_locations,
    VISIT_RADIUS_M,
    PLACE_SNAP_RADIUS_M,
)
from models import Location, Place, Visit
from tests.gps_test_fixtures import (
    GPS_TRACE,
    HOME_SEGMENT,
    WALK_TO_COFFEE,
    COFFEE_SEGMENT,
    WALK_TO_OFFICE,
    OFFICE_SEGMENT,
    HOME_CENTER,
    COFFEE_SHOP_CENTER,
    OFFICE_CENTER,
    BAD_ACCURACY_POINT,
    BAD_SPEED_POINT,
    DUPLICATE_TIME_POINT,
    SEGMENTS,
)


# =====================================================================
# Haversine tests
# =====================================================================

class TestHaversine:
    def test_same_point_is_zero(self):
        assert haversine_m(37.7749, -122.4194, 37.7749, -122.4194) == 0.0

    def test_known_distance(self):
        # SF City Hall to Ferry Building is roughly 2.5 km
        d = haversine_m(37.7793, -122.4193, 37.7956, -122.3935)
        assert 2000 < d < 3000

    def test_short_distance(self):
        # Two points ~100m apart
        d = haversine_m(37.7749, -122.4194, 37.7758, -122.4194)
        assert 90 < d < 110


# =====================================================================
# GPS error filter tests
# =====================================================================

class TestFilterGPSErrors:
    def test_clean_data_passes_through(self):
        result = filter_gps_errors(HOME_SEGMENT)
        assert len(result) == len(HOME_SEGMENT)

    def test_full_trace_preserves_most_points(self):
        result = filter_gps_errors(GPS_TRACE)
        # All 50 test points have valid accuracy and speed
        assert len(result) == len(GPS_TRACE)

    def test_filters_bad_accuracy(self):
        points = [HOME_SEGMENT[0], BAD_ACCURACY_POINT, HOME_SEGMENT[1]]
        result = filter_gps_errors(points)
        lats = [p["latitude"] for p in result]
        assert BAD_ACCURACY_POINT["latitude"] not in lats

    def test_filters_bad_speed(self):
        points = [HOME_SEGMENT[0], HOME_SEGMENT[1], BAD_SPEED_POINT, HOME_SEGMENT[2]]
        result = filter_gps_errors(points)
        speeds = [p["speed"] for p in result]
        assert 200.0 not in speeds

    def test_filters_duplicate_timestamps(self):
        points = [HOME_SEGMENT[0], DUPLICATE_TIME_POINT]
        result = filter_gps_errors(points)
        # Only one should survive (they're 1 sec apart, < MIN_POINT_INTERVAL_S=2)
        assert len(result) == 1

    def test_empty_input(self):
        assert filter_gps_errors([]) == []

    def test_sorts_by_timestamp(self):
        reversed_pts = list(reversed(HOME_SEGMENT))
        result = filter_gps_errors(reversed_pts)
        timestamps = [p["timestamp"] for p in result]
        assert timestamps == sorted(timestamps)


# =====================================================================
# Visit detection tests
# =====================================================================

class TestDetectVisits:
    def test_detects_three_visits_from_full_trace(self):
        clean = filter_gps_errors(GPS_TRACE)
        visits = detect_visits(clean)
        # Should find: Home (12 min), Coffee (8 min), Office (33 min)
        assert len(visits) == 3

    def test_home_visit_duration(self):
        clean = filter_gps_errors(GPS_TRACE)
        visits = detect_visits(clean)
        home_visit = visits[0]
        # Home segment is 12 min = 720s
        assert home_visit["duration_seconds"] >= 300  # at least 5 min

    def test_office_visit_is_longest(self):
        clean = filter_gps_errors(GPS_TRACE)
        visits = detect_visits(clean)
        durations = [v["duration_seconds"] for v in visits]
        # Office (33 min) should be the longest
        assert max(durations) == durations[-1]

    def test_visit_locations_near_centers(self):
        clean = filter_gps_errors(GPS_TRACE)
        visits = detect_visits(clean)

        expected_centers = [HOME_CENTER, COFFEE_SHOP_CENTER, OFFICE_CENTER]
        for visit, center in zip(visits, expected_centers):
            dist = haversine_m(
                visit["latitude"], visit["longitude"],
                center["latitude"], center["longitude"],
            )
            assert dist < VISIT_RADIUS_M, (
                f"Visit at ({visit['latitude']}, {visit['longitude']}) "
                f"is {dist:.0f}m from expected center"
            )

    def test_walking_segments_are_not_visits(self):
        # Walking for 5 min shouldn't be a visit since points are spread out
        visits = detect_visits(WALK_TO_COFFEE)
        assert len(visits) == 0

    def test_too_few_points(self):
        assert detect_visits([]) == []
        assert detect_visits([HOME_SEGMENT[0]]) == []

    def test_short_stay_not_detected(self):
        # 4 minute stay (below 5 min threshold)
        base = datetime.datetime(2024, 1, 15, 10, 0, 0)
        short_stay = [
            {"latitude": 37.78, "longitude": -122.41, "timestamp": base},
            {"latitude": 37.78001, "longitude": -122.41001, "timestamp": base + datetime.timedelta(minutes=2)},
            {"latitude": 37.78002, "longitude": -122.41002, "timestamp": base + datetime.timedelta(minutes=4)},
        ]
        visits = detect_visits(short_stay)
        assert len(visits) == 0


# =====================================================================
# Place snapping tests
# =====================================================================

class TestPlaceSnapping:
    def test_creates_new_place(self, db, test_user):
        place = snap_to_place(db, test_user.id, 37.7615, -122.4240)
        assert place.id is not None
        assert place.user_id == test_user.id

    def test_snaps_to_existing_place(self, db, test_user):
        # Create a place
        place1 = snap_to_place(db, test_user.id, 37.7615, -122.4240)
        db.commit()

        # Try to snap to nearby point (within PLACE_SNAP_RADIUS_M)
        place2 = snap_to_place(db, test_user.id, 37.7616, -122.4241)
        assert place2.id == place1.id

    def test_does_not_snap_to_distant_place(self, db, test_user):
        place1 = snap_to_place(db, test_user.id, 37.7615, -122.4240)
        db.commit()

        # Office is far from home
        place2 = snap_to_place(db, test_user.id, 37.7738, -122.4128)
        assert place2.id != place1.id

    def test_snaps_to_nearest_of_multiple_places(self, db, test_user):
        home = snap_to_place(db, test_user.id, HOME_CENTER["latitude"], HOME_CENTER["longitude"])
        db.commit()
        coffee = snap_to_place(db, test_user.id, COFFEE_SHOP_CENTER["latitude"], COFFEE_SHOP_CENTER["longitude"])
        db.commit()
        office = snap_to_place(db, test_user.id, OFFICE_CENTER["latitude"], OFFICE_CENTER["longitude"])
        db.commit()

        # Point very close to coffee shop should snap there
        result = snap_to_place(db, test_user.id, 37.7656, -122.4196)
        assert result.id == coffee.id


# =====================================================================
# Full pipeline tests
# =====================================================================

class TestProcessDeviceLocations:
    @patch("processing.reverse_geocode", return_value="123 Test St, San Francisco, CA")
    def test_full_pipeline(self, mock_geocode, db, test_user, populated_device):
        visits = process_device_locations(db, populated_device.id, test_user.id)

        assert len(visits) == 3
        assert all(v.place_id is not None for v in visits)
        assert all(v.address is not None for v in visits)

    @patch("processing.reverse_geocode", return_value=None)
    def test_pipeline_handles_geocode_failure(self, mock_geocode, db, test_user, populated_device):
        visits = process_device_locations(db, populated_device.id, test_user.id)
        assert len(visits) == 3
        # Visits still created, just without addresses
        assert all(v.latitude != 0 for v in visits)

    @patch("processing.reverse_geocode", return_value="Test Address")
    def test_incremental_processing(self, mock_geocode, db, test_user, populated_device):
        # First run
        visits1 = process_device_locations(db, populated_device.id, test_user.id)
        assert len(visits1) == 3

        # Second run (no new data) should find nothing new
        visits2 = process_device_locations(db, populated_device.id, test_user.id)
        assert len(visits2) == 0

    @patch("processing.reverse_geocode", return_value="Test Address")
    def test_places_are_created(self, mock_geocode, db, test_user, populated_device):
        process_device_locations(db, populated_device.id, test_user.id)

        places = db.query(Place).filter(Place.user_id == test_user.id).all()
        assert len(places) == 3  # Home, Coffee, Office

    @patch("processing.reverse_geocode", return_value="Test Address")
    def test_place_visit_counts(self, mock_geocode, db, test_user, populated_device):
        process_device_locations(db, populated_device.id, test_user.id)

        places = db.query(Place).filter(Place.user_id == test_user.id).all()
        total_visits = sum(p.visit_count for p in places)
        assert total_visits == 3

    def test_empty_device(self, db, test_user, test_device):
        visits = process_device_locations(db, test_device.id, test_user.id)
        assert len(visits) == 0


# =====================================================================
# Visit merging tests
# =====================================================================

class TestMergeNearbyVisits:
    def test_no_merge_when_different_locations(self):
        """Visits at different places should not be merged."""
        base = datetime.datetime(2024, 1, 15, 10, 0, 0)
        visits = [
            {"latitude": 37.7615, "longitude": -122.4240, "arrival": base,
             "departure": base + datetime.timedelta(minutes=10), "duration_seconds": 600},
            {"latitude": 37.7738, "longitude": -122.4128, "arrival": base + datetime.timedelta(minutes=11),
             "departure": base + datetime.timedelta(minutes=20), "duration_seconds": 540},
        ]
        result = merge_nearby_visits(visits)
        assert len(result) == 2

    def test_no_merge_when_gap_too_long(self):
        """Visits at the same place but >3 min apart should not be merged."""
        base = datetime.datetime(2024, 1, 15, 10, 0, 0)
        visits = [
            {"latitude": 37.7615, "longitude": -122.4240, "arrival": base,
             "departure": base + datetime.timedelta(minutes=10), "duration_seconds": 600},
            {"latitude": 37.7616, "longitude": -122.4241, "arrival": base + datetime.timedelta(minutes=14),
             "departure": base + datetime.timedelta(minutes=25), "duration_seconds": 660},
        ]
        result = merge_nearby_visits(visits)
        assert len(result) == 2

    def test_merges_same_place_short_gap(self):
        """Visits at the same place with <3 min gap should be merged."""
        base = datetime.datetime(2024, 1, 15, 10, 0, 0)
        visits = [
            {"latitude": 37.7615, "longitude": -122.4240, "arrival": base,
             "departure": base + datetime.timedelta(minutes=10), "duration_seconds": 600},
            {"latitude": 37.7616, "longitude": -122.4241, "arrival": base + datetime.timedelta(minutes=12),
             "departure": base + datetime.timedelta(minutes=25), "duration_seconds": 780},
        ]
        result = merge_nearby_visits(visits)
        assert len(result) == 1
        # Duration should span from first arrival to last departure (25 min = 1500s)
        assert result[0]["duration_seconds"] == 1500
        assert result[0]["arrival"] == base
        assert result[0]["departure"] == base + datetime.timedelta(minutes=25)

    def test_merges_multiple_returns(self):
        """Three visits at the same place with short gaps should all merge."""
        base = datetime.datetime(2024, 1, 15, 10, 0, 0)
        visits = [
            {"latitude": 37.7615, "longitude": -122.4240, "arrival": base,
             "departure": base + datetime.timedelta(minutes=10), "duration_seconds": 600},
            {"latitude": 37.7616, "longitude": -122.4241, "arrival": base + datetime.timedelta(minutes=12),
             "departure": base + datetime.timedelta(minutes=20), "duration_seconds": 480},
            {"latitude": 37.7615, "longitude": -122.4240, "arrival": base + datetime.timedelta(minutes=21),
             "departure": base + datetime.timedelta(minutes=30), "duration_seconds": 540},
        ]
        result = merge_nearby_visits(visits)
        assert len(result) == 1
        assert result[0]["duration_seconds"] == 1800  # 30 min

    def test_empty_and_single(self):
        assert merge_nearby_visits([]) == []
        v = [{"latitude": 37.7615, "longitude": -122.4240,
              "arrival": datetime.datetime.now(), "departure": datetime.datetime.now(),
              "duration_seconds": 600}]
        assert len(merge_nearby_visits(v)) == 1

    def test_existing_trace_unchanged(self):
        """The standard test trace (Home → Coffee → Office) should not merge
        because the places are far apart."""
        clean = filter_gps_errors(GPS_TRACE)
        visits = detect_visits(clean)
        merged = merge_nearby_visits(visits)
        assert len(merged) == len(visits)  # still 3
