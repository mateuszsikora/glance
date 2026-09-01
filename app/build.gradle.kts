import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing is driven by keystore.properties in the project root, which is
// deliberately untracked. See keystore.properties.example for the expected keys.
// Debug signing requires an explicit -PuseDebugSigning=true opt-in for an already-provisioned
// Device Owner tablet. Public release builds must never silently inherit a developer's debug key.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val useDebugSigning = providers.gradleProperty("useDebugSigning")
    .map(String::toBoolean)
    .getOrElse(false)
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

// Baseline identity of a hand-built release. Self-hosted update builds override both values so
// every published artifact carries a distinct, monotonically increasing versionCode; Android
// refuses to install an update that does not increase it. See docs/self-hosted-updates.md.
val baseVersionCode = 6
val baseVersionName = "1.6"
val releaseVersionCode = providers.gradleProperty("versionCode")
    .map(String::toInt)
    .getOrElse(baseVersionCode)
// An overridden versionCode without an explicit name would otherwise publish every build under
// the same versionName, leaving the tablet's settings screen unable to tell them apart.
val releaseVersionName = providers.gradleProperty("versionName").getOrElse(
    if (releaseVersionCode == baseVersionCode) baseVersionName else "$baseVersionName-$releaseVersionCode"
)

android {
    namespace = "com.glance"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.glance"
        minSdk = 26
        targetSdk = 34
        versionCode = releaseVersionCode
        versionName = releaseVersionName
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug").takeIf { useDebugSigning }
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // JSON
    implementation("org.json:json:20231013")

    // MQTT (pure Java client; no Android background service dependency)
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
