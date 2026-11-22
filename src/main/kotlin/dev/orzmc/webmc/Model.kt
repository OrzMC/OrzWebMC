package dev.orzmc.webmc

import kotlinx.serialization.Serializable
import java.nio.file.Path

/** 对外暴露的版本信息（/api/versions 与启动页使用）。 */
@Serializable
data class VersionInfo(
    val id: String,
    val name: String,
    val protocol: Int = 0,
    val runtime: String = "",
    val notes: String = "",
)

/** 每个版本目录下的可选 meta.json。 */
@Serializable
internal data class MetaFile(
    val id: String? = null,
    val name: String? = null,
    val client: String? = null,
    val protocol: Int? = null,
    val runtime: String? = null,
    val notes: String? = null,
    /** auto（默认）/ joinServer（强制注入）/ none（原样提供） */
    val inject: String? = null,
)

internal enum class InjectMode { AUTO, JOIN_SERVER, NONE }

internal data class VersionEntry(
    val info: VersionInfo,
    val dir: Path,
    val client: Path,
    val inject: InjectMode,
)
