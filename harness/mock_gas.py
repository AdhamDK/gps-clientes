"""Mock Google Apps Script — GET/POST /exec (FastAPI). Spec: ?lastSync=ISO (canonical camelCase; last_sync alias deprecated), POST array JSON, status success."""
import re
from datetime import datetime, timezone
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
import uvicorn

app = FastAPI(title="Mock GAS")
store: list[dict] = []
UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

def _parse_iso(s: str):
    try:
        iso = s.replace("Z", "+00:00")
        return datetime.fromisoformat(iso)
    except Exception:
        return datetime(1970, 1, 1, tzinfo=timezone.utc)

INITIAL_CLIENTES = [
    {"id": "550e8400-e29b-41d4-a716-446655440001", "nombre": "Cliente A", "lat": 8.61, "lng": -71.65, "latitud": 8.61, "longitud": -71.65, "updated_at": "2026-01-01T00:00:00.000Z", "sync_status": 1, "deleted": 0},
    {"id": "550e8400-e29b-41d4-a716-446655440002", "nombre": "Cliente B", "lat": 8.62, "lng": -71.66, "latitud": 8.62, "longitud": -71.66, "updated_at": "2026-06-01T00:00:00.000Z", "sync_status": 1, "deleted": 0},
]

@app.get("/exec")
def get_exec(request: Request):
    # canonical param is lastSync (A3 2026-08-30); also accept last_sync for compat (deprecated)
    last_sync_raw = request.query_params.get("lastSync") or request.query_params.get("last_sync") or "1970-01-01T00:00:00.000Z"
    last_sync_dt = _parse_iso(last_sync_raw)
    # combine initial + store, filter where updated_at > lastSync
    all_clientes = INITIAL_CLIENTES + store
    filtered = []
    for c in all_clientes:
        ua = c.get("updated_at") or "1970-01-01T00:00:00.000Z"
        try:
            dt = _parse_iso(str(ua))
        except Exception:
            continue
        if dt > last_sync_dt:
            filtered.append(c)
    return {"clientes": filtered}

@app.post("/exec")
async def post_exec(req: Request):
    body = await req.json()
    # spec: POST array JSON ; also accept {clientes: []} wrapper for compat
    clientes = body if isinstance(body, list) else body.get("clientes") if isinstance(body, dict) else None
    if not isinstance(clientes, list):
        return JSONResponse({"status": "error", "detail": "expected array or {clientes: [...] }"}, status_code=400)
    for c in clientes:
        uid = str(c.get("id", ""))
        if not UUID_RE.match(uid.lower()):
            return JSONResponse({"status": "error", "detail": f"invalid UUID: {uid}"}, status_code=400)
    store.extend(clientes)
    # spec expects {status:"success"} ; keep "ok" compatible alias via same value? return success per task
    return {"status": "success", "synced": len(clientes), "inserted": len(clientes)}

@app.get("/_store")
def get_store():
    return {"store": store}

@app.delete("/_store")
def clear_store():
    store.clear()
    return {"cleared": True}

if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=8765, log_level="error")
