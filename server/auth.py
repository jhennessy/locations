"""Authentication utilities: password hashing, DB-backed session tokens."""

import datetime
import hashlib
import os
import secrets

from models import Session


def hash_password(password: str) -> str:
    salt = os.urandom(32)
    key = hashlib.pbkdf2_hmac('sha256', password.encode(), salt, 100000)
    return salt.hex() + key.hex()


def verify_password(password: str, stored: str) -> bool:
    try:
        salt = bytes.fromhex(stored[:64])
        stored_key = stored[64:]
        key = hashlib.pbkdf2_hmac('sha256', password.encode(), salt, 100000)
        return key.hex() == stored_key
    except (ValueError, AttributeError):
        return False


def create_token(user_id: int, username: str, db, device_info: str = None) -> str:
    cleanup_expired_sessions(db)
    token = secrets.token_urlsafe(32)
    session = Session(
        user_id=user_id,
        token=token,
        expires_at=datetime.datetime.utcnow() + datetime.timedelta(days=90),
        device_info=device_info
    )
    db.add(session)
    db.commit()
    return token


def decode_token(token: str, db) -> dict | None:
    if not token:
        return None
    session = db.query(Session).filter(Session.token == token).first()
    if not session:
        return None
    if session.expires_at < datetime.datetime.utcnow():
        db.delete(session)
        db.commit()
        return None
    return {"sub": session.user_id, "username": session.user.username}


def revoke_token(token: str, db):
    session = db.query(Session).filter(Session.token == token).first()
    if session:
        db.delete(session)
        db.commit()


def cleanup_expired_sessions(db):
    db.query(Session).filter(Session.expires_at < datetime.datetime.utcnow()).delete()
    db.commit()
