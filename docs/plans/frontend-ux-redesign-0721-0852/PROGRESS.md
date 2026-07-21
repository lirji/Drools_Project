# PROGRESS · 前端 UX 重设计 + Calm Precision 合并轮

> 交接文件：任何会话/模型接手前先读同目录 `FINAL_PLAN.md`（执行蓝本）+ `DECISION_RECORD.md`（决策依据 + 评审处置表）。

## 状态

- **2026-07-21 计划获批**：用户选「批准，开始实施」＝完整 **Phase A→G**，并指定**用 Opus 4.8 模型执行**。
- 获批前未改任何代码（工作区仅 `frontend/package-lock.json` 有既存改动，与本轮无关）。
- 独立评审 2 严重 / 7 中等 / 9 轻微已全部修入计划（见 DECISION_RECORD 处置表）。

## 下一步（从这里接手）

1. 建分支 `feat/frontend-ux-redesign`（从 main）。
2. 按 FINAL_PLAN §9 顺序执行，**Phase 粒度 commit**，每阶段跑对应验收门：
   - [ ] **A** tokens.css 换代（faint 用 `#6b7280`，非 v2 的 #757c8a）→ typecheck+build 绿 + 明暗目检
   - [ ] **B1** Icon.vue（16 图标清单在 FINAL_PLAN §2）+ Icon.test.ts
   - [ ] **B2** 外壳三件套（**不含 nav-home**）→ vitest + 768/390 核 4px
   - [ ] **C** HomeView(`src/home/`) + 路由(`/home`+redirect+scrollBehavior) + nav-home + returnTo 兜底改 `/home`（同 commit）
   - [ ] **D** ConfirmDialog + useScrollLock + 上下线确认 + 离开守卫替换 + LoginView loading
   - [ ] **E** Field/Section 落地 + invalidNodeIds 行内校验（每步跑 ConditionGroup.test）+ ListView <1024 卡片化（保 `.tr`）+ 断点统一
   - [ ] **F** catalog GROUPS 文案（禁整文件重格式化）+ DemoHome 目录页 + DemoNav 徽记 → e2e:catalog 绿
   - [ ] **G** PageTransition 三出口（每落一处跑 e2e:dev）+ FOUC(非 module) + toast 动画 + contract.md 更新
3. 最终验收门：FINAL_PLAN §11 全项（5 E2E 实跑 + 6 Vitest + typecheck + 768/390 溢出 + 体积门）。

## 红线速查（实施中不许碰）

全部既有 data-testid 逐字保留（只增不改）；`.tr`/`.text-box`/`.no-body` class 保留；catalog.ts 不进主包且禁重格式化；768 平板保持 docked、tablet-smoke 零改；token/存储 key/路由 name 不改名；OrderSummary `→` 字符保留。

## 待用户验收确认的假设

强调色 indigo `#4f46e5`（Phase A 后看真实渲染可换 cobalt/violet）、圆角 14→10、首页最近活动取前 8 条。
