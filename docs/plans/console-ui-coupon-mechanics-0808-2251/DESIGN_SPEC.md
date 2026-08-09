# 控制台设计规范 · 票券工学（Coupon Mechanics）

> 由 `console-ui-visual` workflow 产出：四个视觉方向竞标 → 三视角评审 → 综合定稿。
> 决策与被否方案见 [DECISION_RECORD.md](DECISION_RECORD.md)；后端缺口见 [BACKEND-GAPS.md](BACKEND-GAPS.md)。
> 可运行的六屏高保真原型已交付（单文件 HTML）。
>
> 用户要求原话：**「做炫酷一点的」**。本规范对「炫酷」的兑现方式写在 §1 与 §7。
>
> ⚠️ 本文件 §9 起为 workflow 综合稿的原文，§1–§8 取自优胜方向「票券工学」的原始提案。
> 综合稿正文在 §1–§11 处被 max-output 截断，故 §1–§8 由优胜方向补齐，两者内容一致。

---

## 1. 设计主张

运营每天做的事其实是「印券、定额、划线」——控制台就该给他们一种手里握着量具在裁一张券的确定感：每个数字都有刻度可以对齐，每条边界都有撕线看得见，而不是一堆浮在毛玻璃上的圆角卡片。「活动引擎」配得上这个感觉，因为它的输出物本来就是券面和金额，界面只是把引擎里那张券提前显形。

### 敢冒的那个风险

**把齿孔撕线做成整套系统的结构性分隔语言，而不是卡片角落的装饰。**

具体是：全站所有"一个对象内部的两段语义"都由一条真实的齿孔撕线切开（3px 圆点齿孔带 + 两端咬进纸边的 13px 半圆缺口），并且撕线上下有固定含义——**撕线以上 = 券面（身份 / 时间窗 / 人群条件），撕线以下 = 副券（金额 / 额度 / 操作）**。侧板、活动卡片、发布时间线的版本条目、实验的桶分配面板，全都遵守这条裁切逻辑；连侧栏选中态都不用"左侧强调色条"，改用骑缝齿孔（这一项是被撕下来的那张）。

这是真风险：拟物一旦做过头就是廉价的"优惠券 UI 素材"，做不足又变成随便一条虚线。控制它的三条硬规矩是——(1) 齿孔只用中性色 `--seam`，永远不上强调色，不做高光/内阴影/纸张纹理贴图；(2) 圆角收到 6px（券是裁出来的，不是压出来的），所以撕线读起来是"切口"不是"贴纸"；(3) 一屏最多两条撕线，撕线数量等于该对象的语义分段数。

**为什么它服务内容而不是装饰**：运营在侧板里要做的判断是两类完全不同的事——"这个活动是给谁的、什么时候跑"（券面）和"它花了多少钱、还剩多少"（副券）。原来这两类信息在同一张卡片里靠小标题区分，扫视时要读字。现在切口就是路标：眼睛落在纸上先找到那道缺口，缺口以下直接是钱。第二个用处是"可分离"的暗示——副券那半张对应的正是可以单独导出核销明细、单独调预算的部分，形制和权限边界是重合的。

---

## 2. 调色板（三态主题，可直接粘贴）

```css
/* ============================================================
   票券工学 / Coupon Mechanics — 设计 token
   三态：裸 :root = 完整浅色（券面纸）；prefers-color-scheme 只重定义 token；
   [data-theme="dark"] / [data-theme="light"] 再各定义一遍。
   任何颜色都不只存在于 media / [data-theme] 块内。
   ============================================================ */
:root{
  /* —— 中性：纸。色相偏置 168°（票据安全底纹的浅青绿），饱和 3–8%。
        比冷灰更"纸"，比暖米更不像 AI 默认那套米色衬线风。四层面 + 一层凹陷面。 —— */
  --bg:            #edf1f0;  /* 页底：未印刷的纸垛 */
  --bg-elev:       #ffffff;  /* 券面：卡片 / 表格 / 侧板 */
  --bg-soft:       #f4f7f6;  /* 副面：工具条 / 表头 / 票据明细底 */
  --bg-hover:      #e4eae8;  /* 悬停面 */
  --bg-sunken:     #e8edec;  /* 新增·凹陷面：刻度尺与量筒的轨道底（必须比 bg 还暗，才读成"槽"） */
  --border:        #d8e0de;  /* 常规裁切边 */
  --border-strong: #b6c3c0;  /* 强边：表头下沿、量筒管壁、分组边界 */
  --seam:          #a9b8b5;  /* 新增·齿孔/撕线专用，比 border 深一档——撕线必须看得见 */
  --rule:          #2b3937;  /* 新增·票据实线：会计双线、量具临界线、当前时刻游标（近墨，最高权重的线） */
  --grain:         rgba(16,45,42,.028); /* 新增·安全底纹网纹，只铺在 body */
  --text:          #0e1917;  /* 墨黑带青 */
  --text-soft:     #43514e;  /* 白底 8.31:1 */
  --text-faint:    #5f6d6a;  /* 白底 5.41 / bg 4.75 / bg-soft 4.92 —— 全部 ≥4.5，WCAG 实算 */
  --text-invert:   #ffffff;

  /* —— 强调（签名色）：复写紫红 carbon-copy magenta。
        来源是复写纸第二联的紫红油墨，跟"凭证"同源。
        与 ok/warn/err/blue 全部分离，**永不用于状态编码**，只标"系统自身/主操作/当前选中"。 —— */
  --accent:        #a4256b;  /* 白底 6.87:1；白字压其上同为 6.87:1 */
  --accent-strong: #86164f;  /* 按压 / 液面高光 */
  --accent-soft:   #fbe9f2;  /* 强调软底：选中行、批量条、命中档位 */
  --accent-line:   #edbcd5;  /* 强调描边 / 焦点环 */
  --accent-ink:    rgba(164,37,107,.14); /* 图表面积填充 */

  /* —— 语义色：只用于状态，且永远同时带形状 + 文字 —— */
  --ok:   #1a7040;  --ok-soft:   #dcf0e3;   /* 生效中 / 已核销 · 白底 6.11:1 */
  --warn: #8a5300;  --warn-soft: #f8ecd3;   /* 待审批 */
  --err:  #b3261e;  --err-soft:  #fae3e1;   /* 回退 / 越线 / 失败 */
  --blue: #1a4fbe;  --blue-soft: #dee8fb;   /* 预热中 / 信息 */
  --neutral-soft: #e6ebea;                  /* 已下线 */

  /* —— 数据可视化 5 序列：明度阶梯 + 每序列自带纹样，不靠色相区分 —— */
  --dv-1:#a4256b; --dv-2:#1a4fbe; --dv-3:#0f6f68; --dv-4:#8a5300; --dv-5:#465b58;

  /* —— 阴影：纸叠纸，压得很低。不发光、不彩色投影 —— */
  --shadow-sm: 0 1px 0 rgba(16,45,42,.05), 0 1px 2px rgba(16,45,42,.06);
  --shadow:    0 1px 2px rgba(16,45,42,.07), 0 2px 6px rgba(16,45,42,.06);
  --shadow-md: 0 2px 6px rgba(16,45,42,.09), 0 8px 20px rgba(16,45,42,.08);
  --shadow-lg: 0 4px 12px rgba(16,45,42,.12), 0 22px 46px rgba(16,45,42,.14);

  /* —— 形制：券是"裁"出来的，圆角收到 6px（既有 10px → 6px）；只有胶囊 chip 用 pill —— */
  --radius:6px; --radius-sm:4px; --radius-lg:10px; --radius-pill:999px;
  --notch:13px;               /* 新增·齿孔直径 */

  --focus-ring: 0 0 0 3px var(--accent-line);
}

@media (prefers-color-scheme: dark){
  :root:not([data-theme="light"]){
    /* 碳纸夜：同一条 168° 青绿偏置搬到极低明度，identity 跨主题不丢 */
    --bg:#0b1211; --bg-elev:#131c1a; --bg-soft:#182220; --bg-hover:#1f2b29; --bg-sunken:#0e1615;
    --border:#243230; --border-strong:#354744; --seam:#4a605c; --rule:#c9d6d3;
    --grain:rgba(200,255,246,.030);
    --text:#e4ecea; --text-soft:#a0b0ac; --text-faint:#8b9c98; --text-invert:#0b1211;
    --accent:#f45ca0; --accent-strong:#ff86bb; --accent-soft:#2a1420; --accent-line:#5e2340;
    --accent-ink:rgba(244,92,160,.18);
    --ok:#4fc07f; --ok-soft:#0f2419; --warn:#e0a33a; --warn-soft:#2a1f0c;
    --err:#f4756b; --err-soft:#2c1513; --blue:#6ba1f5; --blue-soft:#12203a;
    --neutral-soft:#1d2725;
    --dv-1:#f45ca0; --dv-2:#6ba1f5; --dv-3:#3fb5a6; --dv-4:#e0a33a; --dv-5:#93a5a1;
    --shadow-sm:0 1px 0 rgba(0,0,0,.4), 0 1px 2px rgba(0,0,0,.4);
    --shadow:0 1px 2px rgba(0,0,0,.5), 0 2px 6px rgba(0,0,0,.42);
    --shadow-md:0 2px 6px rgba(0,0,0,.55), 0 8px 20px rgba(0,0,0,.45);
    --shadow-lg:0 4px 12px rgba(0,0,0,.6), 0 22px 46px rgba(0,0,0,.55);
    --focus-ring:0 0 0 3px rgba(244,92,160,.32);
  }
}
:root[data-theme="dark"]{
  --bg:#0b1211; --bg-elev:#131c1a; --bg-soft:#182220; --bg-hover:#1f2b29; --bg-sunken:#0e1615;
  --border:#243230; --border-strong:#354744; --seam:#4a605c; --rule:#c9d6d3;
  --grain:rgba(200,255,246,.030);
  --text:#e4ecea; --text-soft:#a0b0ac; --text-faint:#8b9c98; --text-invert:#0b1211;
  --accent:#f45ca0; --accent-strong:#ff86bb; --accent-soft:#2a1420; --accent-line:#5e2340;
  --accent-ink:rgba(244,92,160,.18);
  --ok:#4fc07f; --ok-soft:#0f2419; --warn:#e0a33a; --warn-soft:#2a1f0c;
  --err:#f4756b; --err-soft:#2c1513; --blue:#6ba1f5; --blue-soft:#12203a;
  --neutral-soft:#1d2725;
  --dv-1:#f45ca0; --dv-2:#6ba1f5; --dv-3:#3fb5a6; --dv-4:#e0a33a; --dv-5:#93a5a1;
  --shadow-sm:0 1px 0 rgba(0,0,0,.4), 0 1px 2px rgba(0,0,0,.4);
  --shadow:0 1px 2px rgba(0,0,0,.5), 0 2px 6px rgba(0,0,0,.42);
  --shadow-md:0 2px 6px rgba(0,0,0,.55), 0 8px 20px rgba(0,0,0,.45);
  --shadow-lg:0 4px 12px rgba(0,0,0,.6), 0 22px 46px rgba(0,0,0,.55);
  --focus-ring:0 0 0 3px rgba(244,92,160,.32);
}
:root[data-theme="light"]{
  --bg:#edf1f0; --bg-elev:#ffffff; --bg-soft:#f4f7f6; --bg-hover:#e4eae8; --bg-sunken:#e8edec;
  --border:#d8e0de; --border-strong:#b6c3c0; --seam:#a9b8b5; --rule:#2b3937;
  --grain:rgba(16,45,42,.028);
  --text:#0e1917; --text-soft:#43514e; --text-faint:#5f6d6a; --text-invert:#ffffff;
  --accent:#a4256b; --accent-strong:#86164f; --accent-soft:#fbe9f2; --accent-line:#edbcd5;
  --accent-ink:rgba(164,37,107,.14);
  --ok:#1a7040; --ok-soft:#dcf0e3; --warn:#8a5300; --warn-soft:#f8ecd3;
  --err:#b3261e; --err-soft:#fae3e1; --blue:#1a4fbe; --blue-soft:#dee8fb;
  --neutral-soft:#e6ebea;
  --dv-1:#a4256b; --dv-2:#1a4fbe; --dv-3:#0f6f68; --dv-4:#8a5300; --dv-5:#465b58;
  --shadow-sm:0 1px 0 rgba(16,45,42,.05), 0 1px 2px rgba(16,45,42,.06);
  --shadow:0 1px 2px rgba(16,45,42,.07), 0 2px 6px rgba(16,45,42,.06);
  --shadow-md:0 2px 6px rgba(16,45,42,.09), 0 8px 20px rgba(16,45,42,.08);
  --shadow-lg:0 4px 12px rgba(16,45,42,.12), 0 22px 46px rgba(16,45,42,.14);
  --focus-ring:0 0 0 3px var(--accent-line);
}

/* body 必须显式设 background（安全底纹只在这一处） */
body{
  background:var(--bg);
  background-image:
    repeating-linear-gradient( 32deg, var(--grain) 0 1px, transparent 1px 7px),
    repeating-linear-gradient(-32deg, var(--grain) 0 1px, transparent 1px 7px);
  color:var(--text);
  overflow-x:hidden;
}
```

**角色说明速查**
| token | 角色 | 不许拿它做什么 |
| --- | --- | --- |
| `--bg` / `--bg-elev` / `--bg-soft` / `--bg-hover` | 纸的四层 | — |
| `--bg-sunken` | 量具轨道底（唯一比 `--bg` 更暗的浅色面） | 不做卡片底 |
| `--seam` | 齿孔 / 撕线 / 票据点线 leader | 永不上强调色，永不做文字色 |
| `--rule` | 会计双线、量具临界线、当前时刻游标 | 不做边框（太重） |
| `--accent` 复写紫红 | 系统身份 / 主按钮 / 当前选中 / 图表序列 1 | **永不表示状态** |
| `--ok/warn/err/blue` | 状态语义 | 不做装饰色、不做图表主色 |
| `--dv-1..5` | 图表序列 | 不做 UI 面色 |

**中性色的色相偏置**：全部中性色 hue ≈ 165–172°（青绿），饱和度浅色 3–8% / 深色 8–14%。选它是因为财务票据的防伪底纹就是浅青绿，而且它跟复写紫红 accent 是近似互补，accent 落在纸上的跳度最大。深色态保留同一 hue，所以切主题时"还是同一家产品"。

**语义色与强调色分离的验证**：accent（紫红 #a4256b，hue 330°）与 err（赤 #b3261e，hue 4°）色相差 34°，浅色态两者明度也差一档（L 0.103 vs 0.116 接近，因此**从不并置**：accent 只出现在主按钮/选中行/图表，err 只出现在状态位与告警指标卡，两者在同一视觉单元里不同时作为唯一区分手段）。

---

## 3. 排版规范

**font stack 全文（CSP 禁外链，纯系统字体，两栈）**

```css
--font-ui:"SF Pro Text","SF Pro Display",-apple-system,BlinkMacSystemFont,
          "Segoe UI Variable Text","Segoe UI",Roboto,"Helvetica Neue",
          "PingFang SC","HarmonyOS Sans SC","Source Han Sans SC",
          "Noto Sans CJK SC","Noto Sans SC","Microsoft YaHei","Hiragino Sans GB",
          Arial,sans-serif;

--font-num:"SF Mono","SFMono-Regular",ui-monospace,"Cascadia Mono",
           "Segoe UI Mono","Roboto Mono",Menlo,Consolas,"Liberation Mono",monospace;
```

**逐字符回退怎么工作、为什么这么排**
CSS 的 font-family 是**逐字形**匹配的：浏览器对每个字符从左到右找第一个含该字形的家族。所以拉丁族必须排在 CJK 族**前面**——否则 `PingFang SC` 会把数字和拉丁字母一起接管（PingFang 自带全角感的西文，会让 `¥184,320.00` 和活动 ID 变松、变圆，票据感全毁）。反过来 CJK 排后面不会有问题，因为拉丁族里没有汉字，自然掉下来。`Arial` 放在 `sans-serif` 前面是兜底的兜底：某些精简 Linux 镜像里 `sans-serif` 会解析到衬线体。

四平台实际落点：

| 平台 | 拉丁/数字 | 汉字 |
| --- | --- | --- |
| macOS | SF Pro Text | PingFang SC |
| Windows 11 | Segoe UI Variable Text（10 回落 Segoe UI） | Microsoft YaHei |
| Android / HarmonyOS | Roboto | HarmonyOS Sans SC / Noto Sans CJK SC |
| Linux (Ubuntu/CentOS) | Roboto → Arial → sans-serif | Noto Sans CJK SC |

**CJK 字重只有 400/700 可靠**（Windows 的微软雅黑只有 Regular/Bold，Noto Sans CJK 在多数发行版只装 Regular/Bold 两档；PingFang 有 6 档但不能指望）。所以本方向的规矩是：

- **含汉字的元素只用 `font-weight:400` 或 `700`**，中间档一律不用——500/600 在 Windows 上会被合成成难看的伪粗体或直接回落 400，同一界面在 macOS/Windows 上层级会打架。
- 需要"比正文重一点但不到粗体"的层级，**不靠字重，靠 `color`（`--text-soft`/`--text-faint`）和 `letter-spacing`**。
- `font-weight:500` 只允许出现在**纯拉丁/纯数字**元素上（`.num`、按钮英文、指标大数）——这些走 `--font-num` 或拉丁族，500 档真实存在。

**角色 → 数值表**

| 角色 | family | size | weight | line-height | letter-spacing | 备注 |
| --- | --- | --- | --- | --- | --- | --- |
| 页面标题 H1 | ui | 20px | 700 | 1.25 | -0.015em | 含中文，用 700 |
| 侧板标题 H2 | ui | 16px | 700 | 1.30 | -0.01em | |
| 区块 kicker | ui | 10px | 700 | 1.2 | **0.16em** | 纯拉丁（ACTIVITY BENCH），加大字距 |
| 正文 / 表格单元 | ui | 13px | 400 | 1.5 | 0 | 紧凑密度降到 12px |
| 活动名（表格主列） | ui | 13px | 700 | 1.4 | 0 | |
| 次要说明 / 单位 | ui | 12px | 400 | 1.5 | 0 | `--text-faint` |
| 表头 | ui | 10px | 700 | 1 | **0.09em** | 中文表头也用 700，不 uppercase（中文无大小写） |
| ID / 版本号 | num | 10px | 400 | 1.4 | 0 | `ACT-2026-1103 · v7` |
| 指标大数 | num | 26px | **500** | 1.05 | **-0.03em** | 纯数字，500 安全 |
| 票据金额（明细行） | num | 14px | 400 | 1.5 | -0.01em | `tabular-nums`，右对齐 |
| 票据合计 | num | 20px | 500 | 1.3 | -0.02em | 会计双线之上 |
| 货币符号 `¥` | num | 12px | 400 | — | 0 | `--text-faint`，比数字小两档 |
| 量具刻度标 | num | 10px | 400 | 1.2 | 0 | |
| 人话预览（权益编辑器） | ui | 15px | 400 | **1.7** | 0 | 唯一一处 1.7 行高，长句要能读 |
| 人话预览里的参数 | num | 15px | **700** | 1.7 | 0 | 数字加粗以便扫读，仍是纯数字 |
| 按钮 | ui | 13px | 500(拉丁)/700(中文) | 1 | 0 | 中文按钮用 700 |
| 徽章 / 状态 | ui | 11px | 400 | 1.6 | 0 | 层级靠形状不靠字重 |

**全局数字规矩**：所有会被纵向比较的数字（金额、耗时、命中量、百分比）一律 `font-family:var(--font-num); font-variant-numeric:tabular-nums; font-feature-settings:"tnum" 1;`，并**右对齐**。这是票据排版的地基——小数点对齐了，跨行比较才是"看"而不是"读"。

---

## 4. 纵深与动效

## 纵深

**层级只有 4 层，靠"纸叠纸"而不是靠模糊。全站零 `backdrop-filter`。**

| 层 | 用在哪 | 边框 | 阴影 |
| --- | --- | --- | --- |
| L0 页底 | `body` `--bg` + 安全底纹 | — | — |
| L1 券面 | 卡片 / 表格 / 指标卡 | `1px solid --border` | `--shadow-sm` = `0 1px 0 rgba(16,45,42,.05), 0 1px 2px rgba(16,45,42,.06)` |
| L2 悬浮 | 右侧详情板（≥1280 时 push，仍属 L2）、下拉、悬浮卡 | `1px solid --border` | `--shadow-md` = `0 2px 6px rgba(16,45,42,.09), 0 8px 20px rgba(16,45,42,.08)` |
| L3 覆盖 | 抽屉 / 模态 / 移动端侧板 | `1px solid --border` | `--shadow-lg` = `0 4px 12px rgba(16,45,42,.12), 0 22px 46px rgba(16,45,42,.14)` |

阴影全部带 168° 青绿墨色（`rgba(16,45,42,…)`）而不是中性黑，落在纸上像油墨渗色，不像 UI 图层。深色态换成纯黑高不透明度（`rgba(0,0,0,.4–.6)`），因为深色下彩色阴影会脏。

**四种"线"的权重序**（这是本方向最重要的纵深手段，比阴影还重要）：
1. `--border` 1px 实线 — 常规裁切边
2. `--border-strong` 1px 实线 — 表头下沿、量筒管壁
3. `--seam` 3px 圆点齿孔带 — 语义分段（撕线）
4. `--rule` 2px 实线（+ 上方 1px，间距 2px）— 会计双线 / 临界线 / 当前时刻游标，**全站权重最高的线，一屏最多出现 3 处**

**凹陷**：量具的槽用 `--bg-sunken`（比 `--bg` 还暗）+ `border:1px solid --border-strong`，不用 inset shadow（inset shadow 在深色态几乎不可见）。齿孔缺口用 `background:var(--bg)` 的实心圆 + `box-shadow:inset 0 1px 1px rgba(16,45,42,.10)`，只有这一处 inset。

**模糊：全站禁用。** 玻璃拟态在长时间使用的密集表格上会降低文字对比度，且在 Windows 低端机上是持续的合成开销。

## 动效

**三档时长 + 三条缓动，写死在 token：**
```css
--dur-fast:120ms;  /* hover / focus / 颜色变化 / chip 切换 */
--dur-mid:180ms;   /* 展开折叠、批量条压出、数值换值 */
--dur-slow:260ms;  /* 侧板推入推出、抽屉、模态 */
--ease-out:cubic-bezier(.16,1,.3,1);   /* 进入：快起慢收 */
--ease-in:cubic-bezier(.4,0,1,1);      /* 退出：慢起快走 */
--ease-std:cubic-bezier(.2,0,0,1);     /* 属性微变 */
```

**签名动效 1 ·「撕开」**（打开右侧详情板，触发时机 = 点击行 / 键盘 Enter）
- 阶段 A：0–120ms，被点行的 `.seam` 齿孔从 `opacity:.45` → `1`，同时两端缺口 `--notch` 由 `11px` → `13px`（`ease-out`）——视觉上是切口"咬开"。
- 阶段 B：60ms 起（与 A 重叠 60ms），侧板 `opacity 0→1` + `translateX(18px)→0`，260ms，`ease-out`。
- 关闭：反向，`translateX(0→14px)` + `opacity 1→0`，180ms，`ease-in`（退出永远比进入快）。

**签名动效 2 ·「盖章」**（上线/审批成功，触发时机 = 接口返回 200 后）
- 状态徽章 `transform:scale(1.06)→scale(1)`，160ms，`ease-out`；
- 同步一次性 `box-shadow:0 0 0 0 var(--ok-soft) → 0 0 0 8px transparent`，220ms，`ease-out`，`forwards`。
- **不是** ripple、不是粒子、不放大整行。一次，不循环。

**签名动效 3 ·「游标呼吸」**（时间窗甘特条上的当前时刻线）
- 只在**该行 hover / focus-within 时**才播：`opacity .62→1`，1.2s，`ease-in-out`，`alternate infinite`。
- 不 hover 时是静态实线。**理由**：这是运营盯 8 小时的界面，任何常驻循环动画都是长期噪音；只有当注意力已经落到这一行时，才用微弱脉动帮他定位"现在在哪"。

**其他**
- sparkline / 折线首次入场：`stroke-dasharray` 描线 420ms `ease-out` `forwards`，**仅首次挂载**，且用 `IntersectionObserver` 限定在视口内的行；重渲染（筛选、排序）不重播。
- 指标大数换值：**不做逐位数字翻滚**（长期使用会晕）。改为 `opacity 1→0→1` + `translateY(0→-2px→0)`，180ms，`ease-std`。
- 表格行 hover 背景：120ms `ease-std`，只变 `background`，**不做 translate/scale**（行位移会让密集表格产生跳动感）。
- 批量操作条出现：`translateY(-6px)→0` + `opacity`，180ms `ease-out`。
- 页面路由切换：`opacity` + `translateY(6px)`，160ms（沿用既有 `.page-enter-active`）。
- chip / segmented 切换：仅 `background` + `color` 120ms，**不做滑块位移**（滑块在换行的 chip 组里会错位）。

**prefers-reduced-motion 降级（具体到每一项）**
```css
@media (prefers-reduced-motion:reduce){
  *{animation-duration:.001ms !important;
    animation-iteration-count:1 !important;
    transition-duration:.001ms !important;}
  .draw{stroke-dasharray:none !important;stroke-dashoffset:0 !important;} /* 折线直接完整显示 */
}
```
逐项落地：撕开 → 侧板直接出现在终态；盖章 → 徽章直接是终态色，成功反馈改由 toast 文字承担；游标呼吸 → 恒定 `opacity:1`；描线 → 直接画完；数值换值 → 直接替换。**没有任何信息只由动效传达**，所以关掉动效不丢信息。

---

## 5. 布局规范

## 栅格

- 内容最大宽 **1440px**（比既有 1280 放宽，因为工作台是 8 列表格 + 侧板同屏）。
- 12 列栅格，列间距 gutter：桌面 **24px** / 平板 **16px** / 手机 **12px**。
- 页面左右 padding = gutter；顶栏高 **52px**（沿用既有 `--shell-topbar-h`）；侧栏宽 **212px**（既有 248 收窄，把 36px 让给表格——工作台是本产品密度最高的屏）。

## 右侧详情板

```css
--panel-w: clamp(360px, 32vw, 458px);
```
- **≥1280px：push**。`.main{display:grid;grid-template-columns:minmax(0,1fr) var(--panel-w)}`，列表让位不被遮挡，运营能一边看列表一边看券面。侧板 `position:sticky;top:52px;height:calc(100dvh - 52px);overflow-y:auto`。
- **1024–1279px：overlay**。`position:fixed;right:0;top:52px;bottom:0;width:min(94vw,458px)`，`--shadow-lg`，带 scrim。
- **<1024px：全屏 sheet**。`width:100vw;left:0`，从右侧滑入，Esc / 顶部 × 关闭。

## 表格列宽策略

桌面固定用 grid，容器 `overflow-x:auto`，`.tbl{min-width:1052px}`——**宽内容在自己的容器里横滚，body 永不横滚**。

```css
grid-template-columns:
  34px                /* 复选框，固定 */
  minmax(216px,2.2fr) /* 活动名 + ID，唯一弹性主列 */
  96px                /* 玩法（chip 定宽，不换行） */
  178px               /* 生效窗甘特条：定宽才能跨行对齐时间轴 */
  148px               /* 发放额度量筒：定宽才能跨行对齐刻度 */
  112px               /* 今日命中：sparkline + 数值，数值按 8ch 预留 */
  100px               /* 状态 */
  118px;              /* 操作 */
```
原则：**所有量具列必须定宽**——甘特条和量筒的价值来自"跨行同刻度"，一旦弹性伸缩，第 1 行的 50% 和第 5 行的 50% 不在同一横坐标，图形就说谎了。只有文字主列吃 `fr`。数值列宽度按最长内容的 `ch` 数 + 2 预留。

## 密度两档（纯 CSS，radio + `:has`）

```css
:root{--row-h:48px; --row-pad-y:10px; --tbl-fs:13px;}      /* 舒适 */
body:has(#d-compact:checked){--row-h:34px;--row-pad-y:5px;--tbl-fs:12px;} /* 紧凑 */
```
| | 行高 | 上下 padding | 字号 | 一屏可见行（1080p） | 量具高度 |
| --- | --- | --- | --- | --- | --- |
| 舒适 | 48px | 10px | 13px | 11 行 | 甘特 22px / 量筒 22px |
| 紧凑 | 34px | 5px | 12px | 16 行 | 甘特 16px / 量筒 16px |

紧凑档下：ID 副行改为 hover 才显（`title` 属性兜底），量具的刻度标签隐藏只留图形。`(pointer:coarse)` 下紧凑档强制 `--row-h:auto`（触控不允许 34px 命中区）。

## 三档断点各自行为

**≥1024px 桌面**
侧栏常驻 docked（212px）→ 表格保持 8 列 + `overflow-x:auto` → 详情板 ≥1280 push / 1024–1279 overlay → 指标区 5 列（≤1180px 降 3 列）→ 密度切换可用。

**768–1023px 平板**
侧栏转 off-canvas 抽屉（`transform:translateX(-100%)`，不占布局宽度）→ **表格塌成券卡**：每行一张卡，`grid-template-columns:auto minmax(0,1fr)`，卡内用一条 `1px dashed --seam` 把「身份/量具」与「操作」切开（形制在小屏也成立）→ 量具列跨整行、带 `::before{content:attr(data-label)}` 行内标签 → 详情板全屏 sheet → 指标区 3 列 → 密度切换隐藏（卡片模式无意义）。

**<768px 手机**
指标区 2 列（≤400px 转 1 列）→ 页面 padding 12px → 搜索框独占一行 `flex:1 1 100%` → 租户条收进顶栏 popover → 卡片内操作按钮 `flex:1;height:36px` 平分 → 票据金额列 `min-width` 从 96px 降到 80px，`¥` 不换行 → 侧板 `width:100vw`。

**360px 下限校验**：指标卡 1 列，卡内大数 20px 不溢出；券卡里最长内容是「双十一预热 · 满300减50」（14 字 × 13px ≈ 182px）+ 卡片 padding 24px = 206px < 360-24=336px ✓；票据行 `满 1,000.00 元 ……… −¥220.00` = 标签 ~96px + leader 最小 14px + 金额 80px = 190px ✓。

---

## 6. 数据可视化规范

全部内联 SVG / CSS gradient 自绘，零依赖。**统一规则：所有图形必须有 `role="img"` + `aria-label` 说人话，图形只是加速通道，不是唯一通道。**

## 配色底座（与语义色一致 + 色盲可读）

序列色 `--dv-1..5` 就是取自语义系统的同一批墨，但**降一档饱和、拉开明度阶**：

| 序列 | 浅色 | 深色 | 相对亮度 L | 纹样（第二编码） |
| --- | --- | --- | --- | --- |
| dv-1 复写紫红 | #a4256b | #f45ca0 | .103 | 实线 |
| dv-2 油墨蓝 | #1a4fbe | #6ba1f5 | .085 | 长虚线 `7 4` |
| dv-3 墨青 | #0f6f68 | #3fb5a6 | .126 | 点线 `1 3.4` |
| dv-4 琥珀 | #8a5300 | #e0a33a | .112 | 点划线 `6 3 1.5 3` |
| dv-5 石板 | #465b58 | #93a5a1 | .069 | 细实线 1px |

**色盲可读的三条硬规矩**（不靠"选了色盲友好色板"这种空话）：
1. **每个序列自带 `stroke-dasharray` 纹样**，面积/条形自带 `<pattern>`（实心 / 45° 斜线 / 点阵 / 横线）。灰度打印或全色盲下仍可区分——这是主编码，颜色是辅编码。
2. **不用红↔绿作对立轴**。本系统里"好/坏"的对立轴是 **dv-1 紫红 ↔ dv-5 石板**（色相差大 + 明度差 1.5:1），红绿只出现在状态徽章上，而状态徽章永远带形状 + 文字。
3. **直接在线上标注，不依赖图例**。折线终点右侧直接写「P95 18.6ms」，桶分配条内直接写「对照组 50%」。运营不需要在图例和图形之间做颜色配对——这一步正是色觉障碍者最吃力的地方。

## 各图形的自绘方案

**① 折线 + 分位带（决策耗时，监控看板）**
一个 `<svg viewBox="0 0 640 180" preserveAspectRatio="none">`。
- 分位带：P50 与 P95 的两条 path 首尾相接成闭合 polygon，`fill:var(--accent-ink)`（14% 紫红），无描边。
- P50：`--dv-1` 实线 2px；P95：`--dv-2` 长虚线 `7 4` 1.75px；P99：`--dv-4` 点划线 1.5px。
- 网格：`repeating-linear-gradient` 做水平基线（每 45px 一条 `--border`），SVG 内不画网格线（省节点）。
- 坐标换算在 JS 里做（纯数学，无库）：`x = i * (W / (n-1))`、`y = H - (v - min) / (max - min) * H`。
- 阈值线：`--rule` 2px 虚线 + 右端标签「SLO 25ms」。

**② 面积图（今日决策量）**
同一组点，`path` 后追加 `L{W} {H} L0 {H} Z`，`fill:var(--accent-ink)`；上面压同色实线 1.6px。深色态 `--accent-ink` 提到 18% 透明度补偿背景变暗。

**③ sparkline（表格行内）**
`<svg width="42" height="14" viewBox="0 0 96 26" preserveAspectRatio="none">`，单 path，`stroke-width:2`（因为 `preserveAspectRatio="none"` 会横向压扁，2px 描边压完约等于 1.5px 视觉）。无坐标轴、无网格、无点。零数据行画一条 `--dv-5` 虚线基线（**不是空白**——空白读起来像"没渲染出来"，虚线读起来像"确实是 0"）。

**④ 漏斗（决策沙盘：候选 → 资格淘汰 → 阶梯落档 → 合并选中）**
**横向阶梯条，不用梯形漏斗**（梯形的面积会骗人）。每级一根定高 28px 的条，宽度按数量线性映射：
- 存活段：`--dv-1` 实心。
- 淘汰段：`<pattern>` 45° 斜线（`--dv-5` 1.5px 线，间距 5px），**这是关键**——淘汰量必须能被单独点开看「被哪条条件淘汰的、样例用户 ID」。
- 每级右侧直接写「1,284 → 806（淘汰 478 · 37.2%）」，中间用 `--seam` 点线 leader 连过去，跟票据同一种排版。
- 级与级之间画一段 12px 高的收窄连接（两条 `--border` 斜线），让"漏"的动作可见但不夸张。
- 这样运营能自己回答"为什么这个用户没享受到优惠"：点斜线段 → 展开该级的淘汰原因分布（再一层同款横条）。

**⑤ 桶分配条（A/B 实验）**
一根 `height:36px` 的分段条，`display:flex`，每段 `flex:分配比例`：
- 段填充用 CSS `repeating-linear-gradient` 而非纯色：对照组 = 实心 `--dv-5`；实验组 A = 45° 斜线 `--dv-1`；实验组 B = 点阵（`radial-gradient` 平铺）`--dv-2`；未分配 = `--bg-sunken` + 横线。
- 段内直接印「对照 50% · 62.1万人」，段宽不足 64px 时标签移到条下方用引线连（`--seam` 1px）。
- 条上方是一把刻度尺：`repeating-linear-gradient` 每 10% 一根主刻线（`--border-strong` 1px，高 6px）、每 2% 一根次刻线（高 3px）——**跟阶梯档位编辑器共用同一把尺**，两屏之间形成语言一致性。
- 分配总和 ≠ 100% 时，缺口段用 `--err` 斜线 + 条上方一个三角警示标，`aria-live` 播报「桶分配总和 96%，缺 4%」。

**⑥ 回退率（头号指标）**
不画趋势图，画**量具**：一根 `height:6px` 的阈值条，`--bg-sunken` 底，`--err` 填充到当前值/量程，`--rule` 2px 竖线钉在阈值位置。数值 26px `--err` + 一个 CSS 三角（`border-left/right transparent + border-bottom var(--err)`）。三重编码：颜色 + 三角形状 + 文字「超阈值 +0.12pp」。**理由**：回退率会静默改金额，它需要的是"越没越线"的二元判断，不是趋势审美。

---

## 7. 签名动作

**① 齿孔撕线 = 语义分段器（不是装饰边）**
3px 圆点齿孔带（`radial-gradient(circle, var(--seam) 0 1.35px, transparent 1.45px) 0 50%/9px 3px repeat-x`）+ 两端 13px 半圆缺口（`background:var(--bg)`，咬进纸边）。**撕线以上永远是券面（身份/时间/人群），以下永远是副券（金额/额度/操作）**。用于：详情侧板、平板券卡、发布时间线的版本条目、实验面板。侧栏选中态也用它（骑缝齿孔代替左侧强调色条）。
*可用性*：运营找"钱在哪"不用读小标题，找那道切口即可；且切口位置恒定，形成肌肉记忆。

**② 阶梯档位刻度尺编辑器（权益编辑器的核心控件）**
「阶梯满减」不渲染成 N 行 key-value，而是一把水平标尺：主刻度每 100 元一根 6px 线、次刻度每 20 元一根 3px 线（`repeating-linear-gradient` 双层），档位是尺上可拖拽的**卡子**，卡子之间的区段直接印「满300 −50」。档位重叠时重叠区打 `--err` 45° 斜线并弹出「300–600 区间有 2 个档位争抢」；档位之间有断档时断档区打灰色斜线并提示「600–1000 之间无优惠」。
*可用性*：档位的**顺序关系、间距大小、覆盖是否连续**这三件事，原来要在 3 行输入框里心算，现在一眼看见。这是把"schema 驱动的动态表单"从 key-value 里救出来的具体手段。

**③ 票据式金额排版（receipt figures）**
标签左对齐 → `--seam` 圆点 leader 拉过去 → 金额右对齐等宽 `tabular-nums`；`¥` 用 `--text-faint` 小两档；小计/合计上方画**会计双线**（`border-top:2px solid var(--rule)` + `::before` 在 -4px 处一条 1px 同色线）。命中的那一档整行铺 `--accent-soft` 并把标签与金额同时转紫红加粗。
*可用性*：跨行比金额时眼睛沿点线走，不会串行；小数点垂直对齐后"50.00 / 120.00 / 220.00"的量级差是看出来的不是读出来的；双线是财务人员的既有约定，零学习成本地把"这是结果不是参数"讲清楚。

**④ 生效时间窗甘特条 + 当前时刻游标**
表格每行一段 8px 高的窗条，轨道底是周刻度（每 14px 一根 1px `--border-strong`），活动窗是 `--accent` 实心段，未开始是 `--blue` 45° 斜线段，已结束是灰色斜线段；`--rule` 2px 竖线钉在"现在"，顶端带一个 6×3px 小旗。列宽定死 178px，**所有行共享同一时间轴**。
*可用性*：不读两个日期就知道"还剩几天 / 几天后开跑 / 已经跑完"，而且能跨行看出"这三个活动窗口叠在一起了"——这正是运营最怕的活动打架。

**⑤ 发放额度量筒（横置在表格 / 竖置在侧板）**
带管壁（`--border-strong` 1px）的槽 + 10% 一格的刻度环 + 液面 + **80% 临界虚线**（`--rule` 2px dashed）。越线时液面转 `--err` 且标签改成「91.4% 越线」。侧板里改成竖置量筒并配「按当前速率可支撑 1.7 天」。
*可用性*：预算烧穿是运营最贵的事故。液面高度 + 临界线的相对位置是前注意力（pre-attentive）就能处理的图形关系，在一屏 16 行里它会最先跳出来，比任何数字都快。

---

## 8. 与既有代码的兼容性

## 与 `tokens.css` 的兼容性

**纯新增（零风险，直接加到 `:root` + 两个主题块）**
`--bg-sunken` / `--seam` / `--rule` / `--grain` / `--accent-strong` / `--text-invert` / `--neutral-soft` / `--dv-1..5` / `--notch` / `--dur-fast|mid|slow` / `--ease-out|in|std` / `--fs-2xs`(10px) / `--fs-base`(14px)。既有组件不引用它们，加了不动任何现状。

**改既有 token 的值（有视觉外溢，需一次全站回归截图）**
| token | 旧 | 新 | 外溢面 |
| --- | --- | --- | --- |
| `--bg` 系四层 | 冷灰 `#f4f5f7`… | 青绿纸 `#edf1f0`… | 全站底色，纯观感变化，无布局影响 |
| `--accent` | `#4f46e5` indigo | `#a4256b` 复写紫红 | Button.pri / Badge.accent / 链接 / 焦点环 / kicker / `.nav a.on`；**都是 currentColor 或 var() 引用，零改代码** |
| `--accent-soft` | `#eef2ff` | `#fbe9f2` | 同上 |
| `--radius` | 10px | 6px | 全站卡片圆角，纯观感 |
| `--ok/--warn/--err/--blue` | — | 微调（更墨、更低饱和） | Badge / Banner；对比度我按新底色重算过，`--text-faint` 三层底全 ≥4.5 |

**关键点：所有组件都只 `var()` 引用 token，改值不改组件源码。** 唯一要动 CSS 的是 `Badge.vue` 的 `.warn` —— 它现在映射到 `--gold/--gold-soft`，而我的语义表里 `--warn` 与 `--gold` 应统一；最省事的做法是保留 `--gold*` 别名指向 `--warn*`，Badge 一行不改。

**`--fw-medium:500` 的纪律问题**：既有组件（Badge / Button）在含中文的元素上用了 500。按本方向的 CJK 字重规矩要么改 700 要么改 400。这是**唯一需要逐组件扫一遍的改动**（约 6 处），影响是 Windows 上层级更稳，macOS 上几乎看不出差别。可以作为独立小 PR 先落，与视觉方向解耦。

## 与 Vue 组件的兼容性

**零改动可复用**：Card / Section / PageHeader / Kv / Skeleton / EmptyState / ConfirmDialog / ToastHost / Icon / PageTransition / Field / Segmented / AppShell / AppLayout / SidebarNav / TopBar / IdentityBar。全部靠 token 换肤。

**需要新增的组件（纯新增，不改既有）**
- `Seam.vue`（齿孔撕线，~20 行 CSS，无 props 或一个 `variant`）
- `WindowBar.vue`（时间窗甘特条，props: `start/end/now`）
- `Gauge.vue` / `Cylinder.vue`（量筒横/竖两态）
- `Receipt.vue` + `ReceiptRow.vue`（票据式金额排版）
- `Sparkline.vue`（内联 SVG，props: `values/color/dash`）
- `TierRuler.vue`（阶梯刻度尺编辑器 —— 工作量最大的一个，含拖拽 + 重叠/断档校验）
- `DensityToggle.vue`（可以直接复用既有 `Segmented`，只是把值写进一个 Pinia store 或 `<html data-density>`）

**需要改造的既有组件**
- `Badge.vue`：加 `shape` 概念（圆点 / 空心方 / 三角 / 斜线纹），让状态不只靠颜色。**向后兼容**：不传 `shape` 时行为与现在完全一致。
- `condition-tree/*`：视觉上换成 `.cond` 那种「人话句子」渲染，但 **schema 驱动的数据结构与 `logic.ts` 完全不动**——只是把 `<select>` 的 render 换成"句子里的可点词元"。这是纯 presentational 改造，`ConditionLeaf` 的 props/emit 签名不变，单元测试不受影响。
- `ListView.vue`：改动最大的一屏（现 275 行 → 预计 450 行 + 抽出 3 个子组件）。

## 会不会打红 e2e

**tablet smoke（768px）——最需要小心的地方。** 既有 `e2e-tablet-smoke` 在 768 直接点 `tab-new` 和 tenant-bar，依赖的是 `<768 才转抽屉`（docked-768）这条约定。本方向**完整保留这条约定**：`.side` 的抽屉化写在 `@media (max-width:1023px)`，但 768–1023 区间侧栏仍需 docked 才不打红 —— **这一条我在 proof 里写的是 ≤1023 就抽屉，与既有约定冲突，落地时必须改回 `max-width:767px`**。这是本方向唯一一处已知的、必须在实施计划里显式修正的兼容性缺口。表格在 768–1023 塌卡片则不受影响（既有 ListView 就是 ≤1023 塌卡片）。

**data-testid 全保留**：`list-view` / `list-search` / `list-status-filter` / `list-refresh` / `list-error` / `list-empty` / `activity-row-{id}` / `list-pager` / `cond-leaf` / `leaf-field` / `leaf-op` / `leaf-del` 一个都不改名。状态下拉 `list-status-filter` 我在视觉上换成了 chips，**但要保留一个 `<select data-testid="list-status-filter">`**（可以视觉隐藏但保持可访问）或者同步改 e2e —— 建议**保留 select 做状态筛选、chips 只做玩法筛选**，这样零改测试且语义更清晰（状态是单选，玩法是多选）。

**phone smoke（<768）**：卡片化 + 抽屉行为与既有一致，`--touch-min:44px` 的 `(pointer:coarse)` 兜底原样保留，新增的量具/齿孔都是纯展示元素不参与命中区计算。风险低。

**单元测试（Vitest）**：`ConditionLeaf` / `logic.ts` / `DynRowTable` 的测试断言的是数据行为不是 DOM 类名，本方向不碰数据层，预期全绿。唯一可能红的是任何断言了具体颜色值/`--accent` 的快照测试（如果有的话，需要更新基线）。

## 实施顺序建议（按依赖排序，风险从低到高）
1. token 换代（纯 CSS 一个文件）+ 全站截图回归
2. `Seam.vue` / `Sparkline.vue` / `Gauge.vue` 三个原语 + 单测
3. `Badge.vue` 加 `shape`（向后兼容）
4. `ListView` 重做（工作台）—— 这一步就能验证方向是否成立
5. `Receipt` / `Cylinder` + 详情侧板（替换跳页 DetailView）
6. `TierRuler` + 动态权益编辑器（最难、最有价值，建议单独一个 sprint）
7. 沙盘漏斗 / 监控看板 / 发布时间线 / A-B

---

## 12. 无障碍清单

**焦点**
- 全局 `:focus-visible{ outline:2px solid var(--accent); outline-offset:2px }`——**保留 outline，不改 box-shadow**（修 X15：outline 不占布局；box-shadow 同样会被 `overflow:hidden` 裁，改它没有好处）。
- 焦点环在 `--accent-soft` 选中行上仍可见（`--accent` on `--accent-soft` 高对比）；在 accent 填充按钮上，`outline-offset:2px` 让环落在纸面上，天然可见。
- 侧板 / 模态：焦点陷阱 + `Esc` 关闭 + 关闭后焦点回到触发元素。
- `.tbl-scroll` 内的行获得焦点时，浏览器自动 `scrollIntoView`；操作列 `position:sticky;right:0` 保证横滚时按钮不丢。

**键盘可达**
- 表格行 `tabindex=0` + `Enter` 打开侧板 + `Space` 切换选中；`↑/↓` 在行间移动（roving tabindex）。
- 表头排序按钮是真 `<button>`，带 `aria-sort="ascending|descending|none"`。
- TierRuler 卡子：`←/→` 步进、`Shift+←/→` 十倍步进、`Home/End` 跳边界，每次变更 `aria-live="polite"` 播报。
- 甘特条、量筒、桶分配条是 `role="img"` 的**非交互**元素，不进 tab 序；可下钻的漏斗段是 `<button>`。

**状态不只靠颜色**
- 六种状态各有独立几何形 + 中文词（§9.3）。
- 越阈：颜色 + `font-weight:700` + 三角 glyph + 「超阈值 +0.33pp」文字。
- 趋势：语义类 `.up-bad/.up-good/.flat` + `▲/▼` 字符 + 正负号，**方向由指标语义决定而非箭头方向**。
- 图表：线型 / 线宽 / 纹样三轴冗余；图例画真线段样本。
- 桶分配 / 漏斗 / 分布条：段内直接标注文字，不做纯色图例。

**触控**
- `(pointer:coarse)` 下所有 `button / [role=button] / .chip / .seg label / .nav-item / a` `min-height:var(--touch-min)`；`--row-h` 强制 44px。
- 既有 tokens.css 的 coarse 选择器列表需补：`.seg button, .chip, thead th button, .ruler-knob`。

**语义与播报**
- 表格用真 `<table>/<thead>/<tbody>/<th scope="col">`，带 `<caption class="sr">`。
- 每个 SVG：`role="img"` + `<title>` + `aria-label`，label 写**结论**（「P99 在 34 至 58 毫秒，峰值出现在 17 点」）而不是数据罗列。
- 批量操作结果、桶分配偏差、TierRuler 校验、试算完成：`aria-live="polite"`；回退越阈告警：`role="alert"`。
- 密度 / 主题 / 筛选切换按钮带 `aria-pressed`。

**其它**
- 三态主题完整成立（§4），任何颜色都不只定义在 media / `[data-theme]` 块内。
- `prefers-reduced-motion` 降级零信息丢失（§6.6）。
- 强制颜色模式（Windows 高对比）：所有纹样用 `currentColor` 或系统色可覆盖的写法；状态有图标 + 文字；量具有数字。
- 最小对比：正文 ≥4.5:1，图形/边界 ≥3:1，分区线 ≥1.3:1（§6.2）。

---

## 13. 实施顺序

原则：**后端依赖最少的先**；token 换代与结构改造**永不混在同一个 diff 里**（修 X17）。

| PR | 内容 | 后端依赖 | 验证 |
|---|---|---|---|
| **PR-0**（修既有隐患，可独立合） | ① reduced-motion 补 `animation-iteration-count:1`；② `AppLayout.scrim` 硬编码换 `var(--scrim)`；③ `body{font-synthesis-weight:none}`；④ coarse 选择器补全；⑤ 新增 `--z-panel:880` | 无 | 全量 e2e 应保持绿 |
| **PR-1** 颜色换代 | tokens.css：新增全部 token + 改颜色值（**不改 radius / 尺寸 / 字号**）；`--green/--gold/--red` 转发 | 无 | 全量 e2e + 逐页目视；视觉快照重录基线 |
| **PR-2** 形制换代 | `--radius 10→6 / -sm 7→4 / -lg 14→10`、`--shell-sidebar-w 248→212`、`--content-max-wide` 新增、Badge 方角 + `shape`、Segmented 选中下划线、PageHeader kicker | 无 | tablet/phone smoke（用 testid 点击，不受宽度位移影响）+ 快照重录 |
| **PR-3** 图表与量具原语 | `Seam / WindowBar / Gauge / Sparkline / Receipt / DistBar / StateGlyph / DensityToggle`；每个配 Vitest（路径计算与 `mapWindow()` 是纯函数，好测） | 无 | 单测 + Storybook 式 demo 页 |
| **PR-4** ★ **屏 3 权益编辑器** | `TierRuler + PlainLanguagePreview`，改造 `EditorView` 的权益步骤（干掉 `v-if activityType===1 / ===5` 硬编码两套表单） | **已有**：`/activity-marketing/field-dict`、`/preview` | `EditorView.test.ts` + 手工验 |
| **PR-5** ★ **屏 1 工作台** | `SidePanel / BulkBar / BulkConfirm / UndoToast` + `ListView` 重做 + `detail-loaded` 迁到侧板 | **已有**：list/status/{id}；**需新增**：批量接口、跨页匹配计数 | 8 个旧 testid 一个不改；tablet/phone smoke |
| **PR-6** 屏 2 模板选择 | 模板卡片网格；模板先用前端内置常量 | 可选后端模板库 | — |
| **PR-7** 屏 4 决策沙盘 | `FunnelSteps`；先用 `preview` 的现有返回做"最终结果"视图，trace 字段到位后再点亮漏斗 | **需新增**：`preview` 返回决策 trace | 降级态必须先做 |
| **PR-8** 屏 5 监控看板 | `QuantileBand / AreaThreshold`；**先做「等待接入」空态**，接口到位后填数 | **需新增**：全套指标 API | — |
| **PR-9** 屏 6 发布 + 实验 | 时间线 + `BucketBar` + Kill Switch；同样先做空态 | **需新增**：发布/审批/实验 API | — |

**关键排序理由**：屏 3 排在屏 1 之前——它是唯一后端契约完整的屏，且是题面点名的成败关键（修 X6，票券原案把 TierRuler 排到第 6 步）。屏 5/6 排最后，因为它们**没有任何后端接口**（修 X30），先做只会得到一屏假数据。

---

## 14. 依赖后端的清单

> 现状核对：`activity-console` 只有 create / status / list / `{id}` / spu-discount / gifts / preview / field-dict；`activity-decision` 只有两个 POST 决策入口。指标只以 Micrometer 形式落在 actuator/prometheus（Prometheus :9090 / Grafana :3001，SPA 既拿不到也不该直连）。

### 14.1 屏 1 工作台

| 需要 | 现状 | 契约草案 |
|---|---|---|
| 列表补充字段 | list 无 | `budgetUsed / budgetTotal`、`todayHit`、`fallbackRate`、`hitSpark: number[8]`、`grayPercent`、`version` |
| 六态 | 现只有上/下线 | `state: RUNNING\|WARMUP\|GRAY\|PENDING\|ENDED\|OFFLINE`（由后端算，不由前端从时间推） |
| 批量上下线 | 无 | `POST /activities/bulk-state` `{ids[]\|filter, target}` → `{succeeded[], failed[{id,reason}]}` |
| 跨页选择计数 | 无 | list 响应带 `totalMatched` |
| 批量撤销 | 无 | `POST /activities/bulk-state/{opId}/undo`，服务端保留 10s 窗口 |
| 核销明细导出 | 无 | `POST /activities/export`（异步任务 + 轮询） |
| **回退哨兵** | 无 | `GET /decision/v1/fallback-rate` → `{rate, threshold, updatedAt}` —— **单个标量，这是最便宜的一个接口，但价值最高，建议第一个做** |

### 14.2 屏 3 权益编辑器

| 需要 | 现状 | 说明 |
|---|---|---|
| 字段字典 | ✅ `/field-dict` | 需确认是否含 `unit / min / max / step / enumOptions`，TierRuler 的刻度量程依赖它 |
| 玩法参数 schema | ❌ | `GET /playbooks/{code}/schema` → 参数定义 + 校验规则 + 人话模板串（人话预览不应在前端硬编码句式） |
| 校验 | 部分 | 档位重叠/断档目前只能前端判；建议服务端 `POST /activities/validate` 返回同一套 code，前后端共用 |

### 14.3 屏 4 决策沙盘

| 需要 | 现状 | 契约草案 |
|---|---|---|
| 决策 trace | ❌ `preview` 只返回最终结果（ValidateView 因此只能打印原始 JSON） | `POST /preview?trace=true` → `{ stages: [{ name, kept, dropped, reasons:[{field, expected, actual, count}] }], winner, timings }` |
| 载入真实订单 | ❌ | `GET /orders/{id}/decision-context`（脱敏） |

### 14.4 屏 5 监控看板（**整屏无接口**）

| 需要 | 契约草案 |
|---|---|
| 时序指标 | `GET /decision/v1/metrics?window=1h\|24h\|7d` → `{ ts[], qps[], p50[], p95[], p99[], cacheHit[], fallbackRate[] }`（服务端聚合，SPA 不直连 Prometheus） |
| 回退原因构成 | `GET /decision/v1/fallback-reasons?window=` → `[{reason, count, pct}]` |
| 各活动决策明细 | `GET /decision/v1/by-activity?window=` → `[{activityId, hit, share, p95, fallbackRate, version, publishedAt}]` |
| 发布代际标记 | 指标响应内嵌 `markers: [{ts, type:'GENERATION', text}]`，用于图上竖线标注 |
| 网关 | nginx 需把上述路径挂到 `/ui` 同源下（当前 Prometheus/Grafana 未挂） |

### 14.5 屏 6 发布 + 实验（**整屏无接口**）

| 需要 | 契约草案 |
|---|---|
| 版本时间线 | `GET /activities/{id}/releases` → `[{version, publishedAt, publisher, summary, approvals:[{user,at,result}], rolledBackFrom?}]` |
| 四眼审批 | `POST /releases/{id}/approve` / `/reject`，服务端强制"提交人 ≠ 审批人" |
| 回滚 | `POST /releases/{id}/rollback` + 影响摘要 `GET /releases/{id}/rollback-impact` |
| 实验 | `GET /experiments/{id}` → `{buckets:[{name,pct,users,killed}], expected[], actual[]}`；`POST /experiments/{id}/kill` |

### 14.6 未接入时的统一降级约定

**绝不用假数据充数。** 屏 5 / 屏 6 在接口就绪前显示左对齐的说明卡：

> **决策指标尚未接入**
> 决策进程的耗时、命中率与回退率目前只在 Prometheus（`:9090`）中可见，控制台还没有对应的聚合接口。
> 接入后这里会显示近 24 小时的分位带与回退率量具。
> `待建接口：GET /decision/v1/metrics`

导航项对应加一个 `--text-faint` 的「未接入」小标，而不是让运营点进去看一屏假图。

---

# 原型构建说明

## 一、总体组织

**单文件 HTML，六屏靠 `hash` 路由切换**（`#/bench` `#/templates` `#/editor` `#/sandbox` `#/monitor` `#/release`），不是六个 `display:none` 的 div 硬切——用 `location.hash` + 一个 30 行的 `render()` 分派，这样可以直接把链接发给评审并停在某一屏。

**文件结构**
```
<style>   ~1400 行：§4 token 块原样 + 组件层 CSS（全部走 var()）
<body>
  <header class="topbar">    品牌 mark（微缩带齿孔券）/ 租户 / 回退哨兵 pill / 主题三态按钮
  <div class="body">
    <nav class="side">       六屏导航（骑缝齿孔选中态）
    <main id="app">          由 render() 填充
    <aside id="panel">       SidePanel，六屏共用一个实例
<script>  ~500 行原生 JS：路由 / 状态 / 六屏模板串 / 交互
```

**数据一次性写在顶部一个 `DATA` 常量里**，六屏共读——这样"在工作台选中的活动"和"在编辑器里改的档位"能真的联动，评审会立刻看出这不是六张静态图。

**时间基准钉死**：`const NOW = new Date('2026-11-06T15:44:00+08:00')`。甘特轴域 = `2026-09-07 → 2026-12-06`，游标恒在 66.67%。所有"剩 3 天""2 天后开跑"都由这个基准算出来，不是写死的文案。

---

## 二、每屏放什么真实业务内容

### 屏 1 · 活动工作台（`#/bench`）

**指标条 5 个**（KPI 顶轨 meter，`--rail` 是真实百分比）
| 指标 | 值 | 顶轨 | 脚注 |
|---|---|---|---|
| 生效中活动 | `12` `/ 24` | 50% 中性 | 其中 3 个今日到期 |
| 今日决策量 | `128.4` `万次` | 46% accent | sparkline + `▲ 8.3%` `.up-good` |
| 优惠命中率 | `37.4` `%` | 37% 中性 | `▲ 1.2pp` 对比上周 |
| **规则回退率** | `0.83` `%` | **100% `--err`** | 阈值刻度条 + `▲ 0.33pp` `.up-bad` + 三角 |
| 决策耗时 P99 | `54` `ms` | 54% `--ok` | SLO ≤100ms · P50 6.2ms |

**表格 8 行**（覆盖全部六态，甘特条按固定轴域真算）

| # | 活动名 | ID·版本 | 玩法 | 生效窗 | 甘特（left%→right%） | 额度 | 今日命中 | 回退率 | 状态 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 双十一预热 · 满300减50 | ACT-2026-1103 · v7 | 阶梯满减 | 11-01→11-11 | 61.1→72.2 | 63.1% | 184,320 | 0.31% | 生效中 ● |
| 2 | 会员日 · 第二件半价 | ACT-2026-0928 · v3 | 第二件半价 | 11-08→11-09 | 68.9→70.0（**min 6px**） | 未启用 | 0 | — | 预热中 ▶ |
| 3 | 新客首单 · 立减15 | ACT-2026-0815 · v12 | 无门槛立减 | 08-15→长期 | **◄0→100►**（双截断） | **91.4% 越线** | 61,904 | 0.09% | 生效中 ● |
| 4 | 生鲜频道 · 满2件打8折 | ACT-2026-0801 · v5 | 件数折扣 | 11-04→11-30 | 64.4→92.2 | 28.6% | 23,417 | 0.14% | 待审批 □ |
| 5 | **家电专场 · 阶梯满减** | ACT-2026-0728 · v2 | 阶梯满减 | 10-28→11-30 | 56.7→92.2 | 44.2% | 88,304 | **1.24%** | 灰度中 ◐ 12% |
| 6 | 老客召回 · 满99减20 | ACT-2026-0705 · v2 | 定向券 | 07-05→10-31 | **◄0**→60.0 | 46.2% | 0 | — | 已结束 ⊖ |
| 7 | 到店核销 · 满100减12 | ACT-2026-0620 · v9 | 阶梯满减 | 06-20→09-30 | **◄0**→26.7 | 38.0% | 0 | — | 已下线 ▨ |
| 8 | 88VIP · 叠加券 | ACT-2026-0519 · v9 | 折扣券 | 05-19→10-31 | **◄0**→60.0 | 71.5% | 0 | — | 已下线 ▨ |

第 2 行是**甘特修复的现场证据**：2 天窗口按 90 天轴只有 1.1% ≈ 2px，被 `min-width:6px` 救回，且 `aria-label` 带精确日期。第 3/6/7/8 行展示截断箭头。第 5 行是唯一越阈行，回退率 700 + `--err` + 三角。

**筛选**：玩法 chips `全部 24 / 阶梯满减 9 / 第二件半价 5 / 定向券 6 / 超预算预警 2`；状态原生 select；搜索框。

### 屏 2 · 玩法模板（`#/templates`）

八张券卡：阶梯满减 / 第二件半价 / 无门槛立减 / 件数折扣 / 满额赠品 / 定向券包 / 折扣券 / 限时秒杀。
每张：撕线以上 = 玩法名 + 一句人话（「订单金额到达某个门槛就减固定金额，可设多档，取最高档」）；撕线以下 = 迷你票据预览 `满 300 ⋯⋯ −¥50` + 「用它新建」。
筛选行：`全部 8 / 满减类 3 / 折扣类 2 / 赠品类 1 / 券类 2`。

### 屏 3 · 权益编辑器（`#/editor`）

编辑对象固定为**活动 1「双十一预热 · 满300减50」**（与屏 1 联动）。

- **TierRuler**：量程 0–1200 元，主刻 100 / 次刻 20；三个卡子在 300 / 600 / 1000，区段印「满300 −50」「满600 −120」「满1000 −220」
- **故意制造两个可见的校验态**：拖第 2 个卡子到 280 → 250–300 区间出现 `--err` 45° 斜纹 +「280–300 区间有 2 个档位争抢」；拖第 3 个卡子到 1000 保持不动，600–1000 之间正常
- **条件树**（复用既有样板的人话渲染）：`注册天数 大于等于 30 天 且 用户标签 包含 高价值 且 历史订单数 大于 3 单`
- **商品范围**：类目「家电 / 数码」，排除 12 个 SPU（DynRowTable 3 行示例：`SKU-88201 索尼降噪耳机` 等）
- **右 5 栏人话预览**（实时跟随 TierRuler）：
  > 订单满 **300** 元减 **50** 元；满 **600** 元减 **120** 元；满 **1,000** 元减 **220** 元。取最高档，不与其它满减叠加，每人每天 **1** 次。
- **票据式档位表**：三行 + 会计双线合计「今日已核销 ¥184,320.00」，命中档整行 `--accent-soft`
- **竖置量筒**：`¥315,680 剩余 · 池 ¥500,000 · 按当前速率可支撑 1.7 天`

### 屏 4 · 决策沙盘（`#/sandbox`）

输入上下文：`用户 U-2026-88104 · 订单金额 ¥476.00 · 商品 3 件（家电 2 / 日用 1）· 下单 15:41 · 标签 [高价值, 家电偏好]`

漏斗四级：
```
候选 12  →  资格淘汰后 5  →  阶梯落档 3  →  合并选中 1
     淘汰 7：用户标签 ∌ 高价值(3) / 订单金额 156 < 门槛 300(2) /
             商品类目 ∉ 家电·数码(1) / 活动已下线(1)
     落档淘汰 2：未达最低档 600(1) / 商品不在范围(1)
     合并淘汰 2：优先级低于「双十一预热」(2)
```
最终票据：`双十一预热 · 满300减50 → 命中第 1 档 → −¥50.00`，合计 `应付 ¥426.00`。

### 屏 5 · 监控看板（`#/monitor`）

KPI ×4：QPS `1,842 次/秒`（容量 46%）/ P99 `54 ms`（SLO 54%）/ 缓存命中 `96.4 %`（目标 ≥95%）/ **回退率 `0.83 %`（阈值 0.50%，越阈）**
分位带：24 个采样点，P50 9–15ms、P95 23–41ms、P99 42–88ms，**17:00 一个 88ms 毛刺**，竖虚线标注「与「家电专场」规则版本 v2 发布同刻，编译缓存冷启动 41 秒」
回退率面积图：全天 0.3% 上下，18:20 冲到 1.12%，**阈值 0.50% 以上打 45° 斜纹**
回退原因构成：规则编译超时(>800ms) 61% / 条件字段缺失：用户标签未回传 23% / 优惠金额计算异常（负数拦截）11% / 上游商品服务超时 5%
明细表：屏 1 的 8 个活动，按命中量降序

**同时必须放一个"降级示范"**：给分位带面板一个「切到未接入态」的小开关，点一下换成 §14.6 那张说明卡。这是给评审看我们**没打算用假数据糊弄**。

### 屏 6 · 发布 + 实验（`#/release`）

时间线 4 条（每条一张带撕线的券）：
- `v7 · 11-06 15:44 · 陈莉 发布` —「新增第 3 档：满 1000 减 220」— 审批 王涛 ✓ — **当前生效**
- `v6 · 11-02 09:12 · 陈莉 发布` —「人群条件加「历史订单 > 3」」— 审批 王涛 ✓ — [回滚到此版本]
- `v5 · 10-28 20:31 · 系统 自动回滚` —「v6 回退率 2.1% 触发熔断」— `--err` 左标
- `v4 · 10-25 11:07 · 张岚 发布` —「首次上线」— 审批 王涛 ✓

右 5 栏：四眼审批面板（提交 陈莉 / 审批 王涛 / 通过 11-06 15:42）；桶分配条 `对照 50% · 62.1万人 | 变体A 30% · 37.2万人 | 变体B 20% · 24.8万人`，实际 `49.8 / 25.1 / 25.1` 与期望 `50/25/25` 偏差 ≤2pt（合规）；Kill Switch。

---

## 三、必须做成真的可点（不是静态图）

按重要性排序，**前六条是硬要求，做不到就不算兑现**：

1. **主题三态循环**——顶栏按钮：跟随系统 → 浅色 → 深色 → 跟随系统。三态必须都真的对（这是硬约束，也是四份提案里出过 bug 的地方）。切到深色时**主按钮文字必须仍然可读**（`--on-accent` 的现场证明，修 X7）。
2. **密度切换**——舒适 44px ↔ 紧凑 32px，`<html data-density>` 真切、写 localStorage、刷新保持。切换时表格可见行数从 ~9 变 ~13（原型高度下），肉眼可见。
3. **TierRuler 拖拽**——三个卡子真能拖，人话预览与票据表**同步变**，拖出重叠时斜纹与错误文案真的出现。这是整个方案最贵也最值钱的部件，必须真做。
4. **行点击 →「撕开」侧板**——齿孔先咬开（120ms），侧板再滑入（240ms）。≥1280 时侧板推开内容、列表仍可交互；缩到 1100px 变 overlay + scrim；缩到 900px 变全屏 sheet + 锁滚动；**再拉回 1400px 必须自动解锁滚动**（X27 的现场验证）。
5. **批量四段流程**——勾选 3 行 → BulkBar 压出 →「选中全部匹配的 137 项」→「批量下线」→ 影响摘要弹窗（≥10 项要求输入数量）→ 执行 → Toast「20 成功 · 3 失败 · [查看回执] [撤销 10s]」→ 点回执开侧板列失败原因。这是三份评审共同点名的最大缺口，原型里必须完整走通。
6. **排序 + 筛选组合**——点表头三态排序（升/降/取消，`aria-sort` + 三角），玩法 chips 多选、状态 select 单选、搜索防抖，三者**同时生效**且写进 hash query。

再往下是加分项，能做就做：

7. 漏斗淘汰段可点 → 右侧板列出被淘汰活动与失配字段
8. 分位带图上毛刺锚点 hover → 显示「17:00 P99 88ms · 家电专场 v2 发布」
9. 时间窗甘特条 hover → 游标呼吸（唯一的循环动画，且只在 hover 期间）
10. Kill Switch → 确认 → 桶段变斜纹 + 删除线 + 「已熔断」
11. 监控页的「切到未接入态」开关
12. 键盘：`Tab` 走一遍全屏，`Esc` 关侧板，表格 `↑/↓` 移动行、`Enter` 开侧板

---

## 四、哪些视觉效果必须体现出来才算兑现"炫酷"

评审时会被拿来判断"这值不值得做"的，就是下面这七样。**少一样都算没做到。**

| # | 必须看得见的东西 | 判断标准 |
|---|---|---|
| 1 | **齿孔撕线的真实感** | 缺口必须是**咬进纸边的半圆**，不是一条虚线。侧板、平板券卡、模板卡片三处形制一致。截一张图，看得出"这是一张券被切开了" |
| 2 | **甘特条的跨行对齐** | 八行的游标必须**严格在同一条垂直线上**（66.67%）。评审只要拿尺子比一下就知道轴是不是真的。第 2 行的 6px 短条和第 3/6/7/8 行的截断箭头必须都在 |
| 3 | **量筒的临界线关系** | 第 3 行 91.4% 的液面**明显越过** 80% 虚线且变红变文案；其余行明显没越过。这个对比在一屏 8 行里要能被余光抓到 |
| 4 | **票据金额排版** | 点线 leader 拉过去、`¥` 小两档、小数点严格对齐、合计上方**会计双线**（2px + 1px 双道）。命中档整行 `--accent-soft` |
| 5 | **密度切换的密度感** | 紧凑档下每 5 行一条更深的账簿线必须清晰可见；13px→12px 的字号变化不能让任何一列溢出或换行 |
| 6 | **越阈的斜纹** | 回退率面积图阈值以上是 45° 斜纹（不是一片红）；漏斗淘汰段、灰度活动的分布条、熔断的实验桶都用同一套斜纹。**把整个原型截图转成灰度，所有信息必须依然读得出来** |
| 7 | **签名色的稀缺** | 复写紫红全屏面积 <4%——数一数：主按钮、当前选中行、命中档位整行、漏斗终选级、图表 P99 线、kicker。**表格里的占比条一律是中性明度阶，绝不是紫红**（X8 的现场证明） |

**反向验收（同样重要）**：原型里**不许出现**——玻璃拟态、任何 `backdrop-filter`、彩虹渐变、粒子/极光背景、大于 10px 的圆角、emoji 图标、数字滚动动画、静止状态下的任何循环动画、居中的 hero 区、卡片左侧强调色条。

最后一条自检：**把原型的六屏截图并排贴在墙上，蒙掉标题。** 如果有人问"这是 Grafana 还是 Datadog"，说明我们做失败了；如果有人问"这是什么产品，我没见过"，说明我们做对了。