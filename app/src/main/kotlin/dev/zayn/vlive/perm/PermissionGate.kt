package dev.zayn.vlive.perm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings

/**
 * 全部文件访问权限（`MANAGE_EXTERNAL_STORAGE`）的门面。
 *
 * ## 为什么必须从注入的 `() -> Boolean` 构造
 * 真实状态来自 `Environment.isExternalStorageManager()`。障碍不是 API 档位（`minSdk = 31`，
 * 这个方法无条件可用），而是**本地 JVM 测试里没有 Android 运行时**：那是一个要问系统服务的静态调用，
 * 脱离设备它压根不存在。把它做成构造参数，`granted()` 就退化成一次纯 Kotlin 委托，
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
 * ## 为什么这里没有 API 判定
 * `minSdk = 31`，`Environment.isExternalStorageManager()`（API 30 起有）无条件可用，所以
 * 曾经那句 `Build.VERSION.SDK_INT < R ||` 短路已经删掉。曾经留着它的理由是"minSdk 30 的双保险"，
 * 那条理由随 minSdk 一起没了（见 docs/decisions.md §5.12 的更新与 §5.20）。
 * 注意 API 常量若要再写，得是 `Build.VERSION_CODES.R`，不是不存在的 `Build.VERSION.R`。
 */
class PermissionGate(private val isManager: () -> Boolean) {
    /** 委托给注入的提供器，每次调用都重新求值——不缓存。 */
    fun granted(): Boolean = isManager()

    companion object {
        /**
         * 生产用的薄工厂：直接读 `Environment.isExternalStorageManager()`，无条件、无分支。
         * 参数 `context` 保留以匹配调用点（未来若要按包名查询可在此展开），当前不读取。
         */
        @Suppress("UNUSED_PARAMETER")
        fun of(context: Context): PermissionGate = PermissionGate {
            Environment.isExternalStorageManager()
        }

        /** vivo 上可能落不到系统页，调用方需在跳转后回查 granted()（spec R2）。 */
        fun requestIntent(context: Context): Intent =
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
    }
}
