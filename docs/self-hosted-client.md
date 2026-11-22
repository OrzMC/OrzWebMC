# 自托管客户端兜底方案

> **状态**：方案已验证（2026-11-22），作为**官方站不可用时的备用入口**，不是主路径。
> 本文是手工版；已实现的服务（`docs/client-hosting-service.md`）会自动做 patch/托管，
> 本文保留用于理解资产、首次体验与风险。
> 主路径仍是官方深链 `https://eaglercraft.com/play?...&launch=1`；
> 本文只解决「哪天官方站下线/被墙/被下架，玩家还能进服」。
>
> **合规前置**：Eaglercraft 客户端是 **All Rights Reserved**，且项目不符合 Mojang EULA。
> 自托管 = 再分发客户端，法律风险由运营者承担；**不要公开镜像、不要入库、不要商业化**。
> 这不是「合规」方案，只是**可用性兜底**。

---

## 1. 已验证的事实（2026-11-22）

| 项 | 结果 |
|---|---|
| 官方 26.3 分发 | `eaglercraft-26.3-fe8f1237-815f7813.zip`，58,885,242 B，sha256 `815f7813…aaa96` |
| 解包 | `eaglercraft-26.3-fe8f1237.html`，78,124,802 B，sha256 `acc1496a…1a8d3` |
| 官方 HTML 是否读 server 参数 | **否**：`joinServer`/`servers` 出现 0 次；`allowBootMenu:false`，无预置服务器 |
| patch 方式 | 在 `window.eaglercraftXOpts = {` 后注入 `joinServer: URLSearchParams`，读取 `?server=`/`?joinServer=` |
| 本地托管 + 无头 Edge | 客户端正常启动；首次显示 **Edit Profile** → Done；再显示 **Default Username Detected** → Continue Anyway；随后**自动连上** `dev.jokerhub.cn:18443`，被白名单踢出（`You are not on the whitelist`）——**证明 joinServer 生效** |
| 与官方站差异 | 自托管没有官方站的「启动游戏」按钮，也没有「登录以保存进度」弹窗；但首次有客户端自己的档案/用户名确认 |

> 结论：**自托管兜底可行**，代价是：首次要过两个客户端原生确认（档案 + 默认用户名），
> 之后同一浏览器（同源 IndexedDB）不再问；版本冻结在 26.3，靠后端 ViaVersion 兼容新服务端。

---

## 2. 资产准备（不要入库）

```bash
# 1) 下载官方 26.3 分发包（59 MB）
curl -sSL -o eagler-26.3.zip \
  'https://cdn.eaglercraft.net/objects/dl/26.3/eaglercraft-26.3-fe8f1237-815f7813.zip'
sha256sum eagler-26.3.zip
# 815f78136bcbb38e7abcad623e8884e14b6d0c07c89958080b3a4a04982aaa96

# 2) 解包
unzip -o eagler-26.3.zip -d eagler-26.3
# eaglercraft-26.3-fe8f1237.html（78,124,802 B）
```

**不要提交到 Git**：75 MB 单文件 + ARR；放在 VPS 的 `/srv/eagler/`（或对象存储），
用校验和/manifest 记录版本（例如 `SHA256SUMS`），升级时人工替换。

---

## 3. 打 patch（只加一个 joinServer）

官方 HTML 里唯一需要的改动：让 `eaglercraftXOpts` 支持从 URL 读服务器地址。
在 `window.eaglercraftXOpts = {` 之后插入：

```js
joinServer: (function () {
  try {
    var p = new URLSearchParams(location.search);
    return p.get('server') || p.get('joinServer') || undefined;
  } catch (e) { return undefined; }
})(),
```

一次性命令（Python，输出到新文件，保留原文件）：

```bash
python3 - <<'PY'
src = 'eaglercraft-26.3-fe8f1237.html'
dst = 'index.html'
s = open(src, encoding='utf-8', errors='replace').read()
needle = 'window.eaglercraftXOpts = {'
inject = ('window.eaglercraftXOpts = { joinServer: (function(){ try { '
          'var p = new URLSearchParams(location.search); '
          'return p.get("server") || p.get("joinServer") || undefined; '
          '} catch (e) { return undefined; } })(),')
assert s.count(needle) == 1, 'unexpected opts anchor count'
open(dst, 'w', encoding='utf-8', newline='\n').write(s.replace(needle, inject, 1))
PY
```

验证：patch 后 `grep -c 'joinServer' index.html` 应 ≥1；文件大小基本不变。

> 这是**修改 + 再分发**，法律风险高于原样托管；仅作为私有兜底，不要公开宣传。
> 若只想原样托管（不改），官方 HTML 不读 `?server=`，玩家仍需手动「多人游戏 → 直连」粘贴。

---

## 4. 托管（Caddy）

```caddyfile
webclient.example.com {
    root * /srv/eagler
    file_server

    # 75 MB 单文件：长缓存 + 不可变；更新时换文件名（带版本/哈希）最稳
    header Cache-Control "public, max-age=31536000, immutable"
    header X-Robots-Tag "noindex, nofollow"
    header X-Content-Type-Options "nosniff"

    # 可选：只给知道入口的人用（降低公开分发风险）
    # basic_auth { user <bcrypt-hash> }
}
```

- **不要放 GitHub Pages**：单文件 100 MB 硬限、带宽/仓库限制。
- 不要提交到 Git；VPS 上保留 `eaglercraft-26.3-fe8f1237.html` 原件 + `index.html` patch 版。
- 如果套 Cloudflare：HTML 默认不缓存，需加 Cache Rule；75 MB 首访走源站，注意家宽上行。
- 入口**不要**放进 sitemap/搜索；`X-Robots-Tag: noindex`；链接只在官网故障时展示。

---

## 5. 官网侧开关（site）

`site` 已预留配置（默认空 = 不显示）：

```toml
# config/_default/params.toml
web_client_fallback_url = "https://webclient.example.com/?server={address}"
# {address} 会被替换为 URL 编码后的 wss://...；也可用 {version}
```

- 配置后，卡片「在浏览器中玩」下方出现次级链接
  「官方入口不可用？使用备用客户端」；不配置则不渲染。
- 官方站正常时保持为空；确认官方不可用时再填上并发布（或提前填好但只在故障时公开）。
- 备用入口打开 `https://webclient.example.com/?server=wss%3A%2F%2F...`，patch 版客户端自动进服。

---

## 6. 首次使用体验（必须知道）

自托管客户端没有官方站的外壳，首次会走客户端自己的引导：

1. **Edit Profile** → 点 **Done**（可改用户名/皮肤；不点不往下走）。
2. **Default Username Detected**（`Guest_xxxx`）→ 点 **Continue Anyway** 或 **Do not show again**。
3. 之后客户端按 `?server=` **自动连接**；同一浏览器/同源 IndexedDB 会记住档案，不再问。

> 想彻底免这两步，只能改客户端（风险更高）或预置浏览器存储；不建议为兜底场景增加维护面。

---

## 7. 版本与更新

| 场景 | 做法 |
|---|---|
| 官方发布 26.4/27.x | 重新下载、重打 patch、替换 `index.html`；保留旧版一两个版本做回退 |
| 服务端升级、客户端仍旧 26.3 | 后端 **ViaVersion** 负责翻译；确认 Via 仍支持 26.3 客户端 |
| 官方站恢复 | 把 `web_client_fallback_url` 置空（或保留但不再展示），主路径回官方 |
| 官方站被下架 | 兜底入口切为主用；注意我们的副本也可能被要求下架，做好随时停用准备 |

---

## 8. 风险清单

| 风险 | 说明 | 缓解 |
|---|---|---|
| **ARR/EULA** | 自托管/修改客户端是再分发，法律风险高于只链官方 | 私有/不公开、不商业化、不宣传；仅故障时启用 |
| 官方下架波及 | 我们的副本同样可能被投诉/下架 | 随时可停用；不把它做成公开镜像 |
| 版本冻结 | 26.3 客户端连不上未来服务端 | 后端 ViaVersion；定期评估更新副本 |
| 首次两步确认 | 档案 + 默认用户名 | 文档说明；或后续做存储预置/客户端 patch（额外风险） |
| 带宽 | 75 MB 首访；家宽上行 | Caddy 长缓存/immutable；限制入口；不要公开索引 |
| 完整性 | patch 版可能被篡改 | 记录 sha256；VPS 权限最小化；发布前校验 |

---

## 9. 验收清单（已完成 ✅）

- [x] 下载官方 26.3 zip，校验 sha256。
- [x] patch `eaglercraftXOpts.joinServer`，本地 HTTP 托管。
- [x] 无头 Edge：客户端启动 → Edit Profile → Done → Default Username → Continue Anyway。
- [x] 自动连接 `dev.jokerhub.cn:18443`，收到白名单踢出消息（证明链路通）。
- [ ] VPS Caddy 托管 + 官方故障切换演练（上线前做）。
- [ ] 站点 `web_client_fallback_url` 配置与「官方不可用」演练。

> 一旦 VPS 部署完成，把 `web_client_fallback_url` 填上即可；官方站正常时不展示/不使用。
