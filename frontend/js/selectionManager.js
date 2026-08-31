/* selectionManager.js — GPS_CLIENTES client selection state (centralized).
 *
 * Purpose
 *   - Centralize the selectedIds logic in ONE place (previously a loose module Set in app.js).
 *   - Persist selection to localStorage so it survives reloads / pagination / filters.
 *   - Notify subscribers (UI counter, map markers) whenever the selection changes, so the
 *     counter, the list checkboxes and the map always stay in sync.
 *   - Provide pure helpers (computeVisible, buildMarkerPlan) that are unit-testable in Node
 *     without a browser/DOM.
 *
 * This file works both as a browser global (window.SelectionManager) and as a CommonJS module
 * (module.exports) so `frontend/test_selection_manager.js` can execute real logic.
 */
(function (root, factory) {
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = factory();
  } else {
    root.SelectionManager = factory();
  }
})(typeof self !== 'undefined' ? self : this, function () {

  const LS_KEY = 'gps_selected_ids';

  // Centralized logger — use Log.error(...) everywhere instead of swallowing exceptions.
  const Log = {
    error: function () {
      try { console.error.apply(console, ['[GPS]'].concat(Array.prototype.slice.call(arguments))); } catch (e) { /* noop */ }
    },
    warn: function () {
      try { console.warn.apply(console, ['[GPS]'].concat(Array.prototype.slice.call(arguments))); } catch (e) { /* noop */ }
    },
    info: function () {
      try { console.info.apply(console, ['[GPS]'].concat(Array.prototype.slice.call(arguments))); } catch (e) { /* noop */ }
    }
  };

  function getStorage() {
    try {
      if (typeof window !== 'undefined' && window.localStorage) return window.localStorage;
      if (typeof globalThis !== 'undefined' && globalThis.localStorage) return globalThis.localStorage;
    } catch (e) { Log.error('localStorage unavailable', e); }
    return null;
  }

  function SelectionManager(sharedSet) {
    var self = this;
    // sharedSet allows app.js to keep `const selectedIds = new Set();` as the single source of
    // truth while the manager owns persistence + notifications over that same Set.
    self.selected = sharedSet || new Set();
    self._listeners = [];
    self._storage = getStorage();
    self._load();
  }

  SelectionManager.prototype._load = function () {
    var ls = this._storage;
    if (!ls) return;
    try {
      var raw = ls.getItem(LS_KEY);
      if (raw) {
        var arr = JSON.parse(raw);
        if (Array.isArray(arr)) {
          this.selected.clear();
          arr.forEach(function (id) { this.selected.add(String(id)); }, this);
        }
      }
    } catch (e) { Log.error('selection load', e); }
  };

  SelectionManager.prototype._persist = function () {
    var ls = this._storage;
    if (!ls) return;
    try {
      ls.setItem(LS_KEY, JSON.stringify(Array.from(this.selected)));
    } catch (e) { Log.error('selection persist', e); }
  };

  SelectionManager.prototype._emit = function () {
    this._persist();
    for (var i = 0; i < this._listeners.length; i++) {
      try { this._listeners[i](this.selected); } catch (e) { Log.error('selection listener', e); }
    }
  };

  SelectionManager.prototype.subscribe = function (fn) {
    if (typeof fn === 'function') this._listeners.push(fn);
    return fn;
  };
  SelectionManager.prototype.unsubscribe = function (fn) {
    var i = this._listeners.indexOf(fn);
    if (i !== -1) this._listeners.splice(i, 1);
  };

  SelectionManager.prototype.has = function (id) { return this.selected.has(String(id)); };
  SelectionManager.prototype.size = function () { return this.selected.size; };
  SelectionManager.prototype.getSelected = function () { return this.selected; };
  SelectionManager.prototype.getIds = function () { return Array.from(this.selected); };

  SelectionManager.prototype.add = function (id) {
    var sid = String(id);
    if (this.selected.has(sid)) return false;
    this.selected.add(sid);
    this._emit();
    return true;
  };
  SelectionManager.prototype.remove = function (id) {
    var sid = String(id);
    if (!this.selected.has(sid)) return false;
    this.selected.delete(sid);
    this._emit();
    return true;
  };
  SelectionManager.prototype.toggle = function (id) {
    var sid = String(id);
    if (this.selected.has(sid)) { this.selected.delete(sid); } else { this.selected.add(sid); }
    this._emit();
  };
  SelectionManager.prototype.addAll = function (ids) {
    var changed = false;
    (ids || []).forEach(function (id) {
      var sid = String(id);
      if (!this.selected.has(sid)) { this.selected.add(sid); changed = true; }
    }, this);
    if (changed) this._emit();
  };
  SelectionManager.prototype.clear = function () {
    if (this.selected.size === 0) return;
    this.selected.clear();
    this._emit();
  };
  // test hook — inject a mock storage (no-op safe)
  SelectionManager.prototype._resetStorage = function (storage) { this._storage = storage || null; };

  // --- Pure display logic (unit-testable) ---
  // No selection -> base set (has gps fix + coords). With a selection -> include ALL selected
  // clients even if they have no has_gps_fix, as long as they carry coordinates; they are drawn
  // with a differentiated (gold) icon by refreshMapMarkers.
  SelectionManager.prototype.computeVisible = function (clientes) {
    clientes = Array.isArray(clientes) ? clientes : [];
    if (this.selected.size === 0) {
      return clientes.filter(function (c) { return c && c.has_gps_fix !== false && c.lat != null && c.lng != null; });
    }
    return clientes.filter(function (c) { return c && this.selected.has(String(c.id)) && c.lat != null && c.lng != null; }, this);
  };

  // Marker plan builder consumed by refreshMapMarkers: instead of destroying the whole cluster it
  // returns which markers to add / update (icon) / remove / keep, based on the new visible set.
  SelectionManager.prototype.buildMarkerPlan = function (markers, clientes) {
    var visible = this.computeVisible(clientes);
    var visibleIds = new Set();
    visible.forEach(function (c) { visibleIds.add(String(c.id)); });
    var existing = new Map();
    (markers || []).forEach(function (m) { existing.set(String(m.id), m.marker); });
    var plan = { visible: visible, add: [], update: [], remove: [], keep: [] };
    (markers || []).forEach(function (m) {
      if (!visibleIds.has(String(m.id))) plan.remove.push(m);
      else plan.keep.push(m);
    });
    visible.forEach(function (c) {
      var sid = String(c.id);
      var selected = this.selected.has(sid);
      if (existing.has(sid)) plan.update.push({ cliente: c, selected: selected });
      else plan.add.push({ cliente: c, selected: selected });
    }, this);
    return plan;
  };

  SelectionManager.Log = Log;
  SelectionManager.LS_KEY = LS_KEY;
  SelectionManager.createSingleton = function (sharedSet) { return new SelectionManager(sharedSet); };
  return SelectionManager;
});