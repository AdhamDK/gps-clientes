# Client Sync Specification

## Purpose

syncEngine LWW synchronization between frontend IndexedDB/localforage and GAS/Sheets backend. Verified compatible with new `GAS_URL` environment resolution.

## Requirements

### Requirement: PUSH Local Changes to GAS

The system SHALL push locally modified clients to GAS `/clientes/sync` endpoint.

#### Scenario: Happy path — Push pending changes

- GIVEN 5 clients modified locally (created/updated/deleted)
- WHEN `syncEngine.runSync()` called
- THEN collects clients where `local.updated_at > lastSync`
- AND POSTs to `${GAS_API}/clientes/sync` with `{ clients: [...], lastSync }`
- AND on 200: updates local `lastSync` to server `serverTime`
- AND marks synced clients `sync_status: 'synced'`

#### Scenario: Edge case — No pending changes

- GIVEN all clients `sync_status: 'synced'`
- WHEN `syncEngine.runSync()` called
- THEN sends empty `clients: []` with current `lastSync`
- AND returns quickly with `{ synced: 0, conflicts: 0 }`

#### Scenario: Error state — GAS quota exceeded

- GIVEN GAS returns 429 (quota) or 500
- WHEN push attempted
- THEN throws `SyncError('GAS quota exceeded')`
- AND local `lastSync` NOT updated
- AND `sync_status` unchanged for pending clients

### Requirement: PULL Remote Changes from GAS

The system SHALL pull remote changes from GAS `/clientes` and merge LWW.

#### Scenario: Happy path — Pull and merge

- GIVEN server has 3 clients updated since `lastSync`
- WHEN `syncEngine.runSync()` executes PULL phase
- THEN GETs `${GAS_API}/clientes?limit=5000&updated_since=${lastSync}`
- AND for each server client: if `server.updated_at > local.updated_at` → overwrite local
- AND if `server.deleted=1` → soft-delete local (`deleted=1, sync_status='synced'`)
- AND updates local `lastSync` to max of server `updated_at`

#### Scenario: Edge case — Conflict resolution (LWW)

- GIVEN local and server both modified same client since `lastSync`
- WHEN merge executes
- THEN compares `local.updated_at` vs `server.updated_at`
- AND keeps version with newer timestamp (Last-Write-Wins)
- AND increments `conflicts` counter in result

#### Scenario: Edge case — Alias field compatibility

- GIVEN server returns `last_sync` (legacy alias) instead of `lastSync`
- WHEN processing response
- THEN accepts either field (prefers `lastSync`, falls back to `last_sync`)
- AND logs deprecation warning once per session

### Requirement: lastSync Canonical Timestamp

The system SHALL use `lastSync` (camelCase) as canonical; `last_sync` deprecated alias.

#### Scenario: Happy path — lastSync persisted

- GIVEN sync completes successfully
- WHEN `syncEngine` finishes
- THEN `localforage.setItem('lastSync', serverTime)` called
- AND `lastSync` used for next sync's `updated_since` param

#### Scenario: Edge case — Migration from last_sync

- GIVEN only `last_sync` exists in localforage (old data)
- WHEN `syncEngine` initializes
- THEN reads `last_sync`, writes to `lastSync`, removes `last_sync`
- AND uses migrated value for first sync

### Requirement: GAS_URL Resolution Compatibility

The system SHALL work with new `GAS_URL` resolution (Netlify env → localStorage → default).

#### Scenario: Happy path — Netlify production

- GIVEN `import.meta.env.GAS_URL` = `https://script.google.com/macros/s/prod/exec`
- WHEN `syncEngine` constructs API URL
- THEN uses `GAS_URL` directly
- AND no localStorage access

#### Scenario: Edge case — Local dev with override

- GIVEN `import.meta.env.GAS_URL` undefined
- AND `localStorage.GAS_URL` = `https://script.google.com/macros/s/dev/exec`
- WHEN `syncEngine` constructs API URL
- THEN uses localStorage value
- AND logs "syncEngine: Using localStorage GAS_URL"

#### Scenario: Regression test — No double-slash in URL

- GIVEN `GAS_URL` = `https://script.google.com/macros/s/xxx/exec` (no trailing slash)
- WHEN constructing `/clientes/sync` endpoint
- THEN result = `https://script.google.com/macros/s/xxx/exec/clientes/sync`
- AND no `//` double-slash in path

### Requirement: Offline-First Sync Trigger

The system SHALL trigger sync on online event and periodic interval.

#### Scenario: Happy path — Online event triggers sync

- GIVEN app was offline, `window.addEventListener('online')` fires
- WHEN online event received
- THEN `syncEngine.runSync()` called automatically
- AND UI shows "Sincronizando..." indicator

#### Scenario: Edge case — Periodic sync (15 min)

- GIVEN app online for >15 minutes
- WHEN `setInterval` fires (15 min)
- THEN `syncEngine.runSync()` called
- AND does not interrupt user interaction

#### Scenario: Error state — Sync in progress, another trigger

- GIVEN `syncEngine.runSync()` already running
- WHEN online event fires again
- THEN second call queued or skipped (mutex)
- AND no concurrent sync executions