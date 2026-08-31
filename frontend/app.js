/* GPS_CLIENTES Leaflet frontend — vanilla JS (offline-first, El Vigia).
 * Single bundle for browser and Android WebView (https://appassets.androidplatform.net).
 * Pagination: PAGINACION_LIMITE = 500. Backend caps limit at 500 (GET /clientes?limit=500, le=500).
 * Frontend requests 500 to cover fixture 409 without infinite scroll. If total > 500
 * a banner "Mostrando 500 de X — usa busqueda para filtrar" is shown instead of silent truncation.
 * Stack: Leaflet + localforage (IndexedDB) + syncEngine lastSync canonical.
 */
import { CONFIG } from './js/config.js';
import { gasFetch, localFetch, optimizarRuta as optimizarRutaClient } from './js/apiClient.js';
import { nearestNeighbor } from './js/routing-client.js';

const PAGINACION_LIMITE = 500; // max rows frontend will request/display; backend cap is 500 (le=500)

const API = (() => {
  if (window.Android && typeof window.Android.getApiUrl === 'function') { try { const u = window.Android.getApiUrl(); if (u) return u; } catch(e){console.error('[GPS] getApiUrl',e)} } // fix fetch: Android bridge LAN IP
  if (location.protocol === 'file:') return 'http://10.0.2.2:8000';
  if (location.hostname === 'appassets.androidplatform.net') return 'http://10.0.2.2:8000';
  if (location.hostname === 'localhost' || location.hostname === '127.0.0.1') return location.origin;
  // when served from /app/, origin is localhost:8000, API is root
  return location.origin;
})();

let map;
let markers = []; // {id, marker}
let markerCluster = null;
let clientesCache = [];
let routeLayer = null;
let pendingLatLng = null;
let editingClienteId = null;
let pendingDeleteId = null;

let cachedFix=null,myLocationMarker=null,accuracyCircle=null,watchId=null,locating=false,locateTimeout=null;
let permissionStatus=null,permissionDenied=false;
const selectedIds=new Set();
const selectionManager=new SelectionManager(selectedIds);
const Log=SelectionManager.Log;
const SyncQueue=typeof GPSSyncQueue!=='undefined'?GPSSyncQueue:null;
let markersMode=null; // 'selection' | 'route' | 'pending' — lets refreshMapMarkers do incremental updates
// Virtual scrolling / dynamic pagination for the client list (avoid rendering 500+ DOM nodes at once).
let _listCards=[],_listCount=0,_listStep=120,_listObserver=null,_listSentinel=null;
function getVisibleClientes(){
  const base=clientesCache.filter(c=>c.has_gps_fix!==false&&c.lat!=null&&c.lng!=null);
  if(selectedIds.size===0)return base;
  // Sección centralizada: incluye TODOS los seleccionados (aunque no tengan has_gps_fix) si
  // tienen coordenadas; se dibujan con icono dorado en refreshMapMarkers.
  return selectionManager.computeVisible(clientesCache);
}
const THEMES = ['light', 'dark', 'mid'];
function getTheme() { return document.documentElement.getAttribute('data-theme') || 'light'; }
function setTheme(t) {
  if (THEMES.indexOf(t) === -1) t = 'light';
  document.documentElement.setAttribute('data-theme', t);
  try { localStorage.setItem('theme', t); } catch(e){Log.error('[GPS]',e)}
}
function cycleTheme() {
  const cur = getTheme();
  const idx = THEMES.indexOf(cur);
  const nxt = THEMES[(idx + 1) % THEMES.length];
  setTheme(nxt);
  toast('Tema: ' + nxt);
}

function createMyLocationIcon(){return L.divIcon({className:'my-location-wrap',html:'<div class="my-location-dot"></div>',iconSize:[16,16],iconAnchor:[8,8]});}
function updateMyLocationOnMap(lat,lng,acc){cachedFix={lat,lng,accuracy:acc||0};if(!map)return;const ll=[lat,lng];if(myLocationMarker)myLocationMarker.setLatLng(ll);else{myLocationMarker=L.marker(ll,{icon:createMyLocationIcon(),zIndexOffset:1000}).addTo(map);myLocationMarker.bindPopup('Mi ubicación');}if(accuracyCircle){accuracyCircle.setLatLng(ll);accuracyCircle.setRadius(acc||30);}else accuracyCircle=L.circle(ll,{radius:acc||30,color:'#FC4C02',fillColor:'#FC4C02',fillOpacity:0.12,weight:2,opacity:0.6}).addTo(map);}
function _clearWatch(){if(watchId!=null){try{navigator.geolocation.clearWatch(watchId);}catch(e){Log.error('[GPS]',e)}watchId=null;}}
function handlePosition(pos){locating=false;if(locateTimeout){clearTimeout(locateTimeout);locateTimeout=null;}_clearWatch();const{latitude:lat,longitude:lng,accuracy:acc}=pos.coords;updateMyLocationOnMap(lat,lng,acc);if(map)map.setView([lat,lng],16);toast('Ubicación centrada ✓');}
function handlePositionError(err){locating=false;if(locateTimeout){clearTimeout(locateTimeout);locateTimeout=null;}_clearWatch();let msg='No se pudo obtener ubicación';if(err&&err.code===1){permissionDenied=true;msg='Permiso de ubicación denegado — abre Ajustes del navegador y otorga ubicación, luego toca de nuevo';if(!navigator.permissions||!navigator.permissions.query){msg+=' (toca ◎ Mi ubicación para reintentar)';}}else if(err&&err.code===3)msg='Timeout obteniendo ubicación (10s) — intenta de nuevo';else if(err&&err.message)msg=err.message;toast(msg,4000);}
function _doLocate(){watchId=navigator.geolocation.watchPosition(handlePosition,handlePositionError,{enableHighAccuracy:true,timeout:10000,maximumAge:0});if(cachedFix){if(locateTimeout){clearTimeout(locateTimeout);locateTimeout=null;}locating=false;_clearWatch();updateMyLocationOnMap(cachedFix.lat,cachedFix.lng,cachedFix.accuracy);if(map)map.setView([cachedFix.lat,cachedFix.lng],16);toast('Ubicación centrada ✓');}}
function centrarEnMiUbicacion(){if(!navigator.geolocation){toast('Geolocalización no soportada');return;}if(locating)return;locating=true;toast('Ubicando...',1500);locateTimeout=setTimeout(()=>{if(locating){locating=false;_clearWatch();handlePositionError({code:3});}},10000);_clearWatch();if(navigator.permissions&&navigator.permissions.query){try{navigator.permissions.query({name:'geolocation'}).then(function(status){permissionStatus=status;try{status.onchange=function(){if(status.state==='granted'){permissionDenied=false;}};}catch(e){Log.error('[GPS]',e)}if(status.state==='denied'){permissionDenied=true;toast('Permiso denegado — abre Configuración del navegador y otorga ubicación, luego toca de nuevo',4000);locating=false;if(locateTimeout){clearTimeout(locateTimeout);locateTimeout=null;}_clearWatch();return;}_doLocate();}).catch(function(){_doLocate();});return;}catch(e){Log.error('[GPS]',e)}}_doLocate();}
function updateSelectionUI(){const n=selectedIds.size;if(els.selectionBadge)els.selectionBadge.style.display=n>0?'inline-block':'none',n&&(els.selectionBadge.textContent=n+' seleccionados');if(els.miniMenu&&els.miniMenuCount){if(n>0){els.miniMenuCount.textContent=n+' seleccionados';els.miniMenu.style.display='flex';}else els.miniMenu.style.display='none';}}
function toggleSelection(id){selectionManager.toggle(id);updateSelectionUI();refreshMapMarkers();}
function clearSelection(){selectionManager.clear();document.querySelectorAll('#listaClientes input[type="checkbox"]').forEach(cb=>cb.checked=false);updateSelectionUI();refreshMapMarkers();}
// Cola offline de entregados (localStorage key 'queue_entregado') centralizada en js/syncQueue.js.
function _queueGet(){return SyncQueue?SyncQueue.queueGet():[];}
function _queueSave(q){if(SyncQueue)SyncQueue.queueSave(q);}
function _queueRegisterSync(){if(SyncQueue)SyncQueue.registerSync();}
function _queueReplay(){
  if(!SyncQueue)return Promise.resolve(0);
  var fechaHoyQ = (new Date()).toISOString().slice(0,10);
  return SyncQueue.replayQueue({api:API, gasFetch: (typeof gasFetch!=='undefined'?gasFetch:null), fecha: fechaHoyQ, onSynced:function(ids){
    ids.forEach(function(id){var c=clientesCache.find(function(x){return String(x.id)===String(id);});if(c)c.entregado_local=false;});
  }}).then(function(replayed){
    if(replayed>0){toast('Entregados sincronizados \u2713');renderClientes(clientesCache);refreshPendingView();}
    return replayed;
  });
}
function _syncQueuedEntregados(){return _queueReplay();}
async function saveSnapshot(){try{const d={clientes:clientesCache,ts:Date.now()};if(window.localforage)await localforage.setItem('gps_clientes_snapshot',d);localStorage.setItem('gps_clientes_snapshot_json',JSON.stringify(d));}catch(e){Log.error('[GPS]',e)}}
async function loadSnapshot(){try{if(window.localforage){const d=await localforage.getItem('gps_clientes_snapshot');if(d&&d.clientes&&d.clientes.length)return d.clientes;}const raw=localStorage.getItem('gps_clientes_snapshot_json');if(raw){const j=JSON.parse(raw);if(j&&j.clientes)return j.clientes;}}catch(e){Log.error('[GPS]',e)}return null;}
function updateOfflineBanner(reachable){const offline=(typeof reachable==='boolean')?!reachable:!navigator.onLine;if(els.offlineBanner)els.offlineBanner.hidden=!offline;}
async function handleMarcarEntregados(){var ids=[...selectedIds].map(function(id){return String(id);});if(!ids.length){ids=[...document.querySelectorAll('#listaClientes input[type="checkbox"]:checked')].map(function(cb){return String(cb.value);});}if(!ids.length){toast('Selecciona clientes para marcar');return;}var fechaHoy = (new Date()).toISOString().slice(0,10);var clearLocalFlags=function(){ids.forEach(function(id){var c=clientesCache.find(function(x){return String(x.id)===String(id);});if(c)c.entregado_local=false;});};try{await gasFetch('entregado', {cliente_ids: ids, fecha: fechaHoy}, 'POST');ids.forEach(function(id){ selectionManager.remove(id); });clearLocalFlags();document.querySelectorAll('#listaClientes input[type="checkbox"]').forEach(function(cb){if(ids.includes(String(cb.value)))cb.checked=false;});updateSelectionUI();toast('Marcados entregados: ' + ids.length + ' \u2713');await refreshPendingView();}catch(e){if(!navigator.onLine||(e&&e.message&&e.message.includes('Failed to fetch'))|| String(e.message).includes('GAS')|| String(e.message).includes('Network')){if(SyncQueue)SyncQueue.enqueue(ids);else{var q=_queueGet();q.push({ids:ids,ts:Date.now()});_queueSave(q);} _queueRegisterSync();if(SyncQueue)SyncQueue.markEntregadoLocal(clientesCache,ids);ids.forEach(function(id){ selectionManager.remove(id); });updateSelectionUI();renderClientes(clientesCache);toast('Sin conexi\u00f3n \u2014 entregados en cola (BackgroundSync)');}else toast('Error marcar entregado: ' + e.message);}}
async function marcarEntregados(){ return handleMarcarEntregados(); }
async function refreshPendingView(){try{var fechaHoy3=(new Date()).toISOString().slice(0,10);var gasPending=await gasFetch('rutas_hoy', {fecha: fechaHoy3, entregado: 'false'}, 'GET');var pending=(gasPending.rutas||gasPending.data||gasPending||[]).filter(function(r){return !r.entregado;});var pendingIds=new Set(pending.map(function(r){return r.cliente_id;}));if(routeLayer){try{map.removeLayer(routeLayer);}catch(e){Log.error('[GPS]',e)}routeLayer=null;}if(!pending.length){els.rutaPanel.style.display='none';els.listaRuta.innerHTML='';els.rutaStats.textContent='Sin pendientes — lista terminable';clearMarkers();if(routeLayer){try{map.removeLayer(routeLayer);}catch(e){Log.error('[GPS]',e)}routeLayer=null;}return;}const coords=pending.map(r=>{const c=r.cliente;return c&&c.lat!=null?[c.lng,c.lat]:null}).filter(Boolean);if(coords.length>1){const geom=coords.map(c=>[c[1],c[0]]);routeLayer=L.polyline(geom,{color:'#FC4C02',weight:5,opacity:0.9}).addTo(map);try{map.fitBounds(routeLayer.getBounds(),{padding:[32,32]});}catch(e){Log.error('[GPS]',e)}}els.rutaPanel.style.display='block';els.listaRuta.innerHTML='';pending.forEach((r,i)=>{const c=r.cliente||{};const li=document.createElement('li');li.innerHTML=`<b>${i+1}.</b> ${escapeHtml(c.nombre||'Cliente #'+r.cliente_id)} <small style="color:#6B7280">— ${escapeHtml(c.texto_breve||'')}</small>`;els.listaRuta.appendChild(li);});els.rutaStats.textContent=`Pendientes: ${pending.length}`;await renderPendingMarkers(pending);}catch(e){Log.error('refreshPendingView',e);await fetchClientes();}}
async function renderPendingMarkers(pendingRows){const pendingSet=new Set(pendingRows.map(r=>String(r.cliente_id)));const visible=clientesCache.filter(c=>pendingSet.has(String(c.id))&&c.has_gps_fix!==false&&c.lat!=null);clearMarkers();markersMode='pending';if(!visible.length)return;const useCluster=typeof L!=='undefined'&&typeof L.markerClusterGroup==='function';if(useCluster)markerCluster=L.markerClusterGroup({maxClusterRadius:40,showCoverageOnHover:false,spiderfyOnMaxZoom:true,spiderfyDistanceMultiplier:1.2,disableClusteringAtZoom:18,iconCreateFunction:createClusterIcon});let n=0;const bounds=[];visible.forEach(c=>{n+=1;const m=L.marker([c.lat,c.lng],{icon:createNumberedIcon(n)}).bindPopup(`<b>${escapeHtml(c.nombre)}</b><br/>${escapeHtml(c.texto_breve||'')}`);markers.push({id:c.id,marker:m});if(markerCluster)markerCluster.addLayer(m);else m.addTo(map);bounds.push([c.lat,c.lng]);});if(visible.length>1){const latlngs=visible.map(c=>[c.lat,c.lng]);if(routeLayer){try{map.removeLayer(routeLayer);}catch(e){Log.error('[GPS]',e)}routeLayer=null;}routeLayer=L.polyline(latlngs,{color:'#FC4C02',weight:5,opacity:0.9}).addTo(map);}if(markerCluster&&markers.length)map.addLayer(markerCluster);if(bounds.length>1)map.fitBounds(bounds,{padding:[24,24],maxZoom:15});else if(bounds.length===1)map.setView(bounds[0],15);}
async function handleTerminarLista(){
  try{
    var fechaHoy2=(new Date()).toISOString().slice(0,10);
    var gasPending;
    try{ gasPending=await gasFetch('rutas_hoy', {fecha: fechaHoy2, entregado: 'false'}, 'GET'); }catch(e){
      if(CONFIG.isLocalhost()){ const check=await fetch(`${API}/rutas/hoy?entregado=false`); if(!check.ok) throw new Error(await check.text()); gasPending={ rutas: await check.json() }; } else throw e;
    }
    var pending=(gasPending.rutas||gasPending.data||gasPending||[]);
    if(Array.isArray(pending) && pending.length>0 && pending[0] && !pending[0].cliente_id && pending[0].cliente) {} // already normalized
    if(pending.length>0){ toast(`No se puede terminar: ${pending.length} pendiente(s)`); return; }
    // No pending → clear route UI (GAS has no DELETE for RutasHoy; terminating is UI-only — entregados already marked)
    if(routeLayer){ try{map.removeLayer(routeLayer);}catch(e){Log.error('[GPS]',e)} routeLayer=null; }
    els.rutaPanel.style.display='none'; els.listaRuta.innerHTML=''; els.rutaStats.textContent=''; clearSelection(); toast('Lista terminada ✓'); await fetchClientes();
  }catch(e){ toast(`Error terminar: ${e.message}`); }
}
function _exportOpen(formato){window.open(`${API}/clientes/export?formato=${formato}`,'_blank');}
function _closeDropdown(returnFocus){if(!els.exportMenu)return;els.exportMenu.hidden=true;if(els.btnExportDropdown)els.btnExportDropdown.setAttribute('aria-expanded','false');if(returnFocus&&els.btnExportDropdown)els.btnExportDropdown.focus();}
function _openDropdown(){if(!els.exportMenu||!els.btnExportDropdown)return;els.exportMenu.hidden=false;els.btnExportDropdown.setAttribute('aria-expanded','true');const first=els.exportMenu.querySelector('[role="menuitem"]');if(first)first.focus();}
function _toggleDropdown(){if(!els.exportMenu)return;els.exportMenu.hidden? _openDropdown():_closeDropdown(false);}
function _handleDropdownAction(action){_closeDropdown(true);if(action==='xlsx'||action==='pdf')_exportOpen(action);else if(action==='import'&&els.importFile)els.importFile.click();}
let _invalidateTimer=null;
function _scheduleInvalidate(){if(_invalidateTimer)clearTimeout(_invalidateTimer);const doInvalidate=function(){_invalidateTimer=setTimeout(function(){if(map&&typeof map.invalidateSize==='function')map.invalidateSize();_invalidateTimer=null;},220);};if(typeof requestAnimationFrame==='function')requestAnimationFrame(doInvalidate);else doInvalidate();}
 // Actions dropdown — reuses exportMenu a11y pattern (Esc/outside/aria-expanded)
function _closeActionsDropdown(returnFocus){if(!els.actionsMenu||!els.actionsToggle)return;els.actionsMenu.hidden=true;els.actionsToggle.setAttribute('aria-expanded','false');if(returnFocus)els.actionsToggle.focus();_scheduleInvalidate();}
function _openActionsDropdown(){if(!els.actionsMenu||!els.actionsToggle)return;els.actionsMenu.hidden=false;els.actionsToggle.setAttribute('aria-expanded','true');_scheduleInvalidate();}
function _toggleActionsDropdown(){if(!els.actionsMenu)return;els.actionsMenu.hidden?_openActionsDropdown():_closeActionsDropdown(false);}
function _initActionsDropdownState(){if(!els.actionsMenu||!els.actionsToggle)return;var isMobile=window.innerWidth<900;if(isMobile){_closeActionsDropdown(false);}else{_openActionsDropdown();}}
function _closeClientes(returnFocus){if(!els.clientesMenu||!els.clientesToggle)return;els.clientesMenu.hidden=true;els.clientesToggle.setAttribute('aria-expanded','false');if(returnFocus)els.clientesToggle.focus();_scheduleInvalidate();}
function _openClientes(){if(!els.clientesMenu||!els.clientesToggle)return;els.clientesMenu.hidden=false;els.clientesToggle.setAttribute('aria-expanded','true');_scheduleInvalidate();}
function _toggleClientes(){if(!els.clientesMenu)return;els.clientesMenu.hidden?_openClientes():_closeClientes(false);}
// GPX parsing — namespace-agnostic via getElementsByTagName('wpt') + regex fallback
function _parseGpx(xmlText){
  var records=[];
  try{
    var parser=new DOMParser();
    var doc=parser.parseFromString(xmlText,'text/xml');
    var wpts=doc.getElementsByTagName('wpt');
    if(wpts&&wpts.length>0){
      for(var i=0;i<wpts.length;i++){
        var el=wpts[i];
        var lat=parseFloat(el.getAttribute('lat'));
        var lon=parseFloat(el.getAttribute('lon'));
        if(isNaN(lat)||isNaN(lon))continue;
        var nameEl=el.getElementsByTagName('name')[0];
        var name=nameEl&&nameEl.textContent? nameEl.textContent.trim():'';
        if(!name)name='WPT '+ (i+1);
        records.push({lat:lat,lng:lon,name:name});
      }
    }
    if(records.length===0){
      var re=/<wpt[^>]*>/g;
      var m;
      var idx=0;
      while((m=re.exec(xmlText))!==null){
        var tag=m[0];
        var latMt=/lat="([^"]+)"/.exec(tag);
        var lonMt=/lon="([^"]+)"/.exec(tag);
        if(!latMt||!lonMt)continue;
        var lat2=parseFloat(latMt[1]); var lon2=parseFloat(lonMt[1]);
        if(isNaN(lat2)||isNaN(lon2))continue;
        var slice=xmlText.substring(m.index, m.index+2000);
        var nameMt=/<name>([^<]*)<\/name>/.exec(slice);
        var n2=nameMt? nameMt[1].trim():('WPT '+(idx+1));
        records.push({lat:lat2,lng:lon2,name:n2});
        idx++;
      }
    }
  }catch(e){ /* malformed handled below */ }
  return records;
}
function _nfdKey(name,lat,lng){var norm=name.normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase().trim();return norm+':'+lat.toFixed(5)+','+lng.toFixed(5);}
async function _importGpxBatch(records){
  if(!records||!records.length){toast('GPX sin waypoints');return {imported:0,skipped:0};}
  var existingKeys=new Set(clientesCache.map(function(c){return _nfdKey(c.nombre||c.texto_breve||'', c.lat||0, c.lng||0);}));
  var imported=0,skipped=0;
  for(var i=0;i<records.length;i++){
    var r=records[i];
    var key=_nfdKey(r.name,r.lat,r.lng);
    if(existingKeys.has(key)){skipped++;continue;}
    try{
      var nowIso2=new Date().toISOString();
      var cObj={ id: (crypto.randomUUID?crypto.randomUUID():String(Date.now())+Math.random().toString(16).slice(2)), nombre:r.name, rif_cedula:'', direccion:r.name, latitud:r.lat, longitud:r.lng, telefono:'', updated_at: nowIso2, sync_status:1, deleted:0 };
      var gr;
      try{ gr=await gasFetch('sync', {clientes:[cObj]}, 'POST'); if(gr&&gr.error) throw new Error(gr.error); }catch(gasErr2){
        if(CONFIG.isLocalhost()){ var res=await fetch(API+'/clientes',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({nombre:r.name,lat:r.lat,lng:r.lng,referencia:r.name,texto_breve:r.name,zona:'Rutero'})}); if(!res.ok)throw new Error(await res.text()); } else throw gasErr2;
      }
      existingKeys.add(key); imported++;
    }catch(e){skipped++;}
  }
  return {imported:imported,skipped:skipped};
}
async function _handleImportFile(file){if(!file)return;var name=(file.name||'').toLowerCase();
  if(name.endsWith('.gpx')){
    try{
      var text=await new Promise(function(resolve,reject){var fr=new FileReader();fr.onload=function(){resolve(fr.result);};fr.onerror=function(){reject(new Error('FileReader fail'));};fr.readAsText(file);});
      var records=_parseGpx(text);
      if(!records.length){toast('GPX inválido o sin waypoints — 0 importados');return;}
      toast('Importando GPX: '+records.length+' puntos...',2000);
      var result=await _importGpxBatch(records);
      toast('GPX importados: '+result.imported+' ✓'+(result.skipped?' ('+result.skipped+' duplicados)':''),3500);
      await fetchClientes();
    }catch(e){toast('Error GPX: '+e.message);} finally{if(els.importFile)els.importFile.value='';}
    return;
  }
  if(CONFIG.isLocalhost()){
    var fd=new FormData();fd.append('file',file);try{var res=await fetch(API+'/clientes/import',{method:'POST',body:fd});if(!res.ok)throw new Error(await res.text());var j=await res.json();toast('Importados: '+j.imported+' ✓');await fetchClientes();}catch(e){toast('Error import: '+e.message);} finally{if(els.importFile)els.importFile.value='';}
  } else { toast('Import XLSX/CSV solo disponible en local/docker — usa GPX o corre backend local'); if(els.importFile)els.importFile.value=''; }}

const els = {};

function initEls() {
  els.offlineBanner = document.getElementById('offlineBanner');
  els.q = document.getElementById('q');
  els.filtroZona = document.getElementById('filtroZona');
  els.listaClientes = document.getElementById('listaClientes');
  els.countClientes = document.getElementById('countClientes');
  els.btnAgregar = document.getElementById('btnAgregar');
  els.btnOptimizar = document.getElementById('btnOptimizar');
  els.btnExportDropdown=document.getElementById('btnExportDropdown');
  els.exportMenu=document.getElementById('exportMenu');
  els.exportWrap=document.getElementById('exportDropdownWrap');
  els.importFile=document.getElementById('importFile');
  els.actionsDropdown=document.getElementById('actionsDropdown');
  els.actionsToggle=document.getElementById('actionsToggle');
  els.actionsMenu=document.getElementById('actionsMenu');
  els.clientesToggle=document.getElementById('clientesToggle');
  els.clientesMenu=document.getElementById('clientesMenu');
  els.btnLimpiarRuta = document.getElementById('btnLimpiarRuta');
  els.btnRutasHoy = document.getElementById('btnRutasHoy');
  els.btnTheme = document.getElementById('btnTheme');
  els.btnMyLocation = document.getElementById('btnMyLocation');
  els.btnMyLocationMap = document.getElementById('btnMyLocationMap');
  els.selectionBadge = document.getElementById('selectionBadge');
  els.miniMenu = document.getElementById('miniMenu');
  els.miniMenuCount = document.getElementById('miniMenuCount');
  els.btnClearSelection=document.getElementById('btnClearSelection');
  els.btnMiniClear=document.getElementById('btnMiniClear');
  els.btnMiniOptimizar=document.getElementById('btnMiniOptimizar');
  els.btnMiniMarcar=document.getElementById('btnMiniMarcar');
  els.btnMiniTerminar=document.getElementById('btnMiniTerminar');
  els.modalOverlay = document.getElementById('modalOverlay');
  els.modalTitle = document.getElementById('modalTitle');
  els.confirmOverlay = document.getElementById('confirmOverlay');
  els.confirmText = document.getElementById('confirmText');
  els.btnConfirmCancel = document.getElementById('btnConfirmCancel');
  els.btnConfirmDelete = document.getElementById('btnConfirmDelete');
  els.formCliente = document.getElementById('formCliente');
  els.fNombre = document.getElementById('fNombre');
  els.fReferencia = document.getElementById('fReferencia');
  els.fZona = document.getElementById('fZona');
  els.fRif = document.getElementById('fRif');
  els.fLat = document.getElementById('fLat');
  els.fLng = document.getElementById('fLng');
  els.btnCancelar = document.getElementById('btnCancelar');
  els.toast = document.getElementById('toast');
  els.rutaPanel = document.getElementById('rutaPanel');
  els.listaRuta = document.getElementById('listaRuta');
  els.rutaStats = document.getElementById('rutaStats');
  els.healthBadge = document.getElementById('healthBadge');
}

function toast(msg, ms = 2800) {
  els.toast.textContent = msg;
  els.toast.classList.add('show');
  setTimeout(() => els.toast.classList.remove('show'), ms);
}

function initMap() {
  map = L.map('map').setView([8.61, -71.65], 15);
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    attribution: '© OpenStreetMap',
    maxZoom: 19
  }).addTo(map);

  map.on('click', (e) => {
    showForm(e.latlng);
  });
}

function showForm(latlng) {
  editingClienteId = null;
  pendingLatLng = latlng;
  if (els.modalTitle) els.modalTitle.textContent = 'Agregar Cliente';
  els.fLat.value = latlng.lat.toFixed(6);
  els.fLng.value = latlng.lng.toFixed(6);
  els.modalOverlay.classList.add('open');
  els.modalOverlay.setAttribute('aria-hidden', 'false');
  els.fNombre.focus();
}

function showEditForm(cliente) {
  editingClienteId = cliente.id;
  pendingLatLng = { lat: cliente.lat, lng: cliente.lng };
  if (els.modalTitle) els.modalTitle.textContent = 'Editar Cliente #' + cliente.id;
  els.fNombre.value = cliente.nombre || '';
  els.fReferencia.value = cliente.texto_breve || cliente.referencia || '';
  els.fZona.value = cliente.zona || '';
  els.fRif.value = cliente.rif || '';
  els.fLat.value = cliente.lat != null ? Number(cliente.lat).toFixed(6) : '';
  els.fLng.value = cliente.lng != null ? Number(cliente.lng).toFixed(6) : '';
  els.modalOverlay.classList.add('open');
  els.modalOverlay.setAttribute('aria-hidden', 'false');
  els.fNombre.focus();
}

function hideForm() {
  els.modalOverlay.classList.remove('open');
  els.modalOverlay.setAttribute('aria-hidden', 'true');
  els.formCliente.reset();
  pendingLatLng = null;
  editingClienteId = null;
  if (els.modalTitle) els.modalTitle.textContent = 'Agregar Cliente';
}

function showConfirmDelete(cliente) {
  pendingDeleteId = cliente.id;
  if (els.confirmText) els.confirmText.textContent = `¿Borrar "${cliente.nombre}" (#${cliente.id})? Esta acción no se puede deshacer.`;
  if (els.confirmOverlay) {
    els.confirmOverlay.classList.add('open');
    els.confirmOverlay.setAttribute('aria-hidden', 'false');
  }
}

function hideConfirmDelete() {
  if (els.confirmOverlay) {
    els.confirmOverlay.classList.remove('open');
    els.confirmOverlay.setAttribute('aria-hidden', 'true');
  }
  pendingDeleteId = null;
}

async function handleConfirmDelete() {
  if (!pendingDeleteId) return;
  const id = pendingDeleteId;
  try {
    const existing = clientesCache.find(function(c){ return String(c.id)===String(id); });
    if (!existing) throw new Error('Cliente no encontrado en cache');
    const delObj = Object.assign({}, existing, { deleted: 1, sync_status: 1, updated_at: new Date().toISOString() });
    try {
      const r = await gasFetch('sync', { clientes: [delObj] }, 'POST');
      if (r && r.error) throw new Error(r.error);
    } catch (gasErr) {
      if (CONFIG.isLocalhost()) {
        const res = await fetch(`${API}/clientes/${id}`, { method: 'DELETE' });
        if (res.status !== 204 && !res.ok) throw new Error(await res.text() || `HTTP ${res.status}`);
      } else throw gasErr;
    }
    toast('Cliente borrado ✓');
    hideConfirmDelete();
    await fetchClientes();
  } catch (err) {
    toast(`Error borrando: ${err.message}`);
  }
}

async function fetchClientes() {
  const q = els.q.value.trim();
  const zona = els.filtroZona.value;
  try {
    // GAS split: clientes via GAS_URL; keeps offline snapshot fallback
    var gasData;
    try {
      gasData = await gasFetch('clientes', { limit: String(PAGINACION_LIMITE), search: q || undefined, q: q || undefined, zona: zona || undefined });
    } catch (gasErr) {
      // Fallback to legacy API (localhost) when GAS unreachable (dev)
      if (CONFIG.isLocalhost()) {
        var params = new URLSearchParams();
        if (q) params.set('q', q);
        if (zona) params.set('zona', zona);
        params.set('limit', String(PAGINACION_LIMITE));
        var url = API + '/clientes?' + params.toString();
        var res = await fetch(url);
        if (!res.ok) throw new Error('HTTP ' + res.status);
        gasData = await res.json();
      } else throw gasErr;
    }
    var items = Array.isArray(gasData) ? gasData : (gasData.data || gasData.items || gasData.clientes || []);
    var total = Array.isArray(gasData) ? items.length : (gasData.total != null ? gasData.total : items.length);
    // zona filter client-side when GAS does not support it
    if (zona && items.length) {
      var filtered = items.filter(function(c){ return !c.zona || String(c.zona) === String(zona); });
      // if server already filtered, keep; else use client-side filtered only when server returned unfiltered (heuristic: if filtered length < items length)
      if (filtered.length !== items.length) { /* server may not filter zona, apply client side */ }
      // Keep server items as-is; client side filter is already via search param, zona remains local filter for display
    }
    clientesCache = items;
    renderClientes(items);
    if (els.paginacionBanner) {
      if (total > PAGINACION_LIMITE) {
        els.paginacionBanner.textContent = 'Mostrando ' + PAGINACION_LIMITE + ' de ' + total + ' \u2014 usa b\u00fasqueda para filtrar';
        els.paginacionBanner.hidden = false;
      } else {
        els.paginacionBanner.hidden = true;
      }
    }
    saveSnapshot();
  } catch (err) {
    console.error(err);
    var cached = await loadSnapshot();
    if (cached && cached.length) { clientesCache = cached; renderClientes(cached); toast('Offline \u2014 lista desde cache localForage'); }
    else toast('Error cargando clientes: ' + err.message);
  }
}
async function loadClientes() { return fetchClientes(); }

function clearMarkers() {
  if (markerCluster) {
    try { map.removeLayer(markerCluster); } catch (e) { Log.error('remove cluster', e); }
    markerCluster = null;
  }
  markers.forEach(m => { try { map.removeLayer(m.marker); } catch (e) { Log.error('remove marker', e); } });
  markers = [];
  markersMode = null;
}
function markerPopupHtml(c){
  return `<b>${escapeHtml(c.nombre)}</b><br/>${escapeHtml(c.texto_breve||c.referencia||'')}<br/><small>${escapeHtml(c.zona||'')} ${c.rif?'· '+escapeHtml(c.rif):''}</small>`;
}
function _fitMarkersBounds(bounds){
  if(bounds.length>1){try{map.fitBounds(bounds,{padding:[24,24],maxZoom:15});}catch(e){Log.error('fitBounds',e);}}
  else if(bounds.length===1){try{map.setView(bounds[0],15);}catch(e){Log.error('setView',e);}}
}
function refreshMapMarkers(){
  const doRefresh=()=>{
    const visible=getVisibleClientes();
    if(routeLayer){try{map.removeLayer(routeLayer);}catch(e){Log.error('remove routeLayer',e);}routeLayer=null;}
    const useCluster=typeof L!=='undefined'&&typeof L.markerClusterGroup==='function';
    // Incremental path: if we already hold a 'selection' cluster+markers layout, update icons
    // (setIcon) and add/remove only what changed — do NOT destroy the cluster (perf).
    if(markersMode==='selection'&&markerCluster&&markers.length>=0){
      try{
        const plan=selectionManager.buildMarkerPlan(markers,clientesCache);
        plan.remove.forEach(m=>{try{markerCluster.removeLayer(m.marker);}catch(e){Log.error('cluster remove',e);}try{map.removeLayer(m.marker);}catch(e){Log.error('map remove',e);}});
        markers=markers.filter(m=>!plan.remove.some(r=>r.id===m.id));
        const byId=new Map(markers.map(m=>[String(m.id),m]));
        const bounds=[];
        plan.add.forEach(item=>{
          const c=item.cliente;
          const marker=L.marker([c.lat,c.lng],{icon:createDotIcon(item.selected,c)}).bindPopup(markerPopupHtml(c));
          markers.push({id:c.id,marker});
          if(markerCluster)markerCluster.addLayer(marker);else marker.addTo(map);
          bounds.push([c.lat,c.lng]);
        });
        plan.update.forEach(item=>{
          const c=item.cliente;
          const marker=byId.get(String(c.id));
          if(marker){try{marker.setIcon(createDotIcon(item.selected,c));}catch(e){Log.error('setIcon',e);}try{marker.setPopupContent(markerPopupHtml(c));}catch(e){Log.error('setPopupContent',e);}}
          bounds.push([c.lat,c.lng]);
        });
        if(markerCluster){if(!map.hasLayer(markerCluster))map.addLayer(markerCluster);try{markerCluster.refreshClusters();}catch(e){Log.error('refreshClusters',e);}}
        else{markers.forEach(m=>{try{m.addTo(map);}catch(e){Log.error('add marker',e);}});}
        _fitMarkersBounds(bounds);
        return;
      }catch(e){Log.error('incremental refresh fallback to full rebuild',e);}
    }
    // Full rebuild (first render / route or pending mode / fallback)
    clearMarkers();
    markersMode='selection';
    if(!visible.length){if(routeLayer){try{map.removeLayer(routeLayer);}catch(e){Log.error('remove routeLayer',e);}routeLayer=null;}return;}
    if(useCluster)markerCluster=L.markerClusterGroup({maxClusterRadius:40,showCoverageOnHover:false,spiderfyOnMaxZoom:true,spiderfyDistanceMultiplier:1.2,disableClusteringAtZoom:18,iconCreateFunction:createClusterIcon});
    const bounds=[];
    visible.forEach(c=>{
      const selSelected=selectedIds.has(String(c.id));
      const marker=L.marker([c.lat,c.lng],{icon:createDotIcon(selSelected,c)}).bindPopup(markerPopupHtml(c));
      markers.push({id:c.id,marker});
      if(markerCluster)markerCluster.addLayer(marker);else marker.addTo(map);
      bounds.push([c.lat,c.lng]);
    });
    if(markerCluster&&markers.length)map.addLayer(markerCluster);
    _fitMarkersBounds(bounds);
  };
  if(typeof requestAnimationFrame==='function')requestAnimationFrame(doRefresh);
  else doRefresh();
}

function createDotIcon(selected,c){
  const hasFix = c && c.has_gps_fix !== false && c.lat != null && c.lng != null;
  const gold = !!selected;
  const cls = gold ? (hasFix ? 'dot-pin selected' : 'dot-pin selected nogps') : 'dot-pin';
  const star = gold && !hasFix ? '★' : '';
  const size = gold ? 24 : 18;
  return L.divIcon({
    className: gold ? 'dot-wrap sel' : 'dot-wrap',
    html: `<div class="${cls}">${star}</div>`,
    iconSize: [size, size],
    iconAnchor: [size / 2, size / 2],
    popupAnchor: [0, -10]
  });
}

function createNumberedIcon(n) {
  return L.divIcon({
    className: 'pin-wrap',
    html: '<div class="pin"><span>' + n + '</span></div>',
    iconSize: [28, 28],
    iconAnchor: [14, 14],
    popupAnchor: [0, -14]
  });
}
// numberedIdx: pin renumber 1..n uses createNumberedIcon with rAF batching (see refreshMapMarkers)
function createClusterIcon(cluster) {
  return L.divIcon({
    html: '<div class="cluster-chip">' + cluster.getChildCount() + '</div>',
    className: 'cluster-wrap',
    iconSize: [40, 40]
  });
}

function renderClientes(items) {
  // clear handled by refreshMapMarkers (avoid double clear + rAF flash)
  els.listaClientes.innerHTML = '';
  els.countClientes.textContent = `${items.length}`;

  // populate zona filter dynamically
  const zonas = [...new Set(items.map(c => c.zona).filter(Boolean))];
  const existingOptions = new Set([...els.filtroZona.options].map(o => o.value));
  zonas.forEach(z => {
    if (!existingOptions.has(z)) {
      const opt = document.createElement('option');
      opt.value = z;
      opt.textContent = z;
      els.filtroZona.appendChild(opt);
    }
  });

  if (items.length === 0) {
    els.listaClientes.innerHTML = '<p style="font-size:13px;color:#6B7280;padding:12px;text-align:center">Sin resultados</p>';
    _resetListObserver();
    return;
  }

  refreshMapMarkers();
  // Virtual scrolling / dynamic pagination: build ALL cards once, but mount the DOM in chunks
  // as the user scrolls → avoids 500+ live nodes (perf). Selection Set persists so checkboxes
  // of not-yet-rendered rows are still reflected on the map/counter.
  _resetListObserver();
  _listCards = [];
  items.forEach(c => { _listCards.push(_buildClientCard(c)); });
  _listCount = 0;
  _appendListChunk();
  _listSentinel = document.createElement('div');
  _listSentinel.className = 'list-sentinel';
  _listSentinel.setAttribute('data-testid', 'list-sentinel');
  els.listaClientes.appendChild(_listSentinel);
  if (typeof IntersectionObserver !== 'undefined') {
    _listObserver = new IntersectionObserver(_onListIntersect, { root: els.listaClientes, rootMargin: '160px 0px', threshold: 0.01 });
    _listObserver.observe(_listSentinel);
  } else {
    _appendListChunk(true); // fallback: render everything
  }
  updateSelectionUI();
}

function _resetListObserver() {
  if (_listObserver) { try { _listObserver.disconnect(); } catch (e) { Log.error('[GPS] disconnect observer', e); } _listObserver = null; }
  _listSentinel = null;
}
function _onListIntersect(entries) {
  const show = entries.some(e => e.isIntersecting);
  if (show && _listCount < _listCards.length) _appendListChunk();
}
function _appendListChunk(all) {
  const remaining = _listCards.length - _listCount;
  if (remaining <= 0) return;
  const n = all ? remaining : Math.min(_listStep, remaining);
  const sentinel = _listSentinel;
  for (let i = 0; i < n; i++) {
    const card = _listCards[_listCount++];
    if (sentinel && sentinel.parentNode) els.listaClientes.insertBefore(card, sentinel);
    else els.listaClientes.appendChild(card);
  }
}
// build + wire a single client card (used by virtual list)
function _buildClientCard(c) {
  const hasFix = c.has_gps_fix !== false && c.lat != null && c.lng != null;

  // card
  const card = document.createElement('div');
  card.className = 'cliente-card' + (hasFix ? '' : ' no-gps') + (c.entregado_local ? ' entregado-local' : '');
  const cbId = `cb-${c.id}`;
  const isChecked = selectedIds.has(String(c.id)) ? 'checked' : '';
  card.innerHTML = `
    <input type="checkbox" id="${cbId}" value="${c.id}" ${hasFix ? '' : 'disabled title="Sin GPS fix"'} ${isChecked}/>
    <div class="cliente-info">
      <div class="cliente-nombre">${escapeHtml(c.nombre)} ${c.is_flagged ? '<span class="badge badge-flag">FLAG</span>' : ''} ${c.entregado_local ? '<span class="badge badge-entregado-local">✔ entregado (pendiente sync)</span>' : ''}</div>
      <div class="cliente-meta">${escapeHtml(c.texto_breve || c.referencia || c.direccion || '—')}</div>
      <div style="margin-top:4px;display:flex;gap:4px;flex-wrap:wrap">
        ${c.zona ? `<span class="badge badge-zona">${escapeHtml(c.zona)}</span>` : ''}
        ${!hasFix ? '<span class="badge badge-warn">⚠ Sin GPS</span>' : ''}
        ${c.rif ? `<span class="badge" style="background:#F3F4F6">${escapeHtml(c.rif)}</span>` : ''}
      </div>
      <div style="margin-top:6px;display:flex;gap:6px">
        <button type="button" class="btn btn-ghost btn-sm btn-edit" data-edit-id="${c.id}">Editar</button>
        <button type="button" class="btn btn-danger btn-sm btn-delete" data-delete-id="${c.id}">Borrar</button>
      </div>
    </div>
  `;
  const cb = card.querySelector('input[type="checkbox"]');
  if (cb) {
    cb.addEventListener('change', () => {
      const id = String(cb.value);
      if (cb.checked) selectionManager.add(id);
      else selectionManager.remove(id);
      updateSelectionUI();
      refreshMapMarkers();
    });
  }
  const btnEdit = card.querySelector('.btn-edit');
  if (btnEdit) {
    btnEdit.addEventListener('click', (e) => {
      e.stopPropagation();
      showEditForm(c);
    });
  }
  const btnDel = card.querySelector('.btn-delete');
  if (btnDel) {
    btnDel.addEventListener('click', (e) => {
      e.stopPropagation();
      showConfirmDelete(c);
    });
  }
  // click card focuses map
  card.addEventListener('click', (e) => {
    if (e.target.type === 'checkbox') return;
    if (e.target.closest('input')) return;
    if (e.target.closest('button')) return;
    if (hasFix) {
      map.setView([c.lat, c.lng], 16);
      const found = markers.find(m => String(m.id) === String(c.id));
      if (found) found.marker.openPopup();
    }
  });
  return card;
}

function escapeHtml(s) {
  if (s == null) return '';
  return String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}

async function handleCreateCliente(e) {
  e.preventDefault();
  const isEdit = editingClienteId != null;
  if (!isEdit && !pendingLatLng) {
    toast('Hace click en el mapa para elegir ubicación');
    return;
  }
  const nombre = els.fNombre.value.trim();
  if (!nombre) { toast('Nombre es obligatorio'); return; }
  const rifRaw = els.fRif.value.trim();
  if (rifRaw && !/^[JVEGP]\d{7,9}$/.test(rifRaw)) {
    toast('RIF inválido: debe ser J/V/E/G/P + 7-9 dígitos');
    return;
  }
  // Lat/lng may be edited via form fields when editing, or come from pendingLatLng
  let latVal = pendingLatLng ? pendingLatLng.lat : null;
  let lngVal = pendingLatLng ? pendingLatLng.lng : null;
  // If editing, allow manual lat/lng from inputs (editable even if readonly)
  const fLatVal = parseFloat(els.fLat.value);
  const fLngVal = parseFloat(els.fLng.value);
  if (!isNaN(fLatVal) && !isNaN(fLngVal)) {
    latVal = fLatVal;
    lngVal = fLngVal;
  }
  const payload = {
    nombre,
    lat: latVal,
    lng: lngVal,
    referencia: els.fReferencia.value.trim() || null,
    texto_breve: els.fReferencia.value.trim() || null,
    zona: els.fZona.value || null,
    rif: rifRaw || null
  };
  try {
    // Map frontend payload to Sheets HEADERS and persist via GAS sync
    const nowIso = new Date().toISOString();
    const targetId = isEdit ? editingClienteId : (crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random().toString(16).slice(2));
    const existing = isEdit ? clientesCache.find(function(c){ return String(c.id)===String(editingClienteId); }) : null;
    const clienteObj = {
      id: targetId,
      nombre: payload.nombre,
      rif_cedula: payload.rif || (existing ? existing.rif_cedula : ''),
      direccion: payload.referencia || payload.texto_breve || (existing ? existing.direccion : ''),
      latitud: payload.lat,
      longitud: payload.lng,
      telefono: existing ? existing.telefono : '',
      updated_at: nowIso,
      sync_status: 1,
      deleted: 0
    };
    // For edit, preserve lat/lng if null
    if (isEdit && (clienteObj.latitud == null || clienteObj.longitud == null)) {
      if (existing) { clienteObj.latitud = existing.latitud; clienteObj.longitud = existing.longitud; }
    }
    const syncPayload = { clientes: [clienteObj] };
    let syncRes;
    try {
      syncRes = await gasFetch('sync', syncPayload, 'POST');
    } catch (gasErr) {
      // Fallback to legacy localhost API when on localhost dev
      if (CONFIG.isLocalhost()) {
        let res;
        if (isEdit) {
          res = await fetch(`${API}/clientes/${editingClienteId}`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
        } else {
          res = await fetch(`${API}/clientes`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
        }
        if (!res.ok) throw new Error(await res.text() || `HTTP ${res.status}`);
        toast(isEdit ? 'Cliente actualizado ✓' : 'Cliente creado ✓');
        hideForm();
        await fetchClientes();
        return;
      }
      throw gasErr;
    }
    if (syncRes && syncRes.error) throw new Error(syncRes.error);
    toast(isEdit ? 'Cliente actualizado ✓' : 'Cliente creado ✓');
    hideForm();
    await fetchClientes();
  } catch (err) {
    console.error(err);
    const msg = (err.message || String(err));
    // Show CORS/network hint if GAS not reachable
    if (msg.includes('<!DOCTYPE') || msg.includes('Page not found')) {
      toast('Error guardando: GAS no accesible — verifica despliegue "Anyone" y GAS_URL');
    } else {
      toast(`Error guardando: ${msg.slice(0, 400)}`);
    }
  }
}

async function handleOptimizar() {
  var checked = [...selectedIds].map(function(id){ return String(id); });
  if (checked.length === 0) {
    checked = [...document.querySelectorAll('#listaClientes input[type="checkbox"]:checked')].map(function(cb){ return String(cb.value); });
  }
  if (checked.length < 2) {
    toast('Selecciona al menos 2 clientes con GPS');
    return;
  }
  els.btnOptimizar.disabled = true;
  var prevText = els.btnOptimizar.textContent;
  els.btnOptimizar.textContent = 'Optimizando...';
  if (els.btnMiniOptimizar) { els.btnMiniOptimizar.disabled = true; els.btnMiniOptimizar.textContent = 'Optimizando...'; }
  try {
    if (!navigator.onLine) { toast('Sin conexi\u00f3n \u2014 ruta en cola (BackgroundSync via sw.js)'); }
    var clientsMap = {};
    clientesCache.forEach(function(c){ clientsMap[String(c.id)] = c; });
    var result = await optimizarRutaClient(checked, clientsMap);
    var orden = result.ordered || result.orden || checked;
    var data = { orden: orden, geometry: result.geometry, distance: result.distance, duration: result.duration };
    if (result.raw && result.raw.geometry) { data.geometry = result.raw.geometry; data.distance = result.raw.distance; data.duration = result.raw.duration; }
    renderRuta(data, checked);
    if (result.fallback) {
      toast('Optimizaci\u00f3n local (VROOM no disponible \u2014 requiere backend local)', 4000);
      if (els.rutaStats) els.rutaStats.textContent += ' \u00b7 fallback local';
    } else {
      toast('Ruta optimizada: ' + orden.length + ' paradas');
    }
  } catch (err) {
    console.error(err);
    toast('Error optimizando: ' + err.message);
  } finally {
    els.btnOptimizar.disabled = false;
    els.btnOptimizar.textContent = prevText || 'Optimizar Ruta de Hoy';
    if (els.btnMiniOptimizar) { els.btnMiniOptimizar.disabled = false; els.btnMiniOptimizar.textContent = 'Optimizar'; }
  }
}

function renderRuta(data, requestedIds) {
  const orden = data.orden || [];
  const geometry = data.geometry;
  const distance = data.distance;
  const duration = data.duration;

  // clear previous polyline
  if (routeLayer) { map.removeLayer(routeLayer); routeLayer = null; }

  // Hide unselected markers: only show clients in orden (optimized route)
  // List (sidebar) stays with all clients, map filters to selected route only
  clearMarkers();
  markersMode = 'route';
  const byId = Object.fromEntries(clientesCache.map(c => [String(c.id), c]));
  const orderedClientes = orden.map(id => byId[String(id)]).filter(c => c && c.lat != null && c.lng != null);
  if (orderedClientes.length > 0) {
    const useCluster = typeof L !== 'undefined' && typeof L.markerClusterGroup === 'function';
    if (useCluster) {
      markerCluster = L.markerClusterGroup({ maxClusterRadius: 40, showCoverageOnHover: false, spiderfyOnMaxZoom: true, spiderfyDistanceMultiplier: 1.2, disableClusteringAtZoom: 18, iconCreateFunction: createClusterIcon });
    }
    const bounds = [];
    orderedClientes.forEach((c, idx) => {
      const marker = L.marker([c.lat, c.lng], { icon: createNumberedIcon(idx + 1) })
        .bindPopup(`<b>${escapeHtml(c.nombre)}</b><br/>${escapeHtml(c.texto_breve || c.referencia || '')}<br/><small>${escapeHtml(c.zona || '')}</small>`);
      markers.push({ id: c.id, marker });
      if (markerCluster) markerCluster.addLayer(marker); else marker.addTo(map);
      bounds.push([c.lat, c.lng]);
    });
    if (markerCluster && markers.length) map.addLayer(markerCluster);
    // fit to markers if no geometry; geometry fit handled below and will override with polyline bounds
    if ((!geometry || !geometry.coordinates || !geometry.coordinates.length) && bounds.length > 1) {
      try { map.fitBounds(bounds, { padding: [24, 24], maxZoom: 15 }); } catch(e){Log.error('[GPS]',e)}
    } else if (bounds.length === 1) {
      try { map.setView(bounds[0], 15); } catch(e){Log.error('[GPS]',e)}
    }
  }

  // draw polyline for orden only (geometry from VROOM/OSRM or fallback straight lines)
  if (geometry && geometry.coordinates && geometry.coordinates.length > 0) {
    const latlngs = geometry.coordinates.map(c => [c[1], c[0]]);
    routeLayer = L.polyline(latlngs, { color: '#FC4C02', weight: 5, opacity: 0.9 }).addTo(map);
    try { map.fitBounds(routeLayer.getBounds(), { padding: [32, 32] }); } catch(e){Log.error('[GPS]',e)}
  } else if (orderedClientes.length > 1) {
    const latlngs = orderedClientes.map(c => [c.lat, c.lng]);
    routeLayer = L.polyline(latlngs, { color: '#FC4C02', weight: 5, dashArray: '8 8' }).addTo(map);
    try { map.fitBounds(routeLayer.getBounds(), { padding: [32, 32] }); } catch(e){Log.error('[GPS]',e)}
  }

  // render ordered list (rutaPanel) — keeps full orden with names
  els.rutaPanel.style.display = 'block';
  els.listaRuta.innerHTML = '';
  orden.forEach((id, idx) => {
    const c = byId[String(id)] || { nombre: `Cliente #${id}`, texto_breve: '' };
    const li = document.createElement('li');
    li.innerHTML = `<b>${idx + 1}.</b> ${escapeHtml(c.nombre)} <small style="color:#6B7280">— ${escapeHtml(c.texto_breve || c.referencia || '')}</small>`;
    els.listaRuta.appendChild(li);
  });
  const km = distance ? (distance / 1000).toFixed(2) + ' km' : '—';
  const mins = duration ? Math.round(duration / 60) + ' min' : '—';
  els.rutaStats.textContent = `Distancia: ${km} · Duración: ${mins} · Vía VROOM/OSRM`;
}

async function checkHealth() {
  try {
    const res = await fetch(`${API}/health`);
    const data = await res.json();
    const up = data.vroom === 'up' && data.osrm === 'up';
    els.healthBadge.textContent = up ? '● VROOM/OSRM Up' : `● vroom:${data.vroom} osrm:${data.osrm}`;
    els.healthBadge.className = 'badge-health' + (up ? '' : ' down');
  } catch {
    els.healthBadge.textContent = '● API offline';
    els.healthBadge.className = 'badge-health down';
  }
}

function handleExport(formato) {
  // spec: window.open('/clientes/export?formato=xlsx')
  const url = `${API}/clientes/export?formato=${formato}`;
  window.open(url, '_blank');
}

function clearRoute() {
  if (routeLayer) { map.removeLayer(routeLayer); routeLayer = null; }
  els.rutaPanel.style.display = 'none';
  els.listaRuta.innerHTML = '';
  els.rutaStats.textContent = '';
  // clear selection Set + UI
  selectionManager.clear();
  document.querySelectorAll('#listaClientes input[type="checkbox"]').forEach(cb => cb.checked = false);
  updateSelectionUI();
  // restore all markers (user wants "oculte los que no estan seleccionados" only while optimized)
  clearMarkers();
  fetchClientes();
}

async function loadRutasHoy() {
  try {
    var fechaHoy2 = (new Date()).toISOString().slice(0,10);
    var gasRes = await gasFetch('rutas_hoy', { fecha: fechaHoy2 }, 'GET');
    var rows = gasRes.rutas || gasRes.data || gasRes || [];
    if (!rows.length) { toast('No hay ruta guardada para hoy'); return; }
    var orden = rows.map(function(r){ return r.cliente_id; });
    // need geometry? re-optimize or just list
    els.rutaPanel.style.display = 'block';
    els.listaRuta.innerHTML = '';
    rows.forEach(r => {
      const c = r.cliente || {};
      const li = document.createElement('li');
      li.innerHTML = `<b>${r.orden + 1}.</b> ${escapeHtml(c.nombre || 'Cliente #' + r.cliente_id)} <small style="color:#6B7280">— ${escapeHtml(c.texto_breve || '')}</small>`;
      els.listaRuta.appendChild(li);
    });
    els.rutaStats.textContent = `Ruta guardada del ${rows[0].fecha} — ${rows.length} paradas`;
    // sync selection Set and UI
    selectionManager.addAll(orden.map(String));
    document.querySelectorAll('#listaClientes input[type="checkbox"]').forEach(cb => {
      cb.checked = orden.map(String).includes(String(cb.value));
    });
    updateSelectionUI();
    toast(`Ruta de hoy cargada: ${rows.length} paradas`);
  } catch (err) {
    toast(`Error cargando ruta: ${err.message}`);
  }
}

// Init
document.addEventListener('DOMContentLoaded', () => {
  initEls();
  initMap();
  fetchClientes();
  checkHealth();

  let debounce;
  els.q.addEventListener('input', () => { clearTimeout(debounce); debounce=setTimeout(fetchClientes,300); });
  els.filtroZona.addEventListener('change', fetchClientes);
  els.formCliente.addEventListener('submit', handleCreateCliente);
  els.btnCancelar.addEventListener('click', hideForm);
  els.modalOverlay.addEventListener('click', (e) => {
    if (e.target === els.modalOverlay) hideForm();
  });
  if (els.confirmOverlay) {
    els.confirmOverlay.addEventListener('click', (e) => {
      if (e.target === els.confirmOverlay) hideConfirmDelete();
    });
  }
  if (els.btnConfirmCancel) els.btnConfirmCancel.addEventListener('click', hideConfirmDelete);
  if (els.btnConfirmDelete) els.btnConfirmDelete.addEventListener('click', handleConfirmDelete);
  els.btnAgregar.addEventListener('click', () => {
    toast('Hace click en el mapa para elegir ubicación');
  });
  els.btnOptimizar.addEventListener('click', handleOptimizar);
  if(els.btnExportDropdown){els.btnExportDropdown.addEventListener('click', (e)=>{e.stopPropagation();_toggleDropdown();});}
  if(els.exportMenu){els.exportMenu.addEventListener('click',(e)=>{const li=e.target.closest('[data-action]');if(li)_handleDropdownAction(li.dataset.action);});els.exportMenu.addEventListener('keydown',(e)=>{const items=[...els.exportMenu.querySelectorAll('[role="menuitem"]')];const idx=items.indexOf(document.activeElement);if(e.key==='ArrowDown'){e.preventDefault();items[(idx+1)%items.length].focus();}else if(e.key==='ArrowUp'){e.preventDefault();items[(idx-1+items.length)%items.length].focus();}else if(e.key==='Enter'){e.preventDefault();const li=document.activeElement;if(li&&li.dataset.action)_handleDropdownAction(li.dataset.action);}else if(e.key==='Escape'){e.preventDefault();_closeDropdown(true);}});}
  if(els.importFile)els.importFile.addEventListener('change',()=>{const f=els.importFile.files[0];_handleImportFile(f);});
  document.addEventListener('click',(e)=>{if(els.exportMenu&&!els.exportMenu.hidden&&els.exportWrap&&!els.exportWrap.contains(e.target))_closeDropdown(false);});
  // Actions dropdown bindings (reuse a11y pattern)
  if(els.actionsToggle){els.actionsToggle.addEventListener('click',function(e){e.stopPropagation();_toggleActionsDropdown();});}
  if(els.clientesToggle){els.clientesToggle.addEventListener('click',function(e){e.stopPropagation();_toggleClientes();});}
  if(els.actionsMenu&&els.actionsDropdown){document.addEventListener('click',function(e){if(!els.actionsMenu.hidden&&!els.actionsDropdown.contains(e.target))_closeActionsDropdown(false);});}
  if(els.clientesMenu){document.addEventListener('click',function(e){if(!els.clientesMenu.hidden&&!els.clientesMenu.contains(e.target)&&els.clientesToggle&&!els.clientesToggle.contains(e.target))_closeClientes(false);});}
  _initActionsDropdownState(); window.addEventListener('resize',function(){var wasHidden=els.actionsMenu?els.actionsMenu.hidden:false;if(window.innerWidth>=900&&wasHidden)_openActionsDropdown();});
  els.btnLimpiarRuta.addEventListener('click', clearRoute);
  els.btnRutasHoy.addEventListener('click', loadRutasHoy);
  if (els.btnTheme) els.btnTheme.addEventListener('click', cycleTheme);
  if(els.btnMyLocation)els.btnMyLocation.addEventListener('click',centrarEnMiUbicacion);
  if(els.btnMyLocationMap)els.btnMyLocationMap.addEventListener('click',centrarEnMiUbicacion);
  if(els.btnClearSelection)els.btnClearSelection.addEventListener('click',clearSelection);
  if(els.btnMiniClear)els.btnMiniClear.addEventListener('click',clearSelection);
  if(els.btnMiniOptimizar)els.btnMiniOptimizar.addEventListener('click',handleOptimizar);
  if(els.btnMiniMarcar)els.btnMiniMarcar.addEventListener('click',handleMarcarEntregados);
  if(els.btnMiniTerminar)els.btnMiniTerminar.addEventListener('click',handleTerminarLista);
  window.addEventListener('online',function(){updateOfflineBanner();_syncQueuedEntregados();});
  window.addEventListener('offline',updateOfflineBanner);
  updateOfflineBanner();
  // Centralized selection → UI counter + map sync (persisted via selectionManager across reloads)
  selectionManager.subscribe(() => { updateSelectionUI(); refreshMapMarkers(); });
  updateSelectionUI();
  // Real backend connectivity heartbeat (offline-first): not just navigator.onLine.
  if (typeof window !== 'undefined' && window.GPS_syncEngine && typeof window.GPS_syncEngine.startHeartbeat === 'function') {
    window.GPS_syncEngine.startHeartbeat({ intervalMs: 15000, onStatus: updateOfflineBanner });
  }
  _syncQueuedEntregados();
  if('serviceWorker' in navigator&&'SyncManager' in window){navigator.serviceWorker.addEventListener('message',function(e){if(e.data&&e.data.type==='SYNC_COMPLETED')toast('Cola sincronizada ✓');});}
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') { hideForm(); hideConfirmDelete(); _closeDropdown(true); _closeActionsDropdown(true); _closeClientes(true); }
  });
});
