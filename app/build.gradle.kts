plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

// Read HEAD for each build; never hard-code the previous commit into source files.
val gitCommit = providers.exec {
    workingDir(rootDir)
    commandLine("git", "rev-parse", "--short=7", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }.get()

android {
    namespace = "com.xatune.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "com.xatune.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 30
        versionName = "1.1.7"
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
        buildConfigField("String", "ANNOUNCEMENTS_BASE_URL", "\"${providers.gradleProperty("announcementsBaseUrl").orElse("").get()}\"")
        buildConfigField("boolean", "DEMO_ENABLED", "false")
        buildConfigField("boolean", "WEB_DIAGNOSTICS", "false")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = false
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        debug {
            buildConfigField("boolean", "DEMO_ENABLED", "false")
            buildConfigField("String", "ANNOUNCEMENTS_BASE_URL", "\"${providers.gradleProperty("announcementsBaseUrl").orElse("http://localhost:8765").get()}\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildTypes.create("benchmark") {
        initWith(buildTypes.getByName("release"))
        signingConfig = signingConfigs.getByName("debug")
        applicationIdSuffix = ".benchmark"
        isDebuggable = false
        matchingFallbacks += "release"
        buildConfigField("boolean", "DEMO_ENABLED", "true")
    }
    buildTypes.create("qa") {
        initWith(buildTypes.getByName("debug"))
        applicationIdSuffix = ".qa"
        buildConfigField("boolean", "DEMO_ENABLED", "true")
        matchingFallbacks += "debug"
    }
    buildTypes.create("diagnostic") {
        initWith(buildTypes.getByName("release"))
        applicationIdSuffix = ".diagnostic"
        signingConfig = signingConfigs.getByName("debug")
        matchingFallbacks += "release"
        buildConfigField("boolean", "WEB_DIAGNOSTICS", "true")
    }
    buildTypes.getByName("qa") { buildConfigField("boolean", "WEB_DIAGNOSTICS", "true") }
    testBuildType = "qa"
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    val bom = platform("androidx.compose:compose-bom:2025.08.01")
    implementation(bom)
    androidTestImplementation(bom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("androidx.webkit:webkit:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    kapt("androidx.room:room-compiler:2.7.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.21.2")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    add("qaImplementation", "androidx.compose.ui:ui-test-manifest")
}
kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }
