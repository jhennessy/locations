"""NiceGUI web pages: login, registration, device management, location map."""

import datetime
from nicegui import ui, app
from sqlalchemy.orm import Session

from auth import create_token, hash_password, verify_password, decode_token
from database import SessionLocal
from models import User, Device, Location


def get_session_user() -> tuple[Session, User | None]:
    """Return a DB session and the currently logged-in user (or None)."""
    db = SessionLocal()
    token = app.storage.user.get("token")
    if not token:
        return db, None
    payload = decode_token(token)
    if payload is None:
        return db, None
    user = db.query(User).filter(User.id == payload["sub"]).first()
    return db, user


def require_login():
    """Redirect to /login if not authenticated."""
    _, user = get_session_user()
    if user is None:
        ui.navigate.to("/login")


# ---------------------------------------------------------------------------
# Login page
# ---------------------------------------------------------------------------
@ui.page("/login")
def login_page():
    def do_login():
        db = SessionLocal()
        user = db.query(User).filter(User.username == username.value).first()
        if user and verify_password(password.value, user.password_hash):
            token = create_token(user.id, user.username)
            app.storage.user["token"] = token
            app.storage.user["username"] = user.username
            ui.navigate.to("/")
        else:
            ui.notify("Invalid username or password", type="negative")
        db.close()

    with ui.column().classes("absolute-center items-center"):
        ui.label("Location Tracker").classes("text-h4 q-mb-md")
        ui.label("Sign in to your account").classes("text-subtitle1 q-mb-lg")
        with ui.card().classes("w-80"):
            username = ui.input("Username").classes("w-full")
            password = ui.input("Password", password=True, password_toggle_button=True).classes("w-full")
            ui.button("Login", on_click=do_login).classes("w-full q-mt-md")
            with ui.row().classes("w-full justify-center q-mt-sm"):
                ui.label("No account?")
                ui.link("Register", "/register")


# ---------------------------------------------------------------------------
# Registration page
# ---------------------------------------------------------------------------
@ui.page("/register")
def register_page():
    def do_register():
        if not username.value or not email.value or not password.value:
            ui.notify("All fields are required", type="warning")
            return
        if password.value != confirm.value:
            ui.notify("Passwords do not match", type="warning")
            return
        db = SessionLocal()
        existing = db.query(User).filter(
            (User.username == username.value) | (User.email == email.value)
        ).first()
        if existing:
            ui.notify("Username or email already taken", type="negative")
            db.close()
            return
        user = User(
            username=username.value,
            email=email.value,
            password_hash=hash_password(password.value),
        )
        db.add(user)
        db.commit()
        db.refresh(user)
        token = create_token(user.id, user.username)
        app.storage.user["token"] = token
        app.storage.user["username"] = user.username
        db.close()
        ui.navigate.to("/")

    with ui.column().classes("absolute-center items-center"):
        ui.label("Create Account").classes("text-h4 q-mb-md")
        with ui.card().classes("w-80"):
            username = ui.input("Username").classes("w-full")
            email = ui.input("Email").classes("w-full")
            password = ui.input("Password", password=True, password_toggle_button=True).classes("w-full")
            confirm = ui.input("Confirm Password", password=True, password_toggle_button=True).classes("w-full")
            ui.button("Register", on_click=do_register).classes("w-full q-mt-md")
            with ui.row().classes("w-full justify-center q-mt-sm"):
                ui.label("Already have an account?")
                ui.link("Login", "/login")


# ---------------------------------------------------------------------------
# Dashboard (home)
# ---------------------------------------------------------------------------
@ui.page("/")
def dashboard_page():
    db, user = get_session_user()
    if user is None:
        ui.navigate.to("/login")
        return

    def logout():
        app.storage.user.clear()
        ui.navigate.to("/login")

    # Header
    with ui.header().classes("items-center justify-between"):
        ui.label("Location Tracker").classes("text-h6")
        with ui.row().classes("items-center"):
            ui.label(f"Logged in as {user.username}")
            ui.button("Logout", on_click=logout).props("flat color=white")

    # Nav
    with ui.left_drawer().classes("bg-blue-1"):
        ui.label("Navigation").classes("text-h6 q-pa-sm")
        ui.link("Dashboard", "/").classes("q-pa-sm")
        ui.link("Devices", "/devices").classes("q-pa-sm")
        ui.link("Map", "/map").classes("q-pa-sm")

    # Stats
    device_count = db.query(Device).filter(Device.user_id == user.id).count()
    location_count = (
        db.query(Location)
        .join(Device)
        .filter(Device.user_id == user.id)
        .count()
    )

    with ui.column().classes("q-pa-md w-full"):
        ui.label("Dashboard").classes("text-h5 q-mb-md")
        with ui.row().classes("q-gutter-md"):
            with ui.card().classes("w-48"):
                ui.label("Devices").classes("text-subtitle2 text-grey")
                ui.label(str(device_count)).classes("text-h4")
            with ui.card().classes("w-48"):
                ui.label("Location Points").classes("text-subtitle2 text-grey")
                ui.label(str(location_count)).classes("text-h4")

        # Recent locations
        ui.label("Recent Activity").classes("text-h6 q-mt-lg q-mb-sm")
        recent = (
            db.query(Location)
            .join(Device)
            .filter(Device.user_id == user.id)
            .order_by(Location.received_at.desc())
            .limit(10)
            .all()
        )
        if recent:
            rows = [
                {
                    "device": loc.device.name,
                    "lat": f"{loc.latitude:.6f}",
                    "lon": f"{loc.longitude:.6f}",
                    "time": loc.timestamp.strftime("%Y-%m-%d %H:%M:%S"),
                    "received": loc.received_at.strftime("%Y-%m-%d %H:%M:%S"),
                }
                for loc in recent
            ]
            columns = [
                {"name": "device", "label": "Device", "field": "device"},
                {"name": "lat", "label": "Latitude", "field": "lat"},
                {"name": "lon", "label": "Longitude", "field": "lon"},
                {"name": "time", "label": "Device Time", "field": "time"},
                {"name": "received", "label": "Received", "field": "received"},
            ]
            ui.table(columns=columns, rows=rows).classes("w-full")
        else:
            ui.label("No location data yet. Connect a device to start tracking.").classes("text-grey")

    db.close()


# ---------------------------------------------------------------------------
# Device management page
# ---------------------------------------------------------------------------
@ui.page("/devices")
def devices_page():
    db, user = get_session_user()
    if user is None:
        ui.navigate.to("/login")
        return

    def logout():
        app.storage.user.clear()
        ui.navigate.to("/login")

    with ui.header().classes("items-center justify-between"):
        ui.label("Location Tracker").classes("text-h6")
        with ui.row().classes("items-center"):
            ui.label(f"Logged in as {user.username}")
            ui.button("Logout", on_click=logout).props("flat color=white")

    with ui.left_drawer().classes("bg-blue-1"):
        ui.label("Navigation").classes("text-h6 q-pa-sm")
        ui.link("Dashboard", "/").classes("q-pa-sm")
        ui.link("Devices", "/devices").classes("q-pa-sm")
        ui.link("Map", "/map").classes("q-pa-sm")

    with ui.column().classes("q-pa-md w-full"):
        ui.label("Device Management").classes("text-h5 q-mb-md")

        # Add device form
        with ui.card().classes("q-mb-lg w-96"):
            ui.label("Register New Device").classes("text-h6 q-mb-sm")
            device_name = ui.input("Device Name (e.g. John's iPhone)").classes("w-full")
            device_id = ui.input("Device Identifier (unique)").classes("w-full")

            def add_device():
                if not device_name.value or not device_id.value:
                    ui.notify("Both fields are required", type="warning")
                    return
                inner_db = SessionLocal()
                existing = inner_db.query(Device).filter(Device.identifier == device_id.value).first()
                if existing:
                    ui.notify("Device identifier already registered", type="negative")
                    inner_db.close()
                    return
                device = Device(name=device_name.value, identifier=device_id.value, user_id=user.id)
                inner_db.add(device)
                inner_db.commit()
                inner_db.close()
                ui.navigate.to("/devices")

            ui.button("Add Device", on_click=add_device).classes("q-mt-sm")

        # List devices
        ui.label("Your Devices").classes("text-h6 q-mb-sm")
        devices = db.query(Device).filter(Device.user_id == user.id).all()
        if devices:
            for d in devices:
                loc_count = db.query(Location).filter(Location.device_id == d.id).count()
                with ui.card().classes("w-full q-mb-sm"):
                    with ui.row().classes("items-center justify-between w-full"):
                        with ui.column():
                            ui.label(d.name).classes("text-subtitle1 text-bold")
                            ui.label(f"ID: {d.identifier}").classes("text-caption text-grey")
                            ui.label(f"{loc_count} location points").classes("text-caption")
                            if d.last_seen:
                                ui.label(f"Last seen: {d.last_seen.strftime('%Y-%m-%d %H:%M')}").classes(
                                    "text-caption text-grey"
                                )

                        def make_delete(device_id):
                            def delete():
                                inner_db = SessionLocal()
                                dev = inner_db.query(Device).filter(Device.id == device_id).first()
                                if dev:
                                    inner_db.delete(dev)
                                    inner_db.commit()
                                inner_db.close()
                                ui.navigate.to("/devices")
                            return delete

                        ui.button("Delete", on_click=make_delete(d.id)).props("flat color=red")
        else:
            ui.label("No devices registered yet.").classes("text-grey")

    db.close()


# ---------------------------------------------------------------------------
# Map page
# ---------------------------------------------------------------------------
@ui.page("/map")
def map_page():
    db, user = get_session_user()
    if user is None:
        ui.navigate.to("/login")
        return

    def logout():
        app.storage.user.clear()
        ui.navigate.to("/login")

    with ui.header().classes("items-center justify-between"):
        ui.label("Location Tracker").classes("text-h6")
        with ui.row().classes("items-center"):
            ui.label(f"Logged in as {user.username}")
            ui.button("Logout", on_click=logout).props("flat color=white")

    with ui.left_drawer().classes("bg-blue-1"):
        ui.label("Navigation").classes("text-h6 q-pa-sm")
        ui.link("Dashboard", "/").classes("q-pa-sm")
        ui.link("Devices", "/devices").classes("q-pa-sm")
        ui.link("Map", "/map").classes("q-pa-sm")

    devices = db.query(Device).filter(Device.user_id == user.id).all()

    with ui.column().classes("q-pa-md w-full"):
        ui.label("Location Map").classes("text-h5 q-mb-md")

        if not devices:
            ui.label("Register a device first to see locations.").classes("text-grey")
            db.close()
            return

        device_options = {d.id: d.name for d in devices}
        selected_device = ui.select(
            options=device_options,
            label="Select Device",
            value=devices[0].id,
        ).classes("w-64 q-mb-md")

        map_container = ui.column().classes("w-full")

        def render_map():
            map_container.clear()
            inner_db = SessionLocal()
            locations = (
                inner_db.query(Location)
                .filter(Location.device_id == selected_device.value)
                .order_by(Location.timestamp.desc())
                .limit(500)
                .all()
            )

            if not locations:
                with map_container:
                    ui.label("No location data for this device.").classes("text-grey")
                inner_db.close()
                return

            center_lat = locations[0].latitude
            center_lon = locations[0].longitude

            with map_container:
                m = ui.leaflet(center=(center_lat, center_lon), zoom=13).classes("w-full").style("height: 500px")
                for loc in locations:
                    m.marker(latlng=(loc.latitude, loc.longitude))

                # Location history table
                ui.label(f"Showing last {len(locations)} points").classes("text-caption q-mt-sm")
                rows = [
                    {
                        "lat": f"{loc.latitude:.6f}",
                        "lon": f"{loc.longitude:.6f}",
                        "alt": f"{loc.altitude:.1f}" if loc.altitude else "-",
                        "speed": f"{loc.speed:.1f}" if loc.speed else "-",
                        "time": loc.timestamp.strftime("%Y-%m-%d %H:%M:%S"),
                    }
                    for loc in locations[:50]
                ]
                columns = [
                    {"name": "lat", "label": "Latitude", "field": "lat"},
                    {"name": "lon", "label": "Longitude", "field": "lon"},
                    {"name": "alt", "label": "Altitude", "field": "alt"},
                    {"name": "speed", "label": "Speed", "field": "speed"},
                    {"name": "time", "label": "Time", "field": "time"},
                ]
                ui.table(columns=columns, rows=rows).classes("w-full q-mt-md")

            inner_db.close()

        selected_device.on_value_change(lambda _: render_map())
        render_map()

    db.close()
