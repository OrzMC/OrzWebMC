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
            val tokenInput = if (token.isNotBlank()) {
                "<input type=\"hidden\" name=\"token\" value=\"${esc(token)}\">"
            } else ""
            val options = versions.joinToString("") { v ->
                val selected = if (v.info.id == defaultId) " selected" else ""
                "<option value=\"${esc(v.info.id)}\"$selected>${esc(displayName(v.info))}</option>"
            }
            val cards = if (versions.isEmpty()) {
                "<p class=\"empty\">数据目录里还没有客户端版本。</p>"
            } else {
                versions.joinToString("") { v ->
                    val href = "/play?version=${enc(v.info.id)}" +
                        (if (server.isNotBlank()) "&server=${enc(server)}" else "") +
                        (if (token.isNotBlank()) "&token=${enc(token)}" else "")
                    val meta = listOfNotNull(
                        v.info.protocol.takeIf { it > 0 }?.let { "协议 $it" },
                        v.info.runtime.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    """
                    <a class="version-card" href="${esc(href)}">
                      <span class="version-name">${esc(displayName(v.info))}</span>
                      <span class="version-meta">${esc(meta.ifBlank { v.info.id })}</span>
                      <span class="version-go">打开 <span aria-hidden="true">↗</span></span>
                    </a>
                    """.trimIndent()
                }
            }
            val html = """
                <!doctype html>
                <html lang="zh-CN"><head>
                <meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <meta name="color-scheme" content="dark">
                <title>OrzMC · Web 客户端</title>
                <style>
                  :root{color-scheme:dark;--bg:#090a0e;--panel:#11131a;--panel-2:#171a24;--line:#272b38;--text:#f4f4f5;--muted:#9ca3af;--accent:#8b5cf6;--accent-2:#6d28d9;--accent-soft:#c4b5fd}
                  *{box-sizing:border-box}
                  html,body{margin:0;min-height:100%;background:var(--bg);color:var(--text)}
                  body{font:15px/1.65 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Noto Sans SC",sans-serif;background:radial-gradient(900px 500px at 20% -10%,rgba(109,40,217,.22),transparent 65%),radial-gradient(700px 400px at 100% 0,rgba(59,130,246,.10),transparent 60%),var(--bg)}
                  a{color:var(--accent-soft);text-decoration:none}
                  .landing{width:min(920px,100%);margin:0 auto;padding:clamp(32px,7vw,78px) 20px 48px}
                  .eyebrow{display:inline-flex;align-items:center;gap:8px;color:var(--accent-soft);font-size:12px;font-weight:700;letter-spacing:.14em;text-transform:uppercase}
                  .eyebrow:before{content:"";width:7px;height:7px;border-radius:50%;background:var(--accent);box-shadow:0 0 18px var(--accent)}
                  h1{font-size:clamp(30px,5vw,48px);line-height:1.08;letter-spacing:-.045em;margin:14px 0 12px}
                  .lead{max-width:620px;margin:0 0 28px;color:var(--muted);font-size:16px}
                  .panel{background:linear-gradient(150deg,rgba(255,255,255,.055),rgba(255,255,255,.018));border:1px solid var(--line);border-radius:20px;padding:16px;box-shadow:0 24px 70px rgba(0,0,0,.28)}
                  .entry{display:flex;gap:10px;align-items:end;flex-wrap:wrap}
                  .field{display:flex;flex:1 1 220px;flex-direction:column;gap:7px;min-width:0}
                  .field.version{flex:0 1 260px}
                  label{font-size:12px;color:var(--muted);font-weight:600}
                  input,select{width:100%;height:46px;padding:0 13px;border:1px solid #343949;border-radius:10px;background:#0e1016;color:var(--text);font:inherit;outline:none;transition:border-color .15s,box-shadow .15s}
                  input::placeholder{color:#646b7a}
                  input:focus,select:focus{border-color:var(--accent);box-shadow:0 0 0 3px rgba(139,92,246,.18)}
                  select{appearance:none;background-image:linear-gradient(45deg,transparent 50%,#8b93a5 50%),linear-gradient(135deg,#8b93a5 50%,transparent 50%);background-position:calc(100% - 18px) 50%,calc(100% - 13px) 50%;background-size:5px 5px,5px 5px;background-repeat:no-repeat;padding-right:34px}
                  button{cursor:pointer;font:inherit;font-weight:700}
                  .enter{height:46px;padding:0 20px;border:1px solid var(--accent);border-radius:10px;background:linear-gradient(135deg,var(--accent),var(--accent-2));color:white;white-space:nowrap;transition:filter .15s,transform .15s}
                  .enter:hover{filter:brightness(1.08);transform:translateY(-1px)}
                  .enter:disabled{cursor:wait;filter:grayscale(.4);opacity:.65;transform:none}
                  .hint{margin:12px 2px 0;color:#7f8798;font-size:12px}
                  .hint code{color:var(--accent-soft)}
                  .section{margin-top:38px}
                  .section-head{display:flex;align-items:baseline;justify-content:space-between;gap:12px;margin:0 2px 12px}
                  .section-head h2{font-size:15px;margin:0;letter-spacing:.01em}
                  .section-head span{font-size:12px;color:var(--muted)}
                  .version-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:10px}
                  .version-card{display:flex;flex-direction:column;gap:3px;min-height:92px;padding:14px 15px;border:1px solid var(--line);border-radius:14px;background:rgba(17,19,26,.72);color:var(--text);transition:border-color .15s,background .15s,transform .15s}
                  .version-card:hover{border-color:#4c3a76;background:var(--panel-2);transform:translateY(-1px)}
                  .version-name{font-weight:650;letter-spacing:-.01em}
                  .version-meta{color:var(--muted);font-size:12px}
                  .version-go{margin-top:auto;padding-top:8px;color:var(--accent-soft);font-size:12px;font-weight:700}
                  .empty{margin:0;padding:18px;border:1px dashed var(--line);border-radius:14px;color:var(--muted)}
                  .footer{display:flex;gap:10px 18px;flex-wrap:wrap;margin-top:30px;padding-top:16px;border-top:1px solid rgba(39,43,56,.7);color:#7f8798;font-size:12px}
                  .footer code{color:#aab1c0}
                  @media (max-width:640px){.landing{padding-top:34px}.entry{align-items:stretch}.field.version{flex-basis:100%}.enter{width:100%}.version-grid{grid-template-columns:1fr}}
                </style></head><body>
                <main class="landing">
                  <div class="eyebrow">OrzMC · Self-hosted</div>
                  <h1>浏览器直接进服</h1>
                  <p class="lead">自托管 Web 客户端入口。填服务器地址，选一个版本，进游戏；不随附客户端本体。</p>
                  <section class="panel">
                    <form id="entry-form" class="entry" method="get" action="/play" autocomplete="off">
                      <div class="field">
                        <label for="server">服务器地址</label>
                        <input id="server" name="server" value="${esc(server)}" placeholder="wss://example.com:443" spellcheck="false">
                      </div>
                      <div class="field version">
                        <label for="version">客户端版本</label>
                        <select id="version" name="version">$options</select>
                      </div>
                      $tokenInput
                      <button id="enter" class="enter" type="submit">进入游戏 <span aria-hidden="true">→</span></button>
                    </form>
                    <p class="hint">留空服务器地址则进入客户端菜单；首次加载客户端可能需要 10–60 秒。</p>
                  </section>
                  <section class="section">
                    <div class="section-head"><h2>已安装版本</h2><span>${versions.size} 个</span></div>
                    <div class="version-grid">$cards</div>
                  </section>
                  <footer class="footer">
                    <span>API <a href="/api/versions">/api/versions</a></span>
                    <span><a href="/api/health">/api/health</a></span>
                    <span>外嵌 <code>/embed?version=…&amp;server=…</code></span>
                  </footer>
                </main>
                <script>
                (function(){
                  var form=document.getElementById('entry-form');
                  var button=document.getElementById('enter');
                  if(!form||!button)return;
                  form.addEventListener('submit',function(){button.disabled=true;button.textContent='正在打开…';});
                })();
                </script>
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
            val server = call.request.queryParameters["server"]
                ?: call.request.queryParameters["joinServer"].orEmpty()
            val token = call.request.queryParameters["token"].orEmpty()
            val full = call.request.queryParameters["full"] == "1"
            val rawQuery = buildString {
                append("version=").append(enc(entry.info.id))
                if (server.isNotBlank()) append("&server=").append(enc(server))
                if (token.isNotBlank()) append("&token=").append(enc(token))
            }
            val backHref = if (token.isNotBlank()) "/?token=${enc(token)}" else "/"
            val serverLabel = if (server.isBlank()) "未指定服务器 · 进入客户端菜单" else server
            val shellClass = if (full) "play-shell full" else "play-shell"
            val sizeLabel = if (full) "小窗" else "全屏"
            val html = """
                <!doctype html>
                <html lang="zh-CN"><head>
                <meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
                <meta name="color-scheme" content="dark">
                <title>${esc(displayName(entry.info))} · OrzMC</title>
                <style>
                  :root{color-scheme:dark;--bg:#05060a;--line:#2a2e3a;--text:#f4f4f5;--muted:#9ca3af;--accent:#8b5cf6;--accent-soft:#c4b5fd;--pad:clamp(10px,2vw,22px)}
                  *{box-sizing:border-box}
                  html,body{height:100%;margin:0;background:var(--bg);color:var(--text);overflow:hidden}
                  body{font:14px/1.45 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Noto Sans SC",sans-serif}
                  .play-root{display:flex;align-items:center;justify-content:center;height:100%;padding:var(--pad)}
                  .play-shell{display:flex;flex-direction:column;gap:10px;width:100%;max-width:1180px;height:calc(100vh - var(--pad) * 2);max-height:820px;transition:max-width .2s ease,height .2s ease}
                  .play-shell.full{position:fixed;inset:0;z-index:1;width:100vw;max-width:none;height:100vh;max-height:none;gap:0;background:#000}
                  .play-bar{display:flex;align-items:center;gap:8px;flex:0 0 auto;min-height:46px;padding:4px 8px 4px 5px;border:1px solid var(--line);border-radius:12px;background:rgba(17,19,26,.9);backdrop-filter:blur(12px)}
                  .play-shell.full .play-bar{position:absolute;top:10px;left:50%;z-index:20;width:min(780px,calc(100% - 20px));transform:translateX(-50%);box-shadow:0 12px 40px rgba(0,0,0,.45);background:rgba(12,14,20,.88)}
                  .back{display:grid;place-items:center;width:34px;height:34px;flex:0 0 auto;border-radius:9px;color:var(--muted);font-size:20px;line-height:1}
                  .back:hover{background:#232735;color:var(--text)}
                  .titles{display:flex;flex-direction:column;min-width:0;flex:1 1 auto;padding:0 2px}
                  .titles strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:13px;font-weight:650}
                  .titles span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:var(--muted);font-size:11px}
                  .actions{display:flex;align-items:center;gap:6px;flex:0 0 auto}
                  .bar-button{height:32px;padding:0 11px;border:1px solid #343949;border-radius:8px;background:#171a24;color:#d7dae2;font:inherit;font-size:12px;font-weight:650;cursor:pointer;transition:background .15s,border-color .15s,color .15s}
                  .bar-button:hover{border-color:#4c3a76;background:#232735;color:#fff}
                  .bar-button.primary{border-color:#5b3b9a;background:#2b1c4a;color:#ddd0ff}
                  .stage{position:relative;flex:1 1 auto;min-height:0;overflow:hidden;border:1px solid var(--line);border-radius:14px;background:#000;box-shadow:0 24px 80px rgba(0,0,0,.42)}
                  .play-shell.full .stage{position:absolute;inset:0;border:0;border-radius:0;box-shadow:none}
                  .stage iframe{display:block;width:100%;height:100%;border:0;background:#000}
                  .loading{position:absolute;inset:0;z-index:10;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:9px;padding:24px;text-align:center;background:radial-gradient(600px 300px at 50% 35%,rgba(109,40,217,.16),transparent 70%),#08090d;transition:opacity .3s ease,visibility .3s ease}
                  .loading.hidden{opacity:0;visibility:hidden;pointer-events:none}
                  .spinner{width:30px;height:30px;border:2px solid rgba(196,181,253,.18);border-top-color:var(--accent-soft);border-radius:50%;animation:spin .9s linear infinite}
                  .loading h2{margin:2px 0 0;font-size:16px;letter-spacing:-.01em;font-weight:650}
                  .loading p{margin:0;color:var(--muted);font-size:12px;max-width:460px}
                  .loadbar{width:min(280px,65%);height:3px;margin-top:5px;border-radius:99px;background:#1c2030;overflow:hidden}
                  .loadbar i{display:block;width:38%;height:100%;border-radius:inherit;background:linear-gradient(90deg,transparent,var(--accent-soft),transparent);animation:slide 1.4s ease-in-out infinite}
                  .elapsed{color:#70788a;font-size:11px;font-variant-numeric:tabular-nums}
                  @keyframes spin{to{transform:rotate(360deg)}}
                  @keyframes slide{0%{transform:translateX(-130%)}100%{transform:translateX(360%)}}
                  @media (max-width:640px){.play-bar{min-height:44px}.bar-button{padding:0 9px}.titles span{display:none}.play-shell{height:calc(100vh - var(--pad) * 2)}}
                </style></head><body>
                <div class="play-root">
                  <div id="shell" class="$shellClass">
                    <header class="play-bar">
                      <a class="back" href="${esc(backHref)}" title="返回版本列表" aria-label="返回版本列表">←</a>
                      <div class="titles">
                        <strong>${esc(displayName(entry.info))}</strong>
                        <span>${esc(serverLabel)}</span>
                      </div>
                      <div class="actions">
                        <button id="reload" class="bar-button" type="button" title="重新加载客户端">刷新</button>
                        <button id="size" class="bar-button primary" type="button" aria-pressed="$full">$sizeLabel</button>
                      </div>
                    </header>
                    <main class="stage">
                      <iframe id="game" title="${esc(entry.info.name)}" data-src="/play/raw?${esc(rawQuery)}"
                        allow="fullscreen; gamepad; pointer-lock; clipboard-write; autoplay" allowfullscreen></iframe>
                      <div id="loading" class="loading" role="status" aria-live="polite">
                        <div class="spinner" aria-hidden="true"></div>
                        <h2>正在加载客户端</h2>
                        <p>首次加载可能需要 10–60 秒；客户端文件较大，请稍候。</p>
                        <div class="loadbar" aria-hidden="true"><i></i></div>
                        <span id="elapsed" class="elapsed">0 秒</span>
                      </div>
                    </main>
                  </div>
                </div>
                <script>
                (function(){
                  var shell=document.getElementById('shell');
                  var frame=document.getElementById('game');
                  var loading=document.getElementById('loading');
                  var elapsed=document.getElementById('elapsed');
                  var sizeButton=document.getElementById('size');
                  var reloadButton=document.getElementById('reload');
                  var started=0, ticker=0;
                  function setLoading(on){
                    loading.classList.toggle('hidden',!on);
                    loading.setAttribute('aria-hidden',on?'false':'true');
                  }
                  function updateElapsed(){
                    var n=Math.floor((Date.now()-started)/1000);
                    elapsed.textContent=n+' 秒';
                    if(n>45){elapsed.textContent=n+' 秒 · 首次加载较慢，仍在等待…';}
                  }
                  function beginLoad(){
                    started=Date.now();
                    updateElapsed();
                    clearInterval(ticker);
                    ticker=setInterval(updateElapsed,1000);
                    setLoading(true);
                  }
                  frame.addEventListener('load',function(){
                    clearInterval(ticker);
                    setTimeout(function(){setLoading(false);},350);
                  });
                  beginLoad();
                  frame.src=frame.getAttribute('data-src');
                  sizeButton.addEventListener('click',function(){
                    var full=!shell.classList.contains('full');
                    shell.classList.toggle('full',full);
                    sizeButton.textContent=full?'小窗':'全屏';
                    sizeButton.setAttribute('aria-pressed',String(full));
                  });
                  reloadButton.addEventListener('click',function(){
                    beginLoad();
                    frame.src=frame.getAttribute('data-src');
                  });
                })();
                </script>
                </body></html>
            """.trimIndent()
            call.htmlHeaders(cfg, "no-cache")
            call.respondText(html, ContentType.Text.Html)
        }

        // 真正的客户端 HTML：/play 外壳通过 iframe 引用；不直接给最终用户。
        get("/play/raw") {
            if (!call.tokenOk(cfg)) return@get
            val entry = registry.byId(call.versionParam(cfg, registry))
            if (entry == null) {
                call.respondText("Unknown version", status = HttpStatusCode.NotFound)
                return@get
            }
            val file = patcher.prepare(entry)
            call.htmlHeaders(cfg, "public, max-age=3600", allowSelf = true)
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
                <meta name="color-scheme" content="dark">
                <title>${esc(entry.info.name)} · Embed</title>
                <style>
                  html,body{height:100%;margin:0;background:#000;color:#eee;font:14px system-ui,sans-serif}
                  iframe{display:block;width:100%;height:100%;border:0}
                  .loading{position:fixed;inset:0;z-index:2;display:flex;align-items:center;justify-content:center;gap:10px;background:#08090d;color:#9ca3af;transition:opacity .3s}
                  .loading.hidden{opacity:0;pointer-events:none}
                  .spinner{width:22px;height:22px;border:2px solid #2b213e;border-top-color:#c4b5fd;border-radius:50%;animation:spin .9s linear infinite}
                  @keyframes spin{to{transform:rotate(360deg)}}
                </style>
                </head><body>
                <div id="loading" class="loading"><div class="spinner"></div><span>正在加载客户端…</span></div>
                <iframe id="game" data-src="/play/raw?${esc(q)}" allow="fullscreen; gamepad; pointer-lock; clipboard-write; autoplay" allowfullscreen></iframe>
                <script>
                (function(){
                  var frame=document.getElementById('game');
                  var loading=document.getElementById('loading');
                  function setParam(k,v){var u=new URL(frame.getAttribute('data-src'),location.href);u.searchParams.set(k,v);frame.setAttribute('data-src',u.toString());loading.classList.remove('hidden');frame.src=u.toString();}
                  window.WebMC={
                    join:function(server){setParam('server',server);},
                    setVersion:function(v){setParam('version',v);},
                    reload:function(){loading.classList.remove('hidden');frame.src=frame.getAttribute('data-src');}
                  };
                  frame.addEventListener('load',function(){
                    loading.classList.add('hidden');
                    try{parent.postMessage({type:'webmc:ready',version:${js(entry.info.id)},server:${js(server)}},'*');}catch(e){}
                  });
                  frame.src=frame.getAttribute('data-src');
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

private fun ApplicationCall.htmlHeaders(
    cfg: Config,
    cache: String? = null,
    allowSelf: Boolean = false,
) {
    val ancestors = cfg.frameAncestors.trim()
    val frameAncestors = if (allowSelf && ancestors != "*") "'self' $ancestors" else ancestors
    response.header("X-Content-Type-Options", "nosniff")
    response.header("Referrer-Policy", "no-referrer")
    response.header("Content-Security-Policy", "frame-ancestors $frameAncestors")
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

private fun displayName(info: VersionInfo): String {
    val name = info.name.trim()
    return if (name.contains(info.id, ignoreCase = true)) name else "$name · ${info.id}"
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
