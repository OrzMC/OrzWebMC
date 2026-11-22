package dev.orzmc.webmc

import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * 运行时补丁：官方单文件客户端不读 ?server=，这里在 eaglercraftXOpts 里注入 joinServer。
 * 只在启动后的首次访问时生成缓存副本；不在仓库/镜像里存放客户端本体。
 */
internal class Patcher(private val cacheDir: Path) {

    private val log = LoggerFactory.getLogger(Patcher::class.java)
    private val locks = ConcurrentHashMap<String, Any>()

    fun prepare(entry: VersionEntry): Path = synchronized(locks.computeIfAbsent(entry.info.id) { Any() }) {
        if (entry.inject == InjectMode.NONE) return entry.client

        val text = Files.readString(entry.client, StandardCharsets.UTF_8)
        val native = text.contains("joinServer")
        if (native && entry.inject == InjectMode.AUTO) {
            log.info("{}: client already reads joinServer, serving as-is", entry.info.id)
            return entry.client
        }

        val anchor = "window.eaglercraftXOpts = {"
        if (!text.contains(anchor)) {
            log.warn("{}: no eaglercraftXOpts anchor; deep link will need ?server= support in the client", entry.info.id)
            return entry.client
        }

        val patched = text.replaceFirst(anchor, "$anchor $JOIN_SERVER")
        Files.createDirectories(cacheDir)
        val cache = cacheDir.resolve("${entry.info.id}-${Files.getLastModifiedTime(entry.client).toMillis()}-${Files.size(entry.client)}.html")
        if (!Files.isRegularFile(cache) || Files.size(cache) == 0L) {
            val tmp = Files.createTempFile(cacheDir, "patch-", ".html")
            Files.writeString(tmp, patched, StandardCharsets.UTF_8)
            Files.move(tmp, cache, StandardCopyOption.REPLACE_EXISTING)
            log.info("{}: generated patched client {}", entry.info.id, cache.fileName)
        }
        return cache
    }

    private companion object {
        const val JOIN_SERVER =
            "joinServer: (function(){try{var p=new URLSearchParams(location.search);" +
            "return p.get('server')||p.get('joinServer')||undefined}catch(e){return undefined}})(),"
    }
}
