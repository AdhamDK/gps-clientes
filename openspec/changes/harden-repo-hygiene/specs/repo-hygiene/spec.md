# Delta for repo-hygiene

> **No domain requirement changes. Infra-only.** No API contract, business rule, or user-visible behavior changes. This new `repo-hygiene` capability documents infra invariants that MUST hold before and after the change.

## ADDED Requirements

### Requirement: Build Source-of-Truth — frontend canonical, www derived

The build MUST treat `frontend/` as canonical and MUST derive `app/src/main/assets/www/` only via `copyFrontendToAssets` (`from("../frontend")` → `into("src/main/assets/www")`) wired to `preBuild.dependsOn`.

#### Scenario: Fresh clone repopulates www

- GIVEN a fresh clone with empty `app/src/main/assets/www/`
- WHEN `./gradlew preBuild` executes
- THEN `www/` contains byte-identical copies of `frontend/` (excluding `test_*.js`, `e2e.*`, `*.spec.js`)
- AND `www/syncEngine.js` hash equals `frontend/syncEngine.js` (`2702C96D...`)

#### Scenario: Direct www edits are overwritten

- GIVEN a file under `app/src/main/assets/www/` is edited directly
- WHEN `copyFrontendToAssets` runs again
- THEN the edit is overwritten by the `frontend/` version

#### Scenario: Orphan JS source removed

- GIVEN pre-change triple `js/syncEngine.js` / `frontend/syncEngine.js` / `www/syncEngine.js` (hash `2702C96D...3426D3D`)
- WHEN change is applied
- THEN `js/syncEngine.js` MUST NOT exist; only `frontend/` canonical + derived `www/` remain

### Requirement: Test Collection Invariant — pytest without ignore hacks

`python -m pytest -q` MUST collect and pass 45 tests from project root without `--ignore`. `pytest.ini` MUST set `testpaths = backend/tests tests`.

#### Scenario: Default pytest passes

- GIVEN `pytest.ini` with `testpaths = backend/tests tests` at `GPS_CLIENTES/`
- WHEN `python -m pytest -q` runs from `GPS_CLIENTES/`
- THEN 45 tests are collected and pass with exit code 0

#### Scenario: Root has no stray breakers

- GIVEN 5 stray files `test_delete_uuid.py`, `test_insert.py`, `test_sqlalchemy_insert.py`, `check_db.py`, `check_sql2.py`
- WHEN listing `GPS_CLIENTES/*.py`
- THEN none exist at root; they exist only under `scripts/one-offs/` if retained

#### Scenario: Legacy ignore flags removed

- GIVEN old `verify.test_command` with five `--ignore` flags
- WHEN change is applied
- THEN `verify.test_command` and `apply.test_command` SHALL be `python -m pytest -q`

### Requirement: Git Boundary Invariant — root, remote, ignores

Git root MUST be `GPS_CLIENTES/` (not `C:/Users/Usuario`), `origin` MUST be `AdhamDK/gps-clientes`, `.gitignore` MUST cover derived/local artifacts.

#### Scenario: Toplevel is GPS_CLIENTES

- GIVEN a clone of `AdhamDK/gps-clientes`
- WHEN `git rev-parse --show-toplevel` runs inside worktree
- THEN it ends with `GPS_CLIENTES`

#### Scenario: Remote is gps-clientes

- GIVEN repo at `GPS_CLIENTES/`
- WHEN `git remote get-url origin` runs
- THEN it returns `AdhamDK/gps-clientes` (https or ssh form)

#### Scenario: Derived www is ignored and untracked

- GIVEN `.gitignore` contains `app/src/main/assets/www/`
- WHEN `git ls-files | grep www` and `git check-ignore app/src/main/assets/www/syncEngine.js` run
- THEN `ls-files` output is empty and `check-ignore` confirms ignored

#### Scenario: Standard ignores present

- GIVEN `.gitignore` at `GPS_CLIENTES/.gitignore`
- WHEN inspected
- THEN it covers `app/build/`, `.gradle/`, `backend/clientes.db`, `backend/*.db`, `*.log`, `osrm-data/*.pbf`, `__pycache__/`, `.venv/`, `.DS_Store` at minimum

### Requirement: Offline Queue Invariant — syncQueue behavior unchanged

Offline queue (IndexedDB/localforage via `syncEngine.js`/`syncQueue.js`) MUST be unchanged; `entregado_local` queuing and `replayQueue` idempotent `PATCH /rutas/hoy/entregado` MUST be preserved.

#### Scenario: entregado_local queues offline

- GIVEN app is offline and a ruta item is marked `entregado`
- WHEN delivered state is toggled
- THEN change is stored as `entregado_local` in IndexedDB/localforage and survives reload

#### Scenario: replayQueue is idempotent

- GIVEN queued `entregado_local` entries exist
- WHEN connectivity restores and `replayQueue` runs
- THEN each entry sends idempotent `PATCH /rutas/hoy/entregado`, clears only on 2xx
- AND replaying twice has no duplicate side effects

#### Scenario: Harness still passes after dedup

- GIVEN `syncEngine.js` deduped to `frontend/` canonical
- WHEN `tests/test_sync_engine.py` harness runs (mock GAS :8765)
- THEN all entrega assertions pass unchanged
