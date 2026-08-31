# GPS_CLIENTES — Docs Index

This folder contains architecture, verification, and design notes for the offline-first delivery routing system (El Vigia). The canonical project README is `../README.md`.

- [`../README.md`](../README.md) — System overview, stack, quick start (`docker compose up`, `uvicorn backend.main:app`, `frontend` via `/app`, `gradlew assembleDebug`), endpoints, sync `lastSync`, pagination/limits, tests (`pytest backend/tests -q` 42 tests), WebView 0-divergence + loop1/2 decisions, pending items, backup `GPS_CLIENTES_backup_20260830_142728.zip`.
- `ARQUITECTURA_FIXES_2026-08-30.md` — Delta fixes C1/S1/A1/S3/A3 (loop1) + atomic route / export guard / pooling / pagination 500 (loop2), data-flow diagram (text), verification checklist.
- `LOOP_2_CAMBIOS.md` — Loop 2 structural changes: atomic transaction, `logger.warning` instead of silent `except: pass`, export `413` guard 5000, `httpx.Limits`, `PAGINACION_LIMITE=500` banner. Rationale and pending table.
- `CHANGELOG_2026-08-30.md` — Consolidated bullet changelog for loops 1+2.
- `e2e/gps-unificado-parity.md` — E2E parity matrix (center zoom 16, FAB 44px, clusters, themes) plus pagination 500 and CORS whitelist criteria (loop2).
- `DESIGN_WEBVIEW_WRAPPER.md` — WebViewAssetLoader design (`https://appassets.androidplatform.net`, `ENABLE_WEBVIEW` flag, GPS/theme bridges, rollback).
- `CI_VERIFICATION.md` — Environment diagnosis (stub `gradlew`/no SDK on this host), JDK17/SDK34 + wrapper jar bootstrap, harness fallback `harness_gps2.py` 95/95.
- `TASKS_WEBVIEW_WRAPPER.md`, `MOBILE_TEST_GUIDE.md` — Tasks and device test guide.
- `ARQUITECTURA_PROPIA_SPECS.txt` — Legacy consolidated spec dump.
