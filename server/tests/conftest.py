"""Shared pytest fixtures: in-memory DB, test user, test device."""

import sys
import os

# Add server root to path so imports work
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

import database as db_module
from database import Base
from models import User, Device, Location, Place, Visit
from auth import hash_password


@pytest.fixture
def engine():
    """Create a fresh in-memory SQLite database for each test."""
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(bind=engine)
    return engine


@pytest.fixture
def db(engine):
    """Provide a DB session, rolled back after each test."""
    Session = sessionmaker(bind=engine)
    session = Session()
    yield session
    session.close()


@pytest.fixture
def test_user(db):
    """Create a test user."""
    user = User(
        username="testuser",
        email="test@example.com",
        password_hash=hash_password("testpass123"),
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    return user


@pytest.fixture
def test_device(db, test_user):
    """Create a test device for the test user."""
    device = Device(
        name="Test iPhone",
        identifier="test-device-001",
        user_id=test_user.id,
    )
    db.add(device)
    db.commit()
    db.refresh(device)
    return device


@pytest.fixture
def populated_device(db, test_device):
    """Create a device populated with the full GPS trace fixture data."""
    from tests.gps_test_fixtures import GPS_TRACE

    for pt in GPS_TRACE:
        loc = Location(
            device_id=test_device.id,
            latitude=pt["latitude"],
            longitude=pt["longitude"],
            altitude=pt.get("altitude"),
            horizontal_accuracy=pt.get("horizontal_accuracy"),
            speed=pt.get("speed"),
            timestamp=pt["timestamp"],
        )
        db.add(loc)
    db.commit()
    return test_device
