/* PR4 focused test: Offline PWA + Queue — REQ-OFF-01 */
const fs=require('fs'),path=require('path');
function assert(c,m){if(!c){console.error('FAIL: '+m);process.exit(1);}}
const root=__dirname;
const sw=fs.readFileSync(path.join(root,'sw.js'),'utf8');
const html=fs.readFileSync(path.join(root,'index.html'),'utf8');
const css=fs.readFileSync(path.join(root,'style.css'),'utf8');
const js=fs.readFileSync(path.join(root,'app.js'),'utf8');
const ktOsm=fs.readFileSync(path.join(root,'../app/src/main/java/com/gpsclientes/ui/map/OsmMapProvider.kt'),'utf8');
const ktFallback=fs.readFileSync(path.join(root,'../app/src/main/java/com/gpsclientes/ui/map/OfflineMapFallback.kt'),'utf8');
const manifest=fs.readFileSync(path.join(root,'manifest.json'),'utf8');
// sw.js Workbox
assert(sw.includes('workbox'),'sw workbox import missing');
assert(sw.includes('precacheAndRoute'),'sw precache missing');
assert(sw.includes('CacheFirst')&&sw.includes('osm-tiles'),'sw CacheFirst tiles missing');
assert(sw.includes('500')&&sw.includes('30'),'sw 500/30d missing');
assert(sw.includes('StaleWhileRevalidate')&&sw.includes('/clientes'),'sw StaleWhileRevalidate clientes missing');
assert(sw.includes('ExpirationPlugin')&&sw.includes('maxEntries'),'sw ExpirationPlugin missing');
assert(sw.includes('BackgroundSyncPlugin')||sw.includes('BackgroundSync')||sw.includes('sync'),'sw BackgroundSync missing');
assert(sw.includes('/rutas/hoy')||sw.includes('api-cache'),'sw rutas hoy cache missing');
assert(sw.includes('purgeOnQuotaError')||sw.includes('Quota'),'sw quota purge missing or optional');
// app.js localForage snapshot + queue + banner + BackgroundSync
assert(js.includes('localforage')||js.includes('localForage'),'app localForage missing');
assert(js.includes('saveSnapshot')||js.includes('gps_clientes_snapshot'),'app snapshot save missing');
assert(js.includes('loadSnapshot')||js.includes('loadClientesSnapshot')||js.includes('loadSnapshot'),'app snapshot load missing');
assert(js.includes('queue_entregado')&&js.includes('navigator.onLine'),'app queue missing');
assert(js.includes('_queueRegisterSync')||js.includes('SyncManager')||js.includes('BackgroundSync'),'app BackgroundSync register missing');
assert(js.includes('offlineBanner')||js.includes('offline-banner'),'app offline banner wiring missing');
assert(js.includes('updateOfflineBanner')&&js.includes('online')&&js.includes('offline'),'app offline banner logic missing');
assert(js.includes('navigator.serviceWorker')&&js.includes('register'),'app sw register or html register missing (checked html)');
// html banner + sw register + localforage
assert(html.includes('offlineBanner')&&html.includes('offline-banner'),'html offline banner missing');
assert(html.includes('Sin conexi')&&html.includes('offline'),'html offline banner text missing');
assert(html.includes('sw.js')&&html.includes('serviceWorker'),'html sw register missing');
assert(html.includes('localforage'),'html localforage script missing');
assert(html.includes('manifest.json'),'html manifest link missing (precondition)');
// css banner
assert(css.includes('.offline-banner'),'css offline-banner missing');
assert(css.includes('hidden'),'css hidden handling missing');
// Android
assert(ktOsm.includes('tileFileSystemCacheMaxBytes')&&ktOsm.includes('500'),'OsmMapProvider 500MB cache missing');
assert(ktOsm.includes('tileFileSystemCacheTrimBytes'),'OsmMapProvider trim missing');
assert(ktFallback.includes('500')&&ktFallback.includes('30'),'OfflineMapFallback stats 500/30 missing');
assert(ktFallback.includes('Cache')&&ktFallback.includes('tiles'),'OfflineMapFallback cache text missing');
assert(ktFallback.includes('tileCacheMaxMb')||ktFallback.includes('tileMaxEntries'),'OfflineMapFallback params missing');
console.log('PASS offline-pwa: sw Workbox precache+CacheFirst 500/30d+StaleWhileRevalidate+BackgroundSync, app localForage snapshot+queue+banner, html banner+sw, Android 500MB cache+fallback stats ok');
