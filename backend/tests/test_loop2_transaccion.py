"""Loop 2 regression tests — transaction atomicity, export guard, frontend pagination constant.

- test_optimize_atomic_delete_not_before_vroom (VROOM fail -> ruta previa no borrada)
- test_export_guard_large (count >5000 -> 413)
- test_paginacion_limite_const_exists (frontend/app.js contains PAGINACION_LIMITE = 500)
"""
import re
import httpx
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from backend.database import Base, get_db
from backend.main import app
from backend import models


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
    # expose engine/session for tests that need direct DB access
    client_obj = TestClient(app)
    # attach helpers for teardown
    client_obj._test_engine = engine  # type: ignore
    client_obj._test_session_factory = TestingSessionLocal  # type: ignore
    with client_obj as c:
        yield c
    app.dependency_overrides.clear()
    Base.metadata.drop_all(bind=engine)


def _create_clientes_with_gps(cli, n=3):
    ids = []
    for i in range(n):
        r = cli.post("/clientes", json={"nombre": f"Loop2 {i}", "lat": 8.61 + i * 0.01, "lng": -71.65 - i * 0.01})
        assert r.status_code == 201, r.text
        ids.append(r.json()["id"])
    return ids


def _vroom_success_transport():
    import json as _json

    def _ok(req: httpx.Request) -> httpx.Response:
        if req.method == "POST" and req.url.path == "/":
            payload = _json.loads(req.content.decode() or "{}")
            jobs = payload.get("jobs", [])
            ordered = list(reversed([j["id"] for j in jobs])) if len(jobs) > 1 else [j["id"] for j in jobs]
            steps = [{"type": "start", "id": 0}]
            for jid in ordered:
                steps.append({"type": "job", "id": jid})
            steps.append({"type": "end", "id": 0})
            return httpx.Response(200, json={"code": 0, "summary": {"distance": 1000, "duration": 600}, "routes": [{"steps": steps, "distance": 1000, "duration": 600}]})
        if "route" in req.url.path:
            return httpx.Response(200, json={"code": "Ok", "routes": [{"geometry": {"type": "LineString", "coordinates": [[-71.65, 8.61], [-71.66, 8.62]]}, "distance": 1000, "duration": 600}]})
        return httpx.Response(200, json={})

    return httpx.MockTransport(_ok)


def _vroom_fail_transport():
    def _fail(req: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("VROOM down mock")

    return httpx.MockTransport(_fail)


def test_optimize_atomic_delete_not_before_vroom(client, monkeypatch):
    """If VROOM fails, previous ruta for today must NOT be deleted (atomic TX)."""
    import backend.vroom_client as vc

    ids = _create_clientes_with_gps(client, 3)

    # First optimize succeeds -> creates ruta_hoy with 3 rows
    orig_client = httpx.Client

    def _mock_success(*a, **kw):
        kw["transport"] = _vroom_success_transport()
        return orig_client(*a, **kw)

    monkeypatch.setattr(vc.httpx, "Client", _mock_success)
    r1 = client.post("/rutas/optimizar", json={"cliente_ids": ids})
    assert r1.status_code == 200, r1.text
    rows_before = client.get("/rutas/hoy").json()
    assert len(rows_before) == 3, "initial ruta should have 3 rows"

    # Second optimize with VROOM failure -> should return 502 and preserve previous ruta
    def _mock_fail(*a, **kw):
        kw["transport"] = _vroom_fail_transport()
        return orig_client(*a, **kw)

    monkeypatch.setattr(vc.httpx, "Client", _mock_fail)
    # use same ids plus maybe new order, but VROOM will fail before TX delete
    r2 = client.post("/rutas/optimizar", json={"cliente_ids": ids})
    assert r2.status_code == 502, r2.text

    rows_after = client.get("/rutas/hoy").json()
    assert len(rows_after) == 3, f"atomicity: ruta previa must be preserved after VROOM fail, got {len(rows_after)}"
    # orden preserved
    assert [r["orden"] for r in rows_after] == [0, 1, 2]


def test_optimize_atomic_osrm_fail_preserves_route(client, monkeypatch):
    """If OSRM geometry fails after VROOM success, ruta previa must be preserved."""
    import backend.vroom_client as vc
    import json as _json

    ids = _create_clientes_with_gps(client, 2)
    orig_client = httpx.Client

    def _mock_success(*a, **kw):
        kw["transport"] = _vroom_success_transport()
        return orig_client(*a, **kw)

    monkeypatch.setattr(vc.httpx, "Client", _mock_success)
    r1 = client.post("/rutas/optimizar", json={"cliente_ids": ids})
    assert r1.status_code == 200
    assert len(client.get("/rutas/hoy").json()) == 2

    # Now VROOM succeeds but OSRM fails -> should 502 and preserve
    def _osrm_fail_handler(req: httpx.Request) -> httpx.Response:
        if req.method == "POST" and req.url.path == "/":
            payload = _json.loads(req.content.decode() or "{}")
            jobs = payload.get("jobs", [])
            ordered = [j["id"] for j in jobs]
            steps = [{"type": "start", "id": 0}]
            for jid in ordered:
                steps.append({"type": "job", "id": jid})
            steps.append({"type": "end", "id": 0})
            return httpx.Response(200, json={"code": 0, "summary": {"distance": 1000, "duration": 600}, "routes": [{"steps": steps, "distance": 1000, "duration": 600}]})
        if "route" in req.url.path:
            raise httpx.ConnectError("OSRM down mock")
        return httpx.Response(200, json={})

    def _mock_osrm_fail(*a, **kw):
        kw["transport"] = httpx.MockTransport(_osrm_fail_handler)
        return orig_client(*a, **kw)

    monkeypatch.setattr(vc.httpx, "Client", _mock_osrm_fail)
    r2 = client.post("/rutas/optimizar", json={"cliente_ids": ids})
    # OSRM failure maps to 502
    assert r2.status_code == 502, r2.text
    rows_after = client.get("/rutas/hoy").json()
    assert len(rows_after) == 2, "OSRM fail must not delete previous route"


def test_export_guard_large(client):
    """GET /clientes/export with count >5000 must return 413 guard."""
    # Directly insert 5001 rows via DB session for speed (bypass HTTP)
    # Access the test engine/session factory attached to client
    engine = client._test_engine  # type: ignore
    SessionFactory = client._test_session_factory  # type: ignore
    db = SessionFactory()
    try:
        # bulk insert 5001 clientes
        objs = []
        for i in range(5001):
            objs.append(
                models.Cliente(
                    id=f"test-guard-{i:05d}-0000-0000-000000000000",
                    nombre=f"Guard Cliente {i}",
                    nombre_normalizado=f"guard cliente {i}",
                    has_gps_fix=False,
                    is_flagged=False,
                    deleted=0,
                    sync_status=0,
                )
            )
            if len(objs) >= 1000:
                db.add_all(objs)
                db.commit()
                objs = []
        if objs:
            db.add_all(objs)
            db.commit()
    finally:
        db.close()

    resp = client.get("/clientes/export", params={"formato": "xlsx"})
    assert resp.status_code == 413, resp.text
    assert "Demasiados registros" in resp.text or "filtra" in resp.text.lower()

    # Also test pdf guard same
    resp2 = client.get("/clientes/export", params={"formato": "pdf"})
    assert resp2.status_code == 413


def test_paginacion_limite_const_exists():
    """Frontend app.js must define PAGINACION_LIMITE = 500 and use it in fetchClientes."""
    import pathlib

    app_js = pathlib.Path("frontend/app.js").read_text(encoding="utf-8")
    assert "PAGINACION_LIMITE" in app_js, "PAGINACION_LIMITE constant missing"
    # check value 500
    m = re.search(r"PAGINACION_LIMITE\s*=\s*500", app_js)
    assert m is not None, "PAGINACION_LIMITE must be = 500"
    assert "Mostrando" in app_js or "paginacionBanner" in app_js, "banner logic missing"
    # index.html banner exists
    index_html = pathlib.Path("frontend/index.html").read_text(encoding="utf-8")
    assert "paginacionBanner" in index_html

    # vroom_client pooling check
    vroom_py = pathlib.Path("backend/vroom_client.py").read_text(encoding="utf-8")
    assert "CLIENT_LIMITS" in vroom_py or "Limits" in vroom_py
    assert "timeout=10" in vroom_py.lower() or "TIMEOUT" in vroom_py

    # export guard check
    main_py = pathlib.Path("backend/main.py").read_text(encoding="utf-8")
    assert "EXPORT_MAX_ROWS" in main_py or "5000" in main_py
    assert "413" in main_py
