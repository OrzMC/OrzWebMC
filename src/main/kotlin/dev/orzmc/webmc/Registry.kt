package dev.orzmc.webmc

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * 扫描数据目录：<data>/<version>/client.html（或 index.html）+ 可选 meta.json。
 * 只允许目录名/ meta.id 作为版本 id，避免路径穿越；不下载、不打包任何客户端。
 */
internal class VersionRegistry(private val dataDir: Path) {

    private val log = LoggerFactory.getLogger(VersionRegistry::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val versions: List<VersionEntry> = scan()

    fun byId(id: String?): VersionEntry? =
        if (id == null) null else versions.firstOrNull { it.info.id == id }

    fun defaultVersion(preferred: String?): VersionEntry? =
        preferred?.let { byId(it) } ?: versions.lastOrNull()

    private fun scan(): List<VersionEntry> {
        if (!Files.isDirectory(dataDir)) {
            log.warn("data dir does not exist: {}", dataDir)
            return emptyList()
        }
        val found = mutableListOf<VersionEntry>()
        Files.list(dataDir).use { stream ->
            stream.filter { it.isDirectory() }
                .sorted(compareBy { it.name })
                .forEach { dir ->
                    parse(dir)?.let { found += it }
                }
        }
        return found.sortedWith { a, b -> compareVersion(a.info.id, b.info.id) }
    }

    private fun parse(dir: Path): VersionEntry? {
        val metaFile = dir.resolve("meta.json")
        val meta = if (metaFile.isRegularFile()) {
            runCatching { json.decodeFromString<MetaFile>(Files.readString(metaFile)) }
                .onFailure { log.warn("invalid meta.json in {}: {}", dir, it.message) }
                .getOrNull() ?: MetaFile()
        } else MetaFile()

        val id = meta.id?.trim()?.takeIf { it.isNotEmpty() } ?: dir.name
        if (!ID_RE.matches(id)) {
            log.warn("skip {}: invalid version id '{}'", dir, id)
            return null
        }

        val clientName = meta.client?.trim()?.takeIf { it.isNotEmpty() }
            ?: listOf("client.html", "index.html").firstOrNull { dir.resolve(it).isRegularFile() }
            ?: run {
                log.warn("skip {}: no client.html/index.html", dir)
                return null
            }

        val client = dir.resolve(clientName).normalize()
        if (!client.startsWith(dir.normalize()) || !client.isRegularFile()) {
            log.warn("skip {}: client file escapes dir or missing: {}", dir, clientName)
            return null
        }

        val inject = when (meta.inject?.lowercase()) {
            null, "", "auto" -> InjectMode.AUTO
            "joinserver", "join_server" -> InjectMode.JOIN_SERVER
            "none", "off" -> InjectMode.NONE
            else -> {
                log.warn("{}: unknown inject '{}', using auto", dir, meta.inject)
                InjectMode.AUTO
            }
        }

        val info = VersionInfo(
            id = id,
            name = meta.name?.takeIf { it.isNotBlank() } ?: id,
            protocol = meta.protocol ?: 0,
            runtime = meta.runtime?.takeIf { it.isNotBlank() } ?: "",
            notes = meta.notes ?: "",
        )
        log.info("version {}: {} (client={}, inject={})", id, info.name, clientName, inject)
        return VersionEntry(info, dir, client, inject)
    }

    private fun compareVersion(a: String, b: String): Int {
        val pa = a.split('.', '-', '_')
        val pb = b.split('.', '-', '_')
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrNull(i) ?: ""
            val y = pb.getOrNull(i) ?: ""
            val nx = x.toIntOrNull()
            val ny = y.toIntOrNull()
            val cmp = when {
                nx != null && ny != null -> nx.compareTo(ny)
                else -> x.compareTo(y)
            }
            if (cmp != 0) return cmp
        }
        return a.compareTo(b)
    }

    private companion object {
        val ID_RE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    }
}
