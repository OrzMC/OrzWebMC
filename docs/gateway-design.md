# OrzMC Web Gateway —— 架构与实施计划

> **定位**：把「浏览器进 Java 服」做成**一个可自托管的通用网关服务**，而不是每台服都装
> 一个 NMS 反射插件。本文是决策 + 架构 + 里程碑；字节级契约在 [protocol-spec](protocol-spec.md)。
>
> **状态**：草案（2026-11-22）。**D1 已定：Kotlin 2.4.x（JVM 21+）**；D2–D4 待维护者确认（阻塞实现），D5–D6 影响排期。
> **前置阅读**：[eaglercraft-internals](eaglercraft-internals.md)（原理与证据）。
>
> **一句话方案**：Kotlin 2.x 服务（Netty）`orzmc-web-gateway`，终结 `wss`，实现 Eagler 握手 v3/v5，
> 以**普通离线 MC 客户端**身份连 Paper 或 Velocity，PLAY 阶段只做「帧 + 压缩」中继。

---

## 1. 需求

### 1.1 功能

| # | 需求 | 优先级 |
|---:|---|---|
| F1 | 浏览器（Eaglercraft 1.8.8 / 1.12.2 / 26.x）进服：进世界、移动、聊天、方块交互 | P1 |
| F2 | 一个网关接多个后端（按域名/路径路由） | P1 |
| F3 | 对接现有 Velocity 组网（网关作为普通客户端），后端零插件 | P1 |
| F4 | 真实客户端 IP 透传（反代 → 网关 → Velocity） | P1 |
| F5 | 白名单/令牌/登录口令三层收敛身份 | P1（令牌 P2） |
| F6 | 服务器列表 MOTD 查询 | P2 |
| F7 | 皮肤/披风中继 | P2 |
| F8 | 指标、健康检查、结构化日志、优雅重启 | P1 |
| F9 | 兼容 Folia / 多实例 / Geyser 共存（在代理层解决） | P2（验证） |
| F10 | 语音 / WebView / 通知等 Eagler 扩展 | P3+（按需） |

### 1.2 非功能

- **零侵入**：不改 Paper/Spigot/Velocity 的 jar，不依赖 NMS 类名。
- **单进程 / 单 JAR**：`java -jar` 即可运行（systemd/docker）；不依赖外部数据库/中间件。
- **可观测**：每连接有 id、路由、协商版本、耗时、断开原因。
- **可回滚**：与现有 EaglerXServer 并存（不同端口/域名），随时切回。
- **安全默认**：只连配置的上游；默认拒绝未知 Origin/未带真实 IP 的直连。

---

## 2. 候选方案对比

| 维度 | A. 继续用 EaglerXServer | **B. 自研独立网关（本方案）** | C. Direct Sockets / Wisp | D. 混合（网关 + 现有插件） |
|---|---|---|---|---|
| 现网可用 | ✅ 已跑通 | ⏳ 待实现 | ⚠️ 仅 Chromium/需补丁 | ✅ |
| 后端侵入 | 装插件（NMS/反射） | **无** | 无（但需补丁客户端） | 有 |
| 版本跟随 | 跟 MC/Velocity 内部，易断 | 只跟 Eagler 握手 + 登录字段 | 跟浏览器 API | 中 |
| Folia | 仅代理层 | ✅（代理层/直连均可） | ✅ | ✅ |
| 多后端路由 | 靠 Velocity | ✅ 网关直接路由 | ❌ | ✅ |
| 身份/令牌 | 受插件 API 限制 | ✅ 完全可控 | 需补丁 | 中 |
| 皮肤/语音 | ✅ 内置 | ❌ 需自补 | 部分 | ✅ |
| 维护成本 | 中（跟上游） | 中（协议稳定后低） | 高（浏览器兼容/补丁） | 高 |
| 许可证风险 | 低（二进制可用） | 低（自研） | 中（客户端补丁 ARR） | 低 |

**结论**：**B 为主，A 为兜底/对照**。理由：

1. EaglerXServer 把「WS 桥」硬塞进代理的 Netty pipeline，靠反射改压缩/登录状态；
   每次 Velocity/Paper 大版本都可能断（历史上已断过）。
2. 我们的核心诉求是「通用 + 可运营」，独立进程更容易做路由、限流、指标、灰度。
3. 协议本身已经稳定：Eagler 握手版本少（v3/v5），登录包 id 全版本稳定，
   PLAY 阶段零解析——自研的**长期成本低于**跟 NMS。

> C 不是替代品：它解决的是「浏览器如何开 TCP/正版登录」，覆盖面窄；未来可作为
> 「正版登录/低延迟」补充通道，与本网关并存。

### 2.1 与 EaglerXServer 的逐项对比

> 基线：EaglerXServer **1.1.1**（2026-08）+ Velocity 4.3.1；我们：**Kotlin 网关（本仓，未实现）**。
> 图例：✅ 优 / ⚠️ 有条件 / ❌ 缺。

| 维度 | EaglerXServer | 我们的网关 | 结论 |
|---|---|---|---|
| 架构位置 | 注入代理/服务端的 Netty pipeline | **独立进程**（反代之后） | 我们隔离性好；它少一跳 |
| 后端侵入 | 必须装插件（Spigot/Bungee/Velocity） | **零插件**，对后端就是普通离线客户端 | ✅ 我们 |
| 平台覆盖 | Spigot ≤1.17、Bungee、Velocity；新 Paper 靠 `EaglerXPaper`（反编译 fork） | Paper/Folia 直连或 Velocity，不依赖平台内部 | ✅ 我们 |
| Folia | Bukkit 平台不支持；只能在 Velocity 层用 | 直连或代理均可 | ✅ 我们 |
| 多后端 / 切服 | 依赖 Velocity 的 `/server` | 网关按 Host/path 路由 + Velocity 切服 | 相当 |
| 客户端版本 | 1.5.2（Rewind）/ 1.8 / 1.12.2 / 26.x v5 | 1.8.8 / 1.12.2 / 26.x（v3/v5）；**不做 1.5.2** | ⚠️ 它更全 |
| 皮肤 / 披风 | 内置（profile data 缓存 + SkinsRestorer hook） | P1 无；P2 可做中继 | ❌ 我们 |
| 语音 | 内置 WebRTC 信令/转发 | 无 | ❌ 我们 |
| MOTD / 查询 | 内置（动画、缓存、图标） | P2 用 Server List Ping 生成基础版 | ⚠️ 我们 |
| 通知/WebView/暂停菜单/更新证书 | 内置 | 无 | ❌ 我们 |
| 后端 RPC（如 per-player 视距） | 有 `EaglerXBackendRPC` | 无（可加小插件） | ❌ 我们 |
| 自定义 UUID | Velocity/Bungee API `setProfileUUID` | 离线 UUID；自定义需插件或实测代理行为 | ❌ 我们（重要） |
| 真实 IP | 反代 `X-Real-IP` + `forward_ip`（缺头直接断） | 反代 `X-Real-IP` + **PROXY v2** 到 Velocity | 相当；我们多一层配置 |
| 限流 / Origin | 内置 origin 白/黑名单、IP 限流 | 自研，更细（连接/帧/解压/令牌） | ⚠️ 我们灵活但未验证 |
| 性能 / 延迟 | 进程内，少一跳 | 独立进程，多一跳回环 | ⚠️ 它略优（回环可忽略） |
| 部署 / 升级 | 丢一个 jar 进代理/实例 | 多一个进程/容器 + 配置 + TLS 上游 | ⚠️ 它更简单 |
| 版本跟随风险 | 跟 NMS/Velocity 反射，大版本易断 | 只跟 Eagler 握手 + MC 登录字段 | ✅ 我们 |
| 可观测 | 代理日志 + 插件日志 | 自带连接 id、指标、结构化日志 | ✅ 我们 |
| 许可证 / 可改 | ARR（二进制可用，改源码受限） | Apache-2.0 自研 | ✅ 我们 |
| 成熟度 | 现网跑通、有 issue 历史 | 未实现；`protocol-spec` §9 有 10 项待真机确认 | ❌ 我们 |
| 开发成本 | 0（现成） | P1–P3 自研 | ❌ 我们 |

#### 我们的优点（归纳）

1. **零侵入 + 版本解耦**：不碰 NMS/Velocity 内部，Paper/Velocity 升级不会因为反射断掉；
   只需跟 Eagler 握手（v3/v5）与 MC 登录字段。
2. **独立运维**：独立进程可灰度、蓝绿、重启、水平扩容；不把 Web 流量塞进代理进程。
3. **多租户/路由**：一个网关按 Host/path/`requestedServer` 接多个后端；EaglerXServer 主要靠代理。
4. **安全策略点集中**：Origin、真实 IP、限流、帧上限、解压上限、令牌都在网关做。
5. **可观测/可改**：自己的指标与日志；Apache-2.0，可修 bug、可定制。
6. **协议面小**：PLAY 零解析，长期维护成本低。

#### 我们的缺点（归纳）

1. **功能缺口**：皮肤/披风、语音、MOTD 动画、通知/WebView/更新证书、后端 RPC 都要自补；
   其中 **自定义 UUID** 是最实质的差距（EaglerXServer 可在代理层改 UUID）。
2. **未验证**：26.x 的 v5 细节、LoginAck 去重、caps=0、大帧/分片、压缩炸弹等都要真机确认。
3. **多一个进程**：部署/监控/升级多一套；JVM 基线内存与 EaglerXServer 相当（都跑 JVM）。
4. **协议要自己跟**：Eagler 客户端若出 v6 或改行为，需要我们自己适配（上游可能更快）。
5. **1.5.2 不支持**：EaglerXServer + EaglerXRewind 支持；我们 P1 明确不做。
6. **开发成本**：P1–P3 的代码 + 测试 + 文档，都是新增投入。

#### 什么时候选哪个

| 场景 | 建议 |
|---|---|
| 现在就要皮肤/语音/完整功能，或不想维护协议 | **EaglerXServer**（现网继续用） |
| 要长期跟 Paper/Velocity 升级、要独立限流/多租户/自定义身份 | **我们的网关** |
| 两者都想要 | **并存灰度**：EaglerXServer 保底，网关按域名/玩家灰度 |
| 产品要求「Web 玩家绑定已有 Java 账号（同一 UUID）」 | 先补 **Velocity/Paper 小插件**（网关提供令牌/标记），否则达不到 EaglerXServer 的 UUID 能力 |
| 需要 1.5.2 客户端 | 继续 EaglerXServer + EaglerXRewind |

> 结论不变：**B 为主、A 兜底**；但在皮肤/语音/UUID 这三项上，A 目前明显更强。
> 功能对等前不要把生产入口完全切到网关。

---

## 3. 推荐架构

### 3.1 拓扑（推荐：网关 + Velocity）

```text
玩家浏览器 (Eaglercraft 1.8.8 / 12.2 / 26.x)
   │  wss://play.example.com/s/<route>   （TLS + WS）
   ▼
Caddy / nginx（现有反代）
   │  ws://gateway:8081   + X-Real-IP（+ 共享密钥头）
   ▼
orzmc-web-gateway（本仓）
   │  TCP + PROXY v2（真实 IP）  + 普通 MC 登录（offline）
   ▼
Velocity（online-mode=false，[advanced] proxy-protocol=true，modern forwarding）
   ├── Paper 26.x（online-mode=false）
   └── Folia 26.x（online-mode=false）
```

> Velocity 侧三个必须知道的默认值（`velocity.toml`）：
> - `[advanced] proxy-protocol = true`：接受网关的 PROXY v2 头（真实 IP）。
> - `[advanced] login-ratelimit = 3000`（ms）：**同一来源 IP** 两次登录的最小间隔。
>   不开 PROXY 时所有玩家都来自网关 IP，会被这个值限流；开了 PROXY 才是每玩家独立。
> - `[advanced] connection-timeout = 5000` / `read-timeout = 30000`：慢网络/大区块场景
>   建议 read-timeout 调到 120000（与现网一致）。

**为什么接 Velocity 而不是直连 Paper：**

- 网关对 Velocity 而言就是一个**普通离线客户端**，不需要实现 modern forwarding。
- Velocity 负责多后端、切服、UUID 统一、已登录去重、限流。
- PROXY v2 让 Velocity 看到真实 IP（网关在 MC 握手前发一行 PROXY 头）。
- 后端仍然零 Eagler 插件；ViaVersion 可以装在 Velocity 或后端。

**直连 Paper（单服/测试）**：同一网关，`upstream.type=paper`，只是不经过代理；
压缩由网关处理；真实 IP 需要 Paper 版本/插件支持（否则后端只看到网关 IP）。

### 3.2 端口与暴露面

| 组件 | 端口 | 暴露 |
|---|---|---|
| Caddy | 443（宿主 18443） | 公网 |
| 网关 | 8081（容器/环回） | **不发布公网**，只给反代 |
| Velocity | 25565（宿主） | 公网（Java 玩家） |
| 后端 Paper/Folia | 内部端口 | 不发布 |

- 网关**不需要**公网端口；TLS 在 Caddy 终结。
- 反代必须：透传 `Upgrade`、禁用缓冲（`flush_interval -1`）、带 `X-Real-IP`、长超时。
- 若网关与反代同机，可只监听 `127.0.0.1`。

### 3.3 数据流（玩家视角）

```text
1. 浏览器打开客户端（官方页/自建页）→ 选服务器/直连 wss://play.example.com/s/survival
2. WS 建立（permessage-deflate）
3. Eagler 握手 v5/v3（§protocol-spec）
4. 网关按 Host/path/requestedServer 选路由 → 连 Velocity（PROXY v2 + MC 登录）
5. Velocity 返回 Login Success → 网关回 ALLOW_LOGIN
6. 客户端 profile/finish → 网关回 FINISH_LOGIN → PLAY
7. 双向中继：WS 帧（包） ↔ TCP VarInt 帧（+压缩）
```

---

## 4. 组件与代码结构（建议）

```text
webmc/
├── cmd/orzmc-web-gateway/main.go      # 启动、配置、信号、优雅退出
├── internal/
│   ├── config/                        # 配置解析 + 校验 + 热加载（可选）
│   ├── gateway/
│   │   ├── http.go                    # 反代鉴权、Origin、限流、WS upgrade
│   │   ├── session.go                 # 状态机（S0–S4）、超时、错误映射
│   │   ├── query.go                   # Accept: motd（P2）
│   │   └── metrics.go                 # Prometheus / 结构化日志
│   ├── eagler/
│   │   ├── frame.go                   # CLIENT_VERSION / SERVER_VERSION / LOGIN...
│   │   ├── handshake_v3.go
│   │   ├── handshake_v5.go
│   │   └── caps.go
│   ├── mc/
│   │   ├── varint.go
│   │   ├── login.go                   # Handshake/LoginStart/Success/Compression
│   │   ├── frame.go                   # TCP 帧（含压缩）
│   │   └── proxyproto.go              # PROXY v2（可选）
│   ├── relay/
│   │   └── pipe.go                    # 双向中继、背压、关闭
│   └── route/
│       └── table.go                   # Host/path/requestedServer → upstream
├── testdata/                          # 黄金向量（抓包替换）
├── tests/
│   ├── fakeclient/                    # 假 Eagler 客户端
│   ├── fakeserver/                    # 假 MC 后端
│   └── e2e/                           # 真客户端（可选，脚本驱动）
├── docs/
└── deploy/                            # systemd/docker 样例（P3）
```

### 4.1 关键接口（语义，不是最终签名）

```go
type Session interface {
    // 返回一个已建立的 PLAY 会话
    Login(ctx context.Context, hs *eagler.Handshake, ws WSConn) (Play, error)
}
type Play interface {
    FromClient(pkt []byte) error       // 一个完整 MC 包
    NextServerPacket() ([]byte, error) // 一个完整 MC 包（已解压）
    Close() error
}
```

### 4.2 配置草案

```yaml
listen: "127.0.0.1:8081"
trusted_proxy:
  header: X-Real-IP
  secret_header: X-OrzWeb-Secret
  secret: "<从 .env 注入>"
tls: {}                        # 推荐由 Caddy 终结；直接暴露时才配 cert/key

origins:
  allow: ["https://orzmc.jokerhub.cn", "https://play.example.com"]
  allow_null: false            # file:// 客户端默认拒绝；需要时显式开

limits:
  max_connections: 200
  per_ip_connections: 6
  per_ip_rate: "10/m"
  ws_message_max: 1MiB
  backend_packet_max: 8MiB
  handshake_timeout: 10s
  login_timeout: 10s
  idle_timeout: 120s

routes:
  - match: { host: "play.example.com", path_prefix: "/s/survival" }
    upstream:
      type: velocity                  # velocity | paper
      addr: "127.0.0.1:25565"
      proxy_protocol: true
      handshake_host: "play.example.com"
    display_name: "生存服"

  - match: { host: "play.example.com" }
    upstream: { type: velocity, addr: "127.0.0.1:25565", proxy_protocol: true }
    display_name: "大厅"

identity:
  mode: anonymous            # anonymous | token
  token:
    hmac_secret: "<...>"
    param: token             # ?token= 或 WS 子协议（后者客户端不支持）
    map_username: fixed      # 令牌绑定固定名字，忽略客户端名字
```

---

## 5. 协议实现要点（摘要）

> 完整契约见 [protocol-spec](protocol-spec.md)。这里只列**设计选择**。

| 选择 | 决定 | 理由 |
|---|---|---|
| Eagler 握手 | 只做 **v3 + v5** | 覆盖 1.8.8/1.12.2 与 26.x；v2/v4 可回退到 3 或拒绝 |
| MC 协议翻译 | **不做** | 交付 ViaVersion；网关不碰包 id 表 |
| 后端连接 | 登录成功后**代发 Login Acknowledged** | 参考实现证明必需（≥764） |
| 压缩 | 后端链路**自己实现 zlib** | 通用（不能要求别人改 `network-compression-threshold`） |
| 客户端压缩 | 只协商 `permessage-deflate` | 浏览器唯一可用 |
| 帧 | 1 WS 消息 = 1 MC 包 | 客户端实现强制 |
| 缓冲 | 登录期后端包有界缓冲，finish 后 flush | 防丢包/乱序 |
| 皮肤/语音 | P1 不实现 | 不阻塞进服；后续按需 |
| MOTD | P2 由网关做 Server List Ping 生成 | 体验提升，不影响连接 |
| 超时 | 10 s 握手/登录，120 s 空闲 | 与现网一致，防挂死 |

---

## 6. 身份与安全

### 6.1 身份模型（离线）

```text
name ──▶ offline UUID = UUIDv3("OfflinePlayer:" + name)
```

- 与 Java 离线服、现网 EaglerXServer **完全一致**；同名 = 同账号。
- 因此「身份安全」= **能不能用这个名字登录**，必须靠：

| 层 | 手段 | 现状 |
|---|---|---|
| 网关 | Origin/令牌/限流 | 令牌 P2 |
| 代理 | `online-mode=false` + 已登录去重 + 真实 IP | Velocity 现成 |
| 服内 | 白名单（`white-list=true`）+ 登录口令（LoginSecurity） | 已有 |
| 运营 | op/权限名字提前注册（UUID 由名字派生） | 已有规范 |

### 6.2 令牌模式（P2）

- 官网/Hugo 页面登录后下发 `?token=<HMAC(name, exp)>`；网关校验并**强制**使用绑定名字。
- 防止「知道别人名字就能冒用」；令牌短期有效，可绑定 IP/UA（可选）。
- 不引入账号系统：令牌由站点现有能力签发（后续与 `site` 对接）。

### 6.3 真实 IP

| 段 | 方案 |
|---|---|
| 浏览器 → Caddy | Caddy 自带真实 IP |
| Caddy → 网关 | `X-Real-IP`（+ 共享密钥头，拒绝直连） |
| 网关 → Velocity | **PROXY protocol v2**（Velocity `proxy-protocol=true`） |
| Velocity → 后端 | Velocity modern forwarding 自带 IP |

> 若直连 Paper：Paper 对 PROXY 协议的支持随版本/插件而变，P1 先记录网关 IP，
> P2 用服内插件（OrzMCPlugin）读取网关附带的登录期/PLAY 期标记补真实 IP。

### 6.4 滥用防护

- 只连配置上游（**不做开放代理**）。
- 每 IP 并发/速率、全局并发、每连接缓冲上限。
- 解压炸弹：`dataLength` 上限 + 压缩比检查。
- Origin/密钥/真实 IP 三者缺一不可（可配）。
- 日志脱敏；不记录 cookie/token。

---

## 7. 路由与多后端

| 维度 | 用途 | 示例 |
|---|---|---|
| Host | 一个域名一个服/环境 | `survival.example.com` |
| Path | 同域多服 | `wss://play.example.com/s/survival` |
| Eagler `requestedServer` | 客户端服务器列表点击的名字 | v5 可能携带；v3 固定 `default` |
| 默认路由 | 兜底（大厅） | `wss://play.example.com/` |

- 路由表**静态配置**（P1）；不做「用户填目标地址」（安全）。
- 需要跨节点时用 `REDIRECT_TO`（v4+）或 DNS/反代分流；P2 再做。
- 与 Velocity 的关系：网关只选「进哪个代理/后端组」，代理内部切服仍用 `/server`。

---

## 8. 技术选型

### 8.1 已定：Kotlin 2.4.x（JVM 21+）

维护者 2026-11-22 决定：实现语言用**现代 Kotlin（K2）**，与 `backup/java` 的技术栈同代；
JVM 21 工具链（可运行在 25，跟 MC 26.x 同代）。协议/状态机/拓扑与语言无关；`protocol-spec` 不变。

| 项 | 选型 | 说明 |
|---|---|---|
| 语言 | **Kotlin 2.4.x**（K2） | sealed interface + data class 表达握手帧/状态机；value class 包 `VarInt`/UUID；explicit API |
| JVM | **toolchain 21**（运行 21/25） | Gradle `jvmToolchain(21)`；`-Xjdk-release=21`；不依赖 preview |
| 构建 | **Gradle 9.x + Kotlin DSL + version catalog** | `gradle/libs.versions.toml`；`com.gradleup.shadow` 打 fat jar |
| 网络 | **Netty 4.2.x**（或 4.1 LTS） | HTTP/WS/`permessage-deflate`/`ByteBuf` 池化；与 EaglerXServer 同栈思路 |
| 并发 | **kotlinx-coroutines** + `Flow`/`Channel` | 会话状态机/背压用协程；Netty `ChannelFuture` 用 `await()`（jdk8 桥） |
| 序列化 | **kotlinx-serialization-json**；YAML 用 **kaml** | 配置/指标/JSON 查询；避免反射式映射 |
| 日志/指标 | SLF4J + Logback；Micrometer | 先输出 Prometheus 文本；连接级 MDC（conn_id/route/name） |
| 测试 | JUnit 5 + **Kotest**（可选）+ `EmbeddedChannel` | 黄金向量 round-trip；假客户端/假后端组件测试；`mockk` 按需 |
| 质量 | **detekt + ktlint** | CI 与本地同门禁（与 `backup/java` 习惯一致） |

**部署形态**：

```bash
./gradlew shadowJar
java -Xms256m -Xmx512m -jar build/libs/orzmc-web-gateway-all.jar --config /etc/orzmc/web-gateway.yaml
```

- Docker 基础镜像 `eclipse-temurin:21-jre`；systemd 同理。
- Shadow jar 已含 Kotlin 运行时，无需额外装 Kotlin。
- 可选 `jlink` 自定义运行时（~50 MB）；GraalVM native-image 对 Kotlin+Netty 成本高，P3 再评估。
- 不追求 Go 式「单静态二进制」：单实例常驻服务，JVM 启动/内存成本可接受。

### 8.2 与其它语言的对比（记录决策依据）

| 语言 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **Kotlin 2.4.x** | 与 MC/Paper/Velocity 同生态；K2 + 协程 + 密封类型适合协议；Netty 成熟；与 `backup/java` 同栈 | JVM 基线内存/启动成本；打包比 Go 大 | **已定** |
| Java 21 | 同生态、工具链最保守 | 样板代码多；状态机表达不如 sealed/data | 兜底，不作主语言 |
| Go | 单静态二进制、部署简单 | 与 MC 生态远；协议/网络库要另选 | 备选（不选） |
| Rust | 性能/内存最优 | 开发慢、迭代成本高 | 不选 |
| Node/TS | 原型最快（现有 JS 代理） | 每连接内存/GC、压缩 CPU | 只做原型/对照 |

### 8.3 依赖最小化

- 只引入：Netty、kotlinx-coroutines、kotlinx-serialization、YAML、日志、指标。
- **不引入 Minecraft 协议库**（避免被包 id/版本绑架）；登录包手写（字段少且稳定）。
- 不把 EaglerXServer/Velocity 作为编译依赖（只参考协议，不复制代码；其许可为 ARR）。

> 当前 `webmc` 里的 `start.sh`（`npm run build`）是空壳，没有 `package.json`；
> P1 起骨架时删除或改为 `./gradlew shadowJar` / `java -jar` 包装。

---

## 9. 兼容性与降级

| 场景 | 行为 |
|---|---|
| 客户端只声明 v2 | 回 `VERSION_MISMATCH`（提示升级）；或 P2 实现 v2 |
| 客户端只声明 v4 | 尝试按 v5 子集处理（v4≈v5-caps）；否则提示升级/降级 |
| 后端协议不匹配且无 Via | 后端踢人 → 把原因转给客户端（明确提示版本） |
| 后端在线模式 | 明确报错，不静默失败 |
| 后端不支持压缩配置 | 网关自己压缩（默认） |
| Velocity 未开 `[advanced] proxy-protocol` | 配置检查直接失败（启动时报错）；否则真实 IP 全变成网关 IP，还会撞 `login-ratelimit` |
| 反代未带真实 IP | 拒绝（默认）或降级为「记录网关 IP」（显式配置） |

---

## 10. 性能与容量（估算，待实测）

| 指标 | 估算 | 备注 |
|---|---|---|
| 常驻基线 | 100–200 MiB | JVM + Netty；用 `-Xmx` 控上限 |
| 每连接内存 | 0.3–1 MiB | Netty 缓冲 + zlib；100 人约 +50–100 MiB |
| CPU / 玩家 | 1–3% 单核 | 取决于包速率与压缩级别；区块爆发时短时更高 |
| 带宽 | 与 Java 客户端同量级 | WS 头 + deflate 略优于原版 zlib |
| 首包延迟 | +0（同机） | 反向代理/跨地域另算 |
| 并发上限 | 单核 ~100–300 玩家（待压测） | 目标先按 50–100 设计 |

**优化点**：

1. 压缩级别可调（3 比 6 省 CPU、费带宽；家宽上行宝贵 → 用 5–6）。
2. 缓冲池（`sync.Pool`）+ 避免每包 alloc。
3. 批量写：后端 → 客户端方向可把「连续的多个小包」攒到 1 个 WS 消息？
   **不行**（客户端按单包解析）；只能在 TCP 读侧合并读，但发送仍一包一帧。
4. 慢客户端：发送队列 > 8 MiB 直接断开（MC 服务端也会踢）。
5. 限流在 Caddy 之前做（连接层），别让 CPU 被握手打满。

---

## 11. 可观测与运维

| 项 | 内容 |
|---|---|
| 日志 | `ts, level, conn_id, route, name, eagler_v, mc_v, remote_ip, event, dur_ms, err` |
| 指标 | 连接数、握手成功/失败（按原因）、登录耗时、在线时长、字节数、压缩比、队列深度 |
| 健康 | `/healthz`（进程）、`/readyz`（配置/上游可达） |
| 配置 | 启动时校验（路由重叠、上游不可达、密钥缺失）；可加 SIGHUP 重载 |
| 部署 | systemd unit 或 docker compose；`Restart=always`；`LimitNOFILE=65535` |
| 升级 | 先起新端口 → 反代切流 → 旧实例 graceful drain（不再接新连接，等现有连接结束/超时） |

---

## 12. 测试与验收

### 12.1 分层

| 层 | 内容 | 工具 |
|---|---|---|
| 单元 | VarInt/字符串/UUID、v3/v5 编解码 round-trip、Login Success 解析、压缩帧 | `go test` + 黄金向量 |
| 组件 | 假客户端 ↔ 网关 ↔ 假后端：握手全路径、错误路径、超时、缓冲 flush | `tests/fakeclient`、`tests/fakeserver` |
| 真机 | 1.8.8 客户端（headless Chrome）进 Paper；26.3 客户端进 Paper/Velocity | Playwright/Chrome + 现网测试服 |
| 兼容 | 1.12.2、ViaVersion、Folia、Geyser 共存、插件矩阵 | 复用 `deploy` 的 T1–T12 |
| 负载 | N 个假客户端保持在线 + 移动/聊天；观测 CPU/RSS/延迟 | 脚本化（假客户端可复用） |
| 混沌 | 断网、半开连接、慢客户端、zlib bomb、超大帧、错误握手 | 单元 + 组件 |

### 12.2 验收门槛（P1）

- [ ] 1.8.8 客户端：进服、移动、聊天、放置/破坏、`/tp`、断线重连。
- [ ] 26.x 客户端：同上（用现网测试服 + 官方客户端页）。
- [ ] 后端零 Eagler 插件（只有 ViaVersion/常规插件）。
- [ ] 同名二次登录：旧会话被代理拒绝、新会话需口令（LoginSecurity）。
- [ ] 真实 IP 在 Velocity/后端日志中正确。
- [ ] 100 个假客户端在线 30 分钟：无泄漏、无 panic、CPU < 1 核。
- [ ] 反代直连（不带密钥/真实 IP）被拒绝。
- [ ] 回滚：停网关 → 切回 EaglerXServer/直连，5 分钟内恢复。

### 12.3 与现网验收的关系

`deploy` 已有 T1–T12（握手探针、三端共存、插件矩阵、Folia、1006 排查）。
网关实现后，**复用同一套验收口径**，只把「桥接层」从 EaglerXServer 换成网关；
探针 `deploy/tests/eaglercraft_probe.py` 可直接作为网关的协议测试客户端。

---

## 13. 里程碑

### P0 调研（✅ 2026-11-22）

- 产出：`eaglercraft-internals.md`、`protocol-spec.md`、本文件、`references.md`。
- 退出标准：能回答「协议是什么、要做什么、风险在哪」，并有源码/实测证据。

### P1 最小可用（建议 2–3 个迭代）

1. Kotlin 2.4.x 骨架（Gradle 9 + Netty + 协程）+ 配置 + 日志/指标 + 反代鉴权。
2. Eagler v3 握手 + MC 登录（47）+ 压缩 + PLAY 中继 → 直连 Paper 1.8.8/26.x。
3. 黄金向量 + 假客户端/假后端组件测试。
4. 真机 1.8.8 进服（先不做皮肤/MOTD）。
- 退出标准：§12.2 前 3 条 + 真机进服。

### P2 生产化

1. v5/26.x 完整支持（按 §9 清单补齐；LoginAck 去重、caps=0 验证）。
2. Velocity 接入（PROXY v2 + 真实 IP）+ 多路由 + 配置校验。
3. MOTD 查询（Server List Ping 映射）。
4. 令牌身份（与站点对接）+ 限流/熔断。
5. 压测 + 指标面板 + 部署脚本 + 回滚演练。
6. 皮肤中继（可选，先做「同名玩家看到彼此皮肤」）。

### P3 体验与扩展

- 语音（Eagler Voice）评估；WebView/通知；REDIRECT 多节点；Direct Sockets 备选通道。
- 客户端页面（若许可证决策允许）：预置 `servers`/`joinServer`，实现「点开即进服」。

### P4 产品化（可选）

- 对外文档/镜像/一键部署；SaaS 形态（多租户）需重新评估滥用与合规。

---

## 14. 风险登记

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| 26.x 客户端 v5 细节与参考实现有偏差 | 中 | 高 | §9 清单逐项真机验证；保留「回退 EaglerXServer」开关 |
| 浏览器长连接不稳定（1006） | 高 | 中 | 文档化客户端侧处置；服务端 read-timeout/keepalive 调优 |
| 离线身份被冒用 | 高 | 高 | 白名单 + 口令 + 令牌；op 名字预注册 |
| 家宽上行带宽 | 中 | 中 | 压缩级别、视距、限流；必要时只对可信玩家开放 |
| 客户端分发许可证 | 高 | 中 | 不分发客户端；只提供 wss 地址与官方入口 |
| 自研协议跟不动 | 低 | 中 | 协议面很小（v3/v5 + 登录），且有源码/探针 |
| 反代/网络把 WS 弄坏 | 中 | 中 | 固化 Caddy 片段（`flush_interval -1`、`Upgrade`、`X-Real-IP`） |

---

## 15. 待维护者决策

> 本节 **D1–D6 仅指 webmc 网关**，与父仓库产品统一化的 D1–D14 无关。
> **D1 已定**；D2–D4 阻塞实现，D5–D6 影响排期。

| # | 决策 | 含义 / 影响 | 选项 | 建议 |
|---:|---|---|---|---|
| D1 | 实现语言 / 运行时 | 决定代码结构、构建与部署 | Kotlin 2.4.x（JVM 21+） | **已定：Kotlin 2.4.x + Gradle 9 + Netty（2026-11-22）** |
| D2 | 与 EaglerXServer 的关系 | 网关上线后现网桥接层是否保留；影响回滚、功能对等（皮肤/语音/MOTD）、运维复杂度 | 替换 / 并存灰度 / 仅实验 | **并存**：网关新端口/域名，灰度后替换 |
| D3 | 客户端分发形态 | 谁提供浏览器页面/客户端；影响「一键进服」体验、许可证与带宽 | 不分发（官方页+粘地址）/ 自建页面（预置 `servers`）/ 只链官方 | **先不分发**；页面等许可证结论（ARR/EULA） |
| D4 | 身份门槛（P2） | 凭什么证明「你是这个名字」；影响防冒名、登录体验、与站点/插件集成 | 仅白名单+口令 / 站点令牌 / 第三方 OAuth | **站点令牌**（与 `site` 登录打通），保留匿名模式 |
| D5 | 皮肤/语音优先级 | 玩家观感 vs 工作量 | P1 / P2 / P3 / 不做 | 皮肤 P2，语音 P3+ |
| D6 | 首个落地服 | 风险与验收成本 | 测试服 / 国内服 | **测试服**（复用现网 T1–T12） |

> D1 已定；D2/D3/D4 建议在 P1 结束前定（D2 影响部署灰度，D3 影响产品入口，D4 影响身份模块）；
> D5/D6 在 P2 前定即可。

---

## 16. 与现有 `deploy` / `site` 的关系

| 仓库 | 现状 | 网关落地后的变化 |
|---|---|---|
| `deploy` | `ENABLE_EAGLER=true` 时在 Velocity 装 EaglerXServer，Caddy 反代到代理 | 新增「web gateway 容器」与开关；EaglerXServer 变可选；Caddy 上游改为网关 |
| `site` | 卡片显示 `wss://` 地址 + 「在浏览器中玩」打开官方客户端 | 地址不变；如做自建页，`web_client_url` 指向自己的页面（带 `?server=`） |
| `plugin` | 白名单/登录/权限 | 网关令牌模式需要一个小接口（P2）；P1 零改动 |
| `python` / `app` | 启动器 | 无变化（Web 是第三种入口，不替代 Java） |

**回滚路径**：网关是独立进程；停掉它、把 Caddy 上游切回 `orzmc-velocity:25565` 即回到
EaglerXServer 方案。两种方案可以同时监听不同域名，按玩家灰度。

---

## 17. 合规边界：降低暴露面 ≠ 取得 EULA 合规

> **不是法律意见**；以 Mojang EULA 与 Microsoft 服务条款原文为准。
> 结论先说：**Eaglercraft 端到端并不符合 Mojang EULA**，我们的方案也不宣称合规，
> 只是把**我们自己的**风险压到最低（不分发客户端、不商业化、只连自有服务器）。

### 17.1 为什么 Eaglercraft 本身不合规

| 争点 | 事实 | 与 EULA 的关系 |
|---|---|---|
| **客户端来源** | Eaglercraft 是反编译/改写后的 Minecraft Java 客户端（含游戏代码与资源），编译成 JS/WASM 后**公开分发** | EULA 只授予「使用游戏」的有限许可，**不授予**分发/反编译/修改游戏本体的权利；「允许做 mod」不等于「可以随 mod 附带游戏本体」 |
| **离线身份** | 客户端不经过 Mojang 账号/会话认证，服务端用离线 UUID | 离线模式是服务端设置，**不等于**可以使用/分发未授权客户端；绕过正常认证另有条款风险 |
| **公开分发** | 官方站点长期提供单文件客户端下载 | 存在不等于授权；第三方「大家都在用」不构成许可 |
| **商业化** | 若 Web 入口收费/卖权限 | EULA 对商业化有额外限制，风险显著放大 |

一句话：**问题在客户端的分发/改写，而不在「用 WebSocket 连服」这个技术手段。**
技术上把 TCP 换成 WS 是中性的；法律上是否允许，取决于你分发的是什么。

### 17.2 我们做了什么 / 没做什么

| 层 | 我们的做法 | 效果 |
|---|---|---|
| 客户端 | **不分发、不修改、不内置**；玩家自行打开官方页或自担风险 | 避免直接的「分发游戏本体」风险 |
| 网关 | 完全自研（Apache-2.0），只做帧/压缩/登录转换，不含游戏代码 | 网关本身是普通网络服务 |
| 后端 | 只连**我们自己的**服务器；不做「任意服」开放代理 | 不成为公开的规避入口 |
| 运营 | 不收费、不做公开大规模推广；白名单 + 口令/令牌 | 控制暴露面与滥用 |
| 文档 | 明确写「不符合 EULA」，不把「能连」说成「合规」 | 避免误导使用者 |

### 17.3 所以「方案符合 EULA」这个说法错在哪

- **把技术可行当成法律允许**：Eaglercraft 能连 Java 服，不代表它被授权。
- **把「离线模式」当成豁免**：`online-mode=false` 只是服务器配置，不能为未授权客户端提供合法性。
- **把「不分发」当成全链合规**：我们不分发只能降低**我们**的风险；玩家使用反编译客户端这件事本身仍然不合规。
- **把「官方也在做」当成许可**：Mojang 的沉默/未执法不等于授权，且存在被下架的先例风险。

### 17.4 如果未来要公开/商业化

必须先满足至少一条：

1. 取得 Mojang/Microsoft 的**书面许可**（例如官方支持的 Web 客户端形态）；
2. 或改用**官方客户端**（Java/基岩）提供入口，Web 仅作为「远程画面/串流」等不分发客户端的形态；
3. 或把服务严格限制在**私有、非商业、小范围**，并接受「随时可能被要求停止」的风险。

> 这就是为什么 `site` 现在只提供 `wss://` 地址 + 打开官方客户端，**不在本站分发客户端本体**；
> 也是为什么 `README` / 本文档始终写「不符合 EULA」，而不是「符合」。
