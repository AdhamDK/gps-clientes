// apiClient.js - Dual GAS / LOCAL client with fallback
import { CONFIG } from './config.js';
import { nearestNeighbor } from './routing-client.js';

export async function gasFetch(action, payload, method) {
  payload = payload || {};
  method = method || 'GET';
  var url = new URL(CONFIG.GAS_URL);
  url.searchParams.set('action', action);
  var opts = { method: method, headers: { 'Content-Type': 'application/json' } };
  if (method === 'GET') {
    // payload -> query params
    for (var k in payload) if (payload.hasOwnProperty(k) && payload[k] != null) url.searchParams.set(k, String(payload[k]));
  } else {
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

export async function optimizarRuta(clientIds, clientsMap) {
  // clientsMap optional: if not provided, tries local only and falls back to ordering via nearestNeighbor without map (identity)
  try {
    var res = await localFetch('/rutas/optimizar', { cliente_ids: clientIds });
    // Local returns {orden, geometry, distance, duration} or {ordered}
    if (res && res.orden) return { fallback: false, ordered: res.orden, geometry: res.geometry, distance: res.distance, duration: res.duration, raw: res };
    if (res && res.ordered) return { fallback: false, ordered: res.ordered, raw: res };
    return { fallback: false, ordered: clientIds, raw: res };
  } catch (e) {
    try { console.warn('Local VROOM unavailable, using client-side fallback:', e.message || e); } catch (err2) {}
    var fallbackOrdered;
    if (clientsMap) {
      fallbackOrdered = nearestNeighbor(clientIds, clientsMap);
    } else {
      fallbackOrdered = clientIds.slice();
    }
    return { fallback: true, ordered: fallbackOrdered, error: String(e && e.message || e) };
  }
}

// CommonJS compat
try { if (typeof window !== 'undefined') window.GPS_apiClient = { gasFetch: gasFetch, localFetch: localFetch, optimizarRuta: optimizarRuta }; } catch (e) {}
