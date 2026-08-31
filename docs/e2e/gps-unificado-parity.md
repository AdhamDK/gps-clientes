# E2E GPS Unificado — Side-by-Side Parity (PR4 5.3)

**Change:** gps-unificado-web-movil
**Scope:** MyLocation 16 zoom <10s, FAB 44px, circle FC4C02, denied copy + Settings CTA, VROOM start, clusters, themes

## Success Criteria

| # | Scenario | Web (Chrome) | APK (Device) | Pass |
|---|----------|--------------|--------------|------|
| 1 | Center success — permission granted, no cachedFix → `Ubicando…` → `Ubicación centrada` + circle #FC4C02 2px/0.12 radius=accuracy, zoom 16 within 10s | screenshot web-center.png | screenshot apk-center.png | ☐ |
| 2 | Debounce 500ms — double tap FAB within 500ms ignored, single `Ubicando…` | log | log | ☐ |
| 3 | CachedFix instant <30s → <200ms re-center no geolocation | timeline | timeline | ☐ |
| 4 | Stale >=30s → fresh fix required | log | log | ☐ |
| 5 | Timeout 10s → `Timeout 10s — intenta de nuevo` | toast | snackbar | ☐ |
| 6 | Web code 1 / APK permanentlyDenied → `Permiso denegado — ingresa manual` + Settings CTA | dialog | dialog + Ajustes | ☐ |
| 7 | VROOM injection — cachedFix <30s → POST /rutas/optimizar body.start=[lng,lat] | network | logcat | ☐ |
| 8 | FAB 44px bottomEnd ◎ `Mi ubicación`, lifted 72dp bar visible else 16dp | measure | measure | ☐ |
| 9 | Pins 1..5 #FC4C02 72px, 120→`99+` | screenshot | screenshot | ☐ |
| 10 | Clusters >2 @40px → chip 40px bg #1E1E1E border #FC4C02, tap zoom+2 (parity 40 sync frontend/app.js + OsmMapProvider + assets/www) | screenshot | screenshot | ☐ |
| 11 | Theme cycle claro→oscuro→medio (#2D3A2E) no flash restore | screenshots 3 | screenshots 3 | ☐ |
| 12 | Pagination 500 — `GET /clientes?q=&limit=500` respects backend `le=500` (`backend/main.py:329`); `frontend/app.js` `PAGINACION_LIMITE=500` covers fixture 409 without silent truncation; `total>500` shows banner `#paginacionBanner` `Mostrando 500 de X — usa busqueda para filtrar` (`frontend/index.html:76`, `aria-live=polite`) | network `limit=500` + banner assert | network + banner assert | ☐ |
| 13 | CORS whitelist — `Origin: https://evil.com` no `Access-Control-Allow-Origin: *` (blocked); `Origin: http://localhost:8000` -> allowed; `Origin: https://appassets.androidplatform.net` (WebView) -> allowed; `file://` blocked (`backend/main.py:220-232`, `allow_origin_regex=None`, `allow_origins=[localhost:8000,127.0.0.1:8000,appassets.androidplatform.net,10.0.2.2:8000]`) | curl + devtools | curl + logcat | ☐ |
| 14 | Export guard 5000 — `GET /clientes/export?formato=xlsx` with `count>5000` -> `413 Demasiados registros — filtra por zona/q` ( coherent with `MAX_IMPORT_ROWS=5000`); `count<=5000` -> `200` streaming | curl 413 / 200 | curl 413 / 200 | ☐ |
| 15 | Panel desplegable BottomSheet — APK `MapOptionsBottomSheet.kt` (ModalBottomSheet peek 80dp, drag handle, expand full) replica `frontend/index.html` `sidebar-controls`: Row búsqueda `OutlinedTextField` + `ExposedDropdownMenuBox` filtroZona (Todas/VIGIA/Zona Norte/Lunes/Centro + dinámico distinct `zonaRuta`), actions-grid 2x: `+Agregar Cliente` + `Optimizar Ruta de Hoy` (POST `/rutas/optimizar` con `cliente_ids` + `start` cachedFix), `Exportar ▼` (xlsx/pdf/import) + `Ver Ruta Guardada` (GET `/rutas/hoy`) + `Limpiar Ruta`; FAB lift 72dp cuando sheet expandido; `MapaClientesViewModel` `_filtroZona`/`_searchQuery` filtra `pins`; `ClienteList` también filtra por zona | screenshot web sidebar | screenshot apk bottomSheet expanded | ☐ |
| 16 | Filtro zona lista + RIF + Optimizar parity — `ClienteListScreen`/`ViewModel` dropdown zona filtra `displayList`; `ClienteFormScreen`/`EditClienteDialog`/`AddClienteDialog` validan `Regex("^[JVEGP]\\d{7,9}$")` con Snackbar “RIF inválido: J/V/E/G/P + 7-9 dígitos” (parity `frontend/app.js:485`); botón Optimizar en Card selección (4 botones) wire a `optimizarRuta()` con `cachedStartOrNull()`; `OsmMapProvider.createNumberedDrawable` restaura número sobre dot 72px (test requires 72); `assets/www/app.js` `PAGINACION_LIMITE=500` sync `frontend/app.js` | list filter + RIF toast + optimizar toast | same | ☐ |

## Manual Run

```bash
# web
cd frontend && python -m http.server 8000
# open http://localhost:8000, tap ◎, check console + network

# apk
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat | grep -i "gps"   # verify start=[lng,lat] when optimizing
```

## Screenshots

Store in `docs/e2e/screenshots/`:

- `web-center-16.png` / `apk-center-16.png` — map centered zoom 16 with FC4C02 circle
- `web-denied.png` / `apk-denied-permanently.png` — permission copy + Ajustes CTA
- `web-fab-44.png` / `apk-fab-44.png` — FAB measure 44px, lifted vs idle
- `web-cluster-chip.png` / `apk-cluster-chip.png` — chip #1E1E1E/#FC4C02
- `web-theme-medio.png` / `apk-theme-medio.png` — medio #2D3A2E

Screenshots are manual — attach to PR or `docs/e2e/screenshots/` and check boxes above.
All captures must show same tokens side-by-side within visual tolerance.

## Rollback

E2E doc only — revert by deleting file; no runtime impact.
