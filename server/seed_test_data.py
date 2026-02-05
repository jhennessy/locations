#!/usr/bin/env python3
"""Seed the database with GPS test fixture data for development and web UI testing.

Usage:
    python seed_test_data.py

This creates a test user, device, and populates it with the 50-point
San Francisco commute trace, then runs the visit detection pipeline.
"""

import sys
from unittest.mock import patch

from database import init_db, SessionLocal
from models import User, Device, Location
from auth import hash_password
from tests.gps_test_fixtures import GPS_TRACE
from processing import process_device_locations


def seed():
    init_db()
    db = SessionLocal()

    # Create test user
    existing = db.query(User).filter(User.username == "demo").first()
    if existing:
        print("Demo user already exists. Skipping seed.")
        db.close()
        return

    user = User(
        username="demo",
        email="demo@example.com",
        password_hash=hash_password("demo"),
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    print(f"Created user: demo (id={user.id})")

    # Create device
    device = Device(
        name="Demo iPhone",
        identifier="demo-iphone-001",
        user_id=user.id,
    )
    db.add(device)
    db.commit()
    db.refresh(device)
    print(f"Created device: {device.name} (id={device.id})")

    # Insert GPS trace
    for pt in GPS_TRACE:
        loc = Location(
            device_id=device.id,
            latitude=pt["latitude"],
            longitude=pt["longitude"],
            altitude=pt.get("altitude"),
            horizontal_accuracy=pt.get("horizontal_accuracy"),
            speed=pt.get("speed"),
            timestamp=pt["timestamp"],
        )
        db.add(loc)
    db.commit()
    print(f"Inserted {len(GPS_TRACE)} location points")

    # Run visit detection (mock geocoding to avoid hitting Nominatim)
    with patch(
        "processing.reverse_geocode",
        side_effect=[
            "742 Valencia St, Mission District, San Francisco, CA 94110",
            "Ritual Coffee Roasters, 1026 Valencia St, San Francisco, CA 94110",
            "Mission District Office, 2100 18th St, San Francisco, CA 94107",
        ],
    ):
        visits = process_device_locations(db, device.id, user.id)
    print(f"Detected {len(visits)} visits")

    for v in visits:
        print(f"  - {v.address or 'Unknown'}: {v.duration_seconds // 60}m "
              f"({v.arrival.strftime('%H:%M')}-{v.departure.strftime('%H:%M')})")

    db.close()
    print("\nDone! Login with username='demo', password='demo'")


if __name__ == "__main__":
    seed()
