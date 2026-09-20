// ZettaBridge launcher. Generated native/runtime inputs come only from build/launcher.
// Plain Java, UI built in code, no AndroidX.
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
}

// The release key lives outside the repository: ~/.zettabridge/signing.properties names the
// keystore and its passwords. Without that file the release build stays unsigned, so a clone
// without the key still builds.
val signingProps = Properties()
val signingPropsFile = File(System.getProperty("user.home"), ".zettabridge/signing.properties")
if (signingPropsFile.isFile) {
    signingPropsFile.inputStream().use { stream -> signingProps.load(stream) }
}
val releaseKeystore: File? = signingProps.getProperty("storeFile")?.let { path -> File(path) }
val hasReleaseKey = releaseKeystore != null && releaseKeystore.isFile

android {
    namespace = "com.zettabridge.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zettabridge.launcher"
        minSdk = 26
        // 30, not 35: the plugin's Java runs under this app's targetSdk, and a targetSdk of R+
        // makes LocationManager.addGpsStatusListener throw UnsupportedOperationException, which
        // crashes the old Niantic location provider the moment it starts. The 2016 client targets
        // 23 and is unaffected; this keeps the guest on the legacy behavior it expects.
        targetSdk = 30
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
        // A wrapper build carries one game and is named after it, so nothing the user sees says
        // ZettaBridge. -PzbAppName=Kanto for such a build; the default is the generic launcher.
        val appName = (findProperty("zbAppName") as String?)?.takeIf { it.isNotBlank() } ?: "ZettaBridge"
        resValue("string", "app_name", appName)
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main") {
        assets.srcDir(rootProject.file("../../build/launcher/assets"))
        jniLibs.srcDir(rootProject.file("../../build/launcher/jniLibs"))
    }
}

dependencies {
    // Apache-2.0. Lifts hidden API restrictions for the framework hooks in the :guest process.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}
