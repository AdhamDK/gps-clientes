# GPS_CLIENTES — Offline-First Route Management for El Vigia

> Branch `feature/gps-clientes-filtro-seleccion` — 42 tests passing (2 fix loops). Backup `GPS_CLIENTES_backup_20260830_142728.zip` in `Documents`.

Offline-first system for field sales routing in El Vigia (Venezuela): Android app (Kotlin + Compose + Room) with WebView mirror of the Leaflet frontend, backed by FastAPI + SQLite, with VROOM (route optimization) and OSRM (geometry) via Docker.

## Stack

| Layer | Tech |
|---|---|
| Mobile | Android (Kotlin 1.9.22, Compose, Room, Hilt, KSP), `compileSdk 34`, WebViewAssetLoader `https://appassets.androidplatform.net` |
| Frontend | Vanilla JS + Leaflet + `localforage` (IndexedDB) — `frontend/app.js:500` pagination, offline snapshot, syncEngine `lastSync` |
| Backend | Python 3.11, FastAPI 0.11, SQLAlchemy, SQLite (`backend/clientes.db`), Pydantic v2, openpyxl, reportlab |
| Routing | OSRM `ghcr.io/project-osrm/osrm-backend:v5.27.1` on `:5000`, VROOM `vroomvrp/vroom-docker:v1.13.0` on `:3000` |
| Sync | Google Apps Script Web App (`backend/Code.gs`) + Google Sheet (LWW `updated_at`) |

### Requirements

- **Python 3.11** (`pip install -r backend/requirements.txt` or `pip install fastapi sqlalchemy httpx openpyxl reportlab`)
- **Java 17** (Temurin 17) + **Android SDK 34** + **Gradle 8.6** (wrapper `gradle-wrapper.jar` not committed — see `docs/CI_VERIFICATION.md`; stub `gradlew` exists)
- **Node** — not required (no frontend build step; vanilla JS served as static files)
- **Docker / Docker Compose** for OSRM + VROOM (optional — backend degrades to haversine fallback if down)
- Venezuela PBF for OSRM: `osrm-data/venezuela-latest.osm.pbf` (see `docker-compose.yml` comments)

## Quick Start

### 1. Routing services (Docker)

```bash
# Download PBF once (see docker-compose.yml for PowerShell/curl variants):
# Invoke-WebRequest -Uri https://download.geofabrik.de/south-america/venezuela-latest.osm.pbf \
#   -OutFile osrm-data/venezuela-latest.osm.pbf

docker compose up -d          # starts osrm :5000 and vroom :3000
curl http://localhost:5000/route/v1/driving/-71.65,8.61;-71.66,8.62?overview=full
curl http://localhost:3000/health
```

### 2. Backend API

```bash
# from repo root, with venv active:
uvicorn backend.main:app --reload --port 8000
# Frontend is auto-mounted at http://localhost:8000/app  (backend/main.py: StaticFiles)
# API root: http://localhost:8000
# Health:
curl http://localhost:8000/health
```

Alternative (dev without Docker): VROOM/OSRM health returns `down` and `POST /rutas/optimizar` still works with fallback ordering.

### 3. Frontend (served via FastAPI static — no separate server needed)

Open `http://localhost:8000/app` — Leaflet map at `[8.61, -71.65]`, selection via checkboxes (persists across pagination), FAB `Mi ubicacion`, export/import dropdown.

Standalone static (no backend):

```bash
cd frontend && python -m http.server 8000  # then open http://localhost:8000
# Note: API calls will 404 without backend; map + offline snapshot still work.
```

### 4. Android APK

```bash
# Requires JDK 17 + SDK 34 + wrapper jar (see docs/CI_VERIFICATION.md: `gradle wrapper --gradle-version 8.6`)
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk

# No SDK fallback:
python harness_gps2.py           # 95/95 structural PASS (import, normalize, geocoding, map, perms, export)
```

## Key Endpoints

| Method | Path | Notes |
|---|---|---|
| `POST` | `/clientes` | Create (UUID auto-generated, `nombre_normalizado` NFD, `has_gps_fix` derived) |
| `GET` | `/clientes?q=&zona=&page=&limit=&include_deleted=` | Paginated search — `q` NFD `LIKE escape` on `nombre_normalizado`/`texto_breve`, `zona` exact, `page>=1`, `limit 1..500` (default 20). Returns `{items, total, page, limit}` |
| `GET` | `/clientes/{id}` | 404 if soft-deleted |
| `PATCH` | `/clientes/{id}` | Partial update, bumps `updated_at`, `sync_status=0` |
| `DELETE` | `/clientes/{id}` | Soft delete (`deleted=1`) |
| `POST` | `/clientes/import` | Multipart `.xlsx` (column B=nombre, E=RIF, etc.). Guards: `5 MB` / `5000 rows` / Content-Type check. Dedup by RIF upper + `nombre_normalizado`, RIF regex `^[JVEGP]\\d{7,9}$` |
| `GET` | `/clientes/export?formato=xlsx|pdf&columnas=` | Streaming export, whitelist (`EXPORT_WHITELIST`), guard `count>5000 -> 413` with hint to filter |
| `POST` | `/rutas/optimizar` | Body `{cliente_ids: string[], start?: [lng,lat]}`. Validates GPS fix, calls VROOM then OSRM, persists atomic `DELETE+INSERT` for `fecha=today`. Returns `{orden, distance, duration, geometry}` |
| `GET` | `/rutas/hoy?fecha=&entregado=` | Ordered route with `cliente` joined; filter by delivered |
| `PATCH` | `/rutas/hoy/entregado` | Body `{cliente_ids: string[]}` marks `entregado=true`, `delivered_at=now` |
| `DELETE` | `/rutas/hoy` (alias `/rutas/hoy/terminar`) | `409` if pending, else deletes today |
| `GET` | `/health` | `{status, vroom: up|down, osrm: up|down}` |

### Sync Flow (GAS `lastSync`)

Canonical param is `lastSync` (camelCase) — alias `last_sync` deprecated but accepted in `backend/Code.gs` and `harness/mock_gas.py`.

```
syncEngine.js (offline-first):
 1. if (!navigator.onLine) skip
 2. PUSH: pending sync_status=0 -> POST array JSON -> on {status:"success"} mark 1
 3. PULL: GET ?lastSync=<ISO> (from localStorage lastSync)
 4. upsert clientes -> sync_status=1 + save lastSync=now()
        |
        v
  GAS Code.gs  <->  Google Sheet (HEADERS + LWW on updated_at)
              mock_gas.py (tests)
```
Spec: `spec/SYNC_SPEC.md.md`. Conflict: Last-Write-Wins on `updated_at` ISO UTC.

### Pagination & Limits

- **List**: `GET /clientes` paginated `page/limit` in SQL (`COUNT + OFFSET/LIMIT`), `LIKE escaped` on indexed `nombre_normalizado`. `limit` capped `1..500` (Pydantic `le=500`); frontend constant `PAGINACION_LIMITE=500` (see `frontend/app.js:6`, banner in `frontend/index.html:76`). Banner `Mostrando 500 de X — usa busqueda para filtrar` when `total>500`.
- **Import**: `MAX_IMPORT_SIZE=5 MB`, `MAX_IMPORT_ROWS=5000` -> `413` / `400` respectively (DoS mitigation). Whitelist Content-Type, invalid types `400`.
- **Export**: `count>5000 -> 413` (`Demasiados registros — filtra por zona/q`). Coherent with import cap; for larger exports needs streaming paginated `StreamingResponse` or background job (see Pending).

## Tests

```bash
# Backend — 42 tests (loop1 38 + loop2 4)
pytest backend/tests -q

# Detail
pytest backend/tests/test_crud_search.py backend/tests/test_import_export.py \
       backend/tests/test_optimize_health.py backend/tests/test_regressions_20260830.py \
       backend/tests/test_loop2_transaccion.py backend/tests/test_entregados.py -q

# Android unit (requires JDK17+SDK34 + wrapper jar)
./gradlew testDebugUnitTest

# Python harness fallback (no SDK)
python harness_gps2.py   # 95/95 PASS
```

## Architecture

### WebView Mirror — 0 Divergence

`DESIGN_WEBVIEW_WRAPPER.md` — single HTML/JS bundle (`frontend/`) runs in browser and in APK via `WebViewAssetLoader` at `https://appassets.androidplatform.net/assets/www/index.html` (`androidx.webkit:webkit:1.9.0`). Gradle copies `frontend/` -> `app/src/main/assets/www`. `frontend/app.js` sniffs `file://`/`appassets`/`capacitor://` -> `http://10.0.2.2:8000` for emulator, `location.origin` for WebView. Feature flag `BuildConfig.ENABLE_WEBVIEW=false` keeps Compose fallback, no DB migration required for rollback.

### Fix Decisions (Loop 1 & Loop 2)

| ID | Issue | Fix |
|---|---|---|
| **C1** | `ImportError generate_uuid, utc_now_iso` | Added to `backend/database.py` (`uuid4` + `utc iso`) |
| **S1** | CORS `allow_origins=["*"]` + `file://.*` regex = open | Whitelist `localhost:8000`, `127.0.0.1:8000`, `https://appassets.androidplatform.net`, `http://10.0.2.2:8000`; `allow_origin_regex=None`; methods `GET,POST,PATCH,DELETE,OPTIONS`, headers `Content-Type,Authorization` |
| **A1** | `GET /clientes?q` loaded all rows and filtered in Python O(N) | SQL `LIKE escape` + `or_(nombre_normalizado, texto_breve)` + `COUNT + OFFSET/LIMIT`; indexed `nombre_normalizado` |
| **S3** | `POST /clientes/import` unbounded xlsx | `MAX_IMPORT_SIZE 5MB` + `MAX_IMPORT_ROWS 5000`, Content-Type guard, `413/400` |
| **A3** | `?last_sync` vs `?lastSync` incoherence | Canonical `lastSync` across spec/frontend/GAS/mock |
| **Atomic route** | `DELETE+INSERT` not transactional -> partial route on failure | `with db.begin()` after closing implicit read TX (`db.in_transaction()->commit`), VROOM/OSRM outside TX so prior route preserved on external failure |
| **Export OOM** | `GET /clientes/export` did `all()` | `count>5000 -> 413` guard, message to filter |
| **VROOM pooling** | `httpx.Client` per request no limits | `CLIENT_LIMITS max_keepalive 5 / max_connections 10`, TODO lifespan shared client |
| **Pagination UX** | `limit=100` silent truncation (fixture 409) | `PAGINACION_LIMITE=500` + `aria-live` banner when `total>limit` |

Full rationale: `docs/ARQUITECTURA_FIXES_2026-08-30.md` + `docs/LOOP_2_CAMBIOS.md`. Data-flow diagram updated there (Excel -> import guard -> NFD -> SQLite -> SQL paginated -> Leaflet + localForage/IndexedDB <-> syncEngine lastSync <-> GAS/Sheet; VROOM->OSRM->rutas_hoy atomic).

## Pending (Non-Blocking — with Trade-offs)

| Topic | Risk if Skipped | Proposal | Trade-off |
|---|---|---|---|
| **PK Room UUID** (`ClienteEntity.kt` `autoGenerate Int` vs backend `TEXT UUID`) | Fragile migrations, duplicates offline, backend `CAST(id AS TEXT)` already done | Migrate Room to `id TEXT PK UUIDv4`, `MIGRATION_2_3`, `TypeConverter` | Breaking: requires Room migration + reinstall or auto-migration test; sync UUID mapping |
| **Auth** | CORS whitelist is not auth; anyone can `POST /clientes`, `DELETE /rutas/hoy` | `X-API-Key` middleware (env var), optional header in dev, `slowapi` rate-limit on `/import` | Non-breaking if optional in dev; prod enforces `API_KEY` env |
| **FTS / NFD texto_breve** | `texto_breve LIKE %term%` not accent-insensitive, no index; slow >50k | Add `texto_breve_normalizado` column + trigger `normalize_nfd` + index, or FTS5 if >50k | Non-breaking; `ALTER TABLE ADD COLUMN` + backfill trigger; benchmark first |
| **Export streaming** | Guard rejects legitimate >5k exports | `StreamingResponse` generator paginated `limit/offset` loop or async job + link | Non-breaking; O(N)->O(1) memory; requires loop pagination |
| **Observability** | Silent `except: pass` hid failures | Add `logger.warning(..., exc_info=True)` (done Loop2 for 14 sites), add `/metrics` Prometheus if deployed | Partially done |
| **VROOM global pool** | Per-request `httpx.Client` socket churn | Single `httpx.Client` via FastAPI `lifespan` | Requires `MockTransport` handling in tests |

## Docs & Related Files

- `docs/ARQUITECTURA_FIXES_2026-08-30.md` — delta fixes C1/S1/A1/S3/A3 + loop2 appendix, flow diagram
- `docs/LOOP_2_CAMBIOS.md` — transactional, logging, export guard, pooling, pagination UX details
- `docs/CHANGELOG_2026-08-30.md` — bullet changelog loops 1+2
- `docs/e2e/gps-unificado-parity.md` — E2E parity checklist (zoom 16, FAB, clusters, themes, **plus** pagination 500 / CORS whitelist — Loop2 updated)
- `docs/DESIGN_WEBVIEW_WRAPPER.md` — WebView 0-divergence design + rollback flag
- `docs/CI_VERIFICATION.md` — JDK17/SDK34 requirements, wrapper jar, `harness_gps2.py` fallback
- `spec/SYNC_SPEC.md.md` — GAS sync protocol (`lastSync` canonical)
- `docker-compose.yml` — OSRM/VROOM stack + PBF download notes
- `backend/main.py`, `frontend/app.js` — headers updated (see below)

## Backup & Delivery

Archive for this delivery: `C:\Users\Usuario\Documents\GPS_CLIENTES_backup_20260830_142728.zip` (95 MB) — full repo snapshot at 2026-08-30 14:27 UTC-4. Keep alongside APKs (`GPS_CLIENTES_FIX_3.apk`, `GPS_CLIENTES_WEBVIEW_FINAL.apk`) in `Documents`.
