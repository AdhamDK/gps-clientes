# Sync Queue GAS Specification

## Purpose

Offline queue for `marcar entregados` targeting GAS Web App `/rutas/hoy/entregado` with BackgroundSync tag `gps-gas-sync`, idempotent replay, and separation from local optimization queue.

## Requirements

### Requirement: Queue Entregado Operations Offline

The system SHALL queue PATCH `/rutas/hoy/entregado` requests when offline.

#### Scenario: Happy path — Queue while offline

- GIVEN user marks client as delivered (taps "Entregado")
- WHEN network unavailable (navigator.onLine = false)
- THEN operation queued in IndexedDB `sync-queue` store
- AND queue item: `{ id: UUID, type: 'entregado', payload: { fecha, cliente_id }, timestamp, retries: 0 }`
- AND BackgroundSync registered with tag `gps-gas-sync`

#### Scenario: Edge case — Multiple offline deliveries

- GIVEN user marks 5 clients delivered while offline
- WHEN each tap occurs
- THEN 5 items queued in order
- AND single BackgroundSync registration (tag `gps-gas-sync`)

### Requirement: BackgroundSync Replay via GAS

The system SHALL replay queued operations when connectivity restored using BackgroundSync.

#### Scenario: Happy path — Replay on reconnect

- GIVEN 3 items in `sync-queue` with type `entregado`
- WHEN browser fires `sync` event for tag `gps-gas-sync`
- THEN `syncQueue.replayQueue()` called
- AND each item POSTed to `${GAS_API}/rutas/hoy/entregado`
- AND on 200: item removed from queue
- AND on 404: item removed (idempotent — already delivered)
- AND on 409/500: item retained, retries incremented

#### Scenario: Edge case — Partial success

- GIVEN 3 items, 2 succeed (200), 1 fails (500)
- WHEN replay completes
- THEN 2 items removed from queue
- AND 1 item remains with `retries: 1`
- AND BackgroundSync re-registered for retry

#### Scenario: Error state — Persistent failure

- GIVEN item fails 3 times (retries >= 3)
- WHEN replay attempts
- THEN item moved to `dead-letter` store
- AND notification shows "No se pudo sincronizar X entregas — reintente manualmente"

### Requirement: Idempotent Replay

The system SHALL ensure replay is idempotent against GAS PATCH `/rutas/hoy/entregado`.

#### Scenario: Happy path — Duplicate replay safe

- GIVEN queue item already processed by GAS (delivered_at set)
- WHEN replay sends same `{ fecha, cliente_id, entregado: 1 }`
- THEN GAS returns 200 with existing `delivered_at`
- AND queue item removed (no duplicate effect)

#### Scenario: Edge case — Network timeout, then retry

- GIVEN request sent, no response received (timeout)
- WHEN BackgroundSync retries
- THEN same payload sent again
- AND GAS idempotency handles duplicate (returns 200)

### Requirement: Tag Separation from Local Queue

The system SHALL use distinct BackgroundSync tag `gps-gas-sync` separate from `gps-local-queue`.

#### Scenario: Happy path — Tags isolated

- GIVEN `syncQueue.js` registers `gps-gas-sync`
- AND `sw.js` handles `gps-local-queue` for `/rutas/optimizar`
- WHEN both queues have pending items
- THEN `gps-gas-sync` event triggers only GAS queue replay
- AND `gps-local-queue` event triggers only optimization queue replay
- AND no cross-processing occurs

#### Scenario: Edge case — Tag collision prevented

- GIVEN old code used `gps-post-queue-v1` for both
- WHEN new code loads
- THEN old registrations ignored (different tag)
- AND no duplicate processing

### Requirement: Sequential Sync with syncEngine

The system SHALL run `syncEngine.runSync()` before `syncQueue.replayQueue()`.

#### Scenario: Happy path — Sequential execution

- GIVEN app comes online
- WHEN `syncManager.syncAll()` called
- THEN `syncEngine.runSync()` executes first (clientes PUSH/PULL)
- AND on completion, `syncQueue.replayQueue()` executes (entregado)
- AND both complete before UI unblocked

#### Scenario: Edge case — syncEngine fails

- GIVEN `syncEngine.runSync()` throws (GAS quota exceeded)
- WHEN `syncManager.syncAll()` called
- THEN `syncQueue.replayQueue()` still executes
- AND error logged, UI shows partial sync status

### Requirement: Queue Persistence Across Sessions

The system SHALL persist queue in IndexedDB across browser restarts.

#### Scenario: Happy path — Survives browser close

- GIVEN 2 items queued, user closes browser
- WHEN user reopens app (still offline)
- THEN queue items still present in IndexedDB
- AND BackgroundSync re-registered on load

#### Scenario: Edge case — Storage quota exceeded

- GIVEN IndexedDB near quota (50MB)
- WHEN new item queued
- THEN oldest low-priority items evicted (LRU)
- AND critical `entregado` items preserved