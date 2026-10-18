package dev.zayn.livephotobatch.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.zayn.livephotobatch.app.perm.PermissionGate

/**
 * 唯一的 Activity。清单里声明的是 `.app.MainActivity`，而 `android:name` 的点号前缀展开的是
 * namespace（`dev.zayn.livephotobatch`），所以完整组件名是
 * `dev.zayn.livephotobatch/dev.zayn.livephotobatch.app.MainActivity`（Task 12 的 `am start` 用这一串）。
 * 这个类必须留在 `dev.zayn.livephotobatch.app` 包下，别跟着 `FileStorage`/`DirBrowser` 那些子包搬。
 */
class MainActivity : ComponentActivity() {

    private val vm: BatchViewModel by viewModels {
        viewModelFactory { initializer { BatchViewModel(this@MainActivity.application) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BatchScreen(vm, request = {
                // vivo 上 ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION 不一定落得到系统页（spec R2）：
                // 跳不动就退化成手动指引的 Toast，回到前台时 onResume 会再回查一次真实状态。
                runCatching {
                    startActivity(PermissionGate.requestIntent(this@MainActivity))
                }.onFailure {
                    Toast.makeText(
                        this@MainActivity,
                        "请在 设置 → 应用 → 特殊访问 → 所有文件访问 中手动开启",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                vm.refreshPermission()
            })
        }
    }

    /** 从系统授权页回来就重读一次：授权状态只能在运行时问，缓存会一直说谎。 */
    override fun onResume() {
        super.onResume()
        vm.refreshPermission()
    }
}
