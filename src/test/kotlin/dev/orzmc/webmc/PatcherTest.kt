package dev.orzmc.webmc

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PatcherTest {
    @Test
    fun injectsJoinServerFromQuery() {
        val root = Files.createTempDirectory("webmc-patch")
        val dir = Files.createDirectories(root.resolve("26.3"))
        val client = dir.resolve("client.html")
        Files.writeString(client, """<script>window.eaglercraftXOpts = { container: "x" };</script>""")

        val entry = VersionEntry(VersionInfo("26.3", "Test"), dir, client, InjectMode.AUTO)
        val patched = Patcher(root.resolve(".cache")).prepare(entry)
        val text = Files.readString(patched)

        assertContains(text, "joinServer")
        assertContains(text, "container")
        assertContains(text, "URLSearchParams")
        assertFalse(text.contains(",,"))
    }

    @Test
    fun noneModeServesOriginal() {
        val root = Files.createTempDirectory("webmc-patch-none")
        val dir = Files.createDirectories(root.resolve("x"))
        val client = dir.resolve("client.html")
        Files.writeString(client, """<script>window.eaglercraftXOpts = {};</script>""")

        val entry = VersionEntry(VersionInfo("x", "x"), dir, client, InjectMode.NONE)
        val out = Patcher(root.resolve(".cache")).prepare(entry)
        assertEquals(client, out)
    }

    @Test
    fun missingAnchorIsServedAsIs() {
        val root = Files.createTempDirectory("webmc-patch-missing")
        val dir = Files.createDirectories(root.resolve("y"))
        val client = dir.resolve("client.html")
        Files.writeString(client, "<html>not an eagler client</html>")

        val entry = VersionEntry(VersionInfo("y", "y"), dir, client, InjectMode.AUTO)
        val out = Patcher(root.resolve(".cache")).prepare(entry)
        assertEquals(client, out)
    }
}
