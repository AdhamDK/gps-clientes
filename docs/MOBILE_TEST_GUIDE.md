# Mobile Test Guide — GPS_CLIENTES (real device)

Source: post-archive hardening 2026-08-23 (gps-clientes-app id25). APK requires JDK17+SDK34 + Gradle 8.6; this host has only JDK8 JRE + stub wrapper — build must run on CI or a dev machine with SDK.

## 0) Build APK (CI or local with SDK)

Prereqs: Temurin JDK17, Android SDK 34 (build-tools 34.0.0), `MAPS_API_KEY` if map tab needed.

```powershell
# 1. Install JDK17, set env
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-17.0.11.9-hotspot"
java -version   # -> 17.x
javac -version  # -> 17.x

# 2. Install Android SDK (cmdline-tools) or via Android Studio to C:\Android\Sdk
#    Ensure: sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"

# 3. Generate real wrapper (replaces stub that exits 1)
gradle wrapper --gradle-version 8.6
Test-Path gradle/wrapper/gradle-wrapper.jar  # -> True
./gradlew --version  # -> Gradle 8.6

# 4. Configure local.properties
#   sdk.dir=C:\Android\Sdk
#   MAPS_API_KEY=YOUR_GOOGLE_MAPS_KEY   # leave YOUR_API_KEY_HERE to test offline fallback

# 5. Build
./gradlew testDebugUnitTest            # 29 unit PASS offline
./gradlew :app:assembleDebug           # -> app/build/outputs/apk/debug/app-debug.apk
# optional release:
# ./gradlew :app:assembleRelease       # versionCode 2 versionName 1.1.0, minify=true

# 6. Verify APK
adb --version
ls app/build/outputs/apk/debug/app-debug.apk
```

CI artifact: upload `app-debug.apk` from `assembleDebug` job (see verification workflow in verify-report id22 rev7).

## 1) Enable device for install

On phone: Settings → About phone → tap Build number 7× → Developer options → enable USB debugging.
Connect via USB, authorize RSA prompt.

```powershell
adb devices                      # -> device ID + "device"
adb shell getprop ro.build.version.sdk  # -> 24+ required (minSdk 24)
```

## 2) Install APK

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
# -r = replace, add -d if downgrade
adb shell pm list packages | findstr gpsclientes   # -> package:com.gpsclientes
adb shell dumpsys package com.gpsclientes | findstr version  # versionCode 2
```

Manual alternative: copy APK to device and open via file manager → Allow install from unknown sources.

## 3) Grant permissions & test permission flows

Launch app, test each rationale path:

- Location: top map permission handler → Allow → handler onGranted path; Deny → showRationale; Deny + Don't ask again → showPermanentlyDenied with OK + Manual entry (coords still savable with referenciaManual).
- Notifications (Android 13+): Tiramisu POST_NOTIFICATIONS → Allow/Deny; Deny degrades to foreground-only geocoding.

```powershell
# Check permissions:
adb shell dumpsys package com.gpsclientes | findstr permission
adb shell pm grant com.gpsclientes android.permission.ACCESS_FINE_LOCATION
adb shell pm revoke com.gpsclientes android.permission.ACCESS_FINE_LOCATION
```

## 4) Import Clientes_TOM_KEVIN.xlsx — verify 409 rows

```powershell
adb push Clientes_TOM_KEVIN.xlsx /sdcard/Download/Clientes_TOM_KEVIN.xlsx
```
In app: Import → pick `/Download/Clientes_TOM_KEVIN.xlsx` → expect:
- `inserted=409` (or 409-flagged split), `flagged=9`, `skippedInvalidRif=0` (RIFs are valid after dash-strip), `totalRows=409`.
- UTF-8 preserved: open a row where Direccion contains `Vigía` (3 such rows). Verify `direccionOriginalExcel` keeps accent.
- Re-import same file → 0 inserted (dedup by RIF + nombreNormalizado).

DB check via app or `adb shell run-as` (debuggable build):
```powershell
adb shell run-as com.gpsclientes ls databases/
# gps_clientes.db — 409 rows, indices on nombreNormalizado/rif/hasGpsFix
```

## 5) Map tab (ENABLE_MAP flag)

`app/build.gradle.kts` has `buildConfigField("boolean","ENABLE_MAP","false")` by default → Map tab hidden, `OfflineMapFallback` grid placeholder shows `pinsCount` and filters still work.
To enable map:

```powershell
# gradle.properties or env:
# ENABLE_MAP=true  + MAPS_API_KEY=real_key
./gradlew :app:assembleDebug -PENABLE_MAP=true -PMAPS_API_KEY=YOUR_KEY
```
- With key + online: GoogleMap with Clustering (409 pins), `EL_VIGIA` center 8.6167,-71.65 zoom 12, filter chips (Has GPS / Flagged #) toggle correctly.
- Offline or no key: OfflineMapFallback grid (Canvas 48dp), message + pin count, long-press still works.

## 6) Test pin drop, draggable, share, navigation

- Long-press map → pendingPin appears, preview shows `GeocodingResult.textoBreve` or `Sin direccion — lat,lng` fallback. Drag marker → preview updates live via `GeocodingRepository.resolve` (IO dispatcher, Tiramisu async branch).
- Tap Confirmar → ClienteForm prefilled with lat/lng + textoBreve/referenciaManual.
- Open any Cliente DetailSheet → Share → `ShareUtils.shareIntent` chooser with `text/plain` `label\nlat,lng https://maps.google.com/?q=lat,lng` (no geo: data on chooser).
- `ShareUtils.shareText` must contain both `https://maps.google.com/?q=` and `geo:`.
- Navigate button → `google.navigation:q=lat,lng` (Google) or `https://waze.com/ul?ll=lat,lng&navigate=yes` (Waze) + fallback `https://maps.google.com/?q=lat,lng` via `navigateFallbackIntent` / `canResolve` (queryIntentActivities on API 30+).

Manual check: airplane mode → share/navigate intents still produced (offline-safe), maps app may prompt to open without network.

## 7) Export Excel/PDF — paired RowModel

- Export → pick columns (DEFAULT_SELECTION) → choose destination via SAF or file path.
- Excel: `SXSSFWorkbook(100)` streaming window, header bold, auto-size capped, UTF-8 (Vigía), OOM deletes partial, rows = 409 via paginated `loadAllPaginated(100)`.
- PDF: `PdfDocument` A4 842×595, header repeats per page, colWidth computed, truncateForWidth with ellipsis, same RowModel + column order parity with Excel.

```powershell
adb shell run-as com.gpsclientes ls cache/  # temp SXSSF files cleaned via dispose()
adb pull /sdcard/Download/Clientes_export.xlsx .
# Open in Excel → verify 410 rows (header+409), Vigía accent intact
```

## 8) Search & duplicate helper

Search bar → type `Vigía` → normalized `vigia` matches regardless of accent/case. Type `Soneibys Guillen` → fuzzy match `Soneibis` (Levenshtein 1, threshold 2). Type `V267230346` → RIF exact uppercased. Flagged rows deprioritized in `rankForSearch`.

## 9) Offline fallback contract

Enable airplane mode, kill/relaunch app → list + map fallback remain functional (Room is single source of truth). Pins visible, filters work, Share/Navigate produce intents, Export works offline.

## 10) Known issues & fixes applied (this hardening)

- PermissionHandler ComponentActivity cast → now `findActivity()` safe unwrapping.
- ShareUtils chooser geo data + `resolveActivity` → now no data on chooser, `queryIntentActivities` with Tiramisu flags.
- DatabaseModule dao/usecase not singleton → now `@Singleton` on both.
- GoogleMapProvider draggable not wired → now `onInfoWindowClick` + `showInfoWindow` and documented `MarkerState.position` pattern.

## Troubleshooting

- `Gradle wrapper stub - SDK not installed` → generate jar: `gradle wrapper --gradle-version 8.6` after installing SDK/JDK17.
- `Unsupported Java version` / `requires JDK17` → set `JAVA_HOME` to Temurin17, not JDK8.
- `SDK location not found` → check `local.properties` `sdk.dir` and `sdkmanager --list`.

## Checklist before release

- [ ] APK installs via `adb install -r`, versionCode 2/1.1.0, label GPS Clientes
- [ ] Import 409 + 9 flagged + Vigía preserved
- [ ] Long-press → draggable → preview → confirmar → new cliente with hasGpsFix true
- [ ] Filters hasGpsFix / isFlaggedImport cycle null→true→false
- [ ] Share → chooser shows, text contains https + geo
- [ ] Navigate → Google/Waze intents resolve (or fallback)
- [ ] Export Excel & PDF → 409 rows, headers repeat, Vigía intact, partial deleted on OOM
- [ ] Offline fallback grid shows when ENABLE_MAP=false or no key
- [ ] Permission rationale → permanently denied → manual coords still saves
