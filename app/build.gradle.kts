import java.util.Properties
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val versionProperties = Properties().apply {
    rootProject
        .file("version.properties")
        .inputStream()
        .use { input ->
            load(input)
        }
}

val dmlVersionName =
    versionProperties
        .getProperty("VERSION_NAME")
        ?.takeIf { it.matches(Regex("""^v\d+\.\d+\.\d+(?:-beta)?$""")) }
        ?: error(
            "VERSION_NAME must use a value such as " +
                    "v0.7.0-beta or v1.0.0."
        )

val dmlVersionCode =
    versionProperties
        .getProperty("VERSION_CODE")
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
        ?: error(
            "VERSION_CODE must be a positive integer."
        )

android {
    namespace = "com.shonkware.droidmodloader"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.shonkware.droidmodloader"
        minSdk = 30
        //noinspection OldTargetApi
        targetSdk = 36
        versionCode = dmlVersionCode
        versionName = dmlVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation("junit:junit:4.13.2")

    // Provides real org.json classes for local JVM unit tests.
    testImplementation("org.json:json:20260522")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    //noinspection UseTomlInstead
    implementation("org.apache.commons:commons-compress:1.28.0")
    //noinspection UseTomlInstead
    implementation("org.tukaani:xz:1.12")
    //noinspection UseTomlInstead
    implementation("com.github.junrar:junrar:7.6.0")
}
