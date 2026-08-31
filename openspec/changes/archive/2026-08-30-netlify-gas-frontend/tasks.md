# Tasks: netlify-gas-frontend

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | 320–380 |
| 400-line budget risk | Low |
| Chained PRs recommended | No |
| Suggested split | Single PR (commits A → B → C) |
| Delivery strategy | single-pr |
| Chain strategy | pending |

Decision needed before apply: No
Chained PRs recommended: No
Chain strategy: pending
400-line budget risk: Low

### Suggested Work Units

| Unit | Goal | Likely PR | Focused test command | Runtime harness | Rollback boundary |
|------|------|-----------|----------------------|-----------------|-------------------|
| A | GAS dispatcher + RutasHoy sheet | PR 1 | `python -m pytest -q` | Deploy GAS "Anyone, even anonymous" → GET `?action=rutas_hoy` 200+CORS | Revert `backend/Code.gs`; delete `RutasHoy`/`SyncLog` tabs |
| B | Frontend config + apiClient + routing-client + app.js | PR 1 | `python -m pytest -q` | `docker-compose up` → gasFetch GAS, localFetch VROOM; local down → fallback banner | Revert `frontend/js/config.js`, `frontend/js/apiClient.js`, `frontend/js/routing-client.js`, `frontend/app.js` |
| C | Sync queue + SW + Netlify config | PR 1 | `python -m pytest -q` | Offline entregado queued → online `gps-gas-sync` replay; SW install caches | Revert `frontend/js/syncQueue.js`, `frontend/syncEngine.js`, `frontend/sw.js`, `frontend/netlify.toml`, `frontend/manifest.json`, `frontend/index.html` |

## Phase 1: GAS Backend

- [x] 1.1 Add `doGet`/`doPost` dispatcher + `withCors` to `backend/Code.gs` (~40 lines) — `action` routing, `Access-Control-Allow-Origin: *` [gas-routes-api: CORS] — verify: `python -m pytest -q`
- [x] 1.2 Implement `handleRutasHoy`/`handleEntregado` + `RutasHoy`/`SyncLog` tabs in `backend/Code.gs` (~50 lines) — ordered `orden`, idempotent entregado, invalid date 400 [gas-routes-api: GET /rutas/hoy, PATCH /rutas/hoy/entregado]
- [x] 1.3 Extend `handleClientes`/`handleSync` for `action` routing + Netlify CORS in `backend/Code.gs` (~20 lines) [gas-routes-api: GET /clientes, POST /clientes/sync; client-sync: PUSH/PULL] — verify: `python -m pytest -q`

## Phase 2: Frontend Config & API Split

- [x] 2.1 Create `frontend/js/config.js` (~30 lines) + `frontend/js/apiClient.js` (~50 lines) — `GAS_URL` env→localStorage→default, `LOCAL_API` default `http://localhost:8000`, `gasFetch`/`localFetch` [api-split-routing: Env Detection, LOCAL_API, Dual-API, Abstraction]
- [x] 2.2 Create `frontend/js/routing-client.js` (~90 lines) — `haversine`, `nearestNeighbor` tie-break `id`, `twoOpt`, exclude `has_gps_fix==false` [route-optimization: Fallback, Correctness]
- [x] 2.3 Modify `frontend/app.js` (~40 lines) — `gasFetch` for clientes/rutas/entregado, `localFetch` 30s timeout for optimizar with fallback banner [route-optimization: Optimize, Fallback, Button State]

## Phase 3: Sync & Offline

- [x] 3.1 Retarget `frontend/js/syncQueue.js` (~40 lines) — `gasFetch('entregado')`, tag `gps-local-queue`, retries 3→dead-letter, 200/404 remove vs 5xx retain [sync-queue-gas: Queue/Idempotent/Tags; offline-queue: Entregado]
- [x] 3.2 Update `frontend/syncEngine.js` (~15 lines) — use `CONFIG.GAS_URL`, tag `gps-gas-sync`, verify LWW, `lastSync` alias, no `//` [client-sync: lastSync, GAS_URL, PUSH/PULL] — verify: `python -m pytest -q`

## Phase 4: PWA & Deploy

- [x] 4.1 Create `frontend/sw.js` (~70 lines) — 3 caches (static/gas-api/tiles), networkFirst GAS, cacheFirst static/tiles, sync `gps-gas-sync`/`gps-local-queue` [offline-queue: Cache Rules, Tags]
- [x] 4.2 Create `frontend/netlify.toml` (~25 lines) + update `frontend/manifest.json` + wire `sw.js` in `frontend/index.html` (~10 lines) — `publish=frontend`, headers, SPA `/*→/index.html 200`, CSP [netlify-deploy: Hosting, SPA, Headers]
- [x] 4.3 Final gate — `python -m pytest -q` (45 tests), manual GAS + Netlify deploy no CORS [Success Criteria: 8 items] — verify: `python -m pytest -q`
