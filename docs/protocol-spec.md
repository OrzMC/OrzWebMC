# OrzMC Web Gateway 协议规范（v0 草案）

> **定位**：网关必须实现的**最小协议契约**。实现可以换语言，但字节布局、状态顺序、
> 错误语义必须一致；否则客户端会以「握手超时 / 版本不符 / 1006」等形式失败。
>
> **状态**：草案（2026-11-22）。v3 部分由 1.8.8 客户端源码 + EaglerXServer 源码互证；
> **v5 部分有 4 项待真机确认**（§9），未确认前按「与参考实现一致」实现。
>
> **读者**：实现者。原理见 [eaglercraft-internals](eaglercraft-internals.md)，
> 架构/里程碑见 [gateway-design](gateway-design.md)。
>
> **约定**：`u8/u16/u32` 为**大端**无符号整数；`VarInt` 为 Minecraft 风格 LEB128；
> 字符串除注明外均为「长度前缀 + ASCII/UTF-8 字节」。`[x]` 表示可选/按版本出现。

---

## 1. 角色

```text
Browser (Eaglercraft client)
   │  WSS: Eagler handshake + 1 MC packet per binary frame
   ▼
Gateway（本规范实现对象）
   │  TCP: vanilla MC protocol（可带 PROXY protocol 到 Velocity）
   ▼
Paper / Folia / Velocity → (modern forwarding) → Paper / Folia
```

- 网关对后端而言是一个**普通离线 Java 客户端**；不要求后端安装任何 Eagler 插件。
- 网关对客户端而言是一个**Eagler 服务端**；必须实现 §3–§6。

---

## 2. 传输层

### 2.1 WebSocket

| 项 | 要求 |
|---|---|
| 路径 | 任意；建议 `/` 或 `/s/<route>`（客户端会保留路径） |
| 子协议 | 不协商 `Sec-WebSocket-Protocol` |
| 消息类型 | 游戏数据只用 **binary**；text 只用于 §7 查询 |
| 压缩 | 必须支持 `permessage-deflate`（浏览器唯一可用）；可选兼容 `deflate-frame`（仅老 Netty）。zlib level 建议 3–6 |
| 分片 | 必须支持入站分片重组；出站大消息允许库自动分片 |
| 最大消息 | 入站默认 1 MiB（可配 4 MiB）；超限直接关闭（不解析） |
| TLS | 生产必须 `wss`；`ws` 只允许环回/显式开启 |

### 2.2 帧内容（核心）

```text
一个 WS binary 消息 = 一个完整的 MC 包（包 id + 字段）
                     = 不含 VarInt 长度前缀、不含 MC 压缩头
```

- 客户端发送：**一包一消息**（`PlatformNetworking.writePlayPacket`）。
- 网关发送：**一包一消息**（`BinaryWebSocketFrame`）。
- 禁止把多个 MC 包写进同一消息（客户端按单包解析）。

### 2.3 关闭语义

| 事件 | 网关行为 |
|---|---|
| 客户端 WS close/断开 | 关闭后端 TCP（不等待） |
| 后端 TCP EOF | 发 WS close（1000）后关闭；若已收到 Disconnect 包则先转发 |
| 协议错误（握手/帧） | 发 `0xFF SERVER_ERROR`（若已过握手）→ 关闭；否则直接关闭 |
| 限流 | 可发文本帧 `BLOCKED` / `LOCKED` 后再关闭（客户端有对应提示） |

---

## 3. 握手帧：字节级定义

所有帧的第一字节是包 id（`0x01`…`0xFF`）。以下只列 **v3 / v5**（我们的最小集）；
v1/v2/v4 的差异见 §3.9。

### 3.1 `0x01 CLIENT_VERSION`（C→S，第一个二进制帧）

```text
u8   0x01
u8   legacyProtocol        # v2+ 固定 2；v1 为 1（v1 后续布局不同，不支持）
u16  eaglerCount N         # 1..16
u16  eaglerProtocol[N]     # 例：3, 4, 5
u16  mcCount M             # 1..16
u16  mcProtocol[M]         # 例：47 / 340 / 777
u8   brandLen + bytes      # ASCII，<=255
u8   versionLen + bytes    # ASCII，<=255
u8   authFlag              # 0/1；1 表示客户端带 authUsername
u8   usernameLen + bytes   # v5 必填（3–16，[A-Za-z0-9_]）；v3 可为空
```

**选择规则**：

1. 在 `eaglerProtocol[]` 中取最高且我们支持的（5 → 3）。
2. 在 `mcProtocol[]` 中选一个「我们/后端可用」的，规则见 §3.2。
3. 若没有交集：回 `0x03 VERSION_MISMATCH`（带双方支持表）并关闭。

**校验**：`N/M ∈ [1,16]`；字符串长度不越界；多余字节 = 非法（关闭）。

### 3.2 `0x02 SERVER_VERSION`（S→C）

v3 / v4：

```text
u8   0x02
u16  selectedEagler        # 3（或 4）
u16  selectedMc
u8   brandLen + bytes      # ASCII
u8   versionLen + bytes    # ASCII
u8   authMethod            # 0=none, 1=EAGLER_SHA256, 2=AUTHME_SHA256, 0xFF=plaintext
u16  saltLen + salt        # authMethod=0 时 saltLen=0
```

v5 在末尾追加：

```text
u8   nicknameSelection     # 0=false（不要让客户端弹昵称选择）
```

`selectedMc` 应**等于客户端声明值**（不要擅自改写）；后端版本不匹配由后端 ViaVersion 处理，
否则后端会踢人——把后端的踢人原因转给客户端即可（§4.3）。

### 3.3 `0x04 REQUEST_LOGIN`（C→S）

v3：

```text
u8   0x04
u8   nameLen + name
u8   requestedServerLen + requestedServer   # 1.8.8 固定 "default"
u8   passLen + passBytes                   # 无认证时为 0
```

v5 在 v3 之后追加：

```text
u8    enableCookie
u8    cookieLen + cookie                   # enableCookie=0 时必须 cookieLen=0
VarInt standardCaps                        # 位掩码，见 §3.6
VarInt capVersion × popcount(standardCaps) # 与置位顺序一致（低位→高位）
u8    extCount                             # 0..32
{ 16B UUID (MSB,LSB), VarInt version } × extCount
```

> 顺序：cookie **在** caps 之前（参考实现解析顺序）。多余字节 = 非法。

### 3.4 `0x05 ALLOW_LOGIN`（S→C）

v3 / v4：

```text
u8   0x05
u8   nameLen + name        # 最终用户名
16B  UUID                  # 大端 MSB, LSB；离线 UUID = UUIDv3("OfflinePlayer:"+name)
```

v5 追加：

```text
VarInt standardCaps
u8     capVersion × popcount(standardCaps)
u8     extCount
{ 16B UUID, u8 version } × extCount
```

**P1 建议**：`standardCaps=0`、`extCount=0`（只保留进服能力）。实现皮肤/重定向前再逐位打开。

### 3.5 `0x07 PROFILE_DATA`（C→S）

v3（单条）：

```text
u8   0x07
u8   keyLen + key          # "skin_v1" / "cape_v1" / "update_cert_v1"
u16  dataLen + data
```

v4 / v5（批量）：

```text
u8   0x07
u8   count
{ u8 keyLen + key, u16 dataLen + data } × count
```

网关可以**直接丢弃**（P1）：不实现皮肤不影响进服。若实现，需限制 `count<=8`、
单条 `<=65535`、总大小（防内存放大）。

### 3.6 能力位

| bit | 名称 | P1 |
|---:|---|---|
| 0 | UPDATE | ❌ |
| 1 | VOICE | ❌ |
| 2 | REDIRECT | ❌（留口子） |
| 3 | NOTIFICATION | ❌ |
| 4 | PAUSE_MENU | ❌ |
| 5 | WEBVIEW | ❌ |
| 6 | COOKIE | ❌（身份方案用 URL token 替代） |
| 7 | EAGLER_IP | ❌（真实 IP 用 Proxy Protocol） |
| 8 | OPTIFINE_CIT | ❌ |

### 3.7 `0x08 / 0x09 FINISH_LOGIN`

```text
C→S: u8 0x08        # 无 payload
S→C: u8 0x09        # 无 payload
```

### 3.8 失败帧

```text
0x03 VERSION_MISMATCH:
  u8 0x03
  u16 n; u16 eaglerProto[n]
  u16 m; u16 mcProto[m]
  u8  msgLen + msg(ASCII)         # "Outdated Client" / "Outdated Server" / "Unsupported Client Version"

0x06 DENY_LOGIN:
  v2: u8 0x06; u8 len + msg(UTF-8)      # 老格式
  v3+: u8 0x06; u16 len + msg(UTF-8/JSON)  # 1.8.8 客户端按 IChatComponent JSON 解析

0xFF SERVER_ERROR:
  u8 0xFF
  u8 code                              # 1=unknown,2=invalid,3=wrong,4=excess profile,
                                       # 5=dup profile,6=ratelimit blocked,7=locked,
                                       # 8=custom,9=auth required
  v2: u8 len + msg
  v3+: u16 len + msg

0x0A REDIRECT_TO（v4+，需能力位）:
  u8 0x0A; u16 len + UTF-8 地址
```

> 长度格式的精确条件：参考实现用 `handshakeProtocol >= 3 && canSendV3Kick` 判断；
> `canSendV3Kick` 在**发出 SERVER_VERSION 之后**才置真。也就是说：
> - 若在「还没选出/还没发 SERVER_VERSION」时就报错（例如首帧非法），仍用 `u8` 长度（老格式）；
> - 正常流程里的 `DENY_LOGIN` / `SERVER_ERROR` 用 `u16`。
> 实现时照抄这个条件，避免 1.8.8 客户端解析错位。

### 3.9 版本差异速查

| 帧 | v2 | v3 | v4 | v5 |
|---|---|---|---|---|
| CLIENT_VERSION | 同 v3 | 基线 | 同 v3 | 同 v3（authUsername 必填） |
| SERVER_VERSION | 无 nickname 字段 | 同 v2 | 同 v2 | + `nicknameSelection` |
| REQUEST_LOGIN | 无 cookie | 基线 | + cookie | + caps/ext |
| ALLOW_LOGIN | name+UUID | 同 v2 | 同 v2 | + caps/ext |
| PROFILE_DATA | 单条 | 单条 | 批量 | 批量 |
| DENY/ERROR 长度 | `u8` | `u16` | `u16` | `u16` |
| REDIRECT | ❌ | ❌ | ✅ | ✅ |

> **v1（1.5.2）不支持**：首帧是 `0x01 0x01 + u8 mcProtocol + brand + version`，且需要
> 1.5→1.8 的包翻译。收到时回「Outdated Client」并要求升级。

---

## 4. 会话状态机

### 4.1 状态与转换

```text
S0 NEW
  └─ 收到 CLIENT_VERSION ──▶ S1
S1 VERSION_SENT
  └─ 收到 REQUEST_LOGIN ──▶ S2（拨后端 + MC 登录）
S2 BACKEND_LOGIN
  ├─ 失败 ──▶ 发 DENY/ERROR，关闭
  └─ Login Success ──▶ 发 ALLOW_LOGIN ──▶ S3
S3 CLIENT_FINISH
  ├─ 收到 PROFILE_DATA（0..N）──▶ 丢弃/缓存
  └─ 收到 FINISH_LOGIN ──▶ 发 SERVER_FINISH_LOGIN ──▶ S4（flush 缓冲）
S4 PLAY
  └─ 双向中继直到任一侧关闭
```

**超时**（建议）：`S0→S1` 10 s；`S1→S2` 10 s；`S2` 后端登录 10 s；
`S3` 客户端 finish 10 s；`S4` 用 MC keepalive + WS ping 判活（30 s 无 pong 关闭）。
任何超时 → `0xFF SERVER_ERROR(8, "Login timed out")`（能发则发）→ 关闭。

### 4.2 顺序不变量

1. `SERVER_VERSION` 必须在 `ALLOW_LOGIN` 之前；`ALLOW_LOGIN` 必须在后端 `Login Success` 之后。
2. `SERVER_FINISH_LOGIN` 必须在收到客户端 `FINISH_LOGIN` 之后。
3. **PLAY 数据不得早于 `SERVER_FINISH_LOGIN` 发给客户端**（后端提前发的包先缓冲，§5.6）。
4. 已进入 PLAY 后不再出现任何 `0x01`–`0x0A` 握手帧（出现即断开）。

### 4.3 错误映射

| 上游情况 | 网关响应 |
|---|---|
| 后端 `Encryption Request`（在线模式） | `0xFF(8, "This server requires online-mode; web clients are not supported")` |
| 后端登录 `Disconnect`（JSON） | v3：`0x06 DENY` + **原样 JSON**；v5 同 |
| 后端版本不支持 | `0xFF(8, 后端 JSON)`，日志留原文 |
| 客户端版本交集为空 | `0x03 VERSION_MISMATCH` |
| 客户端名字非法 | `0xFF(2, "Invalid Username")` |
| 上游连接失败/超时 | `0xFF(8, "Server unavailable")`（不要泄露内网地址） |

---

## 5. MC 登录包处理（网关 → 后端）

### 5.1 建连

```text
C→S  [VarInt 0x00][VarInt protocol][String host][u16 port][VarInt 2]
```

- `protocol` = 客户端声明的 MC 协议（§3.1 选中的值）。
- `host` = 配置的 `handshake_host`（默认上游地址的域名；部分后端/代理会校验）。
- `port` = 上游端口。

### 5.2 Login Start

```text
P1 支持：
  protocol 47 / 340（老版）：  [0x00][String name]
  protocol >= 764（1.20.2+）：  [0x00][String name][16B offline UUID]
  protocol 759..763：          [0x00][String name]（实现时按 wiki.vg 版本表补；先实测）
```

- `offline UUID = UUIDv3("OfflinePlayer:" + name)`（Java 离线 UUID 算法）。
- 名字先做 `^[A-Za-z0-9_]{3,16}$` 校验（与 Eagler 一致）。

### 5.3 后端 → 网关（登录态）

| id | 包 | 处理 |
|---:|---|---|
| 0x00 | Disconnect | 读 String（JSON/legacy），映射为 Eagler DENY（§4.3） |
| 0x01 | Encryption Request | **拒绝**（离线专用），关闭 |
| 0x02 | Login Success | 解析 name/UUID（§5.4）→ 发 ALLOW_LOGIN |
| 0x03 | Set Compression | 启用后端链路的 zlib（§5.5）；**绝不转发给客户端** |
| 0x04 | Login Plugin Request | 回 `Login Plugin Response`：`success=false`（除非未来使用） |

`Login Plugin Response`（C→S）：

```text
[VarInt 0x02][VarInt messageId][u8 success=0][无 payload]
```

### 5.4 Login Success 字段布局

| 协议 | 布局 |
|---|---|
| < 735 | `[String uuid][String name]` |
| 735 – 758 | `[16B uuid][String name]` |
| 759 – 765 | `[16B uuid][String name][VarInt propCount]{String name, String value, u8 signed[, String signature]}×` |
| >= 766 | 同上 + `[u8 strictErrorHandling]` |

> UUID 取后端返回值为准（离线模式下应等于离线 UUID；若不等，记录警告并按后端值给客户端，
> 以保证「后端视角的身份」一致）。

### 5.5 压缩（后端链路）

- `Set Compression(threshold)` 之后，双方所有包改为：

```text
[VarInt packetLength][VarInt dataLength][payload]
  dataLength == 0 → payload 未压缩
  dataLength  > 0 → payload 是 zlib(dataLength 字节的解压结果)
```

- 网关 → 后端：`len(payload) >= threshold` 才压缩，否则 `dataLength=0`。
- 后端 → 网关：`dataLength>0` 时解压，并**限制解压上限**（如 8 MiB，防 zlib bomb）。
- 客户端侧始终无 MC 压缩（§2.2）。

### 5.6 现代协议：configuration 与 Login Acknowledged

- 协议 `>= 764`：收到 `Login Success` 后，网关必须**自己**发：

```text
C→S [VarInt 0x03]        # Login Acknowledged
```

  然后后端进入 **configuration** 状态，开始发 registry/feature 等包。
- 网关不需要解析 configuration 包，**原样缓存并中继**；
  但必须保证它们**不早于** `SERVER_FINISH_LOGIN` 发给浏览器。
- 缓冲上限建议 4 MiB / 2000 包，超限断开（防恶意后端/异常）。

> ⚠️ 若 26.x 客户端在进入 PLAY 后自己也会发 `0x03`（待确认，§9-2），网关必须
> **只发一次**：优先由网关发；若客户端随后又发，按 PLAY 包原样转发会被后端拒绝/踢出。
> 处理策略：进入 PLAY 后丢弃客户端的第一个 `0x03`（仅当 `protocol>=764` 且我们已代发）。
> 该策略需真机验证后再定稿。

### 5.7 连接超时与 keepalive

- 后端 `read-timeout`：建议 120 s（与 `deploy` 现网一致）。
- 网关不生成 MC KeepAlive；由后端生成、客户端回复，原样中继。
- 网关可发 WS ping（30 s）检测死链；连续 2 次无 pong → 关闭。

---

## 6. PLAY 中继语义

### 6.1 客户端 → 后端

```text
for each WS binary message:
    payload := message bytes            # 已经是一个完整的 MC 包
    if len(payload) > maxFrame: close
    frame := [VarInt len(payload)] + (compressed ? [VarInt dataLen][zlib] : [0][payload])
    write(frame)
```

- 包 id 不解析（除 §5.6 的 `0x03` 特殊处理）。
- 空消息 = 非法（关闭）。

### 6.2 后端 → 客户端

```text
loop:
    read VarInt packetLength (max 8 MiB)
    read packetLength bytes
    if compression enabled:
        read VarInt dataLength inside packet
        payload := dataLength==0 ? rest : inflate(rest, dataLength)
    else:
        payload := packet
    ws.sendBinary(payload)              # 一个包一个消息
```

- 保持顺序、不合并、不丢弃。
- 大包（区块）允许 WS 库自动分片；不要在应用层切包。
- 写超时/积压超过阈值（如 8 MiB）→ 关闭（慢客户端不应拖垮网关）。

### 6.3 线程模型

- 每连接至少 2 个协程/任务：`ws→tcp` 与 `tcp→ws`；共享关闭信号（cancel context）。
- 禁止在 WS 回调线程里做阻塞 IO；后端写使用有界队列。
- 压缩上下文（zlib）**每连接独立**，不能跨连接复用。

---

## 7. 查询 / MOTD（可选，P2）

客户端对同一地址发起额外 WS 连接并发送**文本帧**：

```text
C→S (text): "Accept: motd"            # 变体：motd.noicon / motd.cache.*
S→C (text): JSON
S→C (bin, 可选): PNG 图标             # JSON.icon=true 时
```

JSON 最小字段（参考 `MOTDConnectionWrapper`）：

```json
{
  "type": "motd",
  "motd": ["第一行", "第二行"],
  "icon": false,
  "online": 3,
  "max": 20,
  "players": ["A", "B"],
  "cache": false
}
```

网关实现建议：对上游做一次**标准 Server List Ping**（TCP：Handshake nextState=1 + Status Request），
把 `description`/`players` 映射成上面的字段；`icon` 转发 base64 解出的 PNG（可选）。
查询连接不进入登录流程；限流独立（建议 1 次/5 s/IP）。

---

## 8. 安全与资源不变量

| 项 | 要求 |
|---|---|
| 上游 | **只能连配置里的 upstream**；禁止客户端指定 host/port（防 SSRF/开放代理） |
| 来源 | 仅信任反代：共享密钥头 / mTLS / 环回；`X-Real-IP` 只在可信来源时采信 |
| Origin | 白名单（可配 `null`）；仅作辅助，不能作为唯一鉴权 |
| 名字 | `^[A-Za-z0-9_]{3,16}$`；拒绝保留名（可选） |
| 帧 | 入站消息 ≤ 1 MiB（默认可配）；握手帧 ≤ 64 KiB；profile ≤ 8 条/64 KiB |
| 解压 | 输出上限 8 MiB；压缩比异常 → 断开 |
| 连接 | 每 IP 并发/速率限制；全局最大连接数；登录超时 10 s |
| 日志 | 不记录 cookie/token/口令；只记录名字、路由、耗时、断开原因 |
| 崩溃 | 不允许 panic 传播；单连接错误只影响该连接 |

---

## 9. 待真机确认清单（实现前/实现中逐项关闭）

每项都给出**最小实验**，结论写回本节（含日期 + 证据）。

| # | 问题 | 实验 | 影响 |
|---:|---|---|---|
| 1 | 26.x 进入 PLAY 后是否仍「一帧一包、无长度前缀」 | 无头 Chrome + WS 抓包，取前 10 个 PLAY 帧，与 MC 包长度对照 | 若否，需要按帧拆分/长度前缀 |
| 2 | 26.x 客户端是否自己发 `Login Acknowledged(0x03)` | 抓客户端 → 假后端，看登录后第一个 C→S 包 | 决定网关是否代发/去重（§5.6） |
| 3 | v5 `ALLOW_LOGIN` 能力位全 0 时，客户端是否仍发 profile + finish | 假服务端回 caps=0，观察后续帧 | 决定 P1 能否直接 caps=0 |
| 4 | v5 `REQUEST_LOGIN` 的 caps 编码顺序/长度 | 抓 26.3 首两个帧，按 §3.3 解析 | 解析器正确性 |
| 5 | 26.x 的 `?server=` / `servers` 是否被官方构建支持 | 用官方 HTML + `?server=wss://...` 试；看是否直接进服 | 一键进服路线 |
| 6 | 1.12.2 客户端声明的 eagler 版本列表 | 抓首帧 | 选 3 还是 4 |
| 7 | 大区块包（>64 KiB）在浏览器侧的收发 | 飞到新区块/大视距，抓帧长 | 帧上限与分片 |
| 8 | Velocity `proxy-protocol` 与网关 PROXY 头兼容 | 网关发 PROXY v2 + Velocity 开启，进服并看日志 IP | 真实 IP 方案 |
| 9 | Paper 是否接受网关提供的离线 UUID（>=764） | 登录后对比 `UUID` 与 `OfflinePlayer:` 派生值 | 身份一致性 |
| 10 | 26.3 的 MOTD 查询 JSON 必填字段 | 假查询服务返回最小 JSON，看列表是否正常 | P2 MOTD |

---

## 10. 单元测试黄金向量（构造，待真机替换）

> 以下为**按本规范手工构造**的最小样例，用于锁定编码器/解码器；实现后应逐步替换为
> 真机抓包（`deploy/tests/eaglercraft_probe.py` 可扩展为 dump 工具）。

### 10.1 v3 `CLIENT_VERSION`（1.8.8 形状）

字段序列（非十六进制，便于核对）：

```text
01                     # CLIENT_VERSION
02                     # legacyProtocol
00 02                  # eagler count = 2
00 02 00 03            # 支持 v2, v3
00 01                  # mc count = 1
00 2F                  # MC 47
0A "Eaglercraft"       # brand（示例）
02 "u1"                # version（示例）
00                     # auth = false
0A "TestPlayer"        # username
```

### 10.2 v3 `SERVER_VERSION`（本网关输出）

```text
02                     # SERVER_VERSION
00 03                  # selected eagler = 3
00 2F                  # selected mc = 47
08 "OrzWebMC"          # brand
05 "0.1.0"             # version
00                     # authMethod = none
00 00                  # saltLen = 0
```

### 10.3 v3 `ALLOW_LOGIN`

```text
05
0A "TestPlayer"
<16B UUID>             # UUIDv3("OfflinePlayer:TestPlayer")
```

### 10.4 断言

- 解码后再编码必须**逐字节相等**（round-trip）。
- 越界/多余字节/非法计数必须报错，且不 panic。
- `VERSION_MISMATCH` 的版本表必须排序稳定（按服务端支持顺序）。

---

## 11. 与规范相关的配置项（建议）

```yaml
protocol:
  accept_eagler: [3, 5]          # 只实现这两个
  accept_minecraft: [47, 340, 764, 765, 766, 767, 768, 769, 770, 771, 772, 773, 774, 775, 776, 777]
  fallback_minecraft: 777        # 客户端列表为空/未知时的兜底（可选）
limits:
  handshake_timeout: 10s
  login_timeout: 10s
  max_ws_message: 1MiB
  max_backend_packet: 8MiB
  max_buffer_bytes: 4MiB
```

> 具体配置文件格式由实现决定；语义以上表为准。
