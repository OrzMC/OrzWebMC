package dev.orzmc.webmc

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

@Serializable
internal data class VersionDto(
    val id: String,
    val name: String,
    val protocol: Int,
    val runtime: String,
    val notes: String,
    val playUrl: String,
    val embedUrl: String,
)

fun Application.module(cfg: Config) {
    install(ContentNegotiation) {
        json(Json { prettyPrint = true; ignoreUnknownKeys = true })
    }

    val registry = VersionRegistry(cfg.dataDir)
    val patcher = Patcher(cfg.cacheDir)

    routing {
        get("/") {
            val versions = registry.versions
            val token = call.request.queryParameters["token"].orEmpty()
            val server = call.request.queryParameters["server"].orEmpty()
            val defaultId = registry.defaultVersion(cfg.defaultVersion)?.info?.id
            val options = versions.joinToString("") { v ->
                val selected = if (v.info.id == defaultId) " selected" else ""
                "<option value=\"${esc(v.info.id)}\"$selected>${esc(v.info.name)} (${esc(v.info.id)})</option>"
            }
            val links = versions.joinToString("") { v ->
                val href = "/play?version=${enc(v.info.id)}" +
                    (if (server.isNotBlank()) "&server=${enc(server)}" else "") +
                    (if (token.isNotBlank()) "&token=${enc(token)}" else "")
                "<li><a href=\"${esc(href)}\">${esc(v.info.name)}</a> <code>${esc(v.info.id)}</code></li>"
            }
            val tokenInput = if (token.isNotBlank()) {
                "<input type=\"hidden\" name=\"token\" value=\"${esc(token)}\">"
            } else ""
            val html = """
                <!doctype html>
                <html lang="zh-CN"><head>
                <meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>OrzMC Web 客户端托管</title>
                <style>
                  body{font:15px/1.6 system-ui,sans-serif;max-width:760px;margin:40px auto;padding:0 16px;background:#111;color:#eee}
                  h1{font-size:22px;margin:0 0 6px} p{color:#aaa}
                  form{display:flex;gap:8px;flex-wrap:wrap;margin:20px 0}
                  input,select,button{padding:9px 12px;border-radius:8px;border:1px solid #444;background:#1b1b1b;color:#eee}
                  input{flex:1;min-width:240px} button{background:#6d28d9;border-color:#6d28d9;font-weight:600;cursor:pointer}
                  code{color:#a78bfa} a{color:#a78bfa}
                </style></head><body>
                <h1>OrzMC Web 客户端托管</h1>
                <p>自托管入口：只提供页面/深链/外嵌，不包含客户端本体。把客户端文件放进数据目录后即可使用。</p>
                <form method="get" action="/play">
                  <input name="server" value="${esc(server)}" placeholder="wss://example.com:443（可留空，进客户端菜单）">
                  <select name="version">$options</select>
                  $tokenInput
                  <button type="submit">进入</button>
                </form>
                <h2 style="font-size:16px">已安装版本</h2>
                <ul>$links</ul>
                <p style="font-size:12px;color:#777">API: <a href="/api/versions">/api/versions</a> · <a href="/api/health">/api/health</a> · 外嵌: <code>/embed?version=...&amp;server=...</code></p>
                </body></html>
            """.trimIndent()
            call.htmlHeaders(cfg)
            call.respondText(html, ContentType.Text.Html)
        }

        get("/play") {
            if (!call.tokenOk(cfg)) return@get
            val entry = registry.byId(call.versionParam(cfg, registry))
            if (entry == null) {
                call.respondText("Unknown version", status = HttpStatusCode.NotFound)
                return@get
            }
            val file = patcher.prepare(entry)
            call.htmlHeaders(cfg, "public, max-age=3600")
            call.response.header(HttpHeaders.ContentType, "text/html; charset=utf-8")
            call.respondFile(file.toFile())
        }

        get("/embed") {
            if (!call.tokenOk(cfg)) return@get
            val entry = registry.byId(call.versionParam(cfg, registry))
            if (entry == null) {
                call.respondText("Unknown version", status = HttpStatusCode.NotFound)
                return@get
            }
            val server = call.request.queryParameters["server"]
                ?: call.request.queryParameters["joinServer"].orEmpty()
            val token = call.request.queryParameters["token"].orEmpty()
            val q = buildString {
                append("version=").append(enc(entry.info.id))
                if (server.isNotBlank()) append("&server=").append(enc(server))
                if (token.isNotBlank()) append("&token=").append(enc(token))
                append("&embed=1")
            }
            val html = """
                <!doctype html><html lang="zh-CN"><head>
                <meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>${esc(entry.info.name)} · Embed</title>
                <style>html,body{height:100%;margin:0;background:#000}iframe{display:block;width:100%;height:100%;border:0}</style>
                </head><body>
                <iframe id="game" src="/play?${esc(q)}" allow="fullscreen; gamepad; pointer-lock; clipboard-write; autoplay" allowfullscreen></iframe>
                <script>
                (function(){
                  var frame=document.getElementById('game');
                  function setParam(k,v){var u=new URL(frame.src,location.href);u.searchParams.set(k,v);frame.src=u.toString();}
                  window.WebMC={
                    join:function(server){setParam('server',server);},
                    setVersion:function(v){setParam('version',v);},
                    reload:function(){frame.src=frame.src;}
                  };
                  frame.addEventListener('load',function(){
                    try{parent.postMessage({type:'webmc:ready',version:${js(entry.info.id)},server:${js(server)}},'*');}catch(e){}
                  });
                })();
                </script></body></html>
            """.trimIndent()
            call.htmlHeaders(cfg)
            call.respondText(html, ContentType.Text.Html)
        }

        get("/api/versions") {
            call.response.header(HttpHeaders.AccessControlAllowOrigin, cfg.corsOrigin)
            val base = cfg.publicBaseUrl.orEmpty()
            call.respond(registry.versions.map { v ->
                VersionDto(
                    id = v.info.id,
                    name = v.info.name,
                    protocol = v.info.protocol,
                    runtime = v.info.runtime,
                    notes = v.info.notes,
                    playUrl = "$base/play?version=${enc(v.info.id)}",
                    embedUrl = "$base/embed?version=${enc(v.info.id)}",
                )
            })
        }

        get("/api/health") {
            call.respondText(
                """{"status":"ok","versions":${registry.versions.size}}""",
                ContentType.Application.Json,
            )
        }

        get("/client/{version}/{path...}") {
            if (!call.tokenOk(cfg)) return@get
            val entry = registry.byId(call.parameters["version"])
            if (entry == null) {
                call.respondText("Unknown version", status = HttpStatusCode.NotFound)
                return@get
            }
            val tail = call.parameters.getAll("path")?.joinToString("/")
                ?: call.parameters["path"]
                ?: ""
            if (tail.isBlank()) {
                call.respondText("Not found", status = HttpStatusCode.NotFound)
                return@get
            }
            val relative = Path.of(tail).normalize()
            if (relative.isAbsolute || relative.startsWith("..")) {
                call.respondText("Not found", status = HttpStatusCode.NotFound)
                return@get
            }
            val file = entry.dir.resolve(relative).normalize()
            if (!file.startsWith(entry.dir.normalize()) || !Files.isRegularFile(file)) {
                call.respondText("Not found", status = HttpStatusCode.NotFound)
                return@get
            }
            val ext = file.fileName.toString().substringAfterLast('.', "").lowercase()
            if (ext !in ALLOWED_EXT) {
                call.respondText("Not found", status = HttpStatusCode.NotFound)
                return@get
            }
            call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
            call.respondFile(file.toFile())
        }

        get("/favicon.ico") { call.respond(HttpStatusCode.NoContent) }
    }
}

private fun ApplicationCall.versionParam(cfg: Config, registry: VersionRegistry): String? =
    request.queryParameters["version"]?.takeIf { it.isNotBlank() }
        ?: cfg.defaultVersion
        ?: registry.versions.lastOrNull()?.info?.id

private fun ApplicationCall.htmlHeaders(cfg: Config, cache: String? = null) {
    response.header("X-Content-Type-Options", "nosniff")
    response.header("Referrer-Policy", "no-referrer")
    response.header("Content-Security-Policy", "frame-ancestors ${cfg.frameAncestors}")
    if (cache != null) response.header(HttpHeaders.CacheControl, cache)
}

private suspend fun ApplicationCall.tokenOk(cfg: Config): Boolean {
    val expected = cfg.accessToken ?: return true
    val provided = request.queryParameters["token"]
        ?: request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
    val ok = provided != null && MessageDigest.isEqual(
        provided.toByteArray(StandardCharsets.UTF_8),
        expected.toByteArray(StandardCharsets.UTF_8),
    )
    if (!ok) respondText("Missing or invalid token", status = HttpStatusCode.Unauthorized)
    return ok
}

private fun esc(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

private fun js(s: String): String = Json.encodeToString(kotlinx.serialization.serializer<String>(), s)

private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

private val ALLOWED_EXT = setOf(
    "html", "htm", "js", "mjs", "wasm", "epk", "json", "css", "png", "jpg", "jpeg", "gif", "svg",
    "webp", "ico", "woff", "woff2", "ttf", "otf", "ogg", "mp3", "wav", "dat", "bin", "txt",
)
