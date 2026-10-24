package dev.zayn.vlive.perm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings

/**
 * 全部文件访问权限（`MANAGE_EXTERNAL_STORAGE`）的门面。
 *
 * ## 为什么从注入的 `() -> Boolean` 构造
 * 真实状态来自 `Environment.isExternalStorageManager()`——一个要问系统服务的静态调用，脱离 Android
 * 运行时它压根不存在。做成构造参数之后 [granted] 就是一次纯 Kotlin 委托，`app/src/test` 里的本地 JVM
 * 单测不引 Robolectric、不引新依赖就能驱动，ViewModel 也用假 lambda 测。[of] 是那层薄适配器。
 *
 * ## 与 `FileStorage` / `DirBrowser` 共享的 root 字符串契约
 * 三者用的是同一个未规范化的 `"/storage/emulated/0"`：不带尾斜杠、绝不空。这条必须守，因为
 * `FileStorage.resolve()` 判定 `path == root || path.startsWith("$root/")` 才走原样分支，一旦给
 * root 加尾斜杠（或传空串），`"/storage/emulated/0"` 本身就会被当成相对路径拼一遍，变成
 * `/storage/emulated/0/storage/emulated/0`——`list(root)` 静默返回空、`ensureDir(root)` 在真实存储里
 * 种一棵垃圾树。本类不直接读文件，但它是这条 root 契约被建立的入口（跳系统页 → 回到
 * `granted() == true` → 才允许存储层开工），所以在此写死以防被误改。
 *
 * ## 这里没有 API 判定
 * `Environment.isExternalStorageManager()` 自 API 30 起存在，`minSdk = 31` 下无条件可用，所以不需要
 * 版本分支；哪天 minSdk 降到 30 以下，才需要把短路判断补回来。API 常量若要写，是
 * `Build.VERSION_CODES.R`，不存在 `Build.VERSION.R`。
 */
class PermissionGate(private val isManager: () -> Boolean) {
    /** 委托给注入的提供器，每次调用都重新求值——不缓存。 */
    fun granted(): Boolean = isManager()

    companion object {
        /** 生产用的工厂：直接读 `Environment.isExternalStorageManager()`，无条件、无分支。 */
        fun of(): PermissionGate = PermissionGate { Environment.isExternalStorageManager() }

        /** 部分 ROM（vivo）上这个 intent 可能落不到系统页，调用方跳转后必须回查 [granted]。 */
        fun requestIntent(context: Context): Intent =
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
    }
}
