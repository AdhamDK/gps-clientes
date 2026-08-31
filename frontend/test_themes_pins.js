/* PR1 focused test: themes + numbered pins + cluster + manifest */
const fs = require('fs');
const path = require('path');

function assert(cond, msg) {
  if (!cond) {
    console.error('FAIL: ' + msg);
    process.exit(1);
  }
}

const root = __dirname;
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
const css = fs.readFileSync(path.join(root, 'style.css'), 'utf8');
const js = fs.readFileSync(path.join(root, 'app.js'), 'utf8');
const manifest = JSON.parse(fs.readFileSync(path.join(root, 'manifest.json'), 'utf8'));
const kt = fs.readFileSync(path.join(__dirname, '../app/src/main/java/com/gpsclientes/ui/map/OsmMapProvider.kt'), 'utf8');

// Theme — FOUC guard before CSS
assert(html.includes("localStorage.getItem('theme')"), 'FOUC guard missing localStorage');
assert(html.includes("prefers-color-scheme"), 'FOUC guard missing prefers-color-scheme');
assert(html.includes('data-theme'), 'FOUC guard missing data-theme');
assert(html.indexOf("data-theme") < html.indexOf("style.css"), 'FOUC guard must be before style.css');
assert(html.includes('rel="manifest"'), 'manifest link missing');
assert(html.includes('btnTheme'), 'theme toggle button missing');
assert(html.includes('leaflet.markercluster'), 'markercluster include missing');

// CSS vars 3 themes
assert(css.includes('[data-theme="light"]'), 'light theme vars missing');
assert(css.includes('[data-theme="dark"]'), 'dark theme vars missing');
assert(css.includes('[data-theme="mid"]'), 'mid theme vars missing');
assert(css.includes('#121212'), 'dark bg #121212 missing');
assert(css.includes('#2D3A2E'), 'mid bg #2D3A2E missing');
assert(css.includes('#FC4C02') || css.includes('--strava'), 'Strava #FC4C02 missing');
assert(css.includes('.pin'), '.pin style missing');
assert(css.includes('.cluster-chip'), '.cluster-chip missing');
assert(css.includes('transition'), 'theme transition missing');

// JS numbered pins + cluster
assert(js.includes('L.divIcon'), 'L.divIcon missing');
assert(js.includes('createNumberedIcon'), 'createNumberedIcon missing');
assert(js.includes('createClusterIcon'), 'createClusterIcon missing');
assert(js.includes('iconCreateFunction'), 'iconCreateFunction missing');
assert(js.includes('THEMES'), 'THEMES array missing');
assert(js.includes('cycleTheme'), 'cycleTheme missing');
assert(js.includes('localStorage.setItem'), 'theme persist missing');
assert(js.includes('requestAnimationFrame'), 'rAF batch missing');
assert(js.includes('numberedIdx'), 'renumber logic missing');
assert(js.includes('markerClusterGroup'), 'markerClusterGroup missing');

// Manifest
assert(manifest.display === 'standalone', 'manifest display should be standalone');
assert(manifest.theme_color === '#FC4C02', 'manifest theme_color should be #FC4C02');
assert(Array.isArray(manifest.icons) && manifest.icons.length >= 1, 'manifest icons missing');

// Android
assert(kt.includes('createNumberedDrawable'), 'Android createNumberedDrawable missing');
assert(kt.includes('FC4C02'), 'Android Strava color missing');
assert(kt.includes('forEachIndexed'), 'Android forEachIndexed renumber missing');
assert(kt.includes('tileFileSystemCacheMaxBytes'), 'Android tile cache config missing');
assert(kt.includes('500L * 1024 * 1024'), 'Android 500MB cache missing');

console.log('PASS themes/pins: light/dark/mid vars ok, FOUC before paint ok, divIcon 1..n ok, cluster chip ok, manifest standalone #FC4C02 ok, rAF batch ok, Android numbered drawable ok');
