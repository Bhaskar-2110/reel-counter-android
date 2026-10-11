import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Google sign-in needs the Firebase project's google-services.json (not in the repo). Without it
// the app still builds; Settings → Account then says sign-in isn't set up in this build.
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

android {
    namespace = "com.reelcounter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.reelcounter"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        // The build time makes each APK easy to tell apart (shown at the bottom of Settings).
        versionName = "1.0 (" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMM HH:mm")) + ")"
    }

    buildTypes {
        release {
            // R8 shrinks and optimises the code and drops unused resources: a smaller, faster APK.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-analytics")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    testImplementation("junit:junit:4.13.2")
}
