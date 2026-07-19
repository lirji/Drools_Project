/*
 * 活动营销子应用 (阶段 3b) —— 报表式表单 + 递归条件树构建器 + 优惠验证。
 *
 * 架构 (FRONTEND_PLAN.md Option C): 独立 IIFE, 通过 window.ActivityApp.mount(panel) 接管 #panel。
 * 复用 app.js 闭包里的 helper —— 只经 window.DemoUI 拿 (mount 时惰性读)。
 * 下拉全部由 GET /activity-marketing/field-dict 驱动 (防与后端枚举漂移)；条件树只能从白名单拼装。
 */
(function () {
  "use strict";

  var BASE = "/activity-marketing";
  var TENANT_KEY = "actTenant";
  var UI, el, clear, $, card, kv, tagList, boolPill, fmtMoney;

  var state = { dict: null, route: "list", panel: null, draft: null, tenant: readTenant() };

  /* ───────────── OIDC 登录 (授权码+PKCE, 52-frontend-oidc-login-design.md) ─────────────
   * auth 档 (GET /auth-config 返回 authEnabled=true) 时: 未登录先渲染登录页 → Casdoor authorize
   * (PKCE S256, 公有客户端无 secret) → 回调 ?code= 换 token → 请求带 Bearer, 租户由 token 的 aud 定,
   * 不再发 X-Tenant-Id (发了且≠aud 会被后端 403)。authEnabled=false 时本节全部旁路, dev 租户栏一行不变。
   * token 存 sessionStorage (页面关闭即清, 不用 localStorage 降低 XSS 持久窃取面)。 */
  var AUTH = { cfg: null, token: null, refresh: null, expiresAt: 0 };
  var SS_TOKEN = "actOidcTok", SS_VERIFIER = "actPkceV", SS_STATE = "actOauthState", SS_CID = "actOauthCid";

  function authOn() { return !!(AUTH.cfg && AUTH.cfg.authEnabled); }

  function ensureAuthConfig() {
    if (AUTH.cfg) return Promise.resolve(AUTH.cfg);
    return fetch(BASE + "/auth-config").then(function (r) { return r.json(); })
      .then(function (cfg) { AUTH.cfg = cfg || { authEnabled: false }; restoreToken(); return AUTH.cfg; })
      .catch(function () { AUTH.cfg = { authEnabled: false }; return AUTH.cfg; });
  }

  function restoreToken() {
    try {
      var raw = window.sessionStorage.getItem(SS_TOKEN);
      if (!raw) return;
      var t = JSON.parse(raw);
      AUTH.token = t.token; AUTH.refresh = t.refresh; AUTH.expiresAt = t.expiresAt || 0;
    } catch (e) { /* 解析失败当未登录 */ }
  }
  function storeToken(accessToken, expiresIn, refreshToken) {
    AUTH.token = accessToken || null;
    AUTH.refresh = refreshToken || AUTH.refresh;
    AUTH.expiresAt = Date.now() + (Number(expiresIn) || 3600) * 1000;
    try { window.sessionStorage.setItem(SS_TOKEN, JSON.stringify({ token: AUTH.token, refresh: AUTH.refresh, expiresAt: AUTH.expiresAt })); } catch (e) {}
  }
  function clearToken() {
    AUTH.token = null; AUTH.refresh = null; AUTH.expiresAt = 0;
    try {
      window.sessionStorage.removeItem(SS_TOKEN);
      window.sessionStorage.removeItem(SS_VERIFIER);
      window.sessionStorage.removeItem(SS_STATE);
      window.sessionStorage.removeItem(SS_CID);
    } catch (e) {}
  }
  function isExpiring() { return AUTH.expiresAt > 0 && Date.now() > AUTH.expiresAt - 30000; }

  /* JWT payload 观察 (不验签——验签是后端的事, 前端只取展示信息) */
  function jwtPayload(tok) {
    try {
      var b = tok.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
      return JSON.parse(atob(b));
    } catch (e) { return {}; }
  }
  function tokenAud() {
    var aud = jwtPayload(AUTH.token || "").aud;
    return Array.isArray(aud) ? aud[0] : (aud || "");
  }
  /** 登录租户: 由 token 的 aud 反查 webClients (clientId→tenant); 查不到显示原 aud。 */
  function tokenTenant() {
    var aud = tokenAud();
    var hit = ((AUTH.cfg && AUTH.cfg.webClients) || []).filter(function (w) { return w.clientId === aud; })[0];
    return hit ? hit.tenant : aud;
  }
  /** 操作者展示名: Casdoor 用户 token 的 sub 是 UUID, 展示优先 name/preferred_username (后端四眼仍用原始 sub)。 */
  function tokenSub() {
    var p = jwtPayload(AUTH.token || "");
    return p.name || p.preferred_username || p.sub || "";
  }

  /* PKCE 助手 (Web Crypto) */
  function b64url(buf) {
    return btoa(String.fromCharCode.apply(null, new Uint8Array(buf)))
      .replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  }
  function randomVerifier() { var a = new Uint8Array(32); crypto.getRandomValues(a); return b64url(a.buffer); }
  function challenge(verifier) {
    return crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier)).then(b64url);
  }

  /** 发起登录: 记 verifier/state/clientId → 重定向 Casdoor authorize。 */
  function login(clientId) {
    var verifier = randomVerifier(), st = randomVerifier();
    try {
      window.sessionStorage.setItem(SS_VERIFIER, verifier);
      window.sessionStorage.setItem(SS_STATE, st);
      window.sessionStorage.setItem(SS_CID, clientId);
    } catch (e) { alert("sessionStorage 不可用，无法登录（隐私模式？）"); return; }
    challenge(verifier).then(function (chal) {
      var u = new URL(AUTH.cfg.authorizeEndpoint);
      u.search = new URLSearchParams({
        response_type: "code", client_id: clientId, redirect_uri: AUTH.cfg.redirectUri,
        scope: AUTH.cfg.scope || "openid profile", state: st,
        code_challenge: chal, code_challenge_method: "S256",
      }).toString();
      window.location.assign(u.toString());
    });
  }

  /** 回调着陆: ?code= → token 端点换 token (公有客户端: code_verifier, 无 secret)。 */
  function handleCallback() {
    var p = new URLSearchParams(window.location.search);
    var code = p.get("code");
    if (!code) return Promise.resolve(false);
    if (p.get("state") !== window.sessionStorage.getItem(SS_STATE)) {
      return Promise.reject(new Error("state 不匹配 (可能的 CSRF)，已拒绝回调"));
    }
    return ensureAuthConfig().then(function () {
      return fetch(AUTH.cfg.tokenEndpoint, {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({
          grant_type: "authorization_code", code: code,
          redirect_uri: AUTH.cfg.redirectUri,
          client_id: window.sessionStorage.getItem(SS_CID) || "",
          code_verifier: window.sessionStorage.getItem(SS_VERIFIER) || "",
        }),
      });
    }).then(function (r) { return r.json(); }).then(function (t) {
      if (!t.access_token) throw new Error("换 token 失败: " + JSON.stringify(t));
      storeToken(t.access_token, t.expires_in, t.refresh_token);
      window.sessionStorage.removeItem(SS_VERIFIER);
      window.sessionStorage.removeItem(SS_STATE);
      window.history.replaceState({}, "", window.location.pathname); // 清 ?code= 防刷新重放
      return true;
    });
  }

  /** silent refresh: refresh_token 换新 token; 失败清 token 回登录页。 */
  function refreshToken() {
    if (!AUTH.refresh) { clearToken(); return Promise.reject(new Error("无 refresh_token")); }
    return fetch(AUTH.cfg.tokenEndpoint, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "refresh_token", refresh_token: AUTH.refresh,
        client_id: window.sessionStorage.getItem(SS_CID) || "",
      }),
    }).then(function (r) { return r.json(); }).then(function (t) {
      if (!t.access_token) { clearToken(); throw new Error("refresh 失败"); }
      storeToken(t.access_token, t.expires_in, t.refresh_token);
    });
  }

  function logout() {
    clearToken();
    state.dict = null; // 换身份后字典按新租户重取
    if (state.panel) mount(state.panel);
  }

  /* ───────────── 多租户 (P0-4/P0-3) ─────────────
   * dev/header 档: 所有请求带 X-Tenant-Id, 后端 @TenantId 按租户隔离数据; 切租户即换数据视图。
   * Casdoor 档 (auth.enabled=true) 由 JWT 的 aud 定租户, 那时需登录换 token (本 demo 未接前端登录, 见文档)。 */
  function readTenant() {
    try { return window.localStorage.getItem(TENANT_KEY) || "acme"; } catch (e) { return "acme"; }
  }
  function setTenant(t) {
    state.tenant = t || "";
    try { window.localStorage.setItem(TENANT_KEY, state.tenant); } catch (e) { /* 隐私模式忽略 */ }
  }

  /* ───────────── fetch ───────────── */
  function api(method, path, body) {
    // auth 档: token 快过期先 silent refresh 再发 (refresh 失败会 clearToken, 下方走未登录分支渲染登录页)
    if (authOn() && AUTH.token && isExpiring()) {
      return refreshToken().then(
        function () { return api(method, path, body); },
        function () { if (state.panel) mount(state.panel); return { ok: false, status: 401, json: null, text: "" }; }
      );
    }
    var opts = { method: method, headers: {} };
    if (authOn()) {
      if (AUTH.token) opts.headers["Authorization"] = "Bearer " + AUTH.token; // 租户由 token aud 定, 不发 X-Tenant-Id
    } else if (state.tenant) {
      opts.headers["X-Tenant-Id"] = state.tenant; // dev/header 档不变
    }
    if (body !== undefined && body !== null) {
      opts.headers["Content-Type"] = "application/json";
      opts.body = JSON.stringify(body);
    }
    return fetch(BASE + path, opts).then(function (res) {
      if (authOn() && res.status === 401) { clearToken(); if (state.panel) mount(state.panel); }
      return res.text().then(function (t) {
        var j = null;
        try { j = t ? JSON.parse(t) : null; } catch (e) { /* 非 JSON */ }
        return { ok: res.ok, status: res.status, json: j, text: t };
      });
    });
  }

  /* ───────────── 字典/工具 ───────────── */
  function fieldByKey(k) { return (state.dict.fields || []).filter(function (f) { return f.key === k; })[0]; }
  function opByCode(c) { return (state.dict.operators || []).filter(function (o) { return o.code === c; })[0]; }
  function operandOf(c) { var o = opByCode(c); return o ? o.operand : "SCALAR"; }
  function firstField() { return (state.dict.fields || [])[0]; }

  function uuid() { return "req-" + Date.now().toString(36) + "-" + Math.random().toString(36).slice(2, 8); }
  function numOrNull(v) { return (v === "" || v == null) ? null : Number(v); }
  function toEpoch(local) { return local ? new Date(local).getTime() : null; }
  function toLocalInput(ms) {
    if (ms == null) return "";
    var d = new Date(ms), p = function (n) { return (n < 10 ? "0" : "") + n; };
    return d.getFullYear() + "-" + p(d.getMonth() + 1) + "-" + p(d.getDate()) + "T" + p(d.getHours()) + ":" + p(d.getMinutes());
  }
  function isoToLocal(iso) { if (!iso) return ""; var ms = new Date(iso).getTime(); return isNaN(ms) ? "" : toLocalInput(ms); }

  /* ───────────── 通用 DOM helper ───────────── */
  function labeled(text, control, required) {
    return el("label", { class: "pp-row" }, [
      el("span", { class: "pp-name" }, [text, required ? el("span", { class: "field-req", text: "*" }) : null]),
      control,
    ]);
  }
  function inputEl(id, attrs) {
    var a = { id: id, class: "pp-input" };
    for (var k in attrs) if (attrs.hasOwnProperty(k)) a[k] = attrs[k];
    return el("input", a);
  }
  function selectEl(id, options, current, onChange) {
    var sel = el("select", { id: id, class: "select-input" },
      options.map(function (o) { return el("option", { value: String(o.v) }, [o.label]); }));
    sel.value = current == null ? "" : String(current);
    if (onChange) sel.addEventListener("change", function () { onChange(sel.value); });
    return sel;
  }
  function segToggle(options, current, onSelect) {
    return el("div", { class: "examples" }, options.map(function (o) {
      return el("button", {
        type: "button",
        class: "chip" + (String(o.v) === String(current) ? " chip-active" : ""),
        onclick: function () { onSelect(o.v); },
      }, [o.label]);
    }));
  }
  function primaryBtn(label, onClick, id) {
    return el("button", { type: "button", class: "run-btn", id: id || null, onclick: onClick },
      [el("span", { class: "run-ico", text: "▶" }), label]);
  }
  function banner(text, kind) { return el("div", { class: "act-banner " + (kind || "info"), text: text }); }

  /* ───────────── 头部子标签 ───────────── */
  function header() {
    var tabs = [
      { r: "list", label: "活动列表" },
      { r: "form", label: "新建活动" },
      { r: "validate", label: "优惠验证" },
    ];
    return el("div", {}, [
      el("div", { class: "demo-head" }, [
        el("h2", { text: "活动营销 · 报表配置台" }),
        el("p", { class: "demo-desc", text: "报表式创建活动 → 白名单条件树制定资格规则 → 上线 → 验证优惠命中。对接 /activity-marketing/*，规则由 Drools 执行。" }),
        authOn() ? authBar() : tenantBar(),
      ]),
      el("div", { class: "act-tabs" }, tabs.map(function (t) {
        return el("button", {
          type: "button",
          class: "act-tab" + (state.route === t.r ? " active" : ""),
          onclick: function () { if (t.r === "form") newDraft(); go(t.r); },
        }, [t.label]);
      })),
    ]);
  }

  /* 租户切换条 (多租户 demo)：改 X-Tenant-Id → 切数据视图，直观看隔离。 */
  function tenantBar() {
    var input = el("input", { class: "pp-input tenant-input", id: "act-tenant", value: state.tenant });
    function apply(v) { setTenant(v); go("list"); }
    input.addEventListener("change", function () { apply(input.value.trim()); });
    var quick = ["acme", "beta", "__dev__"].map(function (t) {
      return el("button", {
        type: "button",
        class: "chip tenant-chip" + (t === state.tenant ? " chip-active" : ""),
        onclick: function () { apply(t); },
      }, [t]);
    });
    return el("div", { class: "tenant-bar" }, [
      el("span", { class: "tenant-label", text: "租户 (X-Tenant-Id)" }),
      input,
    ].concat(quick).concat([
      el("span", { class: "tenant-hint", text: "切租户即换数据视图 —— 后端 @TenantId 按此隔离" }),
    ]));
  }

  /* auth 档身份条: 租户由 token 的 aud 定 (手动切换禁用——信封≠aud 会被后端 403), 换租户 = 登出重登。 */
  function authBar() {
    return el("div", { class: "tenant-bar" }, [
      el("span", { class: "tenant-label", text: "登录租户 (token aud)" }),
      el("span", { class: "chip chip-active", text: tokenTenant() || "-" }),
      el("span", { class: "tenant-hint", text: "操作者 " + (tokenSub() || "-") + " —— 租户由 Casdoor token 决定，切租户请登出后用另一租户账号登录" }),
      el("button", { type: "button", class: "chip", onclick: logout }, ["登出"]),
    ]);
  }

  /* auth 档未登录: 登录页 (每租户一个 SPA 应用, 点选租户发起 authorize+PKCE)。 */
  function renderLogin(panel) {
    var clients = (AUTH.cfg.webClients || []);
    clear(panel).appendChild(el("div", { class: "demo-head" }, [
      el("h2", { text: "活动营销 · 报表配置台" }),
      el("p", { class: "demo-desc", text: "已开启 Casdoor 鉴权 (auth 档)：访问活动数据需先登录，租户由登录应用的 token aud 决定。" }),
      banner("请选择租户登录 —— 跳转 Casdoor 完成授权码 + PKCE 登录后自动返回本页。", "info"),
      el("div", { class: "actions" }, clients.length ? clients.map(function (w) {
        return el("button", {
          type: "button", class: "run-btn", style: "margin-right:8px",
          onclick: function () { login(w.clientId); },
        }, [el("span", { class: "run-ico", text: "🔐" }), "登录 " + w.tenant]);
      }) : [banner("auth-config 未配置 web-client-map，无可用登录应用。", "err")]),
    ]));
  }

  /* ───────────── 路由 ───────────── */
  function go(route) { state.route = route; render(); }
  function render() {
    var panel = clear(state.panel);
    panel.appendChild(header());
    if (!state.dict) { panel.appendChild(banner("字段字典加载失败，无法渲染表单。请确认后端已启动。", "err")); return; }
    if (state.route === "list") renderList(panel);
    else if (state.route === "form") renderForm(panel);
    else if (state.route === "detail") renderDetail(panel);
    else if (state.route === "validate") renderValidate(panel);
  }

  /* ───────────── 列表 ───────────── */
  function renderList(panel) {
    var wrap = el("div", { class: "col" });
    wrap.appendChild(el("div", { class: "col-label", text: "全部活动 (当前版本)" }));
    var box = el("div", { class: "alist" });
    wrap.appendChild(box);
    panel.appendChild(wrap);

    box.appendChild(el("div", { class: "act-hint", text: "加载中…" }));
    api("GET", "/list").then(function (r) {
      clear(box);
      var rows = r.json || [];
      if (!rows.length) { box.appendChild(el("div", { class: "row-empty", text: "（暂无活动）— 点上方「新建活动」" })); return; }
      box.appendChild(el("div", { class: "alist-row alist-head" }, [
        el("span", { text: "活动ID" }), el("span", { text: "名称/业务线" }),
        el("span", { text: "类型" }), el("span", { text: "状态" }),
        el("span", { text: "版本" }), el("span", { text: "操作" }),
      ]));
      rows.forEach(function (a) { box.appendChild(listRow(a)); });
    });
  }
  function listRow(a) {
    var typeLabel = dictLabel("activityTypes", a.activityType);
    var stLabel = dictLabel("statuses", a.activityStatus);
    var stPill = el("span", { class: "pill " + (a.activityStatus === 1 ? "pill-yes" : "pill-no"), text: stLabel });
    var acts = el("div", { class: "alist-acts" }, [
      el("button", { type: "button", onclick: function () { state.detailId = a.activityId; go("detail"); } }, ["详情"]),
      el("button", { type: "button", onclick: function () { loadForEdit(a.activityId); } }, ["编辑"]),
      a.activityStatus === 1
        ? el("button", { type: "button", onclick: function () { changeStatus(a.activityId, a.version, 2); } }, ["下线"])
        : el("button", { type: "button", onclick: function () { changeStatus(a.activityId, a.version, 1); } }, ["上线"]),
    ]);
    return el("div", { class: "alist-row" }, [
      el("span", { class: "alist-id", text: a.activityId }),
      el("span", {}, [el("div", { text: a.activityName }), el("div", { class: "act-hint", text: a.bizLine || "-" })]),
      el("span", { text: typeLabel }),
      stPill,
      el("span", { class: "mono", text: "v" + a.version }),
      acts,
    ]);
  }
  function changeStatus(activityId, version, target) {
    api("POST", "/" + encodeURIComponent(activityId) + "/status", { version: version, targetStatus: target })
      .then(function (r) {
        if (!r.ok) { alert(errText(r)); return; }
        go("list");
      });
  }
  function dictLabel(dictKey, code) {
    var arr = state.dict[dictKey] || [];
    var hit = arr.filter(function (x) { return x.code === code; })[0];
    return hit ? hit.label : String(code);
  }
  function errText(r) { return (r.json && (r.json.error || r.json.message)) || ("HTTP " + r.status); }

  /* ───────────── 表单 draft ───────────── */
  function newDraft() {
    state.draft = {
      activityId: null, requestId: uuid(),
      activityType: 1, redMode: "fixed", bindMode: "manual", areaType: 1,
      name: "", bizLine: "mall", rule: "", priority: 1, inventory: 100,
      startLocal: toLocalInput(Date.now() - 3600000), endLocal: toLocalInput(Date.now() + 7 * 86400000),
      districtIds: "", amount: "", takeType: 1, unit: "元", strategy: "MAX",
      ladder: [], gifts: [], spu: [{ storeId: 1, spuId: "" }], pool: [{ poolId: "" }],
      tree: { logic: "AND", children: [] },
    };
  }

  function loadForEdit(activityId) {
    api("GET", "/" + encodeURIComponent(activityId)).then(function (r) {
      if (!r.ok) { alert(errText(r)); return; }
      var d = r.json, m = d.manage, rule = (d.rules || [])[0], cond = (d.conditions || [])[0];
      newDraft();
      var dr = state.draft;
      dr.activityId = activityId; dr.requestId = uuid();   // 编辑必须新铸 requestId, 否则被幂等短路
      dr.activityType = m.activityType; dr.name = m.activityName; dr.bizLine = m.bizLine || "";
      dr.rule = m.activityRule || ""; dr.priority = m.priority; dr.inventory = m.inventory;
      dr.areaType = m.activityAreaType || 1; dr.districtIds = m.districtIds || "";
      dr.startLocal = isoToLocal(m.activityStartTime); dr.endLocal = isoToLocal(m.activityEndTime);
      if (rule) {
        dr.takeType = rule.redPackageTakeType || 1; dr.unit = rule.redPackageAmountUnit || "元";
        if (rule.redPackageRangeAmount) { dr.redMode = "ladder"; dr.ladder = parseLadder(rule.redPackageRangeAmount); }
        else { dr.redMode = "fixed"; dr.amount = rule.redPackageAmount == null ? "" : rule.redPackageAmount; }
      }
      if (cond && cond.conditionTreeJson) { try { dr.tree = JSON.parse(cond.conditionTreeJson); } catch (e) {} }
      dr.gifts = (d.gifts || []).map(function (g) {
        return { batchId: g.batchId, giftName: g.giftName, giftType: g.giftType, giftNum: g.giftNum, absoluteAmount: g.absoluteAmount, rightType: g.rightType };
      });
      var manual = (d.bindings || []).filter(function (b) { return b.bindSource === 0; });
      var pools = (d.poolRefs || []);
      if (pools.length && !manual.length) { dr.bindMode = "pool"; dr.pool = pools.map(function (p) { return { poolId: p.poolId }; }); }
      else { dr.bindMode = "manual"; dr.spu = manual.length ? manual.map(function (b) { return { storeId: b.storeId, spuId: b.spuId }; }) : [{ storeId: 1, spuId: "" }]; }
      go("form");
    });
  }
  function parseLadder(json) {
    try {
      var arr = JSON.parse(json);
      return (arr || []).map(function (t) { return { min: t.min == null ? 0 : t.min, max: t.max == null ? "" : t.max, reward: t.reward == null ? "" : t.reward }; });
    } catch (e) { return []; }
  }

  /* ───────────── 表单渲染 ───────────── */
  function renderForm(panel) {
    var dr = state.draft;
    var grid = el("div", { class: "demo-grid" });
    var form = el("div", { class: "col" });
    var rail = el("div", { class: "col act-rail" });
    grid.appendChild(form); grid.appendChild(rail);
    panel.appendChild(grid);

    if (dr.activityId) form.appendChild(banner("编辑将生成新版本 (version+1)，且活动状态回到「待上线」，保存后需重新上线。", "warn"));

    // ① 基础信息
    form.appendChild(sectionTitle("① 活动基础信息"));
    var base = el("div", { class: "form-grid" });
    base.appendChild(labeled("活动名称", inputEl("am-name", { value: dr.name }), true));
    base.appendChild(labeled("业务线 (bizLine)", inputEl("am-bizline", { value: dr.bizLine, placeholder: "如 mall" })));
    base.appendChild(el("div", { class: "form-full" }, [
      el("span", { class: "pp-name", text: "活动类型" }),
      segToggle(enabledTypes(), dr.activityType, function (v) { dr.activityType = Number(v); saveScalars(); go("form"); }),
    ]));
    base.appendChild(labeled("优先级 (越小越优先)", inputEl("am-priority", { type: "number", value: dr.priority })));
    base.appendChild(labeled("库存", inputEl("am-inventory", { type: "number", value: dr.inventory })));
    base.appendChild(labeled("开始时间", inputEl("am-start", { type: "datetime-local", value: dr.startLocal }), true));
    base.appendChild(labeled("结束时间", inputEl("am-end", { type: "datetime-local", value: dr.endLocal }), true));
    base.appendChild(labeled("地域类型", selectEl("am-area", [{ v: 1, label: "全国" }, { v: 2, label: "指定地域" }], dr.areaType, function (v) { dr.areaType = Number(v); saveScalars(); go("form"); })));
    if (dr.areaType === 2) base.appendChild(labeled("地域IDs (逗号分隔)", inputEl("am-districts", { value: dr.districtIds })));
    base.appendChild(el("div", { class: "form-full" }, [labeled("活动说明 (外显)", el("textarea", { id: "am-rule", class: "code-input", rows: "2" }, [dr.rule || ""]))]));
    form.appendChild(base);

    // ② 红包规则 / ③ 买赠
    if (dr.activityType === 1) form.appendChild(redSection(dr));
    else if (dr.activityType === 5) form.appendChild(giftSection(dr));

    // ④ 商品绑定
    form.appendChild(bindSection(dr));

    // ⑤ 资格条件树
    form.appendChild(treeSection(dr));

    // ⑥ 合并策略
    form.appendChild(sectionTitle("⑥ 多活动合并策略"));
    form.appendChild(el("div", {}, [
      segToggle((state.dict.strategies || []).map(function (s) { return { v: s, label: s }; }), dr.strategy, function (v) { dr.strategy = v; saveScalars(); go("form"); }),
      el("div", { class: "act-hint", text: "注意：策略按 bizLine 生效，会影响同业务线其它活动。" }),
    ]));

    // 右栏
    rail.appendChild(el("div", { class: "col-label", text: "校验 & 提交" }));
    rail.appendChild(el("div", { class: "actions" }, [primaryBtn(dr.activityId ? "保存 (新版本)" : "保存活动", submitForm, "am-submit")]));
    rail.appendChild(el("div", { class: "status-line", id: "am-status" }, [el("span", { class: "status-idle", text: "尚未提交" })]));
    rail.appendChild(el("div", { id: "am-result" }));
    rail.appendChild(el("div", { id: "am-error" }));
  }

  function enabledTypes() {
    // 字典给全部类型, 但后端只接受红包(1)/买赠(5)
    return (state.dict.activityTypes || []).filter(function (t) { return t.code === 1 || t.code === 5; })
      .map(function (t) { return { v: t.code, label: t.label }; });
  }
  function sectionTitle(t) { return el("div", { class: "col-label", text: t }); }

  function redSection(dr) {
    var box = el("div", {});
    box.appendChild(sectionTitle("② 红包规则"));
    box.appendChild(segToggle([{ v: "fixed", label: "固定金额" }, { v: "ladder", label: "阶梯分档" }], dr.redMode, function (v) { dr.redMode = v; saveScalars(); go("form"); }));
    if (dr.redMode === "fixed") {
      var g = el("div", { class: "form-grid" });
      g.appendChild(labeled("红包金额", inputEl("am-amount", { type: "number", value: dr.amount, placeholder: "0 ~ 999999" })));
      g.appendChild(labeled("发放方式", selectEl("am-taketype", (state.dict.distributionModes || []).map(function (m) { return { v: m.code, label: m.label }; }), dr.takeType, null)));
      box.appendChild(g);
    } else {
      box.appendChild(dynRows("阶梯档位", ["起(min)", "止(max,空=无上限)", "奖励(reward)"], dr.ladder,
        function () { return { min: "", max: "", reward: "" }; },
        function (row, i) {
          return [
            inputEl("", { type: "number", value: row.min, oninput: function () { row.min = this.value; } }),
            inputEl("", { type: "number", value: row.max, oninput: function () { row.max = this.value; } }),
            inputEl("", { type: "number", value: row.reward, oninput: function () { row.reward = this.value; } }),
          ];
        }));
    }
    return box;
  }

  function giftSection(dr) {
    var box = el("div", {});
    box.appendChild(sectionTitle("③ 买赠赠品明细"));
    box.appendChild(dynRows("赠品", ["批次", "赠品名", "类型", "数量", "金额", "权益类型"], dr.gifts,
      function () { return { batchId: "", giftName: "", giftType: "PHYSICAL", giftNum: 1, absoluteAmount: 0, rightType: "GIFT" }; },
      function (row) {
        return [
          inputEl("", { value: row.batchId, oninput: function () { row.batchId = this.value; } }),
          inputEl("", { value: row.giftName, oninput: function () { row.giftName = this.value; } }),
          inputEl("", { value: row.giftType, oninput: function () { row.giftType = this.value; } }),
          inputEl("", { type: "number", value: row.giftNum, oninput: function () { row.giftNum = this.value; } }),
          inputEl("", { type: "number", value: row.absoluteAmount, oninput: function () { row.absoluteAmount = this.value; } }),
          inputEl("", { value: row.rightType, oninput: function () { row.rightType = this.value; } }),
        ];
      }));
    return box;
  }

  function bindSection(dr) {
    var box = el("div", {});
    box.appendChild(sectionTitle("④ 商品绑定"));
    box.appendChild(segToggle([{ v: "manual", label: "手动 SPU" }, { v: "pool", label: "商品池(自动圈选)" }], dr.bindMode, function (v) { dr.bindMode = v; saveScalars(); go("form"); }));
    if (dr.bindMode === "manual") {
      box.appendChild(dynRows("SPU 绑定", ["店铺ID", "SPU ID"], dr.spu,
        function () { return { storeId: 1, spuId: "" }; },
        function (row) {
          return [
            inputEl("", { type: "number", value: row.storeId, oninput: function () { row.storeId = this.value; } }),
            inputEl("", { type: "number", value: row.spuId, oninput: function () { row.spuId = this.value; } }),
          ];
        }));
    } else {
      box.appendChild(el("div", { class: "act-hint", text: "填商品池 ID (demo 种子池为 1)，保存时后端按池规则圈选 demo_product 并自动绑定。" }));
      box.appendChild(dynRows("商品池", ["Pool ID"], dr.pool,
        function () { return { poolId: "" }; },
        function (row) { return [inputEl("", { type: "number", value: row.poolId, oninput: function () { row.poolId = this.value; } })]; }));
    }
    return box;
  }

  // 通用动态行表。cells(row,i)->[控件]；onSync 可选(用于 pool 那种包一层的)
  function dynRows(label, headers, arr, makeRow, cells, onSync) {
    var wrap = el("div", { class: "row-group" });
    var cols = headers.length;
    var grid = "grid-template-columns: 40px repeat(" + cols + ", 1fr) 48px;";
    function rebuild() {
      clear(wrap);
      var head = el("div", { class: "row-head" }, [el("span", { text: "#" })].concat(headers.map(function (h) { return el("span", { text: h }); })).concat([el("span", {})]));
      head.setAttribute("style", grid);
      wrap.appendChild(head);
      if (!arr.length) wrap.appendChild(el("div", { class: "row-empty", text: "暂无，点下方添加" }));
      arr.forEach(function (row, i) {
        var r = el("div", { class: "dyn-row" }, [el("span", { class: "mono", text: String(i + 1) })]
          .concat(cells(row, i))
          .concat([el("button", { type: "button", class: "row-del", "aria-label": "删除第" + (i + 1) + "行", onclick: function () { arr.splice(i, 1); if (onSync) onSync(arr); rebuild(); } }, ["✕"])]));
        r.setAttribute("style", grid);
        wrap.appendChild(r);
      });
      wrap.appendChild(el("button", { type: "button", class: "row-add", onclick: function () { arr.push(makeRow()); if (onSync) onSync(arr); rebuild(); } }, ["+ 添加" + label]));
    }
    rebuild();
    return wrap;
  }

  /* ───────────── 条件树 ───────────── */
  function treeSection(dr) {
    var box = el("div", {});
    box.appendChild(sectionTitle("⑤ 资格条件 (白名单条件树)"));
    box.appendChild(el("div", { class: "act-hint", text: "空条件树 = 所有用户恒通过。字段/运算符只能从后端白名单选，服务端翻译成受控 Drools，不接受裸 DRL。" }));
    var ctree = el("div", { class: "ctree", id: "am-ctree" });
    box.appendChild(ctree);
    renderTree(ctree, dr.tree);
    var pv = el("div", {}, [
      el("button", { type: "button", class: "ctree-mini", onclick: previewTree }, ["预览条件 (试编译)"]),
      el("span", { id: "am-preview-status", style: "margin-left:8px" }),
      el("div", { id: "am-preview-box" }),
    ]);
    box.appendChild(pv);
    return box;
  }
  function renderTree(box, root) { clear(box); box.appendChild(renderNode(root, 0, null, -1)); }
  function reTree() { renderTree($("am-ctree"), state.draft.tree); }

  function emptyLeaf() {
    var f = firstField();
    var op = (f.operators || [])[0];
    return { field: f.key, op: op, value: emptyValue(operandOf(op)) };
  }
  function emptyValue(operand) { return operand === "RANGE" ? ["", ""] : operand === "LIST" ? [] : ""; }

  function renderNode(node, depth, parentArr, idx) {
    if (node.logic) return renderGroup(node, depth, parentArr, idx);
    return renderLeaf(node, parentArr, idx);
  }

  function renderGroup(node, depth, parentArr, idx) {
    var head = el("div", { class: "ctree-group-head" }, [
      segToggle([{ v: "AND", label: "且 AND" }, { v: "OR", label: "或 OR" }], node.logic, function (v) { node.logic = v; reTree(); }),
      el("span", { class: "spacer" }),
      el("button", { type: "button", class: "ctree-mini", onclick: function () { node.children.push(emptyLeaf()); reTree(); } }, ["+ 条件"]),
      el("button", { type: "button", class: "ctree-mini", disabled: depth >= 4 ? "disabled" : null, onclick: function () { if (depth < 4) { node.children.push({ logic: "AND", children: [] }); reTree(); } } }, ["+ 分组"]),
      parentArr ? el("button", { type: "button", class: "ctree-mini", onclick: function () { parentArr.splice(idx, 1); reTree(); } }, ["🗑 删组"]) : null,
    ]);
    var kids = el("div", { class: "ctree-children" });
    if (!node.children.length) kids.appendChild(el("div", { class: "ctree-empty", text: "空分组：添加条件或删除 (提交时会自动剪除)" }));
    node.children.forEach(function (c, i) { kids.appendChild(renderNode(c, depth + 1, node.children, i)); });
    return el("div", { class: "ctree-group" }, [head, kids]);
  }

  function renderLeaf(node, parentArr, idx) {
    var f = fieldByKey(node.field) || firstField();
    var fieldSel = selectEl("", (state.dict.fields || []).map(function (x) { return { v: x.key, label: x.label }; }), node.field, function (v) {
      node.field = v; var nf = fieldByKey(v); node.op = (nf.operators || [])[0]; node.value = emptyValue(operandOf(node.op)); reTree();
    });
    var opSel = selectEl("", (f.operators || []).map(function (c) { var o = opByCode(c); return { v: c, label: o ? o.label : c }; }), node.op, function (v) {
      node.op = v; node.value = emptyValue(operandOf(v)); reTree();
    });
    return el("div", { class: "ctree-leaf" }, [
      fieldSel, opSel, valueControl(node),
      el("button", { type: "button", class: "row-del", "aria-label": "删除条件", onclick: function () { parentArr.splice(idx, 1); reTree(); } }, ["✕"]),
    ]);
  }

  function valueControl(node) {
    var operand = operandOf(node.op);
    if (operand === "RANGE") {
      if (!Array.isArray(node.value) || node.value.length !== 2) node.value = ["", ""];
      return el("div", { class: "ctree-value" }, [
        inputEl("", { class: "pp-input", value: node.value[0], placeholder: "下界", oninput: function () { node.value[0] = this.value; } }),
        el("span", { text: "~" }),
        inputEl("", { class: "pp-input", value: node.value[1], placeholder: "上界", oninput: function () { node.value[1] = this.value; } }),
      ]);
    }
    if (operand === "LIST") {
      var listStr = Array.isArray(node.value) ? node.value.join(",") : "";
      return el("div", { class: "ctree-value" }, [
        inputEl("", { class: "pp-input", value: listStr, placeholder: "逗号分隔多个值", oninput: function () { node.value = this.value.split(",").map(function (s) { return s.trim(); }).filter(function (s) { return s !== ""; }); } }),
      ]);
    }
    return el("div", { class: "ctree-value" }, [
      inputEl("", { class: "pp-input", value: node.value == null ? "" : node.value, placeholder: "值", oninput: function () { node.value = this.value; } }),
    ]);
  }

  function pruneTree(node) {
    if (node && node.logic) {
      var kids = (node.children || []).map(pruneTree).filter(function (x) { return x !== null; });
      if (!kids.length) return null;
      return { logic: node.logic, children: kids };
    }
    return node;
  }

  function previewTree() {
    var st = $("am-preview-status"), boxEl = clear($("am-preview-box"));
    var pruned = pruneTree(state.draft.tree);
    if (!pruned) { st.innerHTML = ""; st.appendChild(el("span", { class: "status-pill status-pending", text: "空条件树：所有用户恒通过" })); return; }
    st.innerHTML = ""; st.appendChild(el("span", { class: "status-pill status-pending", text: "编译中…" }));
    api("POST", "/preview", pruned).then(function (r) {
      clear(st);
      var j = r.json || {};
      if (j.ok) {
        st.appendChild(el("span", { class: "status-pill status-ok", text: "✓ " + (j.message || "编译通过") }));
        if (j.drl) boxEl.appendChild(el("div", { class: "mono-box", text: j.drl }));
      } else {
        st.appendChild(el("span", { class: "status-pill status-error", text: "✗ 条件非法" }));
        boxEl.appendChild(el("div", { class: "act-banner err", text: j.message || errText(r) }));
      }
    });
  }

  /* ───────────── 保存 ───────────── */
  function saveScalars() {
    var dr = state.draft;
    if ($("am-name")) dr.name = $("am-name").value;
    if ($("am-bizline")) dr.bizLine = $("am-bizline").value;
    if ($("am-rule")) dr.rule = $("am-rule").value;
    if ($("am-priority")) dr.priority = $("am-priority").value;
    if ($("am-inventory")) dr.inventory = $("am-inventory").value;
    if ($("am-start")) dr.startLocal = $("am-start").value;
    if ($("am-end")) dr.endLocal = $("am-end").value;
    if ($("am-districts")) dr.districtIds = $("am-districts").value;
    if ($("am-amount")) dr.amount = $("am-amount").value;
    if ($("am-taketype")) dr.takeType = Number($("am-taketype").value);
  }

  function submitForm() {
    saveScalars();
    var dr = state.draft;
    var body = {
      requestId: dr.requestId,
      activityId: dr.activityId,
      activityName: dr.name,
      bizLine: dr.bizLine || null,
      activityType: dr.activityType,
      activityRule: dr.rule || null,
      activityStartTime: toEpoch(dr.startLocal),
      activityEndTime: toEpoch(dr.endLocal),
      activityAreaType: dr.areaType,
      districtIds: dr.areaType === 2 ? (dr.districtIds || null) : null,
      priority: numOrNull(dr.priority),
      inventory: numOrNull(dr.inventory),
      redPackageTakeType: (dr.activityType === 1 && dr.redMode === "fixed") ? dr.takeType : null,
      redPackageAmount: (dr.activityType === 1 && dr.redMode === "fixed") ? numOrNull(dr.amount) : null,
      redPackageAmountUnit: dr.unit,
      redPackageRangeAmount: (dr.activityType === 1 && dr.redMode === "ladder") ? JSON.stringify(cleanLadder(dr.ladder)) : null,
      discountStrategy: dr.strategy,
      eligibilityConditionTree: pruneTree(dr.tree),
      spuBindings: dr.bindMode === "manual" ? dr.spu.filter(function (s) { return s.spuId !== "" && s.spuId != null; }).map(function (s) { return { storeId: numOrNull(s.storeId), spuId: numOrNull(s.spuId) }; }) : null,
      poolRefs: dr.bindMode === "pool" ? dr.pool.filter(function (p) { return p.poolId !== "" && p.poolId != null; }).map(function (p) { return Number(p.poolId); }) : null,
      gifts: dr.activityType === 5 ? dr.gifts : null,
    };

    var btn = $("am-submit"); if (btn) btn.disabled = true;
    setStatus("am-status", "pending", "提交中…");
    clear($("am-result")); clear($("am-error"));

    api("POST", "/create", body).then(function (r) {
      if (btn) btn.disabled = false;
      if (r.ok) {
        var res = r.json;
        setStatus("am-status", "ok", "保存成功");
        var c = card("活动已保存", el("div", {}, [
          kv("活动ID", res.activityId, "mono"),
          kv("版本", "v" + res.version),
          kv("状态", dictLabel("statuses", res.status)),
          kv("自动圈选绑定", res.autoBoundCount + " 个"),
          res.idempotentHit ? el("div", { class: "tags" }, [el("span", { class: "tag tag-gold", text: "幂等命中：重复提交返回首次结果" })]) : null,
        ]));
        var resultBox = clear($("am-result"));
        resultBox.appendChild(c);
        resultBox.appendChild(el("div", { class: "actions" }, [el("button", { type: "button", class: "row-add", onclick: function () { go("list"); } }, ["← 返回列表"])]));
      } else {
        setStatus("am-status", "error", "HTTP " + r.status);
        var hint = r.status === 409 ? "版本冲突（并发编辑），请返回列表刷新后重试。" : "参数非法。";
        clear($("am-error")).appendChild(el("div", { class: "err-card" }, [
          el("div", { class: "err-title", text: "HTTP " + r.status + " · " + hint }),
          el("div", { class: "err-body", text: errText(r) }),
        ]));
      }
    });
  }
  function cleanLadder(rows) {
    return rows.filter(function (r) { return r.reward !== "" && r.reward != null; })
      .map(function (r) { return { min: r.min === "" ? 0 : Number(r.min), max: r.max === "" ? null : Number(r.max), reward: Number(r.reward) }; });
  }
  function setStatus(id, kind, msg) { var line = clear($(id)); line.appendChild(el("span", { class: "status-pill status-" + kind, text: msg })); }

  /* ───────────── 详情 ───────────── */
  function renderDetail(panel) {
    var wrap = el("div", { class: "col" });
    wrap.appendChild(el("div", { class: "actions" }, [el("button", { type: "button", class: "row-add", onclick: function () { go("list"); } }, ["← 返回列表"]), el("button", { type: "button", class: "row-add", onclick: function () { loadForEdit(state.detailId); } }, ["编辑"])]));
    var box = el("div", { id: "am-detail" });
    wrap.appendChild(box); panel.appendChild(wrap);
    api("GET", "/" + encodeURIComponent(state.detailId)).then(function (r) {
      clear(box);
      if (!r.ok) { box.appendChild(el("div", { class: "err-card" }, [el("div", { class: "err-body", text: errText(r) })])); return; }
      var d = r.json, m = d.manage, rule = (d.rules || [])[0], cond = (d.conditions || [])[0];
      var grid = el("div", { class: "demo-grid" });
      var left = el("div", { class: "col" }), right = el("div", { class: "col" });
      left.appendChild(card("基础信息 · " + m.activityId + " (v" + m.version + ")", el("div", {}, [
        kv("名称", m.activityName), kv("类型", dictLabel("activityTypes", m.activityType)),
        kv("业务线", m.bizLine || "-"), kv("状态", dictLabel("statuses", m.activityStatus)),
        kv("时间", isoToLocal(m.activityStartTime) + " ~ " + isoToLocal(m.activityEndTime)),
        kv("优先级", m.priority), kv("库存", m.inventory),
      ])));
      if (rule) left.appendChild(card("红包规则", el("div", {}, [
        rule.redPackageRangeAmount ? kv("阶梯", rule.redPackageRangeAmount, "mono") : kv("固定金额", fmtMoney(rule.redPackageAmount) + " " + (rule.redPackageAmountUnit || "元")),
      ])));
      if ((d.gifts || []).length) left.appendChild(card("买赠赠品", el("div", { class: "batch" }, d.gifts.map(function (g) {
        return el("div", { class: "batch-item" }, [el("span", { class: "batch-id", text: g.giftName }), el("span", { text: "×" + g.giftNum + " · " + fmtMoney(g.absoluteAmount) })]);
      }))));
      right.appendChild(card("资格条件", el("div", {}, [
        cond && cond.conditionTreeJson ? el("div", { class: "mono-box", text: cond.conditionTreeJson }) : el("div", { class: "muted", text: "无 (恒通过)" }),
        cond && cond.generatedDrl ? el("div", {}, [el("div", { class: "col-label", text: "翻译后的 Drools 约束" }), el("div", { class: "mono-box", text: cond.generatedDrl })]) : null,
      ])));
      var binds = d.bindings || [];
      right.appendChild(card("商品绑定 (" + binds.length + ")", el("div", { class: "tags" }, binds.length ? binds.map(function (b) {
        return el("span", { class: "tag " + (b.effective === 1 ? "tag-green" : "tag-red"), text: "spu" + b.spuId + (b.bindSource === 1 ? "(池)" : "") });
      }) : [el("span", { class: "muted", text: "无" })])));
      grid.appendChild(left); grid.appendChild(right); box.appendChild(grid);
    });
  }

  /* ───────────── 验证 ───────────── */
  function renderValidate(panel) {
    var grid = el("div", { class: "demo-grid" });
    var reqCol = el("div", { class: "col" }), resCol = el("div", { class: "col" });
    reqCol.appendChild(el("div", { class: "col-label", text: "商品与用户上下文" }));
    var g = el("div", { class: "form-grid" });
    g.appendChild(labeled("SPU 列表 (逗号)", inputEl("v-spu", { placeholder: "如 9001,9002" })));
    g.appendChild(labeled("用户ID", inputEl("v-user", { type: "number", value: 1001 })));
    g.appendChild(labeled("用户地域", inputEl("v-district", { placeholder: "如 110000" })));
    g.appendChild(labeled("用户标签 (逗号)", inputEl("v-tags", { placeholder: "如 vip,new" })));
    g.appendChild(labeled("订单金额", inputEl("v-amount", { type: "number", value: 200 })));
    g.appendChild(labeled("数量", inputEl("v-qty", { type: "number", value: 1 })));
    reqCol.appendChild(g);
    reqCol.appendChild(el("div", { class: "actions" }, [primaryBtn("查红包优惠", function () { runValidate("/spu-discount"); }, "v-run1"), el("button", { type: "button", class: "run-btn", style: "margin-left:8px", onclick: function () { runValidate("/gifts"); } }, ["查买赠赠品"])]));
    resCol.appendChild(el("div", { class: "col-label", text: "决策结果" }));
    resCol.appendChild(el("div", { class: "status-line", id: "v-status" }, [el("span", { class: "status-idle", text: "尚未查询" })]));
    resCol.appendChild(el("div", { id: "v-result" }));
    grid.appendChild(reqCol); grid.appendChild(resCol); panel.appendChild(grid);
  }
  function splitNums(s) { return (s || "").split(",").map(function (x) { return x.trim(); }).filter(function (x) { return x !== ""; }).map(Number); }
  function splitStrs(s) { return (s || "").split(",").map(function (x) { return x.trim(); }).filter(function (x) { return x !== ""; }); }
  function runValidate(path) {
    var body = {
      spuIdList: splitNums($("v-spu").value), userId: numOrNull($("v-user").value),
      userDistrictId: $("v-district").value || null, userTags: splitStrs($("v-tags").value),
      orderAmount: numOrNull($("v-amount").value), quantity: numOrNull($("v-qty").value),
    };
    setStatus("v-status", "pending", "查询中…");
    clear($("v-result"));
    api("POST", path, body).then(function (r) {
      setStatus("v-status", r.ok ? "ok" : "error", r.status + "");
      var box = clear($("v-result")), j = r.json || {};
      if (path === "/gifts") {
        box.appendChild(card("买赠结果 · " + tagMode(j.mode), el("div", { class: "batch" }, (j.gifts || []).length
          ? j.gifts.map(function (g) { return el("div", { class: "batch-item" }, [el("span", { class: "batch-id", text: g.giftName }), el("span", { text: "×" + g.giftNum + " · " + fmtMoney(g.absoluteAmount) })]); })
          : [el("span", { class: "muted", text: "无生效买赠活动" })])));
      } else {
        box.appendChild(card("命中结果 · " + tagMode(j.mode), el("div", {}, [
          el("div", {}, ["命中 ", boolPill(!!j.hit)]),
          j.hit ? kv("优惠金额", fmtMoney(j.hitAmount) + " 元") : null,
          j.hit ? kv("命中活动", j.hitActivityName + " (" + j.hitActivityId + ")", "mono") : null,
          j.hit ? el("div", { class: "tags" }, [el("span", { class: "tag tag-blue", text: "策略 " + j.strategy }), tagMode(j.mode)]) : null,
        ])));
      }
      box.appendChild(card("决策轨迹 traces", el("div", { class: "timeline" }, (j.traces || []).length
        ? j.traces.map(function (t, i) { return el("div", { class: "tl-row" }, [el("span", { class: "tl-seq", text: "#" + (i + 1) }), el("span", { class: "tl-detail", text: t })]); })
        : [el("span", { class: "muted", text: "无" })])));
    });
  }
  function tagMode(mode) { return el("span", { class: "tag " + (mode === "rule-engine" ? "tag-green" : "tag-gold"), text: mode || "-" }); }

  /* ───────────── mount ───────────── */
  function mount(panel) {
    UI = window.DemoUI;
    el = UI.el; clear = UI.clear; $ = UI.$; card = UI.card; kv = UI.kv; tagList = UI.tagList; boolPill = UI.boolPill; fmtMoney = UI.fmtMoney;
    state.panel = panel;
    state.route = "list";
    ensureAuthConfig().then(function () {
      if (authOn() && !AUTH.token) { renderLogin(panel); return; } // auth 档未登录 → 登录页
      if (!state.dict) {
        clear(panel).appendChild(banner("加载字段字典…", "info"));
        api("GET", "/field-dict").then(function (r) { state.dict = r.json; render(); });
      } else render();
    });
  }

  window.ActivityApp = { mount: mount };

  /* ───────────── OIDC 回调着陆 ─────────────
   * redirect_uri 指向 index.html: Casdoor 授权后带 ?code=&state= 回到首页。仅当本页确实发起过
   * authorize (sessionStorage 有 verifier) 才接管, 换完 token 直接挂载活动子应用 (免再点导航)。 */
  if (window.location.search.indexOf("code=") >= 0 && window.sessionStorage.getItem(SS_VERIFIER)) {
    document.addEventListener("DOMContentLoaded", function () {
      handleCallback().then(function (done) {
        if (done && window.ActivityApp && document.getElementById("panel")) {
          window.ActivityApp.mount(document.getElementById("panel"));
        }
      }).catch(function (e) {
        alert("OIDC 回调处理失败: " + e.message);
      });
    });
  }
})();
