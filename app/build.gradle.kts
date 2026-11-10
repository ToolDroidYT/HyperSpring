plugins {
    id("com.android.application")
}

android {
    namespace = "com.icehome.up"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.icehome.up"
        minSdk = 30
        targetSdk = 35
        versionCode = 47
        versionName = "1.0.3"
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
