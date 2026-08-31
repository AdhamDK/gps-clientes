// config.js - Frontend environment configuration for GAS + LOCAL split
export const CONFIG = {
  GAS_URL: (function() {
    try {
      // Priority 1: window.GAS_URL override (manual / E2E)
      if (typeof window !== 'undefined' && window.GAS_URL) return window.GAS_URL;
      // Priority 2: meta tag <meta name="gas-url" content="...">
      if (typeof document !== 'undefined') {
        var meta = document.querySelector('meta[name="gas-url"]');
        if (meta && meta.content) return meta.content;
      }
      // Priority 3: localStorage override
      if (typeof window !== 'undefined' && window.localStorage) {
        var ls = window.localStorage.getItem('GAS_URL');
        if (ls) {
          try { console.log('Using localStorage GAS_URL override'); } catch (e) {}
          return ls;
        }
      }
      // Priority 4: process env (Node tests)
      if (typeof process !== 'undefined' && process.env && process.env.GAS_URL) return process.env.GAS_URL;
    } catch (e) { console.error('[GPS] CONFIG GAS_URL resolve', e); }
    // Default placeholder - replace via Netlify env or localStorage
    return 'https://script.google.com/macros/s/AKfycbweLpc8AI2C4ouMY6AWrH6Voiemkoa4tlwc6qjyqeYIyIK1jGOvGdt_3YiNKKP6V6LsGA/exec';
  })(),
  LOCAL_API: (function() {
    try {
      if (typeof window !== 'undefined' && window.localStorage) {
        var l = window.localStorage.getItem('LOCAL_API');
        if (l) return l;
      }
      if (typeof process !== 'undefined' && process.env && process.env.LOCAL_API) return process.env.LOCAL_API;
    } catch (e) { console.error('[GPS] CONFIG LOCAL_API resolve', e); }
    return 'http://localhost:8000';
  })(),
  isLocalhost: function() {
    try {
      var h = (typeof location !== 'undefined' && location.hostname) ? location.hostname : '';
      return h === 'localhost' || h === '127.0.0.1' || h.startsWith('192.168.') || h === '10.0.2.2';
    } catch (e) { return false; }
  },
  isNetlify: function() {
    try {
      var h2 = (typeof location !== 'undefined' && location.hostname) ? location.hostname : '';
      return h2.includes('.netlify.app') || h2.endsWith('.netlify.app');
    } catch (e) { return false; }
  },
  USE_LOCAL_OPTIMIZATION: true
};

// Browser auto-expose for non-module usage
try { if (typeof window !== 'undefined') window.GPS_CONFIG = CONFIG; } catch (e) { console.error('[GPS]', e); }
