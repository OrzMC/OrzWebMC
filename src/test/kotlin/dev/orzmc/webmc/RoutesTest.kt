package dev.orzmc.webmc

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class RoutesTest {

    private fun makeData(): Path {
        val root = Files.createTempDirectory("webmc-routes")
        val dir = Files.createDirectories(root.resolve("26.3"))
        Files.writeString(dir.resolve("client.html"), """<script>window.eaglercraftXOpts = { container: "x" };</script>""")
        Files.writeString(dir.resolve("meta.json"), """{"id":"26.3","name":"Test Client","protocol":777,"runtime":"wasm-gc"}""")
        Files.createDirectories(dir.resolve("assets"))
        Files.writeString(dir.resolve("assets/test.js"), "console.log('ok');")
        return root
    }

    private fun cfg(data: Path, token: String? = null, frameAncestors: String = "*") = Config(
        host = "127.0.0.1",
        port = 0,
        dataDir = data,
        cacheDir = data.resolve(".cache"),
        defaultVersion = null,
        frameAncestors = frameAncestors,
        accessToken = token,
        corsOrigin = "*",
        publicBaseUrl = null,
    )

    @Test
    fun listsVersionsAndServesPatchedPlay() = testApplication {
        val data = makeData()
        application { module(cfg(data)) }

        val api = client.get("/api/versions")
        assertEquals(HttpStatusCode.OK, api.status)
        assertContains(api.bodyAsText(), "26.3")
        assertContains(api.bodyAsText(), "/play?version=26.3")

        val play = client.get("/play?version=26.3")
        assertEquals(HttpStatusCode.OK, play.status)
        assertContains(play.bodyAsText(), "<iframe")
        assertContains(play.bodyAsText(), "/play/raw?version=26.3")
        assertContains(play.bodyAsText(), "全屏")

        val raw = client.get("/play/raw?version=26.3&server=wss%3A%2F%2Fexample.com")
        assertEquals(HttpStatusCode.OK, raw.status)
        assertContains(raw.bodyAsText(), "joinServer")
        assertContains(raw.headers["Content-Security-Policy"].orEmpty(), "frame-ancestors *")

        val embed = client.get("/embed?version=26.3&server=wss%3A%2F%2Fexample.com")
        assertEquals(HttpStatusCode.OK, embed.status)
        assertContains(embed.bodyAsText(), "<iframe")
        assertContains(embed.bodyAsText(), "/play/raw?")

        val root = client.get("/")
        assertEquals(HttpStatusCode.OK, root.status)
        assertContains(root.bodyAsText(), "Test Client")

        val health = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, health.status)
        assertContains(health.bodyAsText(), "\"versions\"")

        val asset = client.get("/client/26.3/assets/test.js")
        assertEquals(HttpStatusCode.OK, asset.status)
        assertContains(asset.bodyAsText(), "console.log")
    }

    @Test
    fun rawClientAllowsSameOriginFramingWhenAncestorsConfigured() = testApplication {
        val data = makeData()
        application { module(cfg(data, frameAncestors = "https://example.com")) }

        val raw = client.get("/play/raw?version=26.3")
        assertEquals(HttpStatusCode.OK, raw.status)
        assertContains(raw.headers["Content-Security-Policy"].orEmpty(), "'self' https://example.com")
    }

    @Test
    fun rejectsPathTraversal() = testApplication {
        val data = makeData()
        application { module(cfg(data)) }

        val resp = client.get("/client/26.3/../../etc/passwd")
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun tokenProtectsClientRoutes() = testApplication {
        val data = makeData()
        application { module(cfg(data, token = "secret")) }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/play?version=26.3").status)
        assertEquals(HttpStatusCode.OK, client.get("/play?version=26.3&token=secret").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/play/raw?version=26.3").status)
        assertEquals(HttpStatusCode.OK, client.get("/play/raw?version=26.3&token=secret").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/versions").status)
    }
}
