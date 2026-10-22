# vlive 界面改造为 Apple 当前设计语言（Liquid Glass）

**状态**：设计已逐节确认（§1 材料 / §2 结构 / §3 依赖与验收），本文档是那次确认的落纸版本。
**日期**：2026-10-21
**范围**：只改 `:app` 的表现层 + `minSdk` 连带；`:core` 的合成管线、`BatchViewModel` 的状态机、权限模型、日志与持久化一律不动。
**前置**：四条功能切片已落地（本地化/深色、报告分页折叠、目录持久化、自管理日志）。本文档通过后进入实现计划。

---

## 0. 要解决的问题与明确不做的事

现在这套界面是 **iOS 的配色与排版 + Material 的实心面**：卡片是纯色圆角矩形，按钮是纯蓝块，标题区跟内容一起滚。用户要的是"苹果最新设计风格"，而苹果 2025-09 起发布的语言是 **Liquid Glass**，26.1 加了 Tinted 档位、26.2 加了透明度控制、2026-09 的 iOS 27（"Golden Gate"）不是回撤而是把这套语言铺得更开（统一圆角/工具栏、修文字可读性、系统级透明度滑杆）。所以目标不是"看起来玻璃感强一点"，而是**照着苹果当前规范里那几条真正影响观感的规则改**：

- 玻璃只给**浮在内容之上**的控件（顶部标题栏、底部操作栏、目录弹窗顶栏），不铺满所有卡片；
- 主按钮按苹果按钮规范里的 **Tinted**（accent 着色 + 白字 + 高光），不是一个扁平的实心色块；
- accent 色一次给**四个值**（浅/深 × 常规/高对比）。

**不做**（这些都是讨论中明确排除的，写在这里防止实现时顺手加回来）：

| 排除项 | 原因 |
| --- | --- |
| 导航结构重排、多页面化 | 信息架构保持"单屏"不变；这一轮的改动量已经全在表现层 |
| 把每张卡片都做成玻璃 | 与苹果自己的采纳建议相反（自定义玻璃留给一两个关键浮动控件），而且会把需要实时模糊的层数从 2 条变成 5 条 |
| iOS 27 那种系统级透明度滑杆 | 这一屏没有承载它的设置面；加一个设置面属于范围扩张。改成**按外观自动选两档预设** |
| 自己把可滚动内容画两遍来伪造模糊 | 与报告分页切片"少组合几行"的立论直接冲突；选它等于承认那次的理由写错了 |
| 为 API 30 保留一条降级路径 | `minSdk` 提到 31，降级分支一条都不写（连带清单见 §3.2） |

---

## 1. 材料层：`GlassMaterial`

### 1.1 一句话规则

**一层玻璃只模糊一次。** 玻璃 = 真背景模糊（Haze/`RenderEffect`）+ 一层着色（tint）+ 上边缘镜面高光 + hairline 描边。全项目只有**三个 bar 区**注册模糊：主屏顶部标题栏、主屏底部操作栏、目录弹窗顶栏；条内的按钮**不再各自模糊一层**，它们复用所在 bar 已经模糊好的底，只加自己的 tint / 高光 / 描边。苹果那条"玻璃不许叠玻璃"就是被这样满足的，而不是靠把按钮改成实心。

> **这份配方里原本还有一项"阴影"，2026-10-22 实测之后删掉了**：`Modifier.shadow` 不在本层外面画阴影，而是在层内
> **预留**一圈扩散区，排在它后面的 `hazeBlur` / 高光 / 描边连子节点一起被裁进那一圈以内，于是贴边的 full-bleed 顶栏
> 读起来像"玻璃背后压着一块白色方形色块"。七台构建的读数与判据见 [decisions.md §5.21](../../decisions.md#521-玻璃-bar-上的-modifiershadow-全部删掉它在层内预留一圈把玻璃切成白色方块2026-10-22-装机裁定)。

> **实现落地的形状（2026-10-22 设备实测之后）**：注册模糊的 bar 区实际是**两个** —— 主屏顶部标题栏与目录弹窗顶栏。
> 主屏底部操作栏按 §3.4 那条预先约定的回退去掉了模糊（改用同文件的 `Modifier.iosGlassTray`：同一套层序，
> 只把 `hazeBlur` 换成 `.background(glass.tint)`），tint / 高光 / hairline 描边照旧。"一层玻璃只模糊一次"
> 与"玻璃不许叠玻璃"两条没有被削弱，只是受益面从三块变成两块。数字与截图见
> [decisions.md §5.19](../../decisions.md#519-玻璃只给三条浮动-bar卡片与按钮不各自糊一层2026-10-21-增量)。

### 1.2 卡片回到实心面

`SectionCard` / `ReportCard` 保持今天的实心底（浅 `#FFFFFF`、深 `#1C1C1E`），**不加玻璃**。理由有两条，第二条比第一条硬：

1. 实心卡片 + 浮动玻璃 = 苹果自己的采纳建议，也才分得出层次；
2. 需要实时模糊的绘制面从"每张卡片"降到**三块 bar**（主屏两条 + 弹窗一条），帧开销是可测的差别（验收口径见 §3.4）。**实测之后是两块**：底部那条按 §3.4 的回退不糊了，见 §1.1 末尾那条实现说明。

`IosPalette.card` / `groupedBackground` / `highlight` / `separator` / 文字色全部保持不变，`values-night/*.xml` 那套窗口级颜色（splash 边框）也不动——这一轮**不许碰窗口级主题**，冷启动那一帧的 OEM 换色问题已经结案（decisions.md §5.16）。

### 1.3 新增的数据结构

放在 [IosStyle.kt](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosStyle.kt) 里，与 `IosPalette` 并排，由同一个 `IosTheme` 的单次深浅判定一起发出去——**判定仍然只有 [IosStyle.kt:295-321](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosStyle.kt#L295) 这一处**，不许出现第二个 `isSystemInDarkTheme()`。

```kotlin
/** 一块玻璃的全部参数。只服务浮动控件（两条 bar + 弹窗顶栏）与 tinted 按钮。
 *  2026-10-22 实现落地：主屏底部操作栏改用不糊的 `iosGlassTray`，它照样吃这份参数的
 *  tint / highlight / stroke / radius，只有 `blurRadius` 不再被它消费。
 *  这份表里原本还有一项 `shadowElevation`，同日实测之后删掉，见 §1.1 那条注。 */
@Immutable
data class GlassMaterial(
    val tint: Color,            // 盖在模糊之上的着色层，决定"清澈"还是"着色"
    val blurRadius: Dp,         // 真模糊半径；两套预设取同一个值，见下
    val highlight: Color,       // 上边缘镜面高光的起点色（往下渐隐到透明）
    val stroke: Color,          // hairline 描边，用现成的 IosMetrics.hairline
    val radius: Dp,             // 通栏的两条 bar（主屏顶栏、弹窗顶栏）用 0dp；底部浮动栏用 glassRadius
)
```

**预设两套，由外观自动选**（不做用户可见的强度控制）：

| 参数 | 浅色（走"清澈"档） | 深色（走"着色"档） |
| --- | --- | --- |
| `tint` | `White @ 0.55` | `#1C1C1E @ 0.62` |
| `blurRadius` | `24.dp` | `24.dp`（刻意同值：只让着色不同，性能画像只有一种） |
| `highlight` | `White @ 0.70` | `White @ 0.18` |
| `stroke` | `#000000 @ 0.10` | `White @ 0.14` |

具体 alpha 值允许在真机上按可读性调（§3.4 的第 2 条就是这件事），但**调整只能发生在这一张表里**，调用点不许写散装 alpha。深色档比讨论中的初稿更轻：因为卡片回到实心，着色层只需要保证 **bar 上的文字**读得清，不必再为"隔着玻璃读卡片文字"兜底。

### 1.4 按钮按苹果的四档按钮规范

苹果当前的按钮样式是 **Filled / Tinted / Gray / Plain**，醒目操作是 **Tinted**（accent 着色的半透明块），不是实心色块。对应到 [IosControls.kt:231](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosControls.kt#L231) 现有的 `enum class IosButtonStyle`：

| 现有 | 改成 | 画法 |
| --- | --- | --- |
| `Primary` | Tinted | accent 着色填充（`accent.copy(alpha = 0.85f)`）+ 白字 + 上边缘高光 + hairline 描边；**自己不做第二层模糊** |
| `Secondary` | 清澈玻璃 | 近透明 tint + accent 文字 + hairline 描边；在 bar 内时看起来就是同一块玻璃上刻出来的一颗按钮 |
| `Destructive` | 保持 | 系统红不变 |

放在实心卡片里的按钮（目前只有权限卡里那颗"授予"，[BatchScreen.kt:162-168](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchScreen.kt#L162)）用同一套画法：Tinted 的填充是固定 alpha 的 accent，压在实心白卡上读作柔和的着色块，压在玻璃上读作 tinted glass——**一份实现，两处都对**，因此不为它加"在 bar 里/不在 bar 里"的分支。

**禁用态**沿用现有思路（淡文字，主按钮淡底），[IosControls.kt:250-251](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosControls.kt#L251) 那段"禁用态只淡填充与文字，不动结构"的注释在玻璃上更加成立，所以玻璃控件的禁用态**只动文字与 tint 的 alpha，不动模糊**。

**着色只给三种状态**：主操作、选中态、需要注意（错误/警告）。filter chip 选中、"取消"、进度条、状态点这些已经用色的地方保持现在的语义，不因为这次改造而扩大 accent 的覆盖面。

### 1.5 accent 四个值

苹果要求自定义强调色给 **浅/深 × 常规/高对比** 四个值。`IosPalette` 里现有的单个 `blue`（[IosStyle.kt:57](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosStyle.kt#L57) / [:74](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosStyle.kt#L74)）扩成：

```kotlin
/** 一个入口取四个值；绿/橙/红是状态色，不参与这轮扩列。 */
fun accentFor(dark: Boolean, highContrast: Boolean): Color
```

| | 常规 | 高对比（起点值，真机核对后可调） |
| --- | --- | --- |
| 浅色 | `#007AFF`（现状） | `#0040DD` |
| 深色 | `#0A84FF`（现状） | `#66B3FF` |

**高对比那两列的数值不是苹果公开数值的引用**，是本轮定的起点。验收规则是硬指标而不是出处，但这条指标
**只约束高对比那一列**（2026-10-21 整枝评审后改写，裁定与全部读数见
[decisions.md §5.19](../../decisions.md#519-玻璃只给三条浮动-bar卡片与按钮不各自糊一层2026-10-21-增量)）：

- **高对比档**：Tinted 主按钮上的白字必须 **≥ 4.5:1**。浅色 `#0040DD` 合成后实测 **5.7:1**（不透明 7.6:1）过了；
  深色 `#66B3FF` 合成后 **2.9:1**，**没过** —— 这一条记在 §5.19 的待办里，不许当成已结案。
- **常规档**：白字在 Tinted 底上是**量出来记录在案**的分数，不是要冲 4.5 的目标值 —— 浅色常规
  `#007AFF @ 0.85` 压在近白玻璃上 **3.3:1**，深色常规 `#0A84FF @ 0.85` 压在深色玻璃上 **4.7:1**。
  前者是 WCAG 1.4.3 的一条**已接受、已记录的偏离**，判据是它与 iOS 自己的系统按钮同形：不透明
  `#007AFF` 配白字也只有 **4.0:1**，苹果的系统按钮本身就不满足 4.5:1。**不许为了让这条线过 4.5 去调深
  常规 accent** —— 那等于抹掉本轮改造要的 iOS 身份，而身份才是这份色板存在的理由。

数都是按相对亮度在**合成色**上算的（`accent.copy(alpha = 0.85f)` over 该外观的玻璃底），不是设备上取的屏幕色；
玻璃底取 `tint` 压在该外观 `groupedBackground` 上的合成值（浅 `≈#F9F9FB`、深 `≈#111113`）。设备上的实测
仍归 §3.4 第 2 条。

选值信号读系统的"高对比度文字"开关。**实测校正（本机 `javap` + `platforms/android-37.0/data/api-versions.xml`）：真名是 `android.view.accessibility.AccessibilityManager.isHighContrastTextEnabled()`，标 `since="36"`**；本节初稿写的 `AccessibilityManager.isHighTextContrastEnabled` 在整个 API 37 表面里一条都搜不到，而 `android.accessibilityservice.AccessibilityManager` 这个类根本不存在。因为 36 高于 `minSdk 31`，判定**必须留着**（不判就是 API 31–35 启动即 `NoSuchMethodError`，lint 报 `NewApi`），31–35 一律走常规 accent。取不到信号或不生效时的处理不变：**只留常规两列**，并把缺口写进 decisions.md —— 不许静默地把四个值退化成两个而当作完成。

`LocalIosStyle` 的 `blue` getter 保留（现有 12 处 `s.blue` 全部在 [IosControls.kt](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosControls.kt) 里，`BatchScreen.kt` 一处都不用），改为返回 `accentFor` 的结果；`green/orange/red` 不动。

---

## 2. 结构层：主屏改成三层，两条真玻璃（第三条在弹窗，见 §2.5）

> 计划里"两条真玻璃"指主屏顶栏 + 主屏底部操作栏，第三条在弹窗。**2026-10-22 实测之后落地的形状是**：
> 主屏只剩顶栏这一条真玻璃，底部那条按 §3.4 的回退不糊（`iosGlassTray`），弹窗顶栏保留模糊 ——
> 全项目两条真玻璃，位置从"主屏两条"变成"主屏一条 + 弹窗一条"。下面 §2.1 的 z 序图与 §2.3 描述的是计划形态。

### 2.1 现状与目标结构

现在是单层：[BatchScreen.kt:117-131](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchScreen.kt#L117) 一个 `Box` 里放一个 `Column(verticalScroll)`，按钮在滚动流里（[:215-228](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchScreen.kt#L215)），整层被 `windowInsetsPadding(safeDrawing)` 内缩。

改成三层，同一个 `Box` 里按 z 序叠：

```
Box(fillMaxSize, background = groupedBackground)
├─ 内容层  Column(Modifier.hazeSource(hazeState)
│              .verticalScroll(state = scrollState)                    ← 没有 contentPadding 这个形参
│              .padding(paddingValues = PaddingValues(top = 状态栏 inset, bottom = §2.3 那个值)))
│          实心卡片照旧；大标题与应用名留在这一层里
├─ 顶部栏  玻璃，通栏、radius = 0，alpha = titleBarAlpha(scrollState.value, collapseRangePx)
│          只在 alpha > 0 时进入组合（不可见时不画，也就不付模糊的代价）
└─ 底部栏  玻璃，圆角，固定浮在内容之上，装 开始合成 / 取消
```

（实测校正：compose-foundation 1.12.1 的 `verticalScroll` **没有** `contentPadding` 形参（javap 只有
`enabled` / `flingBehavior` / `reverseScrolling` / `overscrollEffect`），所以那份 `PaddingValues` 由挂在
`verticalScroll` **之后**的 `Modifier.padding(paddingValues = …)` 来吃。链上它之前的 modifier 缩的是视口，
之后的才是滚动内容自己的首尾留白 —— 本节初稿把这段留白写成 `verticalScroll` 的 `contentPadding` 形参，会让人去加一个不存在的参数。）

内容层是**主屏这个窗口里唯一的 haze source**，两条 bar 是它的两个消费者；弹窗自己还要一个（§2.5），全项目一共两个。

### 2.2 顶部栏：淡入由滚动位置驱动，且只此一处有新逻辑

大标题不进 bar（保持"从滚动流里推出去"的 iOS 手感），bar 里画的是折叠后的紧凑标题。alpha 必须由**纯函数**算，因为它是这一轮唯一值得单测的逻辑：

```kotlin
/** 滚动 [scrollY]（px）时玻璃标题栏的不透明度；[collapseRange] 是同单位的 px。 */
fun titleBarAlpha(scrollY: Int, collapseRange: Int): Float =
    if (collapseRange <= 0) 1f else (scrollY.toFloat() / collapseRange).coerceIn(0f, 1f)
```

- `collapseRange` 取 `56.dp` 换算成 px（`IosMetrics.collapseRange`）；调用点用 `LocalDensity` 换算，函数本身只收 Int，保持可测；
- `collapseRange <= 0` 返回 `1f`：边界条件宁可让 bar 一直可见，也不能让标题在某种布局下永久消失；
- **不做动画**：alpha 直接从 `scrollState.value` 求值。惯性滚动时套一层 `animateFloatAsState` 只会让 bar 落后于手指；
- bar 用 `graphicsLayer { alpha = … }`，避免每帧重新组合 tint；`alpha == 0f` 时整个 bar 不参与组合（同时解决无障碍里"读得到一个看不见的标题"的问题）。

### 2.3 底部栏：两颗按钮从滚动流里搬出来，占同一个入场序号

`开始合成`（Tinted）与`取消`（清澈玻璃）**同一行**：主按钮占剩余宽度，取消按钮固定在右侧、未运行时禁用（保持今天"禁用而不是隐藏"的语义，用户随时看得见自己能不能取消）。

- 内容高度 = `IosMetrics.buttonHeight`（50.dp，[IosStyle.kt:204](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosStyle.kt#L204)）+ 上下各 12.dp = 74.dp；
- 水平内缩 16.dp，`radius = IosMetrics.glassRadius`（30.dp，刻意小于半高，读作苹果的连续圆角而不是胶囊）；
- 底部留白 = `IosMetrics.barBottomGap`（8.dp）+ 导航栏 inset；
- 入场动效：bar 的容器**沿用腾出来的序号 5**。搬出去之后不需要给后面的 6/7/8 重编号——序号只是延迟倍数，保留原值等于保留现有节奏，[BatchScreen.kt](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchScreen.kt) 里其余调用点一个都不动。

内容层那段下沿留白（`verticalScroll` 之后那个 `padding(paddingValues = …)` 的 `bottom`）= `74.dp + 8.dp + 导航栏 inset + 16.dp`，让最后一张卡片能被推到底部栏上沿之上。实现里这两处共用同一个 `insets.calculateBottomPadding() + IosMetrics.barBottomGap`，等式只在代码里声明一次。

### 2.4 inset 归属必须重新分工（这是最容易改坏的一处）

| 层 | 现在 | 改成 |
| --- | --- | --- |
| 内容层 | `windowInsetsPadding(safeDrawing)`（整层内缩，视口被压小） | **去掉**，改为 `verticalScroll(state = …)` 之后那句 `.padding(paddingValues = PaddingValues(top = 状态栏 inset, bottom = §2.3 那个值))`（`verticalScroll` 没有 `contentPadding` 形参，见 §2.1 的实测校正） |
| 顶部栏 | 不存在 | 上边吃状态栏 inset，再画 `IosMetrics.topBarHeight`（44.dp）高的内容 |
| 底部栏 | 不存在 | 自己吃导航栏 inset |

区别不是风格而是几何：`windowInsetsPadding` 把视口本身缩小，内容永远到不了 bar 底下，玻璃就糊不出东西；挂在 `verticalScroll` 之后的那段 `padding` 保持满屏视口、只把首尾留白推出来，内容才真的会从玻璃下面划过。

inset 一律现算，不许写死 dp。**实测校正（Task 6/7/8）**：落地代码不再用本节初稿那两个切口
（`safeDrawing.only(InsetsSides.Top).asPaddingValues()` 与 `navigationBars.asPaddingValues()`），
而是**每扇窗口一个 `WindowInsets.safeDrawing.asPaddingValues()`**，四条边都从它算：
`calculateTopPadding()` / `calculateBottomPadding()` / `calculateLeftPadding(layoutDirection)` /
`calculateRightPadding(layoutDirection)`。这两个 `calculate*Padding` 是 `PaddingValues` 的**成员方法**，
不是顶层扩展函数（import 它们直接编译不过）。顶栏、底部栏、内容层读的是同一个 `insets` 对象，两处数字
才不会各算各的。用 `safeDrawing` 而不是 `navigationBars` 的代价是它也把 IME 折了进来 —— 这一屏
没有任何输入框（`app/src/main` 里 grep 不到 `TextField` / `BasicTextField`），所以今天这条边恒为 0，
真要加文本字段时得重新分工。

**只有上下两条边的归属变了，左右不变**：内容层与两条 bar 仍要吃横向 inset（刘海/打孔屏靠的就是原来
`safeDrawing` 那一句，它同时管四条边；换形态时最容易把左右一起丢掉）。落地做法是横向 inset 留在同一个
`PaddingValues` 里（内容层那份只吃裸 inset，两条 bar 吃"裸 inset + `screenMargin`"的同一份 `sideGutter`），
**没有**再挂 `windowInsetsPadding(InsetsSides.Horizontal)` —— 它缩的是视口宽度，虽然不影响竖直划过，
但会把"bar 与卡片同宽"变成两条不同来源的算式。

### 2.5 目录弹窗：第二个 haze source，玻璃不许叠玻璃

`IosSheet` 是 `Dialog`——**独立窗口**，拿不到主屏那个 source 的位图，所以它必须自己注册一个 `hazeSource`（给弹窗内的列表），顶栏（[IosControls.kt:611-615](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosControls.kt#L611) 那个 52.dp 的 `Box`）是这块新 source 的唯一消费者。

两条硬约束：

1. 弹窗整体仍然是实心 `groupedBackground` 表面（[IosControls.kt:526](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosControls.kt#L526) 不变）——**主屏的玻璃不铺到弹窗里**，弹窗顶栏也不叠在另一块玻璃上；
2. 两个 `HazeState` 必须是两个实例（各自 `remember`），共用一个会把主屏的位图当成弹窗的模糊源，跨窗口只会得到空白或错位。

还有一条是几何，不补就会做出一个"糊空气"的顶栏：现在弹窗里顶栏和列表是**竖着排**的（顶栏占 52.dp，列表从它下面开始），所以顶栏底下没有任何可糊的东西。要做成 §2.1 那种叠层——列表从弹窗顶部铺满、顶栏压在它上面、列表在同一条 `verticalScroll` 之后用 `padding(paddingValues = PaddingValues(top = 状态栏 inset + 顶栏高))` 把第一行让出来（同样没有 `contentPadding` 形参可用）。`IosSheet` 的签名与 `rowsFor`/换层动画都不受这件事影响。

弹窗是**独立窗口**，四条 inset 必须在 `Dialog { }` 里面现算（`asPaddingValues()` 抓的是求值那一刻所在窗口的 density 与 layoutDirection）；在外面算好传进来，只有两个窗口都全屏时才碰巧相同。

`IosSheet` 的签名不变（`title/subtitle/cancelText/confirmText/…`），改动全在实现里；`BackHandler` 仍在 `content` 内那层（[BatchScreen.kt:470-472](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchScreen.kt#L470)，原因注释已经写在那里）。

### 2.6 动效：加一条、禁一条、其余不动

- **加**：顶部栏 alpha（§2.2，无动画）；
- **禁**：**不做模糊半径动画**。半径从 0 长到 24.dp 意味着 220ms 里每帧重算一次全屏模糊，为一点收尾手感付这个价不值得；
- **不动**：`IosEntrance` 的分段上浮（[IosEntrance.kt:26-58](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosEntrance.kt#L26)）、`IosSwitch` 的 220ms、进度条推进、弹窗换层的推入/弹出。玻璃不改变时长与曲线。

---

## 3. 依赖、minSdk 连带、测试与验收

### 3.1 依赖与"第一步必须是核实 API"

Compose 在没有库的情况下做不出背景模糊：`graphicsLayer { renderEffect = blur(…) }` 糊的是**这一层自己的内容**，而 `RenderEffect` 从 **API 31** 起才有——这正是 `minSdk` 要抬的原因（用户已确认抬到 31，且**不为 30 写降级路径**）。

选 **Haze**（`dev.chrisbanes.haze`，Apache-2.0）：它是这条路上唯一在长期维护的库，`hazeSource` 标记被糊的内容、消费者侧挂一个模糊 modifier。

```toml
# gradle/libs.versions.toml
haze = "2.0.1"
haze = { module = "dev.chrisbanes.haze:haze", version.ref = "haze" }
```

**实现切片 ① 的一半工作是核实，不是集成。** 文档与已发布构件不一致这件事已经预先知道：README 写的是 `hazeSource` + `hazeBlur`（配置用 `blurRadius(20.dp)` / `tint(…)`），而 2.0 的 alpha 版本里这个 modifier 叫 `hazeEffect`。所以不许照文档抄代码，必须按顺序做：

1. 加依赖，`:app:dependencies --configuration debugCompileClasspath` 看解析到的真实版本与是否有额外变体（`haze-effects` 之类的 artifact 是否存在）；
2. 用编译探针确认三个签名的真名：source modifier、consumer modifier、参数的 builder 形式；
3. 确认它对 **Compose BOM 2026.09.00 / Kotlin 2.4.21 / AGP 9.4.1 / compileSdk 37.0** 能编过——`checkDebugAarMetadata` 这类检查历史上就咬过版本（[app/build.gradle.kts:13-16](file:///D:/projects/piece/live-photo-batch/app/build.gradle.kts#L13) 的注释是前车）；
4. 核实结果（真名 + 是否需要第二个 artifact）写进本文档 §3.1 或 decisions.md，之后所有代码照真名写。

核实不通过（编不过或 API 形状完全不同）= 停下来重议，不许当场改成自绘模糊。

### 3.2 minSdk 30 → 31 的连带清单（最容易做一半的地方）

逐项列出，实现切片 ① 的完成判据就是这张表全部打勾。**注意：README 里没有 `minSdk` 或 Android 版本的声明**（只讲 JDK 17 与 `compileSdk 37.0`，[README.md:92](file:///D:/projects/piece/live-photo-batch/README.md#L92)），所以它不在清单里——早期讨论把它记成"README 两处"是错的，实际落在下面这些文件。

| # | 位置 | 动作 |
| --- | --- | --- |
| 1 | [app/build.gradle.kts:23](file:///D:/projects/piece/live-photo-batch/app/build.gradle.kts#L23) | `minSdk = 30` → `31` |
| 2 | [app/build.gradle.kts:53-55](file:///D:/projects/piece/live-photo-batch/app/build.gradle.kts#L53) | splashscreen 依赖的注释说"API 30 上也能覆盖"，改成 31 的说法。**（整枝评审后连"31 的说法"也作废：地板就是 31，"31 上也能覆盖"不再是卖点 —— 注释现在写它真正买到的东西：那套 `windowSplashScreen*` 主题属性与退出动画回调，见 decisions.md §5.20）** |
| 3 | [PermissionGate.kt:46](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/perm/PermissionGate.kt#L46) | 删 `Build.VERSION.SDK_INT < Build.VERSION_CODES.R \|\|` —— minSdk 31 下恒假，`isExternalStorageManager()` 直接调用 |
| 4 | [PermissionGate.kt:51-61](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/perm/PermissionGate.kt#L51) | `if (>= R)` 恒真 → 只留 `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` 分支，删 `ACTION_APPLICATION_DETAILS_SETTINGS` 兜底；`Build` 随之可能不再需要 import |
| 5 | [PermissionGate.kt:29-33](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/perm/PermissionGate.kt#L29) + `:41` | KDoc 整节"短路与 API 30 / minSdk 30 只是双保险"要重写：这段理由在新 minSdk 下已经不存在（现有 JVM 单测只测注入的 lambda，[PermissionGateTest.kt:13-24](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/vlive/perm/PermissionGateTest.kt#L13)，删分支不破测） |
| 6 | [AndroidManifest.xml:7-8](file:///D:/projects/piece/live-photo-batch/app/src/main/AndroidManifest.xml#L7) | `WRITE_EXTERNAL_STORAGE maxSdkVersion="29"` 在 minSdk 31 下是**永远不生效的死声明** → 删。`READ_EXTERNAL_STORAGE maxSdkVersion="32"`（`:5-6`）**保留**，31–32 仍归它管 |
| 7 | [MainActivity.kt:37](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/MainActivity.kt#L37) | 注释里"API 30 走 compat、31+ 走系统两条分支"在 minSdk 31 下只剩一条分支。显式 `setTheme` **保留**，但理由改成"不把恢复时机压在库的内部行为上" |
| 8 | [values/themes.xml:16](file:///D:/projects/piece/live-photo-batch/app/src/main/res/values/themes.xml#L16) + `:20` | 两处 API 30 说法同步 |
| 9 | [mipmap-anydpi-v26/ic_launcher.xml:3](file:///D:/projects/piece/live-photo-batch/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml#L3) | 注释"minSdk 30 ≥ 26"改成 31；`-v26` 这个限定符本身**照旧不删**（decisions.md §5.12 的构建破坏结论与 minSdk 无关） |
| 10 | [.github/workflows/release.yml:78](file:///D:/projects/piece/live-photo-batch/.github/workflows/release.yml#L78) | 发版说明"Android 11+（minSdk 30）" → "Android 12+（minSdk 31）" |
| 11 | [docs/testing.md:141](file:///D:/projects/piece/live-photo-batch/docs/testing.md#L141) | "Android 11+ 设备" → "Android 12+" |
| 12 | `docs/architecture/batch-flow.dot:29` 的 "低于 API 30 短路放行" + 由它重渲染的 `batch-flow.svg`（本轮补进清单：架构图里的这句会随分支删除变成假话）；以及 [docs/architecture/system-model.md:44](file:///D:/projects/piece/live-photo-batch/docs/architecture/system-model.md#L44)、[:88](file:///D:/projects/piece/live-photo-batch/docs/architecture/system-model.md#L88)、[system-model.evidence.md:103](file:///D:/projects/piece/live-photo-batch/docs/architecture/system-model.evidence.md#L103)、[vlive.structurizr.dsl:74](file:///D:/projects/piece/live-photo-batch/docs/architecture/vlive.structurizr.dsl#L74)、`:88` | 设备要求写 `minSdk 30` 的地方与"低于 API 30 短路放行"这句描述，全部随之更新；DSL 改完跑 `node docs/architecture/dsl-check.mjs` |
| 13 | [docs/decisions.md:291](file:///D:/projects/piece/live-photo-batch/docs/decisions.md#L291)（§5.11 图标与启动过渡）、[:297](file:///D:/projects/piece/live-photo-batch/docs/decisions.md#L297) + [:302](file:///D:/projects/piece/live-photo-batch/docs/decisions.md#L302)（§5.12） | §5.11 那句"API 30 走 compat、31+ 走系统两条分支"在 minSdk 31 下只剩一条分支，理由要跟着改（配合 #7）；§5.12 把 `PermissionGate.kt:46/51` 那两条 `ObsoleteSdkInt` 描述成"有意保留的双保险"——分支删掉后这个说法变成假的，必须改写而不是删了了事（它"`-v26` 不能删"的结论本身不受影响）。`minSdk` 在 decisions.md 里只出现在这两节（全文 grep 过），§6.2 讲的是 compileSdk/targetSdk、**不受影响**；本轮决定另起两节：**§5.19 玻璃只给浮动控件**、**§5.20 minSdk 抬到 31 与它带走的分支** |

**lint 的可验证后果**：[docs/testing.md:108](file:///D:/projects/piece/live-photo-batch/docs/testing.md#L108) 记录的基线是 12 条信息级。**本轮从磁盘上那份 SARIF 实读了一遍**（`app/build/reports/lint-results-debug.sarif`，2026-10-21 09:37 生成）：`results` 共 12 条，其中 `ObsoleteSdkInt` 恰好 3 条，位置是 `PermissionGate.kt:46`、`PermissionGate.kt:51`、`mipmap-anydpi-v26`（目录级）。删掉 #3/#4 那两个分支后 `ObsoleteSdkInt` 应为 1 条（只剩不能删的 `-v26`），基线 **12 → 9**。这一条是切片 ① 的客观完成证据，不是"看起来没问题"——判据就是重新跑 `:app:lintDebug` 后这两个数字。

> **实测修正（Task 2，2026-10-21）**：预测过 12 → 10，实际是 12 → 9 —— 被删的 `requestIntent` `else` 分支里那条 `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` 还带走一条 `UseKtx`（原基线里 `UseKtx` 有 2 条，其一就在这条死分支上）。`ObsoleteSdkInt` 3 → 1 的判据不变。

### 3.3 测试

- **新增纯函数单测** `titleBarAlpha`，放 `app/src/test/kotlin/dev/zayn/vlive/ui/TitleBarAlphaTest.kt`（与被测函数同包——现有 `log/`、`browse/`、`perm/` 三个测试目录就是这个照法）：`0 → 0f`、`>= range → 1f`、`range 内线性`、`负 scrollY → 0f`（overscroll 时不能让 bar 变负 alpha）、`range <= 0 → 1f`、`range = 1`。约 6 条。
- **现有 51 条 app JVM 测试必须全绿**（[docs/testing.md:32](file:///D:/projects/piece/live-photo-batch/docs/testing.md#L32)）：`BatchViewModelTest` 14 条不碰 Compose，`DirBrowserTest` 15、`ReportPageTest` 8、`LogRotationTest` 8、`TouchedPathsForTest` 4 不受表现层影响，`PermissionGateTest` 2 条只测注入的 lambda（所以 §3.2 的 #3/#4 不破测，但必须确认它仍是 2 条全绿）。总数 204 → 约 210（core 145 + app 57 + androidTest 8）。
- **`:core` 145 条与 8 条端口契约 instrumented 测试一个字节都不该动**；改了就是越界。
- `./gradlew :app:lintDebug` 除 §3.2 那条 12→9 之外不许新增任何条目（`HardcodedText` / `MissingTranslation` 等门槛项继续为 0）。
- 玻璃本身**不写 UI 单测**：`RenderEffect` 的行为在 JVM 单测里取不到，写出来只能测"modifier 挂上了"，属于自欺。它的证据全在 §3.4 的设备验收。

### 3.4 设备验收（V2419A，无线 adb，判据是数字不是眼睛）

装机与录屏流程按 [docs/testing.md](file:///D:/projects/piece/live-photo-batch/docs/testing.md) 里已记录的那套（`push` + `pm install -r -t`，屏幕要醒；`appops` 授权）。**两轮外观 × 两种语言**都要过：

1. 浅色 + 中文，深色 + 英文各截一遍：主界面静止、滚动到大标题折叠完成、底部栏下还有一张卡片划过；
2. **玻璃上的文字可读性**：紧凑标题、`开始合成` 上的白字、`取消` 上的 accent 字——在两种外观下逐条截图核对；不满意只改 §1.3 那张表。判据按 §1.5 改写后的那条走：**白字在 Tinted 底上是把实测分数记进 decisions.md §5.19**（那里已有按相对亮度算出的一组数：浅常规 3.3:1、深常规 4.7:1、浅高对比 5.7:1、深高对比 2.9:1 未过），设备读数与该组对照；**高对比那一列才必须 ≥ 4.5:1**。这一项据此能诚实地过或诚实地不过，不需要把任何一个数字掰弯 —— 也别靠调深常规 accent 让它"过"。**不许为了让这条线过 4.5 去调深常规 accent**。**深色高对比那一格（2.9:1）已由用户裁定放宽**（2026-10-22）：
`s.blue` 一个 token 兼「accent 当文字」与「accent 当填充」两个角色、两头判据相反，所以不拆 token、不改值，
2.9:1 作为**已裁定接受的偏离**记在 §5.19。硬件侧的旧说法（"V2419A 是唯一测试硬件且当前离线，今天没有任何读数
能把这一格判掉"）已经作废：本轮装机用的是 API 36（V2419A 与新装的 `vlive_api36` 模拟器同级），开关够得着；
实测结果是**信号没送到应用** —— 把 `accessibility_high_text_contrast_enabled` 写成 1 并冷启动后，取样像素与
关掉时逐点相同（`D:/tmp/em31_hc_off_light.png` / `em32_hc_on_light.png` / `em33_hc_on_dark_en.png` /
`em34_hc_off_dark_en.png`）。所以高对比那一列仍然没有被任何设备渲染过一次，这条缺口按 §1.5 的规矩继续记着。
3. **弹窗**：打开目录弹窗，确认顶栏糊的是弹窗自己的列表（不是空白/错位），且没有玻璃叠玻璃；在 root 层与 `DCIM → Camera` 各截一张；
4. **帧率**：让分页报告（36 项那批，删除开关关掉）在底部栏下面连续滚动，读 `adb shell dumpsys gfxinfo dev.zayn.vlive` 的 janky frames 百分比。**先量改造前的基线再量改造后**，两个数字一起记进 decisions.md。预算：改造后 **不超过基线 +3 个百分点，且绝对值 ≤ 10%**；每个数字取三次采样的中位数，单次读数不算数；
   **实测补注（2026-10-22，V2419A / API 36）**：判据**没有**用这一条点名的 `Janky frames:` 字段 —— 那一行在
   九个样本（三台构建 × 三遍）里全部读 `0 (0.00%)`，同批 dump 的 `Number Missed Vsync: 0` 也全为 0，而
   `Janky frames (legacy)` 同一批数到 78–136 帧：这台机器上被点名的字段不触发，判据只能落在
   **legacy 那一行 + 帧时间直方图**上。这是判据适用性的修正，两条线（+3pp、≤10%）的数值一个字没改。
   采样规模也不是"36 项那批"而是 12 项（夹具在模拟器与手机上都是 12 对，见 §5.19 的总帧数 888–934）。
   数字与判定（超预算 → 回退已启用 → 仍未回到预算）全在
   [decisions.md §5.19 的"帧预算"一段](../../decisions.md#519-玻璃只给三条浮动-bar卡片与按钮不各自糊一层2026-10-21-增量)。
5. 权限缺失那一屏（红色提示卡 + 授予按钮）与批次运行中（进度 + 取消可用）两种状态各截一张——这两处的按钮画法在 §1.4 里被改了，不能只验主路径；
6. 冷启动 splash 不受影响（§1.2 的约束）：截一张 splash 帧，与改造前对比。

**预先约定的回退**（数字不达标时走这条，不临场发挥）：**底部栏去掉模糊，保留 tint + 高光 + 描边**——玻璃感的主要来源是着色与边缘高光，模糊是最贵的一项；顶部栏因为 alpha 常态为 1、覆盖面积小，保留模糊。走回退时必须把两个 janky 百分比一起写进 decisions.md，而不是静默改完。

> **这条回退已经启用了**（`cab321b`，2026-10-22）：基线 10.13% → 三条 bar 全糊 13.62%（+3.49pp，同时越过两条线），
> 换成 `iosGlassTray` 之后 13.20% —— **仍然在预算外**，两个数字与散布都写在 §5.19。实际实现因此是
> **两个模糊面**（主屏顶栏 + 弹窗顶栏），底部那条只有 tint + 高光 + 描边；它换掉的代价（卡片文字从
> 底部条底下透出来、和按钮文字打架，两种外观都拍到了）也记在同一段里，没有当成"通过"。

**第 1、3、5、6 条的实测记录（2026-10-22 中午，`vlive_api36` / API 36 / 1080x2400，装的是 `cab321b` 的包；图在 `D:/tmp/`）**：

| 条目 | 结果 | 用的图 |
| --- | --- | --- |
| 1 两轮外观 × 两种语言 × 三种状态 | 过。四种组合的静止 / 大标题折叠完成 / 卡片划到底部条底下都截了 | `em01_now.png`、`em20_light_en_titlebar_in.png`、`em34_hc_off_dark_en.png`、`em40_dark_en_scrolled.png`、`em50_dark_zh_idle.png`、`em51_dark_zh_scrolled.png`、`em53_light_zh_idle.png`、`em54_light_zh_scrolled.png` |
| 2 玻璃上的文字可读性 | 过（眼睛这一关）：`vlive` 紧凑标题、`开始合成`/`Start merging` 白字、`取消`/`Cancel` accent 字在两种外观下都读得清；对比度数字与裁定见 §1.5 / §5.19 | 同上 |
| 3 弹窗顶栏糊的是自己的列表 | 过。root 层滚起来后目录图标在玻璃下糊成一团、`关闭 / 选这里 / 标题` 清晰在上；`DCIM → Camera` 下级也截了；全程没有玻璃叠玻璃。**开出一条缺陷**：副标题被 bar 下边缘裁掉（记在 §5.19 末尾与技术债表）。复现注记：1080x2400 这块屏上 root 的 14 项**装得下、滚不动**，所以为了造出可滚动的列表临时 `mkdir` 了 20 个空目录 `zzprobeNN`，截完已删 | `em11_sheet_root_idle.png`、`em13_sheet_root_scrolled.png`、`em14_sheet_dcim.png`、`em41_dark_sheet_scrolled.png`、`em14_crop_bar.png` |
| 5 权限缺失态 / 批次运行态 | 过。红字卡 + Tinted 授予按钮 + 底部 `Start merging` 淡底淡字禁用；运行中那帧 `Progress 1/12`、`Cancel` 是亮 accent 可点 | `em30_perm_denied_light_en.png`、`emrun/g040.png` |
| 6 冷启动 splash 不受影响 | 过。同一台模拟器上分别装 `739b388` 与 `cab321b` 录冷启动，两帧逐点比对（26,760 个采样点）最大差 12/765、平均 0.11、>30 的点 0 个 | `emsp_head/s11.png`、`emsp_basef/b12.png` |

中文那一轮**不是**靠系统语言切出来的：`settings put system system_locales zh-CN` 写完冷启动界面照旧英文，
改用按应用的 `cmd locale set-app-locales dev.zayn.vlive --locales zh-CN` 才渲染出中文，验完还原
（`en-US` + `system_locales` 删回 `null`，证据 `em60_restored_light_en.png`）。
**没验到的**：底部条的非按钮带上**点击**是否穿透（这份夹具里没有任何可点元素能被滚到它底下，报告只有一页；
手势穿透与顶栏的点击穿透都实测通过了）。

### 3.5 实现切片（五次提交，每步测试与 lint 全绿）

| 切片 | 内容 | 判据 |
| --- | --- | --- |
| ① | Haze 依赖核实 + 集成；`minSdk 31` 与 §3.2 全部 13 项连带 | 编译过；lint `ObsoleteSdkInt` 3→1、基线 12→9；51 条 app 测试全绿 |
| ② | 材料层：`GlassMaterial` + 两套预设 + accent 四值 + `accentFor` + 按钮 Tinted/清澈玻璃改法 | `IosButton` 三种样式在实心卡与 bar 内都不破版（设备截图）；测试数不变 |
| ③ | 结构层：内容层 haze source、顶部栏 + `titleBarAlpha`、底部栏、§2.4 的 inset 重新分工 | `titleBarAlpha` 单测落地；滚动不被 bar 吃掉（设备实测） |
| ④ | 弹窗的第二个 haze source | 弹窗顶栏糊的是自己的列表；两个 `HazeState` 实例 |
| ⑤ | 设备验收 + 文档：§3.4 全跑、janky 基线/结果两个数字、decisions.md 新增两节、testing.md 更新条目数与设备判据 | 两轮外观 × 两种语言的截图与数字都在档 |

切片 ② 之后界面已经"半玻璃"（按钮改了、bar 还没出来），③ 之后才成形状。这是可以接受的：每片都独立可回滚，且用户要求过"每个逻辑切片边界提交一次，好区分 CI 结果"。

### 3.6 风险与未验证项（写在这里，不许实现时才发现）

| 风险 | 处理 |
| --- | --- |
| Haze 2.0.1 的实际 API 名/参数与文档不符，或与 BOM 2026.09 冲突 | §3.1 的核实前置到切片 ① 的第一步；不符就停下重议，不自绘模糊 |
| OEM ROM 的 `RenderEffect` 模糊慢或效果异常（这台 vivo 已经证明会替换主题颜色，decisions.md §5.16） | 验收第 4 条用数字判；异常则走 §3.4 的约定回退。**2026-10-22 判了**：不是效果异常（糊出来的内容两种外观下都对），是**开销超线** —— legacy 10.13% → 13.62%，两条线一起越，于是走了回退；回退后 13.20% 仍在预算外，如实记在 §5.19 |
| 非交互的 bar 挡在内容之上，滚动把手是否还能穿过它 | Compose 的命中测试会把事件投给 z 序下所有可接收节点、`Text` 不消费，所以理论上可用——**但这条是推测，必须在设备上验**（切片 ③ 判据）。不通时的手段是给 bar 的容器显式声明透明命中（`Modifier.hitTestBehavior(HitTestBehavior.Transparent)`），不靠删 bar 解决。**2026-10-22 已验，推测成立**：从玻璃顶栏里起手的滑动照样滚后面的内容（100px 位移实测带动标记行走 89px），从底部条的两按钮之间那条缝与下沿起手同样成立；顶栏上的点击穿透到下面的行（点 y=150 打开的是被压住的"源目录"行）。**没有加过 `hitTestBehavior`**，一条代码都没为此改。唯一没验到的是"底部条的非按钮带上的点击穿透"——这份夹具里没有任何可点元素能被滚到它底下，见 §3.4 末尾 |
| 手势导航下 `navigationBars` inset 可能为 0，底部栏贴到屏幕边 | 用现算的 inset + 8.dp 固定间距兜住，两种导航形态各截一张。**手势形态看过**（`vlive_api36`，inset 为 0 的那一侧）：底部条与屏幕下沿之间仍是那条 8.dp 间隙，home indicator 压在条上，顶栏与状态栏之间没有读出没糊的缝（`em20_light_en_titlebar_in.png`、`em40_dark_en_scrolled.png`、`em54_light_zh_scrolled.png`）。**三按钮形态在 shipped 构建上没截** —— 盘上唯一那张 `D:/tmp/T9z_navbar_3button.png` 是回退前的包，不能算这条的证据 |
| 高对比度开关在这台 vivo 上不存在或不生效 | §1.5 已写明：只留常规两列，并把缺口记进 decisions.md，不许静默退化。**2026-10-22 复核**：硬件是 API 36，档位判断这一关过了，但把 `accessibility_high_text_contrast_enabled` 写成 1 并冷启动后取样像素与关掉时逐点相同（V2419A 与模拟器同一结论），这一版 Settings 里也没有"高对比文字"这一行 —— **缺口换了一条成因，仍然存在**，高对比那一列至今没被任何设备渲染过。见 §5.19 |
| 玻璃 + 分页 + 折叠叠在一起后的重组开销 | 卡片保持实心（§1.2 第 2 条理由）；验收第 4 条的滚动场景就是专门挑这条的 |

---

## 附录 A：本轮引用的外部依据

这些是设计取舍的出处，不是可复制的代码；与本文档冲突时以本文为准，与苹果当前规范冲突时以苹果为准并回来改本文档。

- Apple **Liquid Glass**（iOS 26，2025-09 发布）及其后修订：26.1 的 "Tinted" 档、26.2 的透明度控制、iOS 27 / macOS 27 "Golden Gate"（WWDC 2026-06 公布、2026-09 上线）的可读性修正、统一圆角与工具栏、系统级透明度滑杆。**iOS 27 没有回撤这套语言。**
- 苹果的采纳建议明确反对"把所有面都做成玻璃"：自定义玻璃留给一两个关键**浮动**控件，实心面上的卡片读起来更原生，玻璃不许叠玻璃；纯模糊太透、会抢焦点——这正是 26.1 加着色的原因。
- 苹果按钮规范四档：Filled / **Tinted** / Gray / Plain；醒目操作用 Tinted。自定义 accent 需要浅/深 × 常规/高对比四个值。控件着色只给主操作、选中、需要注意三种状态。
- Android 侧事实：`RenderEffect` 模糊 API 31+；`graphicsLayer { renderEffect = blur(…) }` 只糊本层。Haze 文档：<https://chrisbanes.github.io/haze>（构件坐标与签名以 §3.1 核实结果为准）。

## 附录 B：改动落点一览

| 文件 | 改什么 |
| --- | --- |
| `gradle/libs.versions.toml`、`app/build.gradle.kts` | Haze 依赖；`minSdk 31`；注释同步 |
| `app/src/main/kotlin/dev/zayn/vlive/ui/IosStyle.kt` | `GlassMaterial` + 两套预设 + `accentFor` + `IosMetrics` 新几何常量（`glassRadius`/`topBarHeight`/`barBottomGap`/`collapseRange`） |
| `app/src/main/kotlin/dev/zayn/vlive/ui/IosControls.kt` | `IosButtonStyle` 的 Tinted/清澈玻璃画法；`IosSheet` 顶栏玻璃 + 弹窗内第二个 haze source |
| `app/src/main/kotlin/dev/zayn/vlive/BatchScreen.kt` | 三层结构、`hazeSource`、顶部栏、底部栏、inset 分工 |
| `app/src/main/kotlin/dev/zayn/vlive/ui/TitleBarAlpha.kt`（新） | `titleBarAlpha` 纯函数 |
| `app/src/test/.../TitleBarAlphaTest.kt`（新） | §3.3 的约 6 条 |
| `app/src/main/AndroidManifest.xml`、`MainActivity.kt`、`values/themes.xml`、`mipmap-anydpi-v26/ic_launcher.xml`、`perm/PermissionGate.kt` | §3.2 的 minSdk 连带 |
| `.github/workflows/release.yml`、`docs/testing.md`、`docs/decisions.md`、`docs/architecture/*` | §3.2 表里的文档与 DSL 连带；§3.4 的验收数字入档 |
