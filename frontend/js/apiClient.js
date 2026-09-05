// apiClient.js - Dual GAS / LOCAL client with fallback
import { CONFIG } from './config.js';
import { nearestNeighbor } from './routing-client.js';

export async function gasFetch(action, payload, method) {
  payload = payload || {};
  method = method || 'GET';
  var url = new URL(CONFIG.GAS_URL);
  url.searchParams.set('action', action);
  // Use text/plain to avoid CORS preflight (OPTIONS) which GAS web app doesn't handle
  var opts = { method: method, headers: {} };
  if (method === 'GET') {
    for (var k in payload) if (payload.hasOwnProperty(k) && payload[k] != null) url.searchParams.set(k, String(payload[k]));
  } else {
    opts.headers['Content-Type'] = 'text/plain;charset=utf-8';
    opts.body = JSON.stringify(payload);
  }
  var res = await fetch(url.toString(), opts);
  if (!res.ok) throw new Error('GAS ' + res.status + ' ' + (await res.text().catch(function(){return '';})));
  return res.json();
}

export async function localFetch(endpoint, payload, method) {
  payload = payload || {};
  method = method || 'POST';
  // Guard: only allow local when isLocalhost or explicitly allowed (tests)
  // For Netlify HTTPS -> http://localhost mixed content will naturally fail; we let fallback handle it
  var base = CONFIG.LOCAL_API.replace(/\/$/, '');
  var url = base + (endpoint.startsWith('/') ? endpoint : '/' + endpoint);
  var opts = { method: method, headers: { 'Content-Type': 'application/json' } };
  if (method !== 'GET') opts.body = JSON.stringify(payload);
  // 30s timeout for VROOM
  var controller = (typeof AbortController !== 'undefined') ? new AbortController() : null;
  var timer = null;
  if (controller) {
    timer = setTimeout(function() { try { controller.abort(); } catch(e) {} }, 30000);
    opts.signal = controller.signal;
  }
  try {
    var res = await fetch(url, opts);
    if (!res.ok) throw new Error('Local ' + res.status);
    return res.json();
  } finally {
    if (timer) clearTimeout(timer);
  }
}

async function fetchOSRMGeometry(ordered, clientsMap, start) {
  if (!ordered || ordered.length < 2 || !clientsMap) return null;
  var coords = [];
  // Prepend user location if provided (start = {lat, lng})
  if (start && start.lat != null && start.lng != null) {
    coords.push(start.lng + ',' + start.lat);
  }
  ordered.forEach(function(id){
    var c = clientsMap[id];
    var lat = c.lat != null ? c.lat : c.latitud;
    var lng = c.lng != null ? c.lng : c.longitud;
    coords.push(lng + ',' + lat);
  });
  coords = coords.join(';');
  var endpoints = [
    'https://router.project-osrm.org/route/v1/driving/' + coords + '?overview=full&geometries=geojson',
    'https://routing.openstreetmap.de/routed-car/route/v1/driving/' + coords + '?overview=full&geometries=geojson'
  ];
  for (var ei = 0; ei < endpoints.length; ei++) {
    try {
      var url = endpoints[ei];
      var controller = (typeof AbortController !== 'undefined') ? new AbortController() : null;
      var timer = null;
      if (controller) timer = setTimeout(function(){ try{ controller.abort(); }catch(e){} }, 8000);
      var res = await fetch(url, controller ? { signal: controller.signal } : {});
      if (timer) clearTimeout(timer);
      if (!res.ok) continue;
      var j = await res.json();
      if (j.code !== 'Ok' || !j.routes || !j.routes[0]) continue;
      var r = j.routes[0];
      return { geometry: r.geometry, distance: r.distance, duration: r.duration, provider: ei===0 ? 'osrm.org' : 'osm.de' };
    } catch (e) {
      try { console.warn('OSRM '+ei+' failed', e.message || e); } catch(e2){}
      continue;
    }
  }
  return null;
}

export async function optimizarRuta(clientIds, clientsMap, start) {
  // clientsMap optional: if not provided, tries local only and falls back to ordering via nearestNeighbor without map (identity)
  // start optional: {lat, lng} user location to use as route start (forwarded to VROOM, used by nearestNeighbor fallback)
  try {
    var res = await localFetch('/rutas/optimizar', { cliente_ids: clientIds, start: start });
    // Local returns {orden, geometry, distance, duration} or {ordered}
    if (res && res.orden) return { fallback: false, ordered: res.orden, geometry: res.geometry, distance: res.distance, duration: res.duration, raw: res };
    if (res && res.ordered) return { fallback: false, ordered: res.ordered, raw: res };
    return { fallback: false, ordered: clientIds, raw: res };
  } catch (e) {
    try { console.warn('Local VROOM unavailable, trying OSRM public:', e.message || e); } catch (err2) {}
    var fallbackOrdered;
    if (clientsMap) {
      fallbackOrdered = nearestNeighbor(clientIds, clientsMap, start);
    } else {
      fallbackOrdered = clientIds.slice();
    }
    // Try OSRM public for road-following geometry (restores v1.1.7 look on Netlify)
    var osrm = await fetchOSRMGeometry(fallbackOrdered, clientsMap, start);
    if (osrm && osrm.geometry) {
      return { fallback: false, ordered: fallbackOrdered, geometry: osrm.geometry, distance: osrm.distance, duration: osrm.duration, raw: osrm };
    }
    return { fallback: true, ordered: fallbackOrdered, error: String(e && e.message || e) };
  }
}

// CommonJS compat
try { if (typeof window !== 'undefined') window.GPS_apiClient = { gasFetch: gasFetch, localFetch: localFetch, optimizarRuta: optimizarRuta }; } catch (e) {}
