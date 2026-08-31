# Exploration: netlify-gas-frontend

## Current State

### Architecture Overview

The GPS_CLIENTES app is an offline-first PWA for route management in El Vigía, Venezuela. It has two backends:

1. **FastAPI (local Docker)** — `backend/main.py` serves:
   - Client CRUD: `GET/POST/PATCH/DELETE /clientes`, `POST /clientes/import`, `GET /clientes/export`
   - Route optimization: `POST /rutas/optimizar` → calls **VROOM (:3000) + OSRM (:5000)** in Docker
   - Route management: `GET/PATCH/DELETE /rutas/hoy`, `PATCH /rutas/hoy/entregado`
   - Health check: `GET /health` (probes VROOM/OSRM)
   - Serves frontend static files at `/app`

2. **Google Apps Script (GAS)** — `backend/Code.gs` provides:
   - `doGet(e)` — `?lastSync=` returns clientes updated since timestamp (camelCase `lastSync`, snake_case `last_sync` alias)
   - `doPost(e)` — accepts JSON array of clientes, upserts by UUID into Google Sheet
   - CORS via "Anyone, even anonymous" Web App deployment (returns `Access-Control-Allow-Origin: *`)

### Frontend API Calls (app.js)

All calls go through `API` variable (resolves to `http://localhost:8000` in dev):

| Endpoint | Method | Purpose | Current Backend |
|----------|--------|---------|-----------------|
| `/clientes` | GET | List clients (pagination, search, zona filter) | FastAPI |
| `/clientes` | POST | Create client | FastAPI |
| `/clientes/{id}` | PATCH | Update client | FastAPI |
| `/clientes/{id}` | DELETE | Soft delete client | FastAPI |
| `/clientes/import` | POST | Import XLSX (multipart) | FastAPI |
| `/clientes/export` | GET | Export XLSX/PDF | FastAPI |
| `/rutas/optimizar` | POST | **VROOM/OSRM route optimization** | FastAPI (Docker) |
| `/rutas/hoy` | GET | Get today's route (with `?entregado=false`) | FastAPI |
| `/rutas/hoy/entregado` | PATCH | Mark clients as delivered | FastAPI |
| `/rutas/hoy` | DELETE | End route (terminar lista) | FastAPI |
| `/health` | GET | Check VROOM/OSRM status | FastAPI |

### syncEngine.js (already GAS-aware)

- Uses `GAS_URL = https://script.google.com/macros/s/AKfycbx.../exec`
- 4-step sync: PUSH pending (sync_status=0) → PULL `?lastSync=` → upsert with sync_status=1 → save `lastSync`
- Heartbeat probes `GAS_URL` for real connectivity (not just `navigator.onLine`)
- Already uses canonical camelCase `lastSync`

### syncQueue.js (offline queue for "marcar entregados")

- Queues PATCH `/rutas/hoy/entregado` payloads in localStorage
- `replayQueue()` currently calls `api + '/rutas/hoy/entregado'` (points to `API` = localhost:8000)
- Uses BackgroundSync API (`gps-post-queue-v1`)
- Optimistic UI: marks `entregado_local=true` immediately

### VROOM/OSRM Dependency (Critical Constraint)

`POST /rutas/optimizar` in `main.py:605-689`:
- Calls `vroom_client.optimize_via_vroom()` → `http://vroom:3000`
- Calls `vroom_client.fetch_geometry()` → `http://osrm:5000`
- **Cannot run in GAS** (no Docker, no native binaries, 30s execution limit)
- Must stay in local Docker or a hosted VROOM/OSRM service

### Netlify Deployment Requirements

- Static site from `frontend/` — no build step
- Environment variable: `GAS_URL` (Web App URL)
- Service worker (`sw.js`) uses Workbox 7 for offline caching
- PWA manifest at `manifest.json`

---

## Affected Areas

| File | Why Affected |
|------|--------------|
| `frontend/app.js` | API routing logic — must split calls between GAS_URL (client CRUD) and local API (VROOM/OSRM + route mgmt) |
| `frontend/syncEngine.js` | Already uses GAS_URL — verify compatibility, may need minor updates |
| `frontend/js/syncQueue.js` | `replayQueue()` points to API for `/rutas/hoy/entregado` — must decide target (GAS or local) |
| `backend/Code.gs` | Needs new `doGet`/`doPost` routes for `/rutas/hoy*`, `/clientes/import`, `/clientes/export` |
| `frontend/sw.js` | Cache rules must handle split origins (GAS + local) |
| `docker-compose.yml` | Local Docker still needed for VROOM/OSRM — document dev/prod split |
| Netlify config (new) | `netlify.toml` for headers, redirects, env vars |

---

## Approaches

### 1. Hybrid Split (Recommended)
**Client CRUD → GAS; Route Optimization → Local Docker; Route Management → GAS**

| Endpoint | Target |
|----------|--------|
| `GET/POST/PATCH/DELETE /clientes` | GAS Web App |
| `POST /clientes/import` | GAS Web App (multipart → base64 → Sheet) |
| `GET /clientes/export` | GAS Web App (generate XLSX/PDF in Apps Script) |
| `POST /rutas/optimizar` | **Local Docker only** (VROOM/OSRM) |
| `GET/PATCH/DELETE /rutas/hoy*` | GAS Web App (Sheet-backed) |
| `GET /health` | Local Docker (probes VROOM/OSRM) |

**Pros:**
- GAS handles all Sheets-backed operations (clients + routes)
- VROOM/OSRM stays local — no architectural change
- syncEngine already works with GAS for clients
- Netlify frontend is truly static + GAS backend

**Cons:**
- GAS must implement route storage in Sheets (new tab `RutasHoy`)
- `/clientes/import` multipart handling in GAS is awkward (base64 workaround)
- `/clientes/export` XLSX/PDF generation in GAS needs Apps Script libraries
- CORS: Netlify origin + localhost must both be allowed by GAS Web App
- Two API bases in frontend: `GAS_URL` and `LOCAL_API_URL`

**Effort:** Medium-High (GAS route endpoints + export/import + frontend routing)

---

### 2. Hybrid Split + Route Management Local
**Client CRUD → GAS; Everything Route-Related → Local Docker**

| Endpoint | Target |
|----------|--------|
| `GET/POST/PATCH/DELETE /clientes` | GAS Web App |
| `POST /clientes/import` | GAS Web App |
| `GET /clientes/export` | GAS Web App |
| **All `/rutas/*`** | **Local Docker** |

**Pros:**
- GAS only does what it's good at: Sheet CRUD sync
- Route logic stays in FastAPI (already tested, complex VROOM integration)
- Simpler GAS code (no route tab needed)

**Cons:**
- **Local Docker required in production** for route management — defeats "serverless GAS/Sheets" goal
- syncQueue already queues `/rutas/hoy/entregado` — would need local Docker online to replay
- Offline "marcar entregados" can't sync without local backend reachable

**Effort:** Medium (less GAS work, but production architecture constraint)

---

### 3. Netlify Functions Proxy for VROOM/OSRM
**All → GAS via Netlify Functions proxying to hosted VROOM/OSRM**

**Pros:**
- Single API origin (GAS Web App)
- Netlify Functions could proxy `/rutas/optimizar` to a hosted VROOM service

**Cons:**
- No hosted VROOM/OSRM service exists (would need to run own)
- Netlify Functions 10s timeout — VROOM can take longer
- Adds complexity, cost, latency
- Still need GAS route endpoints

**Effort:** High (not viable without hosted VROOM)

---

### 4. Full Local Docker Production (Status Quo)
**Keep everything on localhost:8000; Netlify only hosts static files**

**Pros:**
- Zero backend changes
- Works today

**Cons:**
- Not "Netlify + GAS/Sheets deployment" — defeats the task
- Requires Docker host for production
- No Sheets sync for non-dev environments

**Effort:** Low (but doesn't satisfy requirement)

---

## Recommendation

**Approach 1 (Hybrid Split)** with these specifics:

1. **GAS Web App** becomes the primary backend for:
   - Client CRUD (already done)
   - Route storage (`RutasHoy` sheet tab)
   - `marcar entregados` (PATCH /rutas/hoy/entregado)
   - Import/Export (Apps Script can generate XLSX via `Utilities.newBlob` + Sheets API; PDF via `DocumentApp`)

2. **Frontend routing** in `app.js`:
   - New `GAS_API` variable from `import.meta.env.GAS_URL` (Netlify env) or localStorage fallback
   - New `LOCAL_API` variable for VROOM/OSRM (defaults to `http://localhost:8000`, overridden in prod if hosted)
   - Route calls by endpoint category

3. **syncQueue.js** `replayQueue()` → targets `GAS_API + '/rutas/hoy/entregado'`

4. **sw.js** cache rules:
   - GAS GET routes (`/clientes*`, `/rutas/hoy*`) → StaleWhileRevalidate
   - Local POST/PATCH → BackgroundSync (already configured)

5. **CORS**: GAS Web App deployed as "Anyone, even anonymous" → returns `Access-Control-Allow-Origin: *` automatically. Netlify origin works.

6. **Local Dev**: `docker-compose up` for VROOM/OSRM; frontend served by FastAPI at `/app` or `npx serve frontend/` with `LOCAL_API=http://localhost:8000`

---

## Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| GAS 30s execution limit on `/clientes/import` (large XLSX) | Import may timeout | Chunk import; or keep import on local Docker |
| GAS 6 min/day quota for consumer accounts | High-volume sync may hit limits | Use Workspace account; batch sync |
| CORS preflight failures on GAS Web App | Netlify → GAS calls blocked | Deploy as "Anyone, even anonymous"; test `doOptions` |
| VROOM/OSRM local-only in production | Route optimization unavailable on Netlify | Document: optimization only works when local Docker running |
| syncEngine + syncQueue dual sync (clients vs entregados) | Potential race conditions | Sequential: syncEngine runs first, then syncQueue replays |
| GAS `doPost` multipart handling | XLSX import complex | Convert to base64 in frontend, decode in GAS; or keep import local |

---

## Open Questions

1. **Import/Export in GAS**: Should `/clientes/import` and `/clientes/export` move to GAS, or stay on local Docker?
   - GAS can parse XLSX via `Utilities.parseCsv` (CSV only) or Sheets API `batchUpdate` — native XLSX parsing not trivial
   - Recommendation: Keep import/export on local Docker; GAS only for CRUD + routes

2. **Route storage in GAS**: New sheet tab `RutasHoy` with columns: `fecha, orden, cliente_id, entregado, delivered_at`?
   - Yes — mirrors `models.RutasHoy` in FastAPI

3. **Production VROOM/OSRM**: Is there a plan to host VROOM/OSRM, or is local-only acceptable?
   - If local-only, "optimize route" button should show "Requires local backend" when `LOCAL_API` unreachable

4. **Environment detection**: How does frontend know it's on Netlify vs localhost?
   - `location.hostname.includes('netlify.app')` or `import.meta.env.GAS_URL` presence

5. **BackgroundSync tag conflict**: `sw.js` uses `gps-post-queue-v1` for both `/rutas/optimizar` and `/rutas/hoy/entregado` — if one goes to GAS and one to local, need separate queues.

---

## Ready for Proposal

**Yes.** The exploration is complete. The orchestrator should present the Hybrid Split (Approach 1) as the recommended path, with the open questions above for user clarification before writing the proposal.