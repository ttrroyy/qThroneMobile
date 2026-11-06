@file:Suppress("UnstableApiUsage")

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("com.google.devtools.ksp")
    id("kotlin-parcelize")
}

setupApp()

val generatedLicenseAssets = layout.buildDirectory.dir("generated/assets/rootLicense")
val generateRootLicenseAsset by tasks.registering(Copy::class) {
    from(rootProject.layout.projectDirectory.file("LICENSE"))
    into(generatedLicenseAssets)
}

android {
    defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
    ksp {
        arg("room.incremental", "true")
        arg("room.schemaLocation", "$projectDir/schemas")
    }
    bundle {
        language {
            enableSplit = false
        }
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
        aidl = true
    }
    namespace = "app.rawline.mobile"
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    androidResources {
        generateLocaleConfig = true
    }
    sourceSets.named("main") {
        assets.srcDir(generatedLicenseAssets)
        jniLibs.srcDir(rootProject.layout.projectDirectory.dir("deployment/carriers"))
    }
    sourceSets.named("androidTest") { assets.srcDir("schemas") }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(generateRootLicenseAsset)
}

dependencies {

    implementation(fileTree("libs"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.recyclerview:recyclerview:1.3.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.5.6")
    implementation("androidx.browser:browser:1.5.0")
    implementation("androidx.preference:preference-ktx:1.2.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    implementation("androidx.work:work-runtime-ktx:2.8.1")
    implementation("androidx.work:work-multiprocess:2.8.1")

    implementation("com.google.android.material:material:1.8.0")
    implementation("com.google.code.gson:gson:2.9.0")
    // Lightweight AEAD API keeps Happ crypt5 compatible with Android 7–8 as well.
    implementation("org.bouncycastle:bcprov-jdk15to18:1.86")

    implementation("com.github.jenly1314:zxing-lite:2.1.1")
    // 0.24.x is built with Kotlin 2.2 (unreadable for the Kotlin 2.0 compiler/KSP) and 0.24.5+ needs compileSdk 36;
    // 0.23.7 is Kotlin 2.1 and only references stdlib members that 2.0.21 has, so the app keeps its own stdlib
    implementation("io.github.rosemoe:editor:0.23.7") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.github.mwiede:jsch:0.2.16")
    implementation("org.yaml:snakeyaml:2.5")
    implementation("com.github.daniel-stoneuk:material-about-library:3.2.0-rc01")
    implementation("com.jakewharton:process-phoenix:2.1.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")

    implementation("com.simplecityapps:recyclerview-fastscroll:2.0.1") {
        exclude(group = "androidx.recyclerview")
        exclude(group = "androidx.appcompat")
    }

    implementation("androidx.room:room-runtime:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")
}
