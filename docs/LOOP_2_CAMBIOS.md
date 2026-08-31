# Loop 2 — Structural Changes 2026-08-30 (Second Pass)

> First pass fixed C1/S1/A1/S3/A3 (38 tests). Second pass addresses remaining debt **without breaking compatibility**. Now 42 tests passing (38 + 4 loop 2).

## Files Touched and Why

| File | Change | Reason |
|---|---|---|
| `backend/main.py` | **Atomic transaction `POST /rutas/optimizar`** — wrap `DELETE + INSERT` in `with db.begin()` atomic, closing implicit read TX first and calling VROOM/OSRM outside TX | Avoids race/loss: if VROOM/OSRM fails prior route is not deleted; if INSERT fails mid-loop rollback leaves no partial route. Order: validate clients -> call VROOM (outside TX) -> fetch geometry -> TX DELETE+INSERT |
| `backend/main.py` | **Logging instead of silent `except: pass`** — added `import logging; logger = logging.getLogger(__name__)` and 14× `except Exception as e: logger.warning(..., exc_info=True)` | Observability: legacy migrations, VROOM parse, bounds, import size check now log without changing behavior (still fallback) |
| `backend/main.py` | **Export guard `GET /clientes/export`** — `count > 5000 -> 413 "Demasiados registros — filtra por zona/q"` documented in docstring | Avoids OOM: previously `all()` loaded everything into RAM; now rejects huge exports and guides client to filter |
| `backend/vroom_client.py` | **Connection pooling** — `httpx.Limits(max_keepalive_connections=5, max_connections=10)` + `CLIENT_LIMITS` on all `httpx.Client(timeout=10, limits=...)` + TODO comment for lifespan pool | Socket reuse; lower latency and documents next optimization (single shared Client via FastAPI lifespan) |
| `frontend/app.js` | **Pagination UX** — `const PAGINACION_LIMITE = 500` (backend cap `le=500` per `backend/main.py:329`), `params limit=500`, banner `Mostrando 500 de X — usa busqueda para filtrar` when `total > limit` | Previously `limit=100` silently truncated 409 fixture; now covers full dataset (500 > 409) and warns when more data exists without infinite scroll |
| `frontend/index.html` | Banner `<div id="paginacionBanner">` + style + `aria-live="polite"` | Visual support for pagination notice |
| `docs/ARQUITECTURA_FIXES_2026-08-30.md` | Section 4 updated (transaction, export guard, logging, VROOM pooling, pagination) + data-flow diagram refreshed | Traceability |
| `backend/tests/test_loop2_transaccion.py` | Loop 2 regression tests (4: atomic VROOM fail, atomic OSRM fail, export guard 5000, frontend constant) | Verifies atomicity, export guard, pagination constant |

## Decisions

- **Why `PAGINACION_LIMITE = 500` and not 1000**: backend caps `limit` at `le=500` (`GET /clientes` Pydantic). Frontend uses 500 to respect that contract. 500 > 409 fixture covers real case without unnecessary infinite scroll. If backend is raised to 1000, just change constant + backend `le`. Doc fix: previous comment saying "backend cap 1000" was inaccurate — corrected to 500.
- **Why banner not infinite scroll**: simple and direct; avoids incremental pagination complexity on map (markers + memo). Trade-off: user must filter via search when `total>500`; documented.
- **Why `db.in_transaction()` + `db.commit()` before `with db.begin()`**: SQLAlchemy auto-begins on first SELECT. `with db.begin()` inside active TX raises `InvalidRequestError`. The read TX is explicitly closed to start a clean write TX. Alternative `begin_nested()` (savepoint) works but adds complexity; pre-commit is minimal.
- **Why `logger.warning` not `logger.error`**: legacy migrations and VROOM parse fallbacks are recoverable (fallback to input order, `pass`); `warning` avoids error noise and keeps prior behavior (no raise).
- **Why export guard 5000**: matches `MAX_IMPORT_ROWS=5000` (S3 DoS mitigation) — coherent limits. 5000 rows x 19 cols in xlsx/pdf is heavy (>5 MB). For more, export should be paginated streaming (`yield` per chunk `limit=1000 offset`) or background job.
- **Why `httpx.Limits` not global pool yet**: global pool requires `lifespan` of FastAPI and `transport` handling in tests (`MockTransport`). Switching to global now would break tests that patch `httpx.Client`. Per-request limits are a safe incremental improvement; TODO documented.

## Pending (Non-Blocking — Requires Product Decision)

| Topic | Risk if Skipped | Minimal Proposal | Trade-offs / Breaking? |
|---|---|---|---|
| **PK Room UUID** (`ClienteEntity.kt` `autoGenerate Int` vs backend `TEXT UUID`) | Fragile migrations, offline duplicates, `CAST(id AS TEXT)` already on backend but Room still Int | Migrate Room to `id: String PK UUIDv4`, remove `autoGenerate`, add `TypeConverter`, `MIGRATION_2_3` mapping `CAST(id AS TEXT)` same as backend `migrate_uuid.py` | **Breaking**: requires Room migration, reinstall or auto-migration; sync must map UUID; test 409 fixture |
| **Auth** | CORS whitelist is not auth; anyone with URL can `POST /clientes`, `DELETE /rutas/hoy` | Add `X-API-Key` middleware (env var) or JWT if login; `slowapi` rate-limit `/clientes/import` | Non-breaking if header optional with default allow in dev; prod requires env `API_KEY` |
| **FTS / NFD texto_breve** | `texto_breve LIKE %term%` not accent-insensitive, no index -> slow >50k | Column `texto_breve_normalizado` + trigger `normalize_nfd` + index, or FTS5 if >50k | Non-breaking; requires `ALTER TABLE ADD COLUMN` + backfill trigger; benchmark LIKE vs FTS |
| **Export streaming paginated** | Guard 413 rejects >5000; legitimate 10k client cannot export all | Implement `StreamingResponse` generator paginated (`yield` per chunks `limit=1000 offset`) or async job + link | Non-breaking; O(N)->O(1) memory but needs loop pagination |
| **VROOM global lifespan pool** | Per-request `httpx.Client` churn | Shared `httpx.Client` via FastAPI `lifespan` | Needs test transport handling |

Consistent with `docs/ARQUITECTURA_FIXES_2026-08-30.md` section 4 and `README.md` pending table. No contradictions; loop 2 diagram already updated.

## Verification

- `python -m pytest backend/tests -q` -> 42 passed (38 + 4 loop2)
- No change to `docker-compose.yml` -> no Docker break
- Compat: `PAGINACION_LIMITE` respects `le=500`; export 413 only for >5000 (fixture 409 still 200); VROOM limits backward compat (`MockTransport` still works); logging does not change semantics
- Frontend comment corrected: was "backend cap 1000", now "backend cap 500" (aligns with `backend/main.py:329`)

## Next Steps

1. PK Room UUID — spike migration on separate branch with `ClienteDaoInstrumentedTest` + `ImportInstrumentedTest`.
2. Auth — spike `X-API-Key` middleware + `docker-compose` env.
3. FTS — evaluate real volume (if <10k, defer; current LIKE is sufficient).
