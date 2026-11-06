plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.phonehost.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.phonehost.app"
        minSdk = 24
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0"
        // GitHub Actions me repo automatically milta hai. Local build me yahan apna repo likho.
        val repo = System.getenv("GITHUB_REPOSITORY") ?: "YOUR_USERNAME/PhoneHost"
        buildConfigField("String", "GITHUB_REPO", "\"$repo\"")
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        create("release") {
            storeFile = file("phonehost.p12")
            storePassword = "phonehost"
            keyAlias = "phonehost"
            keyPassword = "phonehost"
            storeType = "pkcs12"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
