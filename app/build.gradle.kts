plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("kotlin-parcelize")
}

android {
    namespace = "com.gpsclientes"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gpsclientes"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "1.1.7"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // OSM map enabled — no API key required (osmdroid MAPNIK tiles)
        buildConfigField("boolean", "ENABLE_MAP", "true")
        // WebView wrapper for 0 divergence — same HTML/JS in web and APK
        buildConfigField("boolean", "ENABLE_WEBVIEW", "true")
        buildConfigField("String", "API_URL", "\"http://192.168.0.103:8000\"") // fix fetch: LAN IP for physical device
    }

    buildTypes {
        debug {
            isMinifyEnabled = false // fix cache: keep debug fast, no minify (21MB debug is dev artifact)
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    val roomVersion = "2.6.1"
    val hiltVersion = "2.48"
    val lifecycleVersion = "2.7.0"

    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:$lifecycleVersion")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose BOM
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Room
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Hilt
    implementation("com.google.dagger:hilt-android:$hiltVersion")
    ksp("com.google.dagger:hilt-compiler:$hiltVersion")

    // Play Services Location (kept for GPS) + OSM osmdroid (no API key, MAPNIK tiles)
    implementation("com.google.android.gms:play-services-location:21.1.0")
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    implementation("androidx.preference:preference-ktx:1.2.1")

    // DataStore for Theme persistence (claro/oscuro/medio)
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // WebView for 0 divergence wrapper
    implementation("androidx.webkit:webkit:1.9.0")

    // Apache POI for Excel import/export
    implementation("org.apache.poi:poi:5.2.5")
    implementation("org.apache.poi:poi-ooxml:5.2.5")

    // WorkManager (for NominatimWorker)
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // Navigation + Hilt Navigation Compose + Coroutines Play Services (for LocationRepository tasks.await and hiltViewModel)
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("io.mockk:mockk:1.13.9")
    testImplementation("app.cash.turbine:turbine:1.0.0")
    testImplementation("androidx.room:room-testing:$roomVersion")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test:core-ktx:1.5.0")
    testImplementation("org.robolectric:robolectric:4.11.1")

    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

// WebView 0 divergence — copy frontend to assets/www for WebViewAssetLoader
tasks.register<Copy>("copyFrontendToAssets") {
    from("../frontend")
    into("src/main/assets/www")
    exclude("test_*.js", "e2e.*", "*.spec.js") // fix cache: sync sw.js now - was exclude sw.js divergencia v4/v5
}
tasks.named("preBuild") { dependsOn("copyFrontendToAssets") }

// Coverage: Kover/Jacoco — enable with: ./gradlew koverHtmlReport or jacocoTestReport
// CI runs: ./gradlew testDebugUnitTest koverXmlReport (requires JDK17+SDK34)
// Wrapper jar: generate via `gradle wrapper` on CI (gradle-wrapper.jar is gitignored until generated;
// distributionUrl in gradle-wrapper.properties points to gradle-8.6-bin.zip)
