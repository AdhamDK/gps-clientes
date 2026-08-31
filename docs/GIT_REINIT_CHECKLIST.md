# Git Re-init Checklist — harden-repo-hygiene (Phase 4, manual post-merge)

> **Do NOT automate.** Run these steps manually after PR `harden-repo-hygiene` merges.
> Apply never runs `git init` automatically — operator consent required.

## Preconditions

- PR merged to `feature/gps-clientes-filtro-seleccion` (or `main` if rebased)
- Remote `https://github.com/AdhamDK/gps-clientes` exists (empty or initialized)
- Current wrong root is `C:/Users/Usuario` with remote `catalogo-empresa` — will be replaced
- Backup required before destructive operation

## Steps

### 1. Backup

```powershell
Compress-Archive -Path C:/Users/Usuario/Documents/GPS_CLIENTES -DestinationPath C:/Temp/GPS_CLIENTES_backup_20260830_142728.zip -Force
# Verify
Test-Path C:/Temp/GPS_CLIENTES_backup_20260830_142728.zip
```

### 2. Export patch for hygiene commits (optional safety net)

```powershell
git -C C:/Users/Usuario format-patch origin/feature/gps-clientes-filtro-seleccion --stdout > C:/Temp/gps-clientes.patch
# Should contain patch 97d90db9 + hygiene commits (chore: harden gitignore, chore: dedup JS, chore: remove pytest hacks)
Get-Content C:/Temp/gps-clientes.patch | Select-String "harden-repo-hygiene" | Select-Object -First 5
```

### 3. Re-init git at GPS_CLIENTES

```powershell
# Remove old meta if present (GPS_CLIENTES/.git does not exist yet — this is fresh init)
git -C C:/Users/Usuario/Documents/GPS_CLIENTES init
git -C C:/Users/Usuario/Documents/GPS_CLIENTES status
git -C C:/Users/Usuario/Documents/GPS_CLIENTES rev-parse --show-toplevel
# Expected: C:/Users/Usuario/Documents/GPS_CLIENTES
```

### 4. Add new remote

```powershell
git -C C:/Users/Usuario/Documents/GPS_CLIENTES remote add origin https://github.com/AdhamDK/gps-clientes.git
git -C C:/Users/Usuario/Documents/GPS_CLIENTES remote get-url origin
# Expected: https://github.com/AdhamDK/gps-clientes.git (or ssh form git@github.com:AdhamDK/gps-clientes.git)
```

### 5. Apply patch / re-create commits

Option A — if patch exists:
```powershell
git -C C:/Users/Usuario/Documents/GPS_CLIENTES am C:/Temp/gps-clientes.patch
```

Option B — manual add:
```powershell
git -C C:/Users/Usuario/Documents/GPS_CLIENTES add .
git -C C:/Users/Usuario/Documents/GPS_CLIENTES commit -m "chore: harden repo hygiene"
```

### 6. Push

```powershell
git -C C:/Users/Usuario/Documents/GPS_CLIENTES push -u origin feature/gps-clientes-filtro-seleccion
# Verify tracking
git -C C:/Users/Usuario/Documents/GPS_CLIENTES branch -vv | Select-String "feature/gps-clientes-filtro-seleccion"
```

### 7. Verify invariants

```powershell
git -C C:/Users/Usuario/Documents/GPS_CLIENTES rev-parse --show-toplevel
# → .../GPS_CLIENTES

git -C C:/Users/Usuario/Documents/GPS_CLIENTES remote get-url origin
# → AdhamDK/gps-clientes

git -C C:/Users/Usuario/Documents/GPS_CLIENTES ls-files | Select-String "www"
# → (empty)

git -C C:/Users/Usuario/Documents/GPS_CLIENTES check-ignore app/src/main/assets/www/syncEngine.js
# → app/src/main/assets/www/syncEngine.js (ignored)

python -m pytest -q
# → 45 passed

Test-Path C:/Users/Usuario/Documents/GPS_CLIENTES/js/syncEngine.js
# → False (deleted, only frontend/ canonical remains)
```

## Rollback

If re-init fails, restore backup:

```powershell
Expand-Archive -Path C:/Temp/GPS_CLIENTES_backup_20260830_142728.zip -DestinationPath C:/Users/Usuario/Documents -Force
git -C C:/Users/Usuario remote get-url origin
# Should still be catalogo-empresa until next attempt
```

## Post-check

- `frontend/` remains canonical; `app/src/main/assets/www/` repopulated via `./gradlew preBuild` (requires JDK17+SDK34)
- `scripts/one-offs/` holds 5 debug scripts + README.md
- `.gitignore` covers `app/build/`, `.gradle/`, `app/src/main/assets/www/`, `*.log`, `osrm-data/*.pbf`, `__pycache__/`, `.venv/`, `rclone_token.json`
