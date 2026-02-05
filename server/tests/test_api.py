"""Tests for REST API endpoints using the ASGI test client."""

import datetime
from unittest.mock import patch

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from database import Base, get_db as original_get_db
from models import User, Device, Location, Place, Visit  # noqa: F401
from auth import hash_password, create_token
from tests.gps_test_fixtures import GPS_TRACE, HOME_SEGMENT


# ---------------------------------------------------------------------------
# Test setup — override get_db using the original function reference as key
# ---------------------------------------------------------------------------

@pytest.fixture
def app_and_db():
    """Create a test FastAPI app with an in-memory database."""
    # Use StaticPool so all threads/connections share the same in-memory DB
    engine = create_engine(
        "sqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    Base.metadata.create_all(bind=engine)
    TestSession = sessionmaker(bind=engine)

    def test_get_db():
        session = TestSession()
        try:
            yield session
        finally:
            session.close()

    from api import router

    app = FastAPI()
    # Use the original function reference (captured at api.py import time) as override key
    app.dependency_overrides[original_get_db] = test_get_db
    app.include_router(router)

    # Seed test user
    session = TestSession()
    user = User(username="testuser", email="test@example.com", password_hash=hash_password("testpass"))
    session.add(user)
    session.commit()
    session.refresh(user)
    token = create_token(user.id, user.username)
    session.close()

    client = TestClient(app)
    return client, token, TestSession


@pytest.fixture
def client(app_and_db):
    return app_and_db[0]


@pytest.fixture
def auth_headers(app_and_db):
    return {"Authorization": f"Bearer {app_and_db[1]}"}


# ---------------------------------------------------------------------------
# Auth endpoint tests
# ---------------------------------------------------------------------------

class TestAuthEndpoints:
    def test_register(self, client):
        resp = client.post("/api/register", json={
            "username": "newuser",
            "email": "new@example.com",
            "password": "newpass123",
        })
        assert resp.status_code == 200
        data = resp.json()
        assert "token" in data
        assert data["username"] == "newuser"

    def test_register_duplicate_username(self, client):
        resp = client.post("/api/register", json={
            "username": "testuser",
            "email": "another@example.com",
            "password": "pass",
        })
        assert resp.status_code == 409

    def test_login_success(self, client):
        resp = client.post("/api/login", json={"username": "testuser", "password": "testpass"})
        assert resp.status_code == 200
        assert "token" in resp.json()

    def test_login_wrong_password(self, client):
        resp = client.post("/api/login", json={"username": "testuser", "password": "wrong"})
        assert resp.status_code == 401

    def test_login_nonexistent_user(self, client):
        resp = client.post("/api/login", json={"username": "nobody", "password": "pass"})
        assert resp.status_code == 401


# ---------------------------------------------------------------------------
# Device endpoint tests
# ---------------------------------------------------------------------------

class TestDeviceEndpoints:
    def test_create_device(self, client, auth_headers):
        resp = client.post("/api/devices", json={
            "name": "My iPhone",
            "identifier": "iphone-001",
        }, headers=auth_headers)
        assert resp.status_code == 201
        data = resp.json()
        assert data["name"] == "My iPhone"
        assert data["id"] > 0

    def test_list_devices(self, client, auth_headers):
        client.post("/api/devices", json={"name": "D1", "identifier": "d1"}, headers=auth_headers)
        client.post("/api/devices", json={"name": "D2", "identifier": "d2"}, headers=auth_headers)
        resp = client.get("/api/devices", headers=auth_headers)
        assert resp.status_code == 200
        assert len(resp.json()) == 2

    def test_delete_device(self, client, auth_headers):
        create_resp = client.post("/api/devices", json={"name": "D1", "identifier": "del-d1"}, headers=auth_headers)
        device_id = create_resp.json()["id"]
        del_resp = client.delete(f"/api/devices/{device_id}", headers=auth_headers)
        assert del_resp.status_code == 204

    def test_delete_nonexistent_device(self, client, auth_headers):
        resp = client.delete("/api/devices/9999", headers=auth_headers)
        assert resp.status_code == 404

    def test_unauthenticated_access(self, client):
        resp = client.get("/api/devices")
        assert resp.status_code == 422  # missing header


# ---------------------------------------------------------------------------
# Location upload tests
# ---------------------------------------------------------------------------

class TestLocationEndpoints:
    def _create_device(self, client, auth_headers):
        resp = client.post("/api/devices", json={"name": "Test", "identifier": "loc-test"}, headers=auth_headers)
        return resp.json()["id"]

    @patch("api.process_device_locations", return_value=[])
    def test_upload_batch(self, mock_proc, client, auth_headers):
        device_id = self._create_device(client, auth_headers)
        locations = [
            {
                "latitude": pt["latitude"],
                "longitude": pt["longitude"],
                "altitude": pt.get("altitude"),
                "horizontal_accuracy": pt.get("horizontal_accuracy"),
                "speed": pt.get("speed"),
                "timestamp": pt["timestamp"].isoformat(),
            }
            for pt in HOME_SEGMENT
        ]
        resp = client.post("/api/locations", json={
            "device_id": device_id,
            "locations": locations,
        }, headers=auth_headers)
        assert resp.status_code == 200
        data = resp.json()
        assert data["received"] == len(HOME_SEGMENT)
        assert "batch_id" in data

    @patch("api.process_device_locations", return_value=[])
    def test_get_locations(self, mock_proc, client, auth_headers):
        device_id = self._create_device(client, auth_headers)
        locations = [
            {
                "latitude": pt["latitude"],
                "longitude": pt["longitude"],
                "timestamp": pt["timestamp"].isoformat(),
            }
            for pt in HOME_SEGMENT[:3]
        ]
        client.post("/api/locations", json={
            "device_id": device_id,
            "locations": locations,
        }, headers=auth_headers)

        resp = client.get(f"/api/locations/{device_id}", headers=auth_headers)
        assert resp.status_code == 200
        assert len(resp.json()) == 3


# ---------------------------------------------------------------------------
# Visit and Place endpoint tests
# ---------------------------------------------------------------------------

class TestVisitEndpoints:
    def _setup_device_with_data(self, client, auth_headers):
        resp = client.post("/api/devices", json={"name": "VP", "identifier": "vp-test"}, headers=auth_headers)
        device_id = resp.json()["id"]
        locations = [
            {
                "latitude": pt["latitude"],
                "longitude": pt["longitude"],
                "altitude": pt.get("altitude"),
                "horizontal_accuracy": pt.get("horizontal_accuracy"),
                "speed": pt.get("speed"),
                "timestamp": pt["timestamp"].isoformat(),
            }
            for pt in GPS_TRACE
        ]
        return device_id, locations

    @patch("processing.reverse_geocode", return_value="Test Address, SF")
    def test_visits_endpoint(self, mock_geocode, client, auth_headers):
        device_id, locations = self._setup_device_with_data(client, auth_headers)
        client.post("/api/locations", json={
            "device_id": device_id,
            "locations": locations,
        }, headers=auth_headers)

        resp = client.get(f"/api/visits/{device_id}", headers=auth_headers)
        assert resp.status_code == 200
        visits = resp.json()
        assert len(visits) == 3
        assert all("arrival" in v for v in visits)
        assert all("departure" in v for v in visits)
        assert all(v["duration_seconds"] >= 300 for v in visits)

    @patch("processing.reverse_geocode", return_value="Test Address, SF")
    def test_places_endpoint(self, mock_geocode, client, auth_headers):
        device_id, locations = self._setup_device_with_data(client, auth_headers)
        client.post("/api/locations", json={
            "device_id": device_id,
            "locations": locations,
        }, headers=auth_headers)

        resp = client.get("/api/places", headers=auth_headers)
        assert resp.status_code == 200
        places = resp.json()
        assert len(places) == 3
        assert all(p["visit_count"] >= 1 for p in places)

    @patch("processing.reverse_geocode", return_value="Test Address, SF")
    def test_frequent_places_endpoint(self, mock_geocode, client, auth_headers):
        device_id, locations = self._setup_device_with_data(client, auth_headers)
        client.post("/api/locations", json={"device_id": device_id, "locations": locations}, headers=auth_headers)

        resp = client.post(f"/api/visits/{device_id}/reprocess", headers=auth_headers)
        assert resp.status_code == 200

        resp = client.get("/api/places/frequent", headers=auth_headers)
        assert resp.status_code == 200
        assert isinstance(resp.json(), list)

    def test_visits_wrong_device(self, client, auth_headers):
        resp = client.get("/api/visits/9999", headers=auth_headers)
        assert resp.status_code == 404
