plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
android {
    namespace = "kr.mom.probe"
    compileSdk = 36
    defaultConfig {
        applicationId = "kr.mom.probe"
        minSdk = 33
        targetSdk = 36
        versionCode = 10
        versionName = "0.9.0-agent"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // No service credentials are compiled into the APK. The production NEIS lane
        // was removed; a server-side proxy would be required to restore it.
    }
    signingConfigs {
        maybeCreate("release").apply {
            val storePath = providers.gradleProperty("MAMA_RELEASE_STORE_FILE").orNull
                ?: System.getenv("MAMA_RELEASE_STORE_FILE")
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = providers.gradleProperty("MAMA_RELEASE_STORE_PASSWORD").orNull
                    ?: System.getenv("MAMA_RELEASE_STORE_PASSWORD")
                keyAlias = providers.gradleProperty("MAMA_RELEASE_KEY_ALIAS").orNull
                    ?: System.getenv("MAMA_RELEASE_KEY_ALIAS")
                keyPassword = providers.gradleProperty("MAMA_RELEASE_KEY_PASSWORD").orNull
                    ?: System.getenv("MAMA_RELEASE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".qa"
            versionNameSuffix = "-qa"
        }
        release {
            // Fail closed: release builds require real signing material. There is no
            // debug-signing fallback; `verifyReleaseSigning` aborts the build clearly.
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) signingConfig = releaseSigning
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "3g"
            val testHome = layout.buildDirectory.dir("test-home").get().asFile
            val testTemp = layout.buildDirectory.dir("test-tmp").get().asFile
            it.systemProperty("user.home", testHome.absolutePath)
            it.systemProperty("java.io.tmpdir", testTemp.absolutePath)
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            it.doFirst { testHome.mkdirs(); testTemp.mkdirs() }
        }
    }
    lint { abortOnError = true; checkReleaseBuilds = true }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    description = "Fails release assembly when signing credentials are absent."
    group = "verification"
    doLast {
        val config = android.buildTypes.getByName("release").signingConfig
        require(config != null && config.storeFile?.exists() == true) {
            "Release signing is not configured. Set MAMA_RELEASE_STORE_FILE, " +
                "MAMA_RELEASE_STORE_PASSWORD, MAMA_RELEASE_KEY_ALIAS and " +
                "MAMA_RELEASE_KEY_PASSWORD (gradle properties or environment). " +
                "Debug signing is never used for release builds."
        }
    }
}
tasks.matching { it.name == "packageRelease" || it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn(verifyReleaseSigning)
}
dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.05.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

