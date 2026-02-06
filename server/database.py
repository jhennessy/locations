"""Database setup and session management using SQLAlchemy + SQLite."""

import logging
import os

logger = logging.getLogger(__name__)

from sqlalchemy import create_engine, inspect, text
from sqlalchemy.orm import declarative_base, sessionmaker

DATABASE_URL = os.environ.get("DATABASE_URL", "sqlite:///locations.db")

engine = create_engine(DATABASE_URL, connect_args={"check_same_thread": False})
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
Base = declarative_base()


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def init_db():
    """Create all tables, run migrations, and seed the default admin user."""
    from models import User, Device, Location, Place, Visit, Config, ReprocessingJob  # noqa: F401

    logger.info("Initializing database at %s", DATABASE_URL)
    Base.metadata.create_all(bind=engine)
    _migrate()
    _seed_admin()
    _seed_config()


def _migrate():
    """Add any missing columns to existing tables."""
    insp = inspect(engine)
    if "users" in insp.get_table_names():
        columns = {c["name"] for c in insp.get_columns("users")}
        if "is_admin" not in columns:
            logger.info("Migrating: adding is_admin column to users table")
            with engine.begin() as conn:
                conn.execute(text("ALTER TABLE users ADD COLUMN is_admin BOOLEAN DEFAULT 0"))

    if "locations" in insp.get_table_names():
        columns = {c["name"] for c in insp.get_columns("locations")}
        if "notes" not in columns:
            logger.info("Migrating: adding notes column to locations table")
            with engine.begin() as conn:
                conn.execute(text("ALTER TABLE locations ADD COLUMN notes TEXT"))


def _seed_admin():
    """Create the default admin user if it doesn't exist."""
    from auth import hash_password
    from models import User

    db = SessionLocal()
    try:
        if not db.query(User).filter(User.username == "admin").first():
            admin = User(
                username="admin",
                email="admin@localhost",
                password_hash=hash_password("admin"),
                is_admin=True,
            )
            db.add(admin)
            db.commit()
            logger.info("Default admin user created")
    finally:
        db.close()


# Default algorithm thresholds (must match processing.py module-level constants)
DEFAULT_THRESHOLDS = {
    "max_horizontal_accuracy_m": "100.0",
    "max_speed_ms": "85.0",
    "min_point_interval_s": "2",
    "visit_radius_m": "50.0",
    "min_visit_duration_s": "300",
    "place_snap_radius_m": "80.0",
    "visit_merge_gap_s": "180",
}


DEFAULT_SETTINGS = {
    "timezone": "Europe/Dublin",
}


def _seed_config():
    """Insert default algorithm thresholds and settings if not present."""
    from models import Config

    db = SessionLocal()
    try:
        for key, value in {**DEFAULT_THRESHOLDS, **DEFAULT_SETTINGS}.items():
            if not db.query(Config).filter(Config.key == key).first():
                db.add(Config(key=key, value=value))
        db.commit()
    finally:
        db.close()
