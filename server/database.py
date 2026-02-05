"""Database setup and session management using SQLAlchemy + SQLite."""

import os

from sqlalchemy import create_engine
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
    """Create all tables and seed the default admin user."""
    from models import User, Device, Location, Place, Visit  # noqa: F401

    Base.metadata.create_all(bind=engine)
    _seed_admin()


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
    finally:
        db.close()
