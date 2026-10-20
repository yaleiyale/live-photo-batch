package dev.zayn.vlive.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * 真机二分（2026-10-20）钉下来的规则：vivo 相册认不认这条 live photo，取决于视频末尾那个
 * `uuid/vivoMediaExtInfo` 盒里 `com.android.camera.livephoto` 的值**是否以 motionphoto 开头**。
 * 相机自己写的是数字开头的 id，所以原样追加的产物在这台 V2419A 上一律不认；加上前缀就认，
 * 且盒尺寸、JSON 里其余的键、`version` 数字、尾记录内容都不影响（Y1/Y2/Y3 认、Y4 不认）。
 *
 * 另一条同等重要：**看不懂就一个字都不改**。没有这个盒、JSON 解析不出、长度字段自相矛盾、
 * 盒子没走到文件末尾——一律 null，调用方原样搬运。我们只改自己看得懂的东西，也不伪造结构。
 */
class VivoLivePhotoTailTest {

    private fun plan(bytes: ByteArray) = VivoLivePhotoTail.plan(ByteArraySource(bytes))

    /** JUnit 的 assertNotNull 返回 void，这里要的是"必须有改写计划"且能接着取值。 */
    private fun requirePlan(bytes: ByteArray): VivoLivePhotoTail.Rewrite =
        plan(bytes) ?: fail("末盒看得懂却没给出计划")

    private data class Parsed(
        val json: String,
        val declaredJsonLen: Long,
        val record: ByteArray,
        val boxSize: Long,
    )

    /** 拆一个末盒：[u32 size]['uuid'][16 B 伪 UUID]['vivo'][JSON][u32BE=JSON 字节数][尾记录]。 */
    private fun parse(box: ByteArray): Parsed {
        assertEquals("uuid", box.copyOfRange(4, 8).toString(Charsets.ISO_8859_1), "tag 不是 uuid")
        assertEquals("vivoMediaExtInfo", box.copyOfRange(8, 24).toString(Charsets.ISO_8859_1))
        assertEquals("vivo", box.copyOfRange(24, 28).toString(Charsets.ISO_8859_1))
        val jsonEnd = jsonEndOf(box) ?: error("夹具 JSON 不配对")
        val json = box.copyOfRange(28, jsonEnd).toString(Charsets.UTF_8)
        return Parsed(
            json = json,
            declaredJsonLen = be32(box, jsonEnd),
            record = box.copyOfRange(jsonEnd + 4, box.size),
            boxSize = be32(box, 0),
        )
    }

    /** 从 'vivo' 魔数之后第一个 '{' 数到配对 '}'，返回 JSON 末后的第一个字节下标。 */
    private fun jsonEndOf(box: ByteArray): Int? {
        var depth = 0
        for (i in 28 until box.size) {
            when (box[i]) {
                '{'.code.toByte() -> depth++
                '}'.code.toByte() -> if (--depth == 0) return i + 1
            }
        }
        return null
    }

    private fun be32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private fun put32(b: ByteArray, at: Int, value: Long) {
        b[at] = (value shr 24).toByte()
        b[at + 1] = (value shr 16).toByte()
        b[at + 2] = (value shr 8).toByte()
        b[at + 3] = value.toByte()
    }

    @Test fun `数字开头的真实编号被加上 motionphoto 前缀`() {
        val video = Fixtures.vivoMp4()
        val oldBox = Fixtures.vivoTailBox()
        val rewrite = requirePlan(video)
        assertEquals((video.size - oldBox.size).toLong(), rewrite.verbatimBytes,
            "verbatim 段应当正好停在末盒之前")
        val parsed = parse(rewrite.replacement)
        assertTrue("\"com.android.camera.livephoto\":\"motionphoto${Fixtures.REAL_LIVEPHOTO}\"" in parsed.json,
            "改写后的值不对：${parsed.json}")
        assertEquals(parsed.json.toByteArray(Charsets.UTF_8).size.toLong(), parsed.declaredJsonLen,
            "JSON 长度字段没跟着新 JSON 变")
        assertEquals(rewrite.replacement.size.toLong(), parsed.boxSize, "盒 size 字段与实际字节数不符")
        assertEquals(rewrite.totalBytes, rewrite.verbatimBytes + rewrite.replacement.size)
    }

    @Test fun `伪 UUID 与尾记录逐字节不变`() {
        val rewrite = requirePlan(Fixtures.vivoMp4())
        val before = parse(Fixtures.vivoTailBox())
        val after = parse(rewrite.replacement)
        assertArrayEquals(before.record, after.record, "JSON 之后那 58 B 尾记录被改动了")
        assertEquals(58, after.record.size)
        assertEquals(0x1B.toByte(), after.record[47], "填充段起点变了")
        assertArrayEquals(
            Fixtures.vivoTailBox().copyOfRange(8, 28), rewrite.replacement.copyOfRange(8, 28),
            "伪 UUID 与 'vivo' 魔数被改动了",
        )
    }

    @Test fun `拼接后的视频前半与源逐字节相同`() {
        val video = Fixtures.vivoMp4()
        val rewrite = requirePlan(video)
        val out = video.copyOfRange(0, rewrite.verbatimBytes.toInt()) + rewrite.replacement
        assertArrayEquals(
            video.copyOfRange(0, rewrite.verbatimBytes.toInt()),
            out.copyOfRange(0, rewrite.verbatimBytes.toInt()),
        )
        assertEquals(rewrite.totalBytes.toInt(), out.size)
    }

    @Test fun `已经合规的末盒不动`() {
        assertNull(plan(Fixtures.vivoMp4("motionphoto00000000000000000")), "值已带 motionphoto 前缀，不该再改")
        assertNull(plan(Fixtures.vivoMp4("motionphoto")), "只有前缀本身也算合规")
    }

    @Test fun `没有 vivoMediaExtInfo 盒的视频不伪造`() {
        assertNull(plan(Fixtures.mp4(512)), "普通 mp4 没有那个盒，原样搬运")
        assertNull(plan(Fixtures.zeroFilledJpeg()), "连盒子都不是的东西更不该动")
    }

    @Test fun `末盒没走到文件末尾就不动`() {
        // 盒 size 字段说它到 EOF，后面却还拖着 16 字节垃圾 -> 自相矛盾，不碰。
        assertNull(plan(Fixtures.vivoMp4() + ByteArray(16)), "末盒 size 与实际位置不符")
    }

    @Test fun `JSON 长度字段自相矛盾就不动`() {
        val video = Fixtures.vivoMp4()
        val box = Fixtures.vivoTailBox()
        val broken = video.copyOf()
        put32(broken, broken.size - box.size + jsonEndOf(box)!!, 0x7FFFFFFFL)
        assertNull(plan(broken), "长度字段与 JSON 实际字节数不符时宁可什么都不做")
    }

    @Test fun `标记落在读窗口之外就不动`() {
        val huge = Fixtures.mp4(64) + Fixtures.vivoTailBox(livephoto = "1" + "x".repeat(100 * 1024))
        assertNull(plan(huge), "末盒超出读窗口 -> 不动")
    }

    @Test fun `JSON 里没有 livephoto 键就不动`() {
        val json = Fixtures.ascii("{\"com.android.camera.version\":2104}")
        val body = Fixtures.ascii("vivoMediaExtInfo") + Fixtures.ascii("vivo") + json +
            Fixtures.uint32(json.size.toLong()) + Fixtures.ascii("cameralbum!")
        val box = Fixtures.uint32((8 + body.size).toLong()) + Fixtures.ascii("uuid") + body
        assertNull(plan(Fixtures.mp4(64) + box), "不该凭空造一个 livephoto 键")
    }
}
