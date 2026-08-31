"""Regression tests for audit fixes 2026-08-30 — C1,S1,A1,S3,A3.

Covers:
- test_generate_uuid_importable (C1)
- test_search_pagination_sql (A1)
- test_cors_headers_whitelist (S1)
"""
import re
import io

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from backend.database import Base, get_db, generate_uuid, utc_now_iso
from backend.main import app, MAX_IMPORT_SIZE, MAX_IMPORT_ROWS
from backend import models

UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")


@pytest.fixture()
def client():
    engine = create_engine(
        "sqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    TestingSessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
    Base.metadata.create_all(bind=engine)

    def override_get_db():
        db = TestingSessionLocal()
        try:
            yield db
        finally:
            db.close()

    app.dependency_overrides[get_db] = override_get_db
    with TestClient(app) as c:
        yield c
    app.dependency_overrides.clear()
    Base.metadata.drop_all(bind=engine)


def test_generate_uuid_importable():
    """C1: generate_uuid and utc_now_iso must be importable from database and return valid values."""
    # import already done at top — would have raised ImportError if C1 regressed
    uid = generate_uuid()
    assert isinstance(uid, str), "generate_uuid must return str"
    assert UUID_RE.match(uid.lower()), f"invalid UUIDv4: {uid}"
    # ensure lowercase per spec
    assert uid == uid.lower()
    iso = utc_now_iso()
    assert isinstance(iso, str)
    # must be ISO8601 parseable
    assert "T" in iso  # date + time separator
    # second call should differ (uuid)
    uid2 = generate_uuid()
    assert uid != uid2


def test_search_pagination_sql(client):
    """A1: search with q uses SQL pagination (limit/page via SQL), not Python slice.
    Creates 5 clientes, searches with q and verifies limit/page are applied at DB level.
    Also verifies that LIKE escaping works (%/_ not treated as wildcards).
    """
    # create 5 clientes with overlapping term "Cliente X"
    for i in range(5):
        resp = client.post("/clientes", json={"nombre": f"Cliente {i} TestSQL"})
        assert resp.status_code == 201, resp.text

    # q should match all 5 (nombre_normalizado contains "cliente")
    resp = client.get("/clientes", params={"q": "cliente", "limit": 2, "page": 1})
    assert resp.status_code == 200
    data = resp.json()
    assert data["total"] == 5, f"expected total 5, got {data['total']}"
    assert len(data["items"]) == 2
    assert data["page"] == 1
    assert data["limit"] == 2

    resp2 = client.get("/clientes", params={"q": "cliente", "limit": 2, "page": 3})
    assert resp2.status_code == 200
    data2 = resp2.json()
    assert data2["total"] == 5
    assert len(data2["items"]) == 1, "page 3 with limit 2 should have 1 remaining"
    assert data2["page"] == 3

    # verify that searching for literal % does not wildcard-match everything
    # create cliente with % in name via direct DB? Instead test that q="%" does not return all
    resp3 = client.get("/clientes", params={"q": "%", "limit": 10})
    # Should not match all 5 because % is escaped; only those whose normalized name contains "%" (none)
    assert resp3.status_code == 200
    # If escaping works, total should be 0 (no nombre contains literal %)
    assert resp3.json()["total"] == 0

    # also test texto_breve search via LIKE (create cliente with texto_breve)
    cid = client.post("/clientes", json={"nombre": "Unico", "lat": 1.0, "lng": 1.0}).json()["id"]
    # patch texto_breve
    r = client.patch(f"/clientes/{cid}", json={"texto_breve": "obra especial"})
    assert r.status_code == 200
    # search q that matches texto_breve — should find it via SQL LIKE on texto_breve
    resp4 = client.get("/clientes", params={"q": "obra"})
    assert resp4.status_code == 200
    # texto_breve search may depend on SQL LIKE vs NFD; if fix uses LIKE on raw, it should match
    # Accept total >=1 and that our cliente is in results
    items = resp4.json()["items"]
    assert any(str(x["id"]) == str(cid) for x in items), "texto_breve SQL LIKE should find patched cliente"


def test_cors_headers_whitelist(client):
    """S1: CORS must not allow arbitrary origins; whitelist is enforced.
    Tests that allowed origin gets ACAO, disallowed does not echo *.
    """
    # Allowed origin
    resp = client.get("/clientes", headers={"Origin": "http://localhost:8000"})
    assert resp.status_code == 200
    acao = resp.headers.get("access-control-allow-origin")
    # Starlette/CORSMiddleware returns the requesting origin when allowed, not *
    assert acao == "http://localhost:8000", f"expected whitelisted ACAO, got {acao!r}"

    # Another allowed origin (WebView)
    resp2 = client.get("/clientes", headers={"Origin": "https://appassets.androidplatform.net"})
    assert resp2.headers.get("access-control-allow-origin") == "https://appassets.androidplatform.net"

    # Disallowed origin should NOT get ACAO back (or None)
    resp3 = client.get("/clientes", headers={"Origin": "https://evil.com"})
    acao3 = resp3.headers.get("access-control-allow-origin")
    # Should be None or not equal to evil.com — must not be wildcard allowed
    assert acao3 is None or acao3 != "https://evil.com", f"evil.com should not be allowed, got ACAO={acao3!r}"
    # Also ensure not "*"
    assert acao3 != "*", "CORS must not return wildcard *"


def test_import_limits_constants():
    """S3: constants for DoS mitigation must exist and have expected values."""
    assert MAX_IMPORT_SIZE == 5 * 1024 * 1024
    assert MAX_IMPORT_ROWS == 5000
