# Specs — Source of Truth

This directory holds canonical domain specs. No specs have been promoted yet.

Active changes live in `openspec/changes/{change-name}/specs/{domain}/spec.md` as deltas
(ADDED / MODIFIED / REMOVED / RENAMED). On `sdd-archive`, approved deltas merge here.

Domains observed in this codebase (for future spec scaffolding):
- `clientes` — CRUD, NFD search (q/zona/pagination), RIF validation, soft-delete, derivados (nombre_normalizado, is_flagged, has_gps_fix)
- `import-export` — Import 20->11 xlsx (openpyxl), NFD dedup, RIF regex, flagged #, Export xlsx/pdf column whitelist
- `rutas` — VROOM optimize (+ OSRM geometry), RutasHoy (fecha/orden/entregado/delivered_at), atomic delete, GET /rutas/hoy?fecha&entregado
- `sync` — GAS/Sheets LWW (lastSync canonical, last_sync alias deprecated), offline IndexedDB/localforage, syncEngine
- `map-frontend` — Leaflet, PAGINACION_LIMITE 500, selection persistence, virtual scroll, FAB Mi ubicacion
- `mobile` — Android Kotlin Compose/Room 31-col, WebViewAssetLoader https://appassets.androidplatform.net

Reference: `../_shared/openspec-convention.md` for delta structure.
