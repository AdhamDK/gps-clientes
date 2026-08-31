# Design: apk-webview-wrapper — 0 Divergencia WebView

## Objetivo
Envolver `frontend/` dentro del APK vía WebView para que **el mismo HTML/JS** corra en web y en móvil, eliminando drift Compose vs Leaflet.

## Enfoque Elegido: WebViewAssetLoader
- **WebViewAssetLoader** con `https://appassets.androidplatform.net/assets/www/index.html` usando `androidx.webkit:webkit:1.9.0`
- **Gradle Copy** `frontend/` → `app/src/main/assets/www` (excluye `sw.js` para evitar precache clash)
- **WebViewScreen** con `javaScriptEnabled=true`, `domStorageEnabled=true`, `allowFileAccess=false`
- **Flag `BuildConfig.ENABLE_WEBVIEW` default false** — mantiene Compose intacto, rollback seguro sin migración DB
- **API origin detection** en `frontend/app.js` para `file://`, `appassets`, `capacitor://` → fallback a `http://10.0.2.2:8000` o `https://appassets...`
- **Bridges opcionales** `Android.getGpsFix()` → `FusedLocationRepository` y `getTheme/setTheme` → `DataStore`, ambos con fallback a `navigator.geolocation` y `localStorage`

## Decisiones Clave (Tradeoffs)
| Decisión | Opción Elegida | Alternativa Rechazada | Por qué |
|---|---|---|---|
| Origin | `https://appassets` via AssetLoader | `file:///android_asset` | CORS y SW incompatibles en file:// |
| Wrapper | WebViewAssetLoader | Capacitor | 0 npm overhead, más rápido |
| Flag | `ENABLE_WEBVIEW` | Reemplazo total | Rollback sin migración |
| Copy | Gradle Copy | npm build | Zero toolchain |
| Bridge GPS | Opcional `@JavascriptInterface` | Obligatorio | Fallback a browser geolocation |

## Archivos Afectados
- `app/build.gradle.kts` → add `androidx.webkit:webkit:1.9.0`
- `app/src/main/AndroidManifest.xml` → `usesCleartextTraffic` + `network_security_config.xml` para `192.168.0.103` y `10.0.2.2`
- `app/src/main/java/com/gpsclientes/ui/WebViewScreen.kt` (nuevo) + `MainActivity.kt` flag routing
- `app/src/main/assets/www/*` (via Gradle Copy)
- `frontend/app.js` → `API` origin sniff para `appassets`
- `frontend/sw.js` → scope a `appassets` o deshabilitar dentro de WebView

## Testing
- Unit: `WebViewScreen` con Robolectric + `WebViewAssetLoader` mock, `generateUUID`/`updated_at`
- Integration: `Fused` → `Android.getGpsFix()` JSON, `DataStore` theme sync via `evaluateJavascript`
- E2E: Playwright/Cypress con WebView `file://` vs `https://appassets` + `adb shell am start`

## Riesgos
- PK `Long` vs `UUID TEXT` requiere mapper si se expone Room directo — mitigado deferiendo DB bridge a HTTP v1
- `file://` SW no soportado — mitigado con AssetLoader https
- Theme flicker — mitigado con `DataStore` ↔ `localStorage` sync en `onPageFinished`
