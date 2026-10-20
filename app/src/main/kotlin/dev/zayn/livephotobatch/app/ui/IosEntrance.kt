package dev.zayn.livephotobatch.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 入场闸门：内容在 splash 的退出动画放完之前停在 `alpha = 0`，两段动画才接得上——
 * 否则卡片在 logo 背后就已经演完了，用户看到的只是"闪一下然后界面已经在屏幕上"。
 *
 * [release] 有两条来路：MainActivity 的 splash 退出监听，和 [entranceFallback] 那条超时。
 * 超时不是多余的防御：OEM 对 splash 的分支不少，监听器万一不回叫，没有兜底整屏就**永久**停在
 * 看不见的位置上。看不见动画可以接受，看不见界面不行。
 */
class IosEntrance {
    var released by mutableStateOf(false)
        private set

    fun release() {
        released = true
    }
}

/**
 * 超时兜底：[IosMotion.RELEASE_TIMEOUT_MS] 之后无条件放行。放在 `BatchScreen` 里跑一次就够，
 * 不要每个 section 各起一条。名字走 Compose 约定（返回 Unit 的组合函数用大驼峰）。
 */
@Composable
fun IosEntranceFallback(entrance: IosEntrance) {
    LaunchedEffect(entrance) {
        delay(IosMotion.RELEASE_TIMEOUT_MS)
        entrance.release()
    }
}

/**
 * 第 [index] 段的入场：淡入 + [IosMotion.RISE_DP] dp 上浮，按 [IosMotion.STAGGER_MS] ms 交错。
 *
 * 这里**没有**读 `Settings.Global.ANIMATOR_DURATION_SCALE`：那是一次跨进程查询，与本仓库
 * 「组合期不碰 binder / 磁盘」（见 `BatchScreen` 的 KDoc）这条约定冲突，所以留给平台去折算。
 * 用户在系统里关掉动画时这段到底会不会归零，装机确认过再补断言 —— 别把没验过的事写进注释。
 *
 * 挂在 `Modifier` 上而不是包一层容器：`SectionCard` 自己已经带 `padding`/`clip`/`background`，
 * 再套一层 Column 会改变卡片间距的归属，交错就变成"整屏一起动"。
 */
@Composable
fun Modifier.iosEnter(index: Int, entrance: IosEntrance): Modifier {
    val progress by animateFloatAsState(
        targetValue = if (entrance.released) 1f else 0f,
        animationSpec = tween(
            durationMillis = IosMotion.ENTER_MS,
            delayMillis = index * IosMotion.STAGGER_MS,
            easing = IosMotion.EaseOut,
        ),
        label = "sectionEnter",
    )
    val density = LocalDensity.current
    return this
        .alpha(progress)
        // lambda 重载：位移在绘制阶段读状态，而不是每次进度变化都重组一遍这棵子树。
        .offset {
            IntOffset(0, with(density) { ((1f - progress) * IosMotion.RISE_DP).dp.roundToPx() })
        }
}
