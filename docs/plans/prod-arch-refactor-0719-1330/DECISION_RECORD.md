# 决策记录 · 微服务化 + 前后端分离重构（2026-07-19）

> 输入：1 个架构代理（微服务评估，见 `00-microservices-evaluation.md`）+ 6 个 frontend-plan 只读子代理
> （需求用户流 / UI-UX / 前端架构 / 仓库约束 / 移动端 / 测试风险）。冲突已裁决，见各条。
> 用户指令背景：「看看服务端是不是需要拆微服务，更像生产环境样式；做前后端分离，前端使用框架，充分考虑体验与流畅度」。

## D1 · 服务端形态：最小二分（决策服务 / 控制台服务），不做细粒度

- **备选**：A 单体+生产化包装 / B 决策面·控制台面二分 / C 细粒度四分（编译/决策/运营/身份）。
- **裁决：B，按「先 A 后 B」两里程碑落地**（M1 生产化包装 → M2 物理二分）。
- 理由：①既有"方案 B 二期"的接缝（artifact 冻结、内容寻址缓存、异步预热、fail-safe、@TenantId 机制）已全部实现，拆分从"重构"降级为"部署变更+一条轮询"；②本项目拆分动机是**学习价值**（"更像生产"是显式目标），B 以最小代价演示读写分面/只读账号/发布跨进程传播三个真概念；③热路径零妥协（KieBase 缓存整体留决策进程）。
- **否决 C 的理由**：规则编译服务是伪服务（KieBase 不可跨进程搬运，KJAR 路线已被既有决策否决）；身份服务已存在（Casdoor）；C 只增运维与网络跳数，负学习价值。
- 数据归属：**单库双账号不拆库**（console 读写+独占 DDL；decision 只读+validate）。发布传播：generation 轮询 + artifact 不可变兜底，不引 MQ。

## D2 · 前端框架：Vue 3 + Vite + TypeScript(strict)，仓库内 `frontend/`（推翻 30 号决策 1）

- **备选**：A Vue3 同源单 SPA / B React18 独立工程（TanStack Query + zod）/ C 保守双轨（仅活动台框架化，演示台永久原生）。
- **裁决：A，借入 B 的两条纪律，C 作为 F1 结束时的显式退出闸门。**
- 选 Vue 而非 React 的项目特征依据：①报表式表单密集（v-model 消灭 `saveScalars()` DOM 回读反模式）；②递归条件树是 SFC 自引用递归的甜区；③单人学习型，中文生态摩擦最小；④无 SSR 需求，裸 Vue + vue-router 足够。
- 从 B 借入：①API 边界运行时守卫（至少覆盖 field-dict 与 create 响应，防枚举/形状漂移）；②redirect_uri 多环境策略（见 D4）。
- **决策变更对照**：本决策显式推翻 `30-DECISION_RECORD_FRONTEND.md` 决策 1（"纯原生纪律不引框架"）。作废：FE-0 自研 store/router/reconcile、ES5 锁、无构建约束。**继承的 12 条纪律**：字典驱动下拉、白名单条件树（永不裸 DRL）、requestId 幂等+编辑重铸、树节点临时 id 提交剥离（升级为 `:key`，禁 index key）、token sessionStorage+state 校验、API base 不散写（升级为 apiClient 注册表）、契约冒烟、诚实空态、可信回退、design token+data-theme 双主题、桌面优先定位、SUMMARY registry 模式。

## D3 · 部署形态与 CORS：分阶段统一到网关，全程零后端 CORS

- **冲突裁决**（架构代理 M1.3"网关托管前端" vs 前端代理 A"产物回填 Spring static"）：**分阶段统一**——
  - F0–F2（前端先行）：Vite 产物经 Maven 挂钩拷回 Spring `static/ui/`，保住"`./mvnw spring-boot:run` 一条命令全栈起"；dev 用 Vite proxy(:5173→8081/8099)。
  - M1.3（compose+网关就位）起：产物改由 nginx 网关托管，API 走 `/api/console`、`/api/decision` 前缀。前端代码零改动（apiClient 注册表 + 运行时 API base 已预埋）。
- 后端**始终不加 CORS 配置**：dev 靠 proxy、生产靠网关同源。Casdoor token 端点跨域是既有行为（e2e 实证可通）。
- 路由 history 模式：F0–F2 期 Spring 加约 10 行 `/ui/**` forward；网关期 nginx `try_files`。hash 模式保留为零后端改动的降级开关。

## D4 · OIDC redirect_uri：前端 origin 派生 + Casdoor 多值白名单

- 现状单值模型（application.yml 下发 `http://localhost:8099/index.html`）在多 origin（dev 5173 / Spring 8099/ui / 网关 8090）下失效。
- **裁决**：前端以 `${window.location.origin}` + base(`/ui/`) + `/auth/callback` 派生 redirect_uri（即 `http://host/ui/auth/callback`；dev :5173 因 base 由 Vite 处理为 `http://localhost:5173/auth/callback`）；Casdoor SPA 应用 `redirectUris` 白名单登记全部环境值（provision 脚本改为支持追加更新，现脚本 `ensure_spa_app` 对已存在应用直接 skip 是已知坑，需加 update-application 分支）；auth-config 的 `redirectUri` 字段降级为提示值。回调处理保留双重门（code+verifier）与清 query 防重放，改为保路由态只清 query。
- **CORS 提前验证**（评审补）：新 origin 打 Casdoor token 端点(:8000)的跨域放行未验证，列为 F0 首要动作（FINAL_PLAN §4b）；失败则 dev 档回退为「auth 档只同源验」，不阻塞主线。
- 并存期旧 `index.html` 回调地址**保留在白名单不删**（回滚免动 Casdoor）；auth 档登录闭环归新前端，旧页只保 dev 档。

## D5 · 移动端定位：沿用 30 号决策 2，不重开评审

- 桌面 ≥1024 一等公民；平板 768–1023 完整可用（**侧栏 off-canvas 抽屉**——现状最大结构缺口，288px 侧栏无任何 @media）；手机 ≤560 non-goal，仅保证可读不崩。断点沿用 980/560 两档 + 正交 `(pointer:coarse)` 触控命中区 ≥44px（现状无一按钮达标，最密集的操作按钮仅 27–29px）。
- 30 号的"2 个手机应急例外"（审核放行/回滚止血）属未来态界面，本轮无此屏幕，记入 non-goals 附注待后端就绪再落。

## D6 · 测试政策：E2E 为主干入仓，Vitest 窄口径

- Playwright v2 脚本**随前端工程入仓** `frontend/e2e/`（推翻 31 号"不进仓"旧决策——有构建链后理由消失）；选择器层以 **data-testid 契约表**固化，断言逻辑 100% 继承现有基线（dev 3 + oidc 9），另补幂等 tag / 409 提示 / 编辑 version+1 / 预览失败态 4 条。
- Vitest 只投两处高风险面：条件树不变量纯函数+组件（~15 例）、api/auth 模块（~8 例）。不给 33 个 catalog 驱动 demo 面板写组件测试（E2E 循环覆盖）。不追覆盖率指标。
- 基线数字修正：后端 `./mvnw test` 当前 **104 绿**（QA_PROFILE 的 55 是旧档案值）。

## 待澄清问题（不阻塞启动，F1 结束前需用户拍板）

1. 18 Step 演示台最终迁入 SPA（F2）还是永久保留原生（降级 C 闸门）——F1 结束时拍板。
2. 四眼前端接线（dev 档 X-Actor 输入 + submittedBy 展示）已列入 F1 范围；审批队列等目标态界面仍待后端端点。
3. 组件库：F1 先手写 + 平移现有 CSS token 不引组件库；若表单工作量超预期再议按需引入。
4. 旧静态页退役时点：以"auth 档真登录走查 + 契约冒烟全绿"为门槛，非日历时间。
