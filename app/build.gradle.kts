plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

// Подпись release-сборки берётся из переменных окружения, а не из файла
// в репозитории. В CI они приезжают из GitHub Secrets, локально их можно
// выставить в своей оболочке. Если KEYSTORE_PATH не задан, signingConfig
// не создаётся и assembleRelease выдаёт неподписанный APK — так локальная
// сборка и форки продолжают работать без доступа к ключу.
val keystorePath: String? = System.getenv("KEYSTORE_PATH")

android {
    namespace = "com.trackstudio.rfidmanager"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.trackstudio.rfidmanager"
        minSdk = 33
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // null, если ключ не передан — тогда APK останется неподписанным
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
}

// Пришло на смену kotlinOptions { jvmTarget = "11" }: строковый jvmTarget
// в старом DSL объявлен ошибкой начиная с Kotlin 2.2. compilerOptions
// поддерживается с Kotlin 2.0, так что работает и на текущей 2.1.0.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(files("libs/DeviceAPI_ver20230301_release.aar"))
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}