"""Location processing engine: GPS filtering, visit detection, place snapping, geocoding.

Processing pipeline (runs server-side after each batch upload):
1. Filter out GPS errors (accuracy threshold, speed sanity, duplicates)
2. Cluster stationary points into candidate visits (>= 5 min in ~50m radius)
3. Snap each visit to nearest known Place (or create a new one)
4. Reverse-geocode new Places via Nominatim (OpenStreetMap, free)
"""

import datetime
import logging
import math
import time
from typing import Optional

import requests
from sqlalchemy.orm import Session

from models import Device, Location, Place, Visit

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

# GPS error filter thresholds
MAX_HORIZONTAL_ACCURACY_M = 100.0  # discard points with accuracy worse than this
MAX_SPEED_MS = 85.0                # ~306 km/h; discard impossible speeds
MIN_POINT_INTERVAL_S = 2           # deduplicate points closer than this in time

# Visit detection
VISIT_RADIUS_M = 50.0             # max radius for a stationary cluster
MIN_VISIT_DURATION_S = 300        # 5 minutes

# Place snapping
PLACE_SNAP_RADIUS_M = 80.0       # snap to existing place within this distance

# Nominatim rate limiting (max 1 req/sec per OSM policy)
_last_nominatim_call = 0.0


# ---------------------------------------------------------------------------
# Geo math
# ---------------------------------------------------------------------------

def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Distance in metres between two WGS-84 points."""
    R = 6_371_000  # Earth radius in metres
    rlat1, rlat2 = math.radians(lat1), math.radians(lat2)
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = math.sin(dlat / 2) ** 2 + math.cos(rlat1) * math.cos(rlat2) * math.sin(dlon / 2) ** 2
    return R * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


# ---------------------------------------------------------------------------
# Step 1: GPS error filtering
# ---------------------------------------------------------------------------

def filter_gps_errors(points: list[dict]) -> list[dict]:
    """Remove GPS points that are likely erroneous.

    Filters applied:
    - Horizontal accuracy > MAX_HORIZONTAL_ACCURACY_M
    - Speed > MAX_SPEED_MS (physically impossible)
    - Duplicate timestamps within MIN_POINT_INTERVAL_S
    """
    if not points:
        return []

    filtered = []
    last_ts = None

    for pt in sorted(points, key=lambda p: p["timestamp"]):
        # Accuracy filter
        acc = pt.get("horizontal_accuracy")
        if acc is not None and acc > MAX_HORIZONTAL_ACCURACY_M:
            continue

        # Speed filter
        speed = pt.get("speed")
        if speed is not None and speed > MAX_SPEED_MS:
            continue

        # Time-duplicate filter
        ts = pt["timestamp"]
        if last_ts is not None and (ts - last_ts).total_seconds() < MIN_POINT_INTERVAL_S:
            continue

        filtered.append(pt)
        last_ts = ts

    return filtered


# ---------------------------------------------------------------------------
# Step 2: Visit detection
# ---------------------------------------------------------------------------

def detect_visits(points: list[dict]) -> list[dict]:
    """Detect stationary visits from a time-sorted list of GPS points.

    Algorithm:
    - Walk through points chronologically.
    - Maintain a current cluster (centroid + members).
    - If next point is within VISIT_RADIUS_M of centroid, add it.
    - Otherwise, check if current cluster duration >= MIN_VISIT_DURATION_S;
      if yes, emit as a visit. Start a new cluster from the current point.
    """
    if len(points) < 2:
        return []

    visits = []
    cluster: list[dict] = [points[0]]
    cx, cy = points[0]["latitude"], points[0]["longitude"]

    for pt in points[1:]:
        dist = haversine_m(cx, cy, pt["latitude"], pt["longitude"])

        if dist <= VISIT_RADIUS_M:
            cluster.append(pt)
            # Update centroid as running mean
            n = len(cluster)
            cx = cx + (pt["latitude"] - cx) / n
            cy = cy + (pt["longitude"] - cy) / n
        else:
            # Finalize cluster if it qualifies as a visit
            _maybe_emit_visit(cluster, cx, cy, visits)
            # Start new cluster
            cluster = [pt]
            cx, cy = pt["latitude"], pt["longitude"]

    # Don't forget the last cluster
    _maybe_emit_visit(cluster, cx, cy, visits)

    return visits


def _maybe_emit_visit(
    cluster: list[dict],
    centroid_lat: float,
    centroid_lon: float,
    visits: list[dict],
):
    if len(cluster) < 2:
        return
    arrival = cluster[0]["timestamp"]
    departure = cluster[-1]["timestamp"]
    duration = (departure - arrival).total_seconds()
    if duration >= MIN_VISIT_DURATION_S:
        visits.append({
            "latitude": centroid_lat,
            "longitude": centroid_lon,
            "arrival": arrival,
            "departure": departure,
            "duration_seconds": int(duration),
        })


# ---------------------------------------------------------------------------
# Step 3: Place snapping
# ---------------------------------------------------------------------------

def snap_to_place(
    db: Session,
    user_id: int,
    lat: float,
    lon: float,
) -> Place:
    """Find the nearest existing Place within PLACE_SNAP_RADIUS_M, or create one."""
    places = db.query(Place).filter(Place.user_id == user_id).all()

    best_place = None
    best_dist = float("inf")

    for p in places:
        d = haversine_m(lat, lon, p.latitude, p.longitude)
        if d < best_dist:
            best_dist = d
            best_place = p

    if best_place is not None and best_dist <= PLACE_SNAP_RADIUS_M:
        return best_place

    # Create new place
    new_place = Place(
        user_id=user_id,
        latitude=lat,
        longitude=lon,
        visit_count=0,
        total_duration_seconds=0,
    )
    db.add(new_place)
    db.flush()  # get the id
    return new_place


# ---------------------------------------------------------------------------
# Step 4: Reverse geocoding (Nominatim / OpenStreetMap)
# ---------------------------------------------------------------------------

def reverse_geocode(lat: float, lon: float) -> Optional[str]:
    """Look up an address from coordinates using Nominatim (free, 1 req/s)."""
    global _last_nominatim_call

    # Rate limit
    elapsed = time.time() - _last_nominatim_call
    if elapsed < 1.1:
        time.sleep(1.1 - elapsed)

    try:
        resp = requests.get(
            "https://nominatim.openstreetmap.org/reverse",
            params={
                "lat": lat,
                "lon": lon,
                "format": "jsonv2",
                "zoom": 18,
                "addressdetails": 1,
            },
            headers={"User-Agent": "LocationTracker/1.0"},
            timeout=10,
        )
        _last_nominatim_call = time.time()

        if resp.status_code == 200:
            data = resp.json()
            return data.get("display_name")
    except Exception as e:
        logger.warning(f"Nominatim reverse geocode failed: {e}")

    return None


# ---------------------------------------------------------------------------
# Full pipeline: process locations for a device
# ---------------------------------------------------------------------------

def process_device_locations(db: Session, device_id: int, user_id: int) -> list[Visit]:
    """Run the full processing pipeline on all unprocessed locations for a device.

    Returns newly created Visit objects.
    """
    # Get the latest visit departure for this device (process only newer data)
    latest_visit = (
        db.query(Visit)
        .filter(Visit.device_id == device_id)
        .order_by(Visit.departure.desc())
        .first()
    )
    since = latest_visit.departure if latest_visit else datetime.datetime.min

    # Fetch raw locations since last processed visit
    raw_locations = (
        db.query(Location)
        .filter(Location.device_id == device_id, Location.timestamp > since)
        .order_by(Location.timestamp.asc())
        .all()
    )

    if not raw_locations:
        return []

    # Convert to dicts for processing
    points = [
        {
            "latitude": loc.latitude,
            "longitude": loc.longitude,
            "altitude": loc.altitude,
            "horizontal_accuracy": loc.horizontal_accuracy,
            "speed": loc.speed,
            "timestamp": loc.timestamp,
        }
        for loc in raw_locations
    ]

    # Step 1: Filter
    clean_points = filter_gps_errors(points)
    if not clean_points:
        return []

    # Step 2: Detect visits
    raw_visits = detect_visits(clean_points)
    if not raw_visits:
        return []

    # Step 3 & 4: Snap to places, geocode, create Visit records
    new_visits = []
    for v in raw_visits:
        place = snap_to_place(db, user_id, v["latitude"], v["longitude"])

        # Geocode if place has no address yet
        if not place.address:
            addr = reverse_geocode(place.latitude, place.longitude)
            if addr:
                place.address = addr

        # Update place stats
        place.visit_count += 1
        place.total_duration_seconds += v["duration_seconds"]

        visit = Visit(
            device_id=device_id,
            place_id=place.id,
            latitude=v["latitude"],
            longitude=v["longitude"],
            arrival=v["arrival"],
            departure=v["departure"],
            duration_seconds=v["duration_seconds"],
            address=place.address,
        )
        db.add(visit)
        new_visits.append(visit)

    db.commit()
    return new_visits
