package dev.zayn.vlive

import android.os.Bundle
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.zayn.vlive.R
import dev.zayn.vlive.perm.PermissionGate
import dev.zayn.vlive.ui.IosEntrance
import dev.zayn.vlive.ui.IosMotion
import dev.zayn.vlive.ui.IosTheme

/**
 * 唯一的 Activity。清单里 `android:name=".MainActivity"` 的点号前缀展开的是 namespace
 * （`dev.zayn.vlive`），所以完整组件名是 `dev.zayn.vlive/dev.zayn.vlive.MainActivity`
 * （真机验收的 `am start` 用这一串）。这个类必须留在 `dev.zayn.vlive` 包下，别跟着
 * `FileStorage`/`DirBrowser` 那些子包搬。
 */
class MainActivity : ComponentActivity() {

    private val vm: BatchViewModel by viewModels {
        viewModelFactory { initializer { BatchViewModel(this@MainActivity.application) } }
    }

    /** 入场闸门：splash 退出动画放完（或超时兜底）之后，`BatchScreen` 的各段才交错上浮。 */
    private val entrance = IosEntrance()

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super.onCreate 之前装 splash：它是 window 级的，晚一步就拦不到首帧。
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // 显式换回运行时主题。`core-splashscreen` 在 31+ 走系统分支；显式 `setTheme` 保留，
        // 是为了不把恢复时机压在库的内部行为上。
        setTheme(R.style.Theme_Vlive)
        // logo 淡出 + 轻微收缩，落点交给 BatchScreen 的交错入场接住（同一条 220ms 时长）。
        splash.setOnExitAnimationListener { splashView ->
            splashView.view
                .animate()
                .alpha(0f)
                .scaleX(0.94f)
                .scaleY(0.94f)
                .setDuration(IosMotion.EXIT_MS.toLong())
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    splashView.remove()
                    entrance.release()
                }
                .start()
        }
        // 控件层是自己画的，但 MaterialTheme 还得留着：Text 的兜底色、Dialog 的表面色都从它取，
        // 给它 iOS 色板才不会被 M3 默认配色漏出来。
        // enableEdgeToEdge() 刻意**不**开：计划没要求，而 BatchScreen 已经在窗口层用
        // WindowInsets.safeDrawing 手动避让；真机（targetSdk 36 + API 37）本来就强制 edge-to-edge，
        // 在这里再叠一层只会把已审过的 insets 行为改掉。
        setContent {
            IosTheme {
                BatchScreen(vm, entrance = entrance, request = {
                    // vivo 上 ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION 不一定落得到系统页（spec R2）：
                    // 跳不动就退化成手动指引的 Toast，回到前台时 onResume 会再回查一次真实状态。
                    runCatching {
                        startActivity(PermissionGate.requestIntent(this@MainActivity))
                    }.onFailure {
                        Toast.makeText(this@MainActivity, R.string.perm_toast, Toast.LENGTH_LONG).show()
                    }
                    vm.refreshPermission()
                })
            }
        }
    }

    /** 从系统授权页回来就重读一次：授权状态只能在运行时问，缓存会一直说谎。 */
    override fun onResume() {
        super.onResume()
        vm.refreshPermission()
    }
}
