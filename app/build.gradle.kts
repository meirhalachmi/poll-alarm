plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// versionCode: CI's run number + 10 (so CI builds are newer than the first hand-built APKs, 1 and
// 2); locally 1, or -PlocalVersion=N.
val versionCodeFromCi = System.getenv("GITHUB_RUN_NUMBER")?.toInt()?.plus(10)
val appVersionCode = versionCodeFromCi ?: (findProperty("localVersion") as String?)?.toInt() ?: 1

// Where the app looks for updates: the repository CI builds from, or this one for a local build.
val updateRepo = System.getenv("GITHUB_REPOSITORY") ?: "meirhalachmi/poll-alarm"

// The release key comes from the environment (CI decodes it from repository secrets). Without it,
// a release build is signed with the debug key, which cannot update an installed release.
val signingKeystore = System.getenv("SIGNING_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
val signingPassword = System.getenv("SIGNING_PASSWORD")

android {
    namespace = "org.halachmi.polls"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.halachmi.polls"
        minSdk = 28
        targetSdk = 36
        versionCode = appVersionCode
        versionName = "1.0.$appVersionCode"
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    signingConfigs {
        if (signingKeystore != null && signingPassword != null) {
            create("release") {
                storeFile = signingKeystore
                storePassword = signingPassword
                keyAlias = "pollalarm"
                keyPassword = signingPassword
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    lint {
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    testImplementation("junit:junit:4.13.2")
}
