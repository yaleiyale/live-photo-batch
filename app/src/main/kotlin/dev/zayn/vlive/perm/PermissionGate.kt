package dev.zayn.vlive.perm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * 全部文件访问权限（`MANAGE_EXTERNAL_STORAGE`）的门面。
 *
 * ## 为什么必须从注入的 `() -> Boolean` 构造
 * 真实状态来自 `Environment.isExternalStorageManager()`——一个只能在 API 30+ 的 Android
 * 运行时里读到真值的静态调用。把它做成构造参数，`granted()` 就退化成一次纯 Kotlin 委托，
 * `app/src/test` 里的本地 JVM 单测（不引 Robolectric、不引新依赖）能直接测出行为，Task 11
 * 的 ViewModel 也能用假 lambda 测。`of(context)` 只是那层薄适配器。
 *
 * ## 与 `FileStorage` / `DirBrowser` 共享的 root 字符串契约
 * Task 11 用同一个未规范化的 `"/storage/emulated/0"` 字符串构造 `FileStorage(root)` 与本类
 * 的兄弟类 `DirBrowser(root)`——不带尾斜杠、绝不空。这个约定必须守，因为
 * `FileStorage.resolve()` 判定 `path == root || path.startsWith("$root/")` 才走原样分支，
 * 一旦给 root 加尾斜杠（或传空串），`"/storage/emulated/0"` 本身就会被当成相对路径拼一遍，
 * 变成 `/storage/emulated/0/storage/emulated/0`——`list(root)` 静默返回空、`ensureDir(root)`
 * 在真实存储里种一棵垃圾树。本类不直接读文件，但它的 `of()` 与 `requestIntent()` 就是那条
 * root 契约被建立时的入口（用户在 UI 上跳系统页 → 回到 `granted()==true` → 才允许
 * `FileStorage("/storage/emulated/0")` 开工），所以在此把契约写死以防被误改。
 *
 * ## 短路与 API 30
 * `of(context)` 的 lambda 用 `Build.VERSION.SDK_INT < Build.VERSION_CODES.R || ...`：低于
 * API 30 时短路，`Environment.isExternalStorageManager()`（API 30 才有）根本不会被调用，
 * minSdk 30 只是双保险。注意 API 常量必须写 `Build.VERSION_CODES.R`，
 * 不是 `Build.VERSION.R`（后者不存在）。
 */
class PermissionGate(private val isManager: () -> Boolean) {
    /** 委托给注入的提供器，每次调用都重新求值——不缓存。 */
    fun granted(): Boolean = isManager()

    companion object {
        /**
         * 生产用的薄工厂：读 `Environment.isExternalStorageManager()`，低于 API 30 直接放行。
         * 参数 `context` 保留以匹配调用点（未来若要按包名查询可在此展开），当前不读取。
         */
        @Suppress("UNUSED_PARAMETER")
        fun of(context: Context): PermissionGate = PermissionGate {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
        }

        /** vivo 上可能落不到系统页，调用方需在跳转后回查 granted()（spec R2）。 */
        fun requestIntent(context: Context): Intent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                )
            } else {
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                )
            }
    }
}
