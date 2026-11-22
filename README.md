# OrzWebMC — 自托管 Web 客户端托管服务

> **定位**：给 Eaglercraft 网页客户端提供**自托管入口、深链、外嵌**的最小服务。
> **不包含客户端本体**：客户端由部署者自行从官方来源下载，放进挂载的数据目录；
> 本仓库、Docker 镜像与发布物**零客户端文件**。
>
> **状态**：可运行（Kotlin 2.4 + Ktor 3.6，2026-11-22）。原「自研协议网关」线已暂缓，
> 因为目标「浏览器能进服」用「官方深链 + 自托管兜底」已经满足。
>
> **最后更新**：2026-11-22

---

## 1. 它解决什么

| 场景 | 做法 |
|---|---|
| 官方站正常 | 官网按钮走官方深链（`/play?joinServer=...&launch=1`），本服务不参与 |
| 官方站不可用 / 被墙 / 下架 | 切到本服务：`https://webclient.example.com/play?version=26.3&server=wss://...` |
| 要嵌到自己的面板/网页 | `https://webclient.example.com/embed?version=26.3&server=...`（iframe + `WebMC` JS API） |
| 要列版本/自动集成 | `GET /api/versions`、`GET /api/health` |

一句话：**服务只托管“入口”，不托管“客户端本体”**；客户端由部署者放进数据目录。

### 合规边界（重要）

Eaglercraft 客户端是 **All Rights Reserved**，且项目不符合 Mojang EULA。本仓库/镜像
**不附带客户端**，只提供代码与空数据目录；部署者自行获取客户端并自行承担风险。
不要公开镜像客户端、不要商业化、不要把 `data/versions` 提交到 Git。

---

## 2. 快速开始（Docker）

```bash
# 1) 准备数据目录：客户端自己下载，不要入库
mkdir -p webmc-data/versions/26.3
# 把官方 26.3 单文件 HTML 放成：
#   webmc-data/versions/26.3/client.html
# 可选元数据：
#   cp data/versions/meta.example.json webmc-data/versions/26.3/meta.json

# 2) 启动
docker compose up -d --build
curl http://127.0.0.1:8300/api/health
```

Caddy：

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

然后：

```text
https://webclient.example.com/play?version=26.3&server=wss%3A%2F%2Fyour-host%3A443
```

- 官方 26.3 客户端不读 `?server=`；服务首次访问时在缓存里生成注入版
  （`eaglercraftXOpts.joinServer`），源文件不改、镜像不存客户端。
- 首次启动仍会走客户端自己的 **Edit Profile → Done**、**Default Username → Continue Anyway**；
  之后同一浏览器不再问。
- 不同版本：放 `data/versions/1.8.8/client.html`，访问 `?version=1.8.8`。

---

## 3. 数据目录

```text
data/versions/
├── 26.3/
│   ├── client.html        # 必需（或 index.html）
│   └── meta.json          # 可选：id/name/protocol/runtime/client/inject
└── 1.8.8/
    └── client.html
```

- 目录名 = 版本 id；`meta.json` 可覆盖展示名/入口/协议号等。
- `inject`: `auto`（默认）/ `joinServer`（强制注入）/ `none`（原样提供）。
- 新增/更新版本后重启服务；`data/versions` 与 `data/.cache` 都不进 Git。

详见 [`data/versions/README.md`](data/versions/README.md)。

---

## 4. 接口

| 路径 | 说明 |
|---|---|
| `GET /` | 极简启动页（版本列表 + 地址输入） |
| `GET /play?version=&server=` | 客户端 HTML（注入 `joinServer`；`joinServer` 参数也接受） |
| `GET /embed?version=&server=` | 可 iframe 外壳；`WebMC.join/setVersion/reload` |
| `GET /api/versions` | 版本清单 JSON（CORS） |
| `GET /api/health` | 健康检查 |
| `GET /client/<version>/<path>` | 版本目录内静态文件（白名单 + 防穿越） |

深链/外嵌示例：

```text
/play?version=26.3&server=wss%3A%2F%2Fdev.jokerhub.cn%3A18443
/embed?version=26.3&server=wss%3A%2F%2Fdev.jokerhub.cn%3A18443
```

配置（环境变量）：`WEBMC_DATA_DIR`、`WEBMC_CACHE_DIR`、`WEBMC_PORT`、
`WEBMC_DEFAULT_VERSION`、`WEBMC_FRAME_ANCESTORS`、`WEBMC_ACCESS_TOKEN`、
`WEBMC_CORS_ORIGIN`、`WEBMC_PUBLIC_BASE_URL`。完整说明见
[`docs/client-hosting-service.md`](docs/client-hosting-service.md) §4。

---

## 5. 与 OrzMC 其它仓库

- **`site`**：
  - 主入口：`web_client_url = "https://webclient.example.com/play?version=26.3"`；
  - 兜底：`web_client_fallback_url = "https://webclient.example.com/play?version={version}&server={address}"`。
- **`OrzMCDeploy`**：compose 增加 `webmc` 服务（镜像 + `./webmc-data:/data`），Caddy 加
  `webclient.` 站点；客户端文件由运维放置，deploy 不打包客户端。
- **客户端本体**：不进任何仓库/镜像；官方不可用时由部署者自行获取。

---

## 6. 文档

| 文档 | 内容 |
|---|---|
| [`docs/client-hosting-service.md`](docs/client-hosting-service.md) | **本服务**：接口/配置/部署/外嵌/集成/测试 |
| [`docs/self-hosted-client.md`](docs/self-hosted-client.md) | 手工兜底方案：官方 26.3 资产、patch、Caddy、首次体验、风险 |
| [`docs/deployment.md`](docs/deployment.md) | （旧）协议网关部署手册（暂缓） |
| [`docs/eaglercraft-internals.md`](docs/eaglercraft-internals.md) | Eaglercraft 原理/握手/帧/限制 |
| [`docs/protocol-spec.md`](docs/protocol-spec.md) | （旧）网关协议规范（暂缓） |
| [`docs/gateway-design.md`](docs/gateway-design.md) | （旧）自研网关方案与决策（暂缓） |
| [`docs/references.md`](docs/references.md) | 上游源码、证据、许可证边界 |

---

## 7. 开发

```bash
./gradlew test          # 单元/路由测试（假客户端 fixture）
./gradlew installDist   # 本地运行产物
docker build -t orzmc-webmc:dev .
```

- 语言/构建：Kotlin 2.4.21、Gradle 9.9.0、JDK 17（镜像运行时 JDK 21）。
- 客户端文件**不参与**测试与构建；测试只用几十字节的假 HTML。
- 代码许可证：Apache-2.0（见 `LICENSE`）；客户端许可证归其作者（ARR）。
