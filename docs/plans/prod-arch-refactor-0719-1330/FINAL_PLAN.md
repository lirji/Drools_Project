# FINAL_PLAN · 微服务化 + 前后端分离重构

> 2026-07-19。决策依据见同目录 `DECISION_RECORD.md`（D1–D6）与 `00-microservices-evaluation.md`。
> 形态：后端最小二分（M 线）+ 前端 Vue3 框架化（F 线），两线在 M1.3 网关处汇合。
> **本计划获批前不改任何代码。**

## 0. 规模与推进节奏（评审补·诚实）

这是**跨多个工作会话的工程**，不是一次做完：前端 console 全重写（4 页 + 递归条件树 + 20 摘要组件）+ Vue 学习曲线 + 后端六步微服务拆分 + compose/nginx。**建议推进顺序**：先做 **F0（脚手架，证明零侵入）+ F1（活动工作台，收益最大、痛点全在此）**，到 G4 门全绿后回看 F2（演示台迁移 vs 降级 C 闸门）与 M 线（后端拆分）是否继续。M 线可在 F1 后独立并行。每个里程碑都留了一步回滚（§10），任何阶段可停在可用状态。

## 1. Goals / Non-goals

**Goals**
- G-1 后端拆为 decision-svc（只读决策）+ console-svc（写面+Step 1–18），单库双账号，compose+nginx 网关一键起，更像生产样式。
- G-2 前端独立为 `frontend/` Vue3+Vite+TS 工程，覆盖活动工作台（F1）与 18 Step 演示台（F2），根治丢焦点/无路由/DOM 回读三大流畅度痛点。
- G-3 保留 OIDC PKCE 登录全部安全不变量；补齐四眼前端接线（dev 档 X-Actor + submittedBy 展示）。
- G-4 每阶段有自动化验证门（G0–G5）与一步回滚。
- G-5 平板适配（侧栏抽屉）+ 触控 44px；UX 升级：全局 toast 替 4 处 alert、列表筛选/分页、表单就地校验、条件树增量渲染。

**Non-goals**
- 不拆库、不引注册中心/配置中心/K8s/MQ/Redis/BFF（替身见 00 号 §6）。
- 手机 ≤560 仅"可读不崩"（30 号决策 2 沿用；2 个应急例外属未来态界面）。
- 不做审核队列/灰度/回滚/版本历史/监控看板界面（后端端点未就绪，诚实空态原则下不画假界面）。
- 不引组件库（先手写+平移 token）；不做 SSR；不改 Drools/Spring 版本；COUPONS/CPS/RIGHT_COUPON 类型仍禁用。

## 2. 路由与页面流（前端）

**挂载基路径 `/ui/`**（F0–F2 与旧 index.html 同源并存，避免与根 `/` 欢迎页冲突；Vite `base: '/ui/'`）。下表路由均在此前缀下，实际 URL 为 `/ui/console` 等。F3 退役旧页后可选迁回根（另议，不在本期）。

```
/ui/                     → redirect /ui/console
/ui/login                auth 档登录页（webClients 按租户点选）
/ui/auth/callback        OIDC 回调（校验 state → 换 token → replace 到 returnTo）
/ui/console              活动工作台外壳（lazy chunk）
  /ui/console/activities        列表（筛选/分页）
  /ui/console/activities/new    新建
  /ui/console/activities/:id    详情
  /ui/console/activities/:id/edit  编辑（版本+1）
  /ui/console/validate          优惠验证
/ui/demos                18 Step 演示台外壳（lazy chunk，F2）
  /ui/demos/:demoId?example=idx  demo 深链
```
- history 模式（vue-router `createWebHistory('/ui/')`）；F0–F2 期 Spring 把 `/ui/**` 非静态请求 forward 到 `/ui/index.html`（挂在既有 `TenantRateLimitConfig` 或新 `WebMvcConfigurer`，~10 行），网关期 nginx `try_files $uri /ui/index.html`。hash 为零后端改动的降级开关。
- **redirect_uri = `${window.location.origin}/ui/auth/callback`**（origin 派生含 base；dev 为 `http://localhost:5173/auth/callback`，见 §5 与风险表）。
- 页面流不变量：dev 档进 /ui/console 直接可用（X-Tenant-Id）；auth 档未登录 → /ui/login；登录后回 returnTo；401 → 清 token → /ui/login。

## 3. 组件树（标注 新建/平移）

```
shared/   apiClient.ts(新建·服务注册表 root|marketing→未来+decision)  tokens.css(平移 styles.css :root 三态)
          ui/{Card,Kv,TagList,BoolPill,StatusPill,Toast(新建),ErrCard}(DemoUI 转世)
auth/     authClient.ts(平移 activity.js PKCE/换码/refresh 纯逻辑)  useAuthStore(Pinia 包装)
          LoginView.vue  CallbackView.vue
console/  pages/{List,Detail,Editor,Validate}.vue(重写)
          condition-tree/{ConditionGroup,ConditionLeaf,ValueControl}.vue(自引用递归，key=临时 node.id)
          DynRowTable.vue(泛型动态行表→阶梯/赠品/SPU/池)  DictSelect.vue(字典驱动)
          TenantBar.vue(dev 档) / AuthBar.vue(auth 档，+X-Actor 输入框[dev]与 submittedBy 展示)
demos/    catalog.ts(平移 examples.js 强类型化)  DemoShell/DemoNav/DemoPanel.vue
          summaries/ 20 个摘要小组件(平移 SUMMARY registry 分发模式)
stores/   useDictStore(field-dict 按租户+bizLine 缓存)  useTenantStore(localStorage actTenant)
```

## 4. 状态与边界（逐页）

| 页面 | loading | empty | error | 其它边界 |
|---|---|---|---|---|
| 列表 | 骨架行 | 空态+新建 CTA | **修现状 bug：错误不再伪装空态**，ErrCard+重试 | 筛选态保 URL query；切租清缓存 |
| 新建/编辑 | 提交中 disable | — | 409 专文案+刷新引导；400 字段级定位（激活既有 .field-error） | 必填/时间范围/金额就地校验；编辑重铸 requestId；切 segToggle 不丢输入；离开脏检查提示 |
| 详情 | 骨架卡 | — | ErrCard | — |
| 验证 | 按钮 disable+pending | 无命中态 | ErrCard（修现状 j={} 硬渲染） | 双按钮防并发；AbortController 取消在途 |
| 条件树 | 预览"编译中" | 空树=恒通过提示 | 预览失败定位到节点 | 深度≤4；RANGE 恰 2 值；删中间行不串值（稳定 key） |
| 登录/回调 | 跳转中 | 无 webClients 错误横幅 | state 不匹配/换码失败 → Toast+回登录 | 多 tab：新 tab 未登录回 /login；?code= 清理保路由态 |
| demos 面板 | 请求中+耗时 | — | 400/404/409 hintMap+网络错误分支(平移) | 文本响应(prometheus)/pathParams 未填报错/空 body POST 三特例 |
| 横切 | — | — | 401(auth 档)→清 token→/login | Toast 替 4 处 alert；aria-live 播报状态 |

## 4b. Dev 联调与 CORS 提前验证（评审补）

- Vite dev(:5173) 用正则 proxy 表把 18 Step 散点前缀 + `/activity-marketing` + `/actuator` 反代到 8081/8099，API 侧零 CORS。
- **但 auth 档 dev 真登录会跨 origin 打 Casdoor token 端点(:8000)**：现状同源(8099)可通已实证，新 origin(5173) 未验证。**F0 首要动作之一 = 提前验证**：`http://localhost:5173/auth/callback` 登记进 Casdoor 应用 redirectUris 后，浏览器从 :5173 换 token 是否被预检拒。若拒且 Casdoor 侧不可配 CORS，dev 档回退为「仅测 header 档，auth 档只在 :8099/ui 同源验」——不阻塞 F1，但要在 F0 门明确结论。

## 5. API 契约

- **不改现有契约**：`/activity-marketing/*` 8 端点 + auth-config + 18 Step 散点端点（清单见仓库约束勘察，全部已被前端消费）。响应形状以 controller record 为权威，前端建 TS 类型 + field-dict/create 两处运行时守卫。
- **M1.1 新增**：`/decision/v1/spu-discount`、`/decision/v1/gifts`（薄别名，复用 QueryService；旧路径 deprecated 不删）。
- **M1.4 新增**：generation 表 + decision 侧轮询（内部机制，无对外 API）；可选 `POST /internal/warm`（仅 M2M）。
- 网关期前缀：`/api/console/*`→console、`/api/decision/*`→decision；前端 apiClient 注册表切 base，代码零改动。
- 四眼：dev 档请求带 `X-Actor` header（前端新增输入）；auth 档自动 JWT sub。

## 6. 响应式与移动端适配策略

- 断点：≥1024 桌面全布局 / 768–1023 平板（**侧栏 off-canvas 抽屉 + 汉堡**，表单单列，demo-grid 堆叠沿用）/ ≤560 手机（可读不崩：列表卡片化、条件树纵堆叠最小缩进、行表横滚容器）。
- 触控：`(pointer:coarse)` 下交互目标 ≥44px（row-del/ctree-mini/alist-acts 现仅 27–29px 全部放大）。
- 验收要求见 §9；测试矩阵见 §8。手机深度优化为 non-goal。

## 7. 文件级改动清单 + 实施步骤（按依赖排序）

**F 线（前端，先行）**
| 步 | 改动 | 验证门 |
|---|---|---|
| F0 脚手架 | 新建 `frontend/`（create-vue+TS+Pinia+vue-router+Vitest）；apiClient/authClient/tokens.css；Vite proxy 表；Maven `frontend-maven-plugin` 挂 generate-resources 拷 `dist→static/ui/`（`-DskipFrontend` 可跳）；Spring `/ui/**` forward 控制器；契约冒烟脚本平移 | **G1**：`npm run build` 过；`./mvnw test` 104 绿；G0 基线三命令（dev 3/3、oidc 9/9、104 绿）原样全绿=零侵入 |
| F1 活动工作台 | console/* 全部组件+3 store+登录/回调路由；data-testid 契约表；四眼接线；UX 升级（toast/校验/筛选分页/骨架）；改 casdoor-spa-provision.sh 支持 redirectUris 追加更新并登记 dev/ui 两值；application.yml redirect-uri 注释更新；旧 index.html 活动入口加"新版工作台"链接（activity.js 原样保留） | **G3**：`e2e-dev-v2` ~8 断言绿；**G4**：`e2e-oidc-v2` 9+1(多 tab) 绿；Vitest 树+auth 全绿；768px 平板 smoke 1 条 |
| F2 演示台 | catalog.ts + DemoShell/summaries 组件化；根 index.html 变跳转页 | **G2**：`e2e-catalog-v2` 按 catalog 循环 33 面板断言非网络错误 + 3 个代表面板摘要断言 |
| F3 退役 | 达退役门槛（auth 档真登录走查+契约冒烟绿）后删旧 assets 三 JS，或降级 C 闸门（演示台永久保留原生，F2 裁掉） | **G5**：回滚演练一次通过后再删 |

**M 线（后端，F1 后可并行）**
| 步 | 改动 | 验证门 |
|---|---|---|
| M1.1 | `/decision/v1/*` 别名 controller | 104 绿+双路径一致 |
| M1.3 | `docker-compose.yml`（mysql/casdoor/后端/nginx 网关托管 `frontend/dist`）；nginx.conf 前缀路由 | compose up 走网关全套 E2E 绿 |
| M1.4 | generation 表实体+repo；ArtifactService 发布 bump；RuntimeService 轮询预热 | 发布→轮询预热命中；104+新测试绿 |
| M2.1 | Maven 多模块化 activity-common/console/decision/drools-lab | 104 绿；两 app 独立启动 |
| M2.2 | decision 物理拆分（8082 只读账号 validate）；网关切流；移除进程内直调 | kill console 决策仍服务；kill decision 不伤 console |
| M2.3 | 双 prometheus + grafana 面板 | 面板双服务指标可见 |

## 8. 测试策略

- E2E（Playwright，入仓 `frontend/e2e/`）：v2 三脚本继承全部现有断言+4 条新增（幂等 tag/409/version+1/预览失败）+1 多 tab；选择器走 data-testid 契约表。视口：1280×800 主 + 768×1024 平板 smoke 1 条。
- Vitest：条件树不变量与组件 ~15 例；api/auth ~8 例。契约冒烟（field-dict/preview shape）进 npm script。
- 后端：104 绿是每个 M/F 门的硬前置；M 线新增 generation/别名路径测试。
- 性能预算：首屏 JS gzip ≤150KB、总 ≤250KB（不引组件库前提下 Vue 运行时 ~34KB 富余）；首屏可交互前最多 1 个阻塞 API；build 后体积检查进 G5。

## 9. 验收标准

1. G0–G5 全部验证门命令+期望数字通过（无人工判断项）。
2. 现有回归基线不降：dev 3/3、oidc 9/9（v2 同断言）、`./mvnw test` 104 绿。
3. 流畅度三痛点消失的可验证断言：条件树删中间行不串值不丢焦点（E2E）、切 segToggle 不丢已输入值（E2E）、列表/详情/demo 深链刷新可复现（E2E）。
4. **平板 768×1024 视口**：侧栏抽屉可开合、活动表单可完整填写提交（E2E smoke）。
5. M2.2 演示：kill console 容器决策 API 持续正确响应；kill decision 不影响 console 与 Step 1–18。
6. 全程后端零 CORS 配置（代码审查项）。

## 10. 风险与回滚

| 风险 | 缓解/回滚 |
|---|---|
| redirect_uri 三方不同步（最高危） | origin 派生+Casdoor 白名单多值并存不删旧值；G4 门专测；provision 脚本改 update 语义 |
| 双前端并存期幂等短路假象（新前端漏重铸 requestId） | E2E 专断言；并存期 auth 档只归新前端 |
| Maven 前端插件拖慢后端迭代 | `-DskipFrontend`；前端独立 `npm run dev` 不依赖 Maven |
| 条件树组件化状态 bug（渲染期 mutation/index key） | 归一化函数+稳定 id+Vitest 不变量测试（30 号已实证的坑清单） |
| 双 app DDL 打架 | 铁律仅 console 执行 DDL；decision validate |
| h2 file 锁 | compose 形态一律 MySQL；本地双实例用 h2:mem 覆盖 |
| **总回滚** | F 线任意阶段：删 `frontend/`+去 Maven 插件+还原入口链接=回到现状（旧 assets F3 前不删）；M 线：compose 下线回单进程 `spring-boot:run`，M2 前无任何不可逆改动；DB 仅新增 generation 表（可留可删） |
