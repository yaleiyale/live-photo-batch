# 文档索引

- **基准**：`ff9ad98`（分支 `feat/live-photo-batch`）+ 其后的工作区增量，采集 **2026-10-20**、刷新 **2026-10-21**（品牌与包名改到 `vlive`；图标与开屏过渡、入场动画、单列目录浏览、存储根别名）。所有文档描述的是 **as-built（代码现状）**。
- **建模方式**：`architecture-visualization` 插件下的 `system-modeler` + `c4model`（结构）、`flow-visualizer` + `graphviz`（流动）。
- **一句话规则**：文本结论在 `.md`，图源在 `.dsl` / `.dot`，`.svg` 是**派生产物**（别手改），逐条断言的文件与行号只在 [architecture/system-model.evidence.md](architecture/system-model.evidence.md) 里维护一份。

## 文档清单

| 文件 | 回答的问题 | 何时该读 |
| --- | --- | --- |
| [/README.md](../README.md) | 这是什么、怎么用、怎么装 | 第一次接触 |
| [architecture/system-model.md](architecture/system-model.md) | 边界、模块、端口契约、数据与状态、并发模型、未知区 | 要动结构 / 要理解分工 |
| [architecture/system-model.evidence.md](architecture/system-model.evidence.md) | 每条架构断言的证据与置信度 | 不信某条结论时；改完代码对齐行号时 |
| [architecture/vlive.structurizr.dsl](architecture/vlive.structurizr.dsl) | C4 四视图（Context / Containers / Core 组件 / App 组件） | 需要一张可导航的结构图 |
| [architecture/batch-flow.md](architecture/batch-flow.md) + `.dot`/`.svg` | 一批从侧载到相册能播的端到端流动；谁拥有什么 | 排查"整批没反应 / 相册没更新" |
| [architecture/compose-pipeline.md](architecture/compose-pipeline.md) + `.dot`/`.svg` | 一项要过哪八道关口、失败与取消时磁盘留什么 | 改 `MotionPhotoComposer` 之前必读 |
| [motion-photo-container.md](motion-photo-container.md) | 产物字节布局、XMP 目录规则、尾巴声明矩阵、vivo 末盒、厂商反例 | 排查"相册不认" |
| [decisions.md](decisions.md) | 为什么这样设计；哪些地方**刻意**不寻常；已知技术债；spec 引用索引 | 觉得"这里写得真怪"的时候 |
| [testing.md](testing.md) | 204 条测试怎么分布、钉住了什么、测不到什么、真机验收清单 | 提交前 |
| [architecture/render-svg.mjs](architecture/render-svg.mjs) | 怎么重新渲染两张 DOT | 改了 `.dot` |
| [architecture/dsl-check.mjs](architecture/dsl-check.mjs) | 没有 Java 时怎么给 C4 图源做结构自检 | 改了 `.dsl` |

## 三条阅读路径

- **新人上手**：`/README.md` → `batch-flow.md` → `system-model.md` → `decisions.md` §1/§4。
- **要改字节层**：`compose-pipeline.md` → `motion-photo-container.md` → `decisions.md` §3 → `testing.md` 的真机清单。
- **相册不认（线上排查）**：`motion-photo-container.md` §2 的对照表 → §4 尾巴声明矩阵 → §5 vivo 末盒 → `testing.md` 验收清单第 1、6、8 步。

## 渲染与验证状态

| 产物 | 状态 | 说明 |
| --- | --- | --- |
| `compose-pipeline.dot` → `.svg` | ✅ **已真渲染验证** | Graphviz 16.1.0（`@viz-js/viz` WASM）parse + layout 通过；22 节点、31 边全部布局；在浏览器里按 `viewBox` 逐元素量过 `text`/`path`/`polygon`，**无内容被裁** |
| `batch-flow.dot` → `.svg` | ✅ **已真渲染验证** | 同上；17 节点、19 边、2 个边界 cluster；无裁切 |
| `vlive.structurizr.dsl` | ⚠️ **未过官方解析器**（本机无 Java，Structurizr 解析器是 Java 的） | 已过**结构自检** [`dsl-check.mjs`](architecture/dsl-check.mjs)（零依赖）：花括号平衡、29 个声明元素、64 处关系/视图端点全部指向已声明标识符、4 个视图目标存在、位置参数无超限。**这只挡手误，不证明语义** —— 请在 Qoder 的 Structurizr DSL 视图里打开四个视图确认；某视图打不开时以两张 DOT 为准 |
| `.svg` 里的中文 | ⚠️ 已知瑕疵 | WASM 构建没有 CJK 字体度量，按估算字宽画框，中文标签可能略微越过自己的圆角边框（不影响可读性）。出终稿用带 CJK 字体的本机 graphviz 重渲染 |

重渲染：

```bash
# 推荐：依赖装在临时目录，别在 docs/architecture 里留下 node_modules/ 与 package-lock.json
mkdir -p /tmp/gv && cd /tmp/gv && npm install @viz-js/viz
cp /d/projects/piece/live-photo-batch/docs/architecture/*.dot .
cp /d/projects/piece/live-photo-batch/docs/architecture/render-svg.mjs .
node render-svg.mjs && cp *.svg /d/projects/piece/live-photo-batch/docs/architecture/

# 或用本机 graphviz（不需要 npm）：
dot -Tsvg compose-pipeline.dot -o compose-pipeline.svg
dot -Tsvg batch-flow.dot       -o batch-flow.svg

# 改了 C4 图源就跑一次结构自检（零依赖，不需要 Java）：
node docs/architecture/dsl-check.mjs
```

`render-svg.mjs` 会先解析+布局，status 不是 `success` 就**不写文件并非零退出** —— 可以直接当文档层的守门用。
若坚持在 `docs/architecture` 里就地 `npm install`，记得把 `node_modules/` 与 `package*.json` 排除在版本控制之外。

## 新鲜度与维护

这些文档不是"写完就完"的产物，最容易过时的有两类：

1. **证据表里的行号**（`system-model.evidence.md`）。这是唯一需要随代码重新对齐的部分；结构性结论不随行号漂移。
2. **字节层的规则**（`motion-photo-container.md`）。判据来自真机实验，改它就改验收前提。

维护约定：

- 改 `XmpPacket` / `MotionPhotoComposer` / `VivoLivePhotoTail` / `TrailerModel` → 同步 `compose-pipeline.dot` 的关口表 + `motion-photo-container.md`。
- 改 `:app` 的适配器或 VM 编排 → 同步 `batch-flow.dot` + `system-model.md` 的端口契约表 + DSL 的 `AppComponents` 视图。
- 新增/删除模块 → 先改 DSL 与 `.dot`，再改两份 `.md` 的表格。
- 别把结构图复制进 README 正文（旧 README 抄了一份 mermaid 模块图，于是和 DSL 各自漂移）。README 只链图片。
- `.svg` 只由脚本生成，永远不要手工编辑。

## 当前已知的未知

| 未知 | 为什么重要 | 怎么确认 |
| --- | --- | --- |
| 相册是否把产物认成动态照片 | 这是**唯一**的验收判据，而 204 条测试没有一条能判它 | 真机跑一批（[testing.md 清单](testing.md#真机验收清单改了字节层就必须重跑)） |
| 现 XMP 包（1,057 字节那份）的真机接受度 | 它是在 1,024 那份"被认"的包之上加了 `Primary Length=0` + `Padding` 之后得到的 | 同上 |
| OPPO / 小米 / Pixel 等非 vivo 机型的识别 | `VivoLivePhotoTail` 只认 vivo 末盒；OPPO 样本只作为**反例**被分析过 | 各厂商真机各跑一批 |
| "源片没有 XMP 段"这一形状的产物是否被认 | 此时按规范插在 SOI 之后，而语料的源片**都带 XMP**，没有参考实现可比 | 造一份无 XMP 的 JPEG 上真机试 |
| 性能预算（100 张约 15 秒） | 只是注释里的动机，用来解释为什么不能逐字节读；无计时测试 | 真机计时 |
| 跨卷 `move` 退化路径 | 代码里有，无测试 | 仪器化测试构造跨卷场景 |
| 49 处 `spec §…` KDoc 引用指向已删除的设计稿 | 悬空引用会误导下一个读者 | 见 [decisions.md 的 spec 引用索引](decisions.md#spec-引用索引)，或把引用改指本目录 |

## 与已删除的设计文档的关系

`docs/superpowers/specs/2026-10-17-…-design.md`（预构建设计稿）与 `docs/superpowers/plans/2026-10-17-…-android.md`
已分别于 `38bd595`、`52d0621` 退役删除。本目录取代它们成为唯一的现状描述。KDoc 里残留的 `Task N` 步骤号
只作历史线索，**不要据它推断需求**；`spec §…` 的映射表在 [decisions.md](decisions.md#spec-引用索引)。
