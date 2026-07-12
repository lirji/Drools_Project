/*
 * Drools 演示台 — 前端逻辑
 *
 * 职责：读 window.DROOLS_CATALOG 渲染导航与面板；发请求（同源相对路径）；
 * 按 demo.summary 渲染"看得见规则效果"的摘要；统一处理 JSON / 文本 / 非 2xx 错误。
 * 不假设所有响应都是 JSON（/actuator/prometheus 是文本）。
 */
(function () {
  "use strict";

  var CATALOG = window.DROOLS_CATALOG || { groups: [], demos: [] };
  var state = { demoId: null };

  /* ───────────────────────── DOM 小工具 ───────────────────────── */
  function el(tag, attrs, children) {
    var node = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (k) {
        if (k === "class") node.className = attrs[k];
        else if (k === "text") node.textContent = attrs[k];
        else if (k === "html") node.innerHTML = attrs[k];
        else if (k.slice(0, 2) === "on" && typeof attrs[k] === "function")
          node.addEventListener(k.slice(2), attrs[k]);
        else if (attrs[k] != null) node.setAttribute(k, attrs[k]);
      });
    }
    (children || []).forEach(function (c) {
      if (c == null) return;
      node.appendChild(typeof c === "string" ? document.createTextNode(c) : c);
    });
    return node;
  }
  function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); return node; }
  function $(id) { return document.getElementById(id); }

  function fmtMoney(v) {
    if (typeof v === "number") return v.toFixed(2);
    var n = Number(v);
    return isNaN(n) ? String(v) : n.toFixed(2);
  }
  function demoById(id) {
    for (var i = 0; i < CATALOG.demos.length; i++) if (CATALOG.demos[i].id === id) return CATALOG.demos[i];
    return null;
  }

  /* ───────────────────────── 侧栏导航 ───────────────────────── */
  function renderNav() {
    var nav = clear($("nav"));
    CATALOG.groups.forEach(function (g) {
      var demos = CATALOG.demos.filter(function (d) { return d.group === g.id; });
      if (!demos.length) return;
      nav.appendChild(el("div", { class: "nav-group-title" }, [
        el("span", { text: g.title }),
        el("span", { class: "nav-group-sub", text: g.subtitle }),
      ]));
      demos.forEach(function (d) {
        var item = el("button", {
          class: "nav-item" + (d.id === state.demoId ? " active" : ""),
          "data-id": d.id,
          onclick: function () { selectDemo(d.id); },
        }, [
          el("span", { class: "method-dot method-" + d.method }),
          el("span", { class: "nav-item-title", text: d.title }),
          el("span", { class: "nav-item-step", text: "S" + d.step }),
        ]);
        nav.appendChild(item);
      });
    });
  }

  /* ───────────────────────── 主面板 ───────────────────────── */
  function selectDemo(id) {
    state.demoId = id;
    renderNav();
    var demo = demoById(id);
    if (!demo) return;
    var panel = clear($("panel"));

    // 头部
    panel.appendChild(el("div", { class: "demo-head" }, [
      el("div", { class: "demo-meta" }, [
        el("span", { class: "badge badge-" + demo.method, text: demo.method }),
        el("code", { class: "demo-path", text: demo.path }),
        el("span", { class: "step-tag", text: "Step " + demo.step }),
      ]),
      el("h2", { text: demo.title }),
      el("p", { class: "demo-desc", text: demo.desc }),
    ]));

    // 主体两栏
    var body = el("div", { class: "demo-grid" });

    /* 左：请求 */
    var reqCol = el("div", { class: "col req-col" });
    reqCol.appendChild(el("div", { class: "col-label", text: "示例" }));
    var exWrap = el("div", { class: "examples" });
    demo.examples.forEach(function (ex, i) {
      exWrap.appendChild(el("button", {
        class: "chip", "data-idx": i,
        onclick: function () { loadExample(demo, i); },
      }, [ex.label]));
    });
    reqCol.appendChild(exWrap);

    // 路径参数
    if (demo.pathParams && demo.pathParams.length) {
      var pp = el("div", { class: "path-params" });
      demo.pathParams.forEach(function (p) {
        pp.appendChild(el("label", { class: "pp-row" }, [
          el("span", { class: "pp-name", text: p.label + " ({" + p.name + "})" }),
          el("input", { type: "text", class: "pp-input", id: "pp-" + p.name, placeholder: p.placeholder || p.name }),
        ]));
      });
      reqCol.appendChild(pp);
    }

    // 请求体
    if (demo.method !== "GET") {
      reqCol.appendChild(el("div", { class: "col-label", text: "请求体 (JSON) — 可编辑" }));
      reqCol.appendChild(el("textarea", { id: "req-body", class: "code-input", spellcheck: "false", rows: "14" }));
    } else {
      reqCol.appendChild(el("div", { class: "hint", text: "GET 请求，无请求体。" }));
    }

    var actions = el("div", { class: "actions" }, [
      el("button", { class: "run-btn", id: "run-btn", onclick: function () { runDemo(demo); } }, [
        el("span", { class: "run-ico", text: "▶" }), "运行",
      ]),
      el("span", { class: "run-url", id: "run-url" }),
    ]);
    reqCol.appendChild(actions);
    body.appendChild(reqCol);

    /* 右：响应 */
    var resCol = el("div", { class: "col res-col" });
    resCol.appendChild(el("div", { class: "col-label", text: "响应" }));
    resCol.appendChild(el("div", { class: "status-line", id: "status-line" }, [
      el("span", { class: "status-idle", text: "尚未发送请求" }),
    ]));
    resCol.appendChild(el("div", { id: "summary" }));
    resCol.appendChild(el("div", { id: "error-box" }));
    resCol.appendChild(el("div", { class: "raw-wrap" }, [
      el("div", { class: "raw-head" }, [
        el("span", { text: "原始响应" }),
        el("button", { class: "copy-btn", id: "copy-btn", title: "复制", onclick: copyRaw }, ["复制"]),
      ]),
      el("pre", { class: "raw", id: "raw-response" }, [el("code", { text: "" })]),
    ]));
    body.appendChild(resCol);

    panel.appendChild(body);

    // 默认加载第一个示例
    loadExample(demo, 0);
  }

  function loadExample(demo, idx) {
    var ex = demo.examples[idx];
    if (!ex) return;
    // 高亮选中 chip
    var chips = $("panel").querySelectorAll(".chip");
    chips.forEach(function (c) { c.classList.toggle("chip-active", String(c.getAttribute("data-idx")) === String(idx)); });
    // 路径参数
    if (demo.pathParams) {
      demo.pathParams.forEach(function (p) {
        var input = $("pp-" + p.name);
        if (!input) return;
        input.value = (ex.pathParams && ex.pathParams[p.name] != null) ? ex.pathParams[p.name] : (p.placeholder || "");
      });
    }
    // 请求体
    var ta = $("req-body");
    if (ta) ta.value = ex.body == null ? "" : JSON.stringify(ex.body, null, 2);
  }

  /* ───────────────────────── URL / 请求体 ───────────────────────── */
  function buildUrl(demo) {
    var path = demo.path;
    if (demo.pathParams) {
      for (var i = 0; i < demo.pathParams.length; i++) {
        var p = demo.pathParams[i];
        var input = $("pp-" + p.name);
        var val = input ? input.value.trim() : "";
        if (!val) throw new Error("路径参数 {" + p.name + "} 未填写");
        path = path.replace("{" + p.name + "}", encodeURIComponent(val));
      }
    }
    return path;
  }

  function parseBody(demo) {
    if (demo.method === "GET") return undefined;
    var ta = $("req-body");
    var raw = ta ? ta.value.trim() : "";
    if (!raw) return undefined; // 无请求体的 POST（如 poll/stop、campaign/end）
    try {
      return JSON.parse(raw);
    } catch (e) {
      throw new Error("请求体不是合法 JSON：" + e.message);
    }
  }

  /* ───────────────────────── 发送请求 ───────────────────────── */
  function runDemo(demo) {
    var url, body;
    try {
      url = buildUrl(demo);
      body = parseBody(demo);
    } catch (e) {
      showClientError(e.message);
      return;
    }

    $("run-url").textContent = demo.method + " " + url;
    setStatus("pending", "请求中…");
    clear($("summary"));
    clear($("error-box"));
    setRaw("");

    var opts = { method: demo.method, headers: {} };
    if (body !== undefined) {
      opts.headers["Content-Type"] = "application/json";
      opts.body = JSON.stringify(body);
    }
    var btn = $("run-btn");
    if (btn) btn.disabled = true;
    var t0 = performance.now();

    fetch(url, opts).then(function (res) {
      return res.text().then(function (text) {
        var elapsed = Math.round(performance.now() - t0);
        handleResponse(demo, res, text, elapsed);
      });
    }).catch(function (err) {
      setStatus("error", "网络错误");
      showClientError("请求失败（后端未启动或网络问题）：" + err.message);
    }).then(function () {
      if (btn) btn.disabled = false;
    });
  }

  function handleResponse(demo, res, text, elapsed) {
    setStatus(res.ok ? "ok" : "error", res.status + " " + res.statusText + " · " + elapsed + "ms");

    var isText = demo.responseType === "text";
    var parsed = null, jsonOk = false;
    if (!isText) {
      try { parsed = JSON.parse(text); jsonOk = true; } catch (e) { /* 保底当文本 */ }
    }

    // 原始响应
    setRaw(jsonOk ? JSON.stringify(parsed, null, 2) : text);

    if (!res.ok) {
      renderError(res.status, jsonOk ? parsed : text);
      return;
    }
    if (isText || !jsonOk) return; // 文本响应只展示原文
    renderSummary(demo, parsed);
  }

  /* ───────────────────────── 状态 / 原始 / 错误 ───────────────────────── */
  function setStatus(kind, msg) {
    var line = clear($("status-line"));
    line.appendChild(el("span", { class: "status-pill status-" + kind, text: msg }));
  }
  function setRaw(text) {
    var pre = $("raw-response");
    clear(pre).appendChild(el("code", { text: text }));
  }
  function copyRaw() {
    var code = $("raw-response").querySelector("code");
    if (!code) return;
    var txt = code.textContent;
    if (navigator.clipboard) navigator.clipboard.writeText(txt);
    var btn = $("copy-btn");
    if (btn) { btn.textContent = "已复制"; setTimeout(function () { btn.textContent = "复制"; }, 1200); }
  }
  function showClientError(msg) {
    setStatus("error", "未发送");
    clear($("summary"));
    var box = clear($("error-box"));
    box.appendChild(el("div", { class: "err-card" }, [
      el("div", { class: "err-title", text: "⚠ 无法发送请求" }),
      el("div", { class: "err-body", text: msg }),
    ]));
    setRaw("");
  }
  function renderError(status, payload) {
    var box = clear($("error-box"));
    var detail = typeof payload === "string"
      ? payload
      : (payload && (payload.error || payload.message)) || JSON.stringify(payload);
    var hintMap = { 400: "请求或规则编译错误（DRL 语法错时含行号）", 404: "资源不存在（如未知 sessionId）", 409: "状态冲突（如活动已结束）" };
    box.appendChild(el("div", { class: "err-card" }, [
      el("div", { class: "err-title", text: "HTTP " + status + (hintMap[status] ? " · " + hintMap[status] : "") }),
      el("pre", { class: "err-body", text: detail }),
    ]));
  }

  /* ───────────────────────── 摘要卡片工具 ───────────────────────── */
  function card(title, node) {
    return el("div", { class: "sum-card" }, [
      title ? el("div", { class: "sum-title", text: title }) : null,
      node,
    ]);
  }
  function kv(label, value, cls) {
    return el("div", { class: "kv" }, [
      el("span", { class: "kv-k", text: label }),
      el("span", { class: "kv-v " + (cls || ""), text: value }),
    ]);
  }
  function priceRow(total, finalV) {
    var saved = (Number(total) - Number(finalV));
    return el("div", { class: "price-row" }, [
      el("div", { class: "price-block" }, [ el("div", { class: "price-lbl", text: "原价" }), el("div", { class: "price-orig", text: fmtMoney(total) }) ]),
      el("div", { class: "price-arrow", text: "→" }),
      el("div", { class: "price-block" }, [ el("div", { class: "price-lbl", text: "最终" }), el("div", { class: "price-final", text: fmtMoney(finalV) }) ]),
      saved > 0.0001 ? el("div", { class: "price-saved", text: "省 " + fmtMoney(saved) }) : null,
    ]);
  }
  function tagList(title, arr, cls) {
    if (!arr || !arr.length) return el("div", { class: "muted", text: title + "：（无）" });
    return el("div", { class: "taglist" }, [
      el("div", { class: "taglist-title", text: title },),
      el("div", { class: "tags" }, arr.map(function (t) { return el("span", { class: "tag " + (cls || ""), text: String(t) }); })),
    ]);
  }
  function boolPill(v) { return el("span", { class: "pill " + (v ? "pill-yes" : "pill-no"), text: v ? "是" : "否" }); }

  /* ───────────────────────── 摘要分发 ───────────────────────── */
  var SUMMARY = {
    generic: function () { return null; },

    order: function (o) {
      return card("折扣结果", el("div", {}, [
        o.orderId ? kv("订单号", o.orderId) : null,
        priceRow(o.totalAmount, o.finalAmount),
        tagList("命中规则 (discountReasons)", o.discountReasons, "tag-blue"),
      ]));
    },

    orderBatch: function (arr) {
      if (!Array.isArray(arr)) return null;
      return card("批处理结果（" + arr.length + " 单）", el("div", { class: "batch" }, arr.map(function (o) {
        return el("div", { class: "batch-item" }, [
          el("div", { class: "batch-id", text: o.orderId || "-" }),
          priceRow(o.totalAmount, o.finalAmount),
          tagList("命中", o.discountReasons, "tag-blue"),
        ]);
      })));
    },

    cart: function (c) {
      return card("购物车结果", el("div", {}, [
        c.cartId ? kv("购物车", c.cartId) : null,
        priceRow(c.totalAmount, c.finalAmount),
        el("div", { class: "kv" }, [ el("span", { class: "kv-k", text: "金卡 goldStatus" }), boolPill(!!c.goldStatus) ]),
        tagList("命中规则 (discountReasons)", c.discountReasons, "tag-blue"),
        tagList("推荐 (recommendations)", c.recommendations, "tag-green"),
      ]));
    },

    audit: function (r) {
      var cart = r.cart || {};
      var trail = r.auditTrail || [];
      var timeline = el("div", { class: "timeline" }, trail.map(function (ev) {
        return el("div", { class: "tl-row" }, [
          el("span", { class: "tl-seq", text: "#" + ev.sequence }),
          el("span", { class: "tl-type type-" + (ev.type || "").split("_")[0].toLowerCase(), text: ev.type }),
          el("span", { class: "tl-detail", text: ev.detail }),
        ]);
      }));
      return el("div", {}, [
        card("购物车结果", el("div", {}, [
          priceRow(cart.totalAmount, cart.finalAmount),
          tagList("命中规则", cart.discountReasons, "tag-blue"),
          tagList("推荐", cart.recommendations, "tag-green"),
        ])),
        card("规则触发轨迹 auditTrail（" + trail.length + " 事件）", timeline),
      ]);
    },

    fraud: function (r) {
      var alerts = r.alerts || [];
      if (!alerts.length) return card("风控结果", el("div", { class: "ok-note", text: "✓ 无 burst 告警（滑窗内未达阈值）" }));
      return card("Burst 告警（" + alerts.length + "）", el("div", { class: "alerts" }, alerts.map(function (a) {
        return el("div", { class: "alert-card" }, [
          el("div", { class: "alert-name", text: a.customerName }),
          kv("窗内单数", a.eventCount),
          kv("检出时刻 (ms)", a.detectedAt),
        ]);
      })));
    },

    backward: function (r) {
      var answers = r.answers || [];
      var rows = el("div", { class: "answers" }, answers.map(function (a) {
        return el("div", { class: "ans-row " + (a.contained ? "ans-yes" : "ans-no") }, [
          el("span", { class: "ans-ico", text: a.contained ? "✓" : "✗" }),
          el("span", { class: "ans-text", text: a.thing + " ∈ " + a.container }),
          el("span", { class: "ans-tag", text: a.contained ? "成立" : "不成立" }),
        ]);
      }));
      var anc = (r.ancestorsLookup || []).map(function (x) {
        return el("div", { class: "anc-row" }, [
          el("span", { class: "anc-thing", text: x.thing }),
          el("span", { class: "anc-arrow", text: "⊂" }),
          el("span", { class: "anc-list", text: (x.ancestors || []).join(" ⊂ ") || "（无）" }),
        ]);
      });
      return el("div", {}, [
        card("包含关系证明", rows),
        anc.length ? card("祖先链 ancestorsLookup", el("div", { class: "anc" }, anc)) : null,
      ]);
    },

    hotUpsert: function (r) {
      return card("热加载结果", el("div", {}, [ kv("规则名", r.name), kv("状态", r.status, "mono") ]));
    },

    hotRun: function (r) {
      var cart = r.cart || {};
      return card("运行结果", el("div", {}, [
        priceRow(cart.totalAmount, cart.finalAmount),
        kv("fire 条数 firedCount", r.firedCount),
        tagList("命中规则", cart.discountReasons, "tag-blue"),
      ]));
    },

    scannerDeploy: function (r) {
      return card("部署结果", el("div", {}, [
        kv("ReleaseId", r.releaseId, "mono"),
        kv("代次 generation", r.generation),
        el("div", { class: "kv" }, [ el("span", { class: "kv-k", text: "轮询中" }), boolPill(!!r.polling) ]),
        kv("动作", r.action),
      ]));
    },

    scannerRun: function (r) {
      var cart = r.cart || {};
      return card("运行结果（generation " + r.generation + "）", el("div", {}, [
        priceRow(cart.totalAmount, cart.finalAmount),
        kv("fire 条数", r.firedCount),
        tagList("命中规则", cart.discountReasons, "tag-blue"),
      ]));
    },

    scannerStatus: function (r) {
      return card("Scanner 状态", el("div", {}, [
        kv("ReleaseId", r.releaseId, "mono"),
        el("div", { class: "kv" }, [ el("span", { class: "kv-k", text: "container 就绪" }), boolPill(!!r.containerReady) ]),
        kv("代次 generation", r.generation),
        el("div", { class: "kv" }, [ el("span", { class: "kv-k", text: "轮询中" }), boolPill(!!r.polling) ]),
      ]));
    },

    loyalty: function (s) {
      var tierColors = { NONE: "", BRONZE: "tier-bronze", SILVER: "tier-silver", GOLD: "tier-gold" };
      return card("会员状态", el("div", {}, [
        el("div", { class: "loyalty-hero" }, [
          el("div", { class: "loyalty-points" }, [ el("span", { class: "lp-num", text: s.totalPoints }), el("span", { class: "lp-unit", text: "积分" }) ]),
          el("span", { class: "tier-badge " + (tierColors[s.tier] || ""), text: s.tier }),
        ]),
        kv("本次获得", s.lastEarned),
        tagList("已解锁徽章 unlockedBadges", s.unlockedBadges, "tag-gold"),
      ]));
    },

    tms: function (r) {
      function phaseBox(title, phase, retracted) {
        var p2 = (phase && phase.phase2Alerts) || [];
        return el("div", { class: "tms-col " + (retracted ? "tms-logical" : "tms-regular") }, [
          el("div", { class: "tms-col-title", text: title }),
          el("div", { class: "tms-phase" }, [
            el("span", { class: "tms-plabel", text: "阶段1 (95热)" }),
            el("span", { class: "tms-alerts", text: fmtAlerts((phase && phase.phase1Alerts) || []) }),
          ]),
          el("div", { class: "tms-phase" }, [
            el("span", { class: "tms-plabel", text: "阶段2 (改50冷)" }),
            el("span", { class: "tms-alerts " + (p2.length === 0 ? "tms-empty" : "tms-kept"), text: p2.length === 0 ? "[] 已自动撤销" : fmtAlerts(p2) }),
          ]),
        ]);
      }
      function fmtAlerts(arr) {
        if (!arr.length) return "[]";
        return arr.map(function (a) { return (a.level || a.sensorName || "Alert"); }).join(" , ");
      }
      return card("TMS 对比（hot " + r.hotValue + " → cool " + r.coolValue + "）", el("div", { class: "tms-grid" }, [
        phaseBox("logical (insertLogical)", r.logical, true),
        phaseBox("regular (insert)", r.regular, false),
        el("div", { class: "tms-note", text: "logical 的衍生 Alert 随前提失配被引擎自动 retract；regular 的解耦，依然留在 working memory。" }),
      ]));
    },

    guardRunaway: function (r) {
      return card("护栏结果", el("div", {}, [
        kv("护栏 guard", r.guard, "mono"),
        kv("fire 条数 fireCount", r.fireCount),
        kv("截断时 finalValue", r.finalValue),
        kv("耗时 elapsedMillis", r.elapsedMillis + " ms"),
      ]));
    },

    guardCanary: function (r) {
      var cart = r.cart || {};
      return card("灰度结果", el("div", {}, [
        priceRow(cart.totalAmount, cart.finalAmount),
        tagList("命中规则", cart.discountReasons, "tag-blue"),
        tagList("推荐", cart.recommendations, "tag-green"),
        tagList("放行通道 allowedReleases", r.allowedReleases, "tag-green"),
        tagList("被拦规则 skipped", r.skipped, "tag-red"),
      ]));
    },

    metrics: function (r) {
      var o = r.order || {};
      return el("div", {}, [
        card("折扣结果", el("div", {}, [ priceRow(o.totalAmount, o.finalAmount), kv("fire 条数 rulesFired", r.rulesFired), tagList("命中规则", o.discountReasons, "tag-blue") ])),
        card("提示", el("div", { class: "muted", text: "多打几次后，打开「Prometheus 抓取端点」demo 看 drools_ 指标累积。" })),
      ]);
    },

    dmn: function (r) {
      var d = r.decisions || {};
      return card("DMN 决策结果", el("div", { class: "dmn-grid" }, [
        el("div", { class: "dmn-cell" }, [ el("div", { class: "dmn-lbl", text: "Discount Rate" }), el("div", { class: "dmn-val", text: d["Discount Rate"] }) ]),
        el("div", { class: "dmn-cell" }, [ el("div", { class: "dmn-lbl", text: "Final Price" }), el("div", { class: "dmn-val", text: d["Final Price"] }) ]),
        el("div", { class: "dmn-cell" }, [ el("div", { class: "dmn-lbl", text: "Membership Tier" }), el("div", { class: "dmn-val", text: d["Membership Tier"] }) ]),
      ]));
    },

    campaignCreate: function (r) {
      return card("活动已创建", el("div", {}, [ kv("campaignId", r.campaignId, "mono"), kv("名称", r.name), el("div", { class: "kv" }, [ el("span", { class: "kv-k", text: "状态" }), el("span", { class: "pill pill-yes", text: r.status }) ]) ]));
    },

    campaignCheck: function (r) {
      return card("资格判定", el("div", {}, [
        kv("用户", r.userId),
        el("div", { class: "kv" }, [ el("span", { class: "kv-k", text: "是否够格 eligible" }), boolPill(!!r.eligible) ]),
        kv("命中规则数 firedCount", r.firedCount),
        tagList("够格理由 reasons", r.reasons, "tag-green"),
      ]));
    },

    campaignList: function (arr) {
      if (!Array.isArray(arr) || !arr.length) return card("活动列表", el("div", { class: "muted", text: "（暂无活动）" }));
      return card("活动列表（" + arr.length + "）", el("div", { class: "clist" }, arr.map(function (c) {
        return el("div", { class: "clist-row" }, [
          el("span", { class: "clist-id", text: c.campaignId }),
          el("span", { class: "clist-name", text: c.name }),
          el("span", { class: "pill " + (c.status === "ACTIVE" ? "pill-yes" : "pill-no"), text: c.status }),
          el("span", { class: "clist-cached", text: "缓存 " + (c.cached ? "✓" : "✗") }),
        ]);
      })));
    },
  };

  function renderSummary(demo, body) {
    var fn = SUMMARY[demo.summary] || SUMMARY.generic;
    var node;
    try { node = fn(body); } catch (e) { node = el("div", { class: "muted", text: "（摘要渲染跳过：" + e.message + "，见下方原始响应）" }); }
    var box = clear($("summary"));
    if (node) box.appendChild(node);
  }

  /* ───────────────────────── 主题切换 ───────────────────────── */
  function initTheme() {
    var saved = null;
    try { saved = localStorage.getItem("drools-theme"); } catch (e) {}
    if (saved) document.documentElement.setAttribute("data-theme", saved);
    var btn = $("theme-btn");
    if (btn) btn.addEventListener("click", function () {
      var cur = document.documentElement.getAttribute("data-theme");
      var next = cur === "dark" ? "light" : "dark";
      document.documentElement.setAttribute("data-theme", next);
      try { localStorage.setItem("drools-theme", next); } catch (e) {}
    });
  }

  /* ───────────────────────── 启动 ───────────────────────── */
  function init() {
    renderNav();
    initTheme();
    if (CATALOG.demos.length) selectDemo(CATALOG.demos[0].id);
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
  else init();
})();
