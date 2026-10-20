plugins {
    // AGP 9 自带 Kotlin 支持：不要再 apply org.jetbrains.kotlin.android，
    // 否则插件自己会拒绝加载（"no longer required for Kotlin support since AGP 9.0"，
    // 本地 `:app:tasks` 实测）。见 gradle/libs.versions.toml 的 [plugins] 注释。
    alias(libs.plugins.android.application)
    // Task 11 Step 0：@Composable 需要 Compose 编译器插件（Kotlin 2.x 起它不再随 Kotlin 自带），
    // 版本与 Kotlin 同一个 ref，见 libs.versions.toml。
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.zayn.livephotobatch"
    // 计划写的是 36，但 CI（run 39084353266）在 :app:checkDebugAarMetadata 上失败：
    // compose-bom 2026.09.00（→ compose 1.12.1）与 lifecycle 2.11.0 的 AAR 元数据要求
    // "compile against version 37 or later"，AGP 的建议动作就是升到 37。
    // SDK 仓库里 37 只有 android-37.0 / android-37.1 两个包名，所以 minor 必须是 0。
    // targetSdk 仍是 36：AAR 检查只管 compileSdk，运行时行为不该被这条依赖约束牵着走。
    compileSdk = 37
    compileSdkMinor = 0

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
    // Task 11 Step 0：没有这一行，androidx.compose 的运行时在依赖图里，但 :app 的源码里
    // 一个 @Composable 都编译不出来（AGP 不会自动开启 compose）。
    buildFeatures { compose = true }
    // 这个 kotlin { } 不是 AGP 的子块（android { } 没有 kotlin 成员），
    // 它走的是外层 Project 接收者上的内置 Kotlin 扩展，写在 android 里只是为了和 compileOptions 对齐。
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    // 冷启动那一帧的 SplashScreen（Theme.SplashScreen + installSplashScreen）。
    // 这是 :app 唯一的"多一个依赖"决定，换来的是 API 30 上也能覆盖进程启动到首帧那段真空；
    // 版本走 libs.versions.toml 的 splashScreen。
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // 仪器化测试直接 import org.junit.*（AndroidJUnit4 / Assert / @Test），
    // 之前只靠 androidx.test:runner 的传递依赖拿到，声明下来不再依赖别人的依赖图。
    androidTestImplementation(libs.junit4)
}
