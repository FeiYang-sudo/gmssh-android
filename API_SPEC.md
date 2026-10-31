# GMSSH 桌面端 · 本地服务 API 契约

> 来源：`/tmp/gmssh_app/app_extracted/dist/assets/*.js`（Vue3 生产包，已反解压缩变量名）
> 用途：为 Android（及 JVM）端重新实现「本机 GMSSH 本地服务」提供精确契约。
> 标注：**【已确认】**= 从调用处/字面量直接读出；**【推测】**= 由用法反推，需实测。

---

## 0. 端口与启动约定 【已确认】

前端通过 Electron IPC（Android 上由 `gm_shim.js` → `AndroidBridge.invoke` 垫片）拿端口：

```js
// index-DcvslqPy.js:  ve=()=>{Be?.invoke("render-to-main",{type:"get-port"}).then(E=>{Nt.value=E; ...})}
```

`get-port` 的返回值 **必须直接是端口对象**（垫片内部已 `JSON.parse` 一次，不要再包一层 `{status,data}`）：

```json
{ "gmPort": 8898, "multiPort": 8898, "browserPort": 8899, "gmServerStatus": true }
```

- `gmServerStatus` 必须为 `true`，否则前端判定本地服务未启动：
  `ol = e => !!(e?.gmServerStatus && e?.gmPort && e?.multiPort)`
- 另有 IPC `{type:"startGMServer"}`：前端在服务未就绪时会发它来拉起服务，壳里应把它当作“幂等启动”处理。
- 三个端口的职责（**已确认**，来自三个独立 axios 实例的 baseURL）：

| 变量 | 端口 | baseURL | 用途 |
|---|---|---|---|
| `z1` / `Gt` | **multiPort** | `http://127.0.0.1:${multiPort}` | **所有业务 API**（`/node/*`、`/group/*`、`/server/*`、`/data/*`、`/settings/*`、`/tunnel/*`） |
| `$D` | **gmPort** | `http://127.0.0.1:${gmPort}` | 桌面/终端通道：`/gmssh/{id}`、`/api/v1/stream/*`、`/web/lang/*.json` |
| `BD` | **browserPort** | `http://127.0.0.1:${browserPort}` | 内嵌浏览器 / Chromium 下载 |

> ⚠️ 关键点：**业务 API 走 multiPort，不是 gmPort**。本阶段可让 multiPort == gmPort（同一 NanoHTTPD 实例），另开一个端口给 browserPort。
> 前端在拿到端口前 `multiPort` 为 `undefined`，baseURL 会变成 `http://127.0.0.1:0` —— 因此 `get-port` 必须尽早可用。

---

## 1. 统一响应信封 【已确认】

```ts
{ code: number, msg: string, data: any }
```

前端拦截器（`z1`）判定：

| code | 行为 |
|---|---|
| `0` / `200` | 成功，`resolve(response.data)`，调用处用 `res.data` 取载荷 |
| `200000` | `$D` 实例的成功码（桌面/终端通道） |
| `2` | 业务失败，`Promise.reject(msg)` |
| `3` | **需要 sudo 密码**（`nu=3`；前端弹 sudo 输入框后重试） |
| `410/401/200410/200401` | `$D` 实例的鉴权失效（走 ga token 刷新） |
| 其它 | 失败，弹出 `msg` 作为 toast |

因此：失败时请返回 `HTTP 200 + {code:2, msg:"具体原因"}`，前端会把 `msg` 原样弹给用户 —— 这对「测试连接失败原因」是必需的。

---

## 2. 端点总览

- 静态扫描：包内共 **95** 条 `url + method` 调用点。
- 其中走本地服务（`Gt` 客户端，baseURL=multiPort）的：**43** 条。
- 其余走云端 `https://api.gmssh.com`（`/api/*`、`/user_service/*`、`/batch_command/*` 等），**不属于本地服务**。

### 2.1 本阶段必须实现（P1）

| # | 方法 | 路径 | 状态 |
|---|---|---|---|
| 1 | POST | `/data/status` | 已确认 |
| 2 | POST | `/data/open` | 已确认 |
| 3 | POST | `/node/list` | 已确认 |
| 4 | POST | `/node/add` | 已确认 |
| 5 | POST | `/node/edit` | 已确认 |
| 6 | POST | `/node/delete` | 已确认 |
| 7 | POST | `/node/test` | 已确认 |
| 8 | POST | `/group/list` | 已确认 |
| 9 | POST | `/group/add` | 已确认 |
| 10 | POST | `/settings/get` | 已确认 |
| 11 | POST | `/server/list` | 已确认 |
| 12 | POST | `/server/add` | 已确认 |
| 13 | POST | `/server/edit` | 已确认 |
| 14 | POST | `/server/updateLocal` | 已确认 |

---

## 3. 逐端点契约

### 3.1 `/data/status` 【已确认】

机器仓库（机器列表）的加密/密码保护状态。**访问门**：`encrypted=false` 直接放行。

```jsonc
// 请求
{}
// 响应
{ "code": 0, "msg": "ok", "data": { "encrypted": false, "hasPassword": false } }
```

- 调用处：`Cme()` → `Gt({url:"/data/status",method:"post",params:{noErrorTip:!0}})`
- 若返回 `encrypted: true`，前端会要求输入仓库密钥后调 `/data/open`。

### 3.2 `/data/open` 【已确认】

```jsonc
// 请求（推测字段名，依据 setPassword/removePassword 同族）
{ "password": "xxx" }
// 响应
{ "code": 0, "msg": "ok", "data": null }
```
同族：`/data/setPassword`、`/data/removePassword`、`/data/reset`、`/data/import`、`/data/export`（GET+blob）、`/data/importRaw`、`/data/exportRaw`、`/data/validateRawPassword`。

### 3.3 `/node/list` 【已确认】

```jsonc
// 请求
{ "cond": "", "groupId": "", "pageNum": 1, "pageSize": 10 }
// 响应
{ "code":0, "msg":"ok",
  "data": { "total": 1, "list": [ { /* node 对象，见 3.4 */ } ] } }
```

- 调用处：`oi({cond, groupId, pageNum, pageSize:10})`，`data.list` + `Number(data.total)`。
- 分页：`qs()` 依据 `total > 0 ? 已加载数 < total : list.length >= pageSize` 决定是否还有下一页。
- **列表项字段（已确认被读取）**：`id`、`name`、`ip`、`port`、`userName`、`groupId`、`desc`、`authType`、
  `status`（`2` = 在线）、`osName`、`lastUpdateTime`(秒)、`latencyMs`、`runTime`、
  `memoryTotal`/`memoryUsed`/`memUsedPct`、`processNum`、`disks[{device,used,total,used_pct}]`、`authStatus`、`isTop`。
- 前端会用 `tunnelRevisions[node.id]` / `browserRevisions[node.id]` 做增量刷新；本地服务返回时可不带（视为 0）。
- 未采集到运行信息的机器，前端只画“名称 + IP”，不要求 `status` 等字段存在。

### 3.4 `/node/add` 【已确认 — 来自 add-machine-modal 的默认表单模型】

表单默认值原文：
```js
{ name:null, host:{ip:null, port:"22"}, userName:"root",
  authType:"pass", authValue:null, groupId:"", desc:null, keyPass:null }
```

```jsonc
// 请求
{
  "name": "my-box",
  "host": { "ip": "127.0.0.1", "port": "22" },   // ⚠️ 端口是字符串
  "userName": "root",
  "authType": "pass",        // "pass" = 密码；"ca" = 私钥
  "authValue": "password",   // 密码明文 / 私钥全文（authType=ca 时）
  "keyPass": null,           // 私钥口令（选填）
  "groupId": "",
  "desc": null,
  "sudoPolicy": "provide",   // 可选："provide" | "skip"
  "sudoPassword": ""         // sudoPolicy=provide 时
}
// 响应
{ "code":0, "msg":"添加机器成功", "data": { "id": "n1" } }
```
- 调用 `io(...)` = `/node/add`（带 `rawBusinessError:true`）。
- `msg` 会被前端直接 `u.success(res.msg)`，所以文案可自定义（默认 `添加机器成功`）。
- 若需要 sudo：返回 `{code:3}`，前端弹「输入sudo密码」后带 `sudoPolicy/sudoPassword` 重试。
- 校验规则（前端）：name 2~30 字符、不含 `#%&+/=?<>:"|\`；port 1~65535（非 root 端口 >1024）；userName 2~32。

### 3.5 `/node/edit` 【已确认】
请求 = `/node/add` 的字段 + `id`。响应 `{code:0,msg:"编辑机器成功",data:{...}}`。

### 3.6 `/node/delete` 【已确认】
```jsonc
// 请求
{ "id": "n1" }
// 响应
{ "code":0, "msg":"删除成功", "data":null }
```

### 3.7 `/node/test` 【已确认 — 本阶段的核心】
```jsonc
// 请求（= 表单字段 + noErrorTip 控制位，not 落库）
{ "name":"t", "host":{"ip":"127.0.0.1","port":"22"}, "userName":"root",
  "authType":"ca", "authValue":"-----BEGIN OPENSSH PRIVATE KEY-----...",
  "keyPass":null, "groupId":"", "desc":null }
// 成功
{ "code":0, "msg":"测试成功", "data":null }
// 失败（msg 会被原样 toast 给用户）
{ "code":2, "msg":"Auth fail: all auth methods failed" }
```
- 调用：`Ze = (...)=> Ma({...dn(), noErrorTip})` = `/node/test`。
- 前端流程：测试失败 → 弹「错误原因：{msg}」+「监测到无法连接到当前服务器，是否继续？」是/否；
  测试成功 → 直接添加。
- 批量导入页也用同一端点，`authType:"pass"` 固定。

### 3.8 `/group/list` 【已确认】
```jsonc
// 请求 {}  → 响应
{ "code":0, "msg":"ok", "data": { "list": [ { "id":"g1", "name":"默认" } ] } }
```
（前端：`this.groupList = e.data?.list?.map(n=>({...n, originalName:n.name}))`）

### 3.9 `/group/add` 【已确认】
```jsonc
// 请求 { "name":"默认" }  → 响应 { "code":0,"msg":"添加成功","data":"g1" }   // data 就是新 id
```
同族：`/group/edit`、`/group/delete`、`/group/setOrder`（`{id, order}` 推测）。

### 3.10 `/settings/get` 【已确认】
```jsonc
// 请求 {} → 响应 { "code":0, "msg":"ok", "data": {} }
```
`data` 为设置字典；本阶段返回空对象即可（前端各处都有 `?.` 兜底）。

### 3.11 `/server/*` 【已确认 — 注意语义】
`/server/*` 是**远端云端服务器列表**（「同步到云端 / 从云端同步」「云计算」），**不是**本机机器列表。
`添加机器` 弹窗走的是 `/node/*`。两条线都要实现，但 P1 的 UI 主链路只用 `/node/*`。

| 端点 | 请求 | 响应 |
|---|---|---|
| `/server/list` | `{}` | `{code,msg,data:{list:[],total:0}}` |
| `/server/add` | `{name, host, port, userName, password/key...}`【推测】 | `{code,msg,data:{id}}`【推测】 |
| `/server/edit` | 同上 + `id`【推测】 | 同上【推测】 |
| `/server/updateLocal` | `{}` | `{code,msg,data:null}`【推测】：把云端列表落本地 |

### 3.12 本阶段返回 501 的端点（下阶段补）

`/node/detail`、`/node/proxy/detail`、`/node/refreshInfo`、`/node/reboot`、`/node/check-boot`、
`/node/autoRefresh`(GET)、`/node/autoRefresh/toggle`、`/node/setTop`、`/node/sort`、
`/node/bastionList`、`/node/clearAuthCache`、`/node/updateOSName`、`/node/sudoPassword/save`、
`/group/edit`、`/group/delete`、`/group/setOrder`、
`/tunnel/{list,add,edit,delete,start,stop,updateName,validateTunnelPorts}`、
`/data/{import,setPassword,removePassword,reset,export}`、`/gmssh/{id}`。

> 但 `/node/bastionList`、`/node/autoRefresh`、`/node/sort`、`/node/setTop` 这几条**返回空集合/成功**比 501 更好，
> 否则前端会弹错误 toast 打断「添加机器」主流程。实现中按“空实现成功”处理，日志标 `STUB`。

---

## 4. 第二阶段：终端 / 实时通道协议 【已确认，来自 initAuth 与 machine-manage】

### 4.1 机器状态推送 WebSocket
```
ws://127.0.0.1:<multiPort>/node/ws/refreshInfo
```
服务端推送 JSON 文本帧：
```jsonc
{ "type":"tunnel_running_snapshot", "data":[ {"nodeId":"n1","hasTunnel":true} ] }
{ "type":"tunnel_running_update",   "data": {"nodeId":"n1","hasTunnel":true} }
{ "type":"...", "data": {"nodeId":"n1","hasBrowser":true} }
"pong"   // 心跳应答
```
客户端会发 `"ping"`，服务端需回 `"pong"`。

### 4.2 终端/桌面连接（gmPort）
```
ws://127.0.0.1:<gmPort>/api/v1/stream/login      // subprotocol: "123"
```
**客户端首帧（加密）**：
```jsonc
{ "encrypted_data": "<RSA 加密后的字符串>", "is_browser": false }
```
其中 `encrypted_data` = 用 JS 里内嵌的 **RSA 公钥** 做 `encryptUnicodeLong` 得到的密文；
明文是：
```jsonc
{ "host":"1.2.3.4", "port":"22", "user":"root", "auth_type":"ca",
  "auth_value":"<密码或私钥>", "key_pass":"", "sudo_password":"",
  "sign":"<SHA256withRSA 签名>", "syn":"<变量>" }
```
**服务端应答**：
```jsonc
{ "code":200000, "data": { "data": { "token":"...", "init_ok":1 } } }
```
- 拿到 `token` 后前端连 `ws://127.0.0.1:<gmPort>/api/v1/stream/init`（【推测】：`init` 端点为真正数据通道，
  需带 token；`code!==200000` 视为失败；`tcpForwardingDisabled` 为单独错误位）。
- 桌面/终端 UI 资源：`http://127.0.0.1:<gmPort>/gmssh/{nodeId}`（拿桌面 token `{data:{token,rand}}`）、
  `http://127.0.0.1:<gmPort>/newweb/#<id>?...`、`http://127.0.0.1:<browserPort>/web/lang/{lang}.json`。

**结论（对下一阶段的影响）**：终端通道**不是**裸 WS 转发，而是「RSA 加密的凭据握手 → 换 token → init 通道」。
服务端必须能从 JS 里内嵌公钥对应的私钥解密，或改成直接信任 token —— 这是 P2 的主要未知点。

---

## 5. 已确认的额外约束

1. **CORS**：桌面版页面来自 `file://`/`https://appassets.androidplatform.net`，请求打到 `http://127.0.0.1:<port>`。
   必须返回 `Access-Control-Allow-Origin: *`、`Access-Control-Allow-Headers: *`、`Access-Control-Allow-Methods: *`，
   并对 `OPTIONS` 预检返回 204（`Content-Type: application/json` 会触发预检）。
2. **请求体**：一律 `application/json`，POST。
3. `noErrorTip:true` 只是 query 参数，服务端可忽略 —— 但 `code:2` 时前端仍会读 `msg`。
4. 语言头：`lang: zh|en`。
5. 鉴权头：`Authorization: Bearer <gmssh_user_token>`（云端用；本地服务 P1 忽略）。
