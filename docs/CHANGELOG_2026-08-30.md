# Changelog — 2026-08-30 (Loops 1 & 2)

> Branch `feature/gps-clientes-filtro-seleccion`. 42 tests passing (`pytest backend/tests -q`). Docs finalized per delivery.

## Loop 1 — Delta Fixes Audit (C1, S1, A1, S3, A3)

- **C1 — backend/database.py**: Added `generate_uuid()` (`uuid.uuid4()`) and `utc_now_iso()` (`datetime.now(timezone.utc).isoformat()`) with imports. Fixes `ImportError` that blocked deploy (`backend/main.py:34`).
- **S1 — backend/main.py CORS**: Replaced `allow_origins=["*"]` + `allow_origin_regex=file://.*|null` with explicit whitelist `http://localhost:8000`, `http://127.0.0.1:8000`, `https://appassets.androidplatform.net`, `http://10.0.2.2:8000`; `allow_origin_regex=None`, `allow_methods=[GET,POST,PATCH,DELETE,OPTIONS]`, `allow_headers=[Content-Type,Authorization]`. Auditable, WebView-compatible.
- **A1 — backend/main.py `GET /clientes` search**: Replaced Python full-scan (`all()` + list filter O(N) + dead `_probe`) with SQL `or_(nombre_normalizado LIKE, texto_breve LIKE)` with `escape="\\"`, `query.count()` + `offset/limit` in DB. Uses `ix_clientes_nombre_normalizado`. `texto_breve` stays direct LIKE (needs `texto_breve_normalizado` col for full NFD — deferred).
- **S3 — backend/main.py `POST /clientes/import`**: Added `MAX_IMPORT_SIZE=5MB`, `MAX_IMPORT_ROWS=5000`, `file.size` + `len(content)` checks (`413` / `400`), `ws.max_row` guard, Content-Type blocklist (`text/*`, `image/*`, `application/json`).
- **A3 — `lastSync` canonical**: `spec/SYNC_SPEC.md.md`, `frontend/syncEngine.js`, `js/syncEngine.js`, `app/src/main/assets/www/syncEngine.js`, `backend/Code.gs`, `harness/mock_gas.py` now use camelCase `lastSync`; `last_sync` kept as deprecated alias in GAS/mock. Spec updated with compat note.
- **Tests — backend/tests/test_regressions_20260830.py**: New regression covering imports, CORS, LIKE pagination, import guards.

## Loop 2 — Structural Improvements (Atomic TX, Guards, Pooling, Pagination UX)

- **backend/main.py — atomic `POST /rutas/optimizar`**: VROOM/OSRM calls outside transaction; `if db.in_transaction(): db.commit()` then `with db.begin(): DELETE where fecha=today; INSERT ordered`. On VROOM/OSRM `502` prior route preserved; on mid-INSERT failure rollback leaves no partial route.
- **backend/main.py — logging**: Replaced 14 silent `except: pass` with `logger.warning(..., exc_info=True)` (migrations, VROOM parse, bounds, import size). Behavior unchanged, observability added.
- **backend/main.py — export guard `GET /clientes/export`**: `count>5000 -> 413 "Demasiados registros — filtra por zona/q"`, docstring updated. Coherent with import `5000` cap.
- **backend/vroom_client.py — pooling**: `CLIENT_LIMITS = httpx.Limits(max_keepalive_connections=5, max_connections=10)` on all `httpx.Client(timeout=10, limits=...)`; future TODO `lifespan` shared client.
- **frontend/app.js — pagination UX**: `PAGINACION_LIMITE=500` (was `100`, silently truncated 409 fixture), `params limit=500`, `total>limit` banner via `#paginacionBanner` (`role=status aria-live`).
- **frontend/index.html**: Added `#paginacionBanner` div + style.
- **Tests — backend/tests/test_loop2_transaccion.py**: 4 tests — `test_optimize_atomic_delete_not_before_vroom`, `test_optimize_atomic_osrm_fail_preserves_route`, `test_export_guard_large`, `test_paginacion_limite_const_exists`.
- **Docs sync**: `docs/ARQUITECTURA_FIXES_2026-08-30.md` section 4 + data-flow diagram refreshed; `docs/LOOP_2_CAMBIOS.md` structural record; `docs/e2e/gps-unificado-parity.md` pagination 500 + CORS whitelist rows added; headers in `backend/main.py` + `frontend/app.js` updated.

## Docs/Readme (Delivery — This Pass)

- Added/updated `README.md` (root) — system description, stack/requirements (Java 17, Python 3.11, Docker, Node none), `docker compose up` + `uvicorn backend.main:app` (+ `/app` static) + `gradlew assembleDebug`, endpoints table, sync `lastSync` flow, pagination/limits, tests `pytest backend/tests -q` (42) + `gradlew testDebugUnitTest`, WebView 0-divergence + loop1/2 decisions, pending table (PK Room UUID, auth, FTS, streaming) with trade-offs, backup `GPS_CLIENTES_backup_20260830_142728.zip`.
- Created `docs/README.md` — docs index pointing to root README.
- Updated headers: `backend/main.py` (offline-first context, endpoints, UUID PK) and `frontend/app.js` (WebView mirror, pagination 500, localforage/syncEngine).

## Verification

- `pytest backend/tests -q` -> 42 passed, 5034 warnings (openpyxl/reportlab deprecation only).
- No breaking change: pagination `le=500` respected, export guard only >5000, VROOM limits compat with `MockTransport`, logging non-semantic.
- Backup: `C:\Users\Usuario\Documents\GPS_CLIENTES_backup_20260830_142728.zip` (95 MB, 2026-08-30 14:27).

## Pending (Non-Blocking)

- Room PK `autoGenerate Int` -> `TEXT UUID` migration (`MIGRATION_2_3`), auth (`X-API-Key`), FTS5/`texto_breve_normalizado`, export streaming paginated, VROOM lifespan pool. See `README.md` / `LOOP_2_CAMBIOS.md` pending tables.
