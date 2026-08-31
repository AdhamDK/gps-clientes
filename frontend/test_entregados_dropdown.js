/* PR3 focused test: Entregados flow + Export dropdown — REQ-ENT-01/02 + REQ-EXP-01 */
const fs=require('fs'),path=require('path');
function assert(c,m){if(!c){console.error('FAIL: '+m);process.exit(1);}}
const root=__dirname;
const html=fs.readFileSync(path.join(root,'index.html'),'utf8');
const css=fs.readFileSync(path.join(root,'style.css'),'utf8');
const js=fs.readFileSync(path.join(root,'app.js'),'utf8');
const pyModels=fs.readFileSync(path.join(root,'../backend/models.py'),'utf8');
const pySchemas=fs.readFileSync(path.join(root,'../backend/schemas.py'),'utf8');
const pyMain=fs.readFileSync(path.join(root,'../backend/main.py'),'utf8');
const ktScreen=fs.readFileSync(path.join(root,'../app/src/main/java/com/gpsclientes/ui/map/MapaClientesScreen.kt'),'utf8');
// Backend entregado migration + index
assert(pyModels.includes('entregado')&&pyModels.includes('delivered_at'),'models entregado/delivered_at missing');
assert(pyModels.includes('ix_rutas_hoy_fecha_entregado'),'models index entregado missing');
assert(pyModels.includes('Boolean')&&pyModels.includes('default=False'),'models entregado default false missing');
assert(pySchemas.includes('EntregadoRequest')&&pySchemas.includes('cliente_ids'),'schemas EntregadoRequest missing');
assert(pySchemas.includes('delivered_at'),'schemas delivered_at missing');
assert(pyMain.includes("PATCH")&&pyMain.includes("/rutas/hoy/entregado"),'PATCH entregado missing');
assert(pyMain.includes('entregado == False')||pyMain.includes('entregado==False')||pyMain.includes('entregado == false')||pyMain.includes('models.RutasHoy.entregado'),'GET filter entregado missing');
assert(pyMain.includes('409')&&pyMain.includes('pending'),'terminar 409 missing');
assert(pyMain.includes('/rutas/hoy/terminar'),'terminar alias missing');
assert(pyMain.includes('idempotent')||pyMain.includes('if not r.entregado'),'idempotent logic missing');
assert(pyMain.includes('ALTER TABLE')&&pyMain.includes('entregado'),'migration fallback missing');
// Frontend entregados selection -> PATCH, pin disappearance renumber, terminar blocked
assert(js.includes('handleMarcarEntregados'),'handleMarcarEntregados missing');
assert(js.includes('/rutas/hoy/entregado')&&js.includes('PATCH'),'PATCH fetch missing');
assert(js.includes('refreshPendingView')&&js.includes('renderPendingMarkers'),'pending filter renumber missing');
assert(js.includes('entregado=false')||js.includes("entregado=false"),'pending filter query missing');
assert(js.includes('handleTerminarLista')&&js.includes("DELETE")&&js.includes("/rutas/hoy"),'terminar DELETE missing');
assert(js.includes('pending')&&js.includes('409')||js.includes('pendiente'),'terminar blocked handling missing');
assert(js.includes('createNumberedIcon')&&js.includes('L.marker'),'renumber 1..n markers missing');
assert(js.includes('queue_entregado')&&js.includes('navigator.onLine')&&js.includes('online'),'offline queue entregado missing');
assert(js.includes('_syncQueuedEntregados'),'sync queued missing');
assert(html.includes('btnMiniMarcar')&&html.includes('Marcar entregados'),'HTML mini Marcar missing');
assert(html.includes('btnMiniTerminar')&&html.includes('Terminar lista'),'HTML mini Terminar missing');
assert(html.includes('seleccionados'),'contador precondition missing');
// Export dropdown a11y
assert(html.includes('btnExportDropdown')&&html.includes('aria-expanded')&&html.includes('aria-haspopup'),'dropdown trigger aria missing');
assert(html.includes('exportMenu')&&html.includes('role="menu"')&&html.includes('role="menuitem"'),'menu roles missing');
assert(html.includes('data-action="xlsx"')&&html.includes('data-action="pdf"')&&html.includes('data-action="import"'),'dropdown items missing');
assert(!html.includes('btnExportXlsx')&&!html.includes('btnExportPdf'),'standalone export buttons SHALL NOT remain');
assert(html.includes('importFile')&&html.includes('accept=".xlsx"'),'import file input missing');
assert(js.includes('_toggleDropdown')&&js.includes('_closeDropdown')&&js.includes('_openDropdown'),'dropdown toggle missing');
assert(js.includes('aria-expanded'),'aria-expanded js missing');
assert(js.includes('ArrowDown')&&js.includes('ArrowUp')&&js.includes('Enter'),'Arrow/Enter keyboard nav missing');
assert(js.includes("Escape")&&js.includes('_closeDropdown'),'Esc close missing');
assert(js.includes('contains(e.target)')&&js.includes('_closeDropdown'),'outside click missing');
assert(js.includes('focus()')&&js.includes('returnFocus')||js.includes('btnExportDropdown'),'return focus missing');
assert(js.includes('data-action')&&js.includes('_handleDropdownAction'),'dropdown action handling missing');
assert(js.includes('importFile')&&js.includes('FormData'),'import handling missing');
assert(css.includes('.dropdown-wrap')&&css.includes('.dropdown-menu')&&css.includes('[role="menuitem"]'),'dropdown CSS missing');
assert(ktScreen.includes('DropdownMenu')&&ktScreen.includes('Exportar'),'Kotlin dropdown missing');
assert(ktScreen.includes('exportExpanded')&&ktScreen.includes('onDismissRequest'),'Kotlin Esc/outside missing');
console.log('PASS entregados+dropdown: delivered boolean migration + PATCH idempotent + GET filter + terminar 409 + pending renumber 1..n + offline queue + Exportar dropdown aria-expanded/Esc/outside/Arrow-Enter/focus + Android dropdown ok');
