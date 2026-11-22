# Web 客户端托管服务（webmc）

> **状态**：可运行 / 本地验收通过（2026-11-22）。Kotlin 2.4 + Ktor 3.6；单容器，数据目录挂载。
> **定位**：给「Eaglercraft 网页客户端」提供一个**自托管入口、深链、外嵌**服务。
> **不包含客户端本体**：客户端由部署者自行下载放进数据目录，镜像/仓库/发布物零客户端文件。

---

## 1. 为什么这样设计

| 需求 | 做法 |
|---|---|
| 官方站可能不可用 | 自己托管客户端文件 + 自己的入口 |
| 不同版本共存 | `data/versions/<id>/client.html`，自动发现/路由 |
| 深链调用 | `/play?version=26.3&server=wss://...`，服务注入 `joinServer` |
| 其它服务集成 | `/api/versions`、`/embed`、`/play`，可被 `site`/`deploy` 引用 |
| **避免分发客户端** | Git 仓库、Docker 镜像、发布物**不含**客户端；只提供代码与空数据目录 |

> 合规口径：客户端本身是 ARR 且不符合 Mojang EULA；本服务**不随附**客户端，
> 部署者自行获取、自行承担风险。详见 `self-hosted-client.md` 与 `gateway-design.md` §17。

---

## 2. 数据目录

```text
data/versions/
├── 26.3/
│   ├── client.html      # 必需（或 index.html）
│   └── meta.json        # 可选
└── 1.8.8/
    └── client.html
```

- 目录名即版本 id（`[A-Za-z0-9._-]{1,64}`）；`meta.json` 可覆盖 id/name/client/protocol/runtime/notes/inject。
- 服务启动时扫描；新增/更新版本后重启（热加载可后续加）。
- 官方 26.3 HTML 不读 `?server=`；服务在**首次访问**时生成一份**缓存补丁**：
  在 `window.eaglercraftXOpts = {` 后注入 `joinServer: URLSearchParams(...).get("server")`。
  源文件不改；补丁文件在 `WEBMC_CACHE_DIR`（默认临时目录；容器内 `/tmp/webmc-cache`），不在镜像/仓库。
- 客户端本身已支持 `joinServer` 时原样提供；`meta.inject=none` 可强制关闭注入。

---

## 3. 接口

| 方法/路径 | 作用 |
|---|---|
| `GET /` | 极简启动页：版本列表 + 服务器地址输入 |
| `GET /play?version=26.3&server=wss://host:port` | 返回该版本客户端 HTML（已注入 `joinServer`）；`joinServer` 参数也接受 |
| `GET /embed?version=26.3&server=wss://...` | 可 iframe 的最小外壳；暴露 `window.WebMC` |
| `GET /api/versions` | 版本清单 JSON（id/name/protocol/runtime/notes/playUrl/embedUrl），带 CORS |
| `GET /api/health` | `{"status":"ok","versions":N}` |
| `GET /client/<version>/<path>` | 版本目录内静态文件（扩展名白名单、防路径穿越） |

深链示例：

```text
https://webclient.example.com/play?version=26.3&server=wss%3A%2F%2Fdev.jokerhub.cn%3A18443
https://webclient.example.com/embed?version=26.3&server=wss%3A%2F%2Fdev.jokerhub.cn%3A18443
```

- `server` / `joinServer` 均可；会由客户端 HTML 在运行时读取。
- `launch=1` 等官方站参数会被忽略（服务端自己启动客户端，不经过官方站外壳）。
- 官方 26.3 客户端首次启动仍会走 **Edit Profile → Done**、**Default Username → Continue Anyway**；
  之后同一浏览器（同源 IndexedDB）不再问。

### 外嵌 API

`/embed` 内是 `<iframe allow="fullscreen; gamepad; pointer-lock; clipboard-write; autoplay">`，并暴露：

```js
WebMC.join('wss://host:port')   // 切换服务器
WebMC.setVersion('1.8.8')       // 切换版本
WebMC.reload()
```

加载完成会 `postMessage({type:'webmc:ready', version, server}, '*')`（供宿主显示状态）。
`WEBMC_FRAME_ANCESTORS` 控制 CSP `frame-ancestors`（默认 `*`，生产建议收窄）。

---

## 4. 配置

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `WEBMC_HOST` | `0.0.0.0` | 监听地址 |
| `WEBMC_PORT` | `8300` | 监听端口 |
| `WEBMC_DATA_DIR` | `/data/versions` | 版本目录 |
| `WEBMC_CACHE_DIR` | `${java.io.tmpdir}/webmc-cache` | 补丁缓存（可删，重建；容器内为 `/tmp/webmc-cache`） |
| `WEBMC_DEFAULT_VERSION` | 无（排序最后） | 无 `version` 参数时的默认版本 |
| `WEBMC_FRAME_ANCESTORS` | `*` | CSP `frame-ancestors`，限制可嵌入的站点 |
| `WEBMC_ACCESS_TOKEN` | 空 | 非空则 `/play`、`/embed`、`/client` 需 `?token=` 或 Bearer |
| `WEBMC_CORS_ORIGIN` | `*` | `/api/versions` 的 CORS |
| `WEBMC_PUBLIC_BASE_URL` | 空 | API 返回绝对 play/embed URL 时使用 |

---

## 5. 运行（Docker，推荐）

```bash
# 1) 准备数据目录（客户端自己下载，不要入库）
mkdir -p webmc-data/versions/26.3
# 把官方 26.3 HTML 放成 webmc-data/versions/26.3/client.html
# 可选：复制 data/versions/meta.example.json 为 webmc-data/versions/26.3/meta.json

# 2) 启动
docker compose up -d --build
curl http://127.0.0.1:8300/api/health

# 3) 反代（Caddy）
```

```caddyfile
webclient.example.com {
    reverse_proxy 127.0.0.1:8300 {
        flush_interval -1
    }
    header {
        X-Content-Type-Options nosniff
        Referrer-Policy no-referrer
        X-Robots-Tag "noindex, nofollow"
    }
}
```

- 75 MB 单文件的首访由 VPS 出；建议 Caddy 长缓存（服务已对 `/client` 发 immutable，`/play` 1 小时）。
- 不要直接暴露 8300；只给反代。
- 也可不用 Docker：`./gradlew installDist` 后运行 `build/install/orzmc-webmc/bin/orzmc-webmc`，环境变量同上。

---

## 6. 与其它服务集成

### 6.1 官网 `site`

两种用法：

1. **主入口**直接指向本服务（官方挂掉时）：
   ```toml
   web_client_url = "https://webclient.example.com/play?version=26.3"
   ```
   `site` 会自动追加 `joinServer`/`launch=1`；本服务读 `joinServer`，忽略 `launch`。
2. **兜底入口**（推荐，官方正常时不展示）：
   ```toml
   web_client_fallback_url = "https://webclient.example.com/play?version={version}&server={address}"
   ```

### 6.2 `OrzMCDeploy`

在 compose 中加一个服务（示例，实际以 deploy 的约定为准）：

```yaml
services:
  webmc:
    image: ghcr.io/orzmc/orzmc-webmc:0.1.0
    restart: unless-stopped
    ports:
      - "127.0.0.1:8300:8300"
    volumes:
      - ./webmc-data:/data
    environment:
      WEBMC_FRAME_ANCESTORS: "https://orzmc.jokerhub.cn"
      WEBMC_PUBLIC_BASE_URL: "https://webclient.example.com"
```

- 客户端文件由运维在 `webmc-data/versions/<id>/client.html` 放置；
- Caddy 增加 `webclient.` 站点反代到 `webmc:8300`；
- `site` 的 `web_client_url`/`web_client_fallback_url` 指向该域名。
- deploy 侧不打包客户端；升级客户端 = 替换数据目录文件 + 重启 `webmc`。

### 6.3 任意第三方

- `GET /api/versions` 取版本；
- `/play?version=&server=` 深链；
- `/embed` iframe + `WebMC` JS API；
- 不需要知道客户端内部结构；版本更新只动数据目录。

---

## 7. 安全与运维

- **路径安全**：只按 `api` 清单里的 version id + 白名单扩展名提供文件；拒绝 `..`/绝对路径。
- **不代理任意 URL**：只做静态托管 + 本地注入，不是开放代理。
- **访问令牌**：私有兜底建议设 `WEBMC_ACCESS_TOKEN`，站点链接带 `?token=`。
- **缓存**：`/client` immutable；`/play` 1h；更新客户端后重启并换版本目录名/清缓存。
- **健康**：`/api/health`；Docker healthcheck 已内置。
- **日志**：版本发现、补丁生成、请求错误；不记录 token。
- **合规**：镜像/仓库零客户端；部署者自行获取；`data/versions` 不入 Git。

---

## 8. 开发与测试

```bash
./gradlew test          # 单元/路由测试（假客户端 fixture，不含真实客户端）
./gradlew installDist   # 本地运行产物
docker build -t orzmc-webmc:dev .
```

本地验收（2026-11-22，真实官方 26.3 HTML 78,124,802 B）：

| 项 | 结果 |
|---|---|
| `./gradlew clean test` | 通过（版本/注入/embed/API/token/穿越；并修复注入双逗号） |
| `/api/health` / `/api/versions` | `{"status":"ok","versions":1}` / 26.3（777, wasm-gc） |
| `/` 启动页 | 正常列出 26.3 |
| `/client/26.3/assets/test.js` | 200；路径穿越 404 |
| `/play?version=26.3` | 200，`Content-Length: 78124960`；含 `joinServer`，无 `,,` |
| `/embed?...` | 200，iframe + `WebMC` 脚本；CSP frame-ancestors 生效 |
| token | 无 token 401；带 token 200；embed 带 token 200 |
| 无头 Edge | 首次 Edit Profile → Done、Default Username → Continue Anyway 后自动连测试服（白名单踢出） |
| Docker 运行镜像（temurin 25 本地验证） | 健康/API/`/play` 200（78,124,960 B，约 6s）；缓存生成正常 |

> 首次 `/play` 在 `/mnt/e`（Windows 文件系统）生成补丁约 40s，缓存后约 13s；
> Docker/ext4 约 6s。多阶段 `Dockerfile` 因本机 Docker Hub 拉取超时未跑；`gradle:9.9-jdk21`
> 标签已核对存在，需在 CI/生产机跑一次。真实客户端文件只用于本地验证，**没有**提交到仓库/镜像。
