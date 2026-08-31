# Technical Design: netlify-gas-frontend

## 1. Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                     NETLIFY (HTTPS)                             │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────────┐  │
│  │  Frontend    │    │ Service      │    │  CONFIG Module   │  │
│  │  (Leaflet/JS)│◄───│  Worker      │◄───│  GAS_URL (env)   │  │
│  └──────┬───────┘    └──────┬───────┘    └────────┬─────────┘  │
└─────────│───────────────────│─────────────────────│────────────┘
          │                   │                     │
          ▼                   ▼                     ▼
┌───────────────────────┐ ┌─────────────┐ ┌─────────────────────┐
│  GAS Web App (HTTPS)  │ │ Localhost   │ │  Netlify Env Vars   │
│  ┌─────────────────┐  │ │ :8000 (HTTP)│ │  GAS_URL=...        │
│  │ doGet/doPost    │  │ │             │ │                     │
│  │ ├─ clientes     │  │ │ VROOM :3000 │ │                     │
│  │ ├─ sync         │  │ │ OSRM  :5000 │ │                     │
│  │ ├─ rutas_hoy    │  │ │             │ │                     │
│  │ └─ entregado    │  │ └─────────────┘ │                     │
│  └─────────────────┘  │                 │                     │
│  Google Sheets (DB)   │  LOCAL DOCKER   │                     │
└───────────────────────┘                 │                     │
                                          ▼                     │
                              ┌─────────────────────┐          │
                              │ routing-client.js   │          │
                              │ (nearest-neighbor   │          │
                              │  + 2-opt fallback)  │          │
                              └─────────────────────┘          │
```

**Hybrid Split**: All CRUD, sync, route management → GAS/Sheets. Only `optimizarRuta` → Local Docker. Client-side fallback when localhost unavailable.

## 2. GAS Web App Routing

### Dispatcher (`Code.gs`)
```javascript
function doGet(e) { return routeRequest(e.parameter); }
function doPost(e) { return routeRequest(JSON.parse(e.postData.contents)); }

function routeRequest(params) {
  const action = params.action || 'clientes';
  delete params.action;
  const handlers = {
    'clientes': handleClientes,
    'sync': handleSync,
    'rutas_hoy': handleRutasHoy,
    'entregado': handleEntregado,
    'import': handleImport,
    'export': handleExport
  };
  const handler = handlers[action] || (() => ({error: 'Unknown action'}));
  return withCors(handler(params));
}
```

### Action Map
| Action | Method | Params | Returns |
|--------|--------|--------|---------|
| `clientes` | GET | `limit`, `offset`, `search` | `{data: [], total: N}` |
| `sync` | POST | `{clients: [], lastSync: ISO}` | `{synced: N, conflicts: []}` |
| `rutas_hoy` | GET | `fecha` (default today) | `{rutas: [], fecha}` |
| `entregado` | POST | `{cliente_ids: [], fecha}` | `{updated: N, fecha}` |
| `import` | POST | `{base64_xlsx: string}` | `{imported: N, errors: []}` |
| `export` | GET | — | Base64 xlsx |

### CORS Headers (every response)
```javascript
function withCors(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON)
    .setHeaders({
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type',
      'Access-Control-Max-Age': '86400'
    });
}
```

### Error Envelope
```javascript
{ error: string, code: string, details?: any }
```

## 3. Sheets Data Model

### `Clientes` Tab (existing, extended)
| Column | Type | Notes |
|--------|------|-------|
| id | STRING | UUID, PK |
| nombre | STRING | Required |
| direccion | STRING | |
| lat | NUMBER | Required for routing |
| lng | NUMBER | Required for routing |
| telefono | STRING | |
| email | STRING | |
| notas | STRING | |
| sync_status | NUMBER | 0=local, 1=synced |
| last_sync | STRING | ISO timestamp |
| created_at | STRING | ISO |
| updated_at | STRING | ISO |

### `RutasHoy` Tab (NEW)
| Column | Type | Notes |
|--------|------|-------|
| id | STRING | UUID |
| fecha | STRING | YYYY-MM-DD |
| cliente_id | STRING | FK → Clientes.id |
| orden | NUMBER | Sequence in route |
| entregado | BOOLEAN | Default false |
| delivered_at | STRING | ISO when marked |
| sync_status | NUMBER | 0=pending, 1=synced |
| created_at | STRING | ISO |

### `SyncLog` Tab (NEW, audit)
| Column | Type | Notes |
|--------|------|-------|
| id | STRING | UUID |
| timestamp | STRING | ISO |
| action | STRING | clientes/sync/rutas/entregado/import/export |
| status | STRING | success/partial/failed |
| details | STRING | JSON summary |

## 4. Frontend API Split

### `frontend/js/config.js` (generated at build from Netlify env)
```javascript
export const CONFIG = {
  GAS_URL: 'https://script.google.com/macros/s/DEPLOY_ID/exec', // Netlify env var
  LOCAL_API: 'http://localhost:8000',
  isLocalhost: () => ['localhost','127.0.0.1'].includes(location.hostname),
  isNetlify: () => location.hostname.includes('.netlify.app'),
  USE_LOCAL_OPTIMIZATION: true
};
```

### `frontend/js/apiClient.js` (NEW)
```javascript
export async function gasFetch(action, payload = {}, method = 'GET') {
  const url = new URL(CONFIG.GAS_URL);
  url.searchParams.set('action', action);
  const opts = { method, headers: {'Content-Type': 'application/json'} };
  if (method !== 'GET') opts.body = JSON.stringify(payload);
  const res = await fetch(url, opts);
  if (!res.ok) throw new Error(`GAS ${res.status}`);
  return res.json();
}

export async function localFetch(endpoint, payload = {}, method = 'POST') {
  if (!CONFIG.isLocalhost()) throw new Error('Local API only on localhost');
  const res = await fetch(`${CONFIG.LOCAL_API}${endpoint}`, {
    method, headers: {'Content-Type': 'application/json'},
    body: method !== 'GET' ? JSON.stringify(payload) : undefined
  });
  if (!res.ok) throw new Error(`Local ${res.status}`);
  return res.json();
}

export async function optimizarRuta(clientIds) {
  try {
    return await localFetch('/rutas/optimizar', {cliente_ids: clientIds});
  } catch (e) {
    console.warn('Local VROOM unavailable, using client-side fallback:', e.message);
    return { fallback: true, ordered: nearestNeighbor(clientIds) };
  }
}
```

## 5. Service Worker Cache Strategy

### `frontend/sw.js` (NEW, vanilla)
```javascript
const CACHES = {
  STATIC: 'gps-static-v1',
  GAS_API: 'gps-gas-api-v1',
  TILES: 'gps-tiles-v1'
};

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHES.STATIC).then(c => c.addAll([
    '/', '/index.html', '/app.js', '/js/config.js', '/js/apiClient.js',
    '/js/routing-client.js', '/js/syncQueue.js', '/manifest.json'
  ])));
});

self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);
  // GAS API → network-first
  if (url.origin === new URL(CONFIG.GAS_URL).origin) {
    e.respondWith(networkFirst(e.request, CACHES.GAS_API));
  }
  // Map tiles → cache-first
  else if (url.hostname.match(/tile\.openstreetmap\.org|a\.tile|b\.tile|c\.tile/)) {
    e.respondWith(cacheFirst(e.request, CACHES.TILES));
  }
  // Static → cache-first
  else {
    e.respondWith(cacheFirst(e.request, CACHES.STATIC));
  }
});

async function networkFirst(req, cacheName) {
  const cache = await caches.open(cacheName);
  try {
    const fresh = await fetch(req);
    cache.put(req, fresh.clone());
    return fresh;
  } catch {
    const cached = await cache.match(req);
    return cached || new Response('Offline', {status: 503});
  }
}
async function cacheFirst(req, cacheName) {
  const cache = await caches.open(cacheName);
  const cached = await cache.match(req);
  if (cached) return cached;
  const fresh = await fetch(req);
  cache.put(req, fresh.clone());
  return fresh;
}
```

## 6. BackgroundSync Namespacing

| Tag | Purpose | Queue Storage Key |
|-----|---------|-------------------|
| `gps-gas-sync` | Clientes LWW sync (syncEngine) | `queue_gas_sync` |
| `gps-local-queue` | Entregado offline (syncQueue) | `queue_entregado` |
| `gps-import-queue` | XLSX import when offline | `queue_import` |

Each queue: `[{payload, ts, retries}]`. Replay on `sync` event, max 3 retries, dead-letter after.

## 7. Nearest-Neighbor Fallback (`frontend/js/routing-client.js`)

```javascript
export function nearestNeighbor(clientIds, clientsMap) {
  // clientsMap: id → {lat, lng}
  const unvisited = new Set(clientIds);
  const route = [];
  let current = clientIds[0];
  route.push(current); unvisited.delete(current);
  
  while (unvisited.size > 0) {
    let nearest = null, minDist = Infinity;
    const [clat, clng] = [clientsMap[current].lat, clientsMap[current].lng];
    for (const id of unvisited) {
      const d = haversine(clat, clng, clientsMap[id].lat, clientsMap[id].lng);
      if (d < minDist) { minDist = d; nearest = id; }
    }
    route.push(nearest); unvisited.delete(nearest); current = nearest;
  }
  return twoOpt(route, clientsMap); // 2-opt improvement
}

function haversine(lat1, lng1, lat2, lng2) {
  const R = 6371e3;
  const φ1 = lat1 * Math.PI/180, φ2 = lat2 * Math.PI/180;
  const Δφ = (lat2-lat1) * Math.PI/180, Δλ = (lng2-lng1) * Math.PI/180;
  const a = Math.sin(Δφ/2)**2 + Math.cos(φ1)*Math.cos(φ2)*Math.sin(Δλ/2)**2;
  return 2 * R * Math.asin(Math.sqrt(a));
}

function twoOpt(route, clientsMap) {
  let improved = true;
  while (improved) {
    improved = false;
    for (let i = 1; i < route.length - 2; i++) {
      for (let j = i + 1; j < route.length; j++) {
        if (routeDist(route, i, j, clientsMap) > routeDist(route, i, j, clientsMap, true)) {
          reverse(route, i, j); improved = true;
        }
      }
    }
  }
  return route;
}
```

## 8. Netlify Config (`netlify.toml`)

```toml
[build]
  publish = "frontend"
  command = ""  # No build step

[functions]
  directory = "netlify/functions"  # Empty, reserved

[[headers]]
  for = "/*"
  [headers.values]
    X-Frame-Options = "DENY"
    X-Content-Type-Options = "nosniff"
    Referrer-Policy = "strict-origin-when-cross-origin"
    Content-Security-Policy = "default-src 'self' 'unsafe-inline' https: data: blob:; script-src 'self' 'unsafe-inline' https:; style-src 'self' 'unsafe-inline' https:; img-src 'self' data: blob: https:; connect-src 'self' https: http://localhost:8000; font-src 'self' https: data:;"

[env]
  GAS_URL = "https://script.google.com/macros/s/DEPLOY_ID/exec"  # Set in Netlify UI
```

## 9. Env Detection Logic

```javascript
// In CONFIG module
isLocalhost: () => {
  const h = location.hostname;
  return h === 'localhost' || h === '127.0.0.1' || h.startsWith('192.168.');
},
isNetlify: () => location.hostname.endsWith('.netlify.app'),
// LOCAL_API only used when isLocalhost() === true
```

## 10. Sequence Diagrams (Text)

### App Load → Render Clients
```
User → Netlify (HTTPS) → index.html → sw.js registers
    → CONFIG.init() → gasFetch('clientes') → GAS Web App
    → Sheets → JSON → render map markers → localForage cache
```

### Optimizar Ruta Click
```
User clicks "Optimizar"
    → CONFIG.isLocalhost() ?
    │   YES → localFetch('/rutas/optimizar') → Docker VROOM/OSRM → ordered route
    │   NO  → nearestNeighbor(clientIds, clientsMap) → ordered route (fallback)
    → render route on map → store in localForage rutas_hoy
```

### Entregado Click (Online)
```
User marks cliente(s) delivered
    → navigator.onLine ?
    │   YES → gasFetch('entregado', {cliente_ids, fecha}) → GAS → Sheets
    │        → sync_status=1 → clear local queue → refresh UI
    │   NO  → syncQueue.enqueue(ids) → localStorage queue_entregado
    │        → registerSync('gps-local-queue') → BackgroundSync
    │        → optimistic entregado_local=true → grey badge "(pendiente sync)"
```

### BackgroundSync Replay
```
Service Worker 'sync' event (tag: gps-local-queue)
    → syncQueue.replayQueue({api: gasFetch, onSynced: clearFlags})
    → for each queued entry: gasFetch('entregado', entry.payload)
    → on success: shift queue, clear entregado_local flags
    → on network error: break, keep queue for next sync
```

## 11. Rollback / Migration

- **Local FastAPI + SQLite**: Unchanged, fully functional. Run `uvicorn backend.main:app` → localhost:8000 works.
- **Netlify Deploy**: Additive only. No destructive changes to local code.
- **GAS Web App Deploy**: New version, old version still accessible. Rollback = revert to previous deployment in Apps Script UI.
- **Sheets**: `RutasHoy` tab added. Existing `Clientes` tab untouched. Migration script: `ALTER TABLE` equivalent in Apps Script (one-time `appendRow` headers).

---

**Estimated Implementation**: ~300 lines net across 3 work-unit commits (within 800 budget, single PR).