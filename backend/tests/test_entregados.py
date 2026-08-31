"""PR5 Unit: entregado flow — PATCH idempotent + terminar blocked (REQ-ENT-01/02)."""
import httpx
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from backend.database import Base, get_db
from backend.main import app


@pytest.fixture()
def client():
    engine = create_engine("sqlite:///:memory:", connect_args={"check_same_thread": False}, poolclass=StaticPool)
    Session = sessionmaker(autocommit=False, autoflush=False, bind=engine)
    Base.metadata.create_all(bind=engine)

    def override():
        db = Session()
        try:
            yield db
        finally:
            db.close()

    app.dependency_overrides[get_db] = override
    with TestClient(app) as c:
        yield c
    app.dependency_overrides.clear()
    Base.metadata.drop_all(bind=engine)


def _gps_ids(cli, n=3):
    ids = []
    for i in range(n):
        r = cli.post("/clientes", json={"nombre": f"E2E {i}", "lat": 8.61 + i * 0.01, "lng": -71.65 - i * 0.01})
        assert r.status_code == 201, r.text
        ids.append(r.json()["id"])
    return ids


def _mock_vroom(monkeypatch):
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

    import backend.vroom_client as vc
    tr = httpx.MockTransport(_ok)
    orig = httpx.Client

    def _mc(*a, **kw):
        kw["transport"] = tr
        return orig(*a, **kw)

    monkeypatch.setattr(vc.httpx, "Client", _mc)


def test_entregado_default_and_filter(client, monkeypatch):
    _mock_vroom(monkeypatch)
    ids = _gps_ids(client, 3)
    r = client.post("/rutas/optimizar", json={"cliente_ids": ids})
    assert r.status_code == 200, r.text
    rows = client.get("/rutas/hoy").json()
    assert all(not x["entregado"] for x in rows)
    assert client.get("/rutas/hoy?entregado=false").json().__len__() == 3
    assert client.get("/rutas/hoy?entregado=true").json() == []


def test_patch_idempotent_and_renumber(client, monkeypatch):
    _mock_vroom(monkeypatch)
    ids = _gps_ids(client, 5)
    client.post("/rutas/optimizar", json={"cliente_ids": ids})
    r1 = client.patch("/rutas/hoy/entregado", json={"cliente_ids": ids[:2]})
    assert r1.status_code == 200 and r1.json()["updated"] == 2
    r2 = client.patch("/rutas/hoy/entregado", json={"cliente_ids": ids[:2]})
    assert r2.status_code == 200 and r2.json()["updated"] == 0  # idempotent
    pending = client.get("/rutas/hoy?entregado=false").json()
    assert len(pending) == 3
    pending_ids = {p["cliente_id"] for p in pending}
    assert not pending_ids.intersection(ids[:2])  # delivered removed; orden preserved 0,1,2 after reverse


def test_terminar_blocked_and_success(client, monkeypatch):
    _mock_vroom(monkeypatch)
    ids = _gps_ids(client, 2)
    client.post("/rutas/optimizar", json={"cliente_ids": ids})
    assert client.delete("/rutas/hoy").status_code == 409
    assert client.delete("/rutas/hoy/terminar").status_code == 409
    client.patch("/rutas/hoy/entregado", json={"cliente_ids": ids})
    assert client.delete("/rutas/hoy").status_code == 200
    assert client.get("/rutas/hoy").json() == []
    assert client.delete("/rutas/hoy").status_code == 200  # empty still 200
