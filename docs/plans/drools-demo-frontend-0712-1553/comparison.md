# Comparison

## 候选方案摘要

| 方案 | 核心思路 | 主要改动 | 典型访问方式 |
| --- | --- | --- | --- |
| A | Spring Boot 静态 HTML/CSS/JS，同源调用现有 REST | 新增 `src/main/resources/static/**` | `http://localhost:8081/` |
| B | Spring MVC + Thymeleaf 服务端渲染 | 新增模板、页面 Controller，改 `pom.xml` | `http://localhost:8081/demo` |
| C | 独立 Vite/React/TypeScript SPA | 新增 `frontend/**`，可能改 CORS/构建 | `http://localhost:5173` + API proxy |

## 统一评分表

评分范围：1 分差，5 分优。复杂度、风险、测试难度、回滚成本按“分数越高越好”理解，即越简单、越低风险、越易测试、越易回滚得分越高。

| 维度 | A 静态前端 | B Thymeleaf | C Vite/React |
| --- | ---: | ---: | ---: |
| 正确性 | 4 | 3 | 4 |
| 改动风险 | 5 | 3 | 2 |
| 复杂度 | 5 | 3 | 2 |
| 可维护性 | 4 | 3 | 4 |
| 扩展性 | 3 | 3 | 5 |
| 测试难度 | 4 | 3 | 3 |
| 回滚成本 | 5 | 4 | 3 |
| 总分 | 30 | 22 | 23 |

## 评分理由

### A 静态前端

- 正确性 4：直接调用现有 REST，最贴近 README 中已有 curl 行为；缺点是缺少强类型。
- 改动风险 5：不改 Java、不改依赖、不碰数据库。
- 复杂度 5：没有前端构建链，Spring Boot 默认托管静态资源。
- 可维护性 4：只要把 demo 配置结构化，维护成本可控；原生 JS 过大后会下降。
- 扩展性 3：足够覆盖 demo，但大型编辑器和复杂状态管理不如 React。
- 测试难度 4：可用浏览器或轻量端到端脚本验证；无需 npm 依赖。
- 回滚成本 5：删除静态资源和 README 说明即可，不影响业务类。

### B Thymeleaf

- 正确性 3：可直接调 Service，但容易与 REST Controller 行为分叉；如果调 REST 又变成方案 A 的变体。
- 改动风险 3：需要改 `pom.xml` 并新增页面 Controller。
- 复杂度 3：Spring 技术栈内可控，但每个 Step 都要写表单绑定。
- 可维护性 3：模板和 Controller 容易随 Step 数量增长而臃肿。
- 扩展性 3：适合表单，不适合审计时间线、DRL 编辑器等复杂交互。
- 测试难度 3：需要测试 MVC 页面和 Service 调用。
- 回滚成本 4：移除模板依赖和页面 Controller 即可，但比 A 多一步依赖回滚。

### C Vite/React

- 正确性 4：组件化和 TypeScript 有助于复杂 UI 正确性；但类型需手写或生成。
- 改动风险 2：新增 Node 工具链、dev proxy、可能 CORS，和当前仓库形态差异大。
- 复杂度 2：需要双进程开发和构建流程。
- 可维护性 4：长期维护和复杂交互最好。
- 扩展性 5：最适合演进为规则实验台。
- 测试难度 3：可做组件/E2E，但要先搭建测试链。
- 回滚成本 3：删除 `frontend/` 可回滚，但若改了 CORS 或 Maven 构建，还需同步撤销。

## 风险评审

### 兼容性

- A：与 Spring Boot 静态资源机制兼容；需要避免路径与已有 REST 端点冲突。
- B：新增 Thymeleaf 依赖可能影响启动时间和模板解析；页面 Controller 路径应避开现有 REST 路径。
- C：Node 版本、npm 包版本、Vite dev server 与后端端口协调都需管理。

### 事务

- A：所有事务仍由现有后端 Service 管理，不新增事务边界。
- B：若页面 Controller 直接调用 `LoyaltyService`、`CampaignService`，事务仍在 Service 方法上；但 Controller 若组合多个 Service 调用，需要明确是否跨事务。
- C：同 A，前端不参与事务。

### 并发

- A/C：并发请求直接打现有 REST；现有 KieSession 按请求创建，大多数 Service 安全。`HotReloadService.registry` 和 `CampaignService.registry` 使用 `ConcurrentHashMap`；`ScannerService.deploy` 同步。
- B：如果服务端页面 Controller 引入页面级缓存或共享表单状态，会新增并发风险。

### 幂等

- `/loyalty/start` 同 sessionId 会覆盖；前端需提示或用示例 sessionId。
- `/campaign/create` 同 id 会更新并重新 ACTIVE；前端需展示这是覆盖行为。
- `/scanner/deploy` 每次 deploy 会增加 generation；不是幂等。
- `/hot/upsert` 同 name 替换 KieBase；不是严格幂等。

### 性能

- A：静态资源很小，性能影响最低。
- B：每次页面提交重渲染 HTML，性能对 demo 足够。
- C：首次加载和构建产物更重，但交互性能好。
- 所有方案都应避免默认在前端频繁调用失控规则接口，如 `/guard/timeout`。

### 安全

- 当前后端暴露 DRL 编译、KJAR deploy、Actuator Prometheus，这适合学习环境，不适合公网。
- A 同源托管不额外扩大 CORS 面。
- C 如果新增宽松 CORS，会扩大攻击面；应优先用 dev proxy 或只允许 localhost。
- 前端不能把 DRL 输入当 HTML 渲染，必须以文本展示错误和响应，避免 XSS。

### 数据迁移

- A：无数据库变更。
- B：无数据库变更。
- C：无数据库变更。
- 如果未来前端需要保存示例或用户配置，需另起数据模型，本任务非目标。

### 灰度与回滚

- A：可通过静态资源开关或直接回滚新增文件。
- B：可隐藏 `/demo` 链接，但依赖仍在；回滚需删 Controller 和依赖。
- C：可不启动前端服务或不发布 dist；如果已改 CORS/Maven，需要同步回滚。

## 推荐结论

推荐以方案 A 为最终方案，并吸收方案 C 的“配置驱动 demo catalog”和“组件化思路”，但不引入 React/Vite。

理由：

- 当前项目已有完整 REST API，缺的是浏览器可视化入口。
- 方案 A 最大化复用现有 Controller 行为，避免页面 Controller 和 REST Controller 分叉。
- 同源静态资源避免 CORS 和双进程启动。
- 回滚最轻，符合学习项目和本次需求规模。

已知弱点：

- 原生 JS 长期扩展性有限。
- 没有 TypeScript 类型检查，需用测试和集中配置降低字段漂移风险。
- 如果未来要做规则编辑器、可视化流程图、保存用户场景，可能需要迁移到方案 C。
