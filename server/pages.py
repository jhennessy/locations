"""NiceGUI web pages: login, registration, devices, map, visits, frequent places."""

import datetime
import os
from nicegui import ui, app
from sqlalchemy import func
from sqlalchemy.orm import Session

from auth import create_token, hash_password, verify_password, decode_token
from database import SessionLocal, DEFAULT_THRESHOLDS
from models import User, Device, Location, Visit, Place, Config, ReprocessingJob
from processing import reprocess_all


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


def _nav_link(icon: str, label: str, href: str):
    """Render a navigation link with an icon."""
    with ui.element("a").props(f'href="{href}"').classes(
        "flex items-center gap-3 q-pa-sm q-pl-md no-underline text-dark"
        " rounded-borders cursor-pointer hover:bg-blue-2"
    ).style("text-decoration: none; transition: background 0.15s"):
        ui.icon(icon).classes("text-blue-8")
        ui.label(label)


def _nav_drawer(user=None):
    """Shared left-drawer navigation."""
    with ui.left_drawer().classes("bg-blue-1"):
        ui.label("Location Tracker").classes("text-h6 q-pa-sm q-mb-sm")
        _nav_link("dashboard", "Dashboard", "/")
        _nav_link("phone_iphone", "Devices", "/devices")
        _nav_link("map", "Map", "/map")
        _nav_link("place", "Visits", "/visits")
        _nav_link("star", "Frequent Places", "/places")
        ui.separator().classes("q-my-sm")
        _nav_link("settings", "Settings", "/settings")
        if user and user.is_admin:
            _nav_link("admin_panel_settings", "Admin", "/admin")
            _nav_link("article", "Logs", "/logs")


def _header(user):
    """Shared header with logout."""
    def logout():
        app.storage.user.clear()
        ui.navigate.to("/login")

    with ui.header().classes("items-center justify-between"):
        ui.label("Location Tracker").classes("text-h6")
        with ui.row().classes("items-center"):
            ui.label(f"Logged in as {user.username}")
            ui.button("Logout", on_click=logout).props("flat color=white")


def _format_duration(seconds: int) -> str:
    """Format seconds into human-readable duration."""
    if seconds < 60:
        return f"{seconds}s"
    minutes = seconds // 60
    if minutes < 60:
        return f"{minutes}m"
    hours = minutes // 60
    remaining_min = minutes % 60
    if hours < 24:
        return f"{hours}h {remaining_min}m"
    days = hours // 24
    remaining_hrs = hours % 24
    return f"{days}d {remaining_hrs}h"


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

    _header(user)
    _nav_drawer(user)

    device_count = db.query(Device).filter(Device.user_id == user.id).count()
    location_count = (
        db.query(Location).join(Device).filter(Device.user_id == user.id).count()
    )
    visit_count = (
        db.query(Visit).join(Device).filter(Device.user_id == user.id).count()
    )
    place_count = db.query(Place).filter(Place.user_id == user.id).count()

    with ui.column().classes("q-pa-md w-full"):
        ui.label("Dashboard").classes("text-h5 q-mb-md")
        with ui.row().classes("q-gutter-md"):
            with ui.card().classes("w-48"):
                ui.label("Devices").classes("text-subtitle2 text-grey")
                ui.label(str(device_count)).classes("text-h4")
            with ui.card().classes("w-48"):
                ui.label("Location Points").classes("text-subtitle2 text-grey")
                ui.label(str(location_count)).classes("text-h4")
            with ui.card().classes("w-48"):
                ui.label("Visits").classes("text-subtitle2 text-grey")
                ui.label(str(visit_count)).classes("text-h4")
            with ui.card().classes("w-48"):
                ui.label("Known Places").classes("text-subtitle2 text-grey")
                ui.label(str(place_count)).classes("text-h4")

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
                    "notes": loc.notes or "",
                }
                for loc in recent
            ]
            columns = [
                {"name": "device", "label": "Device", "field": "device"},
                {"name": "lat", "label": "Latitude", "field": "lat"},
                {"name": "lon", "label": "Longitude", "field": "lon"},
                {"name": "time", "label": "Device Time", "field": "time"},
                {"name": "received", "label": "Received", "field": "received"},
                {"name": "notes", "label": "Notes", "field": "notes", "align": "left"},
            ]
            ui.table(columns=columns, rows=rows).classes("w-full")
        else:
            ui.label("No location data yet. Connect a device to start tracking.").classes("text-grey")

        commit_sha = os.environ.get("COMMIT_SHA", "")[:8]
        if commit_sha:
            ui.label(f"Build: {commit_sha}").classes("text-caption text-grey q-mt-lg")

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

    _header(user)
    _nav_drawer(user)

    with ui.column().classes("q-pa-md w-full"):
        ui.label("Device Management").classes("text-h5 q-mb-md")

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

        ui.label("Your Devices").classes("text-h6 q-mb-sm")
        devices = db.query(Device).filter(Device.user_id == user.id).all()
        if devices:
            for d in devices:
                loc_count = db.query(Location).filter(Location.device_id == d.id).count()
                visit_count = db.query(Visit).filter(Visit.device_id == d.id).count()
                with ui.card().classes("w-full q-mb-sm"):
                    with ui.row().classes("items-center justify-between w-full"):
                        with ui.column():
                            ui.label(d.name).classes("text-subtitle1 text-bold")
                            ui.label(f"ID: {d.identifier}").classes("text-caption text-grey")
                            ui.label(f"{loc_count} points | {visit_count} visits").classes("text-caption")
                            if d.last_seen:
                                ui.label(f"Last seen: {d.last_seen.strftime('%Y-%m-%d %H:%M')}").classes(
                                    "text-caption text-grey"
                                )

                        def make_delete(did):
                            def delete():
                                inner_db = SessionLocal()
                                dev = inner_db.query(Device).filter(Device.id == did).first()
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
# Map page — shows current/latest location per device
# ---------------------------------------------------------------------------
@ui.page("/map")
def map_page():
    db, user = get_session_user()
    if user is None:
        ui.navigate.to("/login")
        return

    _header(user)
    _nav_drawer(user)

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

            # Latest location is the "current" position
            latest = locations[0]
            center_lat = latest.latitude
            center_lon = latest.longitude

            with map_container:
                # Current location highlight
                with ui.card().classes("w-full q-mb-sm bg-blue-1"):
                    with ui.row().classes("items-center q-gutter-sm"):
                        ui.icon("my_location", color="blue")
                        ui.label("Current Location").classes("text-bold")
                        ui.label(
                            f"{latest.latitude:.6f}, {latest.longitude:.6f}"
                        ).classes("text-caption")
                        ui.label(
                            f"({latest.timestamp.strftime('%Y-%m-%d %H:%M:%S')})"
                        ).classes("text-caption text-grey")

                m = ui.leaflet(center=(center_lat, center_lon), zoom=14).classes("w-full").style("height: 500px")

                # Latest location — larger blue marker
                m.marker(latlng=(center_lat, center_lon))

                # Trail of older locations
                for loc in locations[1:]:
                    m.marker(latlng=(loc.latitude, loc.longitude))

                # Draw polyline for the path
                if len(locations) >= 2:
                    path_points = [(loc.latitude, loc.longitude) for loc in reversed(locations)]
                    m.generic_layer(
                        name="polyline",
                        args=[path_points, {"color": "#4285F4", "weight": 3, "opacity": 0.7}],
                    )

                ui.label(f"Showing last {len(locations)} points").classes("text-caption q-mt-sm")
                rows = [
                    {
                        "lat": f"{loc.latitude:.6f}",
                        "lon": f"{loc.longitude:.6f}",
                        "alt": f"{loc.altitude:.1f}" if loc.altitude else "-",
                        "speed": f"{loc.speed:.1f}" if loc.speed else "-",
                        "acc": f"{loc.horizontal_accuracy:.0f}m" if loc.horizontal_accuracy else "-",
                        "time": loc.timestamp.strftime("%Y-%m-%d %H:%M:%S"),
                    }
                    for loc in locations[:50]
                ]
                columns = [
                    {"name": "lat", "label": "Latitude", "field": "lat"},
                    {"name": "lon", "label": "Longitude", "field": "lon"},
                    {"name": "alt", "label": "Altitude", "field": "alt"},
                    {"name": "speed", "label": "Speed", "field": "speed"},
                    {"name": "acc", "label": "Accuracy", "field": "acc"},
                    {"name": "time", "label": "Time", "field": "time"},
                ]
                ui.table(columns=columns, rows=rows).classes("w-full q-mt-md")

            inner_db.close()

        selected_device.on_value_change(lambda _: render_map())
        render_map()

    db.close()


# ---------------------------------------------------------------------------
# Visits page
# ---------------------------------------------------------------------------
@ui.page("/visits")
def visits_page():
    db, user = get_session_user()
    if user is None:
        ui.navigate.to("/login")
        return

    _header(user)
    _nav_drawer(user)

    devices = db.query(Device).filter(Device.user_id == user.id).all()

    with ui.column().classes("q-pa-md w-full"):
        ui.label("Visits").classes("text-h5 q-mb-md")
        ui.label(
            "Places where you stayed for at least 5 minutes, detected automatically from GPS data."
        ).classes("text-caption text-grey q-mb-md")

        if not devices:
            ui.label("Register a device first.").classes("text-grey")
            db.close()
            return

        device_options = {d.id: d.name for d in devices}
        selected_device = ui.select(
            options=device_options,
            label="Select Device",
            value=devices[0].id,
        ).classes("w-64 q-mb-md")

        content = ui.column().classes("w-full")

        def render_visits():
            content.clear()
            inner_db = SessionLocal()
            visits = (
                inner_db.query(Visit)
                .filter(Visit.device_id == selected_device.value)
                .order_by(Visit.arrival.desc())
                .limit(200)
                .all()
            )

            with content:
                if not visits:
                    ui.label("No visits detected yet. Upload more location data.").classes("text-grey")
                    inner_db.close()
                    return

                # Map showing visit locations
                center = visits[0]
                m = ui.leaflet(center=(center.latitude, center.longitude), zoom=13).classes("w-full").style(
                    "height: 400px"
                )
                for v in visits:
                    m.marker(latlng=(v.latitude, v.longitude))

                # Visit table
                rows = [
                    {
                        "address": v.address or f"{v.latitude:.5f}, {v.longitude:.5f}",
                        "arrival": v.arrival.strftime("%Y-%m-%d %H:%M"),
                        "departure": v.departure.strftime("%H:%M"),
                        "duration": _format_duration(v.duration_seconds),
                        "place_id": v.place_id,
                    }
                    for v in visits
                ]
                columns = [
                    {"name": "address", "label": "Location", "field": "address", "align": "left"},
                    {"name": "arrival", "label": "Arrived", "field": "arrival"},
                    {"name": "departure", "label": "Left", "field": "departure"},
                    {"name": "duration", "label": "Duration", "field": "duration"},
                ]
                ui.table(columns=columns, rows=rows).classes("w-full q-mt-md")

            inner_db.close()

        selected_device.on_value_change(lambda _: render_visits())
        render_visits()

    db.close()


# ---------------------------------------------------------------------------
# Frequent Places page
# ---------------------------------------------------------------------------
@ui.page("/places")
def places_page():
    db, user = get_session_user()
    if user is None:
        ui.navigate.to("/login")
        return

    _header(user)
    _nav_drawer(user)

    with ui.column().classes("q-pa-md w-full"):
        ui.label("Frequent Places").classes("text-h5 q-mb-md")
        ui.label(
            "Locations you visit repeatedly, ranked by number of visits."
        ).classes("text-caption text-grey q-mb-md")

        places = (
            db.query(Place)
            .filter(Place.user_id == user.id)
            .order_by(Place.visit_count.desc())
            .all()
        )

        if not places:
            ui.label("No places detected yet. Visit detection runs automatically when locations are uploaded.").classes(
                "text-grey"
            )
            db.close()
            return

        # Map with all known places
        m = ui.leaflet(center=(places[0].latitude, places[0].longitude), zoom=12).classes("w-full").style(
            "height: 400px"
        )
        for p in places:
            m.marker(latlng=(p.latitude, p.longitude))

        # Table
        rows = [
            {
                "name": p.name or "-",
                "address": p.address or f"{p.latitude:.5f}, {p.longitude:.5f}",
                "visits": p.visit_count,
                "total_time": _format_duration(p.total_duration_seconds),
                "avg_time": _format_duration(p.total_duration_seconds // p.visit_count) if p.visit_count else "-",
                "pid": p.id,
            }
            for p in places
        ]
        columns = [
            {"name": "name", "label": "Name", "field": "name", "align": "left"},
            {"name": "address", "label": "Address", "field": "address", "align": "left"},
            {"name": "visits", "label": "Visits", "field": "visits"},
            {"name": "total_time", "label": "Total Time", "field": "total_time"},
            {"name": "avg_time", "label": "Avg Duration", "field": "avg_time"},
        ]
        ui.table(columns=columns, rows=rows).classes("w-full q-mt-md")

        # Inline rename
        ui.label("Rename a place").classes("text-h6 q-mt-lg q-mb-sm")
        place_options = {p.id: (p.name or p.address or f"Place #{p.id}") for p in places}
        sel_place = ui.select(options=place_options, label="Select Place").classes("w-64")
        new_name = ui.input("New Name").classes("w-64")

        def rename_place():
            if not sel_place.value or not new_name.value:
                ui.notify("Select a place and enter a name", type="warning")
                return
            inner_db = SessionLocal()
            place = inner_db.query(Place).filter(Place.id == sel_place.value).first()
            if place:
                place.name = new_name.value
                inner_db.commit()
                ui.notify(f"Renamed to '{new_name.value}'", type="positive")
            inner_db.close()
            ui.navigate.to("/places")

        ui.button("Rename", on_click=rename_place).classes("q-mt-sm")

    db.close()


# ---------------------------------------------------------------------------
# Settings page — change password
# ---------------------------------------------------------------------------
@ui.page("/settings")
def settings_page():
    db, user = get_session_user()
    if user is None:
        ui.navigate.to("/login")
        return

    _header(user)
    _nav_drawer(user)

    with ui.column().classes("q-pa-md w-full"):
        ui.label("Settings").classes("text-h5 q-mb-md")

        with ui.card().classes("w-96"):
            ui.label("Change Password").classes("text-h6 q-mb-sm")
            current_pw = ui.input("Current Password", password=True, password_toggle_button=True).classes("w-full")
            new_pw = ui.input("New Password", password=True, password_toggle_button=True).classes("w-full")
            confirm_pw = ui.input("Confirm New Password", password=True, password_toggle_button=True).classes("w-full")

            def do_change_password():
                if not current_pw.value or not new_pw.value:
                    ui.notify("All fields are required", type="warning")
                    return
                if new_pw.value != confirm_pw.value:
                    ui.notify("New passwords do not match", type="warning")
                    return
                inner_db = SessionLocal()
                u = inner_db.query(User).filter(User.id == user.id).first()
                if not verify_password(current_pw.value, u.password_hash):
                    ui.notify("Current password is incorrect", type="negative")
                    inner_db.close()
                    return
                u.password_hash = hash_password(new_pw.value)
                inner_db.commit()
                inner_db.close()
                current_pw.value = ""
                new_pw.value = ""
                confirm_pw.value = ""
                ui.notify("Password changed successfully", type="positive")

            ui.button("Change Password", on_click=do_change_password).classes("q-mt-md")

    db.close()


# ---------------------------------------------------------------------------
# Admin page — user management + algorithm tuning (admin only)
# ---------------------------------------------------------------------------

# Threshold labels for the admin UI
_THRESHOLD_LABELS = {
    "max_horizontal_accuracy_m": ("Max GPS Accuracy (m)", "Discard points with accuracy worse than this"),
    "max_speed_ms": ("Max Speed (m/s)", "Filter out physically impossible speeds (~306 km/h = 85)"),
    "min_point_interval_s": ("Min Point Interval (s)", "Deduplicate points closer than this in time"),
    "visit_radius_m": ("Visit Cluster Radius (m)", "Max radius for grouping stationary points"),
    "min_visit_duration_s": ("Min Visit Duration (s)", "Minimum seconds to count as a visit (300 = 5 min)"),
    "place_snap_radius_m": ("Place Snap Radius (m)", "Snap visit to existing place if within this distance"),
    "visit_merge_gap_s": ("Visit Merge Gap (s)", "Merge consecutive visits at same place if gap is shorter (180 = 3 min)"),
}


@ui.page("/admin")
def admin_page():
    db, user = get_session_user()
    if user is None:
        ui.navigate.to("/login")
        return
    if not user.is_admin:
        ui.navigate.to("/")
        return

    _header(user)
    _nav_drawer(user)

    with ui.column().classes("q-pa-md w-full"):
        ui.label("Administration").classes("text-h5 q-mb-md")

        with ui.tabs().classes("w-full") as tabs:
            users_tab = ui.tab("Users", icon="people")
            algo_tab = ui.tab("Algorithm", icon="tune")

        with ui.tab_panels(tabs, value=users_tab).classes("w-full"):
            # ------ Users tab ------
            with ui.tab_panel(users_tab):
                _render_users_tab(user)

            # ------ Algorithm tab ------
            with ui.tab_panel(algo_tab):
                _render_algorithm_tab(user)

    db.close()


def _render_users_tab(current_user):
    """Users management tab content."""
    users_container = ui.column().classes("w-full")

    def render_users():
        users_container.clear()
        inner_db = SessionLocal()
        all_users = inner_db.query(User).order_by(User.id).all()

        with users_container:
            for u in all_users:
                with ui.card().classes("w-full q-mb-sm"):
                    with ui.row().classes("items-center justify-between w-full"):
                        with ui.column():
                            with ui.row().classes("items-center q-gutter-sm"):
                                ui.label(u.username).classes("text-subtitle1 text-bold")
                                if u.is_admin:
                                    ui.badge("admin", color="blue")
                                if not u.is_active:
                                    ui.badge("disabled", color="red")
                            ui.label(f"{u.email}").classes("text-caption text-grey")
                            ui.label(
                                f"Created: {u.created_at.strftime('%Y-%m-%d %H:%M') if u.created_at else 'N/A'}"
                            ).classes("text-caption text-grey")

                        with ui.row().classes("q-gutter-sm"):
                            def make_toggle_active(uid, currently_active):
                                def toggle():
                                    tdb = SessionLocal()
                                    target = tdb.query(User).filter(User.id == uid).first()
                                    if target:
                                        target.is_active = not currently_active
                                        tdb.commit()
                                    tdb.close()
                                    render_users()
                                return toggle

                            def make_toggle_admin(uid, currently_admin):
                                def toggle():
                                    tdb = SessionLocal()
                                    target = tdb.query(User).filter(User.id == uid).first()
                                    if target:
                                        target.is_admin = not currently_admin
                                        tdb.commit()
                                    tdb.close()
                                    render_users()
                                return toggle

                            def make_delete(uid):
                                def delete():
                                    if uid == current_user.id:
                                        ui.notify("Cannot delete yourself", type="warning")
                                        return
                                    tdb = SessionLocal()
                                    target = tdb.query(User).filter(User.id == uid).first()
                                    if target:
                                        tdb.delete(target)
                                        tdb.commit()
                                    tdb.close()
                                    render_users()
                                return delete

                            if u.id != current_user.id:
                                label = "Disable" if u.is_active else "Enable"
                                ui.button(label, on_click=make_toggle_active(u.id, u.is_active)).props("flat")
                                admin_label = "Remove Admin" if u.is_admin else "Make Admin"
                                ui.button(admin_label, on_click=make_toggle_admin(u.id, u.is_admin)).props("flat")
                                ui.button("Delete", on_click=make_delete(u.id)).props("flat color=red")

            # Reset password section
            ui.separator().classes("q-my-md")
            ui.label("Reset User Password").classes("text-h6 q-mb-sm")
            user_options = {u.id: u.username for u in all_users if u.id != current_user.id}
            if user_options:
                sel_user = ui.select(options=user_options, label="Select User").classes("w-64")
                reset_pw = ui.input("New Password", password=True, password_toggle_button=True).classes("w-64")

                def do_reset():
                    if not sel_user.value or not reset_pw.value:
                        ui.notify("Select a user and enter a password", type="warning")
                        return
                    tdb = SessionLocal()
                    target = tdb.query(User).filter(User.id == sel_user.value).first()
                    if target:
                        target.password_hash = hash_password(reset_pw.value)
                        tdb.commit()
                        ui.notify(f"Password reset for {target.username}", type="positive")
                        reset_pw.value = ""
                    tdb.close()

                ui.button("Reset Password", on_click=do_reset).classes("q-mt-sm")

        inner_db.close()

    render_users()


def _render_algorithm_tab(current_user):
    """Algorithm thresholds and data regeneration tab."""
    # --- Threshold editor ---
    ui.label("Detection Thresholds").classes("text-h6 q-mb-sm")
    ui.label(
        "These parameters control how GPS data is filtered and how visits and places are detected."
    ).classes("text-caption text-grey q-mb-md")

    inner_db = SessionLocal()
    config_rows = inner_db.query(Config).all()
    current_values = {r.key: r.value for r in config_rows}
    inner_db.close()

    inputs = {}
    with ui.card().classes("w-full q-mb-lg"):
        for key in _THRESHOLD_LABELS:
            label, hint = _THRESHOLD_LABELS[key]
            val = float(current_values.get(key, DEFAULT_THRESHOLDS[key]))
            inp = ui.number(label, value=val).classes("w-full").tooltip(hint)
            inputs[key] = inp

        with ui.row().classes("q-mt-md q-gutter-sm"):
            def save_thresholds():
                tdb = SessionLocal()
                for key, inp in inputs.items():
                    row = tdb.query(Config).filter(Config.key == key).first()
                    if row:
                        row.value = str(inp.value)
                    else:
                        tdb.add(Config(key=key, value=str(inp.value)))
                tdb.commit()
                tdb.close()
                ui.notify("Thresholds saved", type="positive")

            def reset_defaults():
                tdb = SessionLocal()
                for key, default_val in DEFAULT_THRESHOLDS.items():
                    row = tdb.query(Config).filter(Config.key == key).first()
                    if row:
                        row.value = default_val
                tdb.commit()
                tdb.close()
                for key, inp in inputs.items():
                    inp.value = float(DEFAULT_THRESHOLDS[key])
                ui.notify("Reset to defaults", type="info")

            ui.button("Save Thresholds", on_click=save_thresholds).props("color=primary")
            ui.button("Reset to Defaults", on_click=reset_defaults).props("flat")

    # --- Regeneration ---
    ui.separator().classes("q-my-md")
    ui.label("Data Regeneration").classes("text-h6 q-mb-sm")
    ui.label(
        "Delete all detected visits and places, then reprocess all location data "
        "using the current thresholds. This may take a while for large datasets."
    ).classes("text-caption text-grey q-mb-md")

    journal_container = ui.column().classes("w-full")

    def render_journal():
        journal_container.clear()
        jdb = SessionLocal()
        jobs = (
            jdb.query(ReprocessingJob)
            .filter(ReprocessingJob.user_id == current_user.id)
            .order_by(ReprocessingJob.started_at.desc())
            .limit(10)
            .all()
        )

        with journal_container:
            if jobs:
                rows = []
                for j in jobs:
                    rows.append({
                        "id": j.id,
                        "status": j.status,
                        "started": j.started_at.strftime("%Y-%m-%d %H:%M:%S") if j.started_at else "-",
                        "finished": j.finished_at.strftime("%Y-%m-%d %H:%M:%S") if j.finished_at else "-",
                        "visits": j.visits_created,
                        "places": j.places_created,
                        "error": j.error_message or "",
                    })
                columns = [
                    {"name": "status", "label": "Status", "field": "status"},
                    {"name": "started", "label": "Started", "field": "started"},
                    {"name": "finished", "label": "Finished", "field": "finished"},
                    {"name": "visits", "label": "Visits", "field": "visits"},
                    {"name": "places", "label": "Places", "field": "places"},
                    {"name": "error", "label": "Error", "field": "error"},
                ]
                ui.table(columns=columns, rows=rows).classes("w-full")
            else:
                ui.label("No regeneration jobs yet.").classes("text-grey")

        jdb.close()

    def do_regenerate():
        import datetime as dt

        jdb = SessionLocal()
        job = ReprocessingJob(
            user_id=current_user.id,
            status="running",
            started_at=dt.datetime.utcnow(),
        )
        jdb.add(job)
        jdb.commit()
        jdb.refresh(job)
        job_id = job.id
        jdb.close()

        render_journal()
        ui.notify("Regeneration started...", type="info")

        try:
            rdb = SessionLocal()
            result = reprocess_all(rdb, current_user.id)

            job = rdb.query(ReprocessingJob).filter(ReprocessingJob.id == job_id).first()
            job.status = "completed"
            job.finished_at = dt.datetime.utcnow()
            job.visits_created = result["visits_created"]
            job.places_created = result["places_created"]
            rdb.commit()
            rdb.close()

            ui.notify(
                f"Regeneration complete: {result['visits_created']} visits, "
                f"{result['places_created']} places",
                type="positive",
            )
        except Exception as e:
            rdb = SessionLocal()
            job = rdb.query(ReprocessingJob).filter(ReprocessingJob.id == job_id).first()
            if job:
                job.status = "failed"
                job.finished_at = dt.datetime.utcnow()
                job.error_message = str(e)
                rdb.commit()
            rdb.close()
            ui.notify(f"Regeneration failed: {e}", type="negative")

        render_journal()

    ui.button("Regenerate All Data", on_click=do_regenerate).props("color=negative icon=refresh")
    ui.label("").classes("q-mb-md")
    render_journal()


# ---------------------------------------------------------------------------
# Logs page — admin only
# ---------------------------------------------------------------------------
@ui.page("/logs")
def logs_page():
    db, user = get_session_user()
    if user is None:
        ui.navigate.to("/login")
        return
    if not user.is_admin:
        ui.navigate.to("/")
        return

    _header(user)
    _nav_drawer(user)

    LOG_DIR = os.environ.get("LOG_DIR", "/data" if os.path.isdir("/data") else ".")
    LOG_FILE = os.path.join(LOG_DIR, "location-tracker.log")

    with ui.column().classes("q-pa-md w-full"):
        ui.label("Server Logs").classes("text-h5 q-mb-md")

        log_area = ui.textarea("").classes("w-full font-mono").props(
            "readonly outlined autogrow"
        ).style("min-height: 500px; font-size: 12px;")

        def load_logs(tail_lines=200):
            try:
                with open(LOG_FILE, "r") as f:
                    lines = f.readlines()
                log_area.value = "".join(lines[-tail_lines:])
            except FileNotFoundError:
                log_area.value = "Log file not found."

        with ui.row().classes("q-gutter-sm q-mb-md"):
            ui.button("Refresh", on_click=lambda: load_logs()).props("icon=refresh")
            ui.button("Last 50", on_click=lambda: load_logs(50)).props("flat")
            ui.button("Last 200", on_click=lambda: load_logs(200)).props("flat")
            ui.button("Last 500", on_click=lambda: load_logs(500)).props("flat")
            ui.button("All", on_click=lambda: load_logs(100000)).props("flat")

        load_logs()

    db.close()
