package dev.orzmc.webmc

import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * 运行时补丁：不动源文件，只在首次访问时生成一份缓存副本。
 *
 * 目前注入两件事：
 * 1. `keyboardShim`：键盘兜底脚本（IME 场景按物理键位改写按键，见 [KeyboardShim]）；
 * 2. `joinServer`：官方单文件客户端不读 `?server=`，在 `eaglercraftXOpts` 里补一个读 URL 的实现。
 */
internal class Patcher(private val cacheDir: Path) {

    private val log = LoggerFactory.getLogger(Patcher::class.java)
    private val locks = ConcurrentHashMap<String, Any>()

    fun prepare(entry: VersionEntry): Path = synchronized(locks.computeIfAbsent(entry.info.id) { Any() }) {
        val cache = cacheDir.resolve(
            "${entry.info.id}-${Files.getLastModifiedTime(entry.client).toMillis()}-${Files.size(entry.client)}" +
                "-r$PATCH_REV.html"
        )
        if (Files.isRegularFile(cache) && Files.size(cache) > 0L) return cache

        // 78 MB 单文件客户端不要先 readString 再 replace：String 的 char[]/UTF-8 副本
        // 会把 512M 堆撑爆（生产首测 OutOfMemoryError）。按字节插入，峰值约 2×源文件。
        val bytes = Files.readAllBytes(entry.client)
        val edits = mutableListOf<Edit>()

        if (entry.keyboardShim) {
            val at = headEnd(bytes)
            if (at < 0) {
                log.warn("{}: no <head>; keyboard shim skipped", entry.info.id)
            } else {
                edits += Edit(at, KeyboardShim.script.toByteArray(StandardCharsets.UTF_8))
            }
        }

        if (entry.inject == InjectMode.NONE) {
            // 显式要求原样提供：连 joinServer 也不注入（keyboardShim 仍按开关生效）。
        } else {
            val native = indexOf(bytes, "joinServer".toByteArray(StandardCharsets.US_ASCII)) >= 0
            if (native && entry.inject == InjectMode.AUTO) {
                log.info("{}: client already reads joinServer, skipping joinServer patch", entry.info.id)
            } else {
                val anchor = "window.eaglercraftXOpts = {".toByteArray(StandardCharsets.US_ASCII)
                val at = indexOf(bytes, anchor)
                if (at < 0) {
                    log.warn(
                        "{}: no eaglercraftXOpts anchor; deep link will need ?server= support in the client",
                        entry.info.id,
                    )
                } else {
                    edits += Edit(at + anchor.size, (" $JOIN_SERVER").toByteArray(StandardCharsets.US_ASCII))
                }
            }
        }

        if (edits.isEmpty()) return entry.client

        val ordered = edits.sortedBy { it.at }
        val total = bytes.size + ordered.sumOf { it.bytes.size }
        val patched = ByteArray(total)
        var src = 0
        var dst = 0
        for (edit in ordered) {
            val len = edit.at - src
            bytes.copyInto(patched, dst, src, src + len)
            dst += len
            edit.bytes.copyInto(patched, dst)
            dst += edit.bytes.size
            src = edit.at
        }
        bytes.copyInto(patched, dst, src, bytes.size)

        Files.createDirectories(cacheDir)
        val tmp = Files.createTempFile(cacheDir, "patch-", ".html")
        Files.write(tmp, patched)
        Files.move(tmp, cache, StandardCopyOption.REPLACE_EXISTING)
        log.info(
            "{}: generated patched client {} (+{} B)",
            entry.info.id,
            cache.fileName,
            patched.size - bytes.size,
        )
        return cache
    }

    /** `<head ...>` 结束位置（`>` 之后），找不到返回 -1。 */
    private fun headEnd(bytes: ByteArray): Int {
        val at = indexOfIgnoreCase(bytes, "<head")
        if (at < 0) return -1
        for (i in at until minOf(bytes.size, at + 256)) {
            if (bytes[i] == '>'.code.toByte()) return i + 1
        }
        return -1
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int = indexOf(haystack, needle, ignoreCase = false)

    private fun indexOfIgnoreCase(haystack: ByteArray, needle: String): Int =
        indexOf(haystack, needle.toByteArray(StandardCharsets.US_ASCII), ignoreCase = true)

    private fun indexOf(haystack: ByteArray, needle: ByteArray, ignoreCase: Boolean): Int {
        if (needle.isEmpty()) return 0
        if (needle.size > haystack.size) return -1
        outer@ for (i in 0..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                val a = haystack[i + j]
                val b = needle[j]
                if (a == b) continue
                if (ignoreCase && lower(a) == lower(b)) continue
                continue@outer
            }
            return i
        }
        return -1
    }

    private fun lower(b: Byte): Byte = if (b >= 'A'.code.toByte() && b <= 'Z'.code.toByte()) (b + 32).toByte() else b

    private data class Edit(val at: Int, val bytes: ByteArray)

    private companion object {
        /** 补丁内容变化时递增，避免复用旧缓存文件。 */
        const val PATCH_REV = 2

        const val JOIN_SERVER =
            "joinServer: (function(){try{var p=new URLSearchParams(location.search);" +
            "return p.get('server')||p.get('joinServer')||undefined}catch(e){return undefined}})(),"
    }
}
