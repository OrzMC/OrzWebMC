# Eaglercraft 实现原理拆解（黑盒 → 白盒）

> **定位**：把「Eaglercraft 网页客户端如何连进 Java 服务器」拆到**可复现实现的粒度**。
> 本文讲**为什么/怎么工作**；要实现的东西见 [protocol-spec](protocol-spec.md)，
> 落地方案见 [gateway-design](gateway-design.md)。
>
> **状态**：P0 调研完成（2026-11-22）。证据等级：**[实测]** / **[源码]** / **[推断]**。
> **读者**：要评审或实现 Web 网关的人。

---

## 1. 结论先行

Eaglercraft 并不是「仿制版 Minecraft」，也不是「把服务器也搬到浏览器」。它是：

```text
Java 客户端源码（真实 Minecraft）
   ├─ 渲染/输入/音频/文件  → 替换为浏览器实现（WebGL2 / Gamepad / IndexedDB …）
   ├─ 游戏逻辑/世界/实体    → 原样保留（编译到 JS 或 Wasm）
   └─ 网络传输             → 从 TCP 换成 WebSocket，并加一层 Eagler 私有握手
```

因此**服务端仍然是原版 Java 服务器**。浏览器与服务器之间需要一个**协议桥**：

| 层 | 浏览器侧 | 桥接侧 | Java 服务器侧 |
|---|---|---|---|
| 传输 | `wss://` WebSocket（二进制帧） | 终结 WS | `TCP` |
| 帧格式 | **1 个 WS 帧 = 1 个 MC 包**（包 id + 字段，**不带** VarInt 长度前缀） | 加/去长度前缀 | `VarInt 长度 + 包` |
| 压缩 | WebSocket `permessage-deflate`（帧级 zlib） | 解/压帧 | MC 自己的 zlib（Set Compression 之后） |
| 握手 | Eagler 私有握手（v3 / v5） | 翻译成普通 MC 握手 + 离线登录 | 普通 MC 登录 |
| 游戏 | 原始 MC 包（1.8.8 / 1.12.2 / 26.x） | **原样中继** | 原始 MC 包 |

一句话：**桥接层只需要懂「Eagler 握手 + 登录 + 帧/压缩」，游戏阶段的包可以完全不解析。**

> 这条结论由两端源码互相印证：
> 客户端发送时先写 `VarInt 包 id` 再写包体，然后把整段作为一个 WS 消息发出
> （`EaglercraftNetworkManager` → `PlatformNetworking.writePlayPacket`）**[源码]**；
> 服务端在 Eagler 连接上**移除**了原版的 frame decoder/encoder 与压缩
> （`PipelineTransformer.VANILLA_FRAME_DECODERS`）**[源码]**。

---

## 2. 两代客户端（别把版本号搞混）

| 代 | 版本 | 编译方式 | Eagler 握手 | MC 协议 | 备注 |
|---|---|---|---|---|---|
| **Gen 1** | 1.5.2 / 1.8.8（u26/u35…）/ 1.12.2 | TeaVM：Java → JS（+WebGL2） | v1 / v2 / v3（1.12.2 可能到 v4） | 47 / 340 | 长期主流；源码/工作区可获取 |
| **Gen 2** | 26.x（如 26.3） | TeaVM **Wasm-GC** 后端 | **v5** | 与 MC 版本同号（26.3 = **777**） | 2026 年起官方主推；客户端本体未开源 |

**Gen 2 的关键事实（来自 `deploy` 实测，2026-11）** **[实测]**

- 26.3 = 真正的 Minecraft 26.3（`protocol_version=777`），只是平台/渲染/传输层被替换。
- 首帧声明 `eagler_protocols=[3,4,5]`、`minecraft_protocols=[777]`，`auth_username` 才是最终用户名。
- 客户端自带微软登录 / AES / Direct Sockets 的实现，但**经 EaglerX 桥接时用不上**（桥接向后端发离线登录）。
- 默认只尝试 `wss://`（明文 `ws://` 失败）；升级请求**不带** `Sec-WebSocket-Protocol`；本地文件场景 `Origin: null`。
- 品牌字符串滞后（握手报 `Eaglercraft 26.2`），**不要用 brand 判版本**。

> 对我们的意义：**同时支持 v3 与 v5 就能覆盖两代**（v5 客户端必带 v3/v4/v5 列表，v3 客户端带 v2/v3）。
> v1（1.5.2）需要额外的 1.5→1.8 包翻译（`EaglerXRewind` 的角色），P1 不做。

---

## 3. 传输层：WebSocket 上的 Minecraft

### 3.1 帧格式（最容易踩错的一点）

```text
WS binary message  =  [ MC 包 id (VarInt) ][ 包体字段 ... ]
                        ↑ 没有 VarInt 长度前缀，也没有 MC 的压缩头
```

- **客户端 → 服务端**：每个 MC 包单独发一个 WS 消息；客户端不是流式写 socket，而是
  「一个包一次 `send()`」**[源码：`PlatformNetworking.writePlayPacket` / `nativeBinarySend`]**。
- **服务端 → 客户端**：桥接层每写一个包，就被包成一个 `BinaryWebSocketFrame`
  **[源码：`WebSocketEaglerFrameCodec.write`]**；游戏阶段内部仍按「一个包一次 write」处理。
- 所以 **1 帧 = 1 包**，不能把多个包粘进一个帧（原版实现会按单包解析）。

### 3.2 压缩

- **MC 的 zlib 压缩在 Eagler 连接上被禁用**：
  - 客户端：`setCompressionTreshold()` 直接抛 `CompressionNotSupportedException` **[源码]**；
  - 服务端：Velocity/Bungee 侧把 `setCompressionThreshold` 变成 no-op（注释写着 “FUCK YOU!!!”）**[源码]**；
  - 因此即使后端给代理下发了 `Set Compression`，Eagler 这一段也不会启用 MC 压缩。
- 带宽靠 **WebSocket 扩展**压缩：
  - 服务端同时注册 **`deflate-frame`** 与 **`permessage-deflate`**（Netty 支持哪个用哪个），
    默认 zlib level **6**，`server_no_context_takeover=false`（保留上下文，压缩率更好）**[源码：`PipelineTransformer`]**。
  - 浏览器只会协商 `permessage-deflate`（`deflate-frame` 是 Netty 的历史扩展）**[推断，浏览器行为]**。
  - 反向代理**不能**再对 WS 做 gzip；也不能缓冲/改写 WS 帧。
- 26.3 客户端默认**单线程**、无 `SharedArrayBuffer`/`Atomics`/`crossOriginIsolated`
  → 不需要 COOP/COEP 隔离头 **[实测，deploy]**。

### 3.3 连接、TLS 与大小限制

| 项 | 事实 |
|---|---|
| URL | `wss://<host>[:port][/path]`；26.x 只认 TLS；1.8.8 在页面为 `https` 时也强制 `wss`（`requireSSL()`），`file://` 下允许 `ws://` **[源码]** |
| WS 子协议 | 不使用（无 `Sec-WebSocket-Protocol`）**[实测 26.3]** |
| 帧/消息上限 | 服务端默认 `http_websocket_max_frame_length=131071`、`fragment_size=65536`（大包拆成多个 WS fragment，浏览器会自动重组）**[源码：CONFIG.md]** |
| 文本帧 | 仅用于查询（MOTD）与限流信号（`BLOCKED` / `LOCKED`）；在游戏连接上收到文本帧 = 关闭 **[源码]** |
| 空闲 | 服务端可注入 WS ping handler；浏览器自动回 pong；最终仍由 MC keepalive 决定是否踢人 **[源码 + 实测]** |

> 网关实现注意：必须支持**分片重组**与**大消息发送**（区块包可达数百 KB），
> 并且要能把「一个 MC 包」写成「一个 WS 消息」（必要时由 WS 库自动分片）。

---

## 4. 握手协议（Eagler 私有层）

握手发生在 MC 登录**之前**，跑在同一组 WS 二进制帧里。完整字节级定义见
[protocol-spec §3](protocol-spec.md#3-握手帧字节级定义)；这里讲结构与语义。

### 4.1 状态机

```text
客户端                                          服务端
  │ 0x01 CLIENT_VERSION（版本表/brand/是否 auth/用户名） │
  ├──────────────────────────────────────────────▶│
  │                                               │ 选 eagler 协议 + MC 协议
  │ 0x02 SERVER_VERSION（选定版本/brand/auth 方式）  │
  │◀──────────────────────────────────────────────┤
  │ 0x04 REQUEST_LOGIN（用户名/目标服/口令/cookie/caps）│
  ├──────────────────────────────────────────────▶│
  │                                               │ 连后端、做离线登录（§5）
  │ 0x05 ALLOW_LOGIN（最终用户名 + 离线 UUID + caps） │
  │◀──────────────────────────────────────────────┤
  │ 0x07 PROFILE_DATA × N（skin/cape/update_cert）  │
  ├──────────────────────────────────────────────▶│
  │ 0x08 FINISH_LOGIN                             │
  ├──────────────────────────────────────────────▶│
  │ 0x09 SERVER_FINISH_LOGIN                      │
  │◀──────────────────────────────────────────────┤
  │ ============ 进入 PLAY：原始 MC 包双向直通 ============
```

失败路径：`0x06 DENY_LOGIN`（带原因）、`0xFF SERVER_ERROR`（带错误码 + 消息）、
`0x03 VERSION_MISMATCH`（带服务端支持的 eagler/MC 版本表）、`0x0A REDIRECT_TO`（让客户端换地址）。

### 4.2 版本差异（只列会影响网关的）

| 版本 | 引入 | 差异要点 |
|---|---|---|
| v1 | 1.5.2 | 首帧是 `0x01 0x01` + `u8` MC 协议 + brand + version（没有版本表）；需要 1.5→1.8 的包翻译，**不支持** |
| v2 | 1.8.8 早期 | 固定支持 eagler v2 + MC 47；`DENY_LOGIN` 消息用 `u8` 长度 |
| v3 | 主流 1.8.8 | 增加认证方式（`authMethod` + salt）与 profile data；`DENY_LOGIN`/错误消息用 `u16` 长度；`ALLOW_LOGIN` 仍只有 name+UUID |
| v4 | 过渡 | `REQUEST_LOGIN` 增加 cookie；能力位由服务端 fallback 决定；`ALLOW_LOGIN` 仍为 name+UUID |
| v5 | 26.x | `REQUEST_LOGIN` 增加标准能力位 + 能力版本 + 扩展能力（UUID 表）；`SERVER_VERSION` 增加 `nicknameSelection`；`ALLOW_LOGIN` 增加能力表；支持现代 MC 协议（777 等） |

> **网关最小集：v3 + v5**。对 26.x 客户端选 5；对 1.8.8/1.12.2 客户端选 3（它们都声明了 3）。
> 其余版本回 `VERSION_MISMATCH`，消息提示升级客户端。

### 4.3 认证（可选，默认关）

- `SERVER_VERSION` 里的 `authMethod`：
  - `0` 无认证（**我们的默认**）
  - `1` EAGLER_SHA256（客户端二次 SHA-256 + 固定盐）
  - `2` AUTHME_SHA256（AuthMe 兼容哈希）
  - `0xFF` 明文（客户端需要用户确认，不推荐）
- 参考实现默认 `enable_authentication_events=false`，**不校验任何口令**；
  真正的门是「白名单 + 服内登录插件」**[源码 + 实测，deploy §7.5]**。
- 注意：**v5 的用户名取自握手包的 `auth_username`**，不是后面登录包里的名字；
  名字合法性 `^[A-Za-z0-9_]{3,16}$` **[源码]**。

### 4.4 能力位（capabilities）

标准能力（bit = `1 << id`）**[源码：`EnumCapabilityType`]**：

| id | 能力 | 说明 |
|---:|---|---|
| 0 | UPDATE | 客户端自更新/证书 |
| 1 | VOICE | Eagler 语音（WebRTC 信令） |
| 2 | REDIRECT | 登录期重定向到另一个地址 |
| 3 | NOTIFICATION | 游戏内通知徽章 |
| 4 | PAUSE_MENU | 自定义暂停菜单 |
| 5 | WEBVIEW | 内嵌网页窗口 |
| 6 | COOKIE | 持久化 cookie（跨连接会话） |
| 7 | EAGLER_IP | 透传真实 IP |
| 8 | OPTIFINE_CIT | CIT 材质 |

> **给 0 是合法的**：客户端会退化为默认皮肤、无语音、无重定向。P1 建议只声明我们真的实现的位。
> v5 的 `ALLOW_LOGIN` 会逐位回版本号；v3/v4 没有这个字段，能力由服务端 fallback 决定。

### 4.5 登录期数据（profile data）

`0x07 PROFILE_DATA` 是 `[u8 count][u8 keyLen][key][u16 dataLen][data]` 的批量包。
1.8.8 客户端在收到 `ALLOW_LOGIN` 后**固定发送**：

| key | 内容 | 用途 |
|---|---|---|
| `skin_v1` | 皮肤包（PNG/EPK 片段） | 服务端缓存后转发给其他 Eagler 玩家 |
| `cape_v1` | 披风包 | 同上 |
| `update_cert_v1` | 客户端签名证书（可选） | 服务端校验客户端来源 |

服务端把它们存到 `profileDatas`，然后通过 Eagler 的自定义插件消息（`EAG|...` 通道）
分发给其他玩家。**如果网关不实现，玩家之间看到默认皮肤**——不影响进服与玩法（P1 接受）。

### 4.6 重定向与 cookie

- `0x0A REDIRECT_TO`（v4+，需 REDIRECT 能力）：服务端让客户端断开并连到新地址。
  可用于「把玩家从入口网关甩到具体节点」。
- cookie（v4+，需 COOKIE 能力）：客户端在 `REQUEST_LOGIN` 里带上上次服务端下发的 cookie，
  服务端可据此做「免登录/会话粘滞」。**默认客户端没有 cookie**。
- 两者都不是 P1 必需；设计上给路由/多节点留了口子。

---

## 5. 登录时序：浏览器 ↔ 网关 ↔ 后端

这是**整个桥接最需要小心的地方**。参考实现（EaglerXServer）的做法：

```text
Eagler 握手到 REQUEST_LOGIN
        │
        ▼
  连后端 TCP，发普通 MC 握手（nextState=2）+ Login Start（离线）
        │
        ├─ 后端回 Encryption Request（在线模式）→ 给客户端 0xFF「不支持在线模式」
        ├─ 后端回 Disconnect                    → 转成 Eagler DENY/ERROR
        ├─ 后端回 Set Compression               → 只对后端链路生效，不转发给客户端
        └─ 后端回 Login Success                 → 解析 name/UUID
                │
                ▼
        给客户端 ALLOW_LOGIN（name + 离线 UUID [+ caps]）
                │
                ├─ 客户端发 profile data / FINISH_LOGIN
                │   （期间后端可能已经在发 configuration/play 包 → 先缓冲）
                ▼
        给客户端 SERVER_FINISH_LOGIN → 进入 PLAY，把缓冲包 flush 给客户端
```

要点（都有源码依据）：

1. **先连后端，再给客户端 `ALLOW_LOGIN`**：这样后端踢人（白名单/满员/版本不符）时，
   可以把真实原因转给浏览器，而不是先让玩家进服再断线。
2. **`Set Compression` 只影响后端链路**：网关要么实现 zlib（推荐），要么把后端
   `network-compression-threshold` 设为 `-1`。参考实现选择「代理侧禁用压缩」。
3. **现代协议要替客户端发 `Login Acknowledged`**：MC ≥ 764（1.20.2）在 `Login Success`
   之后客户端必须回 `0x03 Login Acknowledged`，服务端才进入 configuration 状态。
   参考实现在 `enterPlayState()` 里**主动注入**这个包 **[源码]**。
   → 网关自己就是客户端，必须自己发；不能等浏览器发。
4. **登录成功的字段布局随协议变化**：UUID 文本/二进制、properties、strict-error 标志。
   解析表见 [protocol-spec §5](protocol-spec.md#5-mc-登录包处理)。
5. **登录期后端可能提前发 configuration/play 包**（尤其现代 Paper）：必须**有界缓冲**，
   等客户端 `FINISH_LOGIN` 后再按序 flush；不能丢包、不能乱序。

---

## 6. 游戏阶段：能原样中继吗？

**能。** 桥接层不解析游戏包，只做：

```text
客户端 ── WS 帧（包 id + 字段） ──▶ 加 VarInt 长度（+ 可选压缩）──▶ 后端 TCP
客户端 ◀── WS 帧（包 id + 字段） ── 去长度/解压 ◀── 后端 TCP 的 VarInt 帧
```

参考实现之所以能这样，是因为它**保留了代理/服务端原有的 MC 编解码器**，只把
「帧边界」和「压缩」换掉了（`WebSocketEaglerFrameCodec` 只做 WS 帧 ↔ `ByteBuf`）。
这意味着：

- 不需要维护每个版本的包 id 表（除了登录期与少量可选注入）。
- ViaVersion / ViaBackwards 在后端照常工作（它们看到的是标准 MC 客户端）。
- 反作弊（GrimAC 等）在后端照常工作（实测：浏览器端 5 分钟零 violation）**[实测，deploy §7.5]**。
- 服务端插件（LuckPerms / EzShops / GriefPrevention / SkinsRestorer …）照常工作 **[实测]**。

### 6.1 Eagler 私有扩展（可选实现）

这些是**跑在 MC 插件消息之上**的私有协议（通道名 `EAG|...`），网关可以：

| 扩展 | 通道/载体 | 不实现的后果 |
|---|---|---|
| 皮肤/披风 | `EAG|Skins-1.8` 等 + profile data | 玩家看到默认皮肤 |
| 语音 | `EAG|Voice-...`（WebRTC 信令，服务端做 SFU/转发） | 无语音（不影响玩） |
| 通知/暂停菜单/WebView | `EAG|...` 能力包 | 无这些 UI 功能 |
| 客户端更新/证书 | `update_cert_v1` / 更新证书包 | 不做自更新校验 |
| 真实 IP | `EAGLER_IP` 能力 | 后端只看到网关 IP（可用 PROXY 协议补） |

> **P1 全不做**；P2 视需求做皮肤转发（纯插件消息，不需要解析游戏包）；语音不在本仓范围。

### 6.2 MOTD / 服务器列表查询

Eagler 客户端在服务器列表里会对同一个 `wss://` 地址发起**查询连接**：

```text
客户端：WS 连接 → 文本帧 "Accept: motd"（或 motd.noicon / motd.cache…）
服务端：文本帧 JSON {type:"motd", motd:[2 行], icon:bool, online, max, players:[...], cache:...}
        + 可选二进制 PNG（icon=true 时）
```

**[源码：`WebSocketQueryHandler` + `MOTDConnectionWrapper`；客户端 `TeaVMServerQuery`]**

网关可以用「对后端做一次标准 Server List Ping（TCP）」来生成这个 JSON；
不实现的话，服务器列表条目会显示离线/无信息（仍可直连）。

### 6.3 限流信号

服务端可以直接发文本帧 `BLOCKED` / `LOCKED`，客户端会显示「IP 限流」而不是普通断线
**[源码：客户端 `PlatformNetworking`]**。网关可复用这个约定。

---

## 7. 参考实现：EaglerXServer 内部结构

上游 `lax1dude/eaglerxserver`（1.1.1，All Rights Reserved）是唯一完整实现。它的做法值得学习，
但**不适合直接当我们的架构**（见 §7.4）。

### 7.1 注入点

- 单 JAR 可装 Spigot / BungeeCord / Velocity；在代理上**不另开端口**，而是
  **把 WS/握手 handler 注入到已有 listener 的 Netty pipeline**（`inject_address` 指向已有 listener）。
- `WebSocketInitialHandler`：第一个二进制帧到达时，在 pipeline 里插入
  `WebSocketEaglerInitialHandler`，并把 `WebSocketInitialHandler` 替换为
  `WebSocketEaglerFrameCodec`（WS 帧 → `ByteBuf`）。
- 同时**移除**原版的 `frame-decoder` / `frame-encoder` / legacy ping 等
  （`VANILLA_FRAME_DECODERS`），因此后续 `ByteBuf` 直接就是「包 id + 字段」。
- `WebSocketEaglerInitialHandler` 解析首帧的 `[0x01][0x02]` + 版本表，选 v3/v4/v5，
  实例化对应 `HandshakerVx`；握手完成后 `pipeline.remove(HANDLER_HANDSHAKE)`，进入普通 MC 处理。

### 7.2 压缩/帧的「反向工程」点

- 代理侧把 `MinecraftConnection.setCompressionThreshold()` 用动态代理改成 no-op
  （Velocity 反射 `AuthSessionHandler`；Bungee 替换 `ChannelWrapper`）。这是**最脆弱的一段**。
- Eagler 帧的压缩完全交给 Netty 的 WS 扩展；`VanillaInitializer` 收到 `Set Compression` 只 `break`。
- `enterPlayState()`：对 MC ≥ 764 主动 `fireChannelRead(0x03)`（Login Acknowledged），
  再 flush 缓冲包，然后移除握手 handler。这个顺序是**协议正确性的关键**。

### 7.3 平台差异与维护状态

| 平台 | 状态 | 风险 |
|---|---|---|
| Velocity | 支持，现网主用 | 反射内部类（`AuthSessionHandler` / `MinecraftConnection`），大版本会断 |
| BungeeCord | 支持 | 反射 `InitialHandler` / `ChannelWrapper`；生态衰退 |
| Spigot/Paper (Bukkit) | ≤1.17 维护；**不支持 Folia** | 需按 NMS 类名注入；>1.17 靠 `EaglerXPaper` 这类反编译 fork |
| Folia | 只能走 Velocity 层 | Bukkit 平台无解 |

- 上游 1.1.1（2026-08）已支持 v5/26.x，但 issue 里仍有 26.3 相关问题；发版慢。
- 第三方 `EaglerXPaper` 是**反编译再改**的产物，声明支持 1.12.2–1.17.1 与 1.21.11–26.2，
  自述**没有测 26.3**；许可证 All Rights Reserved。

> **结论**：它验证了「方案可行 + 协议细节」，但把「WS 桥」和「Minecraft 服务端」焊死在一起，
> 每跟一次 MC/Velocity 版本就要重做反射。独立网关可以完全避开这一层。

### 7.4 与自研网关的职责对比

| 职责 | EaglerXServer | 独立网关（本仓） |
|---|---|---|
| WS 终结 / Eagler 握手 | 代理 Netty pipeline 内 | 自己监听（或反代后面） |
| 帧/压缩转换 | 复用代理的 Netty | 自己实现（WS 库 + zlib） |
| MC 登录 | 伪造 C00Handshake/LoginStart，读 LoginSuccess | 自己作为客户端完成 |
| UUID/身份 | 代理事件可改 UUID | 离线 UUID；登录前用令牌/白名单 |
| 皮肤/语音/MOTD | 内置 | 可选逐步补 |
| 版本跟随 | 跟 NMS/Velocity 内部 | 只跟 Eagler 握手 + MC 登录字段 |
| 对后端的侵入 | 必须装插件 | **零**（后端只看到普通客户端） |

---

## 8. 客户端侧（决定「能不能一键进服」）

### 8.1 运行形态

- 单文件 HTML：内联 wasm/资源（26.3 约 78 MB HTML；1.8.8 的资源是 `.epk` + JS）。
- 资源/语言/世界存 IndexedDB；无 Java、无安装。
- Gen 1 用 TeaVM JS；Gen 2 用 Wasm-GC + WebGL2。

### 8.2 配置项 `eaglercraftXOpts`

工作区模板里可配置（1.8.8 源码）**[源码：`sources/setup/workspace_template/javascript/index.html`]**：

```js
window.eaglercraftXOpts = {
  container: "game_frame",
  assetsURI: "assets.epk",
  servers: [ { addr: "wss://play.example.com/survival", name: "生存服" } ],
  relays: [ { addr: "wss://relay...", comment: "..." } ],
  joinServer: "wss://play.example.com/survival",   // 直接进服
  // ...
};
// 模板还支持 ?server=<addr> → joinServer
```

> **2026-11-22 实测修正**：官方站支持深链
> `/play?joinServer=<wss 地址>&version=<客户端版本>&launch=1`（首页服务器目录的「加入」即这种链接；
> 页面把 `joinServer` 传给客户端的 `launchOptions.joinServer`，`launch=1` 是**自动启动**开关，
> 跳过「启动游戏」按钮）。所以「点开即进服」**不需要**自托管客户端：直接链官方深链即可
> （`site` 的「在浏览器中玩」已改成这种方式）。
> 未登录首次启动时，官方页可能弹一次「登录以保存你的进度」，点 × 关闭即可；
> 自建/自编译客户端页面仍可选（预置多服/离线单文件、彻底免官方弹窗），但不再是必需。
>
> 官方分发页仍**不能 iframe**（`X-Frame-Options: DENY`）；客户端本体也不分发
> （**All Rights Reserved**，且项目不符合 Mojang EULA；见 gateway-design §17）。

### 8.3 地址解析与 TLS

- 输入不以 `ws://`/`wss://` 开头时：页面是 `https` → 自动补 `wss://`，否则补 `ws://`；
  端口默认 443/80；**路径会保留**（可以用 `/s/<name>` 做路由）**[源码：`AddressResolver`]**。
- 26.x：只尝试 `wss://` **[实测]**。
- 服务器列表/直连都接受完整 URL，所以「一个域名 + 路径」可以承载多后端。

### 8.4 relay 不是桥

客户端内置的 `wss://relay.*` 是**WS 中继/NAT 穿透**，只转发字节流；
它**不会**把 Eagler 协议翻译成 Java 协议。要连真实 Java 服，仍然需要 EaglerXServer/网关。

### 8.5 其它通道（不经过 Eagler 桥接层）

| 通道 | 说明 | 对我们的意义 |
|---|---|---|
| **Direct Sockets**（Chrome 151+） | 浏览器直接开 TCP，理论上可连正版服（配合客户端内的微软登录/AES） | 仅 Chromium、需 Isolated Web App/策略；不能作为通用方案 |
| **Wisp / epoxy-tls**（如 `wispcraft`） | 浏览器 → Wisp 服务器 → TCP；可配合补丁做正版登录 | 另一条生态路线；需要客户端补丁 + 额外中继；与本仓网关不冲突 |
| 公共 relay | 只做 WS 转发 | 不能替代网关 |

> 我们的定位：**WSS 网关是兼容性最好的路**（所有支持 WS 的浏览器/客户端版本），
> Direct Sockets/Wisp 作为未来的「正版登录」补充，不在 P1。

---

## 9. 版本/协议矩阵（实现时照这张表）

| 客户端 | Eagler 握手 | 声明 MC 协议 | 后端要求 | 我们的支持 |
|---|---|---|---|---|
| Eaglercraft 1.5.2 | v1（或 v2 兼容） | 61 | 需 1.5→1.8 翻译 | ❌ P1 不做 |
| EaglercraftX 1.8.8 | v2/v3 | 47 | 1.8.8 或 ViaVersion | ✅ v3 |
| Eaglercraft 1.12.2 | v3/v4 | 340 | 1.12.2 或 ViaVersion | ✅ v3（v4 可后续） |
| Eaglercraft 26.x | v3/v4/v5 | 777… | 对应版本或 ViaVersion | ✅ v5 |

**登录包 id 在 1.8 → 26.x 全区间是稳定的**（login 状态）**[源码 + wiki.vg]**：

| 方向 | id | 包 |
|---|---:|---|
| C→S | 0x00 | Login Start |
| C→S | 0x01 | Encryption Response |
| C→S | 0x02 | Login Plugin Response |
| C→S | 0x03 | Login Acknowledged（≥764） |
| S→C | 0x00 | Disconnect |
| S→C | 0x01 | Encryption Request |
| S→C | 0x02 | Login Success |
| S→C | 0x03 | Set Compression |
| S→C | 0x04 | Login Plugin Request |

→ 网关**不需要**按版本维护整张包 id 表，只需要按版本处理**字段布局**（Login Start 的 UUID、
Login Success 的 UUID/properties/strict-error）。

---

## 10. 限制与陷阱（按踩坑频率排序）

| # | 现象/约束 | 根因 | 处置 |
|---|---|---|---|
| 1 | 连不上，提示「不支持在线模式」 | 代理或后端 `online-mode=true` | 整链 offline；网关明确报错 |
| 2 | 进服后频繁 1006 / keepalive timeout | 浏览器渲染/GC 卡 30 s，漏回 keepalive | 客户端硬件加速、前台、降 view-distance；网关侧加大 read timeout |
| 3 | 玩家之间看不到皮肤 | 没实现 profile data 的转发 | 默认皮肤可接受；P2 做皮肤中继 |
| 4 | 同名玩家互相顶号 | 离线 UUID 由名字派生 | 白名单 + 登录口令/令牌；网关层令牌绑定 |
| 5 | 大区块包导致帧超限/断连 | WS 单消息上限/反代缓冲 | 提高上限、确认反代透传 WS 且不缓冲；WS 库支持分片 |
| 6 | 反代后所有玩家 IP 相同 | 网关只看到反代 IP | 反代带 `X-Real-IP`；网关校验并透传（Proxy Protocol 到 Velocity） |
| 7 | 被当成开放代理/SSRF | 允许玩家填任意上游 | **只允许配置里的上游**，禁止用户指定 host |
| 8 | Origin 绕过 | 客户端可伪造/`Origin: null` | 以令牌/来源白名单为主，Origin 只做辅助 |
| 9 | 明文 ws 被浏览器拦 | https 页面强制 wss | 只提供 wss |
| 10 | 26.x 版本号/brand 误判 | brand 字符串滞后 | 以首帧协议表 + `protocol_version` 为准 |
| 11 | 反代 gzip/缓冲 WS | 中间件把 WS 当普通响应 | 关闭压缩、`flush_interval -1`、透传 `Upgrade` |
| 12 | 网关 CPU 打满 | 每帧 deflate level 6 + 大量小包 | 调 level（3–6）、批量写、限并发、监控 |

---

## 11. 对我们的设计含义（摘要）

1. **网关可以很薄**：WS + Eagler 握手（v3/v5）+ MC 登录（含压缩/LoginAck）+ 双向帧中继。
2. **后端零侵入**：网关作为普通离线客户端 → 后端可直连 Paper，也可接 Velocity（无需实现转发）。
3. **身份是产品问题不是协议问题**：离线 UUID + 同名同号，必须用白名单/令牌/口令收敛。
4. **皮肤/语音/更新是可选扩展**，不阻塞「能进服」。
5. **不要重写 MC 协议**：协议翻译交给 ViaVersion；我们只做传输与登录。
6. **客户端分发是独立决策**（ARR + EULA）；服务端能力可以先上线，客户端先用官方页 + 粘贴地址。

细节方案、里程碑与验收见 [gateway-design](gateway-design.md)。
