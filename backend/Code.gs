// Code.gs - GPS_CLIENTES - Backend Google Apps Script
// Copiar y pegar completo en https://script.google.com -> Nuevo proyecto -> Code.gs -> Guardar -> Implementar como App Web

const SHEET_NAME = 'Clientes';
const HEADERS = ['id','nombre','rif_cedula','direccion','latitud','longitud','telefono','updated_at','sync_status','deleted'];

const RUTAS_HOY_SHEET = 'RutasHoy';
const RUTAS_HOY_HEADERS = ['id','fecha','cliente_id','orden','entregado','delivered_at','sync_status','created_at'];

const SYNCLOG_SHEET = 'SyncLog';
const SYNCLOG_HEADERS = ['id','timestamp','action','status','details'];

// --- CORS helper ---
function addCorsHeaders(output) {
  // ContentService no deja setHeader directo en doGet/doPost, usamos HtmlService para OPTIONS
  // Para GET/POST, Apps Script ignora headers custom en ContentService, pero el navegador lo permite si la Web App esta en "Cualquiera" y usamos JSONP o fetch con mode: no-cors
  // Truco: devolver JSON con MIME JSON y el cliente hace fetch con mode: 'cors' - Apps Script en modo "Cualquiera" ya manda Access-Control-Allow-Origin: *
  return output;
}

function doOptions(e) {
  // Para preflight CORS
  const output = ContentService.createTextOutput('');
  output.setMimeType(ContentService.MimeType.TEXT);
  // No hay forma directa de setHeader en ContentService, pero al publicar como "Cualquiera" el preflight pasa
  return output;
}

// --- Helper JSON con CORS ---
function jsonResponse(obj, status) {
  const output = ContentService.createTextOutput(JSON.stringify(obj));
  output.setMimeType(ContentService.MimeType.JSON);
  // Nota: Apps Script Web App en modo "Cualquiera" ya envia Access-Control-Allow-Origin: *
  // Para habilitar CORS explicito, publica como:
  // Ejecutar como: Yo | Quien tiene acceso: Cualquiera (Anyone, even anonymous)
  // Si necesitas OPTIONS, anade doOptions y en fetch usa mode: 'cors'
  return output;
}

function withCors(obj) {
  var out = ContentService.createTextOutput(JSON.stringify(obj));
  out.setMimeType(ContentService.MimeType.JSON);
  try {
    // Apps Script ContentService does not expose setHeaders in all runtimes; guard.
    if (out.setHeaders) {
      out.setHeaders({
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'GET,POST,PATCH,DELETE,OPTIONS',
        'Access-Control-Allow-Headers': 'Content-Type',
        'Access-Control-Max-Age': '86400'
      });
    }
  } catch (e) {}
  return out;
}

// --- Route dispatcher (netlify-gas-frontend) ---
function routeRequest(params) {
  try {
    var action = (params && params.action) ? String(params.action) : '';
    // Normalize: rutas_hoy, rutasHoy, entregado variations
    if (!action) {
      // No action => legacy clientes sync behavior: decide by method presence
      // For GET with lastSync -> clientes list; for POST with clientes -> sync
      // Default to clientes for backward compat when called via old doGet/doPost without action
      action = 'clientes';
    }
    var copy = {};
    if (params) { for (var k in params) if (params.hasOwnProperty(k) && k !== 'action') copy[k] = params[k]; }
    var handlers = {
      'clientes': function(p) { return handleClientes(p); },
      'sync': function(p) { return handleSync(p); },
      'rutas_hoy': function(p) { return handleRutasHoy(p); },
      'rutasHoy': function(p) { return handleRutasHoy(p); },
      'rutas/hoy': function(p) { return handleRutasHoy(p); },
      'entregado': function(p) { return handleEntregado(p); },
      'import': function(p) { return handleImport(p); },
      'export': function(p) { return handleExport(p); }
    };
    var handler = handlers[action] || handlers[action.toLowerCase()];
    if (!handler) {
      return withCors({error: 'Unknown action: ' + action, code: 'unknown_action'});
    }
    // Handlers return plain object; wrap with CORS
    var result = handler(copy);
    // If handler already returned ContentService output, pass through
    if (result && typeof result.getContent === 'function') return result;
    return withCors(result);
  } catch (err) {
    return withCors({error: err.toString(), code: 'internal'});
  }
}

// New dispatcher entrypoints - preserve old behavior via handleClientes/handleSync when no action routing needed
function doGet(e) {
  try {
    var params = (e && e.parameter) ? e.parameter : {};
    // If action param present, route via dispatcher
    if (params.action) {
      return routeRequest(params);
    }
    // No action: if query contains fecha -> assume rutas_hoy route (for backward/manual)
    // else delegate to legacy clientes GET (lastSync filter)
    // Keep legacy inline for direct sheet read without action wrapper
    return routeRequest(params);
  } catch (err) {
    return withCors({error: err.toString(), code: 'internal'});
  }
}

function doPost(e) {
  try {
    var params = {};
    var body = {};
    if (e && e.parameter) { for (var k in e.parameter) params[k] = e.parameter[k]; }
    if (e && e.postData && e.postData.contents) {
      try { body = JSON.parse(e.postData.contents); } catch (parseErr) { body = {}; }
      // Merge body fields into params for routing (action may be in body)
      for (var bk in body) if (body.hasOwnProperty(bk)) params[bk] = body[bk];
      // Keep raw body for handlers that expect clientes array
      params._body = body;
      params._rawContents = e.postData.contents;
    }
    if (params.action) {
      return routeRequest(params);
    }
    // No action: legacy POST = clientes upsert
    return routeRequest(params);
  } catch (err) {
    return withCors({error: err.toString(), code: 'internal'});
  }
}

// --- Handlers extracted from legacy doGet/doPost, plus new RutasHoy ---

function handleClientes(params) {
  // Supports: limit, offset, search (q), lastSync filtering
  try {
    var lastSync = params.lastSync || params.last_sync || '1970-01-01T00:00:00Z';
    var lastSyncDate = new Date(lastSync);
    var limitRaw = params.limit != null ? parseInt(params.limit, 10) : null;
    var offsetRaw = params.offset != null ? parseInt(params.offset, 10) : 0;
    var search = params.search || params.q || '';

    var ss = SpreadsheetApp.getActiveSpreadsheet();
    if (!ss) {
      return {error: 'No hay Spreadsheet vinculado. Crea este script desde tu Sheet: Extensiones > Apps Script, no como proyecto standalone. O pon SPREADSHEET_ID en el codigo.', code: 'no_sheet'};
    }
    var sheet = ss.getSheetByName(SHEET_NAME);
    if (!sheet) {
      sheet = ss.insertSheet(SHEET_NAME);
      sheet.appendRow(HEADERS);
      return {data: [], total: 0, clientes: []};
    }
    var data = sheet.getDataRange().getValues();
    if (data.length < 2) {
      return {data: [], total: 0, clientes: []};
    }
    var headerRow = data[0];
    var idx = {};
    HEADERS.forEach(function(h) { idx[h] = headerRow.indexOf(h); });
    var updatedAtIdx = idx['updated_at'] !== -1 ? idx['updated_at'] : 7;
    var clientes = [];
    for (var i = 1; i < data.length; i++) {
      var row = data[i];
      var updatedAtStr = row[updatedAtIdx];
      if (!updatedAtStr) continue;
      var rowDate;
      try { rowDate = new Date(updatedAtStr); } catch (err2) { continue; }
      if (rowDate > lastSyncDate) {
        var obj = {};
        HEADERS.forEach(function(h) {
          var colIdx = idx[h] !== -1 ? idx[h] : -1;
          if (colIdx !== -1) obj[h] = row[colIdx];
        });
        obj.latitud = parseFloat(obj.latitud) || 0;
        obj.longitud = parseFloat(obj.longitud) || 0;
        obj.sync_status = parseInt(obj.sync_status) || 0;
        obj.deleted = parseInt(obj.deleted) || 0;
        clientes.push(obj);
      }
    }
    // Optional search filter (NFD-insensitive)
    if (search) {
      var normSearch = String(search).normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
      clientes = clientes.filter(function(c) {
        var hay = String(c.nombre || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
        return hay.indexOf(normSearch) !== -1;
      });
    }
    var total = clientes.length;
    if (limitRaw != null && !isNaN(limitRaw)) {
      if (limitRaw > 5000) return {error: 'limit exceeds maximum 5000', code: 'invalid_limit'};
      var off = isNaN(offsetRaw) ? 0 : offsetRaw;
      clientes = clientes.slice(off, off + limitRaw);
    }
    // Return both shapes for compat: {data, total} and {clientes}
    return {data: clientes, total: total, limit: limitRaw, offset: offsetRaw, clientes: clientes};
  } catch (err) {
    return {error: err.toString(), code: 'internal'};
  }
}

function handleSync(params) {
  // Expects params.clientes or params._body as array
  try {
    var ss = SpreadsheetApp.getActiveSpreadsheet();
    if (!ss) {
      return {error: 'No hay Spreadsheet vinculado. Crea este script desde tu Sheet: Extensiones > Apps Script.', code: 'no_sheet'};
    }
    var sheet = ss.getSheetByName(SHEET_NAME);
    if (!sheet) {
      sheet = ss.insertSheet(SHEET_NAME);
      sheet.appendRow(HEADERS);
    }
    if (sheet.getLastRow() === 0) {
      sheet.appendRow(HEADERS);
    } else {
      var firstRow = sheet.getRange(1, 1, 1, HEADERS.length).getValues()[0];
      var needsHeader = false;
      for (var ii = 0; ii < HEADERS.length; ii++) if (firstRow[ii] !== HEADERS[ii]) needsHeader = true;
      if (needsHeader) sheet.getRange(1, 1, 1, HEADERS.length).setValues([HEADERS]);
    }
    var payload = params._body || params;
    var clientes = Array.isArray(payload) ? payload : (payload.clientes || payload.clients || null);
    if (!clientes) {
      // Try to parse raw if still not array
      if (params._rawContents) {
        try { var parsed = JSON.parse(params._rawContents); clientes = Array.isArray(parsed) ? parsed : (parsed.clientes || [parsed]); } catch (e3) {}
      }
    }
    if (!Array.isArray(clientes) || clientes.length === 0) {
      // No clients to sync - return success with 0 (allow empty push per spec)
      if (params.clientes && Array.isArray(params.clientes) && params.clientes.length === 0) {
        return {status: 'success', synced: 0, updated: 0, inserted: 0, total: 0};
      }
      return {error: 'Se esperaba array de clientes', code: 'invalid_payload'};
    }
    var data = sheet.getDataRange().getValues();
    var headerRow2 = data[0];
    var idx2 = {};
    HEADERS.forEach(function(h) { idx2[h] = headerRow2.indexOf(h); });
    var idIdx = idx2['id'] !== -1 ? idx2['id'] : 0;
    var uuidToRow = {};
    for (var r = 1; r < data.length; r++) {
      var uuid = String(data[r][idIdx] || '').trim();
      if (uuid) uuidToRow[uuid] = r + 1;
    }
    var updated = 0;
    var inserted = 0;
    clientes.forEach(function(c) {
      var uuid2 = String(c.id || '').trim();
      if (!uuid2) return;
      var rowValues = HEADERS.map(function(h) {
        var v = c[h];
        if (h === 'updated_at' && !v) v = new Date().toISOString();
        if (h === 'sync_status' && v === undefined) v = 1;
        if (h === 'deleted' && v === undefined) v = 0;
        return v !== undefined ? v : '';
      });
      var existingRow = uuidToRow[uuid2];
      if (existingRow) {
        sheet.getRange(existingRow, 1, 1, HEADERS.length).setValues([rowValues]);
        updated++;
      } else {
        sheet.appendRow(rowValues);
        uuidToRow[uuid2] = sheet.getLastRow();
        inserted++;
      }
    });
    return {status: 'success', synced: updated + inserted, updated: updated, inserted: inserted, total: clientes.length};
  } catch (err) {
    return {error: err.toString(), code: 'internal'};
  }
}

function ensureRutasHoySheet(ss) {
  var sheet = ss.getSheetByName(RUTAS_HOY_SHEET);
  if (!sheet) {
    sheet = ss.insertSheet(RUTAS_HOY_SHEET);
    sheet.appendRow(RUTAS_HOY_HEADERS);
  }
  if (sheet.getLastRow() === 0) {
    sheet.appendRow(RUTAS_HOY_HEADERS);
  } else {
    var firstRow = sheet.getRange(1, 1, 1, RUTAS_HOY_HEADERS.length).getValues()[0];
    var needs = false;
    for (var i = 0; i < RUTAS_HOY_HEADERS.length; i++) if (firstRow[i] !== RUTAS_HOY_HEADERS[i]) needs = true;
    if (needs) sheet.getRange(1, 1, 1, RUTAS_HOY_HEADERS.length).setValues([RUTAS_HOY_HEADERS]);
  }
  return sheet;
}

function isValidFecha(str) {
  return /^\d{4}-\d{2}-\d{2}$/.test(str);
}

function todayFecha() {
  var d = new Date();
  var yyyy = d.getFullYear();
  var mm = ('0' + (d.getMonth() + 1)).slice(-2);
  var dd = ('0' + d.getDate()).slice(-2);
  return yyyy + '-' + mm + '-' + dd;
}

function handleRutasHoy(params) {
  try {
    var fecha = params.fecha || todayFecha();
    if (!isValidFecha(fecha)) {
      return {error: 'Invalid date format, use YYYY-MM-DD', code: 'invalid_date'};
    }
    var entregadoFilter = params.entregado;
    var ss = SpreadsheetApp.getActiveSpreadsheet();
    if (!ss) {
      return {error: 'No hay Spreadsheet vinculado.', code: 'no_sheet'};
    }
    var sheet = ensureRutasHoySheet(ss);
    var data = sheet.getDataRange().getValues();
    if (data.length < 2) {
      return {rutas: [], data: [], fecha: fecha};
    }
    var headerRow = data[0];
    var idx = {};
    RUTAS_HOY_HEADERS.forEach(function(h) { idx[h] = headerRow.indexOf(h); });
    var fechaIdx = idx['fecha'] !== -1 ? idx['fecha'] : 1;
    var clienteIdIdx = idx['cliente_id'] !== -1 ? idx['cliente_id'] : 2;
    var ordenIdx = idx['orden'] !== -1 ? idx['orden'] : 3;
    var entregadoIdx = idx['entregado'] !== -1 ? idx['entregado'] : 4;
    var deliveredAtIdx = idx['delivered_at'] !== -1 ? idx['delivered_at'] : 5;
    var rutas = [];
    for (var i = 1; i < data.length; i++) {
      var row = data[i];
      if (String(row[fechaIdx]) !== String(fecha)) continue;
      var entregadoVal = row[entregadoIdx];
      // filter by entregado if requested: ?entregado=false
      if (entregadoFilter != null && entregadoFilter !== '') {
        var want = String(entregadoFilter) === '1' || String(entregadoFilter).toLowerCase() === 'true' || String(entregadoFilter) === '0' || String(entregadoFilter).toLowerCase() === 'false';
        // explicit filter
        var isEntregado = (entregadoVal === true || String(entregadoVal) === '1' || String(entregadoVal).toLowerCase() === 'true');
        if (String(entregadoFilter) === 'false' || String(entregadoFilter) === '0') {
          if (isEntregado) continue;
        } else if (String(entregadoFilter) === 'true' || String(entregadoFilter) === '1') {
          if (!isEntregado) continue;
        }
      }
      rutas.push({
        id: row[idx['id']],
        fecha: row[fechaIdx],
        cliente_id: row[clienteIdIdx],
        orden: parseInt(row[ordenIdx], 10) || 0,
        entregado: (entregadoVal === true || String(entregadoVal) === '1' || String(entregadoVal).toLowerCase() === 'true'),
        delivered_at: row[deliveredAtIdx],
        sync_status: row[idx['sync_status']],
        created_at: row[idx['created_at']]
      });
    }
    rutas.sort(function(a, b) { return a.orden - b.orden; });
    return {rutas: rutas, data: rutas, fecha: fecha};
  } catch (err) {
    return {error: err.toString(), code: 'internal'};
  }
}

function handleEntregado(params) {
  try {
    var fecha = params.fecha || todayFecha();
    if (!isValidFecha(fecha)) {
      return {error: 'Invalid date format, use YYYY-MM-DD', code: 'invalid_date'};
    }
    var ids = params.cliente_ids || params.clienteIds || params.ids || [];
    if (!Array.isArray(ids) || ids.length === 0) {
      // also accept single cliente_id
      if (params.cliente_id) ids = [String(params.cliente_id)];
      else return {error: 'cliente_ids required', code: 'invalid_payload'};
    }
    ids = ids.map(function(x) { return String(x); });
    var ss = SpreadsheetApp.getActiveSpreadsheet();
    if (!ss) {
      return {error: 'No hay Spreadsheet vinculado.', code: 'no_sheet'};
    }
    var sheet = ensureRutasHoySheet(ss);
    var data = sheet.getDataRange().getValues();
    if (data.length < 2) {
      return {updated: 0, fecha: fecha};
    }
    var headerRow = data[0];
    var idx = {};
    RUTAS_HOY_HEADERS.forEach(function(h) { idx[h] = headerRow.indexOf(h); });
    var fechaIdx = idx['fecha'] !== -1 ? idx['fecha'] : 1;
    var clienteIdIdx = idx['cliente_id'] !== -1 ? idx['cliente_id'] : 2;
    var entregadoIdx = idx['entregado'] !== -1 ? idx['entregado'] : 4;
    var deliveredAtIdx = idx['delivered_at'] !== -1 ? idx['delivered_at'] : 5;
    var idSet = {};
    ids.forEach(function(id) { idSet[String(id)] = true; });
    var nowIso = new Date().toISOString();
    var updated = 0;
    for (var i = 1; i < data.length; i++) {
      var row = data[i];
      if (String(row[fechaIdx]) !== String(fecha)) continue;
      if (!idSet[String(row[clienteIdIdx])]) continue;
      var entregadoVal = row[entregadoIdx];
      var already = (entregadoVal === true || String(entregadoVal) === '1' || String(entregadoVal).toLowerCase() === 'true');
      if (already) {
        // idempotent: do not overwrite delivered_at
        continue;
      }
      sheet.getRange(i + 1, entregadoIdx + 1).setValue(true);
      // only set delivered_at if empty
      var curDelivered = row[deliveredAtIdx];
      if (!curDelivered) {
        sheet.getRange(i + 1, deliveredAtIdx + 1).setValue(nowIso);
      }
      updated++;
    }
    return {updated: updated, fecha: fecha};
  } catch (err) {
    return {error: err.toString(), code: 'internal'};
  }
}

function handleImport(params) {
  return {error: 'Import via GAS not implemented - use local Docker', code: 'not_implemented'};
}

function handleExport(params) {
  return {error: 'Export via GAS not implemented - use local Docker', code: 'not_implemented'};
}

// --- Test local (opcional, ejecutar desde editor) ---
function test_doGet() {
  Logger.log(doGet({parameter:{lastSync:'1970-01-01T00:00:00Z'}}).getContent());
}
function test_doPost() {
  var fake = {postData:{contents: JSON.stringify([{id: Utilities.getUuid(), nombre:'Test', latitud:8.61, longitud:-71.65, updated_at:new Date().toISOString(), sync_status:0, deleted:0}])}};
  Logger.log(doPost(fake).getContent());
}
function test_rutasHoy() {
  Logger.log(handleRutasHoy({fecha: todayFecha()}));
}
function test_entregado() {
  Logger.log(handleEntregado({fecha: todayFecha(), cliente_ids: ['test-id']}));
}
