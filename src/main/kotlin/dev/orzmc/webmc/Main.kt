package dev.orzmc.webmc

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory

fun main() {
    val cfg = Config.fromEnv()
    val log = LoggerFactory.getLogger("webmc")
    log.info(
        "starting webmc on {}:{} (data={}, cache={}, defaultVersion={})",
        cfg.host, cfg.port, cfg.dataDir, cfg.cacheDir, cfg.defaultVersion ?: "latest",
    )
    embeddedServer(Netty, port = cfg.port, host = cfg.host) { module(cfg) }.start(wait = true)
}
