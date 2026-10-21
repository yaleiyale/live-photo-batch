package dev.zayn.vlive

import android.app.Application
import android.content.pm.ApplicationInfo
import dev.zayn.vlive.log.RollingLogTree
import java.io.File
import timber.log.Timber

/**
 * 唯一的 `Application`：只做一件事 —— 把日志的两棵树种下去。
 *
 * 为什么不在 `MainActivity` 里种：`MediaStoreSync` / `BatchViewModel` 会在 Activity 之外被构造
 * （`viewModelFactory` 用的是 `application` 上下文），而 Timber 没种树时 `Timber.x()` 是**静默丢弃**
 * —— 那种"日志没写下来"和"这件事没发生"在事后完全分不开。
 *
 * 两棵树各有分工：
 * - [Timber.DebugTree] 进 logcat，**只在可调试构建**种（判据用 `ApplicationInfo.FLAG_DEBUGGABLE`，
 *   不依赖 `BuildConfig` —— AGP 9 默认不生成它，为一个布尔值打开 buildConfig 不值得）；
 * - [RollingLogTree] 进 `filesDir/logs/`，两种构建都种，自带轮转与上限（默认合计 ≤ 2 MB、≤ 7 天）。
 */
class VliveApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) Timber.plant(Timber.DebugTree())
        Timber.plant(RollingLogTree(File(filesDir, "logs")))
    }
}
