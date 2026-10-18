# live-photo-batch Android 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 做一个只做批量合成的 Kotlin 原生 Android app：把源目录里同名的 `x.jpg` + `x.mp4` 合成 Google Motion Photo `x_live.jpg` 写入目标目录，可选删除原文件。

**Architecture:** 两个 Gradle 模块。`:core` 是纯 JVM Kotlin，包含 JPEG 段解析、XMP 生成与替换、配对与批量编排的全部算法，只通过 `ReadableByteChannel` / `WritableByteChannel` / `ChannelSource` 收发字节，因此不需要设备就能完整单测。`:app` 是唯一持有 `android.*` 和 `java.io.File` 的模块，负责"所有文件访问"权限、真实文件读写、MediaStore 索引收敛和 Compose 单屏 UI。合成全程流式零拷贝（`FileChannel.transferFrom`），不解码视频、不重编码图片。

**Tech Stack:** Kotlin 2.4.21，AGP 9.4.1，Gradle 9.6.1，JDK 25（Android Studio 自带 JBR），JUnit Jupiter 6.1.3，Jetpack Compose BOM 2026.09.00，`minSdk 30` / `targetSdk 36` / `compileSdk 36`。

**Spec:** `docs/superpowers/specs/2026-10-17-live-photo-batch-android-design.md`

## Global Constraints

- 包名与 applicationId：`dev.zayn.livephotobatch`（用户可改，改了要全局同步）。
- `:core` 禁止 import 任何 `android.*`；`:app` 是唯一允许 `java.io.File` 的模块。
- 不引入任何 native / JNI 依赖（不引 exiv2、jexif、FFmpeg、GStreamer）。
- `minSdk = 30`，`targetSdk = 36`，`compileSdk = 36`。
- 输出命名：`x.jpg` → `x_live.jpg`；目标同名文件**覆盖**。
- 配对：stem 严格区分大小写，扩展名判定大小写不敏感；stem 以 `_live` 结尾（大小写不敏感）的 JPEG 不作为输入；源目录**只扫一层，不递归**。
- `MicroVideoOffset` 与 `Item:Length` 都填**追加的视频字节数**。
- XMP 字段集合逐字对应 spec §7.3，不多不少；时间戳固定 `-1`。
- 取消只在当前项 rename 之后生效。
- 删除原文件只在该项 rename 成功之后发生。
- 内存峰值 ≤ 32 MB：所有拷贝走固定小缓冲的流式读，禁止整文件 `readBytes()`。
- 本地环境事实（实测于 2026-10-17）：`java`/`gradle` 不在 PATH；可用 JDK 为
  `C:/Program Files/Android/Android Studio/jbr`（openjdk 25.0.2）；可用 Gradle 为
  `~/.gradle/wrapper/dists/gradle-9.6.1-bin/*/gradle-9.6.1/bin/gradle`；Android SDK 在
  `C:/Users/wodwr/AppData/Local/Android/Sdk`，**只有 `build-tools/36.0.0` 和
  `platforms/android-37.0`，没有 `platforms/android-36`**，且无 `cmdline-tools`。因此 Task 1-8 的
  本地验证只跑 `:core:test`，`:app` 的编译从 Task 9 起由 CI 承担。

本地命令前缀（每个 Task 都用它；Git Bash 下）：

```bash
cd /d/projects/piece/live-photo-batch
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
export PATH="$JAVA_HOME/bin:$PATH"
GD=$(ls -d /c/Users/wodwr/.gradle/wrapper/dists/gradle-9.6.1-bin/*/gradle-9.6.1/bin/gradle)
```

Task 1 生成 wrapper 之后，把后续所有 `$GD` 换成 `./gradlew`。

---

## 文件结构

```
settings.gradle.kts              root + :core（:app 在 Task 9 追加）
build.gradle.kts                 只声明插件，不 apply
gradle.properties
gradle/libs.versions.toml        版本目录
core/build.gradle.kts
core/src/main/kotlin/dev/zayn/livephotobatch/core/
  Sources.kt        ByteRange, ChannelSource, ByteArraySource
  JpegStructure.kt  JPEG 段扫描（marker 遍历 + 熵数据）
  XmpPacket.kt      XMP 包与 APP1 段生成
  JpegXmpPatcher.kt SOI 后插新 XMP、跳过旧 XMP、截到 EOI
  MotionDetector.kt 嵌套动态照片判定
  MotionPhotoComposer.kt 校验顺序 + 串接图与视频
  PairPlanner.kt    同名配对与命名
  BatchRunner.kt    顺序推进 / 进度 / 取消 / 报告
  BatchStorage.kt   存储抽象接口
core/src/test/kotlin/dev/zayn/livephotobatch/core/
  Fixtures.kt       构造 JPEG / MP4 夹具
  JpegStructureTest.kt / XmpPacketTest.kt / JpegXmpPatcherTest.kt
  MotionDetectorTest.kt / MotionPhotoComposerTest.kt / PairPlannerTest.kt
  InMemoryStorage.kt / BatchRunnerTest.kt
app/                （Task 9 起）
.github/workflows/build.yml
README.md
```

---

## Task 1: 仓库脚手架与可运行的空测试

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`
- Create: `core/build.gradle.kts`
- Create: `core/src/main/kotlin/dev/zayn/livephotobatch/core/Sources.kt`
- Create: `core/src/test/kotlin/dev/zayn/livephotobatch/core/SourcesTest.kt`
- Create: `.gitignore`, `.gitattributes`
- Create: `.github/workflows/build.yml`（只有 `core-tests` job；`:app` 的 job 由 Task 9 追加）

**Interfaces:**
- Produces: `data class ByteRange(val start: Long, val endExclusive: Long) { val length: Long }`，
  `interface ChannelSource { fun open(): ReadableByteChannel; fun size(): Long }`，
  `class ByteArraySource(private val bytes: ByteArray) : ChannelSource`（后续 Task 的测试都按这个单参构造调用）

- [ ] **Step 1: 写版本目录与构建脚本**

`gradle/libs.versions.toml`：

```toml
[versions]
kotlin = "2.4.21"
agp = "9.4.1"
junit = "6.1.3"

[libraries]
junit-bom = { group = "org.junit", name = "junit-bom", version.ref = "junit" }
junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter" }
junit4 = { group = "junit", name = "junit", version = "4.13.2" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
android-application = { id = "com.android.application", version.ref = "agp" }
```

`settings.gradle.kts`：

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "live-photo-batch"
include(":core")
```

`build.gradle.kts`：

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.application) apply false
}
```

`gradle.properties`：

```properties
org.gradle.jvmargs=-Xmx2g
org.gradle.caching=true
kotlin.code.style=official
```

`core/build.gradle.kts`：

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging { events("passed", "failed") }
}
```

`.gitattributes`（避免 Windows 换行反复污染 diff）：

```
* text=auto eol=lf
*.png binary
*.jpg binary
*.mp4 binary
*.apk binary
```

`.gitignore`：

```
.gradle/
build/
local.properties
.idea/
*.iml
```

- [ ] **Step 2: 写 `Sources.kt`**

```kotlin
package dev.zayn.livephotobatch.core

import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel

data class ByteRange(val start: Long, val endExclusive: Long) {
    val length: Long get() = endExclusive - start

    init {
        require(endExclusive >= start) { "endExclusive=$endExclusive before start=$start" }
    }
}

/** 可反复打开的只读字节来源；:core 用它表达"同一文件要读两遍"而不依赖 java.io.File。 */
interface ChannelSource {
    fun open(): ReadableByteChannel
    fun size(): Long
}

class ByteArraySource(private val bytes: ByteArray) : ChannelSource {
    override fun open(): ReadableByteChannel = object : ReadableByteChannel {
        private var pos = 0
        private var closed = false
        override fun read(dst: ByteBuffer): Int {
            if (closed) throw java.nio.channels.ClosedChannelException()
            if (pos >= bytes.size) return -1
            val n = minOf(dst.remaining(), bytes.size - pos)
            dst.put(bytes, pos, n)
            pos += n
            return n
        }
        override fun close() { closed = true }
        override fun isOpen() = !closed
    }
    override fun size(): Long = bytes.size.toLong()
}
```

- [ ] **Step 3: 写一个真会失败的测试，证明工具链通**

`core/src/test/kotlin/dev/zayn/livephotobatch/core/SourcesTest.kt`：

```kotlin
package dev.zayn.livephotobatch.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class SourcesTest {
    @Test fun `byte range length is half open`() {
        assertEquals(4L, ByteRange(2L, 6L).length)
    }

    @Test fun `byte range rejects inverted bounds`() {
        assertThrows(IllegalArgumentException::class.java) { ByteRange(5L, 3L) }
    }

    @Test fun `byte array source can be opened twice`() {
        val source = ByteArraySource(byteArrayOf(1, 2, 3, 4))
        repeat(2) {
            source.open().use { ch ->
                val buf = ByteBuffer.allocate(4)
                while (buf.hasRemaining()) ch.read(buf)
                assertEquals(4, buf.position())
            }
        }
        assertEquals(4L, source.size())
    }
}
```

- [ ] **Step 4: 跑测试，确认全绿**

Run: `$GD :core:test`
Expected: `BUILD SUCCESSFUL`，3 个测试通过。若报缺 JDK：确认已 `export JAVA_HOME`。

- [ ] **Step 5: 加 CI（core-tests）**

`.github/workflows/build.yml`：

```yaml
name: CI
on:
  workflow_dispatch:
  push:
    branches: [ "main", "feat/*" ]
  pull_request:
    branches: [ "main" ]
concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true
jobs:
  core-tests:
    name: Core unit tests
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "17" }
      - run: ./gradlew :core:test --no-daemon
```

`push` 触发分支里带上 `feat/*`：push 事件的 workflow 取自被推的 ref，所以功能分支自己就能触发，
不必先把代码合进 `main`。

- [ ] **Step 6: 生成 wrapper**

Run:

```bash
$GD wrapper --gradle-version 9.6.1 --distribution-type bin
./gradlew --version
```

Expected: 生成 `gradlew`, `gradlew.bat`, `gradle/wrapper/*`；版本号 9.6.1。此后统一用 `./gradlew`。

- [ ] **Step 7: 提交**

```bash
git add .gitattributes .gitignore settings.gradle.kts build.gradle.kts gradle.properties gradle gradlew gradlew.bat core .github
git commit -m "chore: scaffold gradle build for core module"
```

---

## Task 2: JPEG 段扫描器

**Files:**
- Create: `core/src/main/kotlin/dev/zayn/livephotobatch/core/JpegStructure.kt`
- Create: `core/src/test/kotlin/dev/zayn/livephotobatch/core/Fixtures.kt`
- Test: `core/src/test/kotlin/dev/zayn/livephotobatch/core/JpegStructureTest.kt`

**Interfaces:**
- Consumes: `ByteRange`
- Produces:
  `data class JpegStructure(val imageEnd: Long, val oldXmp: ByteRange?, val oldXmpText: String?, val hasExtendedXmp: Boolean)`
  `sealed interface JpegScanResult { data class Parsed(val structure: JpegStructure); data object NotJpeg; data object Malformed }`
  `object JpegScanner { fun scan(ch: ReadableByteChannel): JpegScanResult }`

- [ ] **Step 1: 写夹具**

`Fixtures.kt`（后续所有测试都靠它构造字节）：

```kotlin
package dev.zayn.livephotobatch.core

object Fixtures {
    const val XAP = "http://ns.adobe.com/xap/1.0/"
    const val XMP_EXTENSION = "http://ns.adobe.com/xmp/extension/"

    fun uint16(value: Int): ByteArray =
        byteArrayOf((value shr 8).toByte(), value.toByte())

    /** marker + 2 字节长度字段（含长度字段自身）+ payload */
    fun segment(marker: Int, payload: ByteArray): ByteArray =
        byteArrayOf(0xFF.toByte(), marker.toByte()) + uint16(payload.size + 2) + payload

    fun app1(identifier: String, payload: ByteArray): ByteArray =
        segment(0xE1, identifier.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + payload)

    fun sof0(): ByteArray =
        segment(0xC0, byteArrayOf(0x08, 0x00, 0x11, 0x01, 0x01, 0x00, 0x01, 0x11, 0x00))

    fun sos(): ByteArray =
        segment(0xDA, byteArrayOf(0x01, 0x01, 0x00, 0x00, 0x3F, 0x00))

    /** SOI + 头部段 + SOS + 熵数据 + EOI (+ 尾部追加字节) */
    fun jpeg(
        headSegments: List<ByteArray> = listOf(sof0()),
        entropy: ByteArray = byteArrayOf(0x11, 0x22, 0x33, 0x44),
        tail: ByteArray = ByteArray(0),
    ): ByteArray {
        val body = headSegments.fold(byteArrayOf()) { acc, seg -> acc + seg }
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + body + sos() + entropy +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte()) + tail
    }

    /** 标准 ftyp 头 + filler，总长 size */
    fun mp4(size: Long = 4096): ByteArray {
        require(size >= 12)
        val out = ByteArray(size.toInt())
        val boxSize = size.toInt()
        out[0] = (boxSize ushr 24).toByte()
        out[1] = (boxSize ushr 16).toByte()
        out[2] = (boxSize ushr 8).toByte()
        out[3] = boxSize.toByte()
        "ftyp".toByteArray(Charsets.ISO_8859_1).copyInto(out, 4)
        "isom".toByteArray(Charsets.ISO_8859_1).copyInto(out, 8)
        return out
    }

    val xmpPacket: ByteArray =
        "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">OLD</x:xmpmeta>".toByteArray(Charsets.UTF_8)
}
```

- [ ] **Step 2: 写失败的测试**

`JpegStructureTest.kt`：

```kotlin
package dev.zayn.livephotobatch.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.channels.Channels

class JpegStructureTest {
    private fun scan(bytes: ByteArray) =
        JpegScanner.scan(Channels.newChannel(java.io.ByteArrayInputStream(bytes)))

    @Test fun `plain jpeg ends right at eoi`() {
        val bytes = Fixtures.jpeg()
        val structure = (scan(bytes) as JpegScanResult.Parsed).structure
        assertEquals(bytes.size.toLong(), structure.imageEnd)
        assertNull(structure.oldXmp)
    }

    @Test fun `entropy byte stuffing hides a fake eoi`() {
        // 熵数据里 FF 00 D9 是被填充过的 0xFF，不是 marker
        val bytes = Fixtures.jpeg(entropy = byteArrayOf(0, 0xFF.toByte(), 0x00, 0xD9.toByte(), 7, 7))
        val structure = (scan(bytes) as JpegScanResult.Parsed).structure
        assertEquals(bytes.size.toLong(), structure.imageEnd)
    }

    @Test fun `appended video tail is not mistaken for image data`() {
        val video = Fixtures.mp4(256)
        val bytes = Fixtures.jpeg(tail = video)
        val structure = (scan(bytes) as JpegScanResult.Parsed).structure
        assertEquals((bytes.size - video.size).toLong(), structure.imageEnd)
    }

    @Test fun `legacy exif app1 is kept but xmp app1 is located`() {
        val exif = Fixtures.segment(0xE1, "Exif".toByteArray() + byteArrayOf(0, 0) + ByteArray(8))
        val xmp = Fixtures.app1(Fixtures.XAP, Fixtures.xmpPacket)
        val bytes = Fixtures.jpeg(headSegments = listOf(exif, xmp, Fixtures.sof0()))
        val structure = (scan(bytes) as JpegScanResult.Parsed).structure
        assertEquals(Fixtures.xmpPacket.decodeToString(), structure.oldXmpText)
        val start = bytes.indexOfSlice(xmp).toLong()
        assertEquals(start, structure.oldXmp!!.start)
        assertEquals(start + xmp.size, structure.oldXmp.endExclusive)
    }

    @Test fun `extension xmp is flagged`() {
        val extended = Fixtures.app1(Fixtures.XMP_EXTENSION, ByteArray(40))
        val bytes = Fixtures.jpeg(headSegments = listOf(extended, Fixtures.sof0()))
        val structure = (scan(bytes) as JpegScanResult.Parsed).structure
        assertTrue(structure.hasExtendedXmp)
        assertNull(structure.oldXmp)
    }

    @Test fun `non jpeg prefix`() {
        assertTrue(scan("GIF89a".toByteArray()) is JpegScanResult.NotJpeg)
    }

    @Test fun `truncated file is malformed`() {
        val bytes = Fixtures.jpeg().copyOf(7)
        assertTrue(scan(bytes) is JpegScanResult.Malformed)
    }
}

private fun ByteArray.indexOfSlice(slice: ByteArray): Int {
    outer@ for (i in 0..(size - slice.size)) {
        for (j in slice.indices) if (this[i + j] != slice[j]) continue@outer
        return i
    }
    return -1
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `./gradlew :core:test --tests '*JpegStructureTest'`
Expected: 编译失败 `Unresolved reference: JpegScanner`。

- [ ] **Step 4: 实现扫描器**

`JpegStructure.kt`：

```kotlin
package dev.zayn.livephotobatch.core

import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel

data class JpegStructure(
    /** EOI 之后一个字节的位置，即"真 JPEG"的末尾 */
    val imageEnd: Long,
    val oldXmp: ByteRange?,
    val oldXmpText: String?,
    val hasExtendedXmp: Boolean,
)

sealed interface JpegScanResult {
    data class Parsed(val structure: JpegStructure) : JpegScanResult
    data object NotJpeg : JpegScanResult
    data object Malformed : JpegScanResult
}

/**
 * 按 marker 遍历 JPEG，绝不搜索字节序列 FFD9：追加的视频字节里合法地会出现 FFD9。
 * 熵数据内的 0xFF 必定被填充成 FF00，所以"FF 后跟非 0"才是真 marker。
 */
object JpegScanner {
    private val XAP = FixturesIdentifiers.XAP
    private val XMP_EXTENSION = FixturesIdentifiers.XMP_EXTENSION

    fun scan(src: ReadableByteChannel): JpegScanResult {
        val cur = Cursor(src)
        val head = cur.read(2) ?: return JpegScanResult.NotJpeg
        if (head[0] != 0xFF.toByte() || head[1] != 0xD8.toByte()) return JpegScanResult.NotJpeg

        var oldXmp: ByteRange? = null
        var oldXmpText: String? = null
        var hasExtendedXmp = false
        var pending: Int? = null

        while (true) {
            val marker: Int
            if (pending != null) {
                marker = pending
                pending = null
            } else {
                marker = cur.readMarker() ?: return JpegScanResult.Malformed
            }
            val markerStart = cur.position - 2

            if (marker == 0xD9) {
                return JpegScanResult.Parsed(
                    JpegStructure(cur.position, oldXmp, oldXmpText, hasExtendedXmp)
                )
            }
            if (marker == 0x01 || marker in 0xD0..0xD7) continue // TEM / RSTn：无长度字段

            val lenBytes = cur.read(2) ?: return JpegScanResult.Malformed
            val len = ((lenBytes[0].toInt() and 0xFF) shl 8) or (lenBytes[1].toInt() and 0xFF)
            if (len < 2) return JpegScanResult.Malformed
            val payload = cur.read(len - 2) ?: return JpegScanResult.Malformed

            when (marker) {
                0xDA -> pending = cur.scanEntropy() ?: return JpegScanResult.Malformed
                0xE1 -> when {
                    payload.startsWith(XAP) -> {
                        val identifierEnd = XAP.size // XAP 常量末尾已带标识符后的 NUL
                        if (payload.size > identifierEnd) {
                            oldXmp = ByteRange(markerStart, cur.position)
                            oldXmpText = String(
                                payload.copyOfRange(identifierEnd, payload.size), Charsets.UTF_8
                            )
                        }
                    }
                    payload.startsWith(XMP_EXTENSION) -> hasExtendedXmp = true
                }
            }
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }

    /** APP1 标识符是 ISO-8859-1 文本；放在独立对象里，避免生产代码依赖测试夹具。 */
    private object FixturesIdentifiers {
        val XAP = "http://ns.adobe.com/xap/1.0/".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)
        val XMP_EXTENSION = "http://ns.adobe.com/xmp/extension/".toByteArray(Charsets.ISO_8859_1)
    }

    private class Cursor(private val ch: ReadableByteChannel) {
        var position: Long = 0L
            private set
    
        fun read(n: Int): ByteArray? {
            val out = ByteArray(n)
            val buf = ByteBuffer.wrap(out)
            while (buf.hasRemaining()) {
                val r = try { ch.read(buf) } catch (e: java.io.IOException) { return null }
                if (r <= 0) return null
            }
            position += n
            return out
        }

        private fun readByte(): Int {
            val b = read(1) ?: return -1
            return b[0].toInt() and 0xFF
        }

        /** 段边界处读 marker：要求 FF 开头，跳过 fill FF，返回第二个字节。 */
        fun readMarker(): Int? {
            val fill = readByte()
            if (fill < 0 || fill != 0xFF) return null
            var m = readByte()
            if (m < 0) return null
            while (m == 0xFF) {
                m = readByte()
                if (m < 0) return null
            }
            if (m == 0x00) return null
            return m
        }

        /** 扫描熵数据，返回其后第一个 marker 字节（已消耗 FF 与该字节）。 */
        fun scanEntropy(): Int? {
            while (true) {
                val b = readByte()
                if (b < 0) return null
                if (b != 0xFF) continue
                var m = readByte()
                if (m < 0) return null
                while (m == 0xFF) {
                    m = readByte()
                    if (m < 0) return null
                }
                if (m == 0x00) continue        // 填充过的 0xFF
                if (m in 0xD0..0xD7) continue  // RSTn 仍在熵数据内
                return m
            }
        }

    }
}
```

扫描器只负责定位，不负责搬运：Task 4 的复制器改用 `Channels.newInputStream` + 定长缓冲，
所以 `Cursor` 里不保留 `skip`/`scratch` 这类未被调用的成员。为把"EOI 定位精确"这件事钉死，
在 `JpegStructureTest.kt` 里加这条：

```kotlin
    @Test fun `imageEnd counts every image byte exactly`() {
        val bytes = Fixtures.jpeg(tail = Fixtures.mp4(64))
        val structure = (scan(bytes) as JpegScanResult.Parsed).structure
        // SOI(2) + SOF0(2+2+9=13) + SOS(2+2+6=10) + entropy(4) + EOI(2) = 31
        assertEquals(31, structure.imageEnd.toInt())
    }
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./gradlew :core:test --tests '*JpegStructureTest'`
Expected: 9 个测试全绿。
Expected: 8 个测试全绿。
- [ ] **Step 6: 提交**

```bash
git add core/src
git commit -m "feat(core): walk jpeg markers without scanning for FFD9"
```

---

## Task 3: XMP 包与 APP1 段生成

**Files:**
- Create: `core/src/main/kotlin/dev/zayn/livephotobatch/core/XmpPacket.kt`
- Test: `core/src/test/kotlin/dev/zayn/livephotobatch/core/XmpPacketTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  `object XmpPacket { const val XAP_IDENTIFIER: String; fun motionPhoto(videoSize: Long): ByteArray; fun app1Segment(videoSize: Long): ByteArray }`

- [ ] **Step 1: 写失败的测试**

`XmpPacketTest.kt`：

```kotlin
package dev.zayn.livephotobatch.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XmpPacketTest {
    @Test fun `packet carries the exact spec field set`() {
        val text = XmpPacket.motionPhoto(987654L).decodeToString()
        listOf(
            "GCamera:MicroVideo=\"1\"",
            "GCamera:MicroVideoVersion=\"1\"",
            "GCamera:MicroVideoOffset=\"987654\"",
            "GCamera:MicroVideoPresentationTimestampUs=\"-1\"",
            "GCamera:MotionPhoto=\"1\"",
            "GCamera:MotionPhotoVersion=\"1\"",
            "GCamera:MotionPhotoPresentationTimestampUs=\"-1\"",
            "http://ns.google.com/photos/1.0/camera/",
            "http://ns.google.com/photos/1.0/container/",
            "http://ns.google.com/photos/1.0/container/item/",
            "<Item:Mime>image/jpeg</Item:Mime>",
            "<Item:Semantic>Primary</Item:Semantic>",
            "<Item:Mime>video/mp4</Item:Mime>",
            "<Item:Semantic>MotionPhoto</Item:Semantic>",
            "<Item:Length>987654</Item:Length>",
        ).forEach { assertTrue(text.contains(it), "XMP 缺少：$it") }
    }

    @Test fun `only the two size fields carry the value`() {
        val text = XmpPacket.motionPhoto(42L).decodeToString()
        assertEquals(2, Regex("42").findAll(text).count())
    }

    @Test fun `app1 segment is marker, length, identifier, nul, packet`() {
        val segment = XmpPacket.app1Segment(123L)
        assertEquals(0xFF.toByte(), segment[0])
        assertEquals(0xE1.toByte(), segment[1])
        val declared = ((segment[2].toInt() and 0xFF) shl 8) or (segment[3].toInt() and 0xFF)
        assertEquals(segment.size - 2, declared)
        assertTrue(segment.startsWith(XmpPacket.XAP_IDENTIFIER.toByteArray(Charsets.ISO_8859_1), 4))
        assertEquals(0.toByte(), segment[4 + XmpPacket.XAP_IDENTIFIER.length])
        assertEquals(
            XmpPacket.motionPhoto(123L).size,
            segment.size - 4 - XmpPacket.XAP_IDENTIFIER.length - 1
        )
    }

    @Test fun `segment exceeding the 16 bit length field is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            // 用超大数字无法撑爆包体；这里直接断言上限保护存在
            XmpPacket.requireFitsInSegment(ByteArray(65534 - XmpPacket.XAP_IDENTIFIER.length))
        }
    }

    private fun ByteArray.startsWith(other: ByteArray, at: Int): Boolean {
        if (size - at < other.size) return false
        for (i in other.indices) if (this[at + i] != other[i]) return false
        return true
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :core:test --tests '*XmpPacketTest'`
Expected: 编译失败 `Unresolved reference: XmpPacket`。

- [ ] **Step 3: 实现**

`XmpPacket.kt`（模板严格照 spec §7.3，字段集合照 `src/livemaker.vala:171-192`）：

```kotlin
package dev.zayn.livephotobatch.core

object XmpPacket {
    const val XAP_IDENTIFIER = "http://ns.adobe.com/xap/1.0/"
    private const val MAX_SEGMENT_BODY = 65533 // 长度字段自身占 2 字节，字段上限 65535

    private const val CAMERA_NS = "http://ns.google.com/photos/1.0/camera/"
    private const val CONTAINER_NS = "http://ns.google.com/photos/1.0/container/"
    private const val ITEM_NS = "http://ns.google.com/photos/1.0/container/item/"

    /**
     * 行拼接而不是整块原始字符串：`<?xpacket begin=...>` 里的 U+FEFF 在 Kotlin 原始字符串中
     * 不会被解释成转义，写成字面量又极易被编辑器吞掉。
     */
    fun motionPhoto(videoSize: Long): ByteArray {
        val size = videoSize.toString()
        val lines = buildList {
            add("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>")
            add("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">")
            add(" <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">")
            add("  <rdf:Description xmlns:GCamera=\"$CAMERA_NS\"")
            add("      GCamera:MotionPhoto=\"1\" GCamera:MotionPhotoVersion=\"1\"")
            add("      GCamera:MotionPhotoPresentationTimestampUs=\"-1\"/>")
            add("  <rdf:Description xmlns:GCamera=\"$CAMERA_NS\"")
            add("      GCamera:MicroVideo=\"1\" GCamera:MicroVideoVersion=\"1\"")
            add("      GCamera:MicroVideoOffset=\"$size\"")
            add("      GCamera:MicroVideoPresentationTimestampUs=\"-1\"/>")
            add("  <rdf:Description xmlns:Container=\"$CONTAINER_NS\">")
            add("   <Container:Directory>")
            add("    <rdf:Seq>")
            add("     <rdf:li xmlns:Item=\"$ITEM_NS\" rdf:parseType=\"Resource\">")
            add("      <Item:Mime>image/jpeg</Item:Mime>")
            add("      <Item:Semantic>Primary</Item:Semantic>")
            add("     </rdf:li>")
            add("     <rdf:li xmlns:Item=\"$ITEM_NS\" rdf:parseType=\"Resource\">")
            add("      <Item:Mime>video/mp4</Item:Mime>")
            add("      <Item:Semantic>MotionPhoto</Item:Semantic>")
            add("      <Item:Length>$size</Item:Length>")
            add("     </rdf:li>")
            add("    </rdf:Seq>")
            add("   </Container:Directory>")
            add("  </rdf:Description>")
            add(" </rdf:RDF>")
            add("</x:xmpmeta>")
            add("<?xpacket end=\"w\"?>")
        }
        return requireFitsInSegment(lines.joinToString("\n").toByteArray(Charsets.UTF_8))
    }

    /** 完整 APP1 段：FF E1 + 16 位长度 + 标识符 + NUL + XMP 包。 */
    fun app1Segment(videoSize: Long): ByteArray {
        val packet = motionPhoto(videoSize)
        val identifier = XAP_IDENTIFIER.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)
        val body = identifier.size + packet.size
        require(body <= MAX_SEGMENT_BODY) { "APP1 body $body exceeds $MAX_SEGMENT_BODY" }
        return byteArrayOf(0xFF.toByte(), 0xE1, (body + 2 shr 8).toByte(), (body + 2).toByte()) +
            identifier + packet
    }

    /** 供测试与调用方复用：包体超过 16 位长度字段能表达的上限就拒绝。 */
    fun requireFitsInSegment(packet: ByteArray): ByteArray {
        val needed = XAP_IDENTIFIER.length + 1 + packet.size
        require(needed <= MAX_SEGMENT_BODY) {
            "XMP packet needs $needed bytes, over the 16-bit APP1 limit $MAX_SEGMENT_BODY"
        }
        return packet
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :core:test --tests '*XmpPacketTest'`
Expected: 4 个测试全绿。

- [ ] **Step 5: 提交**

```bash
git add core/src
git commit -m "feat(core): emit google motion photo xmp as app1 segment"
```

---

## Task 4: JPEG XMP 段替换

**Files:**
- Create: `core/src/main/kotlin/dev/zayn/livephotobatch/core/ByteChannels.kt`
- Create: `core/src/main/kotlin/dev/zayn/livephotobatch/core/JpegXmpPatcher.kt`
- Test: `core/src/test/kotlin/dev/zayn/livephotobatch/core/JpegXmpPatcherTest.kt`

**Interfaces:**
- Consumes: `JpegStructure`, `ByteRange`, `ChannelSource`, `JpegScanner`
- Produces:
  `object JpegXmpPatcher { fun patch(structure: JpegStructure, image: ChannelSource, sink: WritableByteChannel, app1: ByteArray): Long }`
  返回值 = 从源图实际写入 sink 的**源图字节数**（不含新 APP1，也不含被丢弃的旧段），
  即 `imageEnd - (oldXmp?.length ?: 0)`。调用方要得到产物里的图片总长，得自己加 `app1.size`。
  `core/.../ByteChannels.kt` 提供 `internal fun WritableByteChannel.writeAll(src: ByteBuffer)`，
  Task 4 与 Task 5 共用，避免各写一份排空循环。

- [ ] **Step 1: 写失败的测试**

`JpegXmpPatcherTest.kt`：

```kotlin
package dev.zayn.livephotobatch.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.channels.Channels

class JpegXmpPatcherTest {
    private val app1 = XmpPacket.app1Segment(7L)

    private fun patch(bytes: ByteArray): ByteArray {
        val structure = JpegScanner.scan(Channels.newChannel(java.io.ByteArrayInputStream(bytes)))
            as JpegScanResult.Parsed
        val out = ByteArrayOutputStream()
        val written = JpegXmpPatcher.patch(structure.structure, ByteArraySource(bytes),
            Channels.newChannel(out), app1)
        assertEquals(structure.structure.imageEnd, written)
        return out.toByteArray()
    }

    @Test fun `new xmp sits immediately after soi`() {
        val result = patch(Fixtures.jpeg())
        assertEquals(0xFF.toByte(), result[0])
        assertEquals(0xD8.toByte(), result[1])
        assertEquals(0xFF.toByte(), result[2])
        assertEquals(0xE1.toByte(), result[3])
        assertArrayEquals(app1, result.copyOfRange(2, 2 + app1.size))
    }

    @Test fun `exif and quant table segments survive byte for byte`() {
        val exif = Fixtures.segment(0xE1, "Exif".toByteArray() + byteArrayOf(0, 0) + ByteArray(16))
        val dqt = Fixtures.segment(0xDB, ByteArray(33) { it.toByte() })
        val bytes = Fixtures.jpeg(headSegments = listOf(exif, dqt, Fixtures.sof0()))
        val result = patch(bytes)
        // 源图里除旧 XMP 外的全部字节顺序不变；这里没有旧 XMP，所以尾部应完全等于源图 EOI 前内容
        assertArrayEquals(
            bytes.copyOfRange(2, bytes.size - 2),           // 去掉 SOI 与尾部 EOI
            result.copyOfRange(2 + app1.size, result.size - 2)
        )
    }

    @Test fun `old xmp is dropped and replaced by exactly one new xmp`() {
        val old = Fixtures.app1(Fixtures.XAP, Fixtures.xmpPacket)
        val bytes = Fixtures.jpeg(headSegments = listOf(old, Fixtures.sof0()))
        val result = patch(bytes)
        val occurrences = Regex(Regex.escape(Fixtures.XAP)).findAll(result.decodeToString()).count()
        assertEquals(1, occurrences)
        assertTrue(result.decodeToString().contains("MotionPhoto"))
        assertFalse(result.decodeToString().contains("OLD"))
    }

    @Test fun `appended video tail is not copied`() {
        val video = Fixtures.mp4(512)
        val bytes = Fixtures.jpeg(tail = video)
        val result = patch(bytes)
        assertEquals(bytes.size - video.size + app1.size.toLong(), result.size.toLong())
        assertEquals(0xD9.toByte(), result[result.size - 2 - 1]) // 图片段以 EOI 结束
    }

    @Test fun `extension xmp is left untouched`() {
        val extended = Fixtures.app1(Fixtures.XMP_EXTENSION, ByteArray(40))
        val bytes = Fixtures.jpeg(headSegments = listOf(extended, Fixtures.sof0()))
        val result = patch(bytes)
        assertTrue(result.indexOfSlice(extended) > app1.size)
    }
}

private fun ByteArray.indexOfSlice(slice: ByteArray): Int {
    outer@ for (i in 0..(size - slice.size)) {
        for (j in slice.indices) if (this[i + j] != slice[j]) continue@outer
        return i
    }
    return -1
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :core:test --tests '*JpegXmpPatcherTest'`
Expected: 编译失败 `Unresolved reference: JpegXmpPatcher`。

- [ ] **Step 3: 实现**

`ByteChannels.kt`（Task 4/5 共用，只有一份实现）：

```kotlin
package dev.zayn.livephotobatch.core

import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel

/** WritableByteChannel.write 允许只消费部分缓冲；单次调用会写出短文件。 */
internal fun WritableByteChannel.writeAll(src: ByteBuffer) {
    while (src.hasRemaining()) {
        if (write(src) <= 0) throw java.io.IOException("sink 拒绝写入")
    }
}
```

`JpegXmpPatcher.kt`：

```kotlin
package dev.zayn.livephotobatch.core

import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.WritableByteChannel
import java.io.InputStream

/**
 * 写出 `SOI + 新 APP1(XMP) + 源图 [2, imageEnd) 去掉旧 XMP`。
 * 只做区间搬运，不解释 JPEG 语义——语义已经由 JpegScanner 固化成 JpegStructure。
 */
object JpegXmpPatcher {
    private const val COPY_BUFFER = 64 * 1024

    fun patch(
        structure: JpegStructure,
        image: ChannelSource,
        sink: WritableByteChannel,
        app1: ByteArray,
    ): Long {
        var copied = 0L
        image.open().use { ch ->
            val input = Channels.newInputStream(ch)
            copied += copyExact(input, 2L, sink)                       // SOI
            sink.writeAll(ByteBuffer.wrap(app1))                       // 新 XMP
            val gapStart = structure.oldXmp?.start ?: structure.imageEnd
            copied += copyExact(input, gapStart - 2L, sink)
            if (structure.oldXmp != null) {
                // 读指针此刻正停在 oldXmp.start 上；不先跳过就会把旧段的
                // FF E1 + 长度 + 标识符再写一遍，并且少写同样多的真实尾部字节
                skipExact(input, structure.oldXmp.length)
                copied += copyExact(input, structure.imageEnd - structure.oldXmp.endExclusive, sink)
            }
        }
        return copied
    }

    // 注：交付时 copyExact 与 skipExact 已合并为一个私有
    // transfer(input, buf, n, chunk)，chunk 取 emit/discard；两段 64 KiB 缓冲与两份
    // EOF 检查属于重复，Task 4 的 review 已裁定合并。下面这段保留仅为说明区间语义。
    private fun skipExact(input: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(COPY_BUFFER)
        while (left > 0) {
            val read = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (read <= 0) throw java.io.EOFException("跳过旧 XMP 时数据提前结束")
            left -= read
        }
    }

    /** 精确搬 n 字节；不够就抛，绝不静默写出半截 JPEG。 */
    private fun copyExact(input: InputStream, n: Long, sink: WritableByteChannel): Long {
        require(n >= 0)
        val buf = ByteArray(COPY_BUFFER)
        var left = n
        while (left > 0) {
            val want = minOf(left, buf.size.toLong()).toInt()
            val read = input.read(buf, 0, want)
            if (read <= 0) throw java.io.EOFException("JPEG 数据提前结束，还差 $left 字节")
            sink.writeAll(ByteBuffer.wrap(buf, 0, read))
            left -= read
        }
        return n
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :core:test --tests '*JpegXmpPatcherTest'`
Expected: 5 个测试全绿。

- [ ] **Step 5: 提交**

```bash
git add core/src
git commit -m "feat(core): replace jpeg xmp app1 in one streaming pass"
```

---

## Task 5: 嵌套判定与合成器

**Files:**
- Create: `core/src/main/kotlin/dev/zayn/livephotobatch/core/MotionDetector.kt`
- Create: `core/src/main/kotlin/dev/zayn/livephotobatch/core/MotionPhotoComposer.kt`
- Test: `core/src/test/kotlin/dev/zayn/livephotobatch/core/MotionDetectorTest.kt`
- Test: `core/src/test/kotlin/dev/zayn/livephotobatch/core/MotionPhotoComposerTest.kt`

**Interfaces:**
- Consumes: `JpegScanner`, `JpegStructure`, `XmpPacket`, `JpegXmpPatcher`, `ChannelSource`
- Produces:
  `enum class FailureReason { NOT_JPEG, MALFORMED_JPEG, ALREADY_MOTION_PHOTO, EXTENDED_XMP_UNSUPPORTED, NOT_MP4, IO_ERROR, NO_PAIR }`
  `sealed interface ComposeResult { data class Success(videoSize, totalBytes); data class Failure(reason) }`
  —— 失败不报告写了多少字节：spec §7.5 的清理契约是「失败就无条件删 `.part`」，
  这个计数没有读者，留着只会被误解成「0 表示什么都没写过」
  `object MotionPhotoComposer { fun compose(image: ChannelSource, video: ChannelSource, sink: WritableByteChannel): ComposeResult }`
  `object MotionDetector { fun looksLikeMotionPhoto(xmpText: String?): Boolean }`

- [ ] **Step 1: 写检测器测试**

`MotionDetectorTest.kt`：

```kotlin
package dev.zayn.livephotobatch.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MotionDetectorTest {
    @Test fun `namespace qualified tags are caught`() {
        assertTrue(MotionDetector.looksLikeMotionPhoto("""<x GCamera:MicroVideoOffset="123"/>"""))
        assertTrue(MotionDetector.looksLikeMotionPhoto("""<GCamera:MotionPhoto>1</>"""))
        assertTrue(MotionDetector.looksLikeMotionPhoto("""Item:Semantic>MotionPhoto"""))
    }

    @Test fun `unrelated xmp is not mistaken`() {
        assertFalse(MotionDetector.looksLikeMotionPhoto("<x:xmpmeta>MicroVideoOffsetish</x:xmpmeta>"))
        assertFalse(MotionDetector.looksLikeMotionPhoto(null))
        // 交付新增：原稿只有上一条，删掉正则里的 "GCamera:" 前缀照样全绿——"MicroVideoOffsetish"
        // 后面跟的是字母 i，不在 ["\s=>] 里，那条负例根本测不到命名空间。这三条才真正盯住
        // ruling 4（命名空间限定匹配，不是裸子串）：无前缀/其他前缀的同名元素必须判 false，
        // 否则普通照片会被整批 SKIPPED。
        assertFalse(MotionDetector.looksLikeMotionPhoto("<MicroVideoOffset>123</MicroVideoOffset>"))
        assertFalse(MotionDetector.looksLikeMotionPhoto("<tiff:MotionPhoto>0</tiff:MotionPhoto>"))
        assertFalse(MotionDetector.looksLikeMotionPhoto("<dc:description>MotionPhoto</dc:description>"))
    }
}
```

- [ ] **Step 2: 实现检测器**

`MotionDetector.kt`：

```kotlin
package dev.zayn.livephotobatch.core

object MotionDetector {
    private val MOTION_TAG =
        Regex("""GCamera:(MicroVideoOffset|MotionPhoto)["\s=>]""")
    private val MOTION_SEMANTIC = Regex("""Item:Semantic>?\s*MotionPhoto""")

    fun looksLikeMotionPhoto(xmpText: String?): Boolean {
        if (xmpText == null) return false
        return MOTION_TAG.containsMatchIn(xmpText) || MOTION_SEMANTIC.containsMatchIn(xmpText)
    }
}
```

- [ ] **Step 3: 跑检测器测试**

Run: `./gradlew :core:test --tests '*MotionDetectorTest'`
Expected: 先 FAIL（`Unresolved reference`），实现后 PASS。

- [ ] **Step 4: 写合成器测试（含 round-trip）**

`MotionPhotoComposerTest.kt`：

```kotlin
package dev.zayn.livephotobatch.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels

class MotionPhotoComposerTest {
    private fun compose(image: ByteArray, video: ByteArray): Pair<ComposeResult, ByteArray> {
        val out = ByteArrayOutputStream()
        val result = MotionPhotoComposer.compose(
            ByteArraySource(image), ByteArraySource(video), Channels.newChannel(out)
        )
        return result to out.toByteArray()
    }

    @Test fun `happy path writes image then video and records video size`() {
        val video = Fixtures.mp4(900)
        val image = Fixtures.jpeg()
        val (result, out) = compose(image, video)
        val success = result as ComposeResult.Success
        assertEquals(900L, success.videoSize)
        assertEquals(out.size.toLong(), success.totalBytes)
        // 交付新增：把账本写死——产物 = 源图 + 新 APP1 + 视频，三项一项都不能少。
        assertEquals(image.size + XmpPacket.app1Segment(900L).size + video.size, out.size)
        // 尾部逐字节等于源 MP4
        assertArrayEquals(video, out.copyOfRange(out.size - video.size, out.size))
        // 头 + 新 XMP + 源图剩余 + 视频
        val expectedHead = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            XmpPacket.app1Segment(900L) + image.copyOfRange(2, image.size - 2)
        assertArrayEquals(expectedHead, out.copyOfRange(0, expectedHead.size))
        // 交付新增：sink 归调用方——只有走完写入阶段的这条用例能盯住"compose 不许 close 别人的 sink"
        // （失败路径提前 return，压根碰不到那个 finally）。Task 8 用 use { } 包着它，compose 先关
        // 就等于在 rename 之前关掉 .part 通道。
        val owned = CountingSink()
        MotionPhotoComposer.compose(ByteArraySource(image), ByteArraySource(video), owned)
        assertTrue(owned.isOpen, "compose 关掉了调用方的 sink")
        assertEquals(owned.size().toLong(), success.totalBytes)
    }

    @Test fun `round trip locates video by file size minus offset`() {
        val video = Fixtures.mp4(1234)
        // Fixtures.mp4 已在 box header 之后每 16 字节埋入真实 FF D9 诱饵；
        // 不要再手写 video[6]/video[7]——那会覆盖 'ftyp' 签名，让 composer 先以 NOT_MP4 拒绝。
        //
        // 交付修正：图必须是**干净**的 Fixtures.jpeg()。原版写的是
        // Fixtures.jpeg(tail = Fixtures.mp4(200))，那种输入按 spec §7.4 第 3 条必然
        // ALREADY_MOTION_PHOTO（imageEnd=31 < size=231，正是再下面那条测试的输入），compose
        // 一个字节都不写，于是 out.size=0、start = 0 - 1234 = -1234，实测 ArrayIndexOutOfBoundsException。
        // tail 是从 Task 4 patcher 测试抄来的：patcher 要证明"越过 EOI 不搬"，composer 只接受干净 JPEG。
        val image = Fixtures.jpeg()
        val (_, out) = compose(image, video)
        // 偏移从产物自己的字节里读，而不是再调一次 XmpPacket：那才叫 round trip，
        // 也才看得见 composer 到底往文件里写了什么 XMP。
        val outText = out.toString(Charsets.ISO_8859_1)
        val offset = Regex("""GCamera:MicroVideoOffset="(\d+)"""").find(outText)!!.groupValues[1].toLong()
        assertEquals(video.size.toLong(), offset)
        assertEquals(offset, Regex("""<Item:Length>(\d+)""").find(outText)!!.groupValues[1].toLong())
        val start = out.size - offset
        assertEquals(0x66.toByte(), out[start.toInt() + 4]) // 'f' of ftyp
        assertArrayEquals(video, out.copyOfRange(start.toInt(), out.size))
        // 图片段以真 EOI 收尾、视频紧贴其后；start 恰好 = 源图 + 新 APP1。
        assertEquals(0xFF.toByte(), out[start.toInt() - 2])
        assertEquals(0xD9.toByte(), out[start.toInt() - 1])
        assertEquals((image.size + XmpPacket.app1Segment(video.size.toLong()).size).toLong(), start)
    }

    @Test fun `not jpeg`() {
        // 交付修正：用 PNG 魔数，不依赖源码编码（原版的 "PNGâPNG" 里那个字符是编码事故）。
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) +
            byteArrayOf(0x0D, 0x0A, 0x1A, 0x0A)
        val (result, out) = compose(png, Fixtures.mp4())
        assertEquals(FailureReason.NOT_JPEG, (result as ComposeResult.Failure).reason)
        assertEquals(0, out.size)
    }

    @Test fun `not mp4 when no ftyp near the head`() {
        val bad = ByteArray(2048)
        val (result, out) = compose(Fixtures.jpeg(), bad)
        assertEquals(FailureReason.NOT_MP4, (result as ComposeResult.Failure).reason)
        assertEquals(0, out.size)
        // 交付新增：两头都坏时必须是 NOT_MP4——这条才真正盯住"先探测视频头"的顺序（ruling 3）。
        // 把 JPEG 扫描挪到前面就变成 NOT_JPEG，坏视频在整批报告里被误报成坏图片。
        val both = compose("PNG".toByteArray(), bad)
        assertEquals(FailureReason.NOT_MP4, (both.first as ComposeResult.Failure).reason)
        assertEquals(0, both.second.size)
        // 交付新增：不足 12 字节的头没有 ftyp 的容身之处，同样是 NOT_MP4，不许抛。
        val tiny = compose(Fixtures.jpeg(), byteArrayOf(0, 0, 0, 4, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte()))
        assertEquals(FailureReason.NOT_MP4, (tiny.first as ComposeResult.Failure).reason)
        assertEquals(0, tiny.second.size)
    }

    @Test fun `already a motion photo is skipped without writing a byte`() {
        val embedded = Fixtures.app1(Fixtures.XAP, XmpPacket.motionPhoto(300L))
        val image = Fixtures.jpeg(headSegments = listOf(embedded, Fixtures.sof0()))
        val (result, out) = compose(image, Fixtures.mp4(300))
        assertEquals(FailureReason.ALREADY_MOTION_PHOTO, (result as ComposeResult.Failure).reason)
        assertEquals(0, out.size)
    }

    @Test fun `video appended past jpeg eoi counts as already motion photo`() {
        val image = Fixtures.jpeg(tail = Fixtures.mp4(200))
        val (result, out) = compose(image, Fixtures.mp4(300))
        assertEquals(FailureReason.ALREADY_MOTION_PHOTO, (result as ComposeResult.Failure).reason)
        assertEquals(0, out.size)
    }

    @Test fun `extended xmp is refused`() {
        val extended = Fixtures.app1(Fixtures.XMP_EXTENSION, ByteArray(40))
        val (result, out) = compose(Fixtures.jpeg(headSegments = listOf(extended)), Fixtures.mp4())
        assertEquals(FailureReason.EXTENDED_XMP_UNSUPPORTED, (result as ComposeResult.Failure).reason)
        assertEquals(0, out.size)
    }

    @Test fun `truncated jpeg is malformed`() {
        val (result, out) = compose(Fixtures.jpeg().copyOf(9), Fixtures.mp4())
        assertEquals(FailureReason.MALFORMED_JPEG, (result as ComposeResult.Failure).reason)
        assertEquals(0, out.size)
    }

    @Test fun `nothing is written before validation fails`() {
        val sink = CountingSink()
        val result = MotionPhotoComposer.compose(
            ByteArraySource(Fixtures.jpeg()), ByteArraySource(ByteArray(8)), sink
        )
        // 无 ftyp 的视频在写任何东西之前就被拒
        assertEquals(FailureReason.NOT_MP4, (result as ComposeResult.Failure).reason)
        assertFalse(sink.wroteAnything)
        // 交付新增：sink 归调用方，失败路径也不许替它 close()。
        assertTrue(sink.isOpen, "compose 关掉了调用方的 sink")
    }

    private class CountingSink : java.nio.channels.WritableByteChannel {
        var wroteAnything = false
        private val bytes = ByteArrayOutputStream()
        private var open = true
        override fun write(src: ByteBuffer): Int {
            wroteAnything = true
            val n = src.remaining()
            val chunk = ByteArray(n)
            src.get(chunk)
            bytes.write(chunk)
            return n
        }
        override fun close() { open = false }
        override fun isOpen() = open
        fun size(): Int = bytes.size()
    }
}
```

- [ ] **Step 5: 实现合成器**

`MotionPhotoComposer.kt`（校验顺序按 spec §7.4；被拒绝的项一个字节都不写 sink。整个过程流式，
**不写内存快照**——原简报那句"先写内存快照再决定"与 §2 G5 的常数内存预算冲突，代码本身也没那么做）：

```kotlin
package dev.zayn.livephotobatch.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel

enum class FailureReason { NOT_JPEG, MALFORMED_JPEG, ALREADY_MOTION_PHOTO,
    EXTENDED_XMP_UNSUPPORTED, NOT_MP4, IO_ERROR, NO_PAIR }

sealed interface ComposeResult {
    data class Success(val videoSize: Long, val totalBytes: Long) : ComposeResult
    data class Failure(val reason: FailureReason) : ComposeResult
}

object MotionPhotoComposer {
    private const val COPY_BUFFER = 64 * 1024
    private const val PROBE_BYTES = 16
    private const val MIN_MP4_HEAD = 12
    private val FTYP = byteArrayOf('f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())

    fun compose(image: ChannelSource, video: ChannelSource, sink: WritableByteChannel): ComposeResult {
        val videoSize = video.size()
        val head = try {
            readHead(video, minOf(PROBE_BYTES.toLong(), videoSize).toInt())
        } catch (e: IOException) {
            return ComposeResult.Failure(FailureReason.IO_ERROR)
        }
        if (!containsFtyp(head)) return ComposeResult.Failure(FailureReason.NOT_MP4)

        val scan = try {
            image.open().use { ch -> JpegScanner.scan(ch) }
        } catch (e: IOException) {
            return ComposeResult.Failure(FailureReason.IO_ERROR)
        }
        val structure = when (scan) {
            is JpegScanResult.NotJpeg -> return ComposeResult.Failure(FailureReason.NOT_JPEG)
            is JpegScanResult.Malformed -> return ComposeResult.Failure(FailureReason.MALFORMED_JPEG)
            is JpegScanResult.Parsed -> scan.structure
        }
        if (structure.hasExtendedXmp)
            return ComposeResult.Failure(FailureReason.EXTENDED_XMP_UNSUPPORTED)
        if (structure.imageEnd < image.size() || MotionDetector.looksLikeMotionPhoto(structure.oldXmpText))
            return ComposeResult.Failure(FailureReason.ALREADY_MOTION_PHOTO)

        val app1 = XmpPacket.app1Segment(videoSize)
        return try {
            val patched = JpegXmpPatcher.patch(structure, image, sink, app1)
            copyToEnd(video, sink)
            ComposeResult.Success(videoSize, patched + app1.size + videoSize)
        } catch (e: IOException) {
            // 校验全过了才会走到这里；剩下的只有 I/O 故障。半成品已由调用方按 §7.5 删掉，不计数。
            ComposeResult.Failure(FailureReason.IO_ERROR)
        }
        // 注意：没有 finally { sink.close() }。见本节末尾的交付修正。
    }

    /** 定长读视频头；不足就留零，containsFtyp 自己会拒绝过短的头部。 */
    private fun readHead(video: ChannelSource, n: Int): ByteArray {
        val buf = ByteArray(n)
        if (n == 0) return buf
        video.open().use { ch ->
            val dst = ByteBuffer.wrap(buf)
            while (dst.hasRemaining()) if (ch.read(dst) <= 0) break
        }
        return buf
    }

    /** 视频原样追加：定长缓冲读到 EOF，写出只走共享的 writeAll。 */
    private fun copyToEnd(video: ChannelSource, sink: WritableByteChannel) {
        video.open().use { ch ->
            val buf = ByteBuffer.allocate(COPY_BUFFER)
            while (true) {
                buf.clear()
                if (ch.read(buf) <= 0) break
                buf.flip()
                sink.writeAll(buf)
            }
        }
    }

    /** spec §7.4 第 4 条：起始 12 字节内出现 ftyp。 */
    private fun containsFtyp(head: ByteArray): Boolean {
        if (head.size < MIN_MP4_HEAD) return false
        for (i in 0..(MIN_MP4_HEAD - FTYP.size)) if (head.matchesAt(i, FTYP)) return true
        return false
    }

    /** ByteArray 没有 regionMatches（那是 String/CharSequence 的 API）：自己写一次越界安全的比对。 */
    private fun ByteArray.matchesAt(index: Int, pattern: ByteArray): Boolean {
        if (index < 0 || size - index < pattern.size) return false
        for (j in pattern.indices) if (this[index + j] != pattern[j]) return false
        return true
    }
}
```

交付修正三处（实现与测试都已按此落地，`./gradlew :core:test` = 39 绿）：
1. **删掉 `finally { runCatching { (sink as? AutoCloseable)?.close() } }`**：sink 归调用方，Task 8 用
   `storage.openWrite(tmpPath).use { sink -> compose(...) }` 包着它，compose 先关就是在 rename 之前
   关掉 .part 通道。盯住它的是 happy-path 测试里的 `assertTrue(owned.isOpen)`——早退的失败路径
   根本碰不到那个 finally，所以只有成功用例能验证。
2. **`head.regionMatches(i, ftyp, 0, 4)` 编译不过**：`regionMatches` 只存在于 String/CharSequence，
   ByteArray 没有（实测 `Unresolved reference 'regionMatches' on receiver of type 'ByteArray'`）。
   改为私有 `ByteArray.matchesAt(index, pattern)`，与 JpegScanner 里那条私有 `ByteArray.startsWith`
   同一手法。
3. 视频头读取与视频体追加抽成 `readHead` / `copyToEnd`；写出仍只走 Task 4 抽出来的共享
   `WritableByteChannel.writeAll`（裁定：全仓库只许有一份排空循环）。

- [ ] **Step 6: 跑测试确认通过**

Run: `./gradlew :core:test --tests '*MotionPhotoComposerTest'`
Expected: 10 个测试全绿。

- [ ] **Step 7: 提交**

```bash
git add core/src
git commit -m "feat(core): compose google motion photo from jpg and mp4"
```

---

## Task 6: 同名配对与命名

**Files:**
- Create: `core/src/main/kotlin/dev/zayn/livephotobatch/core/PairPlanner.kt`
- Test: `core/src/test/kotlin/dev/zayn/livephotobatch/core/PairPlannerTest.kt`

**Interfaces:**
- Consumes: `DirEntry`（本 Task 定义）
- Produces:
  `data class DirEntry(val name: String, val size: Long)`
  `data class PairJob(val stem: String, val imageName: String, val videoName: String, val outputName: String, val imageSize: Long, val videoSize: Long, val outputExists: Boolean)`
  `data class Unpaired(val name: String, val missing: Missing)`；`enum class Missing { VIDEO, IMAGE }`
  `data class PlanResult(val jobs: List<PairJob>, val unpaired: List<Unpaired>)`
  `object PairPlanner { const val OUTPUT_SUFFIX = "_live"; fun plan(source: List<DirEntry>, target: List<DirEntry>): PlanResult }`

- [ ] **Step 1: 写失败的测试**

`PairPlannerTest.kt`：

```kotlin
package dev.zayn.livephotobatch.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PairPlannerTest {
    private fun plan(vararg names: String) =
        PairPlanner.plan(names.map { DirEntry(it, 1L) }, emptyList())

    @Test fun `same stem pairs and gets the live suffix`() {
        val result = plan("IMG_001.jpg", "IMG_001.mp4")
        assertEquals(1, result.jobs.size)
        val job = result.jobs[0]
        assertEquals("IMG_001", job.stem)
        assertEquals("IMG_001_live.jpg", job.outputName)
        assertEquals("IMG_001.mp4", job.videoName)
        assertEquals(false, job.outputExists)
    }

    @Test fun `sizes come from the listing`() {
        val result = PairPlanner.plan(
            listOf(DirEntry("a.jpg", 11L), DirEntry("a.mp4", 22L)), emptyList()
        )
        assertEquals(11L, result.jobs[0].imageSize)
        assertEquals(22L, result.jobs[0].videoSize)
    }

    @Test fun `extension case is ignored, stem case is not`() {
        assertEquals(1, plan("a.JPG", "a.Mp4").jobs.size)
        assertTrue(plan("a.jpg", "A.mp4").jobs.isEmpty())
    }

    @Test fun `previous outputs are not treated as inputs`() {
        val result = plan("a.jpg", "a.mp4", "a_live.jpg", "a_live.mp4")
        assertEquals(listOf("a"), result.jobs.map { it.stem })
        assertEquals(listOf("a_live.mp4"), result.unpaired.map { it.name })
    }

    @Test fun `output existing in target is flagged for overwrite`() {
        val result = PairPlanner.plan(
            listOf(DirEntry("a.jpg", 1L), DirEntry("a.mp4", 1L)),
            listOf(DirEntry("a_live.jpg", 9L)),
        )
        assertTrue(result.jobs[0].outputExists)
    }

    @Test fun `unpaired entries are reported both ways`() {
        // fixture 里不能再放 "only.mp4"，否则 only 就配成对，本条测试与 Step 3 的代码
        // 互相矛盾（原计划即如此，Task 6 实现时已纠正）
        val result = plan("only.jpg", "vid.mp4")
        assertTrue(result.jobs.isEmpty())
        // 并分别断言缺的是 VIDEO 与 IMAGE（以交付的测试为准）
        assertEquals(setOf("only.jpg", "vid.mp4"), result.unpaired.map { it.name }.toSet())
    }

    @Test fun `other extensions are ignored entirely`() {
        val result = plan("a.jpg", "a.mp4", "note.txt", "b.heic", "b.mp4")
        assertEquals(1, result.jobs.size)
        assertTrue(result.unpaired.none { it.name == "note.txt" || it.name == "b.heic" })
    }

    @Test fun `jobs are ordered by stem for stable UI`() {
        val result = plan("b.jpg", "b.mp4", "a.jpg", "a.mp4")
        assertEquals(listOf("a", "b"), result.jobs.map { it.stem })
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :core:test --tests '*PairPlannerTest'`
Expected: 编译失败 `Unresolved reference: PairPlanner`。

- [ ] **Step 3: 实现**

`PairPlanner.kt`：

```kotlin
package dev.zayn.livephotobatch.core

data class DirEntry(val name: String, val size: Long)

data class PairJob(
    val stem: String,
    val imageName: String,
    val videoName: String,
    val outputName: String,
    val imageSize: Long,
    val videoSize: Long,
    val outputExists: Boolean,
)

enum class Missing { VIDEO, IMAGE }

data class Unpaired(val name: String, val missing: Missing)

data class PlanResult(val jobs: List<PairJob>, val unpaired: List<Unpaired>)

/**
 * 一层目录的同名配对。stem 严格区分大小写（与 Android 文件系统语义一致），
 * 扩展名判定忽略大小写。上一轮产物（stem 以 _live 结尾）不作为输入。
 */
object PairPlanner {
    const val OUTPUT_SUFFIX = "_live"

    fun plan(source: List<DirEntry>, target: List<DirEntry>): PlanResult {
        val images = LinkedHashMap<String, DirEntry>()
        val videos = LinkedHashMap<String, DirEntry>()
        for (entry in source) {
            val stem = stemOf(entry.name, extension(entry.name)) ?: continue
            when (extension(entry.name)?.lowercase()) {
                "jpg", "jpeg" -> if (!stem.lowercase().endsWith(OUTPUT_SUFFIX)) images.putIfAbsent(stem, entry)
                "mp4" -> videos.putIfAbsent(stem, entry)
            }
        }
        val existingOutputs = target.map { it.name.lowercase() }.toHashSet()
        val stems = (images.keys + videos.keys).sorted()

        val jobs = ArrayList<PairJob>()
        val unpaired = ArrayList<Unpaired>()
        for (stem in stems) {
            val image = images[stem]
            val video = videos[stem]
            when {
                image != null && video != null -> {
                    val outputName = stem + OUTPUT_SUFFIX + ".jpg"
                    jobs += PairJob(
                        stem = stem,
                        imageName = image.name,
                        videoName = video.name,
                        outputName = outputName,
                        imageSize = image.size,
                        videoSize = video.size,
                        outputExists = outputName.lowercase() in existingOutputs,
                    )
                }
                image != null -> unpaired += Unpaired(image.name, Missing.VIDEO)
                video != null -> unpaired += Unpaired(video.name, Missing.IMAGE)
            }
        }
        return PlanResult(jobs, unpaired)
    }

    private fun extension(name: String): String? {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) null else name.substring(dot + 1)
    }

    private fun stemOf(name: String, ext: String?): String? {
        if (ext == null) return null
        return name.substring(0, name.length - ext.length - 1).ifEmpty { null }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :core:test --tests '*PairPlannerTest'`
Expected: 8 个测试全绿。注意 `previous outputs` 那条：`a_live.mp4` 的 stem 是 `a_live`，它不进 `images`（因为扩展名是 mp4 走 videos 分支）→ 期望它出现在 `unpaired`（缺图）。若实现让 `a_live.mp4` 进了 videos，就会与 `a_live.jpg`（被剔除）配不上而成对失败——这正是期望行为。

- [ ] **Step 5: 提交**

```bash
git add core/src
git commit -m "feat(core): pair same-stem jpg and mp4 into plan jobs"
```

---

## Task 7: 存储抽象与内存实现

**Files:**
- Create: `core/src/main/kotlin/dev/zayn/livephotobatch/core/BatchStorage.kt`
- Create: `core/src/test/kotlin/dev/zayn/livephotobatch/core/InMemoryStorage.kt`
- Test: `core/src/test/kotlin/dev/zayn/livephotobatch/core/InMemoryStorageTest.kt`

**Interfaces:**
- Consumes: `ChannelSource`, `DirEntry`
- Produces:
  `interface BatchStorage（7 个成员，与 Step 1 代码一致）{ fun list(dirPath: String): List<DirEntry>; fun open(filePath: String): ChannelSource; fun ensureDir(dirPath: String); fun openWrite(filePath: String): WritableByteChannel; fun move(from: String, to: String): Boolean; fun delete(filePath: String): Boolean; fun exists(filePath: String): Boolean }`

- [ ] **Step 1: 定义接口**

`BatchStorage.kt`：

```kotlin
package dev.zayn.livephotobatch.core

import java.nio.channels.WritableByteChannel

/**
 * BatchRunner 需要的全部文件动作。:app 用 java.io.File 实现，测试用内存实现。
 * 成员级契约见 BatchStorage.kt 的 KDoc：move 不得先删目标、exists/delete 只认普通文件、
 * list 对不存在的目录返回空且从不抛。
 */
interface BatchStorage {
    /** 只列一层，返回普通文件。目录与特殊文件不出现。 */
    fun list(dirPath: String): List<DirEntry>
    fun open(filePath: String): ChannelSource
    fun exists(filePath: String): Boolean
    fun ensureDir(dirPath: String)
    /** 打开写入通道；父目录必须已存在。调用方负责 close。 */
    fun openWrite(filePath: String): WritableByteChannel
    /** 原子改名，允许覆盖已存在的 to。 */
    fun move(from: String, to: String): Boolean
    fun delete(filePath: String): Boolean
}
```

- [ ] **Step 2: 写内存实现的测试**

`InMemoryStorageTest.kt`：

```kotlin
package dev.zayn.livephotobatch.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.io.ByteArrayOutputStream

class InMemoryStorageTest {
    @Test fun `write move delete round trip`() {
        val storage = InMemoryStorage()
        storage.ensureDir("/t")
        Channels.newChannel(storage.openWrite("/t/a.jpg")).use { it.put(ByteBuffer.wrap(byteArrayOf(1, 2))) }
        assertTrue(storage.exists("/t/a.jpg"))
        assertTrue(storage.move("/t/a.jpg", "/t/b.jpg"))
        assertFalse(storage.exists("/t/a.jpg"))
        val out = ByteArrayOutputStream()
        Channels.newChannel(out).use { out.write ByteArray(0) }
        storage.open("/t/b.jpg").open().use { ch ->
            val buf = ByteBuffer.allocate(4)
            while (buf.hasRemaining() && ch.read(buf) > 0) { }
            buf.flip()
            assertEquals(2, buf.remaining())
        }
        assertTrue(storage.delete("/t/b.jpg"))
    }

    @Test fun `move overwrites existing target`() {
        val storage = InMemoryStorage()
        storage.ensureDir("/t")
        storage.openWrite("/t/x").use { it.put(ByteBuffer.wrap(byteArrayOf(9))) }
        storage.openWrite("/t/y").use { it.put(ByteBuffer.wrap(byteArrayOf(7))) }
        assertTrue(storage.move("/t/y", "/t/x"))
        assertFalse(storage.exists("/t/y"))
        assertTrue(storage.exists("/t/x"))
    }

    @Test fun `list skips other directories`() {
        val storage = InMemoryStorage()
        storage.ensureDir("/a")
        storage.openWrite("/a/one.jpg").use { it.put(ByteBuffer.wrap(ByteArray(3))) }
        storage.openWrite("/a/sub/two.jpg").use { it.put(ByteBuffer.wrap(ByteArray(5))) }
        assertEquals(listOf("one.jpg"), storage.list("/a").map { it.name })
    }
}
```

注意 `storage.openWrite(...).use { }`：`WritableByteChannel` 是 `Closeable`，Kotlin `use` 可用。`InMemoryStorage.openWrite` 必须返回一个**关闭时才落盘**的通道。

- [ ] **Step 3: 实现内存存储**

`InMemoryStorage.kt`：

```kotlin
package dev.zayn.livephotobatch.core

import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.WritableByteChannel
import java.io.ByteArrayOutputStream

/** 单测用；key 是绝对路径，目录由 key 的前缀隐含。 */
class InMemoryStorage(initial: Map<String, ByteArray> = emptyMap()) : BatchStorage {
    private val files = LinkedHashMap<String, ByteArray>(initial)

    val tmpFiles: Set<String> get() = files.keys.filter { it.endsWith(".part") }.toSet()

    override fun list(dirPath: String): List<DirEntry> {
        val prefix = dirPath.trimEnd('/') + "/"
        return files.entries
            .filter { it.key.startsWith(prefix) && !it.key.substring(prefix.length).contains('/') }
            .map { DirEntry(it.key.substring(prefix.length), it.value.size.toLong()) }
            .sortedBy { it.name }
    }

    override fun open(filePath: String): ChannelSource {
        val bytes = files[filePath] ?: throw java.io.FileNotFoundException(filePath)
        return ByteArraySource(bytes)
    }

    override fun exists(filePath: String): Boolean = files.containsKey(filePath)

    override fun ensureDir(dirPath: String) { /* 目录隐含在 key 里，无需操作 */ }

    override fun openWrite(filePath: String): WritableByteChannel {
        val buffer = ByteArrayOutputStream()
        val sink = Channels.newChannel(buffer)
        return object : WritableByteChannel {
            private var open = true
            override fun write(src: ByteBuffer): Int {
                check(open); return sink.write(src)
            }
            override fun close() {
                if (!open) return
                open = false
                sink.close()
                files[filePath] = buffer.toByteArray()
            }
            override fun isOpen(): Boolean = open
        }
    }

    override fun move(from: String, to: String): Boolean {
        val bytes = files.remove(from) ?: return false
        files[to] = bytes
        return true
    }

    override fun delete(filePath: String): Boolean = files.remove(filePath) != null

    fun bytesOf(filePath: String): ByteArray = files[filePath] ?: ByteArray(0)
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :core:test --tests '*InMemoryStorageTest'`
Expected: 3 个测试全绿。

- [ ] **Step 5: 提交**

```bash
git add core/src
git commit -m "feat(core): add storage seam with in-memory implementation"
```

---

## Task 8: 批量编排器

**Files:**
- Create: `core/src/main/kotlin/dev/zayn/livephotobatch/core/BatchRunner.kt`
- Test: `core/src/test/kotlin/dev/zayn/livephotobatch/core/BatchRunnerTest.kt`

**Interfaces:**
- Consumes: `BatchStorage`, `PairPlanner`, `PlanResult`, `MotionPhotoComposer`, `ComposeResult`
- Produces:
  `data class BatchOptions(val sourceDir: String, val targetDir: String, val deleteOriginals: Boolean)`
  `enum class ItemStatus { SUCCESS, SKIPPED, FAILED }`
  `data class ItemResult(val stem, val outputName, val status, val reason, val bytes,
  val deleteFailures: List<String> = emptyList(), val deletedSources: List<String> = emptyList())`
  —— 最后两个列表分别是"删除失败的文件名"和"已成功删除的绝对路径"（供 MediaStore 收敛用）
  `data class BatchReport(val items: List<ItemResult>, val composedBytes: Long, val cancelled: Boolean)`
  `fun interface CancelToken { fun cancelled(): Boolean }`
  `class BatchRunner(private val storage: BatchStorage) { fun plan(options): PlanResult; fun run(plan, options, cancel, onItem): BatchReport }`

- [ ] **Step 1: 写失败的测试**

`BatchRunnerTest.kt`：

```kotlin
package dev.zayn.livephotobatch.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BatchRunnerTest {
    private val video = Fixtures.mp4(300)
    private val image = Fixtures.jpeg()

    private fun runnerWith(vararg entries: Pair<String, ByteArray>): Pair<BatchRunner, InMemoryStorage> {
        val storage = InMemoryStorage(entries.toMap())
        return BatchRunner(storage) to storage
    }

    private fun options(deleteOriginals: Boolean = false) =
        BatchOptions("/s", "/t", deleteOriginals)

    @Test fun `happy path writes output and reports success`() {
        val (runner, storage) = runnerWith("/s/a.jpg" to image, "/s/a.mp4" to video)
        val report = runner.run(runner.plan(options()), options(), CancelToken { false }) {}
        val item = report.items.single()
        assertEquals(ItemStatus.SUCCESS, item.status)
        assertEquals(300L, item.bytes)
        assertTrue(storage.exists("/t/a_live.jpg"))
        assertFalse(storage.tmpFiles.isNotEmpty())
    }

    @Test fun `deleting originals only happens after commit`() {
        val (runner, storage) = runnerWith("/s/a.jpg" to image, "/s/a.mp4" to video)
        runner.run(runner.plan(options(true)), options(true), CancelToken { false }) { }
        assertFalse(storage.exists("/s/a.jpg"))
        assertFalse(storage.exists("/s/a.mp4"))
        assertTrue(storage.exists("/t/a_live.jpg"))
    }

    @Test fun `already motion photo items are skipped and keep originals`() {
        val embedded = Fixtures.app1(Fixtures.XAP, XmpPacket.motionPhoto(10L))
        val dirty = Fixtures.jpeg(headSegments = listOf(embedded, Fixtures.sof0()))
        val (runner, storage) = runnerWith("/s/a.jpg" to dirty, "/s/a.mp4" to video)
        val report = runner.run(runner.plan(options(true)), options(true), CancelToken { false }) { }
        assertEquals(ItemStatus.SKIPPED, report.items.single().status)
        assertEquals(FailureReason.ALREADY_MOTION_PHOTO, report.items.single().reason)
        assertTrue(storage.exists("/s/a.jpg"))
        assertFalse(storage.exists("/t/a_live.jpg"))
    }

    @Test fun `unpaired inputs are reported as skipped`() {
        val (runner, _) = runnerWith("/s/a.jpg" to image)
        val report = runner.run(runner.plan(options()), options(), CancelToken { false }) { }
        assertEquals(ItemStatus.SKIPPED, report.items.single().status)
        assertEquals(FailureReason.NO_PAIR, report.items.single().reason)
    }

    @Test fun `overwrite replaces the existing output`() {
        val (runner, storage) = runnerWith(
            "/s/a.jpg" to image, "/s/a.mp4" to video, "/t/a_live.jpg" to ByteArray(5)
        )
        val report = runner.run(runner.plan(options()), options(), CancelToken { false }) { }
        assertEquals(ItemStatus.SUCCESS, report.items.single().status)
        assertEquals(image.size + XmpPacket.app1Segment(300L).size + video.size,
            storage.bytesOf("/t/a_live.jpg").size)
    }

    @Test fun `cancel stops before the next item and keeps what is done`() {
        val (runner, storage) = runnerWith(
            "/s/a.jpg" to image, "/s/a.mp4" to video, "/s/b.jpg" to image, "/s/b.mp4" to video
        )
        var seen = 0
        val report = runner.run(runner.plan(options()), options(), CancelToken { seen >= 1 }) { seen++ }
        assertTrue(report.cancelled)
        assertEquals(1, report.items.size)
        assertTrue(storage.exists("/t/a_live.jpg"))
        assertFalse(storage.exists("/t/b_live.jpg"))
    }

    @Test fun `progress callback sees every item exactly once`() {
        val (runner, _) = runnerWith(
            "/s/a.jpg" to image, "/s/a.mp4" to video, "/s/b.jpg" to image, "/s/b.mp4" to video
        )
        val seen = mutableListOf<String>()
        val report = runner.run(runner.plan(options()), options(), CancelToken { false }) { seen += it.stem }
        assertEquals(listOf("a", "b"), seen)
        assertEquals(2, report.items.size)
    }

    @Test fun `target dir is created before writing`() {
        val (runner, storage) = runnerWith("/s/a.jpg" to image, "/s/a.mp4" to video)
        runner.run(runner.plan(options()), options(), CancelToken { false }) { }
        assertTrue(storage.exists("/t/a_live.jpg"))
    }

    @Test fun `unpaired item does not abort the batch`() {
        val (runner, _) = runnerWith("/s/a.jpg" to image, "/s/b.jpg" to image, "/s/b.mp4" to video)
        val report = runner.run(runner.plan(options()), options(), CancelToken { false }) { }
        assertEquals(ItemStatus.SKIPPED, report.items.first { it.stem == "a" }.status)
        assertEquals(ItemStatus.SUCCESS, report.items.first { it.stem == "b" }.status)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :core:test --tests '*BatchRunnerTest'`
Expected: 编译失败 `Unresolved reference: BatchRunner`。

- [ ] **Step 3: 实现**

`BatchRunner.kt`：

```kotlin
package dev.zayn.livephotobatch.core

import java.nio.channels.WritableByteChannel

data class BatchOptions(
    val sourceDir: String,
    val targetDir: String,
    val deleteOriginals: Boolean,
)

enum class ItemStatus { SUCCESS, SKIPPED, FAILED }

data class ItemResult(
    val stem: String,
    val outputName: String,
    val status: ItemStatus,
    val reason: FailureReason?,
    val bytes: Long,
    val deleteFailures: List<String> = emptyList(),
    val deletedSources: List<String> = emptyList(),
)

data class BatchReport(
    val items: List<ItemResult>,
    val composedBytes: Long,
    val cancelled: Boolean,
)

fun interface CancelToken { fun cancelled(): Boolean }

object NoCancel : CancelToken { override fun cancelled() = false }

/**
 * spec §7.5：先写 `.part`，force 后 rename，rename 成功才允许删除原文件。
 * spec §9：单项失败不中断批次。取消在当前项之后生效。
 */
class BatchRunner(private val storage: BatchStorage) {

    fun plan(options: BatchOptions): PlanResult =
        PairPlanner.plan(storage.list(options.sourceDir), storage.list(options.targetDir))

    fun run(
        plan: PlanResult,
        options: BatchOptions,
        cancel: CancelToken,
        onItem: (ItemResult) -> Unit,
    ): BatchReport {
        val items = ArrayList<ItemResult>()
        var composed = 0L
        var cancelled = false

        for (missing in plan.unpaired) {
            val result = ItemResult(
                stem = missing.name.substringBeforeLast('.'),
                outputName = "",
                status = ItemStatus.SKIPPED,
                reason = FailureReason.NO_PAIR,
                bytes = 0,
                deleteFailures = emptyList(),
            )
            items += result
            onItem(result)
        }

        if (plan.jobs.isNotEmpty()) {
            storage.ensureDir(options.targetDir)
            storage.ensureDir("${options.targetDir}/.tmp")
        }

        for (job in plan.jobs) {
            if (cancel.cancelled()) { cancelled = true; break }
            val result = composeOne(job, options)
            items += result
            if (result.status == ItemStatus.SUCCESS) composed += result.bytes
            onItem(result)
        }
        return BatchReport(items, composed, cancelled)
    }

    private fun composeOne(job: PairJob, options: BatchOptions): ItemResult {
        val imagePath = "${options.sourceDir}/${job.imageName}"
        val videoPath = "${options.sourceDir}/${job.videoName}"
        val finalPath = "${options.targetDir}/${job.outputName}"
        val tmpPath = "${options.targetDir}/.tmp/${job.outputName}.part"
        return try {
            val result = storage.openWrite(tmpPath).use { sink ->
                MotionPhotoComposer.compose(storage.open(imagePath), storage.open(videoPath), sink)
            }
            when (result) {
                is ComposeResult.Failure -> {
                    storage.delete(tmpPath)
                    ItemResult(job.stem, job.outputName,
                        status = if (result.reason in SKIPPABLE) ItemStatus.SKIPPED else ItemStatus.FAILED,
                        reason = result.reason, bytes = 0, deleteFailures = emptyList())
                }
                is ComposeResult.Success -> {
                    if (!storage.move(tmpPath, finalPath)) {
                        storage.delete(tmpPath)
                        ItemResult(job.stem, job.outputName, ItemStatus.FAILED,
                            FailureReason.IO_ERROR, 0, emptyList())
                    } else {
                        val deleteFailures = ArrayList<String>()
                        val deleted = ArrayList<String>()
                        if (options.deleteOriginals) {
                            if (storage.delete(imagePath)) deleted += imagePath
                            else deleteFailures += job.imageName
                            if (storage.delete(videoPath)) deleted += videoPath
                            else deleteFailures += job.videoName
                        }
                        ItemResult(job.stem, job.outputName, ItemStatus.SUCCESS, null,
                            result.totalBytes, deleteFailures, deleted)
                    }
                }
            }
        } catch (e: Exception) {
            storage.delete(tmpPath)
            ItemResult(job.stem, job.outputName, ItemStatus.FAILED, FailureReason.IO_ERROR, 0, emptyList())
        }
    }

    private companion object {
        // spec 只在「已是动态照片」与「配不上对」两种情况下说"跳过"。其余拒绝都是
        // FAILED —— 尤其 MALFORMED_JPEG：Task 2 的 Cursor 会把 IOException 折叠成
        // Malformed，若把它算作跳过，真实磁盘故障会被埋进"坏文件"里
        val SKIPPABLE = setOf(FailureReason.ALREADY_MOTION_PHOTO)
    }
}
```


- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :core:test --tests '*BatchRunnerTest'`
Expected: 9 个测试全绿。

- [ ] **Step 5: 全量回归**

Run: `./gradlew :core:test`
Expected: 全部模块测试通过（Task 1-5 累计 40 条）。

- [ ] **Step 6: 提交**

```bash
git add core/src
git commit -m "feat(core): batch runner with tmp commit, cancel and report"
```

---

## Task 9: 接入 :app 模块与真实文件存储

**前置（人工，一次性）**：CI 上的 Android SDK 自带 platform 36；本地若要编译 `:app`，需在 Android Studio
→ Settings → Languages & Appearance → System Settings → Android SDK 里勾选 **SDK Platform Android 36**
（本机当前只有 `platforms/android-37.0`，没有 `cmdline-tools`）。本 Task 之后 `:app` 的编译验证以 CI 为准。

**Files:**
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/kotlin/dev/zayn/livephotobatch/app/io/FileStorage.kt`
- Create: `app/src/androidTest/kotlin/dev/zayn/livephotobatch/app/io/FileStorageTest.kt`
- Modify: `settings.gradle.kts`（`include(":app")`）
- Modify: `.github/workflows/build.yml`（追加 `apk` job，见 Step 5）

**Interfaces:**
- Consumes: `BatchStorage`, `ChannelSource`, `DirEntry`
- Produces: `class FileStorage : BatchStorage`，`fun fileChannelSource(f: java.io.File): ChannelSource`

- [ ] **Step 1: 加 :app 模块**

`settings.gradle.kts` 末尾把 `include(":core")` 改成：

```kotlin
include(":core", ":app")
```

`app/build.gradle.kts`：

```kotlin
plugins {
    // AGP 9.4.1 自带 Kotlin 支持，不再单独 apply kotlin.android（会与内建 Kotlin 冲突）
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.zayn.livephotobatch"
    // compose-bom 2026.09.00 / lifecycle 2.11.0 的 AAR 元数据要求 compileSdk >= 37；
    // targetSdk 保持 36，运行时行为不受这条依赖约束影响
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        applicationId = "dev.zayn.livephotobatch"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
```

`gradle/libs.versions.toml` 追加：

```toml
[versions]
composeBom = "2026.09.00"
activityCompose = "1.13.0"
lifecycle = "2.11.0"
androidxTestExt = "1.3.0"
androidxTestRunner = "1.7.0"

[libraries]
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-test-ext-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxTestExt" }
androidx-test-runner = { group = "androidx.test", name = "runner", version.ref = "androidxTestRunner" }

[plugins]
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
```

`app/src/main/AndroidManifest.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
        android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="29" />
    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE"
        tools:ignore="ScopedStorage" />

    <application
        android:label="Live Photo Batch"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 2: 写失败的仪器化测试**

`app/src/androidTest/kotlin/dev/zayn/livephotobatch/app/io/FileStorageTest.kt`：

```kotlin
package dev.zayn.livephotobatch.app.io

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class FileStorageTest {
    private fun storage() = FileStorage(
        InstrumentationRegistry.getInstrumentation().targetContext.filesDir.path
    )

    @Test fun writeMoveReadDeleteRoundTrip() {
        val s = storage()
        s.ensureDir("out")
        s.openWrite("out/a.jpg").use { it.put(ByteBuffer.wrap(byteArrayOf(1, 2, 3))) }
        assertTrue(s.move("out/a.jpg", "out/b.jpg"))
        assertFalse(s.exists("out/a.jpg"))
        val channel = s.open("out/b.jpg").open()
        val buf = ByteBuffer.allocate(3)
        while (buf.hasRemaining() && channel.read(buf) > 0) { }
        assertEquals(3, buf.position())
        channel.close()
        assertEquals(3L, s.open("out/b.jpg").size())
        assertTrue(s.delete("out/b.jpg"))
    }

    @Test fun listReturnsOnlyDirectChildrenSorted() {
        val s = storage()
        s.ensureDir("d")
        s.openWrite("d/z.txt").use { it.put(ByteBuffer.wrap(byteArrayOf(1))) }
        s.openWrite("d/a.txt").use { it.put(ByteBuffer.wrap(byteArrayOf(1, 2))) }
        s.ensureDir("d/sub")
        s.openWrite("d/sub/hidden.txt").use { it.put(ByteBuffer.wrap(byteArrayOf(1))) }
        assertEquals(listOf("a.txt", "z.txt"), s.list("d").map { it.name })
        assertEquals(2L, s.list("d").first { it.name == "a.txt" }.size)
    }
}
```

说明：`FileStorage` 有一个 root（生产 `"/storage/emulated/0"`，测试用应用私有目录）。`resolve()`
对已经在 root 之下的绝对路径原样使用，相对路径拼在 root 下，因此仪器化测试里的 `"out/a.jpg"` 与
生产里的 `"/storage/emulated/0/DCIM/Camera"` 走同一套逻辑。

- [ ] **Step 3: 跑测试确认失败**

Run（需要接设备或有 SDK 环境；本地缺 platform 36 时跳过，Task 12 由 CI 覆盖）：
`./gradlew :app:connectedDebugAndroidTest`
Expected: 编译失败 `Unresolved reference: FileStorage`。

- [ ] **Step 4: 实现 FileStorage**

`app/src/main/kotlin/dev/zayn/livephotobatch/app/io/FileStorage.kt`：

```kotlin
package dev.zayn.livephotobatch.app.io

import dev.zayn.livephotobatch.core.ChannelSource
import dev.zayn.livephotobatch.core.DirEntry
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel

/**
 * 路径一律是绝对路径；root 参数只用于测试时改写到应用私有目录。
 * 传进来的绝对路径若不在 root 下，按原样使用——生产环境就是 "/"。
 */
class FileStorage(private val root: String) : dev.zayn.livephotobatch.core.BatchStorage {

    private fun resolve(path: String): File =
        if (path.startsWith(root + "/")) File(path) else File(File(root), path.removePrefix("/"))

    override fun list(dirPath: String): List<DirEntry> {
        val dir = resolve(dirPath)
        val children = dir.listFiles() ?: return emptyList()
        return children.filter { it.isFile && it.canRead() }
            .map { DirEntry(it.name, it.length()) }
            .sortedBy { it.name }
    }

    override fun open(filePath: String): ChannelSource {
        val file = resolve(filePath)
        return object : ChannelSource {
            override fun open(): ReadableByteChannel = RandomAccessFile(file, "r").channel
            override fun size(): Long = file.length()
        }
    }

    // 契约要求只认普通文件，所以必须是 isFile 而不是 exists()
    override fun exists(filePath: String): Boolean = resolve(filePath).isFile

    override fun ensureDir(dirPath: String) {
        val dir = resolve(dirPath)
        if (!dir.isDirectory && !dir.mkdirs()) throw java.io.IOException("无法创建目录 $dirPath")
    }

    override fun openWrite(filePath: String): WritableByteChannel {
        val file = resolve(filePath)
        file.parentFile?.mkdirs()
        val channel = RandomAccessFile(file, "rw").channel
        channel.truncate(0)
        return object : WritableByteChannel {
            private var open = true
            override fun write(src: ByteBuffer): Int = channel.write(src)
            override fun close() {
                if (!open) return
                open = false
                try { channel.force(true) } finally { channel.close() }
            }
            override fun isOpen(): Boolean = open
        }
    }

    override fun move(from: String, to: String): Boolean {
        val src = resolve(from)
        val dst = resolve(to)
        if (!src.isFile) return false
        dst.parentFile?.mkdirs()
        // 不先删目标：renameTo 走 rename(2)，同文件系统内对已存在目标是原子替换；先删会开一个旧文件已消失的窗口
        if (src.renameTo(dst)) return true
        return try {
            src.copyTo(dst, overwrite = true)
            src.delete()
        } catch (e: java.io.IOException) {
            false
        }
    }

    // 同理：目录必须返回 false 且不动它，所以先判 isFile
    override fun delete(filePath: String): Boolean =
        resolve(filePath).let { it.isFile && it.delete() }
}
```

- [ ] **Step 5: 给 CI 加 apk job**

在 `.github/workflows/build.yml` 末尾追加（`core-tests` 之后，同缩进层级）：

```yaml
  apk:
    name: Build debug APK
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "17" }
      # 不用 android-actions/setup-android@v3：它默认要装 Google 已从 SDK 仓库删除的 `tools` 包，
      # 直接用 runner 自带的 sdkmanager
      - name: Install SDK packages
        run: |
          SDKM="$(command -v sdkmanager || echo "${ANDROID_HOME:-/usr/local/lib/android/sdk}/cmdline-tools/latest/bin/sdkmanager")"
          yes | "$SDKM" --licenses >/dev/null 2>&1 || true
          "$SDKM" "platforms;android-37.0" "build-tools;36.0.0"
      - run: ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest --no-daemon
      - uses: actions/upload-artifact@v4
        with:
          name: live-photo-batch-debug
          path: app/build/outputs/apk/debug/*.apk
```

本地没有 `platforms/android-36`，所以 `:app` 的编译与单测从这一步起一律以 CI 为准；仪器化测试在
Task 12 用真机跑。

- [ ] **Step 6: 提交**

```bash
git add settings.gradle.kts gradle/libs.versions.toml app .github
git commit -m "feat(app): add android module with file-backed storage"
```

---

## Task 10: 权限与目录浏览

**Files:**
- Create: `app/src/main/kotlin/dev/zayn/livephotobatch/app/perm/PermissionGate.kt`
- Create: `app/src/main/kotlin/dev/zayn/livephotobatch/app/browse/DirBrowser.kt`
- Test: `app/src/test/kotlin/dev/zayn/livephotobatch/app/perm/PermissionGateTest.kt`
- Test: `app/src/test/kotlin/dev/zayn/livephotobatch/app/browse/DirBrowserTest.kt`

**Interfaces:**
- Produces:
  `class PermissionGate(isManager: () -> Boolean) { fun granted(): Boolean }`
  `class DirBrowser(private val storage: FileStorage) { fun subdirs(path: String): List<String>; fun presets(): List<String> }`

- [ ] **Step 1: 写单元测试**

`PermissionGateTest.kt`（`app/src/test` 是本地 JVM 测试，不需要设备；`:app` 需要
`testOptions.unitTests.isIncludeAndroidResources = false` 且不引用 Android 类——`PermissionGate`
通过注入的 lambda 读状态，因此可测）：

```kotlin
package dev.zayn.livephotobatch.app.perm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionGateTest {
    @Test fun `reflects injected state`() {
        assertFalse(PermissionGate { false }.granted())
        assertTrue(PermissionGate { true }.granted())
    }
}
```

`DirBrowserTest.kt`：

```kotlin
package dev.zayn.livephotobatch.app.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DirBrowserTest {
    @Test fun `lists only readable subdirectories and sorts them`() {
        val root = File(System.getProperty("java.io.tmpdir"), "lpb-test-${System.nanoTime()}")
        File(root, "b").mkdirs()
        File(root, "a").mkdirs()
        File(root, "a/file.txt").apply { parentFile.mkdirs(); writeText("x") }
        val names = DirBrowser(root.path).subdirs("").map { it.name }
        assertEquals(listOf("a", "b"), names)
        root.deleteRecursively()
    }

    @Test fun `missing directory yields empty list`() {
        assertTrue(DirBrowser("/nonexistent-zzz").subdirs("nope").isEmpty())
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testDebugUnitTest`（无 platform 36 时由 CI 跑；本地跳过并在提交信息里注明）
Expected: 编译失败 `Unresolved reference: DirBrowser`。

- [ ] **Step 3: 实现**

`perm/PermissionGate.kt`：

```kotlin
package dev.zayn.livephotobatch.app.perm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

class PermissionGate(private val isManager: () -> Boolean) {
    fun granted(): Boolean = isManager()

    companion object {
        fun of(context: Context): PermissionGate = PermissionGate {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
        }

        /** vivo 上可能落不到系统页，调用方需在跳转后回查 granted()（spec R2）。 */
        fun requestIntent(context: Context): Intent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}"))
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"))
            }
    }
}
```

`browse/DirBrowser.kt`：

```kotlin
package dev.zayn.livephotobatch.app.browse

import java.io.File

data class DirItem(val absolutePath: String, val name: String, val parent: String)

class DirBrowser(private val root: String) {
    /** 常用入口；不存在的一律过滤掉 */
    fun presets(): List<DirItem> {
        val storageRoot = File("/storage/emulated/0")
        val candidates = listOf(
            storageRoot to "内部存储",
            File(storageRoot, "DCIM") to "DCIM",
            File(storageRoot, "DCIM/Camera") to "相机",
            File(storageRoot, "Movies") to "视频",
            File(storageRoot, "Pictures") to "图片",
            File(storageRoot, "Download") to "下载",
        )
        return candidates.filter { it.first.isDirectory }
            .map { DirItem(it.first.absolutePath, it.second, it.first.parentFile?.absolutePath ?: "") }
    }

    fun subdirs(relative: String): List<DirItem> {
        val dir = if (relative.startsWith("/")) File(relative) else File(File(root), relative)
        val children = dir.listFiles() ?: return emptyList()
        return children.filter { it.isDirectory && it.canRead() && !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }
            .map { DirItem(it.absolutePath, it.name, dir.absolutePath) }
    }
}
```

- [ ] **Step 4: 提交**

```bash
git add app/src
git commit -m "feat(app): all-files permission gate and directory browser"
```

---

## Task 11: ViewModel 与单屏 UI

**Files:**
- Create: `app/src/main/kotlin/dev/zayn/livephotobatch/app/BatchViewModel.kt`
- Create: `app/src/main/kotlin/dev/zayn/livephotobatch/app/BatchScreen.kt`
- Create: `app/src/main/kotlin/dev/zayn/livephotobatch/app/MainActivity.kt`
- Create: `app/src/main/kotlin/dev/zayn/livephotobatch/app/MediaStoreSync.kt`

**Interfaces:**
- Consumes: `BatchRunner`, `BatchOptions`, `BatchReport`, `FileStorage`, `DirBrowser`, `PermissionGate`
- Produces: `class BatchViewModel(app: Application) : ViewModel()`，`data class UiState(...)`

- [ ] **Step 0: 先补 Compose 接线（Task 9 之后 `:app` 不再单独 apply KGP，也没有 compose 开关）**

`app/build.gradle.kts` 的 `android { }` 里加 `buildFeatures { compose = true }`，plugins 块加
`alias(libs.plugins.kotlin.compose)`，并在 `gradle/libs.versions.toml` 里加
`kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }`。
以 CI 的 `:app:assembleDebug` 为准；若 compose 编译器版本与 Kotlin 2.4.21 不匹配，报 BLOCKED 而不是猜。

- [ ] **Step 1: MediaStore 收敛**

`MediaStoreSync.kt`：

```kotlin
package dev.zayn.livephotobatch.app

import android.content.Context
import android.media.MediaScannerConnection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object MediaStoreSync {
    /** 产物要能被相册立刻看到，被删的原文件要从索引里消失（spec §7.6）。 */
    fun scan(context: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        val latch = CountDownLatch(1)
        MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { _, _ ->
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
    }
}
```

批量结束时的补扫用一次性传入全部路径；`scanFile` 的回调只回调一次也不影响收敛，因为等待超时后
继续。

- [ ] **Step 2: ViewModel**

`BatchViewModel.kt`：

```kotlin
package dev.zayn.livephotobatch.app

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zayn.livephotobatch.app.browse.DirBrowser
import dev.zayn.livephotobatch.app.io.FileStorage
import dev.zayn.livephotobatch.app.perm.PermissionGate
import dev.zayn.livephotobatch.core.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class UiState(
    val sourceDir: String = "/storage/emulated/0/DCIM/Camera",
    val targetDir: String = "/storage/emulated/0/Pictures/LivePhoto",
    val deleteOriginals: Boolean = false,
    val permissionGranted: Boolean = false,
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val bytes: Long = 0,
    val items: List<ItemResult> = emptyList(),
    val globalError: String? = null,
)

class BatchViewModel(private val app: Application) : ViewModel() {
    private val root = "/storage/emulated/0"
    private val gate = PermissionGate.of(app)
    val browser = DirBrowser(root)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private var job: Job? = null
    @Volatile private var cancelled = false

    fun refreshPermission() { _state.value = _state.value.copy(permissionGranted = gate.granted()) }

    fun setSource(path: String) { _state.value = _state.value.copy(sourceDir = path) }
    fun setTarget(path: String) { _state.value = _state.value.copy(targetDir = path) }
    fun setDeleteOriginals(value: Boolean) { _state.value = _state.value.copy(deleteOriginals = value) }

    fun start() {
        val snapshot = _state.value
        if (snapshot.running || !gate.granted()) return
        cancelled = false
        _state.value = snapshot.copy(
            running = true, done = 0, total = 0, bytes = 0,
            items = emptyList(), globalError = null,
        )
        job = viewModelScope.launch(Dispatchers.IO) {
            val storage = FileStorage(root)
            // spec §9：全局前置失败直接拒绝开始，而不是逐项报错
            if (!File(snapshot.sourceDir).isDirectory) {
                _state.value = _state.value.copy(running = false, globalError = "源目录不存在或不可读")
                return@launch
            }
            try {
                storage.ensureDir(snapshot.targetDir)
            } catch (e: Exception) {
                _state.value = _state.value.copy(running = false, globalError = "目标目录无法创建：${e.message}")
                return@launch
            }
            val runner = BatchRunner(storage)
            val options = BatchOptions(snapshot.sourceDir, snapshot.targetDir, snapshot.deleteOriginals)
            val plan = runner.plan(options)
            _state.value = _state.value.copy(total = plan.jobs.size + plan.unpaired.size)
            val touched = ArrayList<String>()
            val report = runner.run(plan, options, CancelToken { cancelled }) { item ->
                val current = _state.value
                _state.value = current.copy(
                    done = current.done + 1,
                    bytes = current.bytes + item.bytes,
                    items = current.items + item,
                )
                if (item.status == ItemStatus.SUCCESS) {
                    touched += "${snapshot.targetDir}/${item.outputName}"
                }
            }
            report.items.forEach { touched += it.deletedSources }
            MediaStoreSync.scan(app, touched)
            _state.value = _state.value.copy(running = false)
        }
    }

    fun cancel() { cancelled = true }
}
```

- [ ] **Step 3: UI**

`BatchScreen.kt`：

```kotlin
package dev.zayn.livephotobatch.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.zayn.livephotobatch.core.ItemStatus
import java.io.File

@Composable
fun BatchScreen(vm: BatchViewModel, request: () -> Unit) {
    val state by vm.state.collectAsState()
    var browsing by remember { mutableStateOf<((String) -> Unit)?>(null) }

    vm.refreshPermission()
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        if (!state.permissionGranted) {
            Button(onClick = request) {
                Text("授予所有文件访问权限")
            }
            Spacer(Modifier.height(12.dp))
        }
        PathRow("源目录", state.sourceDir, enabled = !state.running) { browsing = it }
        PathRow("目标目录", state.targetDir, enabled = !state.running) { browsing = it }
        Row {
            Checkbox(checked = state.deleteOriginals, onCheckedChange = vm::setDeleteOriginals)
            Text("合成后删除原文件（不可撤销）", Modifier.clickable { vm.setDeleteOriginals(!state.deleteOriginals) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = vm::start, enabled = state.permissionGranted && !state.running) { Text("开始合成") }
            Button(onClick = vm::cancel, enabled = state.running) { Text("取消") }
        }
        state.globalError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("进度 ${state.done}/${state.total}，已写出 ${state.bytes / 1024 / 1024} MB")
        LinearProgressIndicator(progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total },
            modifier = Modifier.fillMaxWidth())
        LazyColumn {
            items(state.items, key = { it.stem + it.outputName }) { item ->
                Text(
                    when (item.status) {
                        ItemStatus.SUCCESS -> "成功 ${item.stem} → ${item.outputName}"
                        ItemStatus.SKIPPED -> "跳过 ${item.stem}：${item.reason}"
                        ItemStatus.FAILED -> "失败 ${item.stem}：${item.reason}" +
                            if (item.deleteFailures.isNotEmpty()) "（原文件删除失败：${item.deleteFailures}）" else ""
                    }, Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }

    browsing?.let { onPick ->
        DirectoryPicker(vm.browser, onDismiss = { browsing = null }, onPick = { path -> onPick(path); browsing = null })
    }
}

@Composable
private fun PathRow(label: String, path: String, enabled: Boolean, onBrowse: ((String) -> Unit) -> Unit) {
    OutlinedTextField(value = path, onValueChange = { }, label = { Text(label) },
        modifier = Modifier.fillMaxWidth(), enabled = false, singleLine = true)
    TextButton(onClick = { onBrowse(path) }, enabled = enabled) { Text("浏览…") }
}

@Composable
private fun DirectoryPicker(browser: dev.zayn.livephotobatch.app.browse.DirBrowser,
                            onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var path by remember { mutableStateOf("/storage/emulated/0") }
    AlertDialog(onDismissRequest = onDismiss, confirmButton = {
        TextButton(onClick = { onPick(path) }) { Text("选这里") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text(path) },
        text = {
            Column {
                browser.presets().forEach { p ->
                    TextButton(onClick = { path = p.absolutePath }) { Text(p.name, maxLines = 1) }
                }
                browser.subdirs(path).forEach { d ->
                    TextButton(onClick = { path = d.absolutePath }) { Text("📁 ${d.name}", maxLines = 1) }
                }
            }
        })
}
```

- [ ] **Step 4: Activity**

`MainActivity.kt`：

```kotlin
package dev.zayn.livephotobatch.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.zayn.livephotobatch.app.perm.PermissionGate

class MainActivity : ComponentActivity() {
    private val vm: BatchViewModel by viewModels {
        viewModelFactory { initializer { BatchViewModel(application) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BatchScreen(vm, request = {
                runCatching {
                    startActivity(PermissionGate.requestIntent(this@MainActivity))
                }.onFailure {
                    android.widget.Toast.makeText(this@MainActivity,
                        "请在 设置 → 应用 → 特殊访问 → 所有文件访问 中手动开启",
                        android.widget.Toast.LENGTH_LONG).show()
                }
                vm.refreshPermission()
            })
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshPermission()
    }
}
```

- [ ] **Step 5: 提交**

```bash
git add app/src
git commit -m "feat(app): single screen batch ui with progress and report"
```

---

## Task 12: CI 出包与真机验收

**Files:**
- Modify: `README.md`（新建）

**Interfaces:**
- Consumes: 全部
- Produces: 可安装 APK + 实测性能数字

- [ ] **Step 1: 确认 CI 已绿**

推送分支后 `curl -H "Authorization: token $TOK" .../actions/runs?per_page=1` 查状态。
Expected: `core-tests` 与 `apk` 都成功，工件 `live-photo-batch-debug` 里有 APK。

- [ ] **Step 2: 下载工件并装机**

已安装的 `com.github.wszqkzqk.live_photo_conv` 是不同包名，可以直接并存。安装：

```bash
adb install -r -g "$(ls app/build/outputs/apk/debug/*.apk)"
adb shell appops set dev.zayn.livephotobatch MANAGE_EXTERNAL_STORAGE allow
```

第二条是可选捷径：不点系统授权页也能先跑通；正式流程仍走 app 内授权按钮。

- [ ] **Step 3: 功能验收（V2419A）**

1. 准备 3 对同名 `IMG_*.jpg` / `IMG_*.mp4`，`adb push` 到 `/storage/emulated/0/DCIM/Camera`。
2. 源目录选相机目录，目标目录选 `Pictures/LivePhoto`，不勾删除，点开始。
3. 在系统相册打开目标目录，逐条确认显示为动态照片、长按能播视频。
4. 重跑同一批，确认目标里已有同名产物被覆盖而不是报错，报告里这些项仍显示"成功"。
5. 把其中一对的 jpg 换成上游 Windows 版产出的 motion photo，确认该项在报告里显示"跳过：已是动态照片"，
   且目标目录没多出文件。
6. 勾选删除原文件后再跑一对，确认源两个文件消失、相册里不残留指向它们的条目；残留则记录为 spec R4 命中。
7. 取消：开始一个大批次后立即点取消，确认已完成的产物存在、`.tmp` 里没有遗留 `.part`。

- [ ] **Step 4: 性能实测（填 G5 预算）**

```bash
# 记录 100 对素材的合成耗时
adb shell "am start -n dev.zayn.livephotobatch/dev.zayn.livephotobatch.app.MainActivity"
# UI 上跑完后读取"进度 ... 已写出 MB"，同时抓峰值内存
adb shell "dumpsys meminfo dev.zayn.livephotobatch" | head -20
```

把实际耗时与 PSS 记进 `README.md` 的"实测"小节。超过 15 秒或超过 32 MB 即未达预算，回到
`MotionPhotoComposer` 的缓冲大小与 `FileStorage` 的通道选择做优化（当前实现每次写都用新建
`RandomAccessFile`，没有复用；这是首要怀疑点）。

- [ ] **Step 5: 写 README 并提交**

`README.md` 至少包含：它做什么、不做什么、权限要求与 vivo 授权路径、验收清单结果、实测数字、
XMP 字段与 `livemaker.vala` 的对应关系、构建命令。

```bash
git add README.md
git commit -m "docs: add acceptance checklist and measured numbers"
```

---

## 自检结论

- **spec 覆盖**：G1/G2/G3 → Task 6/8/11；G4 → Task 3（XMP 字段）+ Task 12 Step 4；G5 → Task 12 Step 5；
  spec §7.1-7.6 → Task 2/3/4/5/8/11；§9 三类全局前置失败 → Task 11 的权限短路 + `start()` 里的源目录
  可读检查与目标目录可建检查（`globalError` 展示）；§10 测试项 1-7 → Task 2-8 的测试；
  R1-R5 全部落在 Task 12 的验收清单里逐条验证。
- **占位符**：已消除"先写坏再修正"的写法，每个 Task 只保留一份代码。实现者必须在每个 Task 结束时
  真实运行该 Task 列出的测试命令并确认全绿，不允许"稍后补测"。
- **类型一致性**：`ComposeResult.Success(videoSize, totalBytes)`、`ItemResult(stem, outputName, status,
  reason, bytes, deleteFailures)`、`BatchOptions(sourceDir, targetDir, deleteOriginals)`、
  `FailureReason` 七个值、`PairJob` 七个字段在各 Task 中保持同名同序。
- **已知妥协**：`FileStorage.openWrite` 每次都新建 `RandomAccessFile`，没有句柄复用。若 Task 12
  Step 5 的实测超出 15 秒预算，这里是第一处要改的地方。


---

## Task 13: 尾巴分类与 XMP 合并（取代 spec §7.4.3 的旧规则）

依据 spec「增补（2026-10-18）」一节。改 `:core`，不动 `:app` 的 UI 形状（只多一个失败原因文案）。

**Files:**
- Create: `core/src/main/kotlin/dev/zayn/livephotobatch/core/TrailerClassifier.kt`
- Create: `core/src/main/kotlin/dev/zayn/livephotobatch/core/XmpMerger.kt`
- Modify: `core/src/main/kotlin/dev/zayn/livephotobatch/core/MotionPhotoComposer.kt`
- Modify: `core/src/main/kotlin/dev/zayn/livephotobatch/core/JpegXmpPatcher.kt`（新增把 trailer 字节也搬出去的选项）
- Modify: `core/src/main/kotlin/dev/zayn/livephotobatch/core/XmpPacket.kt`（暴露模板常量供合并复用）
- Test: `core/src/test/kotlin/.../TrailerClassifierTest.kt`, `XmpMergerTest.kt`, 扩充
  `MotionPhotoComposerTest.kt`、`Fixtures.kt`

**Interfaces:**
- Produces: `enum class TrailerKind { NONE, VIDEO, OTHER }`，
  `object TrailerClassifier { fun classify(head: ByteArray, xmpText: String?): TrailerKind }`
  （`head` = 从 `imageEnd` 起最多 16 字节，`xmpText` = `JpegStructure.oldXmpText`）
- Produces: `object XmpMerger { fun merge(sourcePacket: String?, videoSize: Long): ByteArray }` ——
  无源包时输出与 `XmpPacket.motionPhoto` 等价、字段集完全一致的包；有源包时按 spec §3 合并；
  源包不可解析时抛 `MalformedXmpException`
- Produces: `FailureReason` 新增值 `MALFORMED_XMP`（Task 8 的 `SKIPPABLE` 保持只有
  `ALREADY_MOTION_PHOTO`，故 `MALFORMED_XMP` 归 FAILED）
- Consumes: `JpegStructure.imageEnd`、`ChannelSource.size()`，不变的其他签名

**Steps:**

- [ ] 1. 先写失败的 `TrailerClassifierTest`：mp4 box 头（长度自洽 + `ftyp`/`moov`/`mdat`）→ VIDEO；
  长度自洽但 `free`/`skip` 后紧跟 `ftyp` → VIDEO；`FFD8` 开头 → OTHER；`vivo{` ASCII 开头 → OTHER；
  全零 → OTHER；空 head → NONE；只有 `GCamera:MicroVideoOffset="123"` 无尾巴 → VIDEO；
  `Item:Mime` 含 `video/mp4` → VIDEO；`hdrgm:Version="1.0"` 且只有 GainMap 项 → OTHER。
- [ ] 2. 跑 `./gradlew :core:test --tests '*TrailerClassifierTest'` 确认编译失败。
- [ ] 3. 实现 `TrailerClassifier`，box 长度用大端解析并做 `4 <= N <= remaining` 自洽检查；
  注意 `classify` 只看传入的 head，不得整文件读入。
- [ ] 4. 写失败的 `XmpMergerTest`：无源包 → 与模板逐字段一致；有 gain map 源包 → 保留
  `hdrgm:Version`、保留 Primary 与 GainMap 项、新增 MotionPhoto 项、`Item:Length` 与
  `MicroVideoOffset` 等于传入 videoSize、输出里只有一个 `Container:Directory`；
  源包已含 MotionPhoto 项与 `GCamera:*` → 合并后不重复（幂等：merge 的结果再 merge 一次字节相同）；
  畸形源包 → 抛 `MalformedXmpException`。
- [ ] 5. 用 `javax.xml.parsers.DocumentBuilderFactory` 实现合并（不引依赖；关闭外部实体解析：
  `XMLConstants.FEATURE_SECURE_PROCESSING` + 禁 DTD/外部实体，这是处理不可信 XMP 的硬要求）。
- [ ] 6. 改 composer：取 trailer head（从 `imageEnd` 读 ≤16 字节，用完关闭）→ `classify`；
  `VIDEO` 才返回 `ALREADY_MOTION_PHOTO`；`OTHER`/`NONE` 继续，并把 `imageEnd` 到 `size()` 的字节
  原样搬到 XMP 之后、视频之前；XMP 改走 `XmpMerger.merge(structure.oldXmpText, videoSize)`；
  `MalformedXmpException` → `Failure(MALFORMED_XMP)`。
- [ ] 7. 改 `JpegXmpPatcher.patch` 或在 composer 里补一段 trailer 拷贝 —— 选前者，避免两处各自
  处理边界；`patch()` 的返回值语义改为「写入 sink 的源图字节数（含 trailer，不含新 APP1）」，
  同步修正 `Success.totalBytes` 的算式与现有断言。
- [ ] 8. 在 `Fixtures.kt` 加 `gainMapJpeg()`（主图 + 第二张 JPEG + 声明 GainMap 的 XMP）与
  `vendorTrailerJpeg()` 夹具；补 composer 测试：这两种输入现在必须成功，且产物尾部顺序为
  trailer 在前、mp4 在后，XMP 同时声明 GainMap 与 MotionPhoto。
- [ ] 9. `./gradlew :core:test` 全绿；`git add core/src docs && git commit -m
  "feat(core): keep non-video jpeg trailers and merge motion photo xmp"`。
- [ ] 10. 真机复验（并入 Task 12 验收清单）：vivo 原片配对必须报成功且相册可播、gain map 未丢。

---

## Task 14: 产物形态收敛到 vivo 已识别的合成结果（取代 Task 13 的 §2/§3/§4）

spec 依据：设计文档「增补（2026-10-18b，对照 vivo 已识别语料）：产物形态收敛」§1-§4。Task 13 的
「搬非视频尾巴」+「保留/重建 Container 项」两条实现被参考语料推翻，本任务把它们改掉。

现场证据（已实测，不需要重做）：17 张被 vivo 相册当动态照片的合成结果里，抽验 3 张逐字节确认
**EOI 之后零字节、紧跟 mp4**，源片的 gain map 副图（40 万~106 万字）根本没进产物；EXIF `APP1`
在 XMP `APP1` **之前**（源序如此，产物也如此）；XMP 是**单个** `rdf:RDF`、七属性写在**同一个**
`rdf:Description` 上、`Container:Directory` 里是**空的** `<rdf:Seq/>`。而我们 Task 13 的真机产物
`g_live.jpg` 有两个兄弟 `rdf:RDF`（源包被掏空只剩 `hdrgm:Version="1.0"` 留在第一个 RDF，我们的声明
在第二个 RDF 里）、XMP 被挪到 EXIF 之前、EOI 与视频之间夹着 442,350 字节的副图——相册不认。

改动范围只有 `:core`；`:app`、`BatchRunner`、`PairPlanner`、UI 一律不动。

- [ ] **Step 1: 先把三条新形状要求写成失败的测试**

在 `core/src/test/.../XmpMergerTest.kt` 里加真实 vivo 源包作 fixture（**逐字节照抄**，这是回归用例；
放在 `Fixtures.kt` 里命名 `vivoSourcePacket()`，它是 191555 与 093558 两片共用的那一型）：

```kotlin
// 源包：<x:xmpmeta> 无 <?xpacket?> 头；Container 项写成 <Container:Item Item:Semantic="…"/>
// 这种"语义与 MIME 是子元素的属性"的形状；hdrgm:Version 与 Directory 同居一个 Description。
val vivo = """
<x:xmpmeta
  xmlns:x="adobe:ns:meta/"
  x:xmptk="Adobe XMP Core 5.1.2">
  <rdf:RDF
    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description
      xmlns:Container="http://ns.google.com/photos/1.0/container/"
      xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
      xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/"
      hdrgm:Version="1.0">
      <Container:Directory>
        <rdf:Seq>
          <rdf:li
            rdf:parseType="Resource">
            <Container:Item
              Item:Semantic="Primary"
              Item:Mime="image/jpeg"/>
          </rdf:li>
          <rdf:li
            rdf:parseType="Resource">
            <Container:Item
              Item:Semantic="GainMap"
              Item:Mime="image/jpeg"
              Item:Length="441963"/>
          </rdf:li>
        </rdf:Seq>
      </Container:Directory>
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>
""".trim()
```

断言全部**重解析 DOM 后检查节点数**，不许比对字符串（spec 增补 2026-10-18b §4）：

1. `merge(vivo, 3_863_927)` 的产物里 `x:xmpmeta` 名下 `rdf:RDF` **恰好 1 个**（这条现在必红——
   就是 Task 13 产出的两个 RDF）；
2. 七个动照片属性只出现在**1 个** `rdf:Description` 上，且该 Description 就是 `Container:Directory`
   的父节点；`GCamera:MicroVideoOffset` 等于传入的 `videoSize`；
3. Directory 的 `rdf:Seq` **没有** `rdf:li` 子元素（副图项与视频项都不声明）；
4. `hdrgm:Version="1.0"` 与根上的 `x:xmptk="Adobe XMP Core 5.1.2"` 仍在（源值优先）；
5. `merge(merge(vivo, size), size)` 与 `merge(vivo, size)` **字节相同**（幂等）；
6. 无源包 `merge(null, size)` 的形状与上面 1-3 条一致：单 RDF、七属性同在一个 Description、空 Seq
   （即 §7.3 旧模板作废，模板与合并产物同形）；
7. 源的 `Container:Directory` 出现在第二个 Description、或源里有多个 Directory 时：产物只剩宿主上
   那一个空 Directory，其他位置的 Directory 连同项一起消失；
8. 源里另一个 Description 带着 `GCamera:MotionPhoto="1"`：合并后该属性只在宿主 Description 上出现，
   不会有两组；
9. 源里的非 Directory 内容（例如 `<dc:title><rdf:Alt><rdf:li xml:lang="x-default">标题</rdf:li></rdf:Alt></dc:title>`）
   原样保留——这条守住"只动 Directory 与七属性"的边界。

- [ ] **Step 2: 跑 `./gradlew :core:test --tests '*XmpMergerTest*'` 确认失败**

Expected: 1/2/3/6/7 红（两个 RDF、Directory 带项、模板形状不同形）。红不过就说明断言没咬住旧行为。

- [ ] **Step 3: 重写 `XmpMerger` 的规则（保留 Writer、深度上限、实体防护与 `MalformedXmpException`）**

删掉 `harvestContainerItems`、`installDirectory`、`installCameraDescriptions`、`dropEmptyDescriptions`
和 `Harvested`、`MOTION_PHOTO`/`PRIMARY` 常量（项不再被重建，语义识别没有存在必要），`merge` 换成
下面四步，顺序不许调换：

```kotlin
fun merge(sourcePacket: String?, videoSize: Long): ByteArray {
    val doc = if (sourcePacket.isNullOrBlank()) newDocument() else parse(sourcePacket)
    val root = doc.documentElement
        ?: throw MalformedXmpException("XMP 包没有根元素，无法合并")
    rejectDeepNesting(root)
    if (local(root) != "xmpmeta") {
        throw MalformedXmpException("XMP 包的根元素不是 x:xmpmeta，而是 <${root.tagName}>")
    }
    // §3.1 单根：有则用第一个，没有才新建并追加。绝不另起第二个 rdf:RDF 挂我们的声明。
    val rdf = childElements(root, XmpPacket.RDF_NS, "RDF").firstOrNull()
        ?: newElement(doc, XmpPacket.RDF_NS, "rdf", "RDF")
            .declare("rdf", XmpPacket.RDF_NS).also { root.appendChild(it) }
    // §3.2 宿主 = 带 Directory 的 Description；没有则第一个 Description；再没有才新建（带 rdf:about=""）。
    val descriptions = descendants(rdf).filter { local(it) == "Description" && it.namespaceURI == XmpPacket.RDF_NS }
    val host = descriptions.firstOrNull { hasDirectory(it) }
        ?: descriptions.firstOrNull()
        ?: newElement(doc, XmpPacket.RDF_NS, "rdf", "Description")
            .also { it.setAttributeNS(XmpPacket.RDF_NS, "rdf:about", "") }
            .also { rdf.appendChild(it) }
    // 先剔干净：全树七属性 + 全树 Directory（vivo 的项是 Container:Item 的属性形状，
    // 逐个识别语义既做不到也不需要——产物不声明项）。宿主的旧 Directory 也在这里被摘掉，
    // 随后重新追加空的一份，所以"只有一个 Directory 且 Seq 为空"是构造出来的性质。
    stripCameraAttributes(root)
    descendants(root).forEach { dropDirectories(it) }
    host.declare("GCamera", XmpPacket.CAMERA_NS)
    MOTION_PHOTO_VALUES(videoSize).forEach { (name, value) ->
        host.setAttributeNS(XmpPacket.CAMERA_NS, name, value)
    }
    host.appendChild(emptyDirectory(doc))
    return packet(doc)
}
```

要点：
- `descendants(rdf)` 只用于找 Description：`x:xmpmeta/rdf:RDF/rdf:Description` 是读取方走的形状，
  躲在别处的 Description 不是宿主候选。
- `dropDirectories(parent)` 摘掉该元素名下**直接**的 `Container:Directory` 子元素；对全树每个元素
  各调一次，等价于"任何深度、任何归属"的 Directory 都被摘光（vivo 形状里它是 Description 的直接子元素）。
  项（`rdf:li`）住在 Directory 里面，所以跟着一起消失，不需要单独认语义。
- `emptyDirectory(doc)` = `<Container:Directory><rdf:Seq/></Container:Directory>`，`Container` 前缀
  声明补在 Directory 元素上（`Writer` 的 `usedPrefixes` 已负责补齐，显式 `declare` 更贴近参考语料）。
- `MOTION_PHOTO_VALUES(videoSize)` = 有序七项：`GCamera:MicroVideo="1"`、`MicroVideoVersion="1"`、
  `MicroVideoOffset=videoSize.toString()`、`MicroVideoPresentationTimestampUs="-1"`、
  `MotionPhoto="1"`、`MotionPhotoVersion="1"`、`MotionPhotoPresentationTimestampUs="-1"`。
  `MOTION_PHOTO_ATTRIBUTES`（剔除白名单）与参考语料一致，保持七个本地名不变。
- `newDocument()`：`docBuilder().newDocument()` + `appendChild(x:xmpmeta)`，`x` 前缀声明照 §7.3；
  根上没有 `x:xmptk`（我们不冒充别人的写入方签名）。
- 源包为 null/空白时走 `newDocument()`，**不再有**「直接返回 `XmpPacket.motionPhoto()`」这一支：
  模板与合并共用同一个构造器，形状无法各自漂移。

- [ ] **Step 4: `XmpPacket.motionPhoto()` 改为委托，模板不再自带 Container 项**

```kotlin
/** 无源 XMP 时的包：与合并产物同一个构造器（spec 增补 2026-10-18b §3 末段）。 */
fun motionPhoto(videoSize: Long): ByteArray = XmpMerger.merge(null, videoSize)
```

删掉 `buildList` 那套三组 `rdf:Description` + 两项 `rdf:li` 的模板字符串与 `ITEM_SEMANTIC_PRIMARY`/
`ITEM_SEMANTIC_MOTION_PHOTO`/`ITEM_NS`/`XMPMETA_NS` 中不再被引用的常量（`ITEM_NS` 若 Writer/其他
地方还在用就留着，按实际引用判）。`app1Segment`、`app1SegmentFor`、`requireFitsInSegment`、
`PACKET_BEGIN`/`PACKET_END`、`XAP_IDENTIFIER` 一律不动。
`XmpPacketTest` 里断言旧模板形状（三组 Description、两项 `rdf:li`、`Item:Length`）的用例必须改写为
新形状；`app1SegmentFor` 与 64 KiB 上限的用例不动。

- [ ] **Step 5: 布局测试——EOI 之后只放视频、XMP 写回原位**

`JpegXmpPatcherTest` 新增/改写：
- 源图布局 `SOI + APP0 + APP1(EXIF) + APP1(XMP) + 段 + FFD9 + 442B 副图`，`patch` 之后产物必须是
  `SOI + APP0 + APP1(EXIF) + APP1(新XMP) + 段 + FFD9`，**副图一个字节都不在**；EXIF 仍在新 XMP 之前。
- 源图无 XMP：`SOI + APP0 + 段 + FFD9 + 尾巴` → `SOI + APP1(新XMP) + APP0 + 段 + FFD9`。spec §2
  的字面是「源无 XMP 时插在 SOI 之后」，即新段会**把 APP0 推到后面**。这条要在测试里按实际顺序断言，
  并在 `patch` 的 KDoc 里写明这个后果（vivo 源片本来就没有 APP0，参考语料也没有这种形状，
  不为它加特例）。
- 返回值仍然是「从源图写入 sink 的字节数」= `imageEnd - 旧XMP.length`，**不再含尾巴**；
  所有 `trailerEnd = …` 的调用与断言删除。

`MotionPhotoComposerTest` 改写（这些用例现在钉的是被推翻的旧规则）：
- 带 gain map 尾巴的源 → 必须成功，且 `产物大小 == imageEnd - 旧XMP长度 + app1.size + videoSize`、
  `产物大小 - MicroVideoOffset == 产物自己的 imageEnd`、`imageEnd` 起 12 字节内出现 `ftyp`
  （EOI 与视频之间零字节，用重扫产物得到 `imageEnd` 来断言，不许硬编码偏移）。
- `vendorTrailerJpeg()` / 零填充夹具 → 仍然成功，但尾巴不再出现在产物里。
- 上一轮自己的产物（EOI 后紧跟 mp4）再合成 → 仍然 `ALREADY_MOTION_PHOTO`。
- `Success.totalBytes` 必须等于产物文件的真实字节数（`patched + app1.size + videoSize` 这条算式在
  去掉尾巴后依然成立，`patch` 的返回值已不含尾巴，别重复加）。

`TrailerClassifier`/`TrailerKind` 的 API 与测试**不动**：分类器仍然三态，变的是 composer 对
`OTHER`/`NONE` 的处置——从「把尾巴搬到 XMP 之后、视频之前」改成「丢尾巴」。

- [ ] **Step 6: 实现 `JpegXmpPatcher.patch` 的原位写回**

```kotlin
fun patch(structure: JpegStructure, image: ChannelSource, sink: WritableByteChannel, app1: ByteArray): Long {
    val emit: (ByteBuffer) -> Unit = { sink.writeAll(it) }
    val discard: (ByteBuffer) -> Unit = { }
    val buf = ByteArray(COPY_BUFFER)
    var copied = 0L
    image.open().use { ch ->
        val input = Channels.newInputStream(ch)
        val oldXmp = structure.oldXmp
        if (oldXmp == null) {
            // 源里没有 XMP 段：没有"原位"可回，就按 §7.3 插在 SOI 之后。
            copied += transfer(input, buf, 2L, emit)
            sink.writeAll(ByteBuffer.wrap(app1))
            copied += transfer(input, buf, structure.imageEnd - 2L, emit)
        } else {
            // 旧段之前的段（EXIF、ICC、APP0…）必须留在新 XMP 之前——参考语料就是这个顺序。
            copied += transfer(input, buf, oldXmp.start, emit)         // SOI + [2, 旧段)
            sink.writeAll(ByteBuffer.wrap(app1))
            transfer(input, buf, oldXmp.length, discard)
            copied += transfer(input, buf, structure.imageEnd - oldXmp.endExclusive, emit)
        }
    }
    return copied
}
```

`require(trailerEnd >= imageEnd)` 与那行 trailer 搬运整条删除。KDoc 改：产物布局、返回值语义
（不含尾巴）、以及"新段写回旧段原位"这条为什么存在（相册按 `EXIF 在前` 的形状读，且参考语料如此）。

- [ ] **Step 7: `MotionPhotoComposer` 停止搬尾巴**

`patch(structure, image, sink, app1)` 去掉 `trailerEnd = fileSize` 实参；类头 KDoc 的产物布局一行
改成 spec 增补 2026-10-18b §2 那串，并删掉"中间夹着 gain map 也不改变这个语义"那段——现在不夹了。
校验顺序不变（视频头 → SOI → 段遍历 → 扩展 XMP → 尾巴分类 → XMP 合并），`readAt` 取 trailer head
仍然要（VIDEO 判据依赖它），`TrailerKind.OTHER`/`NONE` 落到"继续，但不搬尾巴"。

- [ ] **Step 8: 全量回归**

Run: `./gradlew :core:test` 然后 `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: `:core` 全绿（条数会比 127 多，因为新增了形状用例）；`:app` 24 绿；`assembleDebug` 无警告。
`BatchRunnerTest` 里若有按旧字节数断言 `totalBytes` 的用例，按新算式改正——**不许**为了让旧断言变绿
而保留尾巴搬运。

- [ ] **Step 9: 变异验证（三条各杀一次，spec 增补 2026-10-18b §4）**

- 把 `patch` 的 EXIF 保序那步改成「先写 app1 再写 `oldXmp.start - 2` 字节」（即恢复旧顺序）→ 必须有
  用例红；
- 把 `dropDirectories` 去掉（保留源 Directory 及其项）→ 必须有用例红；
- 把 `transfer(input, buf, structure.imageEnd - oldXmp.endExclusive, emit)` 改成搬到 `fileSize`
  （恢复搬尾巴）→ 必须有用例红。
三次变异都要在报告里写清命令与红掉的用例名，然后把改动撤回。

- [ ] **Step 10: 提交**

```bash
git add core/src docs
git commit -m "fix(core): match the vivo-recognised motion photo layout and single-root xmp"
```

- [ ] **Step 11: 真机复验（并入 Task 12 的验收清单，本轮唯一成功判据）**

装机后对 15 对 vivo 原片跑一批，取一张产物拉回本机验证：`文件大小 - MicroVideoOffset == 重扫得到的
imageEnd`、EXIF 在 XMP 之前、XMP 单 RDF；然后**在 vivo 相册里看它是不是动态照片**。不识别就回到
本节继续比差异，不许把「测试全绿」当成这一条的替代。


---

## Task 15: 回到桌面端语义——尾巴保留 + XMP 清空重写（取代 Task 13/14 的 XMP 与布局规则）

spec 依据：设计文档最后一段「增补（2026-10-18c，真机单变量对照）：尾巴保留 + XMP 清空重写」§1-§4。
真机对照已经定死判据：A（源 JPEG 整文件 + 尾巴 + 清空重写的包）**被 vivo 相册认**；B（重编码主图、
无尾巴、同一个包）不认；Task 14 产物（截到 EOI、合并保留源 XMP）不认；Task 13 产物（有尾巴、包是
合并出来的两个兄弟 RDF）不认。所以本任务同时撤销 Task 14 的"丢尾巴"和 Task 13 的"合并源包"。

改动只在 `:core`。`RDF_NS` 的修正、`XmpPacket.motionPhoto` 作为唯一包生成入口、新 XMP 写回旧段原位
这三件事**保留**，不要回退。

- [ ] **Step 1: 先写失败的包字面量测试**

`XmpPacketTest` 里加一条：`motionPhoto(3_863_927)` 的字节必须与 spec 2026-10-18c §2 那份包**逐字节相同**
（把那份包原样写成 fixture 常量，两处 `<VIDEO_SIZE>` 用同一个长度替换；换行是 `
`，行首缩进照抄，
`<?xpacket begin>` 里的 BOM 用 `﻿`）。再加一条：`MicroVideoOffset` 与 `Item:Length` 两处都等于
传入的 `videoSize`，且整包小于 64 KiB 上限。

Run: `./gradlew :core:test --tests '*XmpPacketTest*'` → 红（现在的实现产出的是合并形状）。

- [ ] **Step 2: 重写 `XmpPacket.motionPhoto`，删掉合并器**

- `motionPhoto(videoSize)` 直接按 §2 那份模板拼字节（`buildList` 逐行 join 的写法可以留用，
  `﻿` 必须留在普通字符串里，理由见现有注释）。属性顺序、缩进、`rdf:li` 单行写法都要与 fixture 一致。
- 删除 `XmpMerger.kt`、测试侧的 `XmpShape.kt`、`XmpMergerTest.kt`，以及 `Fixtures.kt` 里只被它们用的
  夹具（`vivoSourcePacket()` 若还被 `TrailerClassifierTest`/composer 测试用作"源包"输入就留着，
  按实际引用判）。
- 删除 `FailureReason.MALFORMED_XMP` 与 `MotionPhotoComposer` 里那个 `catch (e: MalformedXmpException)`
  分支（不再有产生它的路径）。`EXTENDED_XMP_UNSUPPORTED` 的守卫留着（包超 64 KiB 仍要报）。
- `XmpPacket` 里不再被引用的常量（`ITEM_SEMANTIC_*`、`XMPMETA_NS`、`PACKET_BEGIN/END` 等）按实际引用删。

- [ ] **Step 3: 恢复尾巴搬运**

`JpegXmpPatcher.patch` 加回 `trailerEnd: Long = structure.imageEnd` 参数，在写完
`源图[旧XMP末, imageEnd)` 之后再搬 `trailerEnd - imageEnd` 字节；返回值 = `imageEnd - 旧XMP.length +
(trailerEnd - imageEnd)`。KDoc 写清为什么尾巴必须留（真机单变量对照：截掉尾巴的产物不被认）。
`MotionPhotoComposer` 调用处传 `trailerEnd = fileSize`，类头 KDoc 换成 2026-10-18c §1 那串布局。

- [ ] **Step 4: 布局与"不掺源声明"的测试**

`MotionPhotoComposerTest` / `JpegXmpPatcherTest` 改到 §4 的判据：
- 带 gain map 尾巴的源 → 成功，且产物里 `imageEnd` 之后那段尾巴**逐字节等于源的那段**，
  尾巴之后紧跟视频字节；
- 产物大小 - `MicroVideoOffset` == 视频起点，该处前 12 字节内出现 `ftyp`；
- EXIF `APP1` 仍在 XMP `APP1` 之前，XMP 落在旧 XMP 原位；
- 源包里有 `hdrgm:Version`、`dc:description`、`GCamera:BoostCorrection` 时，产物包内**一个都不出现**
  （这条是"清空重写"与"合并"的分水岭，必须能红）；
- 上一轮自己的产物再合成 → 仍 `ALREADY_MOTION_PHOTO`；
- `Success.totalBytes` == 产物真实字节数。
`TrailerClassifierTest` 不动。

- [ ] **Step 5: 全量回归 + 提交**

Run: `./gradlew :core:test` 然后 `./gradlew :app:testDebugUnitTest :app:assembleDebug`（不许有警告）。
`BatchRunnerTest` 里按旧字节数断言的用例按新算式改正，不许放宽断言。
测试绿了**立刻提交**（`git add core/src docs && git commit -m
"fix(core): keep the jpeg trailer and rewrite xmp from scratch, like the desktop tool"`），
再做 Step 6——变异脚本只能在提交之后跑。

- [ ] **Step 6: 变异验证（三条，各自必须红，跑完撤回并确认 `git status` 干净）**

- 把尾巴搬运算式去掉（恢复截到 EOI）→ 必须有用例红；
- 把 `Item:Semantic` 从 `Container:Item` 的属性改成 `Item:Semantic` 子元素文本 → 包字面量用例必须红；
- 把源包的 `hdrgm:Version` 掺进产物（恢复合并语义）→ "不掺源声明"那条必须红。

- [ ] **Step 7: 真机复验（控制者执行）**

装这个包，跑一批，取一张产物验 `文件大小 - MicroVideoOffset == 视频起点` 且尾巴在位，
然后 vivo 相册判定。不认就回到 §1 的对照表继续加变量，不许拿"测试全绿"当替代。
