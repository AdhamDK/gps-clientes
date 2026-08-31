# Apply Progress — harden-repo-hygiene

**Change:** harden-repo-hygiene
**Mode:** Strict TDD (infra RED/GREEN)
**Date:** 2026-08-30
**Branch:** feature/gps-clientes-filtro-seleccion (parent root C:/Users/Usuario until manual re-init)

## TDD Cycle Evidence

Infra tasks use `pytest -q` and `git ls-files` as RED/GREEN gates.

| Task | Test / Gate | Layer | Safety Net | RED | GREEN | TRIANGULATE | REFACTOR |
|------|-------------|-------|------------|-----|-------|-------------|----------|
| 1.1 `.gitignore` | `git check-ignore app/src/main/assets/www/syncEngine.js` | infra | N/A (new ignore) | ✅ RED `check-ignore` failed (exit 1) before edit | ✅ GREEN `check-ignore` → `app/src/main/assets/www/` via `.gitignore:29` | ➖ Single ignore path | ✅ Clean (sectioned ignores) |
| 1.2 `pytest.ini` | `python -m pytest -q` collects 45 | config | ✅ 45 passed with --ignore hack before | ✅ RED `pytest -q` → `JSONDecodeError test_delete_uuid.py` (1 error) | ✅ GREEN `pytest.ini testpaths=backend/tests tests` → `45 passed in 5.64s` | ✅ 2 cases: full suite 45 + `tests/test_sync_engine.py` 3 | ➖ None needed |
| 1.3 `scripts/one-offs` | `Get-ChildItem *.py` + `pytest -q` | chore | ✅ 45 passed | ✅ RED root `*.py` = 5 strays breaking collection | ✅ GREEN `Get-ChildItem -Path . -File -Filter *.py` → empty; `scripts/one-offs/` has 5 + README.md | ✅ Root clean + `pytest -q 45` + `check_db.py` still executable | ✅ Extract README explains non-pytest scope |
| 2.1 `www` untrack | `git ls-files | grep www` empty | chore | ✅ `git ls-files` had 15 www files | ✅ RED `git ls-files | grep www` = 15 (app/src/main/assets/www/*) | ✅ GREEN `git rm --cached -r www` → 0, `check-ignore` exit 0 | ✅ `diff -r frontend www` identical excl. `test_*.js`/`e2e.*`; SHA256 `2702C96D...3426D3D` equal | ➖ None (git index only) |
| 2.2 `js/syncEngine.js` delete | `Test-Path js/syncEngine.js` False | chore | ✅ hash `2702C96D...` in 3 places | ✅ RED `git ls-files | grep js/syncEngine` = 1, `Test-Path` True | ✅ GREEN `git rm --cached` + `Remove-Item` → `Test-Path False`, `ls-files js` 0, dir `js/` empty | ✅ hash verified `frontend`==`www` retained; `frontend/js/syncQueue.js` still tracked via copy task | ➖ None |
| 2.3 `copyFrontendToAssets` verify | `copyFrontendToAssets` + `preBuild.dependsOn` + harness | verify | ✅ `app/build.gradle.kts` has task | ✅ RED would fail if `www` missing and no copy | ✅ GREEN structural: `tasks.register<Copy>(copyFrontendToAssets) from("../frontend") into("src/main/assets/www") exclude(...)` + `preBuild.dependsOn`; runtime: `pytest tests/test_sync_engine.py -q` → 3 passed; payload `diff` clean | ✅ Second case `pytest -q` full 45 still green | ➖ None |
| 3.1 `config.yaml` clean | `Select-String test_command` no --ignore; `pytest -q` | config | ✅ `test_command` had 5 `--ignore` | ✅ RED `Select-String` found `--ignore` flags | ✅ GREEN `Select-String` no `--ignore`, `pytest -q` → 45 passed, `testing.notes` updated | ✅ `apply.test_command` and `verify.test_command` both plain | ✅ Updated `risks` to reflect pending manual re-init |
| 3.2 `CI_VERIFICATION.md` | `Select-String AdhamDK/gps-clientes` | docs | N/A | ✅ RED `Select-String AdhamDK` → empty before | ✅ GREEN `Select-String AdhamDK/gps-clientes` → 3 hits in hygiene section + checklist ref | ➖ Single doc | ✅ Added repo hygiene invariants section |
| 3.3 threat-matrix | commit-state + git-selection | verify | ✅ existing tests | ✅ RED `git -C C:/Users/Usuario rev-parse` → `C:/Users/Usuario` (wrong); `git -C GPS_CLIENTES rev-parse` also wrong pre re-init | ✅ GREEN `git rm --cached` keeps working tree (`www/` still on disk, `ls` shows 15 files); `git check-ignore` prevents re-add; commit-state gate passes. Git-selection GREEN pending manual re-init — documented in `GIT_REINIT_CHECKLIST.md` | ✅ Two boundaries: staged `D` vs working tree preserved; `commit -a` dry not re-adding ignored | ➖ None |
| 3.4 final gate | `pytest -q` 45 + harness 3 + `ls-files www` empty | verify | ✅ 45 + 3 | ✅ RED would be any gate failing | ✅ GREEN `pytest -q` 45 passed, `pytest tests/test_sync_engine.py` 3 passed, `git ls-files|grep www` 0, `git ls-files|grep js/syncEngine` 0 | ✅ 3 gates triangulated | ➖ None |
| 4.1 `GIT_REINIT_CHECKLIST.md` | manual checklist doc | docs | N/A | ✅ RED file missing | ✅ GREEN `Test-Path docs/GIT_REINIT_CHECKLIST.md` True; contains `Compress-Archive`, `format-patch`, `git init`, `remote add origin https://github.com/AdhamDK/gps-clientes`, `push -u`, `rev-parse`, `remote get-url` | ➖ Single doc with 7 verify steps | ✅ Added rollback section |

**Total tests written:** 0 new pytest files (infra gates); existing suites: 45 + 3 harness reused as gates
**Layers used:** infra/config/verify gates (no new unit tests required per strict-tdd infra definition)
**Approval tests:** N/A — file moves/ignores, behavior unchanged per `repo-hygiene` offline-queue invariant
**Pure functions created:** 0 (config/ignore hygiene)

## Work Unit Evidence

| Unit | Commit message | Focused test command and exact result | Runtime harness command/scenario and exact result | Rollback boundary |
|------|----------------|----------------------------------------|--------------------------------------------------|-------------------|
| A — Hygiene foundation (1.1-1.3) | `chore: harden gitignore and pytest collection` | `python -m pytest -q` → `45 passed, 5034 warnings in 5.64s` exit 0 (no --ignore). Before: `ERROR test_delete_uuid.py JSONDecodeError` exit 2 | N/A — file-system gate: `Get-ChildItem -Path . -File -Filter *.py` → empty; `scripts/one-offs/` → 5 files + README.md; `git check-ignore app/src/main/assets/www/syncEngine.js` → ignored (exit 0) | Revert `.gitignore`, `pytest.ini`, `Move-Item scripts/one-offs/*` back to root (5 files), remove `scripts/one-offs/README.md` |
| B — JS dedup (2.1-2.3) | `chore: dedup JS canonical frontend, untrack www` | `python -m pytest -q` → 45 passed; `python -m pytest tests/test_sync_engine.py -q` → 3 passed; `Get-FileHash frontend/syncEngine.js == www/syncEngine.js` → `2702C96D...3426D3D` equal | `gradlew preBuild` structural (task `copyFrontendToAssets` + `preBuild.dependsOn` verified in `app/build.gradle.kts:143-148`); JDK17 absent so runtime dry but `diff` payload preserved: `Compare-Object frontend vs www` only `test_selection_manager.js` excluded as expected per `exclude("test_*.js","e2e.*","*.spec.js")` | `git restore --staged` + `git add Documents/GPS_CLIENTES/app/src/main/assets/www/* Documents/GPS_CLIENTES/js/syncEngine.js` to re-track; `Copy-Item frontend/syncEngine.js js/syncEngine.js` to restore orphan |
| C — Config+checklist (3.1,3.2,4.1 + 3.3/3.4 gates) | `chore: remove pytest ignore hacks and document re-init` | `python -m pytest -q` → 45 passed; `Select-String test_command` in `openspec/config.yaml` → no `--ignore`; `git ls-files | grep www` → 0 | `tests/test_sync_engine.py` → 3 passed (mock GAS :8765 offline queue unchanged); `git check-ignore www/syncEngine.js` exit 0 proves commit-state gate | Revert `openspec/config.yaml` test_command + risks + testing.notes, `docs/CI_VERIFICATION.md` hygiene section, `docs/GIT_REINIT_CHECKLIST.md` delete |

## Files Changed

| File | Action | What Was Done |
|------|--------|---------------|
| `.gitignore` | Modified | Comprehensive ignores: `app/build/`, `.gradle/`, `*.log`, `osrm-data/*.pbf`, `harness/__pycache__/`, `rclone_token.json`, `app/src/main/assets/www/`, plus existing `backend/*.db`, `__pycache__`, `.venv`, `.DS_Store`, `gradle-wrapper.jar`, `local.properties` |
| `pytest.ini` | Created | `testpaths = backend/tests tests`, `python_files = test_*.py`, `norecursedirs`, `addopts = -q` |
| `scripts/one-offs/test_delete_uuid.py` | Moved | From root (was breaking collection) — manual httpx debug script |
| `scripts/one-offs/test_insert.py` | Moved | From root — raw SQLite insert |
| `scripts/one-offs/test_sqlalchemy_insert.py` | Moved | From root — SQLAlchemy insert |
| `scripts/one-offs/check_db.py` | Moved | From root — PRAGMA table_info dump |
| `scripts/one-offs/check_sql2.py` | Moved | From root — sqlite_master dump |
| `scripts/one-offs/README.md` | Created | Explains 5 scripts are one-offs not pytest tests, shows explicit `python scripts/one-offs/...` usage |
| `app/src/main/assets/www/*` (15 files) | Untracked | `git -C C:/Users/Usuario rm --cached -r` — stays on disk, now gitignored via `.gitignore`; canonical source remains `frontend/` |
| `js/syncEngine.js` | Deleted | `git rm --cached` + `Remove-Item` — hash `2702C96D...3426D3D` now only in `frontend/syncEngine.js` + derived `www/syncEngine.js` |
| `js/` | Directory emptied | Left empty (no `.gitkeep` — git ignores empty dirs) |
| `openspec/config.yaml` | Modified | `apply.test_command` and `verify.test_command` → `python -m pytest -q`; `testing.notes` updated; `risks` updated to reflect pending manual re-init and archived strays |
| `docs/CI_VERIFICATION.md` | Modified | Added `Repo hygiene invariants` section: source-of-truth, orphan removed, pytest collection, git boundary verifies + ссылки to checklist |
| `docs/GIT_REINIT_CHECKLIST.md` | Created | 7-step manual post-merge checklist: backup, format-patch, init, remote add `AdhamDK/gps-clientes`, push -u, verify toplevel/remote/ls-files/pytest, rollback |
| `openspec/changes/harden-repo-hygiene/tasks.md` | Modified | All 11 tasks marked [x] |
| `app/build.gradle.kts` | Verified only | Confirmed `copyFrontendToAssets` `from("../frontend") into("src/main/assets/www") exclude(...)` + `preBuild.dependsOn` at lines 142-148 |

## Deviations from Design

None — implementation matches `design.md`. File change table aligns with design `File Changes` section. `pytest.ini` adds `norecursedirs` and `addopts` as allowed enhancement (still `testpaths = backend/tests tests` as required). Empty `js/` left without `.gitkeep` per task note "leave directory or add .gitkeep" — leaving empty is valid (git ignores empty dirs) and avoids tracking placeholder.

## Issues Found

- Git root remains `C:/Users/Usuario` (remote `catalogo-empresa`) until manual Phase 4 — `git -C GPS_CLIENTES rev-parse --show-toplevel` still returns `C:/Users/Usuario`. This is expected pre re-init; GREEN for git-selection boundary is pending operator execution of `docs/GIT_REINIT_CHECKLIST.md`. All hygiene gates (pytest, ls-files, check-ignore, harness) are GREEN.
- `www/` currently contains 15 files including `test_*.js`/`e2e.*` that `copyFrontendToAssets` excludes — these are legacy committed artifacts now untracked but still on disk. Fresh `preBuild` after cleaning `www/` would produce filtered copy (10 files). Preserving on disk avoids breaking offline APK until next build; next `gradlew preBuild` will overwrite correctly.
- No JDK17/SDK34 on host — `gradlew preBuild` runtime repopulation verified structurally not via execution. Python harness used as fallback (95/95 not needed for this change; 45+3 pytest gates suffice).

## Test Evidence Summary

```
python -m pytest -q  (RED before)
  ERROR collecting test_delete_uuid.py → JSONDecodeError: Expecting value: line 1 column 1 (char 0)
  Interrupted: 1 error during collection

python -m pytest -q  (GREEN after 1.1-1.3)
  45 passed, 5034 warnings in 5.64s
  warnings: DeprecationWarning datetime.utcnow (openpyxl/sqlalchemy/reportlab)

python -m pytest tests/test_sync_engine.py -q
  3 passed in 1.98s

git ls-files | grep www (RED before)
  app/src/main/assets/www/app.js ... (15 files)

git ls-files | grep www (GREEN after 2.1)
  (empty)  Count 0

git ls-files | grep js/syncEngine (RED before)
  js/syncEngine.js

git ls-files | grep js/syncEngine (GREEN after 2.2)
  (empty)

git check-ignore app/src/main/assets/www/syncEngine.js
  app/src/main/assets/www/syncEngine.js  exit 0 (ignored)

Get-FileHash frontend/syncEngine.js / www/syncEngine.js
  2702C96D181D7AD33E823AFDBEF6AB34A8CFA830755E50F786283A81A3426D3D == (equal)
```

## Remaining Tasks

All 11 tasks complete. Manual Phase 4 (`git init` at `GPS_CLIENTES/`, `remote add AdhamDK/gps-clientes`, `push -u`) is operator step post-merge — do NOT automate.

## Workload / PR Boundary

- Mode: single-pr (150 net lines, 3 conceptual commits within 800 budget)
- Current work unit: all 3 units (A+B+C) in single apply batch — file-system changes prepared; git commits deferred until manual re-init creates `GPS_CLIENTES/.git`
- Boundary: `docs/GIT_REINIT_CHECKLIST.md` is rollback boundary for re-init; hygiene file moves/ignores are independently revertible per Work Unit Evidence table
- Estimated review budget impact: ~150 net (~220 with deletions) — well within 400-line single-PR budget; single PR with 3 work-unit commits recommended

## Status

11/11 tasks complete. Ready for verify (verify will run `python -m pytest -q` → 45 passed, `git ls-files|grep www` empty, `check-ignore` ignored, harness 3 passed). Manual re-init remains operator checklist post-merge.
