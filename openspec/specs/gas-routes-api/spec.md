# GAS Routes API Specification

## Purpose

GAS Web App endpoints for `/clientes`, `/clientes/sync`, `/rutas/hoy`, `/rutas/hoy/entregado` backed by Google Sheets with CORS support for Netlify origin.

## Requirements

### Requirement: GET /clientes — List Clients

The system SHALL return paginated, filterable client list from `Clientes` sheet.

#### Scenario: Happy path — Basic list

- GIVEN `Clientes` sheet has 100 rows
- WHEN GET `https://script.google.com/macros/s/xxx/exec/clientes?limit=50&offset=0`
- THEN returns 200 with JSON `{ data: [...], total: 100, limit: 50, offset: 0 }`
- AND each item has `id, nombre, direccion, lat, lng, zona, derivados`

#### Scenario: Edge case — NFD search filter

- GIVEN query `q=café` (with accent)
- WHEN GET `/clientes?q=cafe` (without accent)
- THEN returns clients matching NFD-normalized `nombre`
- AND `Content-Type: application/json` header present

#### Scenario: Error state — Invalid limit

- GIVEN limit=10000 (exceeds `LIMIT_MAX_ROWS=5000`)
- WHEN GET `/clientes?limit=10000`
- THEN returns 400 with `{ error: "limit exceeds maximum 5000" }`

### Requirement: POST /clientes — Create Client

The system SHALL create new client in `Clientes` sheet with generated UUID.

#### Scenario: Happy path — Valid create

- GIVEN POST `/clientes` with `{ nombre, direccion, lat, lng, zona, telefono, rif }`
- WHEN request validates (RIF regex `^[JVEGP]\d{7,9}$`, lat/lng in range)
- THEN returns 201 with created client including generated `id`
- AND row appended to `Clientes` sheet with `deleted=0`

#### Scenario: Error state — Duplicate RIF

- GIVEN RIF already exists in sheet with `deleted=0`
- WHEN POST with same RIF
- THEN returns 409 with `{ error: "RIF already exists" }`

### Requirement: PATCH /clientes/:id — Update Client

The system SHALL update existing client by ID.

#### Scenario: Happy path — Partial update

- GIVEN client exists with `deleted=0`
- WHEN PATCH `/clientes/uuid` with `{ zona: "Norte" }`
- THEN returns 200 with updated client
- AND sheet row updated, `updated_at` set to ISO8601

#### Scenario: Error state — Not found

- GIVEN client ID not in sheet or `deleted=1`
- WHEN PATCH `/clientes/uuid`
- THEN returns 404 with `{ error: "Client not found" }`

### Requirement: DELETE /clientes/:id — Soft Delete Client

The system SHALL soft-delete client by setting `deleted=1`.

#### Scenario: Happy path — Soft delete

- GIVEN client exists with `deleted=0`
- WHEN DELETE `/clientes/uuid`
- THEN returns 204 no content
- AND sheet row `deleted` set to `1`

### Requirement: POST /clientes/sync — LWW Sync

The system SHALL accept client array and apply Last-Write-Wins using `lastSync`/`last_sync`.

#### Scenario: Happy path — Push changes

- GIVEN POST `/clientes/sync` with `{ clients: [...], lastSync: "2026-08-30T10:00:00Z" }`
- WHEN server processes each client by `id`
- THEN for each: if server `updated_at` > client `updated_at` → keep server; else apply client
- AND returns 200 with `{ synced: N, conflicts: M, serverTime: "ISO8601" }`

#### Scenario: Edge case — Missing lastSync

- GIVEN request without `lastSync` field
- WHEN POST `/clientes/sync`
- THEN treats as full push (no conflict detection)
- AND returns 200 with all applied

### Requirement: GET /rutas/hoy — Today's Routes

The system SHALL return routes for a given date from `RutasHoy` sheet.

#### Scenario: Happy path — Routes for date

- GIVEN `RutasHoy` sheet has rows for `fecha=2026-08-30`
- WHEN GET `/rutas/hoy?fecha=2026-08-30`
- THEN returns 200 with `{ data: [{ cliente_id, orden, entregado, delivered_at }, ...] }`
- AND ordered by `orden` ascending

#### Scenario: Edge case — Filter by entregado

- GIVEN GET `/rutas/hoy?fecha=2026-08-30&entregado=0`
- WHEN request received
- THEN returns only pending (`entregado=0`) routes

#### Scenario: Error state — Invalid date format

- GIVEN GET `/rutas/hoy?fecha=invalid`
- WHEN request received
- THEN returns 400 with `{ error: "Invalid date format, use YYYY-MM-DD" }`

### Requirement: PATCH /rutas/hoy/entregado — Mark Delivered

The system SHALL mark a route stop as delivered with timestamp.

#### Scenario: Happy path — Mark delivered

- GIVEN route exists in `RutasHoy` for `fecha` with `cliente_id`
- WHEN PATCH `/rutas/hoy/entregado` with `{ fecha, cliente_id, entregado: 1 }`
- THEN returns 200 with updated route including `delivered_at` ISO8601
- AND sheet row `entregado=1`, `delivered_at` set

#### Scenario: Idempotent — Already delivered

- GIVEN route already has `entregado=1`
- WHEN PATCH with same `fecha, cliente_id, entregado: 1`
- THEN returns 200 with existing `delivered_at` (no change)
- AND no duplicate timestamp written

#### Scenario: Error state — Route not found

- GIVEN no matching `fecha` + `cliente_id` in sheet
- WHEN PATCH `/rutas/hoy/entregado`
- THEN returns 404 with `{ error: "Route stop not found" }`

### Requirement: CORS Headers

The system SHALL return CORS headers allowing Netlify origin.

#### Scenario: Happy path — Preflight and actual request

- GIVEN OPTIONS request from `https://app.netlify.app`
- WHEN GAS Web App receives request
- THEN responds with `Access-Control-Allow-Origin: *`
- AND `Access-Control-Allow-Methods: GET,POST,PATCH,DELETE,OPTIONS`
- AND `Access-Control-Allow-Headers: Content-Type`

#### Scenario: Edge case — Credentials not supported

- GIVEN request with `credentials: include`
- WHEN GAS responds
- THEN `Access-Control-Allow-Credentials` NOT set (wildcard origin incompatible)
- AND frontend MUST NOT send credentials