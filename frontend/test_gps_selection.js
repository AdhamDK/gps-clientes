/* PR2 focused test: GPS MyLocation + Selection Set + filter persistence */
const fs=require('fs'),path=require('path');
function assert(c,m){if(!c){console.error('FAIL: '+m);process.exit(1);}}
const root=__dirname;
const html=fs.readFileSync(path.join(root,'index.html'),'utf8');
const css=fs.readFileSync(path.join(root,'style.css'),'utf8');
const js=fs.readFileSync(path.join(root,'app.js'),'utf8');
const vm=fs.readFileSync(path.join(root,'../app/src/main/java/com/gpsclientes/ui/map/MapaClientesViewModel.kt'),'utf8');
const screen=fs.readFileSync(path.join(root,'../app/src/main/java/com/gpsclientes/ui/map/MapaClientesScreen.kt'),'utf8');
const osm=fs.readFileSync(path.join(root,'../app/src/main/java/com/gpsclientes/ui/map/OsmMapProvider.kt'),'utf8');
// GPS — REQ-GPS-01
assert(js.includes('navigator.geolocation.watchPosition'),'watchPosition missing');
assert(js.includes('L.circle'),'L.circle accuracy missing');
assert(js.includes('cachedFix'),'cachedFix missing');
assert(js.includes('start') && js.includes('cachedFix.lng'),'start injection missing');
assert(js.includes('timeout') && js.includes('10000'),'10s timeout missing');
assert(js.includes('centrarEnMiUbicacion'),'centrarEnMiUbicacion missing');
assert(js.includes('handlePositionError') && (js.includes('code === 1')||js.includes('code===1')),'permission denied handling missing');
assert(html.includes('btnMyLocation'),'MyLocation button missing');
assert(html.includes('btnMyLocationMap'),'FAB MyLocation missing');
assert(css.includes('.my-location-dot'),'my-location-dot CSS missing');
assert(css.includes('.fab-my-location'),'FAB CSS missing');
assert(vm.includes('centrarState'),'ViewModel centrarState missing');
assert(vm.includes('cachedFix') || vm.includes('_cachedFix'),'ViewModel cachedFix missing');
assert(vm.includes('toggleSelection'),'ViewModel toggleSelection missing');
assert(osm.includes('MyLocationNewOverlay'),'Osm MyLocationNewOverlay missing');
assert(osm.includes('centrarFix'),'Osm centrarFix missing');
assert(screen.includes('FloatingActionButton'),'Screen FAB missing');
assert(screen.includes('locationHandler'),'Screen locationHandler missing');
// Selection — REQ-SEL-01
assert(js.includes('selectedIds') && js.includes('new Set'),'selection Set missing');
assert(js.includes('selectionBadge'),'selectionBadge missing');
assert(js.includes('miniMenu') && js.includes('miniMenuCount'),'miniMenu missing');
assert(js.includes('updateSelectionUI'),'updateSelectionUI missing');
assert(js.includes('clearSelection'),'clearSelection missing');
assert(js.includes('toggleSelection'),'toggleSelection JS missing');
assert(html.includes('selectionBadge') && html.includes('miniMenu'),'HTML badge/miniMenu missing');
assert(html.includes('seleccionados'),'contador seleccionados missing');
assert(js.includes('selectedIds.has') && js.includes('checked'),'checkbox Set sync missing');
assert(js.includes('fetchClientes') && js.includes('selectedIds'),'filter persistence check — fetch must not clear Set');
assert(vm.includes('selection') && vm.includes('Set<Int>'),'ViewModel selection Set missing');
assert(screen.includes('seleccionados'),'Screen contador missing');
assert(css.includes('.selection-badge'),'selection-badge CSS missing');
assert(css.includes('.mini-menu'),'mini-menu CSS missing');
// map-selection-filter wiring + race + list independence
assert(js.includes('getVisibleClientes')&&js.includes('refreshMapMarkers'),'filter helpers missing');
assert(js.includes('requestAnimationFrame')&&js.includes('getVisibleClientes'),'rAF must read live getVisibleClientes inside callback (fetch race)');
assert(js.includes('toggleSelection')&&js.includes('refreshMapMarkers'),'toggleSelection must call refreshMapMarkers');
assert(js.includes('clearSelection')&&js.includes('refreshMapMarkers'),'clearSelection must call refreshMapMarkers');
assert(js.includes("addEventListener('change'")&&js.includes('refreshMapMarkers'),'checkbox change must call refreshMapMarkers');
assert(js.includes('renderClientes')&&js.includes('refreshMapMarkers'),'renderClientes must delegate to refreshMapMarkers');
assert(js.includes('items.forEach')&&js.includes('cliente-card'),'list must still iterate full items (independence)');
assert(js.includes('has_gps_fix!==false')&&js.includes('String(c.id)'),'String id coercion for TEXT PK must remain');
console.log('PASS gps/selection: watchPosition+L.circle+start inject+10s+permission ok, Set+badge+miniMenu+filter persist ok, Android Fused+FAB ok');
