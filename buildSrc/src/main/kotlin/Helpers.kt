import com.android.build.api.dsl.ApplicationExtension
import com.android.build.gradle.AbstractAppExtension
import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.getByName
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions
import java.util.Base64
import java.util.Properties

private val Project.android get() = extensions.getByName<ApplicationExtension>("android")

/** Read on every call: buildSrc statics live as long as the Gradle daemon, so a cache would keep old versions. */
fun Project.requireMetadata(): Properties = Properties().apply {
    rootProject.file("nb4a.properties").inputStream().use { load(it) }
}

/** A `-P<property>` Gradle property, else the [env] variable (CI), else null. */
private fun Project.buildOption(property: String, env: String): String? =
    (findProperty(property) as String?)?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv(env)?.trim()?.takeIf { it.isNotEmpty() }

/** `stable` (default) or `preview`: `-Prawline.channel` / `RAWLINE_CHANNEL`. */
fun Project.buildChannel(): String {
    val channel = buildOption("rawline.channel", "RAWLINE_CHANNEL") ?: "stable"
    if (channel != "stable" && channel != "preview") throw GradleException("rawline.channel must be stable or preview, got $channel")
    return channel
}

/**
 * The last three digits of versionCode: 999 for a stable tag build, the commit count since the last stable tag for a
 * preview, 0 for a local build (`-Prawline.build` / `RAWLINE_BUILD`).
 */
fun Project.buildNumber(): Int {
    val raw = buildOption("rawline.build", "RAWLINE_BUILD") ?: return 0
    return raw.toIntOrNull()?.takeIf { it in 0..999 } ?: throw GradleException("rawline.build must be 0..999, got $raw")
}

/** SHA-256 of the release signing certificate pinned by the in-app updater, "" = no pin (`UPDATE_SIGNER_SHA256`). */
fun Project.updateSignerPin(): String {
    val raw = buildOption("rawline.updateSignerSha256", "UPDATE_SIGNER_SHA256") ?: return ""
    val hex = raw.replace(":", "").lowercase()
    if (!hex.matches(Regex("[0-9a-f]{64}"))) throw GradleException("UPDATE_SIGNER_SHA256 is not a SHA-256 hex digest")
    return hex
}

fun Project.setupCommon() {
    android.apply {
        buildToolsVersion = "35.0.1"
        compileSdk = 35
        defaultConfig {
            minSdk = 24
            targetSdk = 35
        }
        buildTypes {
            getByName("release") {
                isMinifyEnabled = true
            }
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        (android as ExtensionAware).extensions.getByName<KotlinJvmOptions>("kotlinOptions").apply {
            jvmTarget = JavaVersion.VERSION_17.toString()
        }
        lint {
            showAll = true
            checkAllWarnings = true
            checkReleaseBuilds = true
            warningsAsErrors = true
            textOutput = project.file("build/lint.txt")
            htmlOutput = project.file("build/lint.html")
        }
        packaging {
            resources.excludes.addAll(
                listOf(
                    "**/*.kotlin_*",
                    "/META-INF/*.version",
                    "/META-INF/native/**",
                    "/META-INF/native-image/**",
                    "/META-INF/INDEX.LIST",
                    "DebugProbesKt.bin",
                    "com/**",
                    "org/**",
                    "**/*.java",
                    "**/*.proto",
                    "okhttp3/**"
                )
            )
        }
        (this as? AbstractAppExtension)?.apply {
            buildTypes {
                getByName("release") {
                    isShrinkResources = true
                    if (System.getenv("nkmr_minify") == "0") {
                        isShrinkResources = false
                        isMinifyEnabled = false
                    }
                }
                getByName("debug") {
                    applicationIdSuffix = "debug"
                    debuggable(true)
                    jniDebuggable(true)
                }
            }
        }
    }
}

/**
 * Release signing from the environment (CI secrets): SIGNING_KEYSTORE_B64 (base64 keystore), SIGNING_STORE_PASSWORD,
 * SIGNING_KEY_ALIAS, SIGNING_KEY_PASSWORD (defaults to the store password). Without them release builds stay unsigned;
 * debug builds always use the SDK debug key.
 */
fun Project.setupAppCommon() {
    setupCommon()

    val keystoreB64 = System.getenv("SIGNING_KEYSTORE_B64")?.trim()
    if (keystoreB64.isNullOrEmpty()) return
    fun requireEnv(name: String) = System.getenv(name)?.takeIf { it.isNotEmpty() }
        ?: throw GradleException("SIGNING_KEYSTORE_B64 is set but $name is missing")
    val storePass = requireEnv("SIGNING_STORE_PASSWORD")
    val alias = requireEnv("SIGNING_KEY_ALIAS")
    val keyPass = System.getenv("SIGNING_KEY_PASSWORD")?.takeIf { it.isNotEmpty() } ?: storePass
    val keystore = layout.buildDirectory.file("signing/release.keystore").get().asFile
    keystore.parentFile.mkdirs()
    keystore.writeBytes(Base64.getMimeDecoder().decode(keystoreB64))

    android.apply {
        val release = signingConfigs.create("release").apply {
            storeFile = keystore
            storeType = "PKCS12"
            storePassword = storePass
            keyAlias = alias
            keyPassword = keyPass
        }
        buildTypes.getByName("release").signingConfig = release
    }
}

fun Project.setupApp() {
    val metadata = requireMetadata()
    val pkgName = metadata.getProperty("PACKAGE_NAME")
    val baseVersion = metadata.getProperty("VERSION_NAME")
    val versionIndex = metadata.getProperty("VERSION_CODE").toInt()
    val coreRef = metadata.getProperty("THRONE_AAR_REF") ?: metadata.getProperty("THRONE_CORE_REF")
    val preview = buildChannel() == "preview"
    val build = buildNumber()
    android.apply {
        defaultConfig {
            applicationId = pkgName
            versionCode = versionIndex * 1000 + build
            versionName = if (preview) "$baseVersion-pre.$build" else baseVersion
            buildConfigField("String", "THRONE_CORE_REF", "\"$coreRef\"")
            buildConfigField("boolean", "PREVIEW", preview.toString())
            buildConfigField("String", "UPDATE_SIGNER_SHA256", "\"${updateSignerPin()}\"")
        }
    }
    setupAppCommon()

    android.apply {
        this as AbstractAppExtension

        buildTypes {
            getByName("release") {
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    file("proguard-rules.pro")
                )
            }
        }

        // RawlineCore is built for android/arm64, android/arm and android/amd64 only.
        splits.abi {
            reset()
            isEnable = true
            isUniversalApk = true
            include("armeabi-v7a")
            include("arm64-v8a")
            include("x86_64")
        }

        flavorDimensions += "vendor"
        productFlavors {
            create("oss") {
                buildConfigField("boolean", "IN_APP_UPDATER", "true")
            }
            create("fdroid") {
                buildConfigField("boolean", "IN_APP_UPDATER", "false")
            }
        }

        // Rawline-<versionName>[-<flavor>]-<abi>[-<buildType>][-unsigned].apk; oss release = Rawline-<versionName>-<abi>.apk
        applicationVariants.all {
            val variant = this
            outputs.all {
                this as BaseVariantOutputImpl
                outputFileName = "RawlineMobile-${variant.versionName}" + outputFileName.removePrefix(project.name)
                    .replace("-release", "")
                    .replace("-oss", "")
            }
        }

        for (abi in listOf("Arm64", "Arm", "X64")) {
            tasks.register("assemble" + abi + "FdroidRelease") {
                dependsOn("assembleFdroidRelease")
            }
        }
    }
}
