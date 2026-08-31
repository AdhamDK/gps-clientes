# Architecture — Delta Fixes Audit 2026-08-30

> Branch `feature/gps-clientes-filtro-seleccion` — 7 critical fixes (C1, S1, A1, S3, A3, DOC, TEST) loop 1 + 5 structural improvements loop 2. No overdesign: minimal fixes, backward compatible. 42 tests passing. Full changelog `docs/CHANGELOG_2026-08-30.md`, loop 2 details `docs/LOOP_2_CAMBIOS.md`.

## 1. Bugs Fixed and Why

| ID | Severity | File(s) | Bug | Root Cause | Fix |
|---|---|---|---|---|---|
| **C1** | CRITICAL — blocks deploy | `backend/database.py`, `backend/main.py:34` | `ImportError: cannot import generate_uuid, utc_now_iso` | Functions used in `main.py` never defined in `database.py` (only in `migrate_uuid.py`). App failed to start. | Added `generate_uuid() -> str(uuid.uuid4())` and `utc_now_iso() -> datetime.now(timezone.utc).isoformat()` with `uuid`, `datetime/timezone` imports. Verified `from backend.database import generate_uuid, utc_now_iso` no longer fails. |
| **S1** | Security | `backend/main.py:220-232` | CORS `allow_origins=["*"]` + `allow_origin_regex=file://.*\|null` allowed any origin including `file://` -> exfiltration / CSRF in WebView | Permissive dev config without whitelist | Explicit whitelist `["http://localhost:8000","http://127.0.0.1:8000","https://appassets.androidplatform.net","http://10.0.2.2:8000"]`, `allow_origin_regex=None`, `methods=[GET,POST,PATCH,DELETE,OPTIONS]`, `headers=[Content-Type,Authorization]`. In-code security comment. |
| **A1** | Performance O(N)->O(log N) | `backend/main.py:list_clientes` | Search `q` loaded entire table (`all_rows = base_query.all()`) and filtered in Python (`q_norm in ...`). With 10k rows O(N) + NFD per row. | Dead `_probe` + `matched = [c for c in all_rows ...]` + `len(matched)` without SQL pagination | Replaced with direct SQL: `escaped LIKE + or_(nombre_normalizado LIKE, texto_breve LIKE)` with `escape="\\"`, `query.count()` and `offset/limit` in DB. Uses index `ix_clientes_nombre_normalizado`. `texto_breve` stays direct LIKE (no NFD col) — tradeoff: full NFD on `texto_breve` would need extra normalized column. Removed `_probe`. |
| **S3** | DoS | `backend/main.py:POST /clientes/import` | `openpyxl.load_workbook` unbounded: 100 MB or 1M-row xlsx hangs/OOM | Only extension check `.xlsx` | `MAX_IMPORT_SIZE=5MB`, `MAX_IMPORT_ROWS=5000`. Checks `file.size` then `len(content)>5MB -> 413`, `ws.max_row>5000 -> 400`, blocks clearly invalid Content-Types (`text/*`, `image/*`, `application/json`). |
| **A3** | Sync coherence | `spec/SYNC_SPEC.md.md`, `frontend/syncEngine.js`, `js/syncEngine.js`, `app/src/.../syncEngine.js`, `backend/Code.gs`, `harness/mock_gas.py`, `frontend/app.js` | Spec said `?last_sync` snake_case but frontend used `?lastSync` camelCase + `localStorage 'lastSync'` -> incoherence; GAS accepted both without canonical. | Drift without param normalization | Canonical `lastSync` camelCase across whole chain (frontend, syncEngine, spec, mock). Spec updated with compat note; `Code.gs` prioritizes `lastSync` and keeps `last_sync` as deprecated alias; mock and syncEngine document convention in header. |

## 2. Decisions

### CORS (S1)
- **Why whitelist not `*`**: `allow_origins=["*"]` with `allow_credentials=False` is still risky because WebView serves `https://appassets.androidplatform.net` and dev uses `localhost:8000`. Allowing `file://` opens API to any local file. Explicit whitelist is minimal and auditable.
- **Why `allow_origin_regex=None`**: previous regex `https?://.*|file://.*|null` was equivalent to `*`. Removed entirely.
- **Restricted headers/methods**: only those used by `frontend/app.js` + sync (`Content-Type`, `Authorization`). Avoids exposing unused headers.

### SQL Pagination (A1)
- **Why `LIKE ... escape="\\"`**: `q_norm` may contain `%` and `_`; unescaped they act as SQL wildcards (SQLi-like). Escapes `\`, `%`, `_`.
- **Why `or_(nombre_normalizado, texto_breve)`**: `nombre_normalizado` is NFD with index; `texto_breve` is not, but including it in SQL avoids full Python scan. Full NFD for `texto_breve` would need column `texto_breve_normalizado` + trigram index — rejected as overdesign (roadmap).
- **Why no trigram/GIN**: SQLite has no `pg_trgm`; FTS5 is overkill for 5k-10k rows. `LIKE %term%` with prefix index is not optimal but is O(log N) vs O(N) Python and avoids full transfer; sufficient for current volume. Migrate to FTS5 if >100k.
- **Compatibility**: keeps `normalize_nfd(q)` and `page/limit` contract (`limit 1..500`, see `backend/main.py:329` `le=500`); frontend `PAGINACION_LIMITE=500` respects it.

## 3. Data-Flow Diagram (Text)

```
┌─────────────┐      ┌──────────────────┐      ┌─────────────────┐      ┌──────────────┐
│  Excel .xlsx │─────▶│ POST /clientes/  │─────▶│ normalize_nfd() │─────▶│  SQLite      │
│  20 cols     │      │ import (S3: 5MB/ │      │ nombre_normali- │      │  clientes    │
│  (user)      │      │ 5000 rows guard) │      │ zado, dedup RIF │      │  PK TEXT UUID│
└─────────────┘      └──────────────────┘      └─────────────────┘      └──────┬───────┘
                                                                               │
┌─────────────┐      ┌──────────────────┐      ┌─────────────────┐            │
│  Leaflet    │◀─────│ GET /clientes?   │◀─────│  SQL paginated  │◀───────────┘
│  frontend   │      │ q=&zona=&page    │      │  (A1: LIKE     │
│  app.js     │      │ &limit (<=500)   │      │   escape+or_)  │
│  PAGINAC 500│      │ (A1: count +     │      │   offset/limit │
│  +banner    │      │  offset/limit)  │      └─────────────────┘
└──────┬──────┘      └──────────────────┘              │
       │  offline-first (+ export guard)                │
       │  GET /clientes/export?formato=xlsx|pdf         │
       │  guard: count>5000 -> 413 "filtra por zona/q" │
       │  ┌───────────────────────────────────────────────────────────┐
       │  │ syncEngine.js (A3: canonical lastSync)                    │
       │  │ 1. navigator.onLine? skip : continue                      │
       │  │ 2. PUSH: pending sync_status=0 -> POST array JSON ->      │
       │  │    if {status:"success"} mark sync_status=1               │
       │  │ 3. PULL: GET ?lastSync=<ISO> (localStorage lastSync)      │
       │  │ 4. upsert clientes -> sync_status=1 + save lastSync=now() │
       │  └──────────────┬────────────────────────┬───────────────────┘
       │                 │                        │
       ▼                 ▼                        ▼
┌─────────────┐  ┌──────────────┐      ┌──────────────────┐
│ localForage │  │  GAS Web App │      │  Google Sheet    │
│ IndexedDB   │  │  Code.gs     │◀────▶│  Clientes        │
│ snapshot    │  │  doGet/doPost│      │  HEADERS + LWW   │
│ snapshot_json│  └──────────────┘      └──────────────────┘
└─────────────┘        mock_gas.py (tests)

Rutas / Optimization (atomic — Loop 2):
  POST /rutas/optimizar -> validate GPS fix -> VROOM :3000 (with httpx CLIENT_LIMITS 5/10, timeout 10s, 1 retry)
                        -> OSRM :5000 (geometry) -> [outside TX] -> with db.begin(): DELETE+INSERT atomic
                        -> on VROOM/OSRM failure: 502, no DELETE, previous route preserved (rollback)
  GET /rutas/hoy, PATCH /rutas/hoy/entregado, DELETE /rutas/hoy (409 if pending -> 204)
  CORS: whitelist only (S1): localhost:8000, 127.0.0.1:8000, https://appassets.androidplatform.net, 10.0.2.2:8000
```

- Frontend pagination: `frontend/app.js` `PAGINACION_LIMITE=500` (backend cap `le=500`), banner `Mostrando 500 de X — usa busqueda para filtrar` when `total>500` (`frontend/index.html#paginacionBanner`).
- Export guard: `GET /clientes/export` `count>5000 -> 413` aligns with import `MAX_IMPORT_ROWS=5000`.
- Atomic route transaction preserves previous `rutas_hoy` on external failure; internal INSERT failure rolls back (no partial route).

## 4. Next Steps (Updated Loop 2 — 2026-08-30)

> Loop 2 completed: see `docs/LOOP_2_CAMBIOS.md` for detailed delta. Status:

| Topic | Risk if Skipped | Minimal Proposal | Loop 2 Status |
|---|---|---|---|
| **PK UUID Room (Android)** | `ClienteEntity.kt` still `autoGenerate Int` vs backend `TEXT UUID`; fragile migrations, duplicates on offline sync. | Migrate Room to `id TEXT PK` UUIDv4, remove `autoGenerate`, add `TypeConverter` UUID + `MIGRATION_2_3` mapping `CAST(id AS TEXT)` same as backend `migrate_uuid.py`. | **PENDING** — documented in `LOOP_2_CAMBIOS.md` §Pending with breaking-change trade-offs. Not auto-applied. |
| **Atomic route transaction** | `db.query(RutasHoy).delete()` + loop `db.add` without single transaction -> partial route if mid-loop fails (lost clients). | Wrap in `with db.begin()` and close implicit read TX first; keep VROOM/OSRM outside TX. Add `UNIQUE(fecha, orden)` and `UNIQUE(fecha, cliente_id)` optionally. | **DONE Loop 2**: `POST /rutas/optimizar` now: validate -> VROOM/OSRM outside TX -> `if db.in_transaction(): db.commit()` -> `with db.begin(): DELETE+INSERT` atomic. On VROOM failure prior route not deleted; rollback preserves route. See `backend/main.py:694-714`. |
| **Auth** | CORS whitelist is not auth; anyone with URL can `POST /clientes` and `DELETE /rutas/hoy`. | Add API key header (`X-API-Key`) via middleware (env var), or JWT if login. Rate-limit `/clientes/import` (slowapi). | **PENDING** — documented with trade-offs in `LOOP_2_CAMBIOS.md`. |
| **FTS / NFD texto_breve** | `texto_breve LIKE %term%` not accent-insensitive, no index -> slow if large. | Add column `texto_breve_normalizado` via trigger `normalize_nfd` + index, or FTS5 if >50k rows. | **PENDING** — deferred (current <10k, LIKE sufficient). |
| **Import observability** | Silent `except: pass` hid failures | Log `file.size`, `ws.max_row`, `imported/skipped/duplicates` with `logger.warning(..., exc_info=True)`; expose `GET /metrics` for Prometheus if deployed. | **PARTIAL Loop 2**: `backend/main.py` now `logger.warning` on 14 `except: pass` sites (migrations, VROOM parse, bounds, import size). Behavior unchanged, observability added. |
| **Export OOM guard** | `GET /clientes/export` loaded `all()` unbounded -> OOM on large datasets | `count > 5000 -> 413` with zone/q hint | **DONE Loop 2**: `backend/main.py` `export_clientes` guard `EXPORT_MAX_ROWS=5000 -> 413`. |
| **VROOM pooling** | `httpx.Client` per request without limits | `Limits(max_connections=10)` + TODO lifespan pool | **DONE Loop 2**: `backend/vroom_client.py` `CLIENT_LIMITS` on all clients + TODO for future `lifespan` pool. |
| **Frontend pagination UX** | `fetchClientes limit=100` silent truncation of 409 fixture | `PAGINACION_LIMITE=500` + banner `Mostrando 500 de X — usa busqueda` | **DONE Loop 2**: `frontend/app.js` + `frontend/index.html` banner. |

## 5. Manual Verification (Checklist)

- [ ] `python -c "from backend.database import generate_uuid, utc_now_iso; print(generate_uuid()); print(utc_now_iso())"` no `ImportError`.
- [ ] `pytest backend/tests -q` — 42 tests (38 loop1 + 4 loop2) passing.
- [ ] `curl -H "Origin: https://evil.com" http://localhost:8000/clientes` -> no `Access-Control-Allow-Origin: *` (only whitelist).
- [ ] `curl -H "Origin: http://localhost:8000" http://localhost:8000/clientes` -> `Access-Control-Allow-Origin: http://localhost:8000`.
- [ ] `curl -H "Origin: https://appassets.androidplatform.net" http://localhost:8000/clientes -v` -> allowed (WebView).
- [ ] `GET /clientes?q=vigia&limit=2&page=1` -> `total` correct and SQL paginated (`SELECT COUNT(*) ... LIKE`).
- [ ] `POST /clientes/import` with 6 MB xlsx -> `413 File too large`; with 6000 rows -> `400 Too many rows`.
- [ ] `GET /clientes/export` with >5000 rows -> `413 Demasiados registros — filtra por zona/q` (guard).
- [ ] `POST /rutas/optimizar` with VROOM down -> `502` and prior `GET /rutas/hoy` still returns previous 3 rows (atomicity).
- [ ] `GET ?lastSync=...` and `?last_sync=...` both work but canonical is `lastSync` (`spec/SYNC_SPEC.md.md`).
- [ ] Frontend: `PAGINACION_LIMITE=500` in `frontend/app.js`; banner `#paginacionBanner` visible when `total>500`.

## 6. Files Modified (This Delta — Loops 1+2)

- `backend/database.py` — C1 (`generate_uuid`, `utc_now_iso`)
- `backend/main.py` — S1 CORS whitelist, A1 SQL pagination, S3 import limits, Loop2 atomic transaction + `logger.warning` + export guard 413
- `backend/vroom_client.py` — Loop2 `CLIENT_LIMITS` + comments (pool TODO)
- `frontend/app.js` — Loop2 `PAGINACION_LIMITE=500` + banner logic; header updated
- `frontend/index.html` — Loop2 `#paginacionBanner` (`aria-live`) + style
- `spec/SYNC_SPEC.md.md` — A3 `lastSync` canonical + deprecated alias note
- `frontend/syncEngine.js`, `js/syncEngine.js`, `app/src/main/assets/www/syncEngine.js` — A3 header
- `backend/Code.gs` — A3 (`lastSync` canonical + alias)
- `harness/mock_gas.py` — A3 canonical comment
- `backend/tests/test_regressions_20260830.py` — Loop1 regression tests
- `backend/tests/test_loop2_transaccion.py` — Loop2 atomicity/export/pagination tests (3+1)
- `docs/ARQUITECTURA_FIXES_2026-08-30.md` — this file (loops 1+2 consolidated)
- `docs/LOOP_2_CAMBIOS.md`, `docs/CHANGELOG_2026-08-30.md`, `docs/e2e/gps-unificado-parity.md` — loop2 docs updates
- `docs/README.md`, `README.md` — delivery docs (pagination 500, endpoints, pending, backup)

_Remaining risks: see Section 4. No breaking change for existing tests; changes are additive and compatible. Backup `GPS_CLIENTES_backup_20260830_142728.zip` in Documents._
