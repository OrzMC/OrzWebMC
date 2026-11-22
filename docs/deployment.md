# 部署手册（目标形态）

> **状态**：**部署契约 / 预演稿（2026-11-22）**。`webmc` 目前**没有可执行的网关**；
> 今天要上线只能走现网的 **EaglerXServer** 拓扑（见 `OrzMCDeploy` 的 `docs/eaglercraft-deploy.md`）。
> 本文定义 P1 网关交付后的**标准部署方式**与验收步骤；配置键名以 P1 实现为准。
>
> **适用**：Eagler 握手 v3/v5（1.8.8 / 1.12.2 / 26.x），整链 **offline-mode**。
> **读者**：部署/运维；先读 [gateway-design](gateway-design.md) §3 与 [protocol-spec](protocol-spec.md)。

---

## 0. 先明确三件事

1. **网关是独立进程**，对后端而言只是一个「普通离线 Java 客户端」；后端不需要装 Eagler 插件。
2. **TLS 在 Caddy 终结**：浏览器只连 `wss://`，网关只监听环回/内网。
3. **只支持离线链**：Velocity 与 Paper/Folia 都必须 `online-mode=false`；Eagler 玩家是离线 UUID。

---

## 1. 拓扑选择（从简到繁）

> **起步不要上完整 B**。自用/单服/小范围用 **S1 = 拓扑 A** 就够：
> Caddy + 一个网关 + 一个 Paper，一个 YAML + 一个 Caddy 站点块。
> 只有需要多服/切服/真实 IP 时才逐级加组件。

### 1.1 拓扑 A（S1）：最简，推荐起步

```text
浏览器 ──wss──▶ Caddy ──ws+X-Real-IP──▶ gateway ──TCP(offline)──▶ Paper
```

- 优点：组件最少、配置最少；单服/自用/小范围完全够用。
- 放弃：多后端/切服、真实 IP（Paper 只看到网关 IP）、皮肤/语音/MOTD、自定义 UUID。
- 何时用：**推荐作为 P1 的起点**；也是本地验证协议的最短路径。
- **S1 最小清单**：
  - 一个 Paper（`online-mode=false` + 白名单 + LoginSecurity）；
  - 一个网关实例、一个 route、anonymous；
  - 一个 Caddy 站点块（复用现有 443）；
  - **不做**：Velocity/HAProxy/PROXY、MOTD、皮肤、语音、令牌、Prometheus。
  - 只支持**一个客户端代**（26.3/v5 或 1.8.8/v3）；两者都要同服才装 ViaVersion。
  - Paper 的 `spigot.yml` 把 `settings.connection-throttle` 设为 `-1`：
    所有 Web 玩家在 Paper 看来都来自网关 IP，默认的 4 秒连接节流会误伤。

### 1.2 拓扑 B（S3）：完整生产（多服 / 真实 IP / Folia）

```text
Java 玩家 ──TCP──▶ HAProxy :25565 ──PROXY v2──▶┐
                                              ├─▶ Velocity 127.0.0.1:25577 ──modern forwarding──▶ Paper/Folia
浏览器 ──wss──▶ Caddy ──ws──▶ gateway ──PROXY v2─┘
```

- 优点：Java/Web 都拿到**真实 IP**；多后端、`/server` 切服、已登录去重、Folia 可用；后端零 Eagler 插件。
- 代价：比 A 多一个 Velocity + 一个 HAProxy；配置面从 2 个文件涨到 5–6 个。
- 何时用：**只有**需要多服/切服、真实 IP/IP 封禁、多节点时才用；单服/自用不要上。

> **为什么必须有 HAProxy（或等价的 TCP 入口）**：Velocity 的 `proxy-protocol` 是**全局**的
> （`[advanced] proxy-protocol = true`）。一旦打开，**所有**连到该 listener 的连接都必须先发
> PROXY 头；Java 玩家不会发，直连就会失败。所以只有两条路：
>
> 1. **HAProxy 统一接 Java**（本拓扑）：Java → HAProxy（发 PROXY）→ Velocity；Web → gateway（发 PROXY）→ Velocity；
> 2. **不开 PROXY**（拓扑 B-lite）：gateway 普通连接 Velocity，Web 玩家在 Velocity 侧只看到 gateway IP，
>    `login-ratelimit` 要设 0，IP 日志/按 IP 限流失真——只适合 P1 验证。
>
> **PROXY v2 的方向**：HAProxy/gateway → Velocity 的 TCP 连接前发 PROXY 头；
> Caddy → gateway 那一段用 `X-Real-IP` + 共享密钥头，不是 PROXY。

### 1.3 成本对比（先选级别，再照对应章节做）

| 级别 | 组成 | 配置面 | 适合 | 放弃 |
|---|---|---|---|---|
| **S0 现状** | Caddy + Velocity/Paper + **EaglerXServer** | 现有 | 零新增，最少改动 | 继续承担 NMS/反射维护；Folia 仅代理层 |
| **S1 最简网关（推荐起步）** | Caddy + gateway + Paper | **1 YAML + 1 Caddy block** | 单服 / 自用 / 小范围 | 多服/切服、真实 IP、皮肤/语音/MOTD/UUID |
| **S2 网关 + Velocity（B-lite）** | + Velocity | + `velocity.toml` | 多服/切服 | 真实 IP（Web 玩家显示网关 IP）；`login-ratelimit=0` |
| **S3 完整 B** | + HAProxy | + `haproxy.cfg` | 真实 IP / IP 封禁 / 多节点 | 组件最多（唯一值得上 HAProxy 的场景） |

> 维护成本大致：S1 是「一个 jar + 一个配置文件」；S3 是「网关 + Velocity + HAProxy + Caddy +
> Paper 的转发密钥」五个面。**能停在 S1 就不要上 S2/S3。**

---

## 2. 端口与暴露面

> **S1（推荐起步）只有三行**：Caddy、gateway、Paper；HAProxy 与 Velocity 是 S2/S3 才出现。

| 组件 | 监听 | 暴露 | 说明 |
|---|---|---|---|
| Caddy | 443（宿主按现网映射，如 18443） | 公网 | TLS + WS 透传 + `X-Real-IP` |
| gateway | `127.0.0.1:8081` | **不发布** | 只给 Caddy；校验共享密钥 |
| HAProxy | `0.0.0.0:25565` | 公网 | Java 入口；只做 TCP + `send-proxy-v2` |
| Velocity | `127.0.0.1:25577` | **不发布** | 只接受 HAProxy/gateway 的 PROXY 连接 |
| Paper/Folia | 内部端口（如 25566/25567） | 不发布 | 只给 Velocity |
| Geyser（可选） | 19132 TCP/UDP | 公网 | 在 Velocity 侧；与本文无关 |

防火墙口径：

- **S1**：公网只开 `443/tcp`（Caddy）；Java 玩家直连 Paper 的端口（如 25565）；8081 只给本机。
- **S3**：公网只开 `443/tcp`（Caddy）、`25565/tcp`（HAProxy/Java）、基岩需要的 UDP/TCP。
- **不要**把 gateway 的 8081、Velocity 的 25577、后端实例端口发布到公网。

---

## 3. 前置条件

| 项 | 要求 |
|---|---|
| JDK | 21+（网关与 Velocity 都用） |
| Caddy | 已能给域名签发证书（现网用 DNS-01；家宽需 `resolvers` + 较长 `propagation_timeout`） |
| Velocity | 4.x；`online-mode=false`；`player-info-forwarding-mode=modern`；只监听环回 |
| HAProxy（拓扑 B） | 2.x；TCP 模式；`send-proxy-v2` |
| Paper/Folia | `online-mode=false`；白名单 + 登录插件（LoginSecurity） |
| DNS | 给 Web 入口单独子域，如 `play.example.com`（不要复用面板/EasyBot 的 Host） |
| ViaVersion | 后端不是客户端同版本时必装（否则只能同版本连） |
| 许可证 | 客户端不分发；玩家自行打开官方页（见 `gateway-design` §17） |

---

## 4. 逐步部署

### 4.1 后端 Paper/Folia

`server.properties`：

```properties
online-mode=false
white-list=true
enforce-whitelist=true
network-compression-threshold=256   # 保持默认即可，网关会处理压缩
```

如果走拓扑 B，`config/paper-global.yml`：

```yaml
proxies:
  velocity:
    enabled: true
    online-mode: false
    secret: '<与 velocity 的 forwarding.secret 完全一致>'
    enabled-force-key-authentication: false
```

插件：ViaVersion + ViaBackwards（老客户端兜底）、LoginSecurity（口令）、白名单管理。
**不需要** EaglerXServer / EaglerXPaper。

自检：Java 客户端（离线）能进服、能执行 `/register` / `/login`。

### 4.2 Velocity（仅拓扑 B/S2-S3；S1 跳过）

`velocity.toml` 关键项：

```toml
bind = "127.0.0.1:25577"      # 只给 HAProxy/gateway，不直接暴露
online-mode = false
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"

[servers]
lobby = "127.0.0.1:25566"
# survival = "127.0.0.1:25567"
try = ["lobby"]

[advanced]
proxy-protocol = true       # 所有入站连接都必须带 PROXY 头（HAProxy/gateway）
login-ratelimit = 3000      # 开 PROXY 后按玩家真实 IP 计
connection-timeout = 5000
read-timeout = 120000       # 大区块/慢网络；默认 30s 偏紧
```

- `forwarding.secret` 与后端 `paper-global.yml` 的 `secret` 一致。
- **不要**把 25577 发布到公网；Java 玩家走 HAProxy，Web 玩家走 gateway。
- 启动后自检：日志 `Listening on /127.0.0.1:25577`；Java 经 HAProxy 能进 lobby。

### 4.2.1 HAProxy（仅拓扑 B/S3；S1/S2 跳过）

`/etc/haproxy/haproxy.cfg` 片段：

```haproxy
frontend mc_java
    bind :25565
    mode tcp
    default_backend velocity

backend velocity
    mode tcp
    server velocity 127.0.0.1:25577 send-proxy-v2
```

```bash
sudo haproxy -c -f /etc/haproxy/haproxy.cfg
sudo systemctl reload haproxy
```

要点：

- HAProxy 只做 TCP 转发 + 在发往 Velocity 的连接前加 **PROXY v2**（`send-proxy-v2`），
  不解析 Minecraft 协议，开销可以忽略。
- 它看到的是 Java 玩家真实 IP，所以 Velocity 侧拿到的也是真实 IP。
- Gateway 直接连 `127.0.0.1:25577` 并自己发 PROXY 头，不经过 HAProxy。
- 若不想引入 HAProxy：用拓扑 B-lite（不启 PROXY）先验证，或把 gateway 同时做 Java TCP 入口
  （不推荐：Java 的可用性会被网关拖累）。

### 4.3 网关

构建（P1 交付后）：

```bash
./gradlew shadowJar
# 产物：build/libs/orzmc-web-gateway-all.jar
```

安装：

```bash
sudo install -d /opt/orzmc/web-gateway /etc/orzmc
sudo cp build/libs/orzmc-web-gateway-all.jar /opt/orzmc/web-gateway/
sudo install -m 600 web-gateway.yaml /etc/orzmc/web-gateway.yaml
sudo install -m 600 web-gateway.env  /etc/orzmc/web-gateway.env   # 放 WEB_GATEWAY_SECRET
```

`/etc/orzmc/web-gateway.yaml`（拓扑 B）：

```yaml
listen: "127.0.0.1:8081"

trusted_proxy:
  header: X-Real-IP
  secret_header: X-OrzWeb-Secret
  secret: "${WEB_GATEWAY_SECRET}"

origins:
  allow: ["https://orzmc.jokerhub.cn"]   # 你自己的客户端页面/站点；按需加
  allow_null: false                       # file:// 客户端默认拒绝

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
  - match: { host: "play.example.com" }
    upstream:
      type: velocity
      addr: "127.0.0.1:25577"
      proxy_protocol: true
      handshake_host: "play.example.com"
    display_name: "大厅"

identity:
  mode: anonymous            # P2 可切 token

metrics:
  listen: "127.0.0.1:9101"
```

拓扑 A 只改 `upstream`：

```yaml
    upstream:
      type: paper
      addr: "127.0.0.1:25565"
      proxy_protocol: false
      handshake_host: "play.example.com"
```

`systemd`（`/etc/systemd/system/orzmc-web-gateway.service`）：

```ini
[Unit]
Description=OrzMC Web Gateway
After=network-online.target
Wants=network-online.target

[Service]
User=orzmc
EnvironmentFile=/etc/orzmc/web-gateway.env
ExecStart=/usr/bin/java -Xms256m -Xmx512m \
  -jar /opt/orzmc/web-gateway/orzmc-web-gateway-all.jar \
  --config /etc/orzmc/web-gateway.yaml
Restart=always
RestartSec=3
LimitNOFILE=65535
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/log/orzmc

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now orzmc-web-gateway
journalctl -u orzmc-web-gateway -f
```

Docker 也可：基础镜像 `eclipse-temurin:21-jre`，挂载配置；如果网关要与 Velocity 走 `127.0.0.1`，
用 `network_mode: host` 或把 `addr` 改成 compose 服务名。

### 4.4 Caddy

```caddyfile
play.example.com {
    # 如果全局已有 tls 配置，这里可省略
    tls {
        dns cloudflare {env.CLOUDFLARE_API_TOKEN}
    }

    reverse_proxy 127.0.0.1:8081 {
        header_up X-Real-IP {remote_host}
        header_up X-OrzWeb-Secret {env.WEB_GATEWAY_SECRET}
        flush_interval -1
    }
}
```

要点：

- **不要**对 WS 做 gzip/缓冲；`flush_interval -1` 保证长连接不被攒。
- Caddy 会自动透传 `Upgrade`；不需要额外 matcher。
- `X-OrzWeb-Secret` 必须与网关 `trusted_proxy.secret` 一致；Caddy 进程要有该环境变量。
- 非 WS 的普通 GET 也会到网关，网关应返回 `426 Upgrade Required`/`400`，不要回 200 页面。
- 证书：家宽/部分网络需 `resolvers 1.1.1.1 8.8.8.8` + `propagation_timeout 180s`（与现网一致）。

```bash
caddy validate --config /etc/caddy/Caddyfile
caddy reload --config /etc/caddy/Caddyfile
```

### 4.5 DNS 与防火墙

1. `play.example.com` A/AAAA 指向入口（或 Cloudflare 代理；注意 WS 要允许）。
2. **S1**：路由器只转发 443（宿主映射）与 Java 的 Paper 端口（通常 25565）；**不转发 8081**。
   **S3**：额外把 25565 指向 HAProxy；**不转发 25577/8081**。
3. 若用云防火墙，按上面口径放行；后端实例端口（S3）不开放。

---

## 5. 客户端入口

| 客户端 | 地址 | 说明 |
|---|---|---|
| Java 玩家 | `<域名或 IP>:25565` | 走 HAProxy；与改造前一致（端口不变） |
| Eaglercraft 26.x | `wss://play.example.com/` | 只认 wss；官方页直连粘贴 |
| Eaglercraft 1.8.8 | `wss://play.example.com/` | 页面是 https 时自动 wss |
| 路径路由 | `wss://play.example.com/s/survival` | 若配置了 path 路由 |

玩家流程：打开官方客户端 → 多人游戏 → 直连 → 粘贴 `wss://...` → Join。
（官网卡片已有「在浏览器中玩」+ 复制地址，见 `site`。）

---

## 6. 上线顺序（照这个顺序排障）

> **S1 只做 1 → 4 → 5 → 6 → 7 → 8/9/10**（跳过 2/3 的 Velocity/HAProxy）。

1. **后端**：Paper/Folia 启动、`Done`，Java 离线客户端能进（直连后端端口）。
2. **Velocity**：`bind=127.0.0.1:25577`，启动日志 `Listening`。
3. **HAProxy**：`25565 → 127.0.0.1:25577 send-proxy-v2`；Java 客户端从 25565 能进 lobby，
   Velocity 日志里是玩家真实 IP。
4. **网关本地**：`curl -sS http://127.0.0.1:9101/healthz`；日志显示监听 8081。
5. **网关直连探针**（绕过 Caddy，仅本机）：
   `python3 deploy/tests/eaglercraft_probe.py --host 127.0.0.1 --port 8081 --real-ip 127.0.0.1`
   （P1 会新增共享密钥头参数；直连必须带密钥与真实 IP，否则网关按设计拒绝。）
6. **Caddy**：`caddy reload`；外部探针：
   `python3 deploy/tests/eaglercraft_probe.py --host play.example.com --port 443 --tls`
7. **浏览器**：1.8.8 与 26.x 各进一次；移动、聊天、`/tp`。
8. **身份**：白名单 + `/register`/`/login`；同名双开应被代理拒绝。
9. **真实 IP**：Velocity/后端日志里应是玩家公网 IP，不是网关/HAProxy 的内网 IP。
10. **灰度**：先 1–3 个白名单玩家，观察 24 h，再放开。

---

## 7. 验收清单

| # | 项 | 通过标准 |
|---:|---|---|
| 1 | TLS/WS | `curl -I https://play.example.com/` 返回 4xx（网关的 426/400），不是 502 |
| 2 | 握手 | 探针输出 `eagler_protocol=5`（或 3）、`minecraft_protocol=<客户端协议>` |
| 3 | 进服 | 客户端出现 HUD；后端日志 `joined the game` |
| 4 | 玩法 | 移动、聊天、放置/破坏、`/tp`、断线重连 |
| 5 | 真实 IP | Velocity/后端看到玩家公网 IP |
| 6 | 身份 | 同名双开被拒；错误口令被拒；白名单外进不来 |
| 7 | 反代安全 | 不带 `X-OrzWeb-Secret` 直连 8081 被拒 |
| 8 | 版本 | 客户端版本 ↔ 后端协议匹配（或 ViaVersion 生效） |
| 9 | Folia | 跨区域跑动 10 分钟无异常（拓扑 B） |
| 10 | 回滚 | 停网关、Caddy 切回旧上游，5 分钟内恢复 |

---

## 8. 故障排查

| 现象 | 可能原因 | 处置 |
|---|---|---|
| Caddy 502 | 网关没起/端口错/只监听 127.0.0.1 但 Caddy 在容器 | 查 `journalctl`、`ss -lntp`；容器网络改 host 或服务名 |
| `Connection closed during Eagler server version` | Caddy 缓冲/未透传 Upgrade；上游连到了非网关端口 | `flush_interval -1`；确认 upstream 8081 |
| 网关直接关连接 | 缺 `X-Real-IP` 或共享密钥不匹配 | 查 Caddy `header_up` 与环境变量 |
| Velocity 报 PROXY 错误 / 直接断 | gateway/HAProxy 是否发 PROXY 与 Velocity `proxy-protocol` 不一致 | 三处保持一致：Velocity 开、HAProxy `send-proxy-v2`、gateway `proxy_protocol: true` |
| Java 连 25565 进不去，Web 正常 | HAProxy 没起 / 25565 没指向 HAProxy / Velocity 只监听 127.0.0.1 | 查 HAProxy 监听与 `send-proxy-v2`；Java 不要直连 25577 |
| Java 玩家 IP 全是网关/内网 | HAProxy 后端漏了 `send-proxy-v2`，或 Velocity 关了 `proxy-protocol` | 补上并 reload |
| `Your client does not support online mode` | Velocity/Paper `online-mode=true` | 整链改 false |
| 所有 Web 玩家 IP 相同 + 登录被限流 | 拓扑 B-lite（没开 PROXY）或 gateway 没发 PROXY | 上 HAProxy + PROXY；临时把 `login-ratelimit=0` |
| 版本不匹配被踢 | 客户端协议 ≠ 后端，且没装 ViaVersion | 装 ViaVersion/对齐版本 |
| 玩几分钟 1006 | 客户端渲染/后台节流/网络代理；不一定是部署问题 | 客户端硬件加速、前台、关 TUN；服务端 `read-timeout` |
| 没有皮肤/语音/MOTD | P1 网关未实现这些扩展 | 预期；见 `gateway-design` §2.1 / P2 |
| 服务器列表显示离线 | 未实现 MOTD 查询（P2） | 直连仍可用；P2 补 Server List Ping |

---

## 9. 与现网 EaglerXServer 并存 / 回滚

- 现网：`ENABLE_EAGLER=true` → Velocity 装 EaglerXServer，Caddy 反代到 `orzmc-velocity:25565`。
- 新方案：Caddy 新增 `play.example.com` → 网关 8081；旧域名/旧入口保持不动。
- 灰度：按域名或按玩家（不同子域）分流；观察指标与日志。
- **只回滚 Web 网关**：停网关，Caddy 的 `play.example.com` 切回 `orzmc-velocity:25565`
  （EaglerXServer）；HAProxy/Velocity 保持不动，Java 玩家不受影响。
- **整体回退到改造前**：停网关 → 移除 Caddy 的 Web 站点块 → 停 HAProxy →
  Velocity 改回 `bind=0.0.0.0:25565`、`proxy-protocol=false` → 装回 EaglerXServer →
  验证 Java 直连 + 旧 Web 入口。

---

## 10. 当前限制（P1）

- 只支持 Eagler 握手 **v3 + v5**；1.5.2 不支持。
- **皮肤/披风、语音、MOTD 查询、通知/WebView、后端 RPC 未实现**（P2+）。
- **自定义 UUID 未实现**：Web 玩家是离线 UUID；如要绑定已有 Java 账号，需加 Velocity/Paper 小插件。
- **真实 IP 依赖拓扑 B 的 HAProxy**：若走 B-lite（不开 PROXY），Web 玩家在 Velocity/后端只显示
  gateway IP，需把 `login-ratelimit` 设为 0，且 IP 封禁/日志会失真。
- 配置键名是草案，P1 实现时可能微调；以 `webmc` 发布说明为准。
- 客户端本体不分发；玩家使用官方页/自备客户端（合规边界见 `gateway-design` §17）。

> 一句话：**P1 的部署目标 = Web（Caddy → 网关 → Velocity）与 Java（HAProxy → Velocity）
> 同时跑通 1.8.8 与 26.x，后端零 Eagler 插件；皮肤/语音/MOTD/UUID 在后续阶段补。**
