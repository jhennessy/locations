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
    from models import User, Device, Location, Place, Visit  # noqa: F401

    logger.info("Initializing database at %s", DATABASE_URL)
    Base.metadata.create_all(bind=engine)
    _migrate()
    _seed_admin()


def _migrate():
    """Add any missing columns to existing tables."""
    insp = inspect(engine)
    if "users" in insp.get_table_names():
        columns = {c["name"] for c in insp.get_columns("users")}
        if "is_admin" not in columns:
            logger.info("Migrating: adding is_admin column to users table")
            with engine.begin() as conn:
                conn.execute(text("ALTER TABLE users ADD COLUMN is_admin BOOLEAN DEFAULT 0"))


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
