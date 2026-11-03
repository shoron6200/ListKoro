plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun prop(name: String): String? = (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() }

val abiList = (prop("abiFilters") ?: "arm64-v8a").split(",").map { it.trim() }.filter { it.isNotEmpty() }
val shrink = (prop("minify") ?: "true") == "true"
val storeFilePath = prop("LISTKORO_STORE_FILE")

android {
    namespace = "com.listkoro.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.listkoro.app"
        minSdk = 24
        targetSdk = 34
        versionCode = (prop("versionCode") ?: "1").toInt()
        versionName = prop("versionName") ?: "1.0"
        ndk { abiFilters += abiList }
    }

    signingConfigs {
        if (storeFilePath != null) {
            create("listkoro") {
                storeFile = file(storeFilePath)
                storePassword = prop("LISTKORO_STORE_PASSWORD")
                keyAlias = prop("LISTKORO_KEY_ALIAS")
                keyPassword = prop("LISTKORO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = shrink
            isShrinkResources = shrink
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (storeFilePath != null) signingConfigs.getByName("listkoro")
                            else signingConfigs.getByName("debug")
        }
    }

    // Web UI ekta jaygay thake: ../../server/static (Python server o same UI use kore)
    sourceSets["main"].assets.srcDirs("src/main/assets", "../../server/static")

    // yt-dlp/python/ffmpeg .so file theke extract hoy, tai legacy packaging lagbe
    packaging {
        jniLibs { useLegacyPackaging = true }
        resources { excludes += setOf("META-INF/*.md", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    val ytdl = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdl")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdl")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // On-device OCR (Google Play services er model, APK choto thake)
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.0")
}
