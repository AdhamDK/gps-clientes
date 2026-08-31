# Tasks: apk-webview-wrapper — 0 Divergencia WebView

## Breakdown (5 PRs,  Single PR actually, Low risk)

| Phase | Tasks | Focus | Files | Est Lines |
|-------|-------|-------|-------|-----------|
| 1 | 2 | WebViewAssetLoader + Gradle Copy | app/build.gradle.kts, app/src/main/assets/www/*, app/src/main/java/com/gpsclientes/ui/WebViewScreen.kt | 60 |
| 2 | 1 | Flag ENABLE_WEBVIEW | app/build.gradle.kts (buildConfigField), MainActivity.kt | 20 |
| 3 | 1 | API Origin + Cleartext | frontend/app.js, app/src/main/AndroidManifest.xml, network_security_config.xml | 30 |
| 4 | 1 | Optional GPS Bridge | app/src/main/java/com/gpsclientes/ui/WebViewScreen.kt (JavascriptInterface) | 40 |
| 5 | 2 | Testing | app/src/test/java/...WebView*Test.kt, frontend/test_webview.js | 50 |
| **Total** | **7** | | | **200** |

## Implementation Order
1 → 2 → 3 → 4 → 5 — Foundation (AssetLoader) unblocks all; flag unblocks testing; bridges optional.

## Review Workload Forecast
- Estimated changed lines: 200
- 400-line budget risk: Low
- Chained PRs recommended: No
- Delivery: Single PR
- Chain strategy: none

## Next
Ready for sdd-apply single PR.

## Risks
- PK Long vs UUID — defer, keep HTTP
- file:// SW — mitigated by https AssetLoader
- Theme flicker — mitigated by DataStore sync
