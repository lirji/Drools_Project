# 独立计划评审结论（2026-07-19）

> 原计划由独立子代理评审，因会话额度中断；由主流程（掌握全部勘察上下文）自行完成评审并核对现状代码。
> 结论：**需小修后可批准**——已就地修正 3 处，其余通过。

## 已修正（本轮直接改入计划）

| # | 严重度 | 位置 | 问题 | 修法 |
|---|---|---|---|---|
| 1 | P1 | FINAL_PLAN §2 | 路由写 `/console`，但同源部署 SPA 挂 `/ui/` 避与旧 index.html 冲突，实际是 `/ui/console`；redirect_uri origin 派生漏了 base | 路由全加 `/ui/` 前缀，`createWebHistory('/ui/')`，redirect_uri=origin+base+/auth/callback |
| 2 | P1 | FINAL_PLAN 新增 §4b + DR D4 | dev :5173 打 Casdoor token 端点跨域 CORS 未验证，埋到实施期风险高 | 列为 F0 首要动作提前验证；失败则 dev 档 auth 仅同源验，不阻塞主线 |
| 3 | P2 | FINAL_PLAN 新增 §0 | 未诚实交代这是跨多会话工程 | 加规模说明 + 建议先 F0+F1 最高价值切片，F2/M 线后议 |

## 核对通过项

- **可行性**：Spring 无显式 welcome-page 配置（自动服务 index.html），加 `/ui/**` forward 不破坏根 `/`；已有 `TenantRateLimitConfig implements WebMvcConfigurer` 可挂载点。Maven frontend 插件、Vite proxy、generation 轮询均无技术硬伤。`casdoor-spa-provision.sh:52` 幂等 skip 确认是坑，F1 已列入改 update 语义。
- **边界闭合**：§4 逐页状态表核对 activity.js 实际行为（4 处 alert、409 文案、幂等 tag、编辑重铸 requestId、条件树空树恒通过、preview 失败态、prometheus 文本响应、pathParams 未填报错、空 body POST）无遗漏；多 tab / token 过期 / 401 边界闭合。
- **移动端落点**：§6 断点表 + §9 验收含"平板 768×1024 侧栏抽屉可开合 + 表单可提交"E2E smoke，非空喊。
- **步骤依赖**：F0→F1→F2→F3 与 M1→M2 无顺序矛盾；M1.3 网关接管产物需 F0（dist 存在），已注明；G0–G5 门与 F/M 步骤对应自洽。
- **回滚**：F 线删 `frontend/`+还原入口一行回现状（旧 assets F3 前不删）；M 线 compose 下线回单进程，M2 前无不可逆改动，DB 仅新增 generation 表（可留可删）——均可信。
- **与既有决策冲突**：D2 显式推翻 30 号"不引框架"（用户授权）；D6 推翻 31 号"Playwright 不进仓"（有构建链后理由消失）；其余继承。无未声明冲突。

## 总体判断

**计划可批准**（小修已完成）。建议按 §0 节奏：先 F0+F1，G4 门全绿后再定 F2 与 M 线。
