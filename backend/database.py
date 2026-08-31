"""Database engine and session for SQLite clientes.db."""

import os
import re
import unicodedata
import uuid
from datetime import datetime, timezone

from sqlalchemy import create_engine
from sqlalchemy.orm import declarative_base, sessionmaker

# SQLite file lives next to this module: backend/clientes.db
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DB_PATH = os.path.join(BASE_DIR, "clientes.db")
DATABASE_URL = f"sqlite:///{DB_PATH}"

engine = create_engine(
    DATABASE_URL,
    connect_args={"check_same_thread": False},
)

SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

Base = declarative_base()


def get_db():
    """FastAPI dependency: yields a DB session per request."""
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def generate_uuid() -> str:
    return str(uuid.uuid4())


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def normalize_nfd(text: str | None) -> str:
    """NFD normalization: lowercase + trim + strip accents + collapse whitespace.

    Mirrors Android Normalize.kt (Normalizer.Form.NFD + diacritical strip).
    Example: 'Vigía Central' -> 'vigia central'
    """
    if not text:
        return ""
    trimmed = text.strip().lower()
    nfd = unicodedata.normalize("NFD", trimmed)
    without_accents = nfd.encode("ascii", "ignore").decode("ascii")
    collapsed = re.sub(r"\s+", " ", without_accents).strip()
    return collapsed
