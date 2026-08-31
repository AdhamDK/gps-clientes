# Route Optimization Specification

## Purpose

Route optimization via local Docker VROOM/OSRM with client-side nearest-neighbor fallback when `LOCAL_API` unreachable. Modified from full-local to hybrid split architecture.

## Requirements

### Requirement: Optimize Route via LOCAL_API (VROOM)

The system SHALL call `LOCAL_API/ruta/optimizar` for optimal route when available.

#### Scenario: Happy path — VROOM optimization succeeds

- GIVEN 10 clients with valid lat/lng selected
- WHEN user clicks "Optimizar Ruta"
- THEN POST `${LOCAL_API}/rutas/optimizar` with `{ clientes: [...], vehicle_start: {lat, lng} }`
- AND VROOM returns optimized order with geometry from OSRM
- THEN frontend renders route on Leaflet map with OSRM polyline
- AND shows "Optimizado con VROOM" indicator

#### Scenario: Edge case — Vehicle start from user location

- GIVEN user tapped "Mi ubicación" (GPS fix acquired)
- WHEN optimize requested
- THEN `vehicle_start` = user's current GPS coordinates
- AND route starts from user location

#### Scenario: Edge case — Vehicle start from first client

- GIVEN no GPS fix available
- WHEN optimize requested
- THEN `vehicle_start` = first client's coordinates
- AND route starts from first client

### Requirement: Client-Side Nearest-Neighbor Fallback

The system SHALL fall back to deterministic nearest-neighbor when `LOCAL_API` unavailable.

#### Scenario: Happy path — Mixed content fallback

- GIVEN app on Netlify HTTPS, `LOCAL_API` = `http://localhost:8000`
- WHEN optimize requested
- THEN browser blocks mixed content (HTTPS → HTTP)
- THEN fetch fails with `TypeError: Failed to fetch`
- THEN system catches error, runs nearest-neighbor client-side
- AND shows banner "Optimización local (VROOM no disponible — requiere backend local)"

#### Scenario: Happy path — LOCAL_API timeout fallback

- GIVEN `LOCAL_API` reachable but VROOM takes >30s
- WHEN fetch aborts on timeout
- THEN system runs nearest-neighbor fallback
- AND shows fallback banner

#### Scenario: Happy path — LOCAL_API returns error fallback

- GIVEN `LOCAL_API` returns 500 or invalid JSON
- WHEN optimize requested
- THEN system runs nearest-neighbor fallback
- AND logs error, shows fallback banner

#### Scenario: Edge case — Fallback with 50+ clients

- GIVEN 100 clients selected (over `PAGINACION_LIMITE`)
- WHEN fallback executes
- THEN processes all 100 in O(n²) time (<2s on modern mobile)
- AND returns valid order
- AND warns "Optimización aproximada — para mejor resultado use backend local"

### Requirement: Nearest-Neighbor Algorithm Correctness

The system SHALL implement deterministic nearest-neighbor with defined behavior.

#### Scenario: Happy path — Deterministic output

- GIVEN same client set, same start point
- WHEN fallback runs multiple times
- THEN returns identical order each time
- AND no randomness in selection

#### Scenario: Edge case — Tie-breaking on equal distance

- GIVEN two unvisited clients at equal distance from current
- WHEN selecting next
- THEN picks client with lower `id` (string comparison)
- AND deterministic tie-break

#### Scenario: Edge case — Clients without coordinates

- GIVEN 10 clients, 3 have `lat=0, lng=0` or null
- WHEN fallback executes
- THEN excludes clients without valid GPS (`has_gps_fix = false`)
- AND optimizes remaining 7
- AND returns `{ order: [...], excluded: 3, warning: "3 clientes sin coordenadas omitidos" }`

### Requirement: Optimized Route Applied to RutasHoy

The system SHALL persist optimized order to `RutasHoy` via GAS API.

#### Scenario: Happy path — Save optimized route

- GIVEN optimization complete (VROOM or fallback)
- WHEN user confirms "Guardar Ruta"
- THEN for each client in order: POST `${GAS_API}/rutas/hoy` with `{ fecha, cliente_id, orden }`
- AND `RutasHoy` sheet populated for today's date
- AND UI shows "Ruta guardada para hoy"

#### Scenario: Edge case — Overwrite existing route

- GIVEN `RutasHoy` already has entries for today
- WHEN new optimization saved
- THEN existing entries for `fecha` deleted (or marked replaced)
- AND new order written
- AND `orden` sequential from 1

### Requirement: Optimization Button State Management

The system SHALL reflect backend availability in UI.

#### Scenario: Happy path — LOCAL_API healthy

- GIVEN `LOCAL_API` responds to `/health` in <2s
- WHEN routes screen loads
- THEN "Optimizar Ruta" button enabled
- AND no warning banner

#### Scenario: Edge case — LOCAL_API unhealthy

- GIVEN `/health` fails or times out
- WHEN routes screen loads
- THEN "Optimizar Ruta" button enabled (fallback available)
- AND warning banner "VROOM no disponible — se usará optimización local"
- AND tooltip explains "Ejecute docker-compose up para VROOM/OSRM"

#### Scenario: Edge case — No clients selected

- GIVEN 0 clients in selection
- WHEN user clicks "Optimizar Ruta"
- THEN shows "Seleccione al menos 2 clientes"
- AND no API call made