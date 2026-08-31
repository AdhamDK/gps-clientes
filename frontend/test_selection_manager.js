/* Unit tests (execution-based, Node, no browser) for:
 *   - selectionManager: toggleSelection(add/remove/persist), computeVisible (getVisibleClientes),
 *     buildMarkerPlan (refreshMapMarkers incremental update)
 *   - syncQueue: offline/online "marcar entregados" queue + entregado_local optimistic flag
 * Run: node frontend/test_selection_manager.js
 */
const path = require('path');
const assert = require('assert');

// --- Mock localStorage so persistence can be exercised in Node ---
class MockStorage {
  constructor() { this.m = new Map(); }
  getItem(k) { return this.m.has(k) ? this.m.get(k) : null; }
  setItem(k, v) { this.m.set(k, String(v)); }
  removeItem(k) { this.m.delete(k); }
}
global.localStorage = new MockStorage();

const SelectionManager = require(path.join(__dirname, 'js', 'selectionManager.js'));
const SyncQueue = require(path.join(__dirname, 'js', 'syncQueue.js'));

let passed = 0;
function ok(cond, msg) {
  if (!cond) { console.error('FAIL: ' + msg); process.exit(1); }
  passed++; console.log('ok  - ' + msg);
}
function clients() {
  return [
    { id: '1', nombre: 'A', lat: 8.60, lng: -71.64, has_gps_fix: true },
    { id: '2', nombre: 'B', lat: 8.61, lng: -71.65, has_gps_fix: true },
    { id: '3', nombre: 'C', lat: 8.62, lng: -71.66, has_gps_fix: false }, // selected, NO gps fix -> must still show
    { id: '4', nombre: 'D', lat: null, lng: null, has_gps_fix: true }     // no coords -> never visible
  ];
}

// ---------------------------------------------------------------------------
// 1) selectionManager.toggle (toggleSelection) + persistence across "reloads"
// ---------------------------------------------------------------------------
{
  const shared = new Set();
  const m = new SelectionManager(shared);
  m.toggle('1');
  m.toggle('2');
  ok(m.size() === 2 && m.has('1') && m.has('2'), 'toggleSelection adds ids');
  m.toggle('1');
  ok(m.size() === 1 && !m.has('1') && m.has('2'), 'toggleSelection toggles off existing id');
  ok(m.getIds().join(',') === '2', 'getIds returns selected ids');

  // Simulate a page reload: a fresh manager on the same storage must recover the Set.
  const reloaded = new SelectionManager(new Set());
  ok(reloaded.has('2') && reloaded.size() === 1, 'selection survives reload via localStorage');

  m.clear();
  ok(m.size() === 0 && reloaded.size() === 1, 'clear empties current manager');
  reloaded.clear();
  ok(reloaded.size() === 0, 'clear persists across instances');
}

// ---------------------------------------------------------------------------
// 2) computeVisible (drives getVisibleClientes) — selected INCLUDED even without gps_fix
// ---------------------------------------------------------------------------
{
  const m = new SelectionManager(new Set());
  const base = m.computeVisible(clients());
  ok(base.length === 2 && !base.some(c => c.id === '3'), 'no selection -> base gps-fix filter only');

  m.add('3'); // selected client WITHOUT has_gps_fix
  const vis = m.computeVisible(clients());
  ok(vis.some(c => c.id === '3'), 'selected client with has_gps_fix=false is still visible');
  ok(!vis.some(c => c.id === '4'), 'client without coords is never visible');
  ok(vis.length === 1, 'only the selected client is shown when a selection exists');

  m.add('2');
  const both = m.computeVisible(clients());
  ok(both.some(c => c.id === '2') && both.some(c => c.id === '3'), 'selection includes both gps and no-gps clients');
  ok(both.length === 2, 'visible = all selected with coords');
}

// ---------------------------------------------------------------------------
// 3) buildMarkerPlan (refreshMapMarkers incremental) — reuses markers, only diffs
// ---------------------------------------------------------------------------
{
  const m = new SelectionManager(new Set());
  m.clear(); // isolate: drop any selection persisted by previous test sections
  // current on-map markers: only 1 and 2
  const existingMarkers = [
    { id: '1', marker: { setIcon: () => {}, setPopupContent: () => {} } },
    { id: '2', marker: { setIcon: () => {}, setPopupContent: () => {} } }
  ];
  let plan = m.buildMarkerPlan(existingMarkers, clients());
  // no selection -> visible = base gps-fix (1,2); nothing to remove/add, keep+update both
  ok(plan.update.length === 2 && plan.keep.length === 2 && plan.add.length === 0, 'no-selection plan keeps/updates existing markers, adds nothing');
  ok(plan.remove.length === 0, 'no spurious removes when no selection');

  // select 3 (no gps): it becomes the only visible client -> ADDED (not previously on map)
  m.add('3');
  plan = m.buildMarkerPlan(existingMarkers, clients());
  ok(plan.add.some(a => a.cliente.id === '3'), 'new selected no-gps client planned to be ADDED');
  ok(plan.add[0] && plan.add[0].selected === true, 'added marker flagged as selected (gold icon)');
  ok(!plan.remove.some(r => r.id === '3'), 'no-gps selected client is NOT removed');

  // select 2 as well: existing marker 2 must be icon-updated (setIcon), 3 still added
  m.add('2');
  plan = m.buildMarkerPlan(existingMarkers, clients());
  const upd2 = plan.update.find(u => u.cliente.id === '2');
  ok(upd2 && upd2.selected === true, 'existing selected marker planned for setIcon update');
  ok(plan.add.some(a => a.cliente.id === '3'), 'second newly-visible client still added on top');
}

// ---------------------------------------------------------------------------
// 4) Offline/online queue + entregado_local optimistic snapshot (syncQueue)
// ---------------------------------------------------------------------------
async function offlineOnlineScenario() {
  let online = false;
  const mockFetch = async (url, opts) => {
    if (!online) throw new Error('Failed to fetch');
    return { ok: true, status: 200 };
  };

  const cache = clients();
  SyncQueue.enqueue(['1', '2']);
  ok(SyncQueue.hasPending(), 'offline -> entregados quedan en cola');
  const changed = SyncQueue.markEntregadoLocal(cache, ['1', '2']);
  ok(changed && cache[0].entregado_local === true && cache[1].entregado_local === true, 'offline -> entregado_local=true reflejado en UI');

  let replayed = await SyncQueue.replayQueue({ api: 'http://x', fetch: mockFetch, onSynced: () => {} });
  ok(replayed === 0 && SyncQueue.hasPending(), 'offline replay no-op, cola intacta');

  online = true;
  const replayed2 = await SyncQueue.replayQueue({
    api: 'http://x', fetch: mockFetch,
    onSynced: (ids) => { if (ids && ids.length) ids.forEach(id => { const c = cache.find(c2 => String(c2.id) === String(id)); if (c) c.entregado_local = false; }); }
  });
  ok(replayed2 >= 1 && !SyncQueue.hasPending(), 'online replay drena la cola de sync');
  ok(cache[0].entregado_local === false && cache[1].entregado_local === false, 'entregado_local se limpia tras sync confirmado');
}

offlineOnlineScenario().then(() => {
  console.log(`PASS selection-manager/syncqueue: ${passed} aserciones (toggle+persist, computeVisible gps/no-gps, markerPlan incremental, offline/online queue)`);
}).catch((e) => { console.error('FAIL: ' + e.message); process.exit(1); });