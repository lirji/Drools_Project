# 前端 OIDC 登录（授权码 + PKCE）· 实现级设计

> Track B 前端项。**为什么是设计而非落码**：① 按全局规范，中型前端特性先出可执行设计；② 本环境无法做浏览器 E2E（真 Casdoor 重定向/回调），落了也验不了；③ **依赖尚未具备的前置**——需一个 authorization_code+PKCE 的 Casdoor **SPA 公有应用**（现有 `activity-{tenant}-cid` 仅 client_credentials，见 P0-3），且 P0-3 只做了机器决策平面，控制台**人流**（用户身份/审批）本就延后。本设计实现级、可直接照做，落地时机 = 控制台人流启动时。

## 0. 现状与缺口

- **后端已就绪**：`auth.enabled=true` 时 `/activity-marketing/**` 走 Casdoor 验签，租户从 JWT `aud` 解析（`ActivityResourceServerConfig` + `JwtTenantFilter`）。浏览器只需**带上 Bearer token**即可。
- **前端现状**（`activity.js`）：`api(method,path,body)` 给每个请求加 `X-Tenant-Id`（dev/header 档）；租户来自 localStorage 的 `state.tenant`（默认 acme），有租户切换栏。注释已写明「Casdoor 档需登录换 token，本 demo 未接」。
- **缺口**：auth 档下前端没有登录 → 拿不到 token → `/activity-marketing/**` 全 401。需补：登录（authorize+PKCE）→ 存 token → 请求带 Bearer → 租户显示改为「来自 token 的 aud」。

## 1. Casdoor 侧前置（SPA 公有应用，需 provision）

M2M 应用不能用于浏览器登录（无 authorization_code、无 redirect）。需**新建一个 SPA 应用**（或复用 auth-console 的 shared app 模型）：

- `grantTypes`: `["authorization_code","refresh_token"]`
- `redirectUris`: 含 demo 源，如 `http://localhost:8081/activity.html`（或专用 `/callback`）
- **PKCE**：Casdoor 支持 `code_challenge_method=S256`（`code_challenge_methods_supported` 含 S256）
- **公有客户端**：浏览器**不放** client_secret；PKCE 取代 secret 防截获
- 租户来源仍是 `aud`——SPA 应用的 client_id 需能被 `AudienceTenantResolver` 反解成租户：
  - 单应用多租户（shared app + `orgChoiceMode`）：登录选组织，token `aud` = 派生 client_id；需把该 client_id 纳入 `client-tenant-map` 或 `audience-templates`。
  - 或每租户一个 SPA 应用 `activity-{tenant}-web-cid`——但 `audience-templates` 现模板是 `activity-{tenant}-cid`，需加一条 `activity-{tenant}-web-cid` 模板或走 `client-tenant-map`。

> 已知 Casdoor 端点（openid-config 实测）：authorize=`/login/oauth/authorize`、token=`/api/login/oauth/access_token`、logout=`/api/logout`。

## 2. 前端模式感知（新增轻量配置端点）

前端要知道「auth 开没开、authorize 端点/clientId 是什么」。加一个**匿名可读**的配置端点（放链二 permitAll，不泄密——只暴露公开的 OIDC 参数）：

```
GET /activity-marketing/auth-config
→ { "authEnabled": true, "issuer": "http://localhost:8000",
    "authorizeEndpoint": ".../login/oauth/authorize",
    "tokenEndpoint": ".../api/login/oauth/access_token",
    "clientId": "<spa client_id>", "redirectUri": "<demo>/activity.html", "scope": "openid profile" }
```
`authEnabled=false`（默认）→ 前端保持现有 dev/header 租户栏，一行不变。

## 3. activity.js 改动（vanilla，沿用现有风格）

### 3.1 PKCE 助手（Web Crypto）
```js
function b64url(buf){ return btoa(String.fromCharCode.apply(null,new Uint8Array(buf)))
  .replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,''); }
function randomVerifier(){ var a=new Uint8Array(32); crypto.getRandomValues(a);
  return b64url(a.buffer); }
function challenge(verifier){ return crypto.subtle.digest('SHA-256',
  new TextEncoder().encode(verifier)).then(b64url); }
```

### 3.2 登录（authorize 重定向）
```js
function login(cfg){
  var verifier = randomVerifier(), st = randomVerifier();
  sessionStorage.setItem('pkce_v', verifier);
  sessionStorage.setItem('oauth_state', st);
  challenge(verifier).then(function(chal){
    var u = new URL(cfg.authorizeEndpoint);
    u.search = new URLSearchParams({ response_type:'code', client_id:cfg.clientId,
      redirect_uri:cfg.redirectUri, scope:cfg.scope, state:st,
      code_challenge:chal, code_challenge_method:'S256' }).toString();
    window.location.assign(u.toString());
  });
}
```

### 3.3 回调（code → token，PKCE 无 secret）
```js
function handleCallback(cfg){
  var p = new URLSearchParams(window.location.search);
  var code = p.get('code'); if(!code) return Promise.resolve(false);
  if(p.get('state') !== sessionStorage.getItem('oauth_state')) throw new Error('state 不匹配(CSRF)');
  return fetch(cfg.tokenEndpoint, { method:'POST',
    headers:{'Content-Type':'application/x-www-form-urlencoded'},
    body:new URLSearchParams({ grant_type:'authorization_code', code:code,
      redirect_uri:cfg.redirectUri, client_id:cfg.clientId,
      code_verifier:sessionStorage.getItem('pkce_v') }) })
   .then(function(r){return r.json();})
   .then(function(t){ storeToken(t.access_token, t.expires_in, t.refresh_token);
     history.replaceState({}, '', cfg.redirectUri); return true; });
}
```

### 3.4 token 存储 + 租户显示
- 存 `sessionStorage`（不用 localStorage：降低 XSS 持久窃取面；页面关闭即清）。记 `expires_at`。
- 租户从 token 的 `aud` 派生显示（`JSON.parse(atob(jwt.split('.')[1])).aud`）——auth 档**租户由 token 定**，隐藏/禁用手动租户切换栏（信封 `X-Tenant-Id` 后端只校验、≠aud→403）。

### 3.5 请求带 Bearer（改 `api()`）
```js
function api(method, path, body){
  var opts={ method:method, headers:{} };
  if (AUTH.enabled) {
    if (isExpiring()) return refresh().then(function(){ return api(method,path,body); });
    opts.headers['Authorization'] = 'Bearer ' + AUTH.token;   // 租户由 token aud 定，不再发 X-Tenant-Id
  } else if (state.tenant) {
    opts.headers['X-Tenant-Id'] = state.tenant;               // dev/header 档不变
  }
  /* ...原逻辑... */
}
```
- 401 拦截：token 失效 → 触发 `login()` 重新走授权码。

### 3.6 silent refresh + 登出
- `refresh()`：用 `refresh_token` 换新 token（token 端点 `grant_type=refresh_token`）；失败回落 `login()`。
- `logout()`：清 sessionStorage token + 重定向 Casdoor `end_session_endpoint`（`/api/logout`）。

## 4. 安全要点
- **公有客户端不放 client_secret**；PKCE(S256) 防授权码截获。
- **state** 防 CSRF；**nonce**（可选）防重放。
- token 存 sessionStorage、内存优先；避免 localStorage 持久化。
- 信封 `X-Tenant-Id` 在 auth 档**不发**（后端只认 token aud；发了≠aud 会 403）。
- CORS：token 端点 POST 需 Casdoor 允许 demo 源（Casdoor 默认允许；生产按需配）。

## 5. 验证计划（需浏览器，手动）
1. Casdoor 建 SPA 应用（§1）+ 把其 client_id 纳入 `client-tenant-map`/模板。
2. app 起 `auth.enabled=true`；浏览器开 `/activity.html` → 点登录 → Casdoor 登录选组织 → 回调 → 列表加载（带 Bearer）。
3. 切组织重登 → 数据视图随 token aud 变（跨租户隔离在浏览器可见）。
4. token 过期 → silent refresh 或重新登录。

## 6. 落地边界（诚实）
- 依赖 §1 的 Casdoor SPA 应用 provision（本轮未做，避免建无法验证的半成品）。
- 浏览器 E2E 需真人操作，本环境不可自动化验证。
- 与 P1-8 四眼共享「控制台人流」前置（用户身份/审批状态机），建议一并做（见 `51-*.md §5`）。
