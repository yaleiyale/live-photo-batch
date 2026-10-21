package dev.zayn.vlive.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * 上次选过的两个目录。可空 = "没存过"，与"存了但不可用"（[dev.zayn.vlive.BatchViewModel.isSelectable]
 * 挡掉的那种）是两件事，后者由调用方决定怎么处置。
 *
 * **删除开关刻意不在这里。** 它是唯一会动原文件的那一项，记住它意味着"某次勾了一下，之后每次冷启动
 * 都悄悄带着它"—— 而需求要记的是"目录选择结果"。所以它每次启动都回到关，只有两个路径被持久化。
 */
data class StoredPaths(val sourceDir: String? = null, val targetDir: String? = null)

/**
 * [dev.zayn.vlive.BatchViewModel] 看得见的那一面。
 *
 * 存在的理由和 `PermissionGate` / 注入的 `scanner` 是同一条：VM 要能在纯 JVM 上被单测驱动，
 * 所以它只认这个接口，不认 `Context`。生产实现是下面那个 DataStore 版；测试给内存假实现。
 */
interface BatchSettings {
    /** 读回上次的选择。**绝不抛**：读不出来就等于"没存过"，不能让一次 IO 故障掀掉整个界面。 */
    suspend fun load(): StoredPaths

    /** 写回选择。**同样绝不抛**：存不进去只是下次启动回到默认路径，不该打断已经改好的内存状态。 */
    suspend fun save(paths: StoredPaths)
}

/**
 * 文件名的主体（`vlive.preferences_pb`）。DataStore 自己加后缀，所以这里给的是不带后缀的名字。
 *
 * `preferencesDataStore` 这个委托**必须挂在进程唯一的东西上**（这里是 `Context` 的扩展属性，
 * 实际存储是首次访问时创建的单例）：同一个文件开两个实例会撞
 * "There are multiple DataStores active for the same file"。
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vlive")

/** [BatchSettings] 的生产实现：Preferences DataStore，写在应用私有目录里（不需要任何存储权限）。 */
class PreferencesBatchSettings(private val context: Context) : BatchSettings {

    override suspend fun load(): StoredPaths = try {
        val prefs = context.dataStore.data.first()
        StoredPaths(prefs[SOURCE_DIR], prefs[TARGET_DIR])
    } catch (e: IOException) {
        Timber.w(e, "settings load failed, falling back to defaults")
        StoredPaths()
    }

    override suspend fun save(paths: StoredPaths) {
        try {
            context.dataStore.edit { prefs ->
                // 空串 = 删掉这一条，而不是留下一条"指向空目录"的记录；load() 之后就是 null。
                paths.sourceDir?.let { prefs[SOURCE_DIR] = it } ?: prefs.remove(SOURCE_DIR)
                paths.targetDir?.let { prefs[TARGET_DIR] = it } ?: prefs.remove(TARGET_DIR)
            }
        } catch (e: IOException) {
            Timber.w(e, "settings save failed")
        }
    }

    private companion object {
        val SOURCE_DIR = stringPreferencesKey("source_dir")
        val TARGET_DIR = stringPreferencesKey("target_dir")
    }
}
