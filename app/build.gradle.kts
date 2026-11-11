plugins {
    id("com.android.application")
}

// Keep Gradle and local-build output versions in sync with the module runtime metadata.
val moduleVersion = file("src/main/java/com/icehome/up/Version.java").readText()
val moduleVersionName = Regex("""NAME = "([^"]+)"""").find(moduleVersion)?.groupValues?.get(1)
    ?: error("Could not read Version.NAME")
val moduleVersionCode = Regex("""CODE = ([0-9]+)""").find(moduleVersion)?.groupValues?.get(1)?.toInt()
    ?: error("Could not read Version.CODE")

android {
    namespace = "com.icehome.up"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.icehome.up"
        minSdk = 30
        targetSdk = 35
        versionCode = moduleVersionCode
        versionName = moduleVersionName
    }

    buildTypes {
        release {
            // Sin ofuscar: LSPosed necesita encontrar MainHook por nombre.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
