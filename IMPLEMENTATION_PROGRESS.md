# 实施进度 — Drools 前端演示台

对应计划：`docs/plans/drools-demo-frontend-0712-1553/FINAL_PLAN.md`（方案 A：Spring Boot 同源静态前端，覆盖全部端点，现代美观 UI）

## 阶段清单

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| 1 | demo catalog（`examples.js`，全端点 + prometheus） | ✅ 完成 |
| 2 | 前端逻辑（`app.js`） | ✅ 完成 |
| 3 | 布局与样式（`index.html` + `styles.css`） | ✅ 完成 |
| 4 | 构建 + 冒烟测试 | ✅ 完成 |
| 5 | README + 自检 | ✅ 完成 |

## 交付文件
新增（4 个静态资源）：
- `src/main/resources/static/index.html` — 骨架（顶栏 + 侧栏 + 面板容器）
- `src/main/resources/static/assets/examples.js` — demo 目录，33 个 demo（32 后端端点 + `/actuator/prometheus`）
- `src/main/resources/static/assets/app.js` — 渲染/请求/摘要逻辑
- `src/main/resources/static/assets/styles.css` — 现代美观样式，支持明/暗主题

修改：
- `README.md` — 新增「🖥 前端演示台」小节

未触碰：所有 `controller/`、`service/`、`domain/`、`rules/**`、`application*.yml`、`pom.xml`。
（`audit/AuditEvent.java` 是会话开始前就存在的未提交改动，全程保留未覆盖。）

## 阶段 4 冒烟测试结果（H2 profile，localhost:8081）
- 静态资源 `/`、`/index.html`、`/assets/*` 全部 200；首页确为演示台页面。
- 端点值核对：`/discount/calculate` finalAmount=516.8（3 条 reason）；`/dmn/price` VIP2 → Final Price 900；
  `/tms/compare` logical.phase2=[]、regular.phase2=2 条；`/pipeline/audit` 返回 `{cart, auditTrail(17)}`。
- 失败路径：非法 DRL 400、未知会话 404、未知会话 purchase 400、未 deploy 的 scanner/run 400、活动结束后 check 409 —— 全部符合预期。
- 持久化：loyalty 跨请求累积到 GOLD（badges BRONZE/SILVER/GOLD）；campaign 创建 ACTIVE → check eligible=true → end → 409。
- 全部 32 个后端端点 POST/GET 冒烟均 200。
- JS 语法校验通过（`node --check`）。
- **端点覆盖核对**：目录里的 method+path 与后端真实端点集合**完全一致**（无虚构端点、无遗漏端点）。

## 与原计划的差异 / 发现并修正的问题
1. **修正字段漂移 bug**：`hot/run` 实际返回 `Result{firedCount, cart}`（不是裸 Cart）。
   已把该 demo 的 `summary` 从 `"cart"` 改为新增的 `hotRun` 渲染器（读 `r.cart`）。这是计划里预警的头号风险，冒烟时命中并修掉。
2. **覆盖数从"31"更正为"32 端点 + prometheus"**：实际后端有 32 个功能端点（计划文档写的 31 略少）。
   目录已按实测做到 100% 覆盖，Step 7/11/16 均有 UI（这正是阶段二对 Codex 方案的修订点）。

## 备注
- 演示台服务当前以 H2 profile 在 `localhost:8081` 运行中（供直接打开浏览器体验）。
- 运行中的 Spring Boot 实例从 `target/classes/static` 提供静态资源；改静态文件后需 `./mvnw resources:resources` 或重启才生效（生产打包 `mvn package` 自动包含）。
