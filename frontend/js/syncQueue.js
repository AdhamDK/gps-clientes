/* syncQueue.js — offline-first "marcar entregados" queue + local optimistic state.
 *
 * Retargeted to GAS (netlify-gas-frontend): gasFetch('entregado') instead of PATCH localhost.
 * Tag: gps-local-queue (was gps-post-queue-v1) for entregado queue.
 * Retries: 3 -> dead-letter, 200/404 remove vs 5xx retain.
 *
 * Works as browser global (window.GPSSyncQueue) and as CommonJS module.
 */
(function (root, factory) {
  if (typeof module !== 'undefined' && module.exports) module.exports = factory();
  else root.GPSSyncQueue = factory();
})(typeof self !== 'undefined' ? self : this, function () {

  var Log = {
    error: function () {
      try { console.error.apply(console, ['[GPS]'].concat(Array.prototype.slice.call(arguments))); } catch (e) {}
    }
  };
  var LS_KEY = 'queue_entregado';
  var LS_DEAD = 'queue_entregado_dead';
  var SYNC_TAG = 'gps-local-queue';

  function queueGet() {
    try {
      var ls = getLS();
      return ls ? JSON.parse(ls.getItem(LS_KEY) || '[]') : [];
    } catch (e) { Log.error('queue read', e); return []; }
  }

  function queueSave(q) {
    try {
      var ls = getLS();
      if (ls) ls.setItem(LS_KEY, JSON.stringify(q));
    } catch (e) { Log.error('queue write', e); }
  }

  function getLS() {
    try {
      if (typeof window !== 'undefined' && window.localStorage) return window.localStorage;
      if (typeof globalThis !== 'undefined' && globalThis.localStorage) return globalThis.localStorage;
      if (typeof global !== 'undefined' && global.localStorage) return global.localStorage;
    } catch (e) { Log.error('localStorage unavailable', e); }
    return null;
  }

  function registerSync() {
    try {
      if (typeof navigator !== 'undefined' && 'serviceWorker' in navigator && typeof window !== 'undefined' && 'SyncManager' in window) {
        navigator.serviceWorker.ready.then(function (reg) {
          try { reg.sync.register(SYNC_TAG); } catch (e) { Log.error('register sync', e); }
        }).catch(function (e) { Log.error('serviceWorker ready', e); });
      }
    } catch (e) { Log.error('registerSync', e); }
  }

  function enqueue(ids) {
    var q = queueGet();
    q.push({ ids: (ids || []).map(String), ts: Date.now(), retries: 0 });
    queueSave(q);
    registerSync();
    return q.length;
  }

  function hasPending() {
    try { return queueGet().length > 0; } catch (e) { Log.error('hasPending', e); return false; }
  }

  function markEntregadoLocal(clientes, ids) {
    var set = new Set((ids || []).map(String));
    var changed = false;
    (clientes || []).forEach(function (c) {
      if (c && set.has(String(c.id)) && !c.entregado_local) { c.entregado_local = true; changed = true; }
    });
    return changed;
  }

  function getDeadLetter() {
    try {
      var ls = getLS();
      return ls ? JSON.parse(ls.getItem(LS_DEAD) || '[]') : [];
    } catch (e) { return []; }
  }
  function saveDeadLetter(q) {
    try { var ls = getLS(); if (ls) ls.setItem(LS_DEAD, JSON.stringify(q)); } catch (e) {}
  }

  // Replays the queue once the backend is reachable. Returns number replayed.
  async function replayQueue(opts) {
    opts = opts || {};
    var q = queueGet();
    if (!q.length) return 0;
    // Prefer gasFetch injection, else fallback to fetch with GAS_URL
    var gasFetchFn = opts.gasFetch || (typeof window !== 'undefined' && window.GPS_apiClient && window.GPS_apiClient.gasFetch) || null;
    // Dynamic import fallback for ES module usage: opts.gasFetch passed from app.js
    if (!gasFetchFn && opts.gasFetch === undefined) {
      try {
        if (typeof window !== 'undefined' && window.GPS_CONFIG) {
          // try to use global CONFIG GAS_URL via fetch
        }
      } catch (e) {}
    }
    var fetchFn = opts.fetch || (typeof fetch !== 'undefined' ? fetch : null);
    var api = opts.api || (typeof window !== 'undefined' && window.API ? window.API : '');
    var fechaForReplay = opts.fecha || (new Date()).toISOString().slice(0,10);
    var replayed = 0;
    while (q.length) {
      var next = q[0];
      if (next.retries == null) next.retries = 0;
      try {
        var resOk = false;
        var status = 0;
        if (gasFetchFn) {
          try {
            await gasFetchFn('entregado', { cliente_ids: next.ids, fecha: next.fecha || fechaForReplay }, 'POST');
            resOk = true;
            status = 200;
          } catch (gasErr) {
            var msg = String(gasErr && gasErr.message || gasErr);
            var m = msg.match(/GAS (\d+)/) || msg.match(/HTTP (\d+)/);
            if (m) status = parseInt(m[1],10);
            if (status === 404) { resOk = true; } else if (status >= 500) { throw gasErr; } else if (status === 0) { throw gasErr; } else { throw gasErr; }
          }
        } else if (fetchFn) {
          var url = api ? (api + '/rutas/hoy/entregado') : '/rutas/hoy/entregado';
          // Try GAS_URL if available
          try {
            var gasUrl = null;
            if (typeof window !== 'undefined' && window.GPS_CONFIG && window.GPS_CONFIG.GAS_URL) gasUrl = window.GPS_CONFIG.GAS_URL;
            if (gasUrl && gasUrl.indexOf('REPLACE_ME') === -1) {
              var u = new URL(gasUrl);
              u.searchParams.set('action', 'entregado');
              url = u.toString();
            }
          } catch (e2) {}
          var res = await fetchFn(url, {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ cliente_ids: next.ids, fecha: next.fecha || fechaForReplay })
          });
          status = res.status;
          resOk = res.ok;
          if (!res.ok) {
            if (status === 404) resOk = true; // idempotent remove
            else if (status >= 500) throw new Error('replay fail HTTP ' + status);
            else throw new Error('replay fail HTTP ' + status);
          }
        } else {
          return 0;
        }
        if (resOk) {
          q.shift();
          queueSave(q);
          replayed += 1;
          if (opts && opts.onSynced) opts.onSynced(next.ids);
        } else {
          throw new Error('replay not ok ' + status);
        }
      } catch (e) {
        Log.error('replay queue (stay queued)', e);
        var is5xx = String(e && e.message || '').match(/HTTP 5\d\d/) || String(e && e.message || '').match(/GAS 5\d\d/);
        var isNetwork = !is5xx && (String(e.message).includes('Failed to fetch') || String(e.message).includes('Network') || String(e.message).includes('fetch'));
        if (isNetwork) {
          break; // keep head for next attempt, no retry increment for pure offline
        }
        next.retries = (next.retries || 0) + 1;
        if (next.retries >= 3) {
          // dead-letter
          var dead = getDeadLetter();
          dead.push({ ids: next.ids, ts: next.ts, retries: next.retries, error: String(e && e.message || e) });
          saveDeadLetter(dead);
          q.shift();
          queueSave(q);
          Log.error('dead-letter entregado after 3 retries', next.ids);
          // continue to next item (don't break, dead-lettered item removed)
        } else {
          queueSave(q);
          try { registerSync(); } catch (re) {}
          break; // retain and retry later
        }
      }
    }
    return replayed;
  }

  return { queueGet: queueGet, queueSave: queueSave, enqueue: enqueue, hasPending: hasPending,
    markEntregadoLocal: markEntregadoLocal, replayQueue: replayQueue, registerSync: registerSync, LS_KEY: LS_KEY, SYNC_TAG: SYNC_TAG, Log: Log };
});
