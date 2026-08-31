# Protocolo de Sincronización Google Sheets (Apps Script)

## Endpoints de Google Apps Script (Web App)
- GET `?lastSync={ISO_TIMESTAMP}` -> Devuelve lista de clientes con `updated_at` > `lastSync`
  - Nota compat: `?last_sync` sigue aceptado como alias deprecated (usar camelCase).
- POST JSON payload -> Recibe array de clientes locales pendientes (`sync_status = 0`)
  - Estrategia Upsert en Hoja: Buscar por columna `id` (UUID). Si existe, actualiza la fila sólo si `updated_at` recibido es más reciente. Si no existe, inserta nueva fila.

## Regla de Conflicto (Last-Write-Wins)
- Comprara timestamps ISO-8601. La modificación con fecha/hora UTC más reciente sobrescribe la anterior.

## Convención lastSync (A3 — 2026-08-30)
- Canonical: `lastSync` camelCase en todos los componentes: `frontend/app.js`, `frontend/syncEngine.js`, `js/syncEngine.js`, `harness/mock_gas.py`, `backend/Code.gs`.
- Motivo: `frontend/app.js` y `syncEngine.js` ya usaban `localStorage.getItem('lastSync')` y `GET ?lastSync=`; snake_case era inconsistente y rompía coherencia sync.
- Compat backward: backend (Code.gs / mock_gas.py) acepta también `last_sync` pero se documenta como deprecated y se loggea si se usa.