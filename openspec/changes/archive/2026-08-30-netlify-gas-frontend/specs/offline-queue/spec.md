# Offline Queue Specification

## Purpose

BackgroundSync queues for offline operations: `gps-gas-sync` for entregado (GAS) and `gps-local-queue` for route optimization (local). Idempotent replay, tag separation, persistence across sessions.

## Requirements

### Requirement: BackgroundSync Tag Separation

The system SHALL use distinct tags for GAS sync queue vs local optimization queue.

#### Scenario: Happy path — Tags registered independently

- GIVEN `syncQueue.js` registers `gps-gas-sync` for entregado
- AND `sw.js` registers `gps-local-queue` for `/rutas/optimizar`
- WHEN both operations queued offline
- THEN two separate `sync` events fire
- AND each handler processes only its queue

#### Scenario: Edge case — Old tag migration

- GIVEN previous version used `gps-post-queue-v1` for both
- WHEN new version loads
- THEN old tag ignored (no handler registered)
- AND no duplicate processing of stale registrations

### Requirement: Entregado Queue (GAS) — gps-gas-sync

The system SHALL queue and replay `marcar entregado` via GAS `/rutas/hoy/entregado`.

#### Scenario: Happy path — Offline entregado queued

- GIVEN user taps "Entregado" on route stop while offline
- WHEN `syncQueue.queueEntregado(fecha, cliente_id)` called
- THEN item added to IndexedDB `gas-sync-queue` store
- AND `navigator.serviceWorker.ready.then(reg => reg.sync.register('gps-gas-sync'))`
- AND UI shows "Guardado offline — se sincronizará al conectar"

#### Scenario: Happy path — Replay on sync event

- GIVEN 2 items in `gas-sync-queue`
- WHEN `sync` event for `gps-gas-sync` fires
- THEN `syncQueue.replayGasQueue()` called
- AND each item PATCHed to `${GAS_API}/rutas/hoy/entregado`
- AND on 200/404: item deleted from queue
- AND on 5xx: item retained, `retries++`, re-register sync

#### Scenario: Edge case — Idempotent replay

- GIVEN item already processed by GAS (delivered_at exists)
- WHEN replay sends same payload
- THEN GAS returns 200 with existing `delivered_at`
- AND queue item removed (no duplicate)

#### Scenario: Error state — Dead letter after 3 retries

- GIVEN item fails 3 times (retries >= 3)
- WHEN replay attempts
- THEN item moved to `gas-sync-dead-letter` store
- AND notification: "No se sincronizó entrega de [cliente] — reintente manualmente"

### Requirement: Optimization Queue (Local) — gps-local-queue

The system SHALL queue route optimization requests for local backend.

#### Scenario: Happy path — Optimize queued offline

- GIVEN user clicks "Optimizar Ruta" while offline
- WHEN `optimizationQueue.queueOptimize(clientes)` called
- THEN item added to IndexedDB `local-queue` store
- AND `navigator.serviceWorker.ready.then(reg => reg.sync.register('gps-local-queue'))`
- AND UI shows "Optimización en cola — se ejecutará al conectar"

#### Scenario: Happy path — Replay on local sync

- GIVEN item in `local-queue`
- WHEN `sync` event for `gps-local-queue` fires
- THEN `optimizationQueue.replayLocalQueue()` called
- AND POST sent to `${LOCAL_API}/rutas/optimizar`
- AND on 200: result cached, queue item removed
- AND on failure: item retained, re-register sync

#### Scenario: Edge case — Result cached for UI

- GIVEN optimization replay succeeds
- WHEN result received
- THEN cached in `optimization-results` store keyed by client set hash
- AND UI can display immediately on next visit

### Requirement: Queue Persistence Across Sessions

The system SHALL persist all queues in IndexedDB across browser restarts.

#### Scenario: Happy path — Survives browser close

- GIVEN items in both `gas-sync-queue` and `local-queue`
- WHEN user closes and reopens browser (still offline)
- THEN both queues intact in IndexedDB
- AND BackgroundSync re-registered on `serviceWorker.ready`

#### Scenario: Edge case — Storage quota management

- GIVEN IndexedDB near 50MB quota
- WHEN new item queued
- THEN LRU eviction on low-priority items (optimization results)
- AND `gas-sync-queue` items NEVER evicted (critical)
- AND `gas-sync-dead-letter` preserved for manual recovery

### Requirement: Sequential Sync Execution

The system SHALL run `syncEngine` (clientes) → `gas-sync-queue` (entregado) → `local-queue` (optimize).

#### Scenario: Happy path — Ordered execution

- GIVEN app comes online, all three queues have items
- WHEN `syncManager.syncAll()` called
- THEN 1. `syncEngine.runSync()` completes (clientes PUSH/PULL)
- AND 2. `syncQueue.replayGasQueue()` completes (entregado)
- AND 3. `optimizationQueue.replayLocalQueue()` completes (optimize)
- AND UI unblocked after all three

#### Scenario: Edge case — Partial failure continues

- GIVEN `syncEngine` fails (GAS quota)
- WHEN `syncAll()` executes
- THEN `gas-sync-queue` still replays
- AND `local-queue` still replays
- AND errors collected, shown in summary toast

### Requirement: Service Worker Cache Rules for Split Origins

The system SHALL cache GAS GET with StaleWhileRevalidate; local POST via BackgroundSync only.

#### Scenario: Happy path — GAS GET cached

- GIVEN GET `${GAS_API}/clientes` response received
- WHEN service worker handles fetch
- THEN caches response with `StaleWhileRevalidate` strategy
- AND subsequent offline loads serve stale, then update

#### Scenario: Happy path — GAS POST not cached

- GIVEN POST `${GAS_API}/clientes/sync` sent
- WHEN service worker handles fetch
- THEN does NOT cache (POST not cacheable)
- AND if offline: queued via `gps-gas-sync`

#### Scenario: Happy path — Local POST queued only

- GIVEN POST `${LOCAL_API}/rutas/optimizar` sent
- WHEN service worker handles fetch
- THEN does NOT cache
- AND if offline: queued via `gps-local-queue`