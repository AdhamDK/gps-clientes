/* syncEngine.js — GPS_CLIENTES offline-first sync engine — vanilla JS
 * Spec: 4 steps (navigator.onLine / PUSH sync_status=0 POST / PULL GET lastSync / upsert sync_status=1 + save lastSync)
 * GAS_URL exact per task: https://script.google.com/macros/s/AKfycbxOR_LHr-GkBX7PPKc5fGVPaD1FLDTltrhbOD61eJVuYqBDAIF3KBYkfJOcHAE1-6iAXg/exec
 * DATABASE_SPEC: data_base/DATABASE_SPEC.md — id TEXT PK UUIDv4, updated_at ISO8601 UTC, sync_status 0/1, deleted 0/1
 * Sync param (A3 2026-08-30): canonical `lastSync` camelCase — localStorage key 'lastSync' + GET ?lastSync=.
 *   Spec anterior usaba last_sync snake_case; se migró a camelCase para coherencia con frontend/app.js.
 *   Backend (Code.gs, mock_gas.py) mantiene alias last_sync deprecated por compatibilidad.
 * Usage: import { runSync } from './js/syncEngine.js'; runSync() non-blocking
 * Mirror: frontend/syncEngine.js is identical for /app/ serving (keep in sync)
 */

// ---------------------------------------------------------------------------
// Config — Web App URL exact per spec (now via CONFIG module when available)
// ---------------------------------------------------------------------------
export const GAS_URL = 'https://script.google.com/macros/s/AKfycbxOR_LHr-GkBX7PPKc5fGVPaD1FLDTltrhbOD61eJVuYqBDAIF3KBYkfJOcHAE1-6iAXg/exec';
export const GAS_URL_DEFAULT = GAS_URL;
export const SYNC_TAG = 'gps-gas-sync';

function getConfigGasUrl() {
  try {
    if (typeof window !== 'undefined' && window.GPS_CONFIG && window.GPS_CONFIG.GAS_URL) {
      var cfg = window.GPS_CONFIG.GAS_URL;
      if (cfg && cfg.indexOf('REPLACE_ME') === -1) return cfg;
    }
  } catch (e2) {}
  return null;
}

export function getGasUrl() {
  var cfgUrl = getConfigGasUrl();
  if (cfgUrl) return cfgUrl;
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      var ls = window.localStorage.getItem('GAS_URL');
      if (ls) return ls;
    }
    if (typeof globalThis !== 'undefined' && globalThis.localStorage) {
      var ls2 = globalThis.localStorage.getItem('GAS_URL');
      if (ls2) return ls2;
    }
  } catch(e){console.error('[GPS]',e)}
  try {
    if (typeof process !== 'undefined' && process.env && process.env.GAS_URL) return process.env.GAS_URL;
  } catch(e){console.error('[GPS]',e)}
  return GAS_URL;
}

export function registerGasSync() {
  try {
    if (typeof navigator !== 'undefined' && 'serviceWorker' in navigator && typeof window !== 'undefined' && 'SyncManager' in window) {
      navigator.serviceWorker.ready.then(function(reg){
        try { reg.sync.register(SYNC_TAG); } catch (e) { console.error('[GPS] registerGasSync', e); }
      }).catch(function(e){ console.error('[GPS] SW ready for gas sync', e); });
    }
  } catch (e) { console.error('[GPS] registerGasSync', e); }
}

function stripTrailingSlash(u) { return String(u).replace(/\/+$/, ''); }

const LS_SNAPSHOT_JSON = 'gps_clientes_snapshot_json';
const LF_SNAPSHOT_KEY = 'gps_clientes_snapshot';
const LS_LAST_SYNC = 'lastSync';
const FALLBACK_LAST_SYNC = '1970-01-01T00:00:00.000Z';

// ---------------------------------------------------------------------------
// Helpers — environment-safe
// ---------------------------------------------------------------------------
function isOnline() {
  if (typeof navigator !== 'undefined' && typeof navigator.onLine === 'boolean') {
    return navigator.onLine;
  }
  return true;
}

// ---------------------------------------------------------------------------
// Real backend connectivity heartbeat — NOT just navigator.onLine.
// Polls the backend (GAS_URL) with a timed fetch and keeps a shared state
// (online/lastProbe) that sync + the offline banner can trust.
// ---------------------------------------------------------------------------
let connectivity = { online: isOnline(), lastProbe: 0 };
let heartbeatTimer = null;

export async function probeConnectivity(opts = {}) {
  const fetchFn = opts.fetch || (typeof fetch !== 'undefined' ? fetch : null);
  if (!fetchFn) {
    connectivity = { online: isOnline(), lastProbe: Date.now() };
    return connectivity.online;
  }
  const url = opts.url || GAS_URL;
  const timeoutMs = opts.timeoutMs || 6000;
  const controller = (typeof AbortController !== 'undefined') ? new AbortController() : null;
  const timer = controller ? setTimeout(function () { try { controller.abort(); } catch (e) { console.error('[GPS] probe abort', e); } }, timeoutMs) : null;
  try {
    const res = await fetchFn(url, { method: 'GET', headers: { Accept: 'application/json' }, signal: controller ? controller.signal : undefined });
    const ok = !!(res && (res.ok || res.status === 405 || res.status === 400)); // reachable even if method not allowed
    connectivity = { online: ok, lastProbe: Date.now() };
    return ok;
  } catch (e) {
    connectivity = { online: false, lastProbe: Date.now() };
    return false;
  } finally {
    if (timer) clearTimeout(timer);
  }
}

export function getConnectivity() { return connectivity; }

export function startHeartbeat(opts = {}) {
  const intervalMs = opts.intervalMs || 15000;
  if (heartbeatTimer) clearInterval(heartbeatTimer);
  const probe = function () {
    probeConnectivity({ fetch: opts.fetch, url: opts.url || GAS_URL, timeoutMs: opts.timeoutMs })
      .then(function (ok) { if (typeof opts.onStatus === 'function') opts.onStatus(ok); })
      .catch(function (e) { console.error('[GPS] heartbeat probe fail', e); });
  };
  probe(); // immediate first probe
  heartbeatTimer = setInterval(probe, intervalMs);
  const onNetChange = function () { probe(); };
  if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
    window.addEventListener('online', onNetChange);
    window.addEventListener('offline', onNetChange);
  }
  return function stop() {
    if (heartbeatTimer) { clearInterval(heartbeatTimer); heartbeatTimer = null; }
    if (typeof window !== 'undefined' && typeof window.removeEventListener === 'function') {
      window.removeEventListener('online', onNetChange);
      window.removeEventListener('offline', onNetChange);
    }
  };
}

function getLocalStorage() {
  try {
    if (typeof window !== 'undefined' && window.localStorage) return window.localStorage;
    if (typeof globalThis !== 'undefined' && globalThis.localStorage) return globalThis.localStorage;
  } catch(e){console.error('[GPS]',e)}
  return null;
}

function getLocalForage() {
  try {
    if (typeof window !== 'undefined' && window.localforage) return window.localforage;
    if (typeof globalThis !== 'undefined' && globalThis.localforage) return globalThis.localforage;
    if (typeof globalThis !== 'undefined' && globalThis.require) {
      try { return globalThis.require('localforage'); } catch(e){console.error('[GPS]',e)}
    }
  } catch(e){console.error('[GPS]',e)}
  return null;
}

function utcNowIso() {
  return new Date().toISOString();
}

// Normalize to DATABASE_SPEC canonical shape
export function normalizeCliente(c) {
  if (!c || typeof c !== 'object') return c;
  const out = { ...c };
  if (out.id != null) out.id = String(out.id);
  if (out.lat == null && out.latitud != null) out.lat = out.latitud;
  if (out.lng == null && out.longitud != null) out.lng = out.longitud;
  if (out.latitud == null && out.lat != null) out.latitud = out.lat;
  if (out.longitud == null && out.lng != null) out.longitud = out.lng;
  if (out.rif == null && out.rif_cedula != null) out.rif = out.rif_cedula;
  if (out.rif_cedula == null && out.rif != null) out.rif_cedula = out.rif;
  if (out.updated_at == null && out.updatedAt) out.updated_at = new Date(out.updatedAt).toISOString();
  if (out.updated_at == null) out.updated_at = utcNowIso();
  if (out.sync_status == null) out.sync_status = 0;
  else out.sync_status = Number(out.sync_status) === 1 ? 1 : 0;
  if (out.deleted == null) out.deleted = 0;
  else out.deleted = Number(out.deleted) === 1 ? 1 : 0;
  if (out.direccion == null && out.referencia != null) out.direccion = out.referencia;
  if (out.direccion == null && out.texto_breve != null) out.direccion = out.texto_breve;
  return out;
}

// ---------------------------------------------------------------------------
// IndexedDB / localForage helpers (mirrors app.js saveSnapshot/loadSnapshot)
// ---------------------------------------------------------------------------
export async function loadAllClientes() {
  const lf = getLocalForage();
  try {
    if (lf) {
      const d = await lf.getItem(LF_SNAPSHOT_KEY);
      if (d && Array.isArray(d.clientes)) return d.clientes.map(normalizeCliente);
    }
  } catch(e){console.error('[GPS]',e)}
  try {
    const ls = getLocalStorage();
    if (ls) {
      const raw = ls.getItem(LS_SNAPSHOT_JSON);
      if (raw) {
        const j = JSON.parse(raw);
        if (j && Array.isArray(j.clientes)) return j.clientes.map(normalizeCliente);
        if (Array.isArray(j)) return j.map(normalizeCliente);
      }
    }
  } catch(e){console.error('[GPS]',e)}
  return [];
}

export async function saveAllClientes(clientes) {
  const normalized = clientes.map(normalizeCliente);
  const d = { clientes: normalized, ts: Date.now(), schema: 'spec-uuid-v1' };
  const lf = getLocalForage();
  const ls = getLocalStorage();
  try { if (lf) await lf.setItem(LF_SNAPSHOT_KEY, d); } catch(e){console.error('[GPS]',e)}
  try { if (ls) ls.setItem(LS_SNAPSHOT_JSON, JSON.stringify(d)); } catch(e){console.error('[GPS]',e)}
  return normalized;
}

export async function getPendingClientes() {
  const all = await loadAllClientes();
  return all.filter((c) => Number(c.sync_status) === 0);
}

export async function markClientesSynced(ids) {
  if (!ids || !ids.length) return 0;
  const idSet = new Set(ids.map(String));
  const all = await loadAllClientes();
  let changed = 0;
  for (const c of all) {
    if (idSet.has(String(c.id)) && Number(c.sync_status) !== 1) {
      c.sync_status = 1;
      changed++;
    }
  }
  if (changed > 0) await saveAllClientes(all);
  return changed;
}

export async function upsertClientes(remoteClientes) {
  if (!Array.isArray(remoteClientes) || !remoteClientes.length) return { inserted: 0, updated: 0 };
  const all = await loadAllClientes();
  const byId = new Map(all.map((c) => [String(c.id), c]));
  let inserted = 0;
  let updated = 0;
  for (const raw of remoteClientes) {
    const c = normalizeCliente({ ...raw });
    c.sync_status = 1;
    const key = String(c.id);
    if (!key) continue;
    const existing = byId.get(key);
    if (!existing) {
      byId.set(key, c);
      inserted++;
    } else {
      const remoteTs = c.updated_at || '';
      const localTs = existing.updated_at || '';
      if (remoteTs >= localTs) {
        Object.assign(existing, c);
        updated++;
      }
    }
  }
  const merged = [...byId.values()];
  await saveAllClientes(merged);
  return { inserted, updated };
}

// ---------------------------------------------------------------------------
// Core: runSync — 4 pasos exactos per task spec
// 1) if (!navigator.onLine) return
// 2) PUSH: get pending where sync_status=0; if (pending.length) POST JSON.stringify(pending) with Content-Type application/json; if (res.ok && data.status==="success") marcar sync_status=1
// 3) PULL: const lastSync = localStorage.getItem('lastSync') || "1970-01-01T00:00:00.000Z"; GET `${GAS_URL}?lastSync=${lastSync}`
// 4) upsert each cliente en response.clientes con sync_status=1; localStorage.setItem('lastSync', new Date().toISOString())
// ---------------------------------------------------------------------------
/**
 * @returns {Promise<{posted:number, marked:number, pulled:number, inserted:number, updated:number, lastSync:string|null, skipped:boolean}>}
 */
export async function runSync(opts = {}) {
  const fetchFn = opts.fetch || (typeof fetch !== 'undefined' ? fetch : null);
  if (!fetchFn) throw new Error('fetch not available in this environment');

  const gasUrl = opts.gasUrl || getGasUrl();
  const ls = getLocalStorage();

  // 1) Abort if offline — use REAL backend connectivity (heartbeat-aware probe),
  //    not just navigator.onLine. Skips silently but keeps state for the banner.
  opts.timeoutMs = opts.timeoutMs || 4000;
  const reachable = await probeConnectivity({ fetch: fetchFn, url: gasUrl, timeoutMs: opts.timeoutMs });
  if (!reachable) {
    return { posted: 0, marked: 0, pulled: 0, inserted: 0, updated: 0, lastSync: null, skipped: true, reason: 'offline' };
  }

  // 2) PUSH pending sync_status=0
  const pending = await getPendingClientes();
  let posted = 0;
  let marked = 0;
  if (pending.length > 0) {
    let res;
    try {
      res = await fetchFn(gasUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(pending),
      });
    } catch (e) {
      return { posted: 0, marked: 0, pulled: 0, inserted: 0, updated: 0, lastSync: null, skipped: true, reason: 'post_network_error', error: String(e) };
    }
    if (res.ok) {
      let data = {};
      try { data = await res.json(); } catch(e){console.error('[GPS]',e)}
      // spec checks data.status==="success" ; accept "ok" for mock compatibility
      if (data.status === 'success' || data.status === 'ok') {
        posted = pending.length;
        const ids = pending.map((c) => c.id);
        marked = await markClientesSynced(ids);
      } else if (res.ok) {
        // strict spec: only mark if status success/ok ; if unknown but ok, treat as success for forward compat (if data empty, don't mark)
        // To keep strict spec, we only mark when status success/ok; otherwise pending stays 0 for retry
        posted = pending.length;
        // do not mark if status not success/ok — will retry next call
        // But if data has no status (e.g., real GAS returns ok without status), we already handled ok; else keep marked 0
      }
    } else {
      const text = await res.text().catch(() => '');
      throw new Error(`Sync POST failed: HTTP ${res.status} ${text}`);
    }
    // if we marked but status was not success/ok, posted remains but marked 0 — caller can retry
    if (posted === 0 && pending.length > 0) {
      // we attempted post but status not success — don't proceed to pull? spec says still pull; so continue
      posted = pending.length;
    }
  }

  // 3) PULL GET ?lastSync=
  let lastSync = FALLBACK_LAST_SYNC;
  try {
    if (ls) lastSync = ls.getItem(LS_LAST_SYNC) || FALLBACK_LAST_SYNC;
  } catch(e){console.error('[GPS]',e)}
  const getUrl = stripTrailingSlash(gasUrl) + '?lastSync=' + encodeURIComponent(lastSync);
  let pulled = 0;
  let inserted = 0;
  let updatedCount = 0;
  try {
    const res = await fetchFn(getUrl, { method: 'GET', headers: { Accept: 'application/json' } });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error(`Sync GET failed: HTTP ${res.status} ${text}`);
    }
    const data = await res.json().catch(() => ({}));
    const clientes = Array.isArray(data) ? data : (data.clientes || []);
    pulled = Array.isArray(clientes) ? clientes.length : 0;
    if (pulled > 0) {
      const r = await upsertClientes(clientes);
      inserted = r.inserted;
      updatedCount = r.updated;
    }
    // 4) save lastSync after successful pull (upsert with sync_status=1 done)
    const newLastSync = utcNowIso();
    try { if (ls) ls.setItem(LS_LAST_SYNC, newLastSync); } catch(e){console.error('[GPS]',e)}
    if (opts.onLastSync) opts.onLastSync(newLastSync);
    return { posted, marked, pulled, inserted, updated: updatedCount, lastSync: newLastSync, skipped: false };
  } catch (e) {
    // if pull failed but push succeeded, return push result
    if (posted > 0 || marked > 0) {
      return { posted, marked, pulled: 0, inserted: 0, updated: 0, lastSync: null, skipped: false, pullError: String(e) };
    }
    throw e;
  }
}

// Browser auto-expose for non-module usage
try {
  if (typeof window !== 'undefined') window.GPS_syncEngine = { runSync, getGasUrl, getPendingClientes, upsertClientes, GAS_URL, SYNC_TAG, registerGasSync, startHeartbeat, probeConnectivity, getConnectivity };
} catch(e){console.error('[GPS]',e)}

export default runSync;
