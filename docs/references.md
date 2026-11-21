# 参考与证据索引

> 目的：让后来者能在**不重做调研**的情况下复核本文档的每条关键结论。
> 每条给出「证明了什么 → 去哪看 → 怎么复现」。
>
> 最后核对：2026-11-22。

---

## 1. 上游（服务端参考实现）

### 1.1 `lax1dude/eaglerxserver`（EaglerXServer 1.1.1）

- 仓库：<https://github.com/lax1dude/eaglerxserver>（`main`，2026-08-01 发布 1.1.1）
- 许可：All Rights Reserved（二进制可用；改源码/再分发需谨慎）
- 结论：**唯一完整的 Eagler 服务端实现**；支持 1.5.2/1.8/1.12.2 + v5/26.x；
  单 JAR 装 Spigot/BungeeCord/Velocity；Bukkit >1.17 不维护、不支持 Folia。

| 证明了什么 | 文件 / 符号 |
|---|---|
| 首帧识别与版本协商（`0x01 0x02`；v1 为 `0x01 0x01`） | `core/.../pipeline/WebSocketEaglerInitialHandler.java`（`decode` / `handleEaglerConnection`） |
| WS 帧只做「解包 → ByteBuf」，不做长度前缀 | `core/.../pipeline/WebSocketEaglerFrameCodec.java` |
| Eagler 连接上**移除**原版 frame decoder/encoder（所以 1 帧 = 1 包，无长度前缀） | `core/.../pipeline/PipelineTransformer.java`（`VANILLA_FRAME_DECODERS`） |
| WS 压缩：`deflate-frame` + `permessage-deflate`，默认 level 6 | 同上（构造函数）；`CONFIG.md` 的 `http_websocket_compression_level` |
| 握手包 id / 错误码 | `core/.../handshake/HandshakePacketTypes.java` |
| v3/v4/v5 的字段布局 | `HandshakerV3.java`、`HandshakerV4.java`、`HandshakerV5.java` |
| 离线 UUID / profile data / 认证 | `HandshakerInstance.java`（`OFFLINE_PLAYER_BYTES`、`handlePacketProfileData`） |
| 登录后端的顺序（Handshake + LoginStart → 等 LoginSuccess → ALLOW_LOGIN） | `core/.../handshake/VanillaInitializer.java` |
| 忽略后端 `Set Compression`（Eagler 段不压缩） | `VanillaInitializer.java`（`case 0x03: break;`） |
| ≥764 代客户端发 Login Acknowledged | `WebSocketEaglerInitialHandler.java`（`enterPlayState` → `writeVarInt(0x03)`） |
| 代理侧禁用 MC 压缩（反射 no-op） | `core-platform-velocity/.../VelocityUnsafe.java`（`injectCompressionDisable`）；`core-platform-bungee/.../BungeeUnsafe.java` |
| MOTD 查询（`Accept: motd` → JSON） | `core/.../pipeline/WebSocketQueryHandler.java`；`core/.../query/MOTDConnectionWrapper.java` |
| 能力位定义 | `api/.../EnumCapabilityType.java`、`EnumCapabilitySpec.java` |
| 配置语义（协议上限、认证默认关、帧上限、视野等） | `CONFIG.md`（尤其 `protocols.*`、`enable_authentication_events`、`http_websocket_*`） |
| 支持范围/安装说明 | `README.md` |

**快速抓取**：

```bash
curl -sSL https://codeload.github.com/lax1dude/eaglerxserver/tar.gz/refs/heads/main \
  -o /tmp/exs.tgz && mkdir -p /tmp/exs && tar xzf /tmp/exs.tgz -C /tmp/exs --strip-components=1
grep -rn "PROTOCOL_CLIENT_VERSION\|VANILLA_FRAME_DECODERS\|injectCompressionDisable" /tmp/exs
```

### 1.2 `lax1dude/eagl3rxbungee`

- <https://github.com/lax1dude/eagl3rxbungee> —— 旧版 EaglerXBungee/EaglerXVelocity；
  历史实现，现已被 EaglerXServer 取代。可用于对照「注入 listener」的旧做法。

---

## 2. 客户端源码（Gen 1：1.8.8）

> 官方源码在 `git.eaglercraft.rip`；GitHub 有多个镜像。我们用
> `3kh0/eaglercraft-1.8`（注明克隆自官方，`client_version = u35`）。
> **许可证同样是 All Rights Reserved**——只用于阅读协议，不要分发/改包。

- 仓库：<https://github.com/3kh0/eaglercraft-1.8>
- 复现：`curl -sSL https://codeload.github.com/3kh0/eaglercraft-1.8/tar.gz/refs/heads/main -o ec.tgz`

| 证明了什么 | 文件 |
|---|---|
| 首帧写 `0x01`、`0x02`、版本表、MC 47、brand、auth、用户名 | `sources/main/java/net/lax1dude/eaglercraft/v1_8/socket/ConnectionHandshake.java` |
| 客户端只支持 v2/v3、MC 47；`ALLOW_LOGIN` 后发 skin/cape/finish | 同上 |
| 认证方式（EAGLER_SHA256 / AUTHME / PLAINTEXT）与盐算法 | 同上 + `HandshakePacketTypes.java` |
| 每个 MC 包一个 WS 消息（`VarInt id + data`，无长度前缀） | `.../socket/EaglercraftNetworkManager.java`（`writeVarIntToBuffer` → `writePlayPacket`） |
| `setCompressionTreshold` 直接抛异常（不支持 MC 压缩） | 同上 |
| WS 发送/接收、文本 `BLOCKED`/`LOCKED` | `sources/teavm/java/.../internal/PlatformNetworking.java` |
| `ws://`/`wss://` 自动补全、端口默认、`requireSSL()` 由页面 https 决定 | `.../socket/AddressResolver.java`；`.../internal/PlatformRuntime.java` |
| `eaglercraftXOpts`（`servers`、`joinServer`、`relays`）、`?server=` | `sources/setup/workspace_template/javascript/index.html` |
| `joinServer`/`relays` 的读取 | `sources/teavm/java/.../teavm/TeaVMClientConfigAdapter.java` |
| MOTD 查询：WS + 文本 `Accept: motd` + JSON/PNG | `sources/teavm/java/.../teavm/TeaVMServerQuery.java` |
| 单文件构建/打包说明 | `README.md`、`CompileLatestClient.*`、`buildtools/production-index.html` |

**快速验证**：

```bash
grep -n "writeByte(HandshakePacketTypes.PROTOCOL_CLIENT_VERSION)" -A20 \
  /tmp/ec/sources/main/java/net/lax1dude/eaglercraft/v1_8/socket/ConnectionHandshake.java
grep -n "writeVarIntToBuffer(i)" -A12 \
  /tmp/ec/sources/main/java/net/lax1dude/eaglercraft/v1_8/socket/EaglercraftNetworkManager.java
grep -rn "joinServer\|servers" /tmp/ec/sources/setup/workspace_template/javascript/index.html
```

### 2.1 其它 Gen 1 源码镜像（备用）

- `Eaglercraft-Archive/EaglercraftX-1_8-workspace`
- `damiananderson-max/Eaglercraftx-1.8.8-src-Archive`
- `notsharingrealname/vaperclient`

> 这些镜像内容相近；以官方 git 为准。不要把镜像里附带的构建产物当官方分发。

### 2.2 Gen 2（26.x）

- 客户端本体**未开源**；我们依赖 `deploy` 的**实测**（首帧、协议 777、TLS 行为）。
- 工作区（社区）：`kingjoeishere/Eaglercraft-26.2-Workspace` 等；仅作参考，
  未验证是否与官方 26.3 一致。

---

## 3. 我们自己的实测证据（`OrzMCDeploy`）

以下全部来自 `OrzMC/OrzMCDeploy` 仓库，2026-11-12 ~ 2026-11-15，测试服真机/无头 Chrome：

| 结论 | 文档/位置 |
|---|---|
| 26.3 = MC 26.3（`protocol_version=777`）、Eagler 握手 v5 | `deploy/docs/eaglercraft.md` §2.1、§2.4 |
| 首帧实测：`eagler_protocols=[3,4,5]`、`minecraft_protocols=[777]`、`auth_username` | 同上 §2.4；脚本 `deploy/tests/eaglercraft_probe.py --sniff` |
| 26.3 只认 `wss`；无 `Sec-WebSocket-Protocol`；`file://` 下 `Origin: null` | 同上 §2.4 |
| 桥接层不支持 online-mode；代理/后端都必须 offline | 同上 §1、§3 |
| EagerXServer 默认不校验口令（`enable_authentication_events=false`） | 同上 §3 |
| Velocity + EaglerXServer 可用；Folia 可用；EaglerXPaper 不支持 Folia | 同上 §5、§6 |
| 插件矩阵实测（LuckPerms/EzShops/Via/Grim/SkinsRestorer/LoginSecurity…） | `deploy/docs/eaglercraft-deploy.md` §7.5 |
| 1006 掉线的根因是客户端渲染/GC 卡顿导致 keepalive 超时 | 同上 §13 |
| Caddy 片段要求：`flush_interval -1`、`X-Real-IP`、不缓冲 | 同上 §5 |
| 四拓扑实跑（java-only/velocity/eagler-direct/eagler-proxy） | 同上 §12.1 |

**复现探针**：

```bash
# 握手 + 协商（经反代）
python3 deploy/tests/eaglercraft_probe.py --host <域名> --port <WSS端口> --tls
# 登录 + UUID 一致性
python3 deploy/tests/eaglercraft_probe.py --host <域名> --port <WSS端口> --tls --login <名字>
# 抓首帧（对假服务器）
python3 deploy/tests/eaglercraft_probe.py --sniff
```

---

## 4. 相关标准/资料

| 资料 | 用途 |
|---|---|
| <https://wiki.vg/Protocol>（及历史版本） | MC 登录/压缩/configuration 字段布局 |
| <https://wiki.vg/Server_List_Ping> | MOTD 查询（P2） |
| RFC 6455（WebSocket）、RFC 7692（permessage-deflate） | 传输层 |
| Velocity 源码 `LoginSessionHandler.createForwardingData` | 说明 modern forwarding 由 **Velocity 自己做**，网关不需要实现（我们只作为客户端连 Velocity） |
| <https://github.com/PaperMC/Velocity> `proxy-protocol` | 真实 IP（PROXY v2） |

> Velocity 的 forwarding 细节（HMAC-SHA256 over `[VarInt version][addr][UUID][name][properties]`）
> 记录在此仅作背景：**我们的架构里由 Velocity 负责，不由网关实现**。

---

## 5. 同类实现（对照/借鉴）

| 项目 | 方向 | 语言 | 备注 |
|---|---|---|---|
| `twastinfg/EaglercraftX-Vanilla-Proxy` | Eagler → 1.8.9 服 | JS | 最接近的独立代理；用 `minecraft-protocol` 做后端；可读其握手/帧处理 |
| `LoHJG/Vanilla2Eagler` | 反向（Java 1.8 → Eagler 服） | JS | 反向桥，参考价值有限 |
| `dragon731012/Eagler-ViaProxy` | Eagler + ViaProxy | Shell | 组合式（ViaProxy 做翻译） |
| `MercuryWorkshop/wispcraft` | 浏览器 → 正版服（Wisp/Direct Sockets + epoxy-tls） | TS | 另一条生态路线；需客户端补丁 |
| Eaglercraft-Templates/Eaglercraft-Server-Paper | 一体化 1.12.2 服务端 | — | 现成 EaglerXServer 部署参照 |

> 同类项目普遍缺少：v5/26.x、Velocity 真实 IP、路由、限流、指标。
> 这正是本仓网关的差异化空间。

---

## 6. 官方分发与许可证（务必阅读）

| 项 | 事实 |
|---|---|
| 客户端分发 | 官方下载：`https://cdn.eaglercraft.net/objects/dl/26.3/…zip`；官方页 `https://eaglercraft.com/play?version=26.3` |
| 官方页可嵌性 | `X-Frame-Options: DENY`，不能 iframe |
| 客户端许可证 | **All Rights Reserved**（1.8.8 工作区 LICENSE 明确写） |
| Mojang EULA | Eaglercraft 是第三方反编译移植，**不符合** Mojang EULA |
| 本站现状 | `site` 只提供连接方式（`wss://` 地址 + 打开官方客户端），**不分发客户端本体** |

→ 任何「自建客户端页面/预置服务器」的方案，先过许可证评审（`gateway-design` D3）。

---

## 7. 本次调研的复现清单（从零到能开始实现）

```bash
# 1) 参考实现源码
curl -sSL https://codeload.github.com/lax1dude/eaglerxserver/tar.gz/refs/heads/main | tar xz -C /tmp

# 2) 1.8.8 客户端源码（只读）
curl -sSL https://codeload.github.com/3kh0/eaglercraft-1.8/tar.gz/refs/heads/main | tar xz -C /tmp

# 3) 我们的实测文档
git -C deploy pull --ff-only
sed -n '1,220p' deploy/docs/eaglercraft.md

# 4) 复跑握手探针（需要一台 Eagler 服务端）
python3 deploy/tests/eaglercraft_probe.py --host <域名> --port <端口> --tls --sniff
```

**注意**：第 1、2 步的源码许可都是 All Rights Reserved；只用于阅读与内部实现参考，
不要把代码/二进制复制进本仓或对外分发。
