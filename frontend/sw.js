/* sw.js - GPS_CLIENTES PWA - netlify-gas-frontend
 * 3 caches: gps-static-v1, gps-gas-api-v1, gps-tiles-v1
 * GAS API -> networkFirst, tiles/static -> cacheFirst
 * BackgroundSync: gps-gas-sync (clientes) and gps-local-queue (entregado)
 */
var CACHES = {
  STATIC: 'gps-static-v1',
  GAS_API: 'gps-gas-api-v1',
  TILES: 'gps-tiles-v1'
};
var GAS_ORIGIN = 'https://script.google.com';
var STATIC_ASSETS = [
  '/', '/index.html', '/app.js', '/js/config.js', '/js/apiClient.js',
  '/js/routing-client.js', '/js/syncQueue.js', '/js/selectionManager.js', '/syncEngine.js', '/manifest.json', '/style.css'
];

self.addEventListener('install', function(e) {
  e.waitUntil(
    caches.open(CACHES.STATIC).then(function(cache) {
      return cache.addAll(STATIC_ASSETS.map(function(u) { return new Request(u, {cache: 'reload'}); })).catch(function(err){ console.warn('SW precache skip', err); });
    }).then(function(){ return self.skipWaiting(); })
  );
});

self.addEventListener('activate', function(e) {
  e.waitUntil(
    caches.keys().then(function(keys){
      return Promise.all(keys.map(function(k){
        if (k !== CACHES.STATIC && k !== CACHES.GAS_API && k !== CACHES.TILES) {
          // keep legacy caches cleanup: osm-tiles-v1, api-cache-v1 etc
          if (k.indexOf('gps-') === 0 || k.indexOf('osm-') === 0 || k.indexOf('api-') === 0) return caches.delete(k);
        }
        return null;
      }));
    }).then(function(){ return self.clients.claim(); })
  );
});

async function networkFirst(req, cacheName) {
  var cache = await caches.open(cacheName);
  try {
    var fresh = await fetch(req);
    // only cache GET 200
    if (fresh && fresh.ok && req.method === 'GET') {
      try { cache.put(req, fresh.clone()); } catch (e2) {}
    }
    return fresh;
  } catch (err) {
    var cached = await cache.match(req);
    if (cached) return cached;
    return new Response('Offline', {status: 503, statusText: 'Offline'});
  }
}

async function cacheFirst(req, cacheName) {
  var cache = await caches.open(cacheName);
  var cached = await cache.match(req);
  if (cached) return cached;
  try {
    var fresh = await fetch(req);
    if (fresh && fresh.ok) {
      try { cache.put(req, fresh.clone()); } catch (e) {}
    }
    return fresh;
  } catch (e) {
    return cached || new Response('Offline', {status: 503});
  }
}

self.addEventListener('fetch', function(e) {
  var req = e.request;
  if (req.method !== 'GET') return; // let BackgroundSync handle POST/PATCH
  var url;
  try { url = new URL(req.url); } catch (err) { return; }
  // GAS API -> networkFirst
  if (url.origin === GAS_ORIGIN || url.hostname === 'script.google.com' || url.hostname === 'script.googleusercontent.com') {
    e.respondWith(networkFirst(req, CACHES.GAS_API));
    return;
  }
  // Also treat GAS origin via configured URL (script.google.../macros)
  if (req.url.indexOf('script.google.com/macros') !== -1) {
    e.respondWith(networkFirst(req, CACHES.GAS_API));
    return;
  }
  // Map tiles -> cacheFirst
  if (url.hostname.match(/tile\.openstreetmap\.org|a\.tile|b\.tile|c\.tile/) || url.hostname.indexOf('tile.openstreetmap') !== -1) {
    e.respondWith(cacheFirst(req, CACHES.TILES));
    return;
  }
  // Static -> cacheFirst for same-origin assets
  if (url.origin === self.location.origin) {
    e.respondWith(cacheFirst(req, CACHES.STATIC));
    return;
  }
});

// BackgroundSync handlers
self.addEventListener('sync', function(e) {
  if (e.tag === 'gps-gas-sync') {
    e.waitUntil((async function(){
      try {
        // try to use syncEngine if available via import - fallback to no-op
        // We do minimal: keep queue for syncEngine; main work is done by replayQueue in page
        console.log('[SW] sync gps-gas-sync fired');
      } catch (err) { console.error('[SW] gps-gas-sync', err); }
    })());
  } else if (e.tag === 'gps-local-queue') {
    e.waitUntil((async function(){
      try { console.log('[SW] sync gps-local-queue fired'); } catch (err) { console.error('[SW] gps-local-queue', err); }
    })());
  } else if (e.tag === 'gps-post-queue-v1') {
    // legacy tag - ignore to avoid duplicate processing
    console.log('[SW] legacy tag gps-post-queue-v1 ignored');
  }
});

self.addEventListener('message', function(e) {
  if (e.data && e.data.type === 'SKIP_WAITING') self.skipWaiting();
});
