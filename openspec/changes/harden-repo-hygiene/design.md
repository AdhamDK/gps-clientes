# Design: harden-repo-hygiene

## Technical Approach

Infra-only hygiene (~150 net lines, 3 commits, single PR). Fixes git boundary, JS triplication (`2702C96D...3426D3D`), and pytest collection without domain changes. `frontend/` is canonical; `app/src/main/assets/www/` is derived via existing `copyFrontendToAssets` (`from("../frontend")` → `into("src/main/assets/www")`, `preBuild.dependsOn`). Apply handles hygiene (`.gitignore`, dedup, `pytest.ini`, relocations); git re-init is documented manual step — apply never runs `git init` automatically.

Covers `repo-hygiene` invariants: Build Source-of-Truth, Test Collection, Git Boundary, Offline Queue (unchanged).

## Architecture Decisions

| Decision | Chosen | Alternatives | Rationale |
|----------|--------|--------------|-----------|
| Git boundary | Re-init at `GPS_CLIENTES/` + new remote `AdhamDK/gps-clientes`, patch `97d90db9` | filter-branch / sparse checkout | Avoids rewriting 200+ files under `C:/Users/Usuario`; clean boundary, one-time manual step |
| JS dedup | `frontend/` canonical, `www/` gitignored (`git rm --cached`), delete `js/` | Symlink / keep both + sync | Gradle already defines single source; symlink breaks Windows + `WebViewAssetLoader`; sync drifts |
| Stray scripts | Move 5 files to `scripts/one-offs/` + `pytest.ini testpaths = backend/tests tests` | Rename / `conftest` guard | Preserves executability, removes `--ignore` hacks from `openspec/config.yaml` |

## Data Flow

```
frontend/ (tracked) ──copyFrontendToAssets──▶ app/src/main/assets/www/ (gitignored, derived)
  from("../frontend")  exclude("test_*.js","e2e.*","*.spec.js")
  into("src/main/assets/www")  ── dependsOn ──▶ preBuild
```

`syncEngine.js`/`syncQueue.js` follow same copy; `entregado_local` + `replayQueue` unchanged.

## File Changes

Before → After:

```
BEFORE (wrong root C:/Users/Usuario/.git)       AFTER (GPS_CLIENTES/.git)
GPS_CLIENTES/.gitignore (4 lines)            →  .gitignore (+www/, scripts/one-offs/, __pycache__/, .venv/, app/build/, .gradle/, *.log, .DS_Store, osrm-data/*.pbf)
js/syncEngine.js ─┐                           →  js/ removed
frontend/syncEngine.js ─┤ hash 2702C96D       →  frontend/syncEngine.js (canonical)
app/.../www/* ────────┘                       →  app/.../www/ (gitignored, derived)
test_*.py (3), check_*.py (2) at root        →  scripts/one-offs/ (5 files + README.md)
(no pytest.ini)                               →  pytest.ini
```

| File | Action | Description |
|------|--------|-------------|
| `.gitignore` | Modify | Add `www/`, `scripts/one-offs/`, `__pycache__/`, `.venv/`, `app/build/`, `.gradle/`, `backend/*.db`, `*.log`, `.DS_Store`, `osrm-data/*.pbf` |
| `js/syncEngine.js` | Delete | `git rm --cached` + `Remove-Item`; hash `2702C96D...3426D3D` |
| `app/src/main/assets/www/*` | Untrack | `git rm -r --cached` — stays on disk, now ignored |
| `scripts/one-offs/` | Create | Dir + `README.md`; 5 moved files via `git mv` |
| `pytest.ini` | Create | `testpaths = backend/tests tests` |
| `openspec/config.yaml` | Modify | `verify.test_command` and `apply.test_command` → `python -m pytest -q`; update `risks` |
| `docs/CI_VERIFICATION.md` | Modify | Remote `AdhamDK/gps-clientes`, toplevel `GPS_CLIENTES`, wrapper notes |
| `app/build.gradle.kts` | Verify only | Confirm `copyFrontendToAssets` + `preBuild.dependsOn` |

## Interfaces / Contracts

No domain changes:

```ini
# pytest.ini
[pytest]
testpaths = backend/tests tests
```

## Testing Strategy

| Layer | What | Approach |
|-------|------|----------|
| Unit | `pytest -q` collects 45 without ignores | `python -m pytest -q` → 45 passed, no `JSONDecodeError` |
| Integration | `www/` repopulated byte-identical | Delete `www/`, run copy task, `diff -r frontend www/` + SHA256 `syncEngine.js` |
| Integration | `www/` untracked | `git ls-files \| grep www` empty; `git check-ignore www/syncEngine.js` = ignored |
| Integration | Stray files relocated | Root `*.py` empty; `scripts/one-offs/` has 5 files |
| Harness | Offline queue unchanged | `tests/test_sync_engine.py` vs mock GAS `:8765` passes |
| Manual | Git boundary | `git rev-parse --show-toplevel` → `GPS_CLIENTES`; `git remote get-url origin` → `AdhamDK/gps-clientes` |

## Threat Matrix

| Boundary | Applicability | Design response | RED test |
|----------|---------------|-----------------|----------|
| Documentation-like paths | N/A — only `.py` one-offs, no executable doc reclassification | No new execution boundary | None |
| Git repository selection | **Applicable** — toplevel `C:/Users/Usuario` → `GPS_CLIENTES/` | Use `git -C GPS_CLIENTES` ; verify toplevel before writes | `git -C GPS_CLIENTES rev-parse --show-toplevel` must fail at wrong cwd |
| Commit state | **Applicable** — `git rm --cached` keeps working tree | Stage only intended deletes; `git status` shows cached `D` | `git commit -a` must not re-add ignored `www/` |
| Push state | **Applicable** — new remote first push | `git push -u origin feature/gps-clientes-filtro-seleccion` explicit refspec | Push without `-u` fails tracking |
| PR commands | N/A — no `gh pr create` automation | — | None |

Applicable rows carry to `tasks.md` as RED-before-GREEN.

## Migration / Rollout

Single PR, no flags/DB migration. Rollback: `git restore` for hygiene; restore `GPS_CLIENTES_backup_20260830_142728.zip` for re-init.

**Automated (apply):** patch `.gitignore`, create `pytest.ini`, `git mv` 5 files to `scripts/one-offs/`, `git rm --cached js/syncEngine.js` + `www/`, update `config.yaml` + `CI_VERIFICATION.md`, verify (`pytest -q`, `ls-files`, `diff -r`).

**Manual (user consent required — NOT run by apply):**
```powershell
Compress-Archive -Path GPS_CLIENTES -DestinationPath GPS_CLIENTES_backup_20260830_142728.zip
git -C C:/Users/Usuario format-patch origin/feature/gps-clientes-filtro-seleccion --stdout > C:/Temp/gps-clientes.patch
git -C GPS_CLIENTES init; git -C GPS_CLIENTES remote add origin https://github.com/AdhamDK/gps-clientes.git
# apply patch 97d90db9 + hygiene commits
git -C GPS_CLIENTES push -u origin feature/gps-clientes-filtro-seleccion
git -C GPS_CLIENTES rev-parse --show-toplevel; git -C GPS_CLIENTES remote get-url origin
```

## Open Questions

- [ ] `AdhamDK/gps-clientes` exists/empty before first push?
- [ ] JDK17+SDK34 missing — `preBuild` diff structural until CI
