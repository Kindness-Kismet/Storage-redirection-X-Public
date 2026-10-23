plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val appVersionName = project.property("appVersionName").toString()
val appVersionCode = project.property("appVersionCode").toString()
val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }

android {
    namespace = "com.storage.redirect.x"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.storage.redirect.x"
        minSdk = 31
        targetSdk = 37
        versionCode = appVersionCode.toInt()
        versionName = appVersionName
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }
}

dependencies {
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.core.ktx)
    implementation(libs.miuix)
    implementation(libs.miuix.icons)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.nav)
    implementation(libs.coroutines)
    implementation(libs.immutable)
    implementation(libs.gson)
    implementation(libs.hiddenapibypass)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "1024m"
    maxParallelForks = 1
    // Compose dispatchers and native windows must not span Robolectric test-class sandboxes.
    forkEvery = 1
    jvmArgs("-XX:+UseSerialGC")
}
