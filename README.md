# live-photo-batch

Android 上的批量动态照片合成工具：选一个目录，把**同名**的 `.jpg` 与 `.mp4` 合成一张符合
Google **Motion Photo 1.0** 的单文件动态照片，写到另一个目录。不解码、不转码、不重编码 —— 纯字节
拼接加元数据。

## 安装与使用

1. 装机：`./gradlew :app:assembleDebug` 产出 `app/build/outputs/apk/debug/app-debug.apk`，
   `adb install -r <apk>`。
2. 首次打开授予「所有文件访问权限」（`MANAGE_EXTERNAL_STORAGE`）。没有它就无法读写相机目录，
   应用会给出跳转入口。
3. 填源目录与目标目录（也可以「浏览…」逐级选）。目标目录不存在会自动创建；两个目录可以是同一个。
4. 「开始合成」跑批，「取消」在当前项落盘后停止。产物名 `<stem>_MP.jpg`，同名已存在则**覆盖**。
5. 「合成后删除原文件」默认不勾；勾上只在产物 rename 成功之后删 `x.jpg` 与 `x.mp4`，删除失败
   不影响本项成功状态，但会记进报告。

## 产物形态

```
SOI + 源图[2, 旧XMP起点) + 新 XMP APP1 + 源图[旧XMP终点, 文件大小) + 视频字节
```

- 源 JPEG **整文件**搬进产物，包括主图 `FFD9`(EOI) 之后的 HDR gain map 副图与 vivo 厂商记录；
  新 XMP 写回旧 XMP 段原来的位置，所以 EXIF `APP1` 仍排在 XMP `APP1` 之前。
- 视频原始字节追加在文件末尾，`GCamera:MicroVideoOffset` 等于视频字节数，
  因此 `文件大小 − MicroVideoOffset == 视频起点`。
- XMP 从清空白写：源包里的 `hdrgm:`、`dc:`、厂商命名空间与源片自己的容器项都**不带进产物**；
  只有当尾巴确实是一张 gain map 时，才按规范补回 `hdrgm:Version="1.0"` 与 `GainMap` 项。

容器目录声明文件里**每一段字节**（规范 MUST：媒体项按目录顺序紧密封装）：

| `imageEnd` 之后的尾巴 | 目录 |
|---|---|
| 空 | `Primary` → `MotionPhoto` |
| 真 gain map（含 `urn:iso:std:iso:ts:21496-1` 或 `hdr-gain-map` 声明） | `Primary` → `GainMap(Length=整条尾巴)` → `MotionPhoto` |
| 其他字节（MPF 副图、厂商记录、零填充） | `Primary(Padding=尾巴长度)` → `MotionPhoto` |

## 实测（vivo V2419A，Android 16）

- 26 对 vivo 原片 / 222 MB 一批跑完约 **3 秒**（产物 rename 时间戳集中在同一 3 秒窗口内），
  `成功 26 · 跳过 28 · 失败 0`；跳过的 28 项全是缺配对（`NO_PAIR`）。
- 取消：跑批中途打断，进度停在 35/54，`.tmp` 无 `.part` 残留。
- 覆盖：先在目标路径放一个 1 字节的假产物，重跑一批后被替换为 9,151,135 字节的真产物且 inode 变更，
  两次产物 md5 相同 → rename 覆盖生效、合成确定性。
- 相册：vivo 相册把这些 `_MP.jpg` 当动态照片，可播放。
- 测试：`:core:test` 127 条、`:app:testDebugUnitTest` 24 条，`assembleDebug` 无警告。

## 已知偏离与取舍

- **产物比"两文件之和"还略大**：主图与副图字节一字不动。桌面端 live-photo-conv 对 JPEG 主图也是
  整文件拷贝；网上常见的"更小"的动态照片其实走了重编码分支（主图被重新压缩、副图与厂商段被丢掉），
  那是有损的，本项目不做。
- `GainMap` 的 `Item:Length` 写的是**整条尾巴**（含副图 JPEG 之后的厂商统计记录），不是副图自身的
  诚实长度。规范只允许第一项带 `Padding`，副图与视频之间的空隙无处声明；写诚实长度就会留下
  未声明字节、违反"紧密封装"MUST。JPEG 解码器读到自己的 `FFD9` 就停，多出的尾巴无害。
- 相机命名空间用 `GCamera` 前缀（规范默认前缀是 `Camera`）：URI 一致即可，Pixel 自己的产物也写
  `GCamera`。
- 四个 `GCamera:MicroVideo*` 属性：规范明确它们"已从本规范删除，若存在必须被忽略"，这里保留是因为
  vivo 只读它们。
- 文件名 `<stem>_MP.jpg` 满足规范建议的 `^([^\s/\\][^/\]*MP)\.(JPG|jpg|…)`，与 Pixel 同形。
- 只处理一层目录，不递归；只认 `.jpg`/`.jpeg` 与 `.mp4`（扩展名大小写不敏感，stem 严格区分大小写）。
- 分块扩展 XMP（`http://ns.adobe.com/xmp/extension/`）不处理，报 `EXTENDED_XMP_UNSUPPORTED`。

## 开发

```bash
export JAVA_HOME="<你的 JDK>"        # Windows 上 Android Studio 自带的 jbr 可用
./gradlew :core:test                 # 纯 JVM 的格式与批次逻辑，127 条
./gradlew :app:testDebugUnitTest     # ViewModel / 路径 / 存储，24 条
./gradlew :app:assembleDebug
```

`core` 不依赖 Android：JPEG 段遍历、XMP 包生成、容器目录、配对与批次全在那里，可以脱离设备跑测试。
`app` 只做权限、目录浏览、进度 UI 与 MediaStore 收敛。设计依据见
`docs/superpowers/specs/`，实现步骤见 `docs/superpowers/plans/`。
