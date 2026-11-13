import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing lives outside the repo; see keystore.properties.example.
val signing = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use(::load)
}

android {
    namespace = "top.bangwu.taildeck"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "top.bangwu.taildeck"
        minSdk = 26
        targetSdk = 36
        versionCode = 25
        versionName = "0.1.0"
        // libghostty-vt and tsbridge are only built for these two ABIs.
        externalNativeBuild.cmake.abiFilters += listOf("arm64-v8a", "x86_64")
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (signing.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
    }
}

dependencies {
    implementation(files("libs/tsbridge.aar"))

    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    // Material 3 Expressive components; later alphas need compileSdk 37 and AGP 9.1.
    implementation("androidx.compose.material3:material3:1.5.0-alpha14")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.navigation:navigation-compose:2.9.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.browser:browser:1.9.0")
    implementation("androidx.webkit:webkit:1.14.0")
}

// Installs signed by the old key upgrade in place: re-sign with the old key
// for v1/v2 plus the new key and rotation proof for v3 (Android 9+).
interface Injected { @get:javax.inject.Inject val exec: ExecOperations }

val rotateReleaseSignature by tasks.registering {
    val lineage = signing.getProperty("lineageFile")
    onlyIf { lineage != null && file(lineage).exists() }
    val apkDir = layout.buildDirectory.dir("outputs/apk/release")
    val apksigner = android.sdkDirectory.resolve("build-tools/${android.buildToolsVersion}/apksigner")
    val exec = objects.newInstance<Injected>().exec
    val args = listOf(
        apksigner.absolutePath, "sign",
        "--ks", signing.getProperty("legacyStoreFile").orEmpty(),
        "--ks-key-alias", signing.getProperty("legacyKeyAlias").orEmpty(),
        "--ks-pass", "pass:" + signing.getProperty("legacyStorePassword").orEmpty(),
        "--key-pass", "pass:" + signing.getProperty("legacyKeyPassword").orEmpty(),
        "--next-signer",
        "--ks", signing.getProperty("storeFile").orEmpty(),
        "--ks-key-alias", signing.getProperty("keyAlias").orEmpty(),
        "--ks-pass", "pass:" + signing.getProperty("storePassword").orEmpty(),
        "--key-pass", "pass:" + signing.getProperty("keyPassword").orEmpty(),
        "--lineage", lineage.orEmpty(),
        "--rotation-min-sdk-version", "28",
    )
    doLast {
        val apks = apkDir.get().asFile.listFiles { f -> f.extension == "apk" }.orEmpty()
        check(apks.isNotEmpty()) { "No release APKs in ${apkDir.get()}" }
        apks.forEach { apk -> exec.exec { commandLine(args + apk.absolutePath) } }
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(rotateReleaseSignature) }
