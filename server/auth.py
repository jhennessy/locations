"""Authentication utilities: password hashing, database-backed session tokens.

Tokens are random strings stored in the sessions table — they survive server
restarts and SECRET_KEY changes.  Password hashing still uses PBKDF2-HMAC-SHA256.
"""

import datetime
import hashlib
import hmac
import secrets
from typing import Optional

from sqlalchemy.orm import Session as DBSession

TOKEN_EXPIRE_HOURS = 72


# ---------------------------------------------------------------------------
# Password hashing (unchanged — self-contained, no secret key dependency)
# ---------------------------------------------------------------------------

def hash_password(password: str) -> str:
    salt = secrets.token_hex(16)
    h = hashlib.pbkdf2_hmac("sha256", password.encode(), salt.encode(), 100_000)
    return f"{salt}:{h.hex()}"


def verify_password(password: str, password_hash: str) -> bool:
    try:
        salt, stored_hash = password_hash.split(":")
        h = hashlib.pbkdf2_hmac("sha256", password.encode(), salt.encode(), 100_000)
        return hmac.compare_digest(h.hex(), stored_hash)
    except (ValueError, AttributeError):
        return False


# ---------------------------------------------------------------------------
# Database-backed session tokens
# ---------------------------------------------------------------------------

def create_token(user_id: int, username: str, db: DBSession, device_info: str | None = None) -> str:
    """Create a random session token and persist it in the DB."""
    from models import Session

    token = secrets.token_urlsafe(32)
    expires_at = datetime.datetime.utcnow() + datetime.timedelta(hours=TOKEN_EXPIRE_HOURS)
    session = Session(
        user_id=user_id,
        token=token,
        expires_at=expires_at,
        device_info=device_info,
    )
    db.add(session)
    db.commit()
    return token


def decode_token(token: str, db: DBSession) -> Optional[dict]:
    """Look up token in the DB.  Returns ``{"sub": user_id, "username": ...}`` or *None*."""
    from models import Session, User

    session = db.query(Session).filter(Session.token == token).first()
    if session is None:
        return None
    if datetime.datetime.utcnow() > session.expires_at:
        db.delete(session)
        db.commit()
        return None
    user = db.query(User).filter(User.id == session.user_id).first()
    if user is None:
        return None
    return {"sub": user.id, "username": user.username}


def revoke_token(token: str, db: DBSession) -> bool:
    """Delete a session token (logout).  Returns True if the token existed."""
    from models import Session

    session = db.query(Session).filter(Session.token == token).first()
    if session is None:
        return False
    db.delete(session)
    db.commit()
    return True


def cleanup_expired_sessions(db: DBSession) -> int:
    """Remove all expired sessions.  Returns the number deleted."""
    from models import Session

    now = datetime.datetime.utcnow()
    count = db.query(Session).filter(Session.expires_at < now).delete()
    db.commit()
    return count
