"""Tests for PR2 CRUD + Search — RIF 422, NFD, zona null, combined, pagination."""

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
    with TestClient(app) as c:
        yield c
    app.dependency_overrides.clear()
    Base.metadata.drop_all(bind=engine)


def test_create_valid_returns_201_and_nfd(client):
    payload = {"nombre": "Vigía Central", "lat": 8.61, "lng": -71.65, "zona": "VIGIA", "rif": "J12345678"}
    resp = client.post("/clientes", json=payload)
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["nombre_normalizado"] == "vigia central"
    assert body["has_gps_fix"] is True
    assert body["zona"] == "VIGIA"


def test_create_invalid_rif_422_no_row(client):
    resp = client.post("/clientes", json={"nombre": "Test", "rif": "A123"})
    assert resp.status_code == 422
    # no row persisted
    resp2 = client.get("/clientes")
    assert resp2.json()["total"] == 0


def test_lifecycle_null_zona_get_patch_delete(client):
    # create with zona null
    resp = client.post("/clientes", json={"nombre": "Sin Zona"})
    assert resp.status_code == 201
    cid = resp.json()["id"]
    assert resp.json()["zona"] is None

    # GET returns null zona
    resp = client.get(f"/clientes/{cid}")
    assert resp.status_code == 200
    assert resp.json()["zona"] is None

    # PATCH updates zona
    resp = client.patch(f"/clientes/{cid}", json={"zona": "Lunes"})
    assert resp.status_code == 200
    assert resp.json()["zona"] == "Lunes"

    # DELETE
    resp = client.delete(f"/clientes/{cid}")
    assert resp.status_code == 204

    # GET after delete 404
    resp = client.get(f"/clientes/{cid}")
    assert resp.status_code == 404


def test_search_nfd_vigia_finds_vigia_central(client):
    client.post("/clientes", json={"nombre": "Vigía Central", "zona": "VIGIA"})
    client.post("/clientes", json={"nombre": "Merida Norte", "zona": "MERIDA"})
    resp = client.get("/clientes", params={"q": "vigia"})
    assert resp.status_code == 200
    items = resp.json()["items"]
    assert any("Vigía" in r["nombre"] for r in items)
    assert all("vigia" in r["nombre_normalizado"] for r in items)


def test_search_combined_filter_zona(client):
    client.post("/clientes", json={"nombre": "Vigía Central", "zona": "VIGIA"})
    client.post("/clientes", json={"nombre": "Vigía Sur", "zona": None})
    client.post("/clientes", json={"nombre": "Vigía Este", "zona": "MERIDA"})
    resp = client.get("/clientes", params={"q": "vigia", "zona": "VIGIA"})
    assert resp.status_code == 200
    items = resp.json()["items"]
    assert len(items) == 1
    assert items[0]["zona"] == "VIGIA"


def test_search_zona_null_not_excluded(client):
    client.post("/clientes", json={"nombre": "A", "zona": None})
    client.post("/clientes", json={"nombre": "B", "zona": "VIGIA"})
    resp = client.get("/clientes")
    assert resp.status_code == 200
    data = resp.json()
    assert data["total"] == 2
    zonas = [r["zona"] for r in data["items"]]
    assert None in zonas
    assert "VIGIA" in zonas


def test_search_pagination_limit_page(client):
    for i in range(5):
        client.post("/clientes", json={"nombre": f"Cliente {i}"})
    resp = client.get("/clientes", params={"page": 1, "limit": 2})
    assert resp.status_code == 200
    data = resp.json()
    assert data["total"] == 5
    assert len(data["items"]) == 2
    assert data["page"] == 1
    resp2 = client.get("/clientes", params={"page": 3, "limit": 2})
    assert len(resp2.json()["items"]) == 1


def test_rif_valid_variants_and_patch_rif_validation(client):
    for rif in ["J12345678", "V123456789", "E1234567", "G12345678", "P12345678"]:
        resp = client.post("/clientes", json={"nombre": "RIF Test", "rif": rif})
        assert resp.status_code == 201, f"rif {rif} should pass"
    # patch invalid rif -> 422
    cid = client.post("/clientes", json={"nombre": "Patch RIF"}).json()["id"]
    resp = client.patch(f"/clientes/{cid}", json={"rif": "X123"})
    assert resp.status_code == 422


def test_delete_404_and_get_404(client):
    resp = client.delete("/clientes/9999")
    assert resp.status_code == 404
    resp = client.get("/clientes/9999")
    assert resp.status_code == 404


def test_update_nombre_recomputes_normalizado_and_flags(client):
    cid = client.post("/clientes", json={"nombre": "Original", "empresa": "Foo"}).json()["id"]
    resp = client.patch(f"/clientes/{cid}", json={"nombre": "Vigía Nueva", "empresa": "Foo #flagged"})
    assert resp.status_code == 200
    assert resp.json()["nombre_normalizado"] == "vigia nueva"
    assert resp.json()["is_flagged"] is True


def test_has_gps_fix_derived_on_create_and_patch(client):
    cid = client.post("/clientes", json={"nombre": "No GPS"}).json()["id"]
    resp = client.get(f"/clientes/{cid}")
    assert resp.json()["has_gps_fix"] is False
    # add coords
    resp = client.patch(f"/clientes/{cid}", json={"lat": 10.0, "lng": 20.0})
    assert resp.json()["has_gps_fix"] is True
