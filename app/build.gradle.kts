import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: keystore.properties (local) or SIGNING_* environment variables (CI).
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? = signingProps.getProperty(key) ?: System.getenv(env)

val abiList = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
// The version lives in the top-level VERSION file (release builds may override it with
// -Pfsv.versionName=X.Y.Z); the version code is derived from it.
val appVersionName = (findProperty("fsv.versionName") as String?) ?: rootProject.file("VERSION").readText().trim()
val appVersionCode = appVersionName.split('.', '-').take(3).map { it.toIntOrNull() ?: 0 }
    .let { v -> v.getOrElse(0) { 0 } * 10000 + v.getOrElse(1) { 0 } * 100 + v.getOrElse(2) { 0 } }

android {
    namespace = "io.github.doesthings.meshwise"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "io.github.doesthings.meshwise"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared", "-DCMAKE_BUILD_TYPE=Release")
                cppFlags += listOf("-std=c++17")
            }
        }
        ndk {
            abiFilters += abiList
        }
    }

    signingConfigs {
        create("release") {
            val storePath = signingValue("storeFile", "SIGNING_STORE_FILE")
            if (storePath != null && file(storePath).exists()) {
                storeFile = file(storePath)
                storePassword = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")
            }
            // v2 for installation, v3 so the key can be rotated later without breaking updates.
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val release = signingConfigs.getByName("release")
            signingConfig = if (release.storeFile != null) release else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*abiList.toTypedArray())
            isUniversalApk = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            // Compressed native libraries keep the download small (the CAD kernel alone is
            // ~20 MB uncompressed); they are extracted once at install time.
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // JVM build of libmeshwise.so (native/CMakeLists.txt, target meshwise_jvm).
                val hostLib = (project.findProperty("fsv.hostLibDir") as String?) ?: rootProject.file("native/build-host").absolutePath
                test.systemProperty("java.library.path", hostLib)
                test.systemProperty("fsv.hostLibDir", hostLib)
                test.systemProperty("fsv.screenshotDir", rootProject.file("build/screenshots").absolutePath)
                test.systemProperty("fsv.renderDir", rootProject.file("build/renders").absolutePath)
                test.systemProperty("roborazzi.test.record", "true")
                // Optional mirror for Robolectric's runtime downloads (android-all jars).
                (project.findProperty("fsv.robolectricRepo") as String?)?.let {
                    test.systemProperty("robolectric.dependency.repo.url", it)
                    test.systemProperty("robolectric.dependency.repo.id", "mirror")
                }
                test.maxHeapSize = "3g"
                // The JNI library can only be loaded by one classloader per JVM, and test classes
                // with different Robolectric configs get different classloaders.
                test.forkEvery = 1
            }
        }
    }
}

// Distinct version codes per ABI split so stores/updaters pick the right one.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }?.identifier
            val offset = when (abi) {
                "armeabi-v7a" -> 1
                "arm64-v8a" -> 2
                "x86_64" -> 3
                else -> 0
            }
            output.versionCode.set(appVersionCode * 10 + offset)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
