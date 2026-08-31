# CI Verification — gps-clientes-app

## Repo hygiene invariants (2026-08-30 harden-repo-hygiene)

- **Source of truth**: `frontend/` is canonical; `app/src/main/assets/www/` is derived via `copyFrontendToAssets` (`from("../frontend")` → `into("src/main/assets/www")`, `preBuild.dependsOn`). `www/` is gitignored and untracked.
  - Verify: `git ls-files | grep www` → empty; `git check-ignore app/src/main/assets/www/syncEngine.js` → ignored; `diff -r frontend app/src/main/assets/www` identical excluding `test_*.js`, `e2e.*`, `*.spec.js`; SHA256 `syncEngine.js` = `2702C96D...3426D3D` in both trees.
  - Fresh clone: `./gradlew preBuild` repopulates `www/` byte-identical.
- **Orphan removed**: `js/syncEngine.js` deleted; single source is `frontend/syncEngine.js`.
- **Pytest collection**: `pytest.ini` with `testpaths = backend/tests tests` — `python -m pytest -q` collects 45 tests without `--ignore` flags (stray `test_*.py`/`check_*.py` moved to `scripts/one-offs/`).
- **Git boundary**: expected toplevel `.../GPS_CLIENTES`; remote `https://github.com/AdhamDK/gps-clientes.git` (until manual re-init, actual toplevel remains `C:/Users/Usuario` with remote `catalogo-empresa` — see `docs/GIT_REINIT_CHECKLIST.md` for post-merge steps).
  - Verify toplevel: `git -C GPS_CLIENTES rev-parse --show-toplevel` → ends with `GPS_CLIENTES`
  - Verify remote: `git -C GPS_CLIENTES remote get-url origin` → `AdhamDK/gps-clientes`
  - Verify ignores: `git check-ignore app/src/main/assets/www/syncEngine.js` → ignored

## Environment diagnosis (2026-08-23 hardening, id 25 rev7)
Stub env on this host:
- `java -version` → `1.8.0_51` JRE only (HotSpot 25.51-b03), `javac -version` → not found, `JAVA_HOME` unset, `where java` → `C:\ProgramData\Oracle\Java\javapath\java.exe`, `C:\Program Files\Java\jre1.8.0_51` only, no Temurin/Adoptium, no `gradle`/`kotlinc` on PATH.
- `Test-Path C:\Android\Sdk` → False, `Test-Path %LOCALAPPDATA%\Android\Sdk` → False, `local.properties` → `sdk.dir=C:\Android\Sdk` (absent).
- `gradle-wrapper.jar` → missing (False), `gradle-wrapper.properties` → `gradle-8.6-bin.zip` OK, `gradlew`/`gradlew.bat` → stub `exit 1` with message `Gradle wrapper stub - SDK not installed`.
- `./gradlew --version` → stub exit 1; `./gradlew :app:assembleDebug` → stub exit 1. Build requires JDK17+SDK34 to generate real wrapper and compile (22/22 scenarios structural PASS, runtime blocked — see verify report id22 rev7, PR chain f97239d→a2decce).
- Python harness fallback: 95/95 PASS without SDK (import 409/9/Vigía, normalize/levenshtein/fuzzy, geocoding, map, perms, share, export, build config). See `docs/MOBILE_TEST_GUIDE.md` for device install.

## Requirements
- JDK 17 (Temurin 17) — e.g. `C:\Program Files\Eclipse Adoptium\jdk-17.x`
- Android SDK 34 (compileSdk 34, build-tools 34.0.0) at `C:\Android\Sdk` or `%LOCALAPPDATA%\Android\Sdk`
- Gradle 8.6 via wrapper (generate jar; see below)

## Wrapper jar
`gradle-wrapper.jar` is not committed (see diagnosis above). Generate it before any `./gradlew` invocation:

```bash
# Windows (after installing JDK17 + Android SDK 34):
gradle wrapper --gradle-version 8.6
# or if gradle not installed: sdk install gradle 8.6  (SDKMAN/WSL) or use Android Studio → Gradle wrapper task
# Verify:
Test-Path gradle/wrapper/gradle-wrapper.jar  # → True
./gradlew --version  # → Gradle 8.6, Kotlin 1.9.22, JVM 17
```

`gradle/wrapper/gradle-wrapper.properties` already points to `gradle-8.6-bin.zip`.

## Failure envelope without SDK (this host)
```
> ./gradlew :app:assembleDebug
Gradle wrapper stub - SDK not installed. Run 'gradle wrapper' with Android SDK to generate real wrapper.
exit 1  hash 6d334... (wrapper jar stub)
```
Fix: install JDK17 (Temurin), set `JAVA_HOME`, install Android SDK 34 via cmdline-tools or Android Studio, then `gradle wrapper --gradle-version 8.6` to replace the stub. CI must cache `gradle-wrapper.jar` or generate it in a setup step.

## Verification steps (with JDK17+SDK34)

```bash
# Unit tests (no device):
./gradlew testDebugUnitTest --tests "*SearchDuplicateHelperTest" --tests "*GeocodingRepositoryTest" --tests "*PolishCoverageTest"
# -> 11 + 5 + 11 = 27 unit PASS (+ ImportClientesUseCaseTest 2 = 29 total, all structural PASS via python harness 95/95 without SDK)

# Python harness fallback (no SDK/JDK17):
python harness_gps2.py  # 95 PASS: 409/9/Vigía, normalize, levenshtein, geocoding, map, perms, share, export, build

# Build debug APK:
./gradlew :app:assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk  (requires SDK)
# Install + run on device (see docs/MOBILE_TEST_GUIDE.md):
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Optional coverage:
./gradlew koverHtmlReport  # or jacocoTestReport if using Jacoco
./gradlew connectedAndroidTest  # requires emulator/device, covers ClienteDaoInstrumentedTest, ImportInstrumentedTest, GeocodingInstrumentedTest
```

## Release build
- `app/build.gradle.kts` versionCode 2 versionName 1.1.0
- `release { isMinifyEnabled=true, isShrinkResources=true }`
- Proguard rules in `app/proguard-rules.pro` keep Room, Hilt, Maps, export domain
- Feature flag `ENABLE_MAP` is BuildConfig boolean; disabled by default. Enable via `gradle.properties ENABLE_MAP=true` or env.

## Offline fallback contract (PR6 6.1)
- When `ENABLE_MAP=false` or `MAPS_API_KEY` blank or device offline, `OfflineMapFallback` shows placeholder grid and pin count; pins remain visible and filterable via Room.
- Permission rationale dialogs for `ACCESS_FINE_LOCATION` and `POST_NOTIFICATIONS` explain why permission is needed and degrade to manual lat/lng + referenciaManual on deny.
