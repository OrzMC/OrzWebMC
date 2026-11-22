# OrzWebMC — 浏览器连服（Web 端 Minecraft）单仓

> **定位**：让玩家**不安装 Java 客户端**、直接用浏览器进入真实的 Java 版 Minecraft 服务器。
> 本仓承载「协议网关（gateway）」的实现与设计文档；客户端本体、服务端插件、部署编排按
> 归属放在各自仓库（见 §3）。
>
> **状态**：**P0 调研完成（仅文档）**。当前没有可运行代码——`start.sh` / `service.sh` /
> `config.json` 是早期占位符（`npm run build` 依赖的 `package.json` 并不存在）。
> 实现计划见 [`docs/gateway-design.md`](docs/gateway-design.md)。
>
> **最后更新**：2026-11-22

---

## 1. 一句话结论

Eaglercraft 不是「另一套 Minecraft」，而是 **把 Java 客户端编译到浏览器（TeaVM/JS 或
Wasm-GC），并在传输层把 TCP 换成 WebSocket**：

```text
浏览器客户端  ── ws/wss 二进制帧（1 帧 = 1 个 MC 包，无长度前缀，帧级 deflate）──▶  桥接层
                                                                                    │ 普通 MC 协议（TCP）
                                                                                    ▼
                                                                          Java 服务器 / 代理
```

因此**服务端不需要跑 Minecraft 的「Web 版」**：只要有一个能完成 Eagler 握手、把
「WS 帧 ↔ MC 包」互相转换的**协议网关**，浏览器玩家就能进任何**离线模式**的 Java 服
（在线模式不可用，见 §2.3）。

## 2. 我们想做什么

### 2.1 目标

1. **通用**：一个网关二进制 / 容器，可对接我们自己的服务器，也可给别人自托管。
2. **不依赖服务端魔改**：不要求 Paper/Spigot 装 NMS 反射插件（现有 EaglerXServer 的痛点）。
3. **多后端**：按域名 / 路径 / 客户端选择路由到不同后端；支持前面挂 Velocity 组网。
4. **可运营**：限流、来源校验、真实 IP 透传、指标、优雅重启、可回滚。

### 2.2 非目标（至少 P1–P2 不做）

- 不做 MC 协议**翻译**（交给后端 ViaVersion）。
- 不支持 `online-mode=true` 的后端（Eagler 客户端没有正版会话，见 §2.3）。
- 不分发/修改 Eaglercraft 客户端本体（许可证为 All Rights Reserved，且不符合 Mojang EULA）。
- 不做 Eagler 语音（WebRTC）与皮肤 CDN；先保证「能进服、能玩」。

### 2.3 硬约束（已核实）

| 约束 | 后果 |
|---|---|
| 桥接层向后端发的是**离线登录**；后端回 `Encryption Request` 即被拒 | **整链必须 `online-mode=false`**（代理 + 后端都是） |
| Eagler 玩家 UUID 由名字派生（`OfflinePlayer:<name>` 的 v3 UUID） | 「同名 = 同账号」，门槛只能靠白名单 + 登录口令/令牌 |
| 客户端声明的 MC 协议号只有它自己那一版 | 后端不在该版本时必须装 ViaVersion，或只放行匹配版本 |
| 浏览器长连接对前台/硬件加速敏感 | 掉线大多是客户端侧（1006），不是网关 bug；见 `deploy` 的排障节 |

## 3. 文档与仓库边界

| 内容 | 位置 |
|---|---|
| **Eaglercraft 原理拆解**（客户端、握手、帧、压缩、限制） | [`docs/eaglercraft-internals.md`](docs/eaglercraft-internals.md) |
| **网关协议规范**（要实现什么，字节级） | [`docs/protocol-spec.md`](docs/protocol-spec.md) |
| **网关架构与实施计划**（怎么实现、怎么验收） | [`docs/gateway-design.md`](docs/gateway-design.md) |
| 证据、上游源码位置、复现脚本 | [`docs/references.md`](docs/references.md) |
| 现有 Eagler 线上验证（拓扑、插件矩阵、1006 排查） | `OrzMC/OrzMCDeploy` → `docs/eaglercraft.md`、`docs/eaglercraft-deploy.md` |
| 官网玩家入口（Web 地址、按钮） | `OrzMC/OrzMCSite` |
| 服务端身份/白名单/口令 | `OrzMC/OrzMCPlugin`（服内插件） |

> ⚠️ 本仓与服务端**没有强耦合**：网关对后端而言只是一个「普通的离线 Java 客户端」。

## 4. 当前结论（TL;DR）

- **方案可行**：Eagler 在游戏阶段就是原始 MC 包，网关只需做**帧映射 + 登录握手 + 压缩协商**，
  不需要理解每个游戏包。
- **两条实现路线**：① 继续用 `EaglerXServer`（现网已跑通，但需要 NMS/反射注入、版本跟不动）；
  ② **自研独立网关**（本仓目标）。P0 结论：**走 ②，① 保留为对照与兜底**。
- **最小可用范围**：Eagler 握手 **v3 + v5**（覆盖 1.8.8/1.12.2 与 26.x 两代客户端），
  后端 **Paper 直连** 或 **Velocity**（网关作为普通离线客户端接入，无需实现 Velocity 转发）。
- **已定**：实现语言 **Java/Kotlin**（JVM 21+，Netty；2026-11-22）。
- **主要未决**：与 EaglerXServer 的关系（D2）、是否自建客户端页面（D3，许可证风险）、
  身份令牌方案（D4）、皮肤/语音优先级（D5）；26.3 客户端的 v5 细节需真机抓包确认（清单见 `protocol-spec.md` §9）。
