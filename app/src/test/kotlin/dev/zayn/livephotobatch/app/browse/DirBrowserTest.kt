package dev.zayn.livephotobatch.app.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Local JVM test (`app/src/test`) driving a temp directory on the real filesystem.
 * The production root is `"/storage/emulated/0"` (unnormalized, no trailing slash) — the same
 * string handed to `FileStorage` — but the browser is root-agnostic on the JVM, so tests inject
 * a throwaway root here.
 */
class DirBrowserTest {
    private fun freshRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "lpb-test-${System.nanoTime()}")

    @Test fun `lists only readable subdirectories and sorts them`() {
        val root = freshRoot()
        File(root, "b").mkdirs()
        File(root, "a").mkdirs()
        File(root, "a/file.txt").apply { parentFile.mkdirs(); writeText("x") }
        val names = DirBrowser(root.path).subdirs("").map { it.name }
        assertEquals(listOf("a", "b"), names)
        root.deleteRecursively()
    }

    @Test fun `skips files and dot prefixed directories`() {
        val root = freshRoot()
        File(root, "visible").mkdirs()
        File(root, ".hidden").mkdirs()
        File(root, "loose.txt").apply { parentFile.mkdirs(); writeText("x") }
        val names = DirBrowser(root.path).subdirs("").map { it.name }
        assertEquals(listOf("visible"), names)
        root.deleteRecursively()
    }

    @Test fun `orders case insensitively and deterministically`() {
        val root = freshRoot()
        File(root, "Beta").mkdirs()
        File(root, "apple").mkdirs()
        File(root, "Cherry").mkdirs()
        val browser = DirBrowser(root.path)
        val once = browser.subdirs("").map { it.name }
        val twice = browser.subdirs("").map { it.name }
        assertEquals(listOf("apple", "Beta", "Cherry"), once)
        assertEquals(once, twice)
        root.deleteRecursively()
    }

    @Test fun `missing directory yields empty list`() {
        assertTrue(DirBrowser("/nonexistent-zzz").subdirs("nope").isEmpty())
    }

    // listFiles() on a plain file returns null (not a throw), so pointing at a file is safe.
    @Test fun `path that is not a directory yields empty list without throwing`() {
        val root = freshRoot()
        val file = File(root, "notadir.txt").apply { parentFile.mkdirs(); writeText("x") }
        assertTrue(DirBrowser(root.path).subdirs(file.absolutePath).isEmpty())
        root.deleteRecursively()
    }

    @Test fun `absolute path is used as-is and ignores the root`() {
        val elsewhere = freshRoot()
        File(elsewhere, "only").mkdirs()
        val names = DirBrowser("/some/other/root").subdirs(elsewhere.absolutePath).map { it.name }
        assertEquals(listOf("only"), names)
        elsewhere.deleteRecursively()
    }

    @Test fun `presets never throws and only returns existing directories`() {
        // On the JVM "/storage/emulated/0" does not exist, so presets() filters everything out;
        // the invariant is "never throw, and whatever it returns actually exists as a directory".
        for (item in DirBrowser("/storage/emulated/0").presets()) {
            assertTrue(item.absolutePath, File(item.absolutePath).isDirectory)
        }
    }
}
