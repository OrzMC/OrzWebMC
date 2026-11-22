package dev.orzmc.webmc

import java.nio.file.Path

/** 运行配置：优先环境变量；容器镜像只需挂载 /data 与少量环境变量。 */
data class Config(
    val host: String,
    val port: Int,
    val dataDir: Path,
    val cacheDir: Path,
    val defaultVersion: String?,
    val frameAncestors: String,
    val accessToken: String?,
    val corsOrigin: String,
    val publicBaseUrl: String?,
) {
    companion object {
        fun fromEnv(): Config {
            val port = env("WEBMC_PORT")?.toIntOrNull() ?: 8300
            return Config(
                host = env("WEBMC_HOST") ?: "0.0.0.0",
                port = port,
                dataDir = Path.of(env("WEBMC_DATA_DIR") ?: "/data/versions"),
                cacheDir = Path.of(env("WEBMC_CACHE_DIR") ?: (System.getProperty("java.io.tmpdir") + "/webmc-cache")),
                defaultVersion = env("WEBMC_DEFAULT_VERSION")?.takeIf { it.isNotBlank() },
                frameAncestors = env("WEBMC_FRAME_ANCESTORS")?.takeIf { it.isNotBlank() } ?: "*",
                accessToken = env("WEBMC_ACCESS_TOKEN")?.takeIf { it.isNotBlank() },
                corsOrigin = env("WEBMC_CORS_ORIGIN")?.takeIf { it.isNotBlank() } ?: "*",
                publicBaseUrl = env("WEBMC_PUBLIC_BASE_URL")?.trimEnd('/')?.takeIf { it.isNotBlank() },
            )
        }
    }
}

private fun env(name: String): String? = System.getenv(name)
