"""SQLAlchemy models for users, devices, and locations."""

import datetime
from sqlalchemy import Column, Integer, Float, String, DateTime, ForeignKey, Boolean
from sqlalchemy.orm import relationship

from database import Base


class User(Base):
    __tablename__ = "users"

    id = Column(Integer, primary_key=True, index=True)
    username = Column(String, unique=True, nullable=False, index=True)
    email = Column(String, unique=True, nullable=False)
    password_hash = Column(String, nullable=False)
    created_at = Column(DateTime, default=datetime.datetime.utcnow)
    is_active = Column(Boolean, default=True)

    devices = relationship("Device", back_populates="owner", cascade="all, delete-orphan")


class Device(Base):
    __tablename__ = "devices"

    id = Column(Integer, primary_key=True, index=True)
    name = Column(String, nullable=False)
    identifier = Column(String, unique=True, nullable=False, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False)
    created_at = Column(DateTime, default=datetime.datetime.utcnow)
    last_seen = Column(DateTime, nullable=True)

    owner = relationship("User", back_populates="devices")
    locations = relationship("Location", back_populates="device", cascade="all, delete-orphan")


class Location(Base):
    __tablename__ = "locations"

    id = Column(Integer, primary_key=True, index=True)
    device_id = Column(Integer, ForeignKey("devices.id"), nullable=False)
    latitude = Column(Float, nullable=False)
    longitude = Column(Float, nullable=False)
    altitude = Column(Float, nullable=True)
    horizontal_accuracy = Column(Float, nullable=True)
    vertical_accuracy = Column(Float, nullable=True)
    speed = Column(Float, nullable=True)
    course = Column(Float, nullable=True)
    timestamp = Column(DateTime, nullable=False)
    received_at = Column(DateTime, default=datetime.datetime.utcnow)
    batch_id = Column(String, nullable=True, index=True)

    device = relationship("Device", back_populates="locations")
