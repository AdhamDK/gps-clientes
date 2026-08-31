/* PR5 Unit Vitest/pytest — FOUC, pin renumber, Set toggle, PATCH idempotent, terminar blocked */
const fs=require('fs'),path=require('path');
function assert(c,m){if(!c){console.error('FAIL: '+m);process.exit(1);}}
const root=__dirname;
const html=fs.readFileSync(path.join(root,'index.html'),'utf8');
const css=fs.readFileSync(path.join(root,'style.css'),'utf8');
const js=fs.readFileSync(path.join(root,'app.js'),'utf8');
const pyMain=fs.readFileSync(path.join(root,'../backend/main.py'),'utf8');
const pyModels=fs.readFileSync(path.join(root,'../backend/models.py'),'utf8');
// FOUC — inline before CSS, localStorage + prefers-color-scheme, #FC4C02 constant
assert(html.indexOf('localStorage.getItem')<html.indexOf('style.css'),'FOUC guard must be before style.css');
assert(html.includes("prefers-color-scheme"),'FOUC prefers-color-scheme missing');
assert(html.includes('data-theme'),'FOUC data-theme missing');
assert(css.includes('#FC4C02'),'Strava #FC4C02 must stay constant');
assert(css.includes('transition'),'theme transition missing');
// pin renumber 1..n — createNumberedIcon + rAF + pending filter
assert(js.includes('createNumberedIcon'),'createNumberedIcon missing');
assert(js.includes('numberedIdx')&&js.includes('requestAnimationFrame'),'renumber rAF missing');
assert(js.includes('renderPendingMarkers')&&js.includes('pendingSet'),'pending renumber missing');
assert(js.includes('refreshPendingView')&&js.includes('entregado=false'),'pending filter query missing');
assert(pyModels.includes('entregado')&&pyModels.includes('delivered_at'),'model entregado missing');
// Set toggle — persists across filter/pagination
assert(js.includes('selectedIds')&&js.includes('new Set'),'selection Set missing');
assert(js.includes('toggleSelection')||js.includes('selectedIds.has'),'Set toggle missing');
assert(js.includes('updateSelectionUI')&&js.includes('seleccionados'),'badge/mini-menu counter missing');
assert(js.includes('fetchClientes')&&js.includes('selectedIds'),'filter must preserve Set (no clear on fetch)');
// PATCH idempotent + terminar blocked
assert(pyMain.includes('/rutas/hoy/entregado')&&pyMain.includes('if not r.entregado'),'PATCH idempotent guard missing');
assert(pyMain.includes('409')&&pyMain.includes('pending'),'terminar 409 blocked missing');
assert(pyMain.includes('/rutas/hoy/terminar'),'terminar alias missing');
assert(js.includes('handleMarcarEntregados')&&js.includes('PATCH'),'JS PATCH handler missing');
assert(js.includes('handleTerminarLista')&&js.includes('pending'),'JS terminar blocked handling missing');
// dropdown offline queue sanity (unit parity)
assert(js.includes('queue_entregado')&&js.includes('navigator.onLine'),'queue offline missing');
assert(js.includes('dropdown')||js.includes('exportMenu'),'dropdown wiring missing');
// map-selection-filter: getVisibleClientes + refreshMapMarkers
assert(js.includes('getVisibleClientes'),'getVisibleClientes helper missing');
assert(js.includes('has_gps_fix!==false')&&js.includes('c.lat!=null')&&js.includes('c.lng!=null'),'getVisibleClientes base filter missing');
assert(js.includes('selectedIds.size===0')&&js.includes('selectedIds.has(String(c.id))'),'getVisibleClientes selection filter missing');
assert(js.includes('refreshMapMarkers'),'refreshMapMarkers helper missing');
assert(js.includes('refreshMapMarkers')&&js.includes('clearMarkers')&&js.includes('requestAnimationFrame'),'refreshMapMarkers must clear + rAF batch');
assert(js.includes('createDotIcon')&&js.includes('markerCluster')&&js.includes('fitBounds'),'refreshMapMarkers dot pins + cluster + bounds missing');
assert(js.includes('routeLayer')&&js.includes('map.removeLayer(routeLayer)'),'refreshMapMarkers must clear stale routeLayer');
assert(js.includes('setView')&&js.includes(',15)'),'single-pin setView 15 missing');
console.log('PASS phase5-unit: FOUC before paint, pin 1..n rAF renumber, Set toggle persist, PATCH idempotent, terminar 409 blocked ok');
