"""Database setup and session management using SQLAlchemy + SQLite."""

from sqlalchemy import create_engine
from sqlalchemy.orm import declarative_base, sessionmaker

DATABASE_URL = "sqlite:///locations.db"

engine = create_engine(DATABASE_URL, connect_args={"check_same_thread": False})
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
Base = declarative_base()


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


DEFAULT_THRESHOLDS = {
    "position_ttl_seconds": "300",
}


def init_db():
    """Create all tables."""
    from models import User, Device, Location, Place, Visit, Config, Session, CurrentPosition  # noqa: F401

    Base.metadata.create_all(bind=engine)
