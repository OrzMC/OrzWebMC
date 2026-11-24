package dev.orzmc.webmc

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertEquals

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
    fun injectsKeyboardShimAndJoinServerTogether() {
        val root = Files.createTempDirectory("webmc-patch-both")
        val dir = Files.createDirectories(root.resolve("1.8.8"))
        val client = dir.resolve("client.html")
        Files.writeString(
            client,
            """<html><head><meta charset="utf-8"></head><body>""" +
                """<script>window.eaglercraftXOpts = { container: "x" };</script></body></html>""",
        )

        val entry = VersionEntry(VersionInfo("1.8.8", "Test"), dir, client, InjectMode.JOIN_SERVER)
        val text = Files.readString(Patcher(root.resolve(".cache")).prepare(entry))

        // 兜底脚本在 <head> 里，joinServer 在脚本锚点后；两处都只注入一次，原有内容不丢。
        assertEquals(1, text.split("OrzWebMC keyboard shim").size - 1)
        assertEquals(1, text.split("p.get('joinServer')").size - 1)
        assertContains(text, """container: "x"""")
        assertFalse(text.contains(",,"))
        assertContains(text, "<meta charset=\"utf-8\">")
    }

    @Test
    fun keyboardShimSurvivesInjectNone() {
        val root = Files.createTempDirectory("webmc-patch-none-shim")
        val dir = Files.createDirectories(root.resolve("x"))
        val client = dir.resolve("client.html")
        Files.writeString(client, """<html><head></head><body>hi</body></html>""")

        val entry = VersionEntry(VersionInfo("x", "x"), dir, client, InjectMode.NONE)
        val text = Files.readString(Patcher(root.resolve(".cache")).prepare(entry))

        assertContains(text, "OrzWebMC keyboard shim")
        assertFalse(text.contains("joinServer"))
    }

    @Test
    fun keyboardShimCanBeDisabled() {
        val root = Files.createTempDirectory("webmc-patch-shim-off")
        val dir = Files.createDirectories(root.resolve("x"))
        val client = dir.resolve("client.html")
        Files.writeString(client, """<html><head></head><body>hi</body></html>""")

        val entry = VersionEntry(
            VersionInfo("x", "x"),
            dir,
            client,
            InjectMode.NONE,
            keyboardShim = false,
        )
        assertEquals(client, Patcher(root.resolve(".cache")).prepare(entry))
    }

    @Test
    fun noneModeSkipsJoinServer() {
        val root = Files.createTempDirectory("webmc-patch-none")
        val dir = Files.createDirectories(root.resolve("x"))
        val client = dir.resolve("client.html")
        Files.writeString(client, """<script>window.eaglercraftXOpts = {};</script>""")

        val entry = VersionEntry(VersionInfo("x", "x"), dir, client, InjectMode.NONE, keyboardShim = false)
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
