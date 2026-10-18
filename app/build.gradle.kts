plugins {
    // AGP 9 自带 Kotlin 支持：不要再 apply org.jetbrains.kotlin.android，
    // 否则插件自己会拒绝加载（"no longer required for Kotlin support since AGP 9.0"，
    // 本地 `:app:tasks` 实测）。见 gradle/libs.versions.toml 的 [plugins] 注释。
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.zayn.livephotobatch"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.zayn.livephotobatch"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // 这个 kotlin { } 不是 AGP 的子块（android { } 没有 kotlin 成员），
    // 它走的是外层 Project 接收者上的内置 Kotlin 扩展，写在 android 里只是为了和 compileOptions 对齐。
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
