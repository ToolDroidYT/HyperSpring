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
        versionCode = 48
        versionName = "1.1.0-beta1"
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
    // Las dos API de Xposed, solo para compilar: en el móvil las pone el framework.
    compileOnly("de.robv.android.xposed:api:82")          // clásica, motor LegacyEngine
    compileOnly("io.github.libxposed:api:102.0.0")        // libxposed, motor ModernEngine
}
