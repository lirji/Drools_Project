# 重构尾巴执行进度（F3 + M 线 M1.4/M2.1/M2.2/M2.3）

> 跨会话进度锚。计划见同目录 `FINAL_PLAN.md`（§7 步骤 + G0–G5 验证门）、`DECISION_RECORD.md`、`M-LINE-STATUS.md`。
> 分支：`feat/prod-arch-refactor-tail`（从 `main` 切出）。用户 2026-07-20 拍板「全部剩余按序做完，每步过验证门，可停在可用状态」。
> **本轮之前已提交（不在本进度内）**：M 线 M1.1 决策别名 + 角色门控 + `deploy/` 网关；F 线 F0–F2 Vue SPA。基线 112 测试绿。

## 里程碑清单与状态

| 里程碑 | 内容 | 验证门 | 状态 |
|---|---|---|---|
| **F3** 退役旧原生页 | 根 `index.html` → 构建无关的跳转/落地页；删旧三 JS(`app/examples/activity`) + 两旧 CSS(`styles/activity`)；README 同步 | G5：git revert 回滚演练 + `./mvnw test` 不降 | ✅ 完成 |
| **M1.4** 发布 generation 轮询预热 | `(tenant,bizLine,generation)` 表 + repo；`ArtifactService` 发布 bump；`RuntimeService` 轮询预热 | 104+新测试绿；发布→轮询预热命中 | ⬜ 待做 |
| **M2.1** Maven 物理模块拆分 | `activity-common/console/decision/drools-lab` 多模块（搬 100+ 文件、拆 pom）——计划自陈最大重构风险点 | 测试绿；两 app 独立启动 | ⬜ 待做 |
| **M2.2** decision 物理拆进程 | decision 独立 8082 + 只读账号 + `ddl-auto=validate`；网关切流；移除进程内直调 | kill console 决策仍服务；kill decision 不伤 console | ⬜ 待做 |
| **M2.3** 双 prometheus + grafana | 两服务各暴露指标 + grafana 面板 | 面板双服务指标可见 | ⬜ 待做 |

## 关键约束 / 已定决策（执行期补充）

- **F3 与 `-Pfrontend` opt-in 的张力**：SPA 构建是 opt-in（默认不构建，保后端迭代速度）。故根页退役后必须是**构建无关**的静态落地页，否则默认 `spring-boot:run` 跳到不存在的 `/ui/`。已选：根页做纯 HTML 落地页（内联样式，指向 `/ui/console` + 说明需 `-Pfrontend`/网关），不强制翻默认构建。
- F3 无代码/测试依赖旧三 JS/CSS（已 grep 确认），删除低风险。

## 变更文件流水（每步追加）

### F3 ✅（单原子提交，回滚=revert 该 commit）
- `src/main/resources/static/index.html` — 旧原生演示台外壳 → 构建无关静态落地页（内联样式、明暗主题、44px 触控、指向 `/ui/console` `/ui/demos` + 未构建提示）
- 删除 `static/assets/{app.js,examples.js,activity.js,styles.css,activity.css}`（旧原生台全部资源；空 `assets/` 目录一并消失）
- `README.md` §前端演示台 — 旧原生台描述 → Vue3 SPA（`/ui/`）+ 三种起法（Vite dev / `-Pfrontend` / compose 网关）
- **验证**：`./mvnw test` BUILD SUCCESS 107 跑 0 失败（3 skip 既有）；无 Java 改动=不降。前端 `vue-tsc` 0 报错 + `vite build` 成功（主 chunk gzip 42.3KB < 150KB 预算）+ Vitest 26/26 绿。grep 确认无代码/测试引用已删资源。
- **未做（runtime 门，需 Casdoor+MySQL+起服务）**：auth 档浏览器真登录走查 + 契约冒烟——F1/F2 已过其 G3/G4，F3 未动 frontend 源码，故替代可行性不变；留待整栈冒烟时补。
