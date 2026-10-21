# Liquid Glass 界面改造实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 vlive 的三条浮动控件改成真玻璃（Haze 背景模糊 + 着色 + 高光 + 描边），卡片保持实心面，并为此把 `minSdk` 抬到 31。

**Architecture:** 表现层新增一个 `GlassMaterial` 令牌层（与 `IosPalette` 并列、由 `IosTheme` 同一次深浅判定发出）和一条 `Modifier.iosGlassBar()`；`BatchScreen` 从"一层滚动 Column"改成"内容层（haze source）+ 顶部玻璃栏 + 底部玻璃栏"三层，inset 归属由 `windowInsetsPadding` 改为 `contentPadding`。目录弹窗在 `Dialog` 这个独立窗口里注册**第二个** haze source。

**Tech Stack:** Kotlin 2.4.21、AGP 9.4.1、Compose BOM 2026.09.00（= compose 1.12.1）、`dev.chrisbanes.haze:haze` / `:haze-blur` 2.0.1、JUnit 4（`:app` 的 JVM 单测用的就是 junit4，见 `app/build.gradle.kts:63`）。

**Spec:** [docs/superpowers/specs/2026-10-21-liquid-glass-ui-design.md](../specs/2026-10-21-liquid-glass-ui-design.md) —— 本计划逐条实现它，验收判据也在那里（§3.4）。执行时两份一起读。

## Global Constraints

以下每一条对**每个任务**都生效，值一律从 spec 原样抄来：

- `minSdk = 31`（原 30），`targetSdk = 36`、`compileSdk = 37` / `compileSdkMinor = 0`、JVM 17 —— 全部不许顺手改动。
- 依赖只允许新增 `dev.chrisbanes.haze:haze:2.0.1` 与 `dev.chrisbanes.haze:haze-blur:2.0.1`；**不引入** `haze-blur-material3`、`haze-glass`（后者要 `RenderEffect` 的 runtime shader，属于另一套自绘管线，spec §0 已排除自绘路线）。
- 玻璃只给三个 bar 区：主屏顶部标题栏、主屏底部操作栏、弹窗顶栏。**卡片一律实心**，条内按钮**不再各自糊一层**。
- accent 四个值：浅常规 `#007AFF`、浅高对比 `#0040DD`、深常规 `#0A84FF`、深高对比 `#66B3FF`。控件着色只给主操作 / 选中 / 需要注意三种状态。
- 两套玻璃预设由外观自动选，**不做**用户可见的强度控制；参数只许改 spec §1.3 那张表，调用点不许写散装 alpha。
- 不做模糊半径动画；保留 `IosEntrance` 交错与 `IosMotion` 全部时长。
- 深浅判定只有 `IosTheme` 一处，不许出现第二个 `isSystemInDarkTheme()`。
- 这一轮**不许碰窗口级主题**（`values*/themes.xml` 里 `Theme.Vlive.Starting` 的颜色项），冷启动 splash 的 OEM 换色问题已结案（decisions.md §5.16）。
- `:core` 的 145 条测试与 8 条 instrumented 测试不许动；改到了就是越界。
- 每个任务结束时：`:app` JVM 测试全绿、`:app:lintDebug` 除 spec §3.2 那条 12→9 之外零新增、能 `assembleDebug`。
- 提交只在任务边界做，消息用仓库现有风格（`feat(app): …` / `chore(deps): …` / `docs: …`）。**推送要单独问过用户。**

## 已核实的事实（Task 1 之外不必再查）

从 Maven Central 上 `haze-android-2.0.1-sources.jar` 与 `haze-blur-android-2.0.1-sources.jar` 里直接读出来的真签名（不是照文档抄的）：

```kotlin
// dev.chrisbanes.haze（artifact: haze）
@Stable fun rememberHazeState(): HazeState                                  // Haze.kt:32
@Stable fun Modifier.hazeSource(state: HazeState, zIndex: Float = 0f, key: Any? = null)  // Haze.kt:238
sealed interface HazeInput { data class Sources(state, selection = Behind, retention = KeepLastFrame); data class Backdrop(fallback); data object Content }  // HazeInput.kt

// dev.chrisbanes.haze.blur（artifact: haze-blur）
fun Modifier.hazeBlur(input: HazeInput, style: HazeBlurStyle = HazeBlurStyle,
                      performanceMode: HazePerformanceMode? = null, expandLayerBounds: Boolean = true)  // HazeBlur.kt:29
fun HazeBlurStyle(block: HazeBlurStyleScope.() -> Unit): HazeBlurStyle      // HazeBlurStyle.kt:68
//   scope 方法：blurEnabled(Boolean) / blurRadius(Dp) / noiseFactor(Float) / backgroundColor(Color)
//               colorEffects(List<HazeColorEffect>) / fallbackColorEffect(…) / alpha(Float) / mask(Brush?) / progressive(…) / blurredEdgeTreatment(…)
HazeColorEffect.Companion.tint(color: Color, blendMode: BlendMode = SrcOver) // HazeBlurStyle.kt:326
HazeBlurDefaults.blurRadius = 20.dp / noiseFactor = 0.15f                    // HazeBlurDefaults.kt
```

三个对本计划有决定影响的结论：

1. **2.0.1 里消费者 modifier 叫 `hazeBlur`，不是 `hazeEffect`**（`hazeEffect` 还在，但它是吃 `factory` 的底层泛型版本）。spec §3.1 记的那个不一致点 hereby 结案。
2. **`tint` 不是一个 style 方法**，它是 `HazeColorEffect.tint(color)` 工厂，要经 `colorEffects(listOf(...))` 传进去。
3. `haze-blur-android` 的 POM 依赖 `compose ui-android 1.12.1`，与本仓库 BOM 2026.09.00 解析出的版本**完全一致**，`checkDebugAarMetadata` 那道历史上咬过版本的坎不会重现。
4. `platformIsBlurEnabledByDefault()` 在 androidMain 里的实现是 `Build.VERSION.SDK_INT >= 31` —— **库自己在 API 30 上默认关掉模糊**。这是 minSdk 抬到 31 最硬的一条外部证据，Task 2 要把它写进 decisions.md。

---

## 文件结构（改动地图）

| 文件 | 职责 | 任务 |
| --- | --- | --- |
| `gradle/libs.versions.toml`、`app/build.gradle.kts` | Haze 两个构件；`minSdk`；两条注释 | 1、2 |
| `app/src/main/kotlin/dev/zayn/vlive/ui/IosStyle.kt` | 新增 `GlassMaterial` + 两套预设 + `LocalIosGlass` + `accentFor`；`IosTheme` 发出玻璃；`IosMetrics` 加 6 个几何常量 | 3、4 |
| `app/src/main/kotlin/dev/zayn/vlive/ui/GlassBar.kt`（新） | `Modifier.iosGlassBar()` 一条：糊一次、着色、高光、描边、阴影 | 4 |
| `app/src/main/kotlin/dev/zayn/vlive/ui/TitleBarAlpha.kt`（新） | `titleBarAlpha(scrollY, collapseRange)` 纯函数 | 5 |
| `app/src/test/kotlin/dev/zayn/vlive/ui/AccentForTest.kt`（新） | 四个 accent 值 + 选择规则 | 3 |
| `app/src/test/kotlin/dev/zayn/vlive/ui/TitleBarAlphaTest.kt`（新） | §3.3 那 6 条 | 5 |
| `app/src/main/kotlin/dev/zayn/vlive/ui/IosControls.kt` | `IosButton` 改 Tinted/清澈玻璃；`IosSheet` 的第二个 source + 叠层几何 | 4、8 |
| `app/src/main/kotlin/dev/zayn/vlive/BatchScreen.kt` | 三层结构、haze source、两条 bar、inset 分工 | 6、7 |
| `perm/PermissionGate.kt`、`MainActivity.kt`、`AndroidManifest.xml`、`values/themes.xml`、`mipmap-anydpi-v26/ic_launcher.xml` | minSdk 连带（spec §3.2 的 #2–#9） | 2 |
| `.github/workflows/release.yml`、`docs/testing.md`、`docs/decisions.md`、`docs/architecture/*` | minSdk 连带（#10–#13）+ 验收数字入档 | 2、9 |

---

## Task 1: Haze 依赖接入 + API 探针（切片 ① 前半）

**Files:**
- Modify: `gradle/libs.versions.toml`（`[versions]` 与 `[libraries]`）
- Modify: `app/build.gradle.kts:48-60`（`dependencies` 块）
- Create（临时，验完删）: `app/src/main/kotlin/dev/zayn/vlive/ui/HazeProbe.kt`

**Interfaces:**
- Consumes: 无
- Produces: 类路径上有 `dev.chrisbanes.haze.{HazeState, rememberHazeState, Modifier.hazeSource, HazeInput}` 与 `dev.chrisbanes.haze.blur.{Modifier.hazeBlur, HazeBlurStyle, HazeColorEffect}`

- [ ] **Step 1: 加依赖**

`gradle/libs.versions.toml` 的 `[versions]` 里 `timber = "5.0.1"` 之后加一行：

```toml
haze = "2.0.1"
```

`[libraries]` 里 `timber = { … }` 之后加两行（两个构件都要：source 侧在 `haze`，consumer 侧在 `haze-blur`）：

```toml
haze-core = { group = "dev.chrisbanes.haze", name = "haze", version.ref = "haze" }
haze-blur = { group = "dev.chrisbanes.haze", name = "haze-blur", version.ref = "haze" }
```

`app/build.gradle.kts` 的 `implementation(libs.timber)` 之后加：

```kotlin
    // 真背景模糊（RenderEffect，API 31+）。source 侧与 consumer 侧是两个构件，见 docs/decisions.md §5.20。
    implementation(libs.haze.core)
    implementation(libs.haze.blur)
```

- [ ] **Step 2: 确认解析到的真实版本与变体**

Run: `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && ./gradlew :app:dependencies --configuration debugCompileClasspath | grep -i haze`
Expected: 出现 `dev.chrisbanes.haze:haze-android:2.0.1` 与 `dev.chrisbanes.haze:haze-blur-android:2.0.1`，且没有 `-> 其它版本` 的降级/升级冲突行。若出现冲突，把两行原样贴进给用户的汇报里再决定，不许就地 `exclude`。

- [ ] **Step 3: 写探针，把真签名钉死**

创建 `app/src/main/kotlin/dev/zayn/vlive/ui/HazeProbe.kt`，内容只有编译探针，没有任何调用方：

```kotlin
package dev.zayn.vlive.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

/** 编译探针：只为钉死 Haze 2.0.1 的真签名，Task 4 落地后立刻删除本文件。 */
@Composable
internal fun HazeProbe() {
    val state = rememberHazeState()
    Column(Modifier.hazeSource(state)) {}
    Column(
        Modifier.hazeBlur(
            input = HazeInput.Sources(state),
            style = HazeBlurStyle {
                blurRadius(24.dp)
                colorEffects(listOf(HazeColorEffect.tint(androidx.compose.ui.graphics.Color.Red)))
            },
        ),
    ) {}
}
```

- [ ] **Step 4: 编译**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`。若报 unresolved，按报错改**探针**（不是改 spec），改完把真签名同步进 spec §3.1 与 decisions.md §5.20，再继续。

- [ ] **Step 5: 测试仍然全绿**

Run: `./gradlew :app:testDebugUnitTest :core:test`
Expected: `:app` 51 条、`:core` 145 条，0 失败。（新构件不该动任何行为，这一步是防止依赖图意外改了 compose 版本。）

- [ ] **Step 6: 删掉探针并提交**

```bash
rm app/src/main/kotlin/dev/zayn/vlive/ui/HazeProbe.kt
./gradlew :app:assembleDebug   # 确认没有残留引用
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "chore(deps): add Haze for real backdrop blur"
```

---

## Task 2: `minSdk` 抬到 31 + 全部连带（切片 ① 后半）

一个任务吃掉 spec §3.2 的 13 项，因为它们是同一次决定带走的东西：漏任何一项，文档就开始说谎。

**Files:**
- Modify: `app/build.gradle.kts:23`、`:53`
- Modify: `app/src/main/kotlin/dev/zayn/vlive/perm/PermissionGate.kt:29-33`、`:41`、`:46`、`:51-61`
- Modify: `app/src/main/AndroidManifest.xml:7-8`
- Modify: `app/src/main/kotlin/dev/zayn/vlive/MainActivity.kt:37`
- Modify: `app/src/main/res/values/themes.xml:16`、`:20`
- Modify: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml:3`
- Modify: `.github/workflows/release.yml:78`、`docs/testing.md:141`
- Modify: `docs/architecture/system-model.md:44`、`:88`、`docs/architecture/system-model.evidence.md:103`、`docs/architecture/vlive.structurizr.dsl:74`、`:88`、`docs/architecture/batch-flow.dot:29`（+ 用 `/tmp/viz` 里的 `@viz-js/viz` 重渲染 `batch-flow.svg`：把 `.dot` 与 `render-svg.mjs` 拷进 `/tmp/viz`，`cd /tmp/viz && node render-svg.mjs batch-flow.dot`，再把生成的 `.svg` 拷回 `docs/architecture/`）
- Modify: `docs/decisions.md:291`、`:297`、`:302`，并新增 §5.20

**Interfaces:**
- Consumes: Task 1 的依赖（`RenderEffect` 模糊在 API 31 才默认开启）
- Produces: `PermissionGate.of()` 与 `requestIntent()` 的单分支实现；`minSdk = 31`

- [ ] **Step 1: 先记下 lint 基线，改完才有对照**

Run: `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && ./gradlew :app:lintDebug && node -e "const s=JSON.parse(require('fs').readFileSync('app/build/reports/lint-results-debug.sarif','utf8')).runs[0].results;console.log('total',s.length,'ObsoleteSdkInt',s.filter(x=>x.ruleId==='ObsoleteSdkInt').length)"`
Expected: `total 12 ObsoleteSdkInt 3`（本轮已实读确认：位置是 `PermissionGate.kt:46`、`PermissionGate.kt:51`、`mipmap-anydpi-v26`）。

- [ ] **Step 2: 改 minSdk 与两条注释**

`app/build.gradle.kts:23`：`minSdk = 30` → `minSdk = 31`。
`app/build.gradle.kts:53` 那行注释里的"API 30 上也能覆盖"改成"API 31 上也能覆盖"（整句保留，只换版本号）。

- [ ] **Step 3: 删 PermissionGate 的两条死分支**

`:46` 现在是：

```kotlin
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
```

改成（minSdk 31 下前半句恒假）：

```kotlin
            Environment.isExternalStorageManager()
```

`:50-61` 的 `requestIntent` 现在是 `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { A } else { B }`，后半句在 minSdk 31 下永不到达，整块换成：

```kotlin
        /** vivo 上可能落不到系统页，调用方需在跳转后回查 granted()（spec R2）。 */
        fun requestIntent(context: Context): Intent =
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
```

删掉因此不再使用的 `import android.os.Build`。

- [ ] **Step 4: 重写 PermissionGate 里两段已经说过谎的 KDoc**

`:29-33` 整节（标题"## 短路与 API 30"）替换为：

```
 * ## 为什么这里没有 API 判定
 * `minSdk = 31`，`Environment.isExternalStorageManager()`（API 30 起有）无条件可用，所以
 * 曾经那句 `Build.VERSION.SDK_INT < R ||` 短路已经删掉。曾经留着它的理由是"minSdk 30 的双保险"，
 * 那条理由随 minSdk 一起没了（见 docs/decisions.md §5.12 的更新与 §5.20）。
 * 注意 API 常量若要再写，得是 `Build.VERSION_CODES.R`，不是不存在的 `Build.VERSION.R`。
```

`:41` 工厂的 KDoc 里"低于 API 30 直接放行"改为"直接读 `isExternalStorageManager()`"。

- [ ] **Step 5: 删掉永不上号的死权限**

`app/src/main/AndroidManifest.xml` 删这两行（`minSdk 31` 下 `maxSdkVersion="29"` 永不生效）：

```xml
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="29" />
```

上面那条 `READ_EXTERNAL_STORAGE`（`maxSdkVersion="32"`）**保留**，31–32 仍归它管。

- [ ] **Step 6: 同步其余四处注释**

- `MainActivity.kt:37`：把"但 API 30 走 compat、31+ 走系统两条分支"改为"`core-splashscreen` 在 31+ 走系统分支；显式 `setTheme` 保留，是为了不把恢复时机压在库的内部行为上"。
- `values/themes.xml:16` 与 `:20`：两处 "API 30" 说法改成 31（句子结构不动）。
- `mipmap-anydpi-v26/ic_launcher.xml:3`：`minSdk 30 ≥ 26` → `minSdk 31 ≥ 26`。`-v26` 目录本身**不删**（decisions §5.12 的构建破坏结论与 minSdk 无关）。

- [ ] **Step 7: 构建 + 测试 + lint**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`
Expected: 51 条全绿（`PermissionGateTest` 那 2 条只测注入的 lambda，删分支不破测）；lint 变成 **`total 9 ObsoleteSdkInt 1`** —— 这两个数字就是本任务的完成证据，没达到不要往下走。（写在计划里时预测的是 10；Task 2 实测 9 —— 被删的 `else` 分支还带走一条 `UseKtx`。数字以实测为准。）

- [ ] **Step 8: 文档连带（#10–#12）**

- `release.yml:78`：`装之前：Android 11+（minSdk 30）` → `装之前：Android 12+（minSdk 31）`。
- `docs/testing.md:141`：`Android 11+ 设备` → `Android 12+ 设备`。
- `docs/architecture/system-model.md:44`：`设备要求：\`minSdk 30\`` → `31`；`:88` 的"低于 API 30 短路放行"改为"`minSdk 31`，无条件调用 `isExternalStorageManager()`"。
- `docs/architecture/system-model.evidence.md:103`：同一句话同步；行号锚点改成删分支之后的真实行（用 `grep -n` 定位，别照旧值写）。
- `docs/architecture/vlive.structurizr.dsl:74` 的 `"Kotlin / Compose / AGP 9.4.1 / minSdk 30"` → `minSdk 31`；`:88` 的 `PermissionGate` 描述去掉"低于 API 30 短路放行"。
- Run: `node docs/architecture/dsl-check.mjs` → Expected: 无结构错误退出 0。

- [ ] **Step 9: decisions.md 改写 + 新增 §5.20**

`:297` 里"尽管 `minSdk 30` 让 lint 建议" → `minSdk 31`。`:302` 那句"…会连带把 `PermissionGate.kt:46/51` 那两条…一起静音，而那是有意保留的双保险"必须改写：那两条现在已经不存在，句子改成说明"minSdk 抬到 31 后这两个分支被删除、该 findings 消失，剩下的唯一一条 `ObsoleteSdkInt` 就是这里说的 `-v26`，仍然不能照它改"。

在 §5.18 之后新增一节：

```markdown
### 5.20 minSdk 抬到 31：真模糊的门槛，也是它带走了分支（2026-10-21 增量）

**决定**：`minSdk 30 → 31`，且**不为 API 30 保留任何降级路径**。

**为什么**：`RenderEffect` 模糊是 API 31 起的；Haze 2.0.1 的 androidMain 里
`platformIsBlurEnabledByDefault() = Build.VERSION.SDK_INT >= 31` —— **库自己在 30 上默认关掉模糊**，
只剩一块半透明遮罩。与其维护"30 上是另一种观感"这条分支，不如把地板抬过去。
判据来自构件源码，不是文档转述。

**代价**：`PermissionGate` 的两条 below-API-30 分支变成死代码并删除（连带 §5.12 那句"双保险"作废，
lint 基线 12 → 9、`ObsoleteSdkInt` 3 → 1）；`WRITE_EXTERNAL_STORAGE maxSdkVersion="29"` 变成永不上号的
死声明，删；`READ_EXTERNAL_STORAGE maxSdkVersion="32"` 保留（31–32 仍归它管）。
设备侧：Android 11 的原装机器从此装不上，当前 V2419A 满足。
```

- [ ] **Step 10: 提交**

```bash
git add -A
git commit -m "feat(app): raise minSdk to 31 for real backdrop blur and delete the dead API 30 branches"
```

---

## Task 3: accent 四个值（切片 ② 第一半，纯函数）

**Files:**
- Modify: `app/src/main/kotlin/dev/zayn/vlive/ui/IosStyle.kt`（`accentFor` + `IosTheme` 接高对比 + 注释）
- Create: `app/src/test/kotlin/dev/zayn/vlive/ui/AccentForTest.kt`

**Interfaces:**
- Consumes: 无
- Produces: `fun accentFor(dark: Boolean, highContrast: Boolean): Color`；`IosStyle.blue` 在开启高对比文字时返回高对比值，**其余 11 处 `s.blue` 调用点不改**

- [ ] **Step 1: 写失败的测试**

`app/src/test/kotlin/dev/zayn/vlive/ui/AccentForTest.kt`（`:app` 的 JVM 单测是 junit4 + `org.junit.Assert`，与 `ReportPageTest.kt:7` 一致）：

```kotlin
package dev.zayn.vlive.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * accent 必须一次给四个值（浅/深 × 常规/高对比），这是 spec §1.5 的硬要求。
 * 这里只测"取值函数"，不测 Compose 的渲染 —— 后者在 JVM 单测里取不到。
 */
class AccentForTest {

    @Test
    fun `four canonical values`() {
        assertEquals(Color(0xFF007AFF), accentFor(dark = false, highContrast = false))
        assertEquals(Color(0xFF0040DD), accentFor(dark = false, highContrast = true))
        assertEquals(Color(0xFF0A84FF), accentFor(dark = true, highContrast = false))
        assertEquals(Color(0xFF66B3FF), accentFor(dark = true, highContrast = true))
    }

    @Test
    fun `high contrast differs from regular in both appearances`() {
        assertNotEquals(accentFor(false, false), accentFor(false, true))
        assertNotEquals(accentFor(true, false), accentFor(true, true))
    }

    @Test
    fun `regular values match the palettes the rest of the UI already uses`() {
        // 抬到四个值不能顺手改掉现状：常规态必须与 IosLightPalette/IosDarkPalette 的 blue 同值。
        assertEquals(IosLightPalette.blue, accentFor(dark = false, highContrast = false))
        assertEquals(IosDarkPalette.blue, accentFor(dark = true, highContrast = false))
    }
}
```

- [ ] **Step 2: 跑到失败**

Run: `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && ./gradlew :app:testDebugUnitTest --tests "dev.zayn.vlive.ui.AccentForTest"`
Expected: 编译失败（`accentFor` unresolved）—— 这就是"先红"。

- [ ] **Step 3: 实现**

`IosStyle.kt` 里，紧跟 `val IosDarkPalette = IosPalette(...)` 之后加：

```kotlin
/**
 * accent 的四个值：浅/深 × 常规/高对比（spec §1.5）。
 *
 * 常规两列就是色板里原本的两个值，一个都没改；高对比那两列是本轮定的**起点**，不是苹果公开数值的
 * 引用，判据是硬指标：Tinted 主按钮上的白字、玻璃 bar 上的 accent 字在各自外观下 ≥ 4.5:1。
 */
fun accentFor(dark: Boolean, highContrast: Boolean): Color = when {
    !dark && !highContrast -> Color(0xFF007AFF)
    !dark -> Color(0xFF0040DD)
    !highContrast -> Color(0xFF0A84FF)
    else -> Color(0xFF66B3FF)
}
```

- [ ] **Step 4: 让 `blue` 走这条路径**

`IosTheme`（`IosStyle.kt:196-206`）里现在这两行：

```kotlin
    val dark = isSystemInDarkTheme()
    val palette = if (dark) IosDarkPalette else IosLightPalette
```

替换为：

```kotlin
    val dark = isSystemInDarkTheme()
    // 系统"高文字对比度"开关，取法见 spec §1.5。第一次读可能触发一次 IPC（之后 AccessibilityManager
    // 自己缓存），所以整段包在 remember 里、每个进程一次，不在每次重组时读 —— BatchScreen 那条
    // "合成期不碰 binder/磁盘"的约定靠这个 remember 守住，不是靠假设这个 getter 便宜。
    // 已知限制：开关运行中被翻动不会立刻重组，下一次配置变化/主题重建才生效，内部工具接受这个代价。
    val context = LocalContext.current
    // 每个主题实例一次（不是每个进程一次：keying 在 context 上，Activity 重建时会重新求值，
    // 那正好也是 dark 该重新判定的时刻）。
    val highContrast = remember(context) {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) am.isHighContrastTextEnabled else false
    }
    val palette = (if (dark) IosDarkPalette else IosLightPalette).copy(blue = accentFor(dark, highContrast))
```

新增 import：`android.content.Context`、`android.os.Build`、`android.view.accessibility.AccessibilityManager`、`androidx.compose.ui.platform.LocalContext`、`androidx.compose.runtime.remember`。

**计划原文在这一步错过两次，以下是实测校正（本机 `javap` + `platforms/android-37.0/data/api-versions.xml`）：**类名是 `android.view.accessibility.AccessibilityManager`，不存在 `android.accessibilityservice.AccessibilityManager`；方法名是 `isHighContrastTextEnabled()`，`api-versions.xml` 标它 `since="36"`，而计划原本写的 `isHighTextContrastEnabled` 在整个 API 37 表面里**一条都搜不到**。所以 `minSdk 31` 不能免掉判定 —— 不判就是 API 31–35 启动即 `NoSuchMethodError`，lint 也会报 `NewApi`。上面那段代码要按 `Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && am.isHighContrastTextEnabled` 求值，31–35 一律走常规 accent。另有一条 Compose 硬规则：`CompositionLocal.current` 是 `@ReadOnlyComposable`，**不能写在 `remember { }` 里**（`COMPOSABLE_INVOCATION`），要先 `val context = LocalContext.current` 再 `remember(context)`。`darkColorSchemeOf(palette)` 那一行不动 —— 它现在收到的是已经换过 accent 的色板，Material 兜底色与自绘层不会分叉（`IosStyle.kt:192-194` 的注释正是为了这件事）。

`IosPalette.blue` 字段与 `IosStyle.blue` getter 保持原样，所以 12 处 `s.blue` 调用点一个都不改，`green/orange/red` 也不参与本轮扩列。

- [ ] **Step 5: 测试 + 构建**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: `AccentForTest` 3 条全绿，`:app` 共 54 条；145 条 core 不动。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/kotlin/dev/zayn/vlive/ui/IosStyle.kt app/src/test/kotlin/dev/zayn/vlive/ui/AccentForTest.kt
git commit -m "feat(app): give the accent four values with a high-contrast variant"
```

---

## Task 4: `GlassMaterial` + 一套玻璃画法 + 按钮改 Tinted（切片 ② 后半）

**Files:**
- Modify: `app/src/main/kotlin/dev/zayn/vlive/ui/IosStyle.kt`（`GlassMaterial`、两套预设、`LocalIosGlass`、`iosGlass()`、`IosMetrics` 六个新常量）
- Create: `app/src/main/kotlin/dev/zayn/vlive/ui/GlassBar.kt`（`Modifier.iosGlassBar`）
- Modify: `app/src/main/kotlin/dev/zayn/vlive/ui/IosControls.kt:223-265`（`IosButton`）

**Interfaces:**
- Consumes: `HazeInput.Sources` / `Modifier.hazeBlur`（Task 1 已钉死）、`accentFor`（Task 3）
- Produces:
  - `@Immutable data class GlassMaterial(val tint: Color, val blurRadius: Dp, val highlight: Color, val stroke: Color, val shadowElevation: Dp, val radius: Dp)`
  - `val LightGlass: GlassMaterial` / `val DarkGlass: GlassMaterial` / `val LocalIosGlass` / `@Composable fun iosGlass(): GlassMaterial`
  - `fun Modifier.iosGlassBar(glass: GlassMaterial, state: HazeState, radius: Dp = glass.radius, barAlpha: Float = 1f): Modifier`
  - `IosMetrics.glassRadius = 30.dp`、`topBarHeight = 44.dp`、`barContentHeight = 74.dp`、`barBottomGap = 8.dp`、`collapseRange = 56.dp`、`sheetBarHeight = 52.dp`

- [ ] **Step 1: 加玻璃令牌与预设**

`IosStyle.kt` 中，`accentFor` 之后：

```kotlin
/**
 * 一块玻璃的全部参数。**只服务三个浮动 bar 区**（主屏顶栏、主屏底部操作栏、弹窗顶栏）：
 * 卡片不许用它，条内按钮也不许用它再糊一层（spec §1.1"一层玻璃只模糊一次"）。
 *
 * 两套预设按外观自动选，刻意不做用户可见的强度开关：这一屏没有承载它的设置面。
 * alpha 只许改这张表，调用点不许写散装 alpha。
 */
@Immutable
data class GlassMaterial(
    val tint: Color,
    val blurRadius: Dp,
    val highlight: Color,
    val stroke: Color,
    val shadowElevation: Dp,
    val radius: Dp,
)

val LightGlass = GlassMaterial(
    tint = Color.White.copy(alpha = 0.55f),
    blurRadius = 24.dp,
    highlight = Color.White.copy(alpha = 0.70f),
    stroke = Color.Black.copy(alpha = 0.10f),
    shadowElevation = 8.dp,
    radius = 0.dp,
)

/** 深色走"着色"档：着色层比初稿轻，因为卡片已经不是玻璃，只剩 bar 上的文字要保证读得清。 */
val DarkGlass = GlassMaterial(
    tint = Color(0xFF1C1C1E).copy(alpha = 0.62f),
    blurRadius = 24.dp,
    highlight = Color.White.copy(alpha = 0.18f),
    stroke = Color.White.copy(alpha = 0.14f),
    shadowElevation = 6.dp,
    radius = 0.dp,
)

val LocalIosGlass = staticCompositionLocalOf { LightGlass }

/** 与 [iosStyle] 对称：合成期一次取好玻璃参数。 */
@Composable
fun iosGlass(): GlassMaterial = LocalIosGlass.current
```

`IosTheme` 的 `CompositionLocalProvider` 改成同时提供两个（**判定仍然只有这一处**）：

```kotlin
        CompositionLocalProvider(
            LocalIosStyle provides IosStyle(palette),
            LocalIosGlass provides if (dark) DarkGlass else LightGlass,
        ) {
            content()
        }
```

`IosMetrics` 末尾（`buttonHeight` 之后）加：

```kotlin
    /** 底部操作栏的连续圆角：刻意小于半高（74/2），读作苹果的连续圆角而不是胶囊。 */
    val glassRadius: Dp = 30.dp
    val topBarHeight: Dp = 44.dp
    /** 50.dp 按钮 + 上下各 12.dp。内容层的 contentPadding.bottom 要用它。 */
    val barContentHeight: Dp = 74.dp
    val barBottomGap: Dp = 8.dp
    /** 大标题划过多远算"折叠完成"：topBarAlpha 的分母。 */
    val collapseRange: Dp = 56.dp
    /** 弹窗顶栏比主屏高一点：它要同时装下标题与整条路径的副标题（原本是 52.dp 的 Box）。 */
    val sheetBarHeight: Dp = 52.dp
```

- [ ] **Step 2: 一条 modifier 把玻璃画完**

顺序是这条的全部内容：`shadow` 在 `clip` 之前（先有形状才打得出阴影）、`hazeBlur` 在 `clip` 之内（糊完再裁才有那条边），高光是**糊好之后**叠上去的内容而不是参与模糊的东西；阴影、裁剪、描边共用同一个 `shape` 变量，三处各算一次会让高光漏出圆角。

```kotlin
package dev.zayn.vlive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

fun Modifier.iosGlassBar(
    glass: GlassMaterial,
    state: HazeState,
    radius: Dp = glass.radius,
    barAlpha: Float = 1f,
): Modifier {
    val shape: Shape = if (radius > 0.dp) RoundedCornerShape(radius) else RectangleShape
    return this
        .alpha(barAlpha)
        .shadow(elevation = glass.shadowElevation, shape = shape)
        .clip(shape)
        .hazeBlur(
            input = HazeInput.Sources(state),
            style = HazeBlurStyle {
                blurRadius(glass.blurRadius)
                colorEffects(listOf(HazeColorEffect.tint(glass.tint)))
            },
        )
        .background(Brush.verticalGradient(0f to glass.highlight, 0.45f to Color.Transparent))
        .border(width = IosMetrics.hairline, color = glass.stroke, shape = shape)
}
```

- [ ] **Step 3: 按钮改成 Tinted / 清澈玻璃**

`IosControls.kt` 的 `IosButton`（`:225-265`）整体替换为下面这版。**要点**：主按钮 = accent 着色填充 + 白字 + 上边缘高光 + hairline 描边（苹果 HIG 的 Tinted）；次按钮 = 近透明填充 + accent 字 + 描边；两者都**不再自己模糊**（spec §1.1），所以参数表里没有 `HazeState`。

```kotlin
@Composable
fun IosButton(
    text: String,
    enabled: Boolean,
    style: IosButtonStyle = IosButtonStyle.Primary,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val s = LocalIosStyle.current
    val glass = iosGlass()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val label = when (style) {
        IosButtonStyle.Primary -> Color.White
        IosButtonStyle.Destructive -> s.red
        IosButtonStyle.Secondary -> s.blue
    }
    // Tinted：accent 按固定 alpha 着色。同一份画法压在实心白卡上读作柔和色块，压在玻璃上读作
    // tinted glass —— 因此不为"在不在 bar 里"加分支。禁用态只淡填充与文字，不动结构。
    val surface = when {
        style == IosButtonStyle.Primary && enabled -> s.blue.copy(alpha = 0.85f)
        style == IosButtonStyle.Primary -> s.blue.copy(alpha = 0.35f)
        style == IosButtonStyle.Secondary && enabled -> glass.tint.copy(alpha = glass.tint.alpha * 0.6f)
        style == IosButtonStyle.Secondary -> Color.Transparent
        else -> s.card
    }
    val edge = RoundedCornerShape(percent = 50)
    Box(
        modifier
            .fillMaxWidth()
            .height(IosMetrics.buttonHeight)
            .alpha(if (pressed && enabled) 0.7f else 1f)
            .clip(edge)
            .shadow(elevation = if (style == IosButtonStyle.Destructive) 0.dp else 2.dp, shape = edge)
            .background(surface)
            .background(Brush.verticalGradient(0f to glass.highlight.copy(alpha = 0.25f), 0.5f to Color.Transparent))
            .border(IosMetrics.hairline, glass.stroke, edge)
            .clickable(
                interactionSource = interaction,
                enabled = enabled,
                onClickLabel = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = s.button.copy(color = if (enabled) label else label.copy(alpha = 0.4f)))
    }
}
```

新增 import：`androidx.compose.foundation.border`、`androidx.compose.ui.graphics.Brush`、`androidx.compose.ui.unit.dp` 已有。`RoundedCornerShape` 已有。

- [ ] **Step 4: 构建 + 测试 + lint**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug && node -e "const r=JSON.parse(require('fs').readFileSync('app/build/reports/lint-results-debug.sarif','utf8')).runs[0].results;console.log('total',r.length)"`
Expected: 54 条全绿；`BUILD SUCCESSFUL`；lint `total 9`（比 Task 2 结束时不许增加）。

- [ ] **Step 5: 装机看一处不破版（此时还没有 bar，卡片里那颗按钮是唯一可见变化）**

```bash
export MSYS_NO_PATHCONV=1
adb connect 192.168.1.18:36031 && adb devices
adb -s 192.168.1.18:36031 push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/vlive.apk
adb -s 192.168.1.18:36031 shell pm install -r -t /data/local/tmp/vlive.apk
adb -s 192.168.1.18:36031 shell monkey -p dev.zayn.vlive -c android.intent.category.LAUNCHER 1
sleep 2 && adb -s 192.168.1.18:36031 exec-out screencap -p > D:/tmp/glass_task4.png
```

Expected: `Success`，截图里"授予所有文件访问"那颗按钮已经是 tinted 观感（accent 着色 + 白字 + 描边），卡片仍是实心白/深灰，**没有任何一块玻璃**（bar 在 Task 6/7 才出现）。设备不在线就先跳过并在提交信息里写明未验证，不许谎报。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/kotlin/dev/zayn/vlive/ui/IosStyle.kt app/src/main/kotlin/dev/zayn/vlive/ui/GlassBar.kt app/src/main/kotlin/dev/zayn/vlive/ui/IosControls.kt
git commit -m "feat(app): add glass material tokens and tinted buttons"
```

---

## Task 5: `titleBarAlpha` 纯函数（切片 ③ 的第一步）

**Files:**
- Create: `app/src/main/kotlin/dev/zayn/vlive/ui/TitleBarAlpha.kt`
- Create: `app/src/test/kotlin/dev/zayn/vlive/ui/TitleBarAlphaTest.kt`

**Interfaces:**
- Consumes: 无（刻意不依赖 Compose，才能被 JVM 单测覆盖）
- Produces: `fun titleBarAlpha(scrollY: Int, collapseRange: Int): Float`，Task 6 用它

- [ ] **Step 1: 写失败的测试**

```kotlin
package dev.zayn.vlive.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 玻璃标题栏的 alpha 是这一轮唯一值得单测的新逻辑：它决定"划过去才看得见"这件事，
 * 错了只会表现为"标题栏一直挡着"或"永远不出现"，肉眼看一次未必复现得出来。
 *
 * 单位是 px，不是 dp —— 换算发生在调用点（那里有 LocalDensity），函数保持纯。
 */
class TitleBarAlphaTest {

    private fun assertAlpha(expected: Float, scrollY: Int, range: Int) =
        assertEquals(expected, titleBarAlpha(scrollY, range), 1e-6f)

    @Test
    fun `hidden at the very top`() = assertAlpha(0f, scrollY = 0, range = 56)

    @Test
    fun `fully visible once past the collapse range`() = assertAlpha(1f, scrollY = 56, range = 56)

    @Test
    fun `linear in between`() = assertAlpha(0.5f, scrollY = 28, range = 56)

    @Test
    fun `clamped past the range`() = assertAlpha(1f, scrollY = 4000, range = 56)

    @Test
    fun `overscroll does not go negative`() = assertAlpha(0f, scrollY = -120, range = 56)

    @Test
    fun `a non-positive range never hides the bar forever`() {
        assertAlpha(1f, scrollY = 0, range = 0)
        assertAlpha(1f, scrollY = 12, range = -5)
    }
}
```

- [ ] **Step 2: 跑到失败**

Run: `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && ./gradlew :app:testDebugUnitTest --tests "dev.zayn.vlive.ui.TitleBarAlphaTest"`
Expected: 编译失败（`titleBarAlpha` unresolved）。

- [ ] **Step 3: 实现**

```kotlin
package dev.zayn.vlive.ui

/**
 * 滚动 [scrollY]（px）时玻璃标题栏的不透明度，[collapseRange] 是同单位、同方向的 px。
 *
 * 两条边界不是防御性摆设：
 * - `range <= 0` 返回 `1f`：布局在某种窗口尺寸下算出 0 时，宁可让 bar 一直可见，也不能让标题永久消失；
 * - 负 `scrollY`（overscroll 回弹）夹到 `0f`，否则 alpha 变负、`graphicsLayer` 上会出现无意义的值。
 */
fun titleBarAlpha(scrollY: Int, collapseRange: Int): Float =
    if (collapseRange <= 0) 1f else (scrollY.toFloat() / collapseRange).coerceIn(0f, 1f)
```

- [ ] **Step 4: 跑到通过**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.zayn.vlive.ui.TitleBarAlphaTest"`
Expected: 6 条 PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/dev/zayn/vlive/ui/TitleBarAlpha.kt app/src/test/kotlin/dev/zayn/vlive/ui/TitleBarAlphaTest.kt
git commit -m "feat(app): add titleBarAlpha for the collapsing glass title bar"
```

---

## Task 6: 内容层变 haze source + inset 重新分工 + 顶部玻璃栏（切片 ③ 主体）

这一步只动 `BatchScreen.kt` 的外壳：`Box` 从"一个 Column"变成"三层"。按钮**这一步先留在原地**（下一步才搬），这样一旦玻璃出问题，能单独回滚结构而不连着把操作入口一起改掉。

**Files:**
- Modify: `app/src/main/kotlin/dev/zayn/vlive/BatchScreen.kt:117-133`（外壳 + 内容层）、新增 import
- Modify: `app/src/main/kotlin/dev/zayn/vlive/BatchScreen.kt:240-330` 之后（在 `Box` 收尾前插入顶部玻璃栏）

**Interfaces:**
- Consumes: `Modifier.hazeSource` / `rememberHazeState`（Task 1）、`Modifier.iosGlassBar` + `iosGlass()`（Task 4）、`titleBarAlpha` + `IosMetrics.collapseRange/topBarHeight`（Task 5 / Task 4）
- Produces: 一个 `val haze: HazeState` 与一个 `val scroll: ScrollState`，Task 7 的底部栏共用；`val insets: PaddingValues`（Task 7、Task 8 的几何都用它）

- [ ] **Step 1: 先量改造前的帧率基线（这一步之后就没有对照组了）**

改结构之前必须先装一次**当前 HEAD 的产物**并记下数字 —— 玻璃落地之后就再也量不到"没有玻璃"的那一行了。

```bash
export MSYS_NO_PATHCONV=1
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
SER=192.168.1.18:36031
adb connect $SER && adb devices
./gradlew :app:assembleDebug
adb -s $SER push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/vlive.apk
adb -s $SER shell pm install -r -t /data/local/tmp/vlive.apk
adb -s $SER shell appops set dev.zayn.vlive MANAGE_EXTERNAL_STORAGE allow
# 报告列表里得有东西才有可滚的内容：跑一批（删除开关保持关闭），批次的目录由 DataStore 恢复上次的选择
adb -s $SER shell monkey -p dev.zayn.vlive -c android.intent.category.LAUNCHER 1
```

然后在界面上按一次"开始合成"、等批次结束（**这一步会在目标目录里写出产物，跑之前先跟用户确认一次**），再采样：

```bash
adb -s $SER shell dumpsys gfxinfo dev.zayn.vlive reset
for i in $(seq 1 12); do adb -s $SER shell input swipe 608 2000 608 600 300; done
adb -s $SER shell dumpsys gfxinfo dev.zayn.vlive > D:/tmp/gfx_baseline.txt
grep -E "Total frames rendered|Janky frames" D:/tmp/gfx_baseline.txt
```

Expected: 拿到 `P_baseline`（janky 百分比）。**同样的循环连做三遍，取中位数**，把三个数与中位数一起写进本任务给用户的汇报里 —— Task 9 的判定要用它。

- [ ] **Step 2: 换掉外层结构**

把 `:117-133` 那段 `Box { Column(Modifier…safeDrawing…verticalScroll…padding(bottom = 28.dp)) {` 改为：

```kotlin
    val scroll = rememberScrollState()
    val haze = rememberHazeState()
    val glass = iosGlass()
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current
    val collapsePx = with(LocalDensity.current) { IosMetrics.collapseRange.toPx() }.toInt()
    val titleAlpha = titleBarAlpha(scroll.value, collapsePx)

    Box(
        // targetSdk 35+ 起窗口强制 edge-to-edge，底色必须铺到状态栏底下，否则顶部是一条系统给的接缝。
        Modifier
            .fillMaxSize()
            .background(s.groupedBackground),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                // 折叠屏内屏约 780dp 宽：内容封顶后居中，卡片不会被拉成一条横杠。
                .widthIn(max = 640.dp)
                .fillMaxSize()
                // 这一层是主屏窗口里唯一的模糊源，两条 bar 都从它取位图。
                .hazeSource(haze)
                // inset 的归属变了：windowInsetsPadding 会把视口本身缩小，内容永远划不到 bar 底下，
                // 玻璃就糊不出东西；contentPadding 保持满屏视口、只把首尾留白推出来。
                .verticalScroll(
                    state = scroll,
                    contentPadding = PaddingValues(
                        start = insets.calculateLeftPadding(layoutDirection),
                        top = insets.calculateTopPadding(),
                        end = insets.calculateRightPadding(layoutDirection),
                        // 底部栏的总占位 = 导航栏 inset + 8dp 间距 + 74dp 内容 + 16dp 余量。
                        bottom = insets.calculateBottomPadding() +
                            IosMetrics.barBottomGap + IosMetrics.barContentHeight + 16.dp,
                    ),
                ),
        ) {
```

新增 import：`androidx.compose.foundation.layout.PaddingValues`、`androidx.compose.foundation.layout.asPaddingValues`、`androidx.compose.ui.platform.LocalDensity`、`androidx.compose.ui.platform.LocalLayoutDirection`、`dev.chrisbanes.haze.hazeSource`、`dev.chrisbanes.haze.rememberHazeState`、`dev.zayn.vlive.ui.iosGlass`、`dev.zayn.vlive.ui.iosGlassBar`、`dev.zayn.vlive.ui.titleBarAlpha`。（`calculateLeftPadding` / `calculateTopPadding` 这些是 `PaddingValues` 的成员方法，不要 import。）

同时删掉不再使用的 `androidx.compose.foundation.layout.windowInsetsPadding` import（`BatchScreen.kt:21`；这是它唯一一处用到该函数的地方，`IosSheet` 那份在 Task 8 里删）。

注意 `rememberScrollState()` 原来写在 `.verticalScroll(rememberScrollState())` 里，现在提到上面（同一个值 alpha 也要读）。**`windowInsetsPadding(safeDrawing)` 与 `padding(bottom = 28.dp)` 从内容层删掉**，`.padding(horizontal = IosMetrics.screenMargin)` 那些留在每个 section 自己身上的调用一律不动。

- [ ] **Step 3: 插入顶部玻璃栏**

在内容层那个 `Column` 的收尾 `}` 之后、外层 `Box` 的收尾 `}` 之前加：

```kotlin
        // 大标题留在滚动流里，玻璃栏画的是折叠后的紧凑标题；alpha 由滚动位置直接决定，
        // 不做动画（惯性滚动时 animateFloatAsState 只会让 bar 落后于手指）。
        if (titleAlpha > 0f) {
            // 玻璃必须盖到状态栏底下（顺序：先 iosGlassBar 再 padding(top = inset)，反过来会在
            // 状态栏那一条和玻璃之间留一条接缝 —— 与冷启动时已经被投诉过的那条是同一类）。
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .iosGlassBar(glass = glass, state = haze, barAlpha = titleAlpha),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = insets.calculateTopPadding())
                        .height(IosMetrics.topBarHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.app_name),
                        style = s.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 76.dp),
                    )
                }
            }
        }
```

新增 import：`androidx.compose.ui.text.style.TextOverflow`。`insets.calculateTopPadding()` 不收参数（只有左右两个要 `LayoutDirection`），Step 1 里已经这么写；这里保持一致。

- [ ] **Step 4: 构建 + 测试**

Run: `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`；:app 共 **60** 条（51 + `AccentForTest` 3 + `TitleBarAlphaTest` 6），`:core` 仍 145 条。数字要对着 `app/build/reports/tests/testDebugUnitTest/index.html` 报，不许写"约"。

- [ ] **Step 5: 装机验三件事 —— 玻璃出东西了、滚动没被吃掉、横向 inset 还在**

```bash
export MSYS_NO_PATHCONV=1
adb -s 192.168.1.18:36031 push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/vlive.apk
adb -s 192.168.1.18:36031 shell pm install -r -t /data/local/tmp/vlive.apk
adb -s 192.168.1.18:36031 shell monkey -p dev.zayn.vlive -c android.intent.category.LAUNCHER 1
sleep 2 && adb -s 192.168.1.18:36031 exec-out screencap -p > D:/tmp/glass_topbar_rest.png
adb -s 192.168.1.18:36031 shell input swipe 608 1900 608 700 500
sleep 1 && adb -s 192.168.1.18:36031 exec-out screencap -p > D:/tmp/glass_topbar_scrolled.png
```

Expected：
1. `Success`；
2. 静止那张里**看不到**顶部玻璃（`titleAlpha == 0`，bar 不进组合）；
3. 划了一刀那张里玻璃顶栏出现、且能看出下面是划过去的卡片（不是纯色一块 —— 那就是"糊空气"）；
4. 再连划三次确认内容还能继续滚（bar 挡不住手势）。**这一条是 spec §3.6 里点名必须验的推测项**：不通就在 bar 容器上加 `Modifier.hitTestBehavior(HitTestBehavior.Transparent)` 再验，并在提交信息里写明用了哪条。

两张图都要用 Read 工具看过才算数；没装机条件就明确写"视觉未验证"，不许用"应该没问题"。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/kotlin/dev/zayn/vlive/BatchScreen.kt
git commit -m "feat(app): make the content layer a haze source under a glass title bar"
```

---

## Task 7: 底部玻璃操作栏（切片 ③ 收尾）

**Files:**
- Modify: `app/src/main/kotlin/dev/zayn/vlive/BatchScreen.kt:214-228`（从滚动流里删掉那段 Column）
- Modify: `app/src/main/kotlin/dev/zayn/vlive/BatchScreen.kt`（在 `Box` 收尾前、顶部栏之后插入底部栏）

**Interfaces:**
- Consumes: `haze`、`glass`、`insets`、`entrance`（Task 6 都在同一作用域里）、`IosButton` 的 tinted/clear 画法（Task 4）、`IosMetrics.glassRadius/barContentHeight/barBottomGap`（Task 4）
- Produces: 无下游任务依赖（弹窗的 source 是另起一个）

- [ ] **Step 1: 从滚动流里删掉按钮段**

删除现在 `:214-228` 这一整块（`Spacer(sectionGap)` + `Column(Modifier.iosEnter(5, entrance)…) { IosButton(开始) Spacer(12) IosButton(取消) }`）。**序号 5 留给 bar**：后面的 6/7/8 一个都不重编号（spec §2.3）。

- [ ] **Step 2: 加上底部栏**

在 Task 6 那个 `if (titleAlpha > 0f) { … }` 之后加：

```kotlin
        // 开始 / 取消从滚动流里搬到常驻栏：位置不能跟着内容跳，主操作要一直够得着。
        // 整条 bar 就是这一区域唯一的那块玻璃，两颗按钮只加自己的 tint，绝不再各糊一层（spec §1.1）。
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .padding(
                    start = insets.calculateLeftPadding(layoutDirection) + IosMetrics.screenMargin,
                    end = insets.calculateRightPadding(layoutDirection) + IosMetrics.screenMargin,
                )
                .padding(bottom = insets.calculateBottomPadding() + IosMetrics.barBottomGap)
                .iosEnter(5, entrance)
                .iosGlassBar(glass = glass, state = haze, radius = IosMetrics.glassRadius)
                // 50dp 按钮 + 上下各 12dp = IosMetrics.barContentHeight(74dp)，
                // 与内容层 contentPadding.bottom 预留的那段必须同一个数，否则最后一张卡片被压住。
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IosButton(
                text = stringResource(R.string.action_start),
                // 没授权就点不动：spec §9 把权限列为全局前置条件，别让人先按下开始再收一条错误。
                enabled = state.permissionGranted && !state.running,
                onClick = vm::start,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.width(12.dp))
            IosButton(
                text = stringResource(R.string.action_cancel),
                // 未运行时禁用而不是隐藏：用户要一直看得见"现在能不能取消"（沿用原实现语义）。
                enabled = state.running,
                style = IosButtonStyle.Secondary,
                onClick = vm::cancel,
                modifier = Modifier.weight(0.45f),
            )
        }
```

`IosButton` 内部那条 `modifier.fillMaxWidth()` 与外面传进来的 `weight` 不冲突：`weight` 先分好宽，`fillMaxWidth` 再填满分配到的那点宽度。宽度用 `weight(1f)` / `weight(0.45f)` 而不是固定 dp，是因为"取消 / Cancel"两种语言的字宽不一样，Task 9 的两轮语言验收就靠这条不写死。

新增 import（`BatchScreen.kt` 现在两个都没有）：`androidx.compose.foundation.layout.Row`、`androidx.compose.foundation.layout.width`。

- [ ] **Step 3: 构建 + lint**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug && node -e "const r=JSON.parse(require('fs').readFileSync('app/build/reports/lint-results-debug.sarif','utf8')).runs[0].results;console.log('total',r.length)"`
Expected: 构建成功、测试全绿、lint 仍是 `total 9`。

- [ ] **Step 4: 装机验四种状态**

```bash
export MSYS_NO_PATHCONV=1
adb -s 192.168.1.18:36031 push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/vlive.apk
adb -s 192.168.1.18:36031 shell pm install -r -t /data/local/tmp/vlive.apk
adb -s 192.168.1.18:36031 shell appops set dev.zayn.vlive MANAGE_EXTERNAL_STORAGE allow
adb -s 192.168.1.18:36031 shell monkey -p dev.zayn.vlive -c android.intent.category.LAUNCHER 1
sleep 2 && adb -s 192.168.1.18:36031 exec-out screencap -p > D:/tmp/glass_bar_idle.png
```

然后在界面上选一个有配对的源目录与目标目录、点"开始合成"，中途截一张运行态（取消此时应可用）：

```bash
sleep 1 && adb -s 192.168.1.18:36031 exec-out screencap -p > D:/tmp/glass_bar_running.png
```

Expected（每张都用 Read 看过）：bar 浮在内容之上且能看出玻璃（底下卡片划过）；`取消` 未运行时是禁用但**仍在原位**；运行时按钮文字与 tinted/clear 两种画法和 §1.4 一致；卡片仍是实心；最后一张卡片能滚到 bar 上沿之上、不被永久遮挡。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/dev/zayn/vlive/BatchScreen.kt
git commit -m "feat(app): float the start/cancel actions on a glass bottom bar"
```

---

## Task 8: 弹窗的第二个 haze source（切片 ④）

**Files:**
- Modify: `app/src/main/kotlin/dev/zayn/vlive/ui/IosControls.kt:497-577`（`IosSheet` 整体几何）

**Interfaces:**
- Consumes: `Modifier.iosGlassBar`、`iosGlass()`、`rememberHazeState`、`Modifier.hazeSource`、`IosMetrics.sheetBarHeight`
- Produces: `IosSheet` 签名**不变**（`title/subtitle/cancelText/confirmText/confirmEnabled/onDismiss/onConfirm/content`），所以 `BatchScreen.kt:460` 的调用点一行都不用改

- [ ] **Step 1: 把弹窗顶栏改成压在列表之上的玻璃**

`IosSheet` 里 `Dialog { … }` 的内容整体换成下面这版。三处关键变化：弹窗自己 `rememberHazeState()`（不能复用主屏那个，跨窗口取不到位图）；列表变成 `hazeSource` 且**从弹窗顶部铺满**、用 `contentPadding(top = 状态栏 + 顶栏高)` 让出第一行；顶栏是这块 source 的唯一消费者。

```kotlin
@Composable
fun IosSheet(
    title: String,
    subtitle: String,
    cancelText: String,
    confirmText: String,
    confirmEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val s = LocalIosStyle.current
    val glass = iosGlass()
    // 独立窗口：主屏那个 HazeState 在这里取不到位图，必须自己注册第二个（spec §2.5）。
    val haze = rememberHazeState()
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current
    val topInset = insets.calculateTopPadding()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // 弹窗整体仍然是实心分组底色：玻璃只出现在顶栏这一条，绝不玻璃叠玻璃。
        Column(
            Modifier
                .fillMaxSize()
                .background(s.groupedBackground)
                .padding(
                    start = insets.calculateLeftPadding(layoutDirection),
                    end = insets.calculateRightPadding(layoutDirection),
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 折叠屏内屏约 780dp 宽，内容封顶后居中，否则卡片和行会被拉成一条横杠。
            Box(
                Modifier
                    .fillMaxHeight()
                    .widthIn(max = 640.dp)
                    .fillMaxWidth(),
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .hazeSource(haze)
                        .verticalScroll(
                            state = rememberScrollState(),
                            contentPadding = PaddingValues(
                                top = topInset + IosMetrics.sheetBarHeight,
                                bottom = 28.dp,
                            ),
                        ),
                    content = content,
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .iosGlassBar(glass = glass, state = haze),
                ) {
                    // 同主屏顶栏：玻璃盖到状态栏底下，inset 只在里面把内容推下去。
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = topInset)
                            .height(IosMetrics.sheetBarHeight)
                    ) {
                        BarAction(text = cancelText, align = Alignment.CenterStart, onClick = onDismiss)
                        BarAction(
                            text = confirmText,
                            align = Alignment.CenterEnd,
                            enabled = confirmEnabled,
                            emphasized = true,
                            onClick = onConfirm,
                        )
                        Column(
                            Modifier
                                .align(Alignment.Center)
                                .fillMaxWidth()
                                .padding(horizontal = 76.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                title,
                                style = s.title,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                subtitle,
                                style = s.footnote.copy(fontSize = 11.sp, color = s.tertiaryLabel),
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
```

新增 import：`androidx.compose.foundation.layout.PaddingValues`、`androidx.compose.foundation.layout.asPaddingValues`、`androidx.compose.foundation.layout.calculateBottomPadding`、`androidx.compose.foundation.layout.calculateLeftPadding`、`androidx.compose.foundation.layout.calculateRightPadding`、`androidx.compose.foundation.layout.calculateTopPadding`、`androidx.compose.ui.platform.LocalLayoutDirection`、`dev.chrisbanes.haze.hazeSource`、`dev.chrisbanes.haze.rememberHazeState`。

两条必须一起做的删除，否则顶栏糊不到东西：

- 原来那句整层 `windowInsetsPadding(WindowInsets.safeDrawing)`（旧 `:523`）删掉 —— 它把列表整个压到顶栏下面，顶栏底下就没有可糊的内容了；改成只吃横向 inset，竖直方向交给 `contentPadding`。
- 原来紧跟着顶栏那句 `IosDivider(indent = false)`（旧 `:566`）删掉 —— `iosGlassBar` 自带 hairline 描边，再画一条是重复。

- [ ] **Step 2: 构建 + 测试 + lint**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
Expected: 全绿，lint `total 9`。

- [ ] **Step 3: 装机验"糊的是自己的列表"与"没有叠玻璃"**

```bash
export MSYS_NO_PATHCONV=1
adb -s 192.168.1.18:36031 push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/vlive.apk
adb -s 192.168.1.18:36031 shell pm install -r -t /data/local/tmp/vlive.apk
adb -s 192.168.1.18:36031 shell monkey -p dev.zayn.vlive -c android.intent.category.LAUNCHER 1
sleep 2
# 点进"源目录"那一行打开弹窗：坐标来自 uiautomator dump（Compose 不暴露文本，但暴露 clickable 边界）
adb -s 192.168.1.18:36031 shell uiautomator dump /sdcard/w.xml >/dev/null
adb -s 192.168.1.18:36031 shell cat /sdcard/w.xml | tr '>' '\n' > D:/tmp/w.txt   # 从 D:/tmp/w.txt 里读"源目录"那行的 bounds
adb -s 192.168.1.18:36031 shell input tap 300 480
sleep 1 && adb -s 192.168.1.18:36031 exec-out screencap -p > D:/tmp/glass_sheet_root.png
adb -s 192.168.1.18:36031 shell input tap 300 700
sleep 1 && adb -s 192.168.1.18:36031 exec-out screencap -p > D:/tmp/glass_sheet_level2.png
```

Expected：弹窗顶栏是玻璃、糊的是**弹窗自己的列表**（列表首行被顶栏盖住时能看出它在玻璃下划过），不是空白、不是主屏内容；弹窗其余部分是实心；两层都不出现"玻璃下面还是玻璃"。`tap` 坐标必须按当次 dump 的 bounds 算出来，不许照抄示例数字。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/kotlin/dev/zayn/vlive/ui/IosControls.kt
git commit -m "feat(app): give the folder sheet its own haze source under a glass top bar"
```

---

## Task 9: 设备验收矩阵 + 文档落档（切片 ⑤）

这个任务没有新代码，产出是**数字与截图**，以及把数字解释清楚的三段文档。spec §3.4 的六条在这里一次跑完。

**Files:**
- Modify: `docs/decisions.md`（新增 §5.19）
- Modify: `docs/testing.md`（设备判据那节 + 测试条目数）
- Modify: `docs/superpowers/specs/2026-10-21-liquid-glass-ui-design.md`（§3.4 每条后面记实测值；走了回退就写明）

**Interfaces:**
- Consumes: Task 1–8 的全部
- Produces: 无

- [ ] **Step 1: 取回 Task 6 Step 1 记下的基线**

基线只能在**没有玻璃的包**上量，那台机器在 Task 6 之前，所以数字必须来自 Task 6 Step 1 的采样记录（`D:/tmp/gfx_baseline.txt`）。三种情况：

- 文件在、记录在 → 直接引用，把 `git rev-parse --short HEAD`（当时的 HEAD，即 Task 5 的提交）一起写进 decisions.md；
- 文件丢了但 Task 5 的提交还在 → `git stash` 保住当前工作、`git checkout <task5-sha>` 重装再量一次，量完 `git checkout -` 回到当前提交，并把"基线补测于哪个 sha"写清楚；
- 两条都做不到 → **明确写"无基线，只有绝对值"**，不许拿一个记忆里的百分比冒充对照组。这是 spec §3.4 第 4 条的判据，缺了它"≤ 基线 +3 个百分点"这半句就不成立。

```bash
grep -E "Total frames rendered|Janky frames" D:/tmp/gfx_baseline.txt
```

Expected: 三行 `Janky frames: X (P%)` 与一个中位数 `P_baseline`。

- [ ] **Step 2: 在最终包上量同一组数字**

```bash
adb -s $SER push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/vlive.apk
adb -s $SER shell pm install -r -t /data/local/tmp/vlive.apk
adb -s $SER shell am force-stop dev.zayn.vlive
adb -s $SER shell dumpsys gfxinfo dev.zayn.vlive reset
adb -s $SER shell monkey -p dev.zayn.vlive -c android.intent.category.LAUNCHER 1
sleep 3
for i in $(seq 1 12); do adb -s $SER shell input swipe 608 2000 608 600 300; done
adb -s $SER shell dumpsys gfxinfo dev.zayn.vlive > D:/tmp/gfx_after.txt
grep -E "Total frames rendered|Janky frames" D:/tmp/gfx_after.txt | head -3
```

Expected: `P_after`（同样三遍取中位数）与总帧数，连同 Step 1 的 `P_baseline` 一起记进 Step 5 的 decisions.md。**判定：`P_after ≤ P_baseline + 3` 个百分点，且 `P_after ≤ 10%`**（spec §3.4 第 4 条）。

- [ ] **Step 3: 不达标就走预先约定的回退（不许临场发明）**

若 Step 2 的数超出预算：**底部栏去掉模糊，保留 tint + 高光 + 描边**（spec §3.4 预先约定的那条例，不是临场发明）。改法是把 `BatchScreen.kt` 底部栏那处 `iosGlassBar(...)` 换成同文件 `GlassBar.kt` 里新增的 `iosGlassTray(glass, radius)` —— 内容与 `iosGlassBar` 一样但**不含 `hazeBlur`**，用 `.background(glass.tint)` 代替，顶部栏保持模糊。改完回到 Step 2 重测，两个数字都记下来。

`RectangleShape` 的 import 是 `androidx.compose.ui.graphics.RectangleShape`（本机 `javap` 核过：`androidx.compose.ui.graphics.RectangleShapeKt`，不在 `foundation.shape` 里），`GlassBar.kt` 里已有。

```kotlin
/** 回退形态：玻璃感主要来自着色与边缘高光，模糊是最贵的一项，砍它。 */
fun Modifier.iosGlassTray(glass: GlassMaterial, radius: Dp): Modifier {
    val shape: Shape = if (radius > 0.dp) RoundedCornerShape(radius) else RectangleShape
    return this
        .shadow(elevation = glass.shadowElevation, shape = shape)
        .clip(shape)
        .background(glass.tint)
        .background(Brush.verticalGradient(0f to glass.highlight, 0.45f to Color.Transparent))
        .border(IosMetrics.hairline, glass.stroke, shape)
}
```

- [ ] **Step 4: 两轮外观 × 两种语言的截图矩阵**

```bash
adb -s $SER shell cmd uimode night yes    # 深色
adb -s $SER shell settings put system system_locales en-US
adb -s $SER shell am force-stop dev.zayn.vlive
adb -s $SER shell monkey -p dev.zayn.vlive -c android.intent.category.LAUNCHER 1
sleep 2 && adb -s $SER exec-out screencap -p > D:/tmp/m_dark_en_idle.png
adb -s $SER shell input swipe 608 2000 608 600 300 && sleep 1
adb -s $SER exec-out screencap -p > D:/tmp/m_dark_en_scrolled.png
adb -s $SER shell input tap 300 480 && sleep 1
adb -s $SER exec-out screencap -p > D:/tmp/m_dark_en_sheet.png
# 回到浅色 + 中文
adb -s $SER shell cmd uimode night no
adb -s $SER shell settings put system system_locales zh-CN
adb -s $SER shell am force-stop dev.zayn.vlive
adb -s $SER shell monkey -p dev.zayn.vlive -c android.intent.category.LAUNCHER 1
sleep 2 && adb -s $SER exec-out screencap -p > D:/tmp/m_light_zh_idle.png
adb -s $SER shell input swipe 608 2000 608 600 300 && sleep 1
adb -s $SER exec-out screencap -p > D:/tmp/m_light_zh_scrolled.png
adb -s $SER shell input tap 300 480 && sleep 1
adb -s $SER exec-out screencap -p > D:/tmp/m_light_zh_sheet.png
```

Expected（六张图逐张用 Read 看过，`system_locales` 在 vivo 上是否即时生效以截图实际语言为准，不生效就手工切一次并在文档里写明是怎么切的）：
1. 玻璃 bar 上的紧凑标题、`开始合成` 白字、`取消` accent 字在两种外观下都读得清；
2. 卡片在两种外观下仍是实心白/深灰；
3. 弹窗顶栏糊的是自己的列表；
4. 全英文界面无中文残留、无空字符串；
5. 权限缺失态（先 `appops set … deny` 再启动）与批次运行态各截一张 —— 这两处的按钮画法被 Task 4 改过；
6. 冷启动 splash 那一帧与改造前对比无变化（spec §1.2 不许碰窗口级主题）。

```bash
adb -s $SER shell appops set dev.zayn.vlive MANAGE_EXTERNAL_STORAGE allow   # 验完恢复
```

- [ ] **Step 5: decisions.md 新增 §5.19**

```markdown
### 5.19 玻璃只给浮动控件，卡片保持实心（2026-10-21 增量）

**决定**：真背景模糊（Haze + `RenderEffect`）只用在三处 —— 主屏顶部标题栏、主屏底部操作栏、目录弹窗顶栏；
分组卡片保持实心面。主按钮按苹果按钮规范改成 Tinted（accent 着色 + 白字 + 上边缘高光 + 描边），
次按钮是清澈玻璃 + accent 字，二者都**不再自己糊一层**。accent 一次给四个值
（浅/深 × 常规/高对比），控件着色只用于主操作 / 选中 / 需要注意三种状态。

**为什么**：苹果自己的采纳建议就是"自定义玻璃留给一两个关键浮动控件"，实心面上的卡片读起来更原生，
玻璃不许叠玻璃；纯模糊太透、会抢焦点（这也是 26.1 加着色档的原因）。工程侧的第二条理由一样硬：
需要实时模糊的绘制面从"每张卡片"降到三块 bar，帧开销才有预算可谈。

**代价与实测**：gfxinfo janky frames 基线 <P_baseline>%（commit <task5-sha>，无玻璃），改造后 <P_after>%
（commit <sha>）；预算是"不超过基线 +3 个百分点且 ≤ 10%"。<未超预算 → 保留模糊 / 超了 → 底部栏已按
§3.4 的回退去掉模糊，保留 tint + 高光 + 描边>。设备：vivo V2419A。
```

尖括号里的三项**必须**用 Step 1–2 的真实数字与真实 sha 填掉；填不出来就写"未测量"，不许留空或编造。

- [ ] **Step 6: testing.md 更新**

- 总览那一行的条目数改成实际值（204 → 204 + `AccentForTest` 3 + `TitleBarAlphaTest` 6 = 213，以 `:app:testDebugUnitTest` 报告里的数为准），`:32` 那行注释同步；
- `:108` 那条 lint 行的基线数改成 9、`ObsoleteSdkInt` 改成 ×1，并把"两条分支的 ObsoleteSdkInt 与 `else` 分支里那条 `UseKtx` 已随 minSdk 31 一起消失"写进同一格；
- 设备判据一节（`:141` 附近）加两行：**玻璃上的文字可读性**与 **janky frames 百分比**，并写明采样方法（三次取中位数、`dumpsys gfxinfo … reset` 先行）。

- [ ] **Step 7: spec 回填**

在 spec §3.4 的第 4 条后面加一行实测记录（数字 + 日期 + commit），若走了回退也在 §3.4 与 §1.3 各标一句"实际实现砍掉了底部栏的模糊"。计划与实现的差异要留在文档里，不要只留在会话里。

- [ ] **Step 8: 提交**

```bash
git add docs/decisions.md docs/testing.md docs/superpowers/specs/2026-10-21-liquid-glass-ui-design.md
git commit -m "docs: record the glass restyle's measured frame budget and acceptance shots"
```

---

## 自检结果（写完对照 spec 重读一遍的记录）

**1. spec 覆盖 —— 逐条能对上一个任务**

| spec | 落在哪 |
| --- | --- |
| §1.1 一层玻璃只糊一次（三个 bar 区） | Task 4（`iosGlassBar` 只有一条糊法）+ Task 8（弹窗第二个 source），Task 9 Step 4 判据 3 复验 |
| §1.2 卡片回实心、不许碰窗口级主题 | Task 4 不动 `SectionCard`/`ReportCard`；Global Constraints 第 9 条；Task 9 Step 4 判据 2 与 6 |
| §1.3 `GlassMaterial` 与两张预设表 | Task 4 Step 1 |
| §1.4 按钮 Tinted / 清澈玻璃 / 破坏性不变 | Task 4 Step 3 |
| §1.5 accent 四值 + 高对比信号 | Task 3（含"信号取不到就只留常规两列并记缺口"）+ Task 9 Step 4 |
| §2.1 三层结构 | Task 6 Step 2、Task 7 Step 2 |
| §2.2 `titleBarAlpha`、不做动画 | Task 5 + Task 6 Step 3 |
| §2.3 底部栏内容、序号 5 不重编号 | Task 7 Step 1–2 |
| §2.4 inset 归属（含横向不丢） | Task 6 Step 2、Task 7 Step 2、Task 8 Step 1 |
| §2.5 弹窗第二个 source + 叠层几何 | Task 8 Step 1 |
| §2.6 不加半径动画、入场交错保留 | Task 4（没写动画）+ Task 6 Step 3 注释 + Task 7 Step 2 |
| §3.1 依赖核实 | Task 1（真签名已钉死在本文档开头） |
| §3.2 十三项连带 | Task 2 Step 2–9，逐项对号 |
| §3.3 测试 | Task 3 Step 1、Task 5 Step 1、各任务的 `testDebugUnitTest` 步 |
| §3.4 六条验收 + 回退 | Task 6 Step 1（基线）、Task 9 Step 1–4 |
| §3.5 五片切分 | ①=Task 1–2 ②=3–4 ③=5–7 ④=8 ⑤=9 |
| §3.6 六条风险 | 依赖核实→Task 1；OEM 模糊异常→Task 9 Step 1–3；命中穿透→Task 6 Step 5 第 4 条；手势导航 inset→Task 9 Step 4（两种形态各截一张）；高对比开关→Task 3 Step 4 + Task 9 Step 4；叠层重组开销→Task 9 Step 2 的滚动场景 |

**2. 占位符**：全文无 TBD/TODO/"稍后补"/"同 Task N"。所有代码步给的是可编译的最终形态。两处 API 名字用 `javap` 对着本机 Gradle 缓存核过，不是凭印象：`RectangleShape` 在 `androidx.compose.ui.graphics`（不在 `foundation.shape`）、`Brush.verticalGradient(vararg Pair<Float, Color>)` 这个重载确实存在。Haze 的 `hazeBlur` / `HazeBlurStyle { colorEffects(listOf(HazeColorEffect.tint(…))) }` 从 2.0.1 的 sources jar 里读出来，spec §3.1 那个 `hazeEffect` / `hazeBlur` 的悬案在本文档开头结案。

**3. 类型一致性**：`iosGlassBar(glass, state, radius = glass.radius, barAlpha = 1f)` 定义于 Task 4，三处调用（Task 6 传 `barAlpha`、Task 7 传 `radius`、Task 8 全默认）签名一致；`GlassMaterial` 六个字段名在 Task 4 定义与 Task 6/7/8/9 使用处一致；`IosMetrics` 新增六个常量与各任务引用一致；`titleBarAlpha(scrollY: Int, collapseRange: Int): Float` 定义与 Task 6 调用同单位（px）；`IosButton` 参数顺序未改，Task 7 用命名实参 `modifier =` 传 `weight`。

**4. 计划在计划里就承认测不了的东西**：玻璃观感、糊的是不是对的内容、高对比开关在这台 vivo 上生效与否、滚动把手会不会被 bar 吃掉 —— 这四条全部是设备判据，写进了各任务的装机步，任何一条没跑成都要在提交信息与给用户的汇报里写明"未验证"。

---
