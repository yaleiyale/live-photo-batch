package dev.zayn.livephotobatch.app.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
        File(root, "a/file.txt").apply { parentFile?.mkdirs(); writeText("x") }
        val items = DirBrowser(root.path).subdirs("")
        assertEquals(listOf("a", "b"), items.map { it.name })
        // Path assembly, asserted with the same java.io.File API the browser uses (never a
        // hand-written "/" join), so it holds on any host and still catches a broken
        // absolutePath/parent construction.
        assertEquals(listOf(File(root, "a").absolutePath, File(root, "b").absolutePath), items.map { it.absolutePath })
        assertEquals(listOf(root.absolutePath, root.absolutePath), items.map { it.parent })
        root.deleteRecursively()
    }

    @Test fun `skips files and dot prefixed directories`() {
        val root = freshRoot()
        File(root, "visible").mkdirs()
        File(root, ".hidden").mkdirs()
        File(root, "loose.txt").apply { parentFile?.mkdirs(); writeText("x") }
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
        val file = File(root, "notadir.txt").apply { parentFile?.mkdirs(); writeText("x") }
        assertTrue(DirBrowser(root.path).subdirs(file.absolutePath).isEmpty())
        root.deleteRecursively()
    }

    /**
     * The absolute-path branch is `relative.startsWith("/")` — a POSIX-only rule that
     * deliberately differs from `FileStorage.resolve()` (see DirBrowser's KDoc). On a Windows
     * host `java.io.File` hands back `C:\...`, that branch can never fire, and a hand-written
     * `/...` expectation would be asserting a rule the class does not claim on that OS. So the
     * host split is stated honestly with `assumeTrue`, and every expectation is built with the
     * same `File` API under test instead of a literal `/` join.
     */
    @Test fun `absolute path is used as-is and ignores the root`() {
        assumeTrue("absolute-path browsing rule is POSIX-only (startsWith(\"/\"))", File.separator == "/")
        val elsewhere = freshRoot()
        File(elsewhere, "only").mkdirs()
        val items = DirBrowser(File("/some/other/root").absolutePath).subdirs(elsewhere.absolutePath)
        assertEquals(listOf("only"), items.map { it.name })
        assertEquals(listOf(elsewhere.absolutePath), items.map { it.parent })
        assertEquals(listOf(File(elsewhere, "only").absolutePath), items.map { it.absolutePath })
        elsewhere.deleteRecursively()
    }

    @Test fun `presets never throws and only returns existing directories`() {
        // On the JVM "/storage/emulated/0" does not exist, so presets() filters everything out;
        // the invariant is "never throw, and whatever it returns actually exists as a directory".
        for (item in DirBrowser("/storage/emulated/0").presets()) {
            assertTrue(item.absolutePath, File(item.absolutePath).isDirectory)
        }
    }

    // ---- mergedRootEntries: pure display merge, no filesystem reads involved ----

    private fun item(base: String, vararg parts: String, label: String): DirItem {
        var f = File(base)
        for (p in parts) f = File(f, p)
        return DirItem(f.absolutePath, label, f.parentFile?.absolutePath ?: "")
    }

    @Test fun `root level merges preset aliases into the real children, one single list`() {
        val root = freshRoot()
        val here = root.absolutePath
        val presets = listOf(
            item(here, label = "内部存储"),
            item(here, "DCIM", label = "DCIM"),
            item(here, "Pictures", label = "图片"),
        )
        val children = listOf(
            item(here, "DCIM", label = "DCIM"),
            item(here, "Documents", label = "Documents"),
            item(here, "Pictures", label = "Pictures"),
        )
        val merged = mergedRootEntries(presets, children, here)
        // Aliased, deduplicated (Pictures must not appear twice), ordered by the displayed name —
        // ASCII code units before CJK, so the curated entries are not floated into their own group.
        assertEquals(listOf("DCIM", "Documents", "图片"), merged.map { it.name })
        assertEquals(3, merged.map { it.absolutePath }.distinct().size)
        root.deleteRecursively()
    }

    @Test fun `a directory never lists itself`() {
        val root = freshRoot()
        val here = root.absolutePath
        File(root, "DCIM").mkdirs()
        val merged = mergedRootEntries(
            listOf(item(here, label = "内部存储"), item(here, "DCIM", label = "DCIM")),
            listOf(item(here, "DCIM", label = "DCIM")),
            "$here/",  // trailing separator: canonical() has to absorb it, not the caller
        )
        assertEquals(listOf("DCIM"), merged.map { it.name })
        assertTrue(merged.none { File(it.absolutePath).absoluteFile == File(here).absoluteFile })
        root.deleteRecursively()
    }

    @Test fun `children the presets never mention stay reachable`() {
        val root = freshRoot()
        val here = root.absolutePath
        val merged = mergedRootEntries(
            listOf(item(here, "DCIM", label = "DCIM")),
            listOf(item(here, "DCIM", label = "DCIM"), item(here, "Music", label = "Music")),
            here,
        )
        assertEquals(listOf("DCIM", "Music"), merged.map { it.name })
        root.deleteRecursively()
    }

    @Test fun `a nested preset is not added as a shortcut`() {
        val root = freshRoot()
        val here = root.absolutePath
        // 相机 = DCIM/Camera is a grandchild. It used to be pulled up to the root level as a
        // shortcut and looked exactly like a real folder that no file manager shows, so the whole
        // shortcut mechanism was removed: the root list is the real children and nothing else.
        val merged = mergedRootEntries(
            listOf(item(here, "DCIM", label = "DCIM"), item(here, "DCIM", "Camera", label = "相机")),
            listOf(item(here, "DCIM", label = "DCIM")),
            here,
        )
        assertEquals(listOf("DCIM"), merged.map { it.name })
        assertEquals(1, merged.size)
        root.deleteRecursively()
    }

    @Test fun `presets from another root are never pulled in`() {
        val root = freshRoot()
        val here = root.absolutePath
        File(root, "Only").mkdirs()
        val children = listOf(item(here, "Only", label = "Only"))
        // presets() always probes /storage/emulated/0. With an injected temp root those strings
        // are neither children of `here` nor descendants of it, so nothing may be aliased or
        // shortcut in — the list must come out as the real children, untouched.
        val foreign = listOf(
            DirItem(File("/storage/emulated/0").absolutePath, "内部存储", ""),
            DirItem(File("/storage/emulated/0/DCIM/Camera").absolutePath, "相机", ""),
        )
        assertEquals(children, mergedRootEntries(foreign, children, here))
        root.deleteRecursively()
    }

    @Test fun `merging is deterministic across repeated calls`() {
        val root = freshRoot()
        val here = root.absolutePath
        val presets = listOf(item(here, label = "内部存储"), item(here, "Pictures", label = "图片"))
        val children = listOf(item(here, "Beta", label = "Beta"), item(here, "Pictures", label = "Pictures"))
        val once = mergedRootEntries(presets, children, here)
        val twice = mergedRootEntries(presets.reversed(), children, "$here/")
        // Aliased name takes part in the ordering: "Beta" < "图片" by code unit, and an unlisted
        // child keeps its own name.
        assertEquals(listOf("Beta", "图片"), once.map { it.name })
        assertEquals(once, twice)
        root.deleteRecursively()
    }

    // ---- displayPath: the sheet's subtitle form. Literal 内部存储 on purpose — it is what the
    // user reads, so a renamed constant must not silently change this expectation. ----

    @Test fun `subtitle labels the storage root instead of printing its raw path`() {
        val root = freshRoot()
        assertEquals("内部存储", displayPath(root.absolutePath, root.absolutePath))
        // Trailing separators and mixed forms are the same directory; the label must not depend
        // on which one the caller happened to hold.
        assertEquals("内部存储", displayPath("${root.absolutePath}/", root.absolutePath))
        assertEquals("内部存储", displayPath(root.absolutePath, "${root.absolutePath}/"))
        root.deleteRecursively()
    }

    @Test fun `subtitle keeps the levels below the root under the label`() {
        val root = freshRoot()
        val nested = File(File(root, "DCIM"), "Camera")
        assertEquals("内部存储/DCIM", displayPath(File(root, "DCIM").absolutePath, root.absolutePath))
        assertEquals("内部存储/DCIM/Camera", displayPath(nested.absolutePath, root.absolutePath))
        root.deleteRecursively()
    }

    @Test fun `a path outside the root is not relabelled`() {
        // The picker can still show such a path (it just cannot be picked), so pretending it is
        // under 内部存储 would be a lie, not a simplification.
        val root = freshRoot()
        val elsewhere = freshRoot()
        File(elsewhere, "DCIM").mkdirs()
        val shown = displayPath(File(elsewhere, "DCIM").absolutePath, root.absolutePath)
        assertEquals(File(elsewhere, "DCIM").absoluteFile.path, shown)
        assertFalse(shown.startsWith("内部存储"))
        root.deleteRecursively()
        elsewhere.deleteRecursively()
    }
}
