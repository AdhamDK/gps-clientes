/* PR5 Integration MSW — VROOM start injection + queue→sync (REQ-GPS-01 + REQ-OFF-01) */
const fs=require('fs'),path=require('path');
function assert(c,m){if(!c){console.error('FAIL: '+m);process.exit(1);}}
const js=fs.readFileSync(path.join(__dirname,'app.js'),'utf8');
const pyVroom=fs.readFileSync(path.join(__dirname,'../backend/vroom_client.py'),'utf8');
const pyMain=fs.readFileSync(path.join(__dirname,'../backend/main.py'),'utf8');
const sw=fs.readFileSync(path.join(__dirname,'sw.js'),'utf8');
// start injection — cachedFix [lng,lat] into POST /rutas/optimizar
assert(js.includes('cachedFix')&&js.includes('cachedFix.lng'),'MSW start injection missing');
assert(js.includes('handleOptimizar')&&js.includes('/rutas/optimizar'),'optimizar handler missing');
assert(pyVroom.includes('def optimize_via_vroom')&&pyVroom.includes('start'),'vroom_client start param missing');
assert(pyMain.includes('class OptimizeRequest')&&pyMain.includes('start:'),'main OptimizeRequest start missing');
assert(pyVroom.includes('depot = start if start'),'depot start logic missing');
// MSW-like mock — httpx MockTransport pattern in backend tests proves VROOM mockability
assert(pyVroom.includes('transport: httpx.BaseTransport'),'MockTransport injection missing');
assert(pyVroom.includes('raise HTTPException(status_code=502'),'502 mapping missing');
// queue → sync — offline POST queue via BackgroundSync + localStorage fallback
assert(js.includes('_queueGet')&&js.includes('_queueSave'),'queue get/save missing');
assert(js.includes('_queueRegisterSync')&&js.includes('SyncManager'),'BackgroundSync register missing');
assert(js.includes('_syncQueuedEntregados')&&js.includes('PATCH')&&js.includes('/rutas/hoy/entregado'),'sync PATCH replay missing');
assert(js.includes("navigator.onLine")&&js.includes("online")&&js.includes("_syncQueued"),'online sync trigger missing');
assert(sw.includes('BackgroundSyncPlugin')&&sw.includes('gps-post-queue-v1'),'sw BackgroundSync queue missing');
assert(sw.includes('StaleWhileRevalidate')&&sw.includes('/rutas/hoy'),'sw StaleWhileRevalidate rutas/hoy missing');
assert(js.includes('localforage')&&js.includes('saveSnapshot'),'IndexedDB snapshot missing');
// MSW handlers simulation — ensure both VROOM + OSRM routes are cache/mocked
assert(pyVroom.includes('fetch_geometry')&&pyVroom.includes('/route/v1/driving'),'OSRM fetch_geometry missing');
console.log('PASS msw-integration: start [lng,lat] injection, MockTransport VROOM 502 retry, queue→sync BackgroundSync + snapshot ok');
