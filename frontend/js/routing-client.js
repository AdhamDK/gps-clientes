// routing-client.js - Client-side nearest-neighbor fallback for route optimization
// Deterministic tie-break via id string compare, excludes has_gps_fix==false

export function haversine(lat1, lng1, lat2, lng2) {
  var R = 6371e3;
  var phi1 = lat1 * Math.PI / 180;
  var phi2 = lat2 * Math.PI / 180;
  var dphi = (lat2 - lat1) * Math.PI / 180;
  var dlambda = (lng2 - lng1) * Math.PI / 180;
  var a = Math.sin(dphi / 2) * Math.sin(dphi / 2) + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dlambda / 2) * Math.sin(dlambda / 2);
  return 2 * R * Math.asin(Math.sqrt(a));
}

function routeDist(route, i, j, clientsMap, swapped) {
  // distance of segment i-1 -> i and j -> j+1 vs swapped
  // Simplified: total delta if reversing i..j
  if (!swapped) {
    var a = clientsMap[route[i - 1]];
    var b = clientsMap[route[i]];
    var c = clientsMap[route[j]];
    var d = clientsMap[route[j + 1]];
    var dist = 0;
    if (a && b) dist += haversine(a.lat, a.lng, b.lat, b.lng);
    if (c && d) dist += haversine(c.lat, c.lng, d.lat, d.lng);
    return dist;
  } else {
    var a2 = clientsMap[route[i - 1]];
    var b2 = clientsMap[route[j]];
    var c2 = clientsMap[route[i]];
    var d2 = clientsMap[route[j + 1]];
    var dist2 = 0;
    if (a2 && b2) dist2 += haversine(a2.lat, a2.lng, b2.lat, b2.lng);
    if (c2 && d2) dist2 += haversine(c2.lat, c2.lng, d2.lat, d2.lng);
    return dist2;
  }
}

function reverse(route, i, j) {
  while (i < j) {
    var tmp = route[i];
    route[i] = route[j];
    route[j] = tmp;
    i++; j--;
  }
}

export function twoOpt(route, clientsMap) {
  if (!route || route.length < 4) return route.slice();
  var out = route.slice();
  var improved = true;
  var maxIter = 8;
  var iter = 0;
  while (improved && iter < maxIter) {
    improved = false;
    iter++;
    for (var i = 1; i < out.length - 2; i++) {
      for (var j = i + 1; j < out.length - 1; j++) {
        // j+1 must exist for delta calc; allow j == out.length-1 with only one edge considered
        var cur = routeDist(out, i, j, clientsMap, false);
        var nxt = routeDist(out, i, j, clientsMap, true);
        if (nxt < cur - 1e-6) {
          reverse(out, i, j);
          improved = true;
        }
      }
    }
  }
  return out;
}

export function nearestNeighbor(clientIds, clientsMap) {
  if (!Array.isArray(clientIds) || clientIds.length === 0) return [];
  // Filter out clients without valid GPS fix
  var validIds = clientIds.filter(function(id) {
    var c = clientsMap ? clientsMap[id] : null;
    if (!c) return false;
    if (c.has_gps_fix === false) return false;
    var lat = c.lat != null ? c.lat : c.latitud;
    var lng = c.lng != null ? c.lng : c.longitud;
    if (lat == null || lng == null) return false;
    if (Number(lat) === 0 && Number(lng) === 0) return false;
    return true;
  });
  if (validIds.length === 0) return [];
  if (validIds.length === 1) return validIds.slice();

  var unvisited = new Set(validIds);
  var route = [];
  var current = validIds[0];
  route.push(current);
  unvisited.delete(current);

  while (unvisited.size > 0) {
    var nearest = null;
    var minDist = Infinity;
    var cur = clientsMap[current];
    var clat = cur.lat != null ? cur.lat : cur.latitud;
    var clng = cur.lng != null ? cur.lng : cur.longitud;
    var candidates = Array.from(unvisited).sort(); // deterministic iteration order
    for (var k = 0; k < candidates.length; k++) {
      var id = candidates[k];
      var nxt = clientsMap[id];
      var nlat = nxt.lat != null ? nxt.lat : nxt.latitud;
      var nlng = nxt.lng != null ? nxt.lng : nxt.longitud;
      var d = haversine(clat, clng, nlat, nlng);
      if (d < minDist - 1e-9) {
        minDist = d;
        nearest = id;
      } else if (Math.abs(d - minDist) < 1e-9) {
        // tie-break via id string compare (deterministic)
        if (String(id) < String(nearest)) nearest = id;
      }
    }
    route.push(nearest);
    unvisited.delete(nearest);
    current = nearest;
  }
  return twoOpt(route, clientsMap);
}

// CommonJS compat for Node tests
try {
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = { nearestNeighbor: nearestNeighbor, haversine: haversine, twoOpt: twoOpt };
  }
} catch (e) {}

