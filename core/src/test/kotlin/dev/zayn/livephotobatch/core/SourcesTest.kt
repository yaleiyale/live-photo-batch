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
