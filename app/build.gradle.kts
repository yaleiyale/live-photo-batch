plugins {
    // AGP 9 自带 Kotlin 支持：不要再 apply org.jetbrains.kotlin.android，声明了插件会自己拒绝加载
    // （"plugin is no longer required for Kotlin support since AGP 9.0"）。
    alias(libs.plugins.android.application)
    // @Composable 需要 Compose 编译器插件（Kotlin 2.x 起它不随 Kotlin 自带），版本必须与 Kotlin
    // 同一个 ref，见 libs.versions.toml。
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.zayn.vlive"
    // compileSdk 由依赖决定：compose-bom 与 lifecycle 的 AAR 元数据要求
    // "compile against version 37 or later"，停在 36 时 :app:checkDebugAarMetadata 直接失败。
    // SDK 仓库里 37 只有 android-37.0 / android-37.1 两个包名，所以 minor 必须是 0。
    // targetSdk 保持 36：AAR 检查只管 compileSdk，运行时行为不该被这条依赖约束牵着走。
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        applicationId = "dev.zayn.vlive"
        minSdk = 31
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
    // 没有这一行，androidx.compose 的运行时在依赖图里，但 :app 的源码里一个 @Composable 都编译不出来
    // （AGP 不会自动开启 compose）。
    buildFeatures { compose = true }
    // 这个 kotlin { } 不是 AGP 的子块（android { } 没有 kotlin 成员），
    // 它走的是外层 Project 接收者上的内置 Kotlin 扩展，写在 android 里只是为了和 compileOptions 对齐。
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    // 冷启动那一帧的 SplashScreen（Theme.SplashScreen + installSplashScreen）。买的是两件事：
    // 清单/主题里那一套 `windowSplashScreen*` 属性，和 `setOnExitAnimationListener` 这个退出时机——
    // 入场闸门（IosEntrance）就是挂在那次回调上放行的。
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // 目录选择的持久化走 DataStore，日志的树根与格式化走 Timber，都只引这两个库。
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.timber)
    // 真背景模糊（RenderEffect，API 31+）。source 侧与 consumer 侧是两个构件。
    implementation(libs.haze.core)
    implementation(libs.haze.blur)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // 仪器化测试直接 import org.junit.*（AndroidJUnit4 / Assert / @Test），显式声明，
    // 不靠 androidx.test:runner 的传递依赖。
    androidTestImplementation(libs.junit4)
}
