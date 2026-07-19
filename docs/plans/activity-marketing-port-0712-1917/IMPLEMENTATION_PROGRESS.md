# 实施进度 · activity-marketing-port

> 方案 A「收敛移植」，用户确认扩展范围：红包(eligibility+discount) + 阶梯 LADDER + 决策表资格 + 买赠 BUY_AND_GET + 商品池自动圈选。前端报表页走 `/frontend-plan`。
> 计划见 `FINAL_PLAN.md`。每阶段完成后：编译 + 跑测试 + 看 git diff + 更新本文件 + 对照完成标准自检。

## 阶段总览

| 阶段 | 内容 | 状态 |
| ---- | ---- | ---- |
| 1 | 数据结构与领域模型（JPA 实体 / Repo / fact / 枚举 / 场景） | ✅ 完成 |
| 2 | 核心业务逻辑（创建/版本化编辑/上下线/SPU优惠/圈选/规则执行/回退/幂等） | ✅ 完成 |
| 3a | 后端接口层（ActivityMarketingController + 字段字典 + preview） | ✅ 完成 |
| 3b | 前端报表页（Option C 独立 activity.js，用户批准+种子） | ✅ 完成（headless 可验证项已过；浏览器人工冒烟待做） |
| 4 | 测试（H2 集成 6 用例通过） | ✅ 完成 |
| 5 | 文档与最终检查 | ✅ 完成 |

## 阶段 3b ✅ 完成：前端报表页（Option C）

- [x] `app.js` 接缝：末尾导出 `window.DemoUI`；`renderNav` 加 `external` 组分支（在空 demo return 之前）
- [x] `examples.js` 加 external group「活动营销」（不加 demo）
- [x] `index.html` 挂 `activity.css` + `activity.js`（app.js 之后）
- [x] `activity.css`：~30 个新类，全用现有 token，纯新增选择器
- [x] `activity.js`：子应用（mount + router + 列表/表单/详情/验证 4 视图 + 递归条件树构建器 + 动态行 + 预览 + 提交）
- [x] 9 项独立评审修正全部落地（renderNav 分支位置 / examples 路径 / 阶梯非 DistributionMode / 响应契约 / 时间读路径 ISO / 编辑新铸 requestId / 种子门控 / el disabled 用 property / 活动类型只启用 1&5）
- [x] `ActivityDemoSeeder`（`@ConditionalOnProperty` 门控，测试不触发）+ application.yml `seed-demo-data: true`

**验证**（headless）：
- `./mvnw compile` 通过；`node --check activity.js` 语法通过；`./mvnw test` 6/6 绿（种子在测试中未触发，池断言不受污染）。
- 启动 h2：`/assets/activity.js`、`/assets/activity.css` 均 200，`index.html` 引用两者；种子日志 poolId=1。
- HTTP 冒烟池链路：`create poolRefs=[1]` → `autoBoundCount=2`（圈中 9101/9102）；上线后 spu 9101 命中 ¥25、rule-engine；spu 9104（电子但 30 元、超出池价区间）不命中——圈选边界正确。

**待人工**：浏览器点选冒烟（见 FRONTEND_PLAN.md 测试策略清单）——条件树增删嵌套/operand 值控件/预览/类型切换显隐等纯 UI 交互，headless 无法覆盖。

## 阶段 4 ✅ / 阶段 5 ✅

- 阶段 4：`ActivityMarketingFlowTest` 6 用例覆盖创建/编辑/上下线/阶梯/买赠/池，真跑 DRL。
- 阶段 5：新增 `docs/activity-marketing.md`（模块说明 + 接口样例 + 来源字段映射 + 未迁移项）。

## 收尾增强（用户「按推荐来」）

**#3 补测试 ✅**：新增 `ActivityMarketingEdgeTest`（非法条件不落库 / 幂等 / 版本化完整性）+ `ActivityMarketingLegacyTest`（引擎关闭走 legacy 忽略资格取最大）。**全套 10 用例绿**。种子在所有测试中都已关（3 个测试类的 @TestPropertySource 都设 seed-demo-data=false）。

**#1 前端加固 ✅（headless 可验证部分）**：契约冒烟——把前端条件树构建器的**精确输出**（嵌套 `AND[ between, OR[ in, containsAny ] ]`）POST /preview → ok=true 且生成正确 Drools 约束；用该树 + `ladder(max:null)` create → 成功；validate 命中阶梯档 / 越界淘汰正确。证明前端 payload 契约端到端成立。仍待纯人工浏览器点选（headless 无法覆盖）。

**#2 决策表资格 —— 复核后判定不做（避免冗余）**：细看后，**资格条件树已覆盖地域/属性资格**（契约冒烟已验证 `userDistrictId in (...)` 生效），决策表资格与之**功能重叠**；且本项目 Step 7 已演示决策表能力。再加一条决策表资格链路是重复建设 + 集成牵扯主流程，收益低。故不实现，如将来确有"运营用 Excel 维护资格"的诉求再单独排期（可仿 Step 7 `vip-discount.xls` + `VipDiscountSheetGenerator` 模式）。

## 阶段 1 ✅ 完成

- [x] 抽取来源实体字段（两个子代理返回精确 schema + DRL 模板）
- [x] 枚举：`ActivityType/ActivityStatus/DistributionMode/RuleScene/StackStrategy/FieldValueType/RuleLogic/RuleOperator`
- [x] 字段白名单 `RuleField` + 条件树 `ConditionNode`
- [x] facts（可变 POJO）：`ActivityCandidate/ActivityRuleContext/ActivityRuleResult/GiftResult`
- [x] JPA 实体 10 个 + Repository 10 个（`domain/` 14 文件 + `persistence/` 20 文件）
- [x] 编译通过 `./mvnw clean compile`
- [x] H2 profile 启动成功（`Started DroolsDemoApplication in 4.851s`），10 张新表自动建成：
      `activity_manage / activity_rule / activity_spu_binding / activity_condition / activity_strategy /
       activity_gift / activity_product_pool / activity_product_pool_rule / activity_pool_ref / demo_product`
- [x] 未动 Step 1–18（campaign 表仍在，新表独立）

**完成标准对照**：H2 自动建表 ✓；实体覆盖创建/绑定/规则/策略/条件/池/买赠/商品 ✓。→ 进入阶段 2。

## 阶段 2 ✅ 完成：核心业务逻辑

- [x] `RuleConditionTranslator`：ConditionNode → Drools LHS 约束（字段/运算符白名单校验，非法即抛）
- [x] `ActivityDrlBuilder`：eligibility（not-match reject + collect）/ discount（MAX/MUTEX/STACK/PRIORITY，照抄来源模板）/ ladder（分档）/ gift（保留奖品）
- [x] `LadderRangeParser`：redPackageRangeAmount JSON → 分档
- [x] `ActivityRuleRuntimeService`：DRL 内容级缓存 KieBase + StatelessKieSession 执行 + fail-safe 回退
- [x] `ActivityMarketingService`：create / updateByVersion / changeStatus / getDetail / previewEligibility + 校验 + 幂等(requestId) + 版本化并发冲突(softDeleteVersion 影响行数) + `@Transactional`
- [x] `ActivityQueryService`：filterBeginActivityIds（上线+时间+类型）、spuDiscount（资格→阶梯→折扣+回退 trace）、buyAndGetGifts
- [x] `ActivityPoolMatchService`：内存圈选 demo_product + 目标态 diff 物化自动绑定（幂等）

## 阶段 3a ✅ 完成：后端接口层

- [x] `ActivityMarketingController`：create / {id}/status / list / {id} / spu-discount / gifts / preview / field-dict
- [x] 错误约定同 CampaignController（400/409）
- [x] `application.yml` 加 `activity.marketing.rule-engine.enabled`（灰度开关）

**验证**：
- `ActivityMarketingFlowTest` 6 用例全绿（**真跑 DRL**）：资格淘汰+MAX、下线不命中、版本化编辑、阶梯三档、买赠、商品池自动圈选。
- HTTP 冒烟（h2 profile，:8081）：field-dict / create / online / spu-discount（订单 200 命中 50 + trace / 订单 50 资格淘汰不命中 / mode=rule-engine）/ preview 非法字段被白名单拒。

## 阶段 3b（待办）：前端报表页 —— **走 /frontend-plan**

用户选择先对报表式表单 + 条件树构建器出页面级方案，评审通过后再实现。
后端已备好前端所需：`GET /field-dict`（下拉字典）、`POST /preview`（保存前校验）、六个业务端点。

### 关键设计决策（阶段 1 落定）

- **纯 Drools，不引 QLExpress**：资格条件树 `ConditionNode` 由 `RuleConditionTranslator` 翻译成 **Drools LHS 约束语法**（如 `orderAmount >= 100 && userTags contains "vip"`），放进 `ActivityRuleContext( ... )`。资格判定用 `not ActivityRuleContext(<约束>)` → `$c.reject()` 实现 fail-closed（条件不满足即淘汰候选）。空条件树 = 不生成淘汰规则 = 恒通过。
- **字段白名单用内置枚举 `RuleField`**（不建 DB 字典表，简化）：字段 key / 标签 / 对应 `ActivityRuleContext` 访问器 / 值类型 / 允许运算符；前端下拉走一个字典接口暴露它。
- **facts 是可变 POJO**（Drools 会 `modify`/`set`）：`ActivityCandidate` / `ActivityRuleContext` / `ActivityRuleResult` / `GiftResult`，字段对齐来源 `engine/fact/*`。
- **场景**：ELIGIBILITY / DISCOUNT（含算额+合并，省掉 QL 的 DISCOUNT_COMPUTE）/ LADDER / GIFT。
- **合并策略 DRL**（MAX/MUTEX/PRIORITY/STACK）、**阶梯**（redPackageRangeAmount JSON → 分档 DRL）、**买赠**（gift 规则保留候选奖品）均照抄来源 DRL 语义，运行时用 `KieHelper` 编译。
- **商品池圈选**简化：来源是 SQL 打车辆表；demo 用自带 `demo_product` 表（spuId/店铺/名称/类目/价格/标签），圈选规则 = 价格区间 + 类目 + 标签，Java/JPA 过滤后物化进 `activity_spu_binding`（bind_source=AUTO）。
- **省掉的来源字段**：合伙人/审核/权益系统 id、车辆维度字段等与 demo 无关的列不迁。

### 记录

- 2026-07-12：Codex 出规划 → Claude 复核（两仓库逐条核验，Codex 无虚构）→ 用户批准方案 A + 扩展全部四项能力 + 前端走 /frontend-plan。
- 2026-07-12：两个子代理抽出来源精确字段/DRL 模板（core + pool/gift/ladder）。开始写阶段 1 数据层。
