"""Full offline sync cycle against mock GAS — no browser, no Sheet."""
import os
import sqlite3
import threading
import time
import uuid
import httpx
import pytest
import uvicorn
from harness.mock_gas import app as mock_app
from harness.sync_engine import sync

GAS_URL = "http://127.0.0.1:8765/exec"
SCHEMA = """
CREATE TABLE IF NOT EXISTS clientes (
  id TEXT PRIMARY KEY,
  nombre TEXT NOT NULL,
  rif_cedula TEXT,
  direccion TEXT,
  latitud REAL NOT NULL,
  longitud REAL NOT NULL,
  telefono TEXT,
  updated_at TEXT NOT NULL,
  sync_status INTEGER NOT NULL DEFAULT 0,
  deleted INTEGER NOT NULL DEFAULT 0
);
"""

@pytest.fixture(scope="module", autouse=True)
def mock_server():
    config = uvicorn.Config(mock_app, host="127.0.0.1", port=8765, log_level="error")
    server = uvicorn.Server(config)
    t = threading.Thread(target=server.run, daemon=True)
    t.start()
    for _ in range(30):
        try:
            httpx.get("http://127.0.0.1:8765/exec", timeout=0.5)
            break
        except Exception:
            time.sleep(0.2)
    yield
    server.should_exit = True
    t.join(timeout=2)

def _tmp_db(tmp_path):
    db = str(tmp_path / "test_clientes.db")
    con = sqlite3.connect(db)
    con.executescript(SCHEMA)
    con.commit()
    con.close()
    return db

def test_sync_offline_to_mock(tmp_path):
    db_path = _tmp_db(tmp_path)
    uid = str(uuid.uuid4())
    con = sqlite3.connect(db_path)
    con.execute(
        "INSERT INTO clientes (id,nombre,latitud,longitud,updated_at,sync_status) VALUES (?,?,?,?,?,0)",
        (uid, "Cliente Offline Test", 8.60, -71.64, "2026-01-01T00:00:00Z"),
    )
    con.commit()
    cur = con.execute("SELECT sync_status FROM clientes WHERE id=?", (uid,))
    assert cur.fetchone()[0] == 0
    con.close()

    result = sync(db_path, GAS_URL)
    assert result["synced"] == 1

    con = sqlite3.connect(db_path)
    cur = con.execute("SELECT sync_status FROM clientes WHERE id=?", (uid,))
    assert cur.fetchone()[0] == 1
    con.close()

def test_post_rejects_invalid_uuid():
    # spec allows array or {clientes:[]} ; both must validate UUID
    resp = httpx.post(GAS_URL, json=[{"id": "not-a-uuid", "nombre": "X"}])
    assert resp.status_code == 400
    # also wrapper form
    resp2 = httpx.post(GAS_URL, json={"clientes": [{"id": "not-a-uuid", "nombre": "X"}]})
    assert resp2.status_code == 400

def test_get_returns_two_clientes():
    # isolate: clear store so only INITIAL_CLIENTES remain
    try:
        httpx.delete("http://127.0.0.1:8765/_store", timeout=1.0)
    except Exception:
        pass
    resp = httpx.get(GAS_URL + "?lastSync=1970-01-01T00:00:00.000Z")
    assert resp.status_code == 200
    j = resp.json()
    assert "clientes" in j
    assert len(j["clientes"]) == 2
    # also verify last_sync alias compat
    resp2 = httpx.get(GAS_URL + "?last_sync=1970-01-01T00:00:00.000Z")
    assert resp2.status_code == 200
    assert len(resp2.json()["clientes"]) == 2
