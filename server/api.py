"""REST API endpoints for the iOS app (authentication, devices, location uploads)."""

import datetime
import uuid
from functools import wraps
from typing import Optional

from fastapi import APIRouter, Depends, Header, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from auth import create_token, decode_token, hash_password, verify_password
from database import get_db
from models import Device, Location, User

router = APIRouter(prefix="/api")


# ---------------------------------------------------------------------------
# Pydantic schemas
# ---------------------------------------------------------------------------

class RegisterRequest(BaseModel):
    username: str
    email: str
    password: str


class LoginRequest(BaseModel):
    username: str
    password: str


class TokenResponse(BaseModel):
    token: str
    user_id: int
    username: str


class DeviceCreate(BaseModel):
    name: str
    identifier: str


class DeviceResponse(BaseModel):
    id: int
    name: str
    identifier: str
    last_seen: Optional[str] = None

    class Config:
        from_attributes = True


class LocationPoint(BaseModel):
    latitude: float
    longitude: float
    altitude: Optional[float] = None
    horizontal_accuracy: Optional[float] = None
    vertical_accuracy: Optional[float] = None
    speed: Optional[float] = None
    course: Optional[float] = None
    timestamp: str = Field(..., description="ISO 8601 timestamp from the device")


class LocationBatch(BaseModel):
    device_id: int
    locations: list[LocationPoint]


class BatchResponse(BaseModel):
    received: int
    batch_id: str


# ---------------------------------------------------------------------------
# Auth dependency
# ---------------------------------------------------------------------------

def get_current_user(authorization: str = Header(...), db: Session = Depends(get_db)) -> User:
    if not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Invalid authorization header")
    token = authorization[7:]
    payload = decode_token(token)
    if payload is None:
        raise HTTPException(status_code=401, detail="Invalid or expired token")
    user = db.query(User).filter(User.id == payload["sub"]).first()
    if user is None:
        raise HTTPException(status_code=401, detail="User not found")
    return user


# ---------------------------------------------------------------------------
# Auth endpoints
# ---------------------------------------------------------------------------

@router.post("/register", response_model=TokenResponse)
def register(req: RegisterRequest, db: Session = Depends(get_db)):
    if db.query(User).filter((User.username == req.username) | (User.email == req.email)).first():
        raise HTTPException(status_code=409, detail="Username or email already exists")
    user = User(
        username=req.username,
        email=req.email,
        password_hash=hash_password(req.password),
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    token = create_token(user.id, user.username)
    return TokenResponse(token=token, user_id=user.id, username=user.username)


@router.post("/login", response_model=TokenResponse)
def login(req: LoginRequest, db: Session = Depends(get_db)):
    user = db.query(User).filter(User.username == req.username).first()
    if user is None or not verify_password(req.password, user.password_hash):
        raise HTTPException(status_code=401, detail="Invalid credentials")
    token = create_token(user.id, user.username)
    return TokenResponse(token=token, user_id=user.id, username=user.username)


# ---------------------------------------------------------------------------
# Device endpoints
# ---------------------------------------------------------------------------

@router.get("/devices", response_model=list[DeviceResponse])
def list_devices(user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    devices = db.query(Device).filter(Device.user_id == user.id).all()
    return [
        DeviceResponse(
            id=d.id,
            name=d.name,
            identifier=d.identifier,
            last_seen=d.last_seen.isoformat() if d.last_seen else None,
        )
        for d in devices
    ]


@router.post("/devices", response_model=DeviceResponse, status_code=201)
def create_device(req: DeviceCreate, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    existing = db.query(Device).filter(Device.identifier == req.identifier).first()
    if existing:
        raise HTTPException(status_code=409, detail="Device identifier already registered")
    device = Device(name=req.name, identifier=req.identifier, user_id=user.id)
    db.add(device)
    db.commit()
    db.refresh(device)
    return DeviceResponse(id=device.id, name=device.name, identifier=device.identifier)


@router.delete("/devices/{device_id}", status_code=204)
def delete_device(device_id: int, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    device = db.query(Device).filter(Device.id == device_id, Device.user_id == user.id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")
    db.delete(device)
    db.commit()


# ---------------------------------------------------------------------------
# Location endpoints
# ---------------------------------------------------------------------------

@router.post("/locations", response_model=BatchResponse)
def upload_locations(batch: LocationBatch, user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    device = db.query(Device).filter(Device.id == batch.device_id, Device.user_id == user.id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Device not found or not owned by user")

    batch_id = uuid.uuid4().hex[:12]
    now = datetime.datetime.utcnow()

    for pt in batch.locations:
        loc = Location(
            device_id=device.id,
            latitude=pt.latitude,
            longitude=pt.longitude,
            altitude=pt.altitude,
            horizontal_accuracy=pt.horizontal_accuracy,
            vertical_accuracy=pt.vertical_accuracy,
            speed=pt.speed,
            course=pt.course,
            timestamp=datetime.datetime.fromisoformat(pt.timestamp),
            received_at=now,
            batch_id=batch_id,
        )
        db.add(loc)

    device.last_seen = now
    db.commit()

    return BatchResponse(received=len(batch.locations), batch_id=batch_id)


@router.get("/locations/{device_id}")
def get_locations(
    device_id: int,
    limit: int = 100,
    offset: int = 0,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    device = db.query(Device).filter(Device.id == device_id, Device.user_id == user.id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Device not found or not owned by user")

    locations = (
        db.query(Location)
        .filter(Location.device_id == device_id)
        .order_by(Location.timestamp.desc())
        .offset(offset)
        .limit(limit)
        .all()
    )

    return [
        {
            "id": loc.id,
            "latitude": loc.latitude,
            "longitude": loc.longitude,
            "altitude": loc.altitude,
            "speed": loc.speed,
            "course": loc.course,
            "timestamp": loc.timestamp.isoformat(),
            "received_at": loc.received_at.isoformat(),
            "batch_id": loc.batch_id,
        }
        for loc in locations
    ]
