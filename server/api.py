"""REST API endpoints for the iOS app (authentication, devices, location uploads)."""

import datetime
import uuid
from functools import wraps
from typing import Optional

from fastapi import APIRouter, Depends, Header, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from auth import create_token, decode_token, hash_password, verify_password, revoke_token
from database import get_db
from models import Device, Location, Place, User, Visit
from processing import process_device_locations

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
    visits_detected: int = 0


class VisitResponse(BaseModel):
    id: int
    device_id: int
    place_id: int
    latitude: float
    longitude: float
    arrival: str
    departure: str
    duration_seconds: int
    address: Optional[str] = None

    class Config:
        from_attributes = True


class PlaceResponse(BaseModel):
    id: int
    latitude: float
    longitude: float
    name: Optional[str] = None
    address: Optional[str] = None
    visit_count: int
    total_duration_seconds: int

    class Config:
        from_attributes = True


# ---------------------------------------------------------------------------
# Auth dependency
# ---------------------------------------------------------------------------

def get_current_user(authorization: str = Header(...), db: Session = Depends(get_db)) -> User:
    if not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Invalid authorization header")
    token = authorization[7:]
    payload = decode_token(token, db)
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
    token = create_token(user.id, user.username, db)
    return TokenResponse(token=token, user_id=user.id, username=user.username)


@router.post("/login", response_model=TokenResponse)
def login(req: LoginRequest, db: Session = Depends(get_db)):
    user = db.query(User).filter(User.username == req.username).first()
    if user is None or not verify_password(req.password, user.password_hash):
        raise HTTPException(status_code=401, detail="Invalid credentials")
    token = create_token(user.id, user.username, db)
    return TokenResponse(token=token, user_id=user.id, username=user.username)


@router.post("/logout")
async def logout(request: Request, db: Session = Depends(get_db)):
    token = request.headers.get("Authorization", "").replace("Bearer ", "")
    if token:
        revoke_token(token, db)
    return {"message": "Logged out"}


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

    # Trigger visit detection pipeline
    new_visits = process_device_locations(db, device.id, user.id)

    return BatchResponse(received=len(batch.locations), batch_id=batch_id, visits_detected=len(new_visits))


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


# ---------------------------------------------------------------------------
# Visit endpoints
# ---------------------------------------------------------------------------

@router.get("/visits/{device_id}", response_model=list[VisitResponse])
def get_visits(
    device_id: int,
    limit: int = 100,
    offset: int = 0,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    device = db.query(Device).filter(Device.id == device_id, Device.user_id == user.id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Device not found or not owned by user")

    visits = (
        db.query(Visit)
        .filter(Visit.device_id == device_id)
        .order_by(Visit.arrival.desc())
        .offset(offset)
        .limit(limit)
        .all()
    )

    return [
        VisitResponse(
            id=v.id,
            device_id=v.device_id,
            place_id=v.place_id,
            latitude=v.latitude,
            longitude=v.longitude,
            arrival=v.arrival.isoformat(),
            departure=v.departure.isoformat(),
            duration_seconds=v.duration_seconds,
            address=v.address,
        )
        for v in visits
    ]


@router.post("/visits/{device_id}/reprocess")
def reprocess_visits(
    device_id: int,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """Delete existing visits for a device and reprocess all locations."""
    device = db.query(Device).filter(Device.id == device_id, Device.user_id == user.id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Device not found or not owned by user")

    db.query(Visit).filter(Visit.device_id == device_id).delete()
    db.commit()

    new_visits = process_device_locations(db, device.id, user.id)
    return {"reprocessed": True, "visits_detected": len(new_visits)}


# ---------------------------------------------------------------------------
# Place endpoints
# ---------------------------------------------------------------------------

@router.get("/places", response_model=list[PlaceResponse])
def get_places(user: User = Depends(get_current_user), db: Session = Depends(get_db)):
    places = (
        db.query(Place)
        .filter(Place.user_id == user.id)
        .order_by(Place.visit_count.desc())
        .all()
    )
    return [
        PlaceResponse(
            id=p.id,
            latitude=p.latitude,
            longitude=p.longitude,
            name=p.name,
            address=p.address,
            visit_count=p.visit_count,
            total_duration_seconds=p.total_duration_seconds,
        )
        for p in places
    ]


@router.get("/places/frequent", response_model=list[PlaceResponse])
def get_frequent_places(
    limit: int = 20,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """Return the most frequently visited places, ordered by visit count."""
    places = (
        db.query(Place)
        .filter(Place.user_id == user.id, Place.visit_count >= 2)
        .order_by(Place.visit_count.desc())
        .limit(limit)
        .all()
    )
    return [
        PlaceResponse(
            id=p.id,
            latitude=p.latitude,
            longitude=p.longitude,
            name=p.name,
            address=p.address,
            visit_count=p.visit_count,
            total_duration_seconds=p.total_duration_seconds,
        )
        for p in places
    ]


@router.put("/places/{place_id}/name")
def update_place_name(
    place_id: int,
    body: dict,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    place = db.query(Place).filter(Place.id == place_id, Place.user_id == user.id).first()
    if not place:
        raise HTTPException(status_code=404, detail="Place not found")
    place.name = body.get("name", place.name)
    db.commit()
    return {"id": place.id, "name": place.name}


# ---------------------------------------------------------------------------
# Position schemas
# ---------------------------------------------------------------------------

class PositionPoint(BaseModel):
    latitude: float
    longitude: float
    altitude: float = None
    accuracy: float = None
    speed: float = None
    timestamp: str


class PositionBatch(BaseModel):
    device_id: int
    positions: list[PositionPoint]


class RelayedPosition(BaseModel):
    device_id: int
    latitude: float
    longitude: float
    altitude: float = None
    accuracy: float = None
    speed: float = None
    timestamp: str


class RelayBatch(BaseModel):
    relay_device_id: int
    positions: list[RelayedPosition]


# ---------------------------------------------------------------------------
# Position endpoints
# ---------------------------------------------------------------------------

@router.post("/positions")
async def update_positions(batch: PositionBatch, user=Depends(get_current_user), db: Session = Depends(get_db)):
    from models import CurrentPosition, Device as DeviceModel
    device = db.query(DeviceModel).filter(DeviceModel.id == batch.device_id, DeviceModel.user_id == user.id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")

    for pos in batch.positions:
        ts = datetime.datetime.fromisoformat(pos.timestamp.replace("Z", "+00:00")).replace(tzinfo=None)
        existing = db.query(CurrentPosition).filter(CurrentPosition.device_id == batch.device_id).first()
        if existing:
            existing.latitude = pos.latitude
            existing.longitude = pos.longitude
            existing.altitude = pos.altitude
            existing.accuracy = pos.accuracy
            existing.speed = pos.speed
            existing.timestamp = ts
            existing.updated_at = datetime.datetime.utcnow()
        else:
            cp = CurrentPosition(
                user_id=user.id,
                device_id=batch.device_id,
                latitude=pos.latitude,
                longitude=pos.longitude,
                altitude=pos.altitude,
                accuracy=pos.accuracy,
                speed=pos.speed,
                timestamp=ts
            )
            db.add(cp)
    db.commit()
    return {"updated": len(batch.positions)}


@router.get("/positions")
async def get_all_positions(user=Depends(get_current_user), db: Session = Depends(get_db)):
    from models import CurrentPosition, Device as DeviceModel, User as UserModel, Config
    ttl = 300
    config = db.query(Config).filter(Config.key == "position_ttl_seconds").first()
    if config:
        ttl = int(config.value)

    positions = db.query(CurrentPosition).all()
    now = datetime.datetime.utcnow()
    result = []
    for p in positions:
        device = db.query(DeviceModel).filter(DeviceModel.id == p.device_id).first()
        pos_user = db.query(UserModel).filter(UserModel.id == p.user_id).first()
        is_stale = (now - p.updated_at).total_seconds() > ttl if p.updated_at else True
        result.append({
            "device_id": p.device_id,
            "device_name": device.name if device else None,
            "user_id": p.user_id,
            "username": pos_user.username if pos_user else None,
            "latitude": p.latitude,
            "longitude": p.longitude,
            "altitude": p.altitude,
            "accuracy": p.accuracy,
            "speed": p.speed,
            "timestamp": p.timestamp.isoformat() if p.timestamp else None,
            "is_stale": is_stale
        })
    return result


@router.post("/positions/relay")
async def relay_positions(batch: RelayBatch, user=Depends(get_current_user), db: Session = Depends(get_db)):
    from models import CurrentPosition, Device as DeviceModel
    relay_device = db.query(DeviceModel).filter(DeviceModel.id == batch.relay_device_id, DeviceModel.user_id == user.id).first()
    if not relay_device:
        raise HTTPException(status_code=404, detail="Relay device not found")

    updated = 0
    for pos in batch.positions:
        ts = datetime.datetime.fromisoformat(pos.timestamp.replace("Z", "+00:00")).replace(tzinfo=None)
        existing = db.query(CurrentPosition).filter(CurrentPosition.device_id == pos.device_id).first()
        if existing:
            if existing.timestamp and existing.timestamp >= ts:
                continue
            existing.latitude = pos.latitude
            existing.longitude = pos.longitude
            existing.altitude = pos.altitude
            existing.accuracy = pos.accuracy
            existing.speed = pos.speed
            existing.timestamp = ts
            existing.updated_at = datetime.datetime.utcnow()
            existing.relayed_by_device_id = batch.relay_device_id
        else:
            device = db.query(DeviceModel).filter(DeviceModel.id == pos.device_id).first()
            if not device:
                continue
            cp = CurrentPosition(
                user_id=device.user_id,
                device_id=pos.device_id,
                latitude=pos.latitude,
                longitude=pos.longitude,
                altitude=pos.altitude,
                accuracy=pos.accuracy,
                speed=pos.speed,
                timestamp=ts,
                relayed_by_device_id=batch.relay_device_id
            )
            db.add(cp)
        updated += 1
    db.commit()
    return {"relayed": updated}
