# Proposal: netlify-gas-frontend

## Intent

Deploy the GPS_CLIENTES frontend to Netlify with Google Sheets (via GAS Web App) as the primary database backend. Retain local Docker (VROOM/OSRM) for route optimization only. Split API calls: GAS for clientes CRUD, sync, rutas_hoy, and entregado; localhost for optimizarRuta with client-side nearest-neighbor fallback.

## Scope

### In Scope
- Frontend routing in `app.js` to split calls between `GAS_API` and `LOCAL_API`
- GAS Web App (`Code.gs`) extended with `/rutas/hoy*` endpoints backed by new `RutasHoy` sheet tab
- `syncQueue.js` `replayQueue()` redirected to GAS endpoint for `/rutas/hoy/entregado`
- Netlify deployment config (`netlify.toml`) with headers, env vars, SPA redirects
- Service worker cache rules for split-origin caching (GAS + local)
- Environment detection for `GAS_URL` (Netlify env var + `window.location` fallback)
- BackgroundSync tag separation: `gps-gas-sync` (client sync) vs `gps-local-queue` (optimization)

### Out of Scope
- Migration of VROOM/OSRM to cloud (future work: Cloud Run / Railway / self-hosted)
- Authentication/authorization (CORS-only via "Anyone, even anonymous" GAS deployment)
- Google Sheets schema redesign (existing `Clientes` tab unchanged; new `RutasHoy` tab only)
- Import/Export (XLSX/PDF) moved to GAS — **stays on local Docker** per recommendation
- Full offline route optimization (requires local Docker; fallback only)

## Capabilities

### New Capabilities
- `netlify-deploy`: Static frontend hosting on Netlify with env-driven API routing
- `gas-routes-api`: GAS Web App endpoints for `GET/PATCH/DELETE /rutas/hoy*` backed by Sheets
- `api-split-routing`: Frontend dual-API client (`GAS_API` for CRUD/sync, `LOCAL_API` for VROOM/OSRM)
- `sync-queue-gas`: Offline queue for `marcar entregados` targeting GAS Web App

### Modified Capabilities
- `client-sync`: syncEngine already uses GAS; verify compatibility with new `GAS_URL` env resolution
- `route-optimization`: Remains on local Docker; add client-side nearest-neighbor fallback when `LOCAL_API` unreachable
- `offline-queue`: BackgroundSync tag namespaced to separate GAS vs local queues

## Approach

### Approach Options Summary

| # | Approach | Description | Verdict |
|---|----------|-------------|---------|
| 1 | **Hybrid Split (Recommended)** | GAS for clientes CRUD + sync + rutas_hoy + entregado; localhost for optimizarRuta with fallback | ✅ Chosen |
| 2 | Full GAS | Push VROOM logic to GAS | ❌ Not feasible — 30s timeout, no Docker, no native binaries |
| 3 | Full Local | Keep everything localhost; Netlify only static hosting | ❌ Defeats remote access goal — no Sheets sync in production |

### Chosen Approach: Hybrid Split

**Rationale:**
- `syncEngine.js` already uses GAS for client sync (proven working)
- VROOM/OSRM are Docker-only binaries — cannot run in GAS (30s limit, no native deps)
- Netlify static hosting is perfect for vanilla JS frontend
- Google Sheets via GAS provides serverless, scalable primary database
- Route management (`rutas_hoy`, `entregado`) maps naturally to Sheets tab

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `frontend/app.js` | Modified | Dual-API routing (`GAS_API` / `LOCAL_API`), env detection, fallback logic |
| `frontend/syncEngine.js` | Modified | `GAS_URL` resolution from Netlify env + localStorage fallback |
| `frontend/js/syncQueue.js` | Modified | `replayQueue()` targets `GAS_API`; BackgroundSync tag `gps-gas-sync` |
| `backend/Code.gs` | Modified | New `doGet`/`doPost` routes for `/rutas/hoy*`, `RutasHoy` sheet tab |
| `frontend/sw.js` | Modified | Cache rules for split origins (GAS GET → StaleWhileRevalidate) |
| `docker-compose.yml` | Documented | Local dev only; production VROOM strategy documented as future |
| `netlify.toml` (new) | New | Headers, redirects, env vars, SPA fallback |

## Risks & Mitigations

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| GAS 30s execution timeout on large operations | Medium | Chunk imports; keep XLSX import/export on local Docker |
| GAS 6 min/day quota (consumer accounts) | Medium | Use Google Workspace account; batch sync every 15s |
| CORS preflight failures on GAS Web App | Low | Deploy as "Anyone, even anonymous"; returns `Access-Control-Allow-Origin: *` |
| VROOM/OSRM unavailable in production (Netlify HTTPS → localhost HTTP blocked) | High | Client-side nearest-neighbor fallback; show "Requires local backend" banner |
| Mixed content (Netlify HTTPS → local HTTP blocked by browser) | High | `LOCAL_API` only used when explicitly running local Docker; fallback to GAS for all else |
| BackgroundSync tag collision (`gps-post-queue-v1` used for both optimize + entregado) | Medium | Separate tags: `gps-gas-sync` (entregado) vs `gps-local-queue` (optimize) |
| syncEngine + syncQueue dual sync race conditions | Low | Sequential: syncEngine runs first, then syncQueue replays |

## Open Questions to Answer in Proposal

1. **Import/Export (XLSX/PDF) location**: GAS (Sheets) or local (SQLite)?
   - **Recommendation**: Keep on local Docker. GAS import → Sheets via base64 + `Utilities.newBlob` is complex and quota-heavy. Local FastAPI already has robust XLSX/PDF.

2. **Route sheet schema in Sheets**: Need `RutasHoy` tab with columns?
   - **Yes**: `fecha` (DATE), `cliente_id` (TEXT), `orden` (INTEGER), `entregado` (0/1), `delivered_at` (ISO8601). Mirrors FastAPI `models.RutasHoy`.

3. **Production VROOM/OSRM strategy**: Cloud Run / Railway / self-hosted?
   - **Document as future**: Not in this change. Local-only for now; optimization button shows "Requires local backend" when `LOCAL_API` unreachable.

4. **Environment detection for API_URL**: Netlify env var + `window.location` check?
   - **Yes**: `GAS_URL` from `import.meta.env.GAS_URL` (Netlify) or `localStorage.GAS_URL` (local override). `LOCAL_API` defaults to `http://localhost:8000`.

5. **BackgroundSync tag separation**: `gps-gas-sync` vs `gps-local-queue`?
   - **Yes**: Separate queues prevent cross-contamination. `syncQueue.js` uses `gps-gas-sync`; service worker handles `gps-local-queue` for `/rutas/optimizar`.

## Rollback Plan

1. **Frontend**: Revert `app.js`, `syncEngine.js`, `syncQueue.js` to single `API` variable pointing to `location.origin` (local Docker)
2. **GAS**: Keep `Code.gs` as-is (client-only); remove `RutasHoy` tab from Sheet
3. **Netlify**: Delete `netlify.toml`; redeploy from `frontend/` as static site pointing to local Docker
4. **Data**: No migration — `Clientes` tab unchanged; `RutasHoy` is additive
5. **Service Worker**: Revert `sw.js` to single-origin cache rules

## Success Criteria

- [ ] Frontend loads on Netlify (`*.netlify.app`) and fetches clientes from GAS Web App
- [ ] Client CRUD (create/read/update/delete) works end-to-end via GAS → Sheets
- [ ] `syncEngine.runSync()` completes PUSH/PULL cycle against GAS
- [ ] `marcar entregados` works online (GAS) and offline (queued, replayed via BackgroundSync)
- [ ] `optimizar ruta` works when local Docker running; shows fallback banner when not
- [ ] No CORS errors in browser console on Netlify origin
- [ ] Service worker caches GAS GET responses (StaleWhileRevalidate) and queues POST via BackgroundSync
- [ ] Local dev unchanged: `docker-compose up` → frontend at `localhost:8000/app` works identically

## Dependencies

- Netlify account + site creation
- GAS Web App deployed as "Anyone, even anonymous" with updated `Code.gs`
- Google Sheet with `Clientes` tab (existing) + new `RutasHoy` tab
- Local Docker (VROOM/OSRM) for development and route optimization

---
*Generated by sdd-propose — fits single-PR delivery (~300 lines net, well under 800 line budget)*