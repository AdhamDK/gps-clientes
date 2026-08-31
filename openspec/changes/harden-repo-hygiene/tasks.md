# Tasks: harden-repo-hygiene

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~150 net (~220 w/deletions) |
| 400-line budget risk | Low |
| Chained PRs recommended | No |
| Suggested split | Single PR, 3 commits |
| Delivery strategy | single-pr |
| Chain strategy | pending |

Decision needed before apply: No
Chained PRs recommended: No
Chain strategy: pending
400-line budget risk: Low

Single PR fits 800 budget (150 << 400). No chaining.

### Suggested Work Units

| Unit | Goal | PR | Focused test | Runtime harness | Rollback |
|------|------|----|--------------|-----------------|----------|
| 1 | Hygiene foundation | PR#1 A | `pytest -q` 45 passed | N/A — `git ls-files/check-ignore` | Revert .gitignore, pytest.ini, 5 moves |
| 2 | JS dedup | PR#1 B | `pytest -q` + `diff -r frontend www` | `gradlew preBuild`; `tests/test_sync_engine.py` | Revert rm --cached js/+www/ |
| 3 | Config+checklist | PR#1 C | `pytest -q`; `ls-files \| grep www` empty | Mock GAS :8765 | Revert config.yaml, CI_VERIFICATION, checklist |

TDD strict_tdd true: infra RED is failing `pytest -q`/`ls-files` before GREEN edit. Order 1→2→3. 1.1‖1.2 parallel. 4 post-merge manual.

## Phase 1: Foundation

- [x] 1.1 Harden `.gitignore` [infra|deps:none|files:.gitignore|est:20|AC:Standard ignores+Derived www ignored] → `git check-ignore www/syncEngine.js`
- [x] 1.2 Create `pytest.ini` `testpaths=backend/tests tests` [config|deps:none|files:pytest.ini|est:3|AC:Default pytest passes+Legacy flags removed] → `pytest -q` 45 passed
- [x] 1.3 `git mv` 5 strays to `scripts/one-offs/`+README [chore|deps:1.1,1.2|files:scripts/one-offs/*|est:30|AC:Root has no stray breakers] → `Get-ChildItem *.py` clean; `pytest -q` 45

## Phase 2: JS Dedup

- [x] 2.1 Untrack `www/` `git rm -r --cached app/src/main/assets/www/` [chore|deps:1.1|files:www/*|est:50 del|AC:Derived www ignored+untracked] → `git ls-files|grep www` empty
- [x] 2.2 Delete `js/syncEngine.js` [chore|deps:2.1|files:js/syncEngine.js|est:5+del|AC:Orphan JS removed 2702C96D] → `Test-Path js/syncEngine.js` False
- [x] 2.3 Verify `copyFrontendToAssets` [verify|deps:2.1,2.2|files:build.gradle.kts(verify)|est:0|AC:Fresh clone repopulates+Offline harness] → `gradlew preBuild; diff -r frontend www` clean; `pytest tests/test_sync_engine.py -q`

## Phase 3: Config Cleanup

- [x] 3.1 Clean `openspec/config.yaml` → `python -m pytest -q` [config|deps:1.2|files:openspec/config.yaml|est:10|AC:Legacy flags removed] → `Select-String test_command` no --ignore; `pytest -q` 45
- [x] 3.2 Update `docs/CI_VERIFICATION.md` [docs|deps:3.1|files:docs/CI_VERIFICATION.md|est:15|AC:Toplevel+Remote] → `Select-String AdhamDK/gps-clientes`
- [x] 3.3 RED/GREEN threat-matrix (commit-state, git-selection) [verify|deps:2.1|files:—|est:0|AC:Commit state+Git selection] → RED `git -C C:/Users/Usuario rev-parse --show-toplevel` wrong; GREEN `git -C GPS_CLIENTES rev-parse` ends GPS_CLIENTES
- [x] 3.4 Final gate [verify|deps:3.1,2.3|files:—|est:0|AC:All invariants] → `pytest -q` 45; `pytest tests/test_sync_engine.py -q`; `git ls-files|grep www` empty

## Phase 4: Manual Re-init (Post-Merge, NOT Automated)

- [x] 4.1 Author `docs/GIT_REINIT_CHECKLIST.md` [docs|deps:1-3|files:docs/GIT_REINIT_CHECKLIST.md|est:40|AC:Toplevel+Remote] → after merge: `Compress-Archive GPS_CLIENTES *.zip; git -C C:/Users/Usuario format-patch origin/feature/gps-clientes-filtro-seleccion --stdout > C:/Temp/patch; git -C GPS_CLIENTES init; remote add origin https://github.com/AdhamDK/gps-clientes.git; push -u origin feature/gps-clientes-filtro-seleccion; rev-parse --show-toplevel; remote get-url origin`

Commits: A `chore: harden gitignore and pytest collection` (1.1-1.3) → B `chore: dedup JS canonical frontend, untrack www` (2.1-2.3) → C `chore: remove pytest ignore hacks and document re-init` (3.1,3.2,4.1). Each green `pytest -q`.
