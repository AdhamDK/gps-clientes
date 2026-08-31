# API Split Routing Specification

## Purpose

Frontend dual-API client routing: `GAS_API` for clientes CRUD, sync, rutas_hoy, entregado; `LOCAL_API` for VROOM/OSRM route optimization with client-side nearest-neighbor fallback.

## Requirements

### Requirement: Environment Detection for GAS_URL

The system SHALL resolve `GAS_URL` from Netlify build env, localStorage override, or default.

#### Scenario: Happy path — Netlify production

- GIVEN `import.meta.env.GAS_URL` defined at build time
- WHEN app initializes
- THEN `GAS_API` = resolved `GAS_URL`
- AND no localStorage read occurs

#### Scenario: Edge case — Local development override

- GIVEN `import.meta.env.GAS_URL` undefined (local dev)
- AND `localStorage.GAS_URL` = `https://script.google.com/macros/s/dev/exec`
- WHEN app initializes
- THEN `GAS_API` = localStorage value
- AND console logs "Using localStorage GAS_URL override"

#### Scenario: Edge case — No configuration

- GIVEN neither Netlify env nor localStorage has `GAS_URL`
- WHEN app initializes
- THEN `GAS_API` defaults to `https://script.google.com/macros/s/<default>/exec`
- AND warning banner shows "GAS_URL not configured — using default"

### Requirement: LOCAL_API Resolution

The system SHALL resolve `LOCAL_API` for VROOM/OSRM with explicit localhost default.

#### Scenario: Happy path — Local Docker running

- GIVEN `import.meta.env.LOCAL_API` undefined
- WHEN app initializes
- THEN `LOCAL_API` defaults to `http://localhost:8000`
- AND optimization button enabled

#### Scenario: Edge case — Custom local backend

- GIVEN `localStorage.LOCAL_API` = `http://192.168.1.50:8000`
- WHEN app initializes
- THEN `LOCAL_API` = localStorage value
- AND used for `/rutas/optimizar` calls

### Requirement: Dual-API Request Routing

The system SHALL route each API call to correct backend based on operation type.

#### Scenario: Happy path — Clientes CRUD via GAS_API

- GIVEN user creates client via UI
- WHEN `apiClient.post('/clientes', data)` called
- THEN request sent to `${GAS_API}/clientes`
- AND `Authorization` header NOT sent (CORS anonymous)

#### Scenario: Happy path — Rutas hoy via GAS_API

- GIVEN user views today's routes
- WHEN `apiClient.get('/rutas/hoy?fecha=2026-08-30')` called
- THEN request sent to `${GAS_API}/rutas/hoy`
- AND response cached by service worker (StaleWhileRevalidate)

#### Scenario: Happy path — Optimizar ruta via LOCAL_API

- GIVEN user clicks "Optimizar Ruta"
- WHEN `apiClient.post('/rutas/optimizar', { clientes })` called
- THEN request sent to `${LOCAL_API}/rutas/optimizar`
- AND timeout set to 30s (VROOM may take time)

### Requirement: Fallback Logic for Route Optimization

The system SHALL attempt LOCAL_API first, then fall back to client-side nearest-neighbor.

#### Scenario: Happy path — LOCAL_API succeeds

- GIVEN `LOCAL_API` reachable and VROOM healthy
- WHEN optimize requested
- THEN returns optimized order from VROOM
- AND shows "Optimizado con VROOM" badge

#### Scenario: Edge case — LOCAL_API unreachable (mixed content)

- GIVEN Netlify HTTPS page calls `http://localhost:8000`
- WHEN browser blocks mixed content
- THEN fetch fails with network error
- THEN system SHALL catch error and run client-side nearest-neighbor
- AND shows "Optimización local (VROOM no disponible)" banner

#### Scenario: Edge case — LOCAL_API timeout

- GIVEN `LOCAL_API` responds but VROOM takes >30s
- WHEN fetch aborts on timeout
- THEN system SHALL run client-side nearest-neighbor
- AND shows fallback banner

#### Scenario: Edge case — LOCAL_API returns error

- GIVEN `LOCAL_API` returns 500 or invalid response
- WHEN optimize requested
- THEN system SHALL run client-side nearest-neighbor
- AND logs error to console

### Requirement: Client-Side Nearest-Neighbor Fallback

The system SHALL implement deterministic nearest-neighbor as fallback.

#### Scenario: Happy path — Fallback produces valid route

- GIVEN 10 clients with valid lat/lng
- WHEN fallback executes
- THEN returns ordered array starting from user location (or first client)
- AND each step picks nearest unvisited client
- AND total distance <= 2x optimal (heuristic bound)

#### Scenario: Edge case — Missing coordinates

- GIVEN some clients have `lat=0, lng=0` or null
- WHEN fallback executes
- THEN excludes clients without valid GPS fix
- AND warns "X clientes sin coordenadas omitidos"

### Requirement: API Client Abstraction

The system SHALL provide unified `apiClient` with automatic base URL selection.

#### Scenario: Happy path — Method routes correctly

- GIVEN `apiClient.get('/clientes')` → uses `GAS_API`
- GIVEN `apiClient.post('/rutas/optimizar')` → uses `LOCAL_API`
- WHEN called
- THEN correct base URL prepended
- AND `Content-Type: application/json` header set

#### Scenario: Edge case — Unknown endpoint

- GIVEN `apiClient.get('/unknown')`
- WHEN called
- THEN throws `Error: Unknown API endpoint: /unknown`
- AND does not make network request