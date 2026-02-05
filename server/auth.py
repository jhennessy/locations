"""Authentication utilities: password hashing, token creation/verification.

Uses HMAC-SHA256 tokens (no external JWT dependency required).
"""

import base64
import datetime
import hashlib
import hmac
import json
import os
import secrets
from typing import Optional

SECRET_KEY = os.environ.get("SECRET_KEY") or secrets.token_hex(32)
TOKEN_EXPIRE_HOURS = 72


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


def _b64_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def _b64_decode(s: str) -> bytes:
    padding = 4 - len(s) % 4
    if padding != 4:
        s += "=" * padding
    return base64.urlsafe_b64decode(s)


def create_token(user_id: int, username: str) -> str:
    """Create an HMAC-SHA256 signed token."""
    payload = {
        "sub": user_id,
        "username": username,
        "exp": (datetime.datetime.utcnow() + datetime.timedelta(hours=TOKEN_EXPIRE_HOURS)).isoformat(),
        "iat": datetime.datetime.utcnow().isoformat(),
    }
    payload_bytes = json.dumps(payload, separators=(",", ":")).encode()
    payload_b64 = _b64_encode(payload_bytes)
    sig = hmac.new(SECRET_KEY.encode(), payload_b64.encode(), hashlib.sha256).hexdigest()
    return f"{payload_b64}.{sig}"


def decode_token(token: str) -> Optional[dict]:
    """Verify and decode a token. Returns None if invalid or expired."""
    try:
        payload_b64, sig = token.split(".", 1)
        expected_sig = hmac.new(SECRET_KEY.encode(), payload_b64.encode(), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(sig, expected_sig):
            return None
        payload = json.loads(_b64_decode(payload_b64))
        exp = datetime.datetime.fromisoformat(payload["exp"])
        if datetime.datetime.utcnow() > exp:
            return None
        return payload
    except Exception:
        return None
