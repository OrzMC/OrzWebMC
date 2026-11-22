# 客户端数据目录（镜像/仓库不包含客户端本体）

本目录是运行时挂载点（容器内 `/data/versions`）。**客户端 HTML 由部署者自行从官方来源下载并放置**；
本仓库、Docker 镜像与发布物**不包含** Eaglercraft 客户端文件。

## 目录约定

```
data/versions/
├── 26.3/
│   ├── client.html          # 必需：单文件客户端（或 index.html）
│   └── meta.json            # 可选：版本元数据
└── 1.8.8/
    └── client.html
```

- 一个子目录 = 一个版本；目录名即版本 id（`[A-Za-z0-9._-]`，最长 64）。
- 客户端入口默认找 `client.html`，其次 `index.html`；也可在 `meta.json` 的 `client` 指定。
- 服务会**自动发现**版本；新增/更新后重启服务（或等待后续热加载）。
- 官方 26.3 客户端不读 `?server=`；服务会在首次访问时于内存/缓存中注入
  `eaglercraftXOpts.joinServer`（不修改源文件，不在仓库/镜像里保存 patch 后的本体）。
  如果客户端本身已支持 `joinServer`，会原样提供。

## meta.json（可选）

```json
{
  "id": "26.3",
  "name": "Eaglercraft 26.3",
  "client": "client.html",
  "protocol": 777,
  "runtime": "wasm-gc",
  "notes": "官方 26.3 单文件客户端",
  "inject": "auto"
}
```

| 字段 | 说明 |
|---|---|
| `id` | 版本 id；缺省用目录名 |
| `name` | 展示名；缺省同 id |
| `client` | 入口文件；缺省 `client.html` / `index.html` |
| `protocol` | MC 协议号（仅展示/API） |
| `runtime` | `wasm-gc` / `js` 等（仅展示） |
| `notes` | 备注 |
| `inject` | `auto`（默认）/ `joinServer`（强制注入）/ `none`（原样提供） |

## 合法获取

部署者需自行从官方发布页获取客户端，并自行确认许可证/EULA 风险。
本服务只提供托管入口、深链与外嵌，不提供客户端下载，也不对客户端内容负责。
