# Proposal: Harden Repo Hygiene

## Intent

Fix hygiene blocking CI: git root at `C:/Users/Usuario`, triple `syncEngine.js` (`2702C96D...3426D3D`), committed derived `www/`, stray `test_*.py` breaking `pytest`. `frontend/` canonical; `entregado` untouched.

## Scope

### In Scope
- Re-init git at `GPS_CLIENTES/` with remote `AdhamDK/gps-clientes`
- `.gitignore` + untrack derived `app/src/main/assets/www/` (`copyFrontendToAssets`)
- Dedup: `frontend/` canonical; delete orphan `js/syncEngine.js`; `syncQueue` same pattern
- Move 5 stray files (`test_*.py`, `check_*.py`) -> `scripts/one-offs/`
- Add `pytest.ini` so `python -m pytest -q` collects 45 tests
- Verify `preBuild` repopulates `www/` on fresh clone

### Out of Scope
- `datetime.utcnow` modernization, `API_URL` centralization, Kotlin `Int`/`Long` — next change
- Coverage/lint/typecheck, VROOM/OSRM/GAS behavior

## Capabilities

### New Capabilities
- None.

### Modified Capabilities
- None — no spec changes.

## Approach

| Decision | Opt 1 (chosen) | Opt 2 | Opt 3 |
|----------|----------------|-------|-------|
| Git boundary | Re-init + new remote | Fix-in-place | Sparse checkout |
| JS dedup | Single `frontend/` + ignore `www/` | Symlink | Keep both + sync script |
| Stray scripts | Move + `pytest.ini` | Rename | `conftest` guard |

**Chosen: Option 1 each.** Re-init avoids rewriting 200+ files; patch `97d90db9`. Single source matches Gradle `from("../frontend")`. Move preserves scripts; `pytest.ini` removes `--ignore`. ~150 net lines, 3 commits.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `.git/` | New | Re-init at `GPS_CLIENTES/` |
| `.gitignore` | Modified | Add `www/`, `scripts/one-offs/`, `__pycache__/`, `.venv/` |
| `js/syncEngine.js` | Removed | Dupe of `frontend/syncEngine.js` |
| `frontend/**` | Unchanged | Canonical |
| `www/**` | Untracked | Derived via `copyFrontendToAssets` |
| `test_*.py`, `check_*.py` | Moved | -> `scripts/one-offs/` |
| `pytest.ini` | New | `testpaths = backend/tests tests` |
| `openspec/config.yaml` | Modified | Update `risks`, `verify.test_command` |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| History loss | High | `format-patch`; backup `GPS_CLIENTES_backup_20260830_142728.zip` |
| Scripts break | Med | Keep exec in `scripts/one-offs/`; no importers |
| Fresh clone missing `www/` | Low | `preBuild.dependsOn` wired; verify `gradlew preBuild` |
| Staged deletes | Med | `git rm -r --cached www/` |

## Rollback Plan

Restore backup zip, revert remote; after merge revert PR and rerun `copyFrontendToAssets`. No DB migration.

## Dependencies

None. Fresh clone needs `gradle wrapper --gradle-version 8.6`.

## Success Criteria

- [ ] `git rev-parse --show-toplevel` == `.../GPS_CLIENTES`; remote `AdhamDK/gps-clientes`
- [ ] `git ls-files | grep www` empty; `preBuild` recreates `www/syncEngine.js` (`2702C96D...`)
- [ ] `js/syncEngine.js` deleted; `frontend/` sole source
- [ ] `python -m pytest -q` passes 45 without `--ignore`
- [ ] `scripts/one-offs/` holds 5 files; root clean
- [ ] Single PR ~150 lines, 3 commits — within 800 budget
