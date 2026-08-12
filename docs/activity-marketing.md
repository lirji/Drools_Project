# 活动营销模块（`com.lrj.drools.activity`）

从 `autolife/mall-shop` 的活动营销部分**收敛移植**进本 demo 的一份可运行副本，用来在本项目前端走一遍"活动创建 → 制定规则 → 上线 → 验证优惠"的完整流程，探索服务端/前端怎么改造。**不是生产代码**，是学习/探索脚手架。

> 规划与实施全过程（Codex 规划 → Claude 跨模型复核 → 分阶段实施 → /frontend-plan）见 `docs/plans/activity-marketing-port-0712-1917/`：`FINAL_PLAN.md` / `FRONTEND_PLAN.md` / `IMPLEMENTATION_PROGRESS.md`。
>
> 2026-08 的**权益模型 + 性能分层重构**（P0-x 观测/取数/租户缺口、P1-1 代际快照、P1-2/1-3 分层引擎、折扣型/一口价/第 N 件折/随机红包/加价购）见 `docs/plans/benefit-model-refactor-0808-2218/`：`FINAL_PLAN.md` / `DECISION_RECORD.md`（D1–D12）/ `PROGRESS.md`（进度锚）。

## 能力范围

覆盖来源的**红包(RED_PACKAGE)**、**买赠(BUY_AND_GET)** 与新增的**加价购(ADD_ON_PURCHASE=6)** 三类活动。2026-08 收敛后，三条通道的请求上下文与资格淘汰固定共用 `DecisionEligibilityService`；红包的固定、随机、阶梯、折扣、一口价、第 N 件折六种形态固定由 `BenefitEvaluator` 算额并合并。旧红包 DRL（算额 + 合并那套）**已随灰度开关一并从代码里删除**——`buildDiscountDrl` 与 `evalDiscount` / `evalLadder` / `evalEligibility` 都不存在了，仓库里**找不到**可供对拍的第二份算额权威，别去找：

| 场景 | 说明 | 生产实现 | 兼容边界 |
| ---- | ---- | ---- | ---- |
| eligibility | 资格淘汰（可视化条件树 → 受控约束，fail-closed） | `DecisionEligibilityService` 统一上下文，`ConditionTreeEvaluator` 解释条件树 | `java-eligibility-eval` 仅配置兼容，false 不切换求值器 |
| discount | 多活动折扣合并（MAX / MUTEX / STACK / PRIORITY） | `BenefitEvaluator.merge()`，一次 O(N) reduce | `java-benefit-eval` 仅配置兼容，false 不切换求值器 |
| ladder | 阶梯满减（`redPackageRangeAmount` JSON 分档） | `BenefitEvaluator.applyLadder()`，线性查表 | 与其余五形态一样固定走 Java |
| gift | 共享资格淘汰后保留/汇总买赠奖品 | 合格候选送入运行时 DRL；引擎关闭/失败时只聚合合格候选 | — |
| addon | 共享资格淘汰后列选项、按当前配置重新报价 | `AddOnPurchaseService` 两阶段查询（报价不占库存） | — |

**为什么把这三件事移出 Drools**：判据是「这条规则需不需要*其它规则的结论*」。阶梯是标量分段函数（原实现**每档生成一条 DRL 规则**，档位多时 KieBase 随运营配置线性膨胀）、折扣合并是一次 reduce（原实现用 `$c : ActivityCandidate() and not ActivityCandidate(computedAmount > $c.computedAmount)` 做 argmax，是 O(N²) beta 评估）、资格是单事实布尔谓词（活动之间零交互）——三者都不需要。规则引擎的价值在规则**之间**的关系，留给 Drools 的是互斥矩阵、级联改写、CEP 频控这类场景。

生产语义不靠配置默认值偶然对齐：**`DecisionGoldenSetTest`（52 例）守金额/边界**，是发布门禁。

> ⚠️ **别把 `DroolsBenefitGoldenSetTest` 当门禁**——它是个**只有注解、类体为空**的子类（`class DroolsBenefitGoldenSetTest extends DecisionGoldenSetTest {}`），
> 父类用例全在 `@Nested` 内部类里，跑子类时这些嵌套类仍按**父类**的 `@TestPropertySource` 执行，
> 拿不到子类设的 false，所以**它自身跑 0 个用例**。真正守「旧 `java-*` 开关配 false 也不得把生产切回 DRL」这条的是
> `ActivityQuerySafetyFallbackTest#legacyFalseFlagsCannotSwitchProductionBackToDrools`（反射把两个字段置 false 再断言行为）。

外加**商品池自动圈选**：规则驱动圈选 `demo_product` 并物化进绑定表（`bind_source=AUTO`，按目标态 diff 幂等刷新）。

**纯 Drools，不引 QLExpress**：来源用 QLExpress 跑资格表达式，这里先翻译成 Drools LHS 约束；P1-3 之后默认连表达式引擎那步也省了——`activity_condition.condition_tree_json` 里本来就存着结构化 AST，直接解释树比「树 → 串 → 再解析串」少一层翻译、零新依赖、零转义面。

## 权益形态（`BenefitForm`）

同一列 `red_package_amount` 里的数字**是什么意思**，由既有的 `red_package_amount_unit` 判别（不新增列——该字段一路从 `activity_rule` 搬到候选/快照/DRL 上下文，但此前从来没被任何计算读过）：

| 单位 | 形态 | `redPackageAmount` 的含义 | 配套字段 |
| ---- | ---- | ---- | ---- |
| `元`（或 null） | `AMOUNT` 金额型 | 要减的钱 | 阶梯分档走 `redPackageRangeAmount` 数组 |
| `折` | `RATIO_ZHE` 折扣型 | **折数**，取值 (0,10)，8 = 八折 = 减 20%（打在**作用域基数**上，不是无条件整单） | `redPackageMaxDiscount` **必填**（封顶减免额） |
| `价` | `FIXED_PRICE` 一口价/秒杀 | **卖多少**（减免 = **作用域基数** − 一口价） | 配套 `/{id}/claim` 扣库存 |
| `件折` | `NTH_ZHE` 第 N 件折 | **折数**（只在**作用域内**的订单行里数「第 N 件」，域外商品不替它凑件数） | `redPackageRangeAmount` = `{"nth":2}` |
| `元` + `redPackageTakeType=2` | 随机红包 | 不读该字段 | `redPackageRangeAmount` = `{"min":5,"max":20}` |

- **未知单位一律回落金额型**（`BenefitForm.of`）：历史数据全是 `元`/null，行为一个字节不变；写错的单位表现为「按金额发」（旧行为）而不是「按猜出来的比例发」。写平面另有白名单 `BenefitForm.isSupportedUnit`，非受控单位直接 400。
- **`redPackageRangeAmount` 是多用途列**：数组 → 阶梯（`LadderRangeParser`）；对象里的 `min/max` → 随机区间、`nth` → 第 N 件（`RandomRangeParser`）。对象结构再由 `BenefitForm` / takeType 与写平面校验约束，不能只看“是对象”就猜形态。
- **底层算术只有一份实现**：`BenefitMath`（静态纯函数）。历史 DRL 和当前 `BenefitEvaluator` 都调这些函数，可避免取整/封顶公式各写一份；但分支判别、适用性与合并策略仍要靠金标和 fallback 测试守，不能宣称“不可能漂移”。钱一律 2 位小数、`RoundingMode.DOWN`（向下取整是系统性偏向"不多发"，与 fail-closed 一致）。
- **「作用域基数」是折/价/件折三形态算钱的分母**（`BenefitEvaluator.baseAmount`，三档顺序不能反）：① 候选的 `scopedSpuIds == null`（作用域未知，手工构造或还没接上作用域的装配路径）→ 整单 `orderAmount`，与改造前逐字节一致；② 作用域**覆盖**本次请求的全部 SPU → 整单（今天绝大多数流量落在这档：单 SPU 查询、全场券）；③ 作用域是**真子集** → 必须靠订单行分摊，`Σ 作用域行小计`，**拿不到行就返回 null 让候选被淘汰**，绝不用整单金额顶替。之所以要这一层：绑定关系此前只是个候选筛选器，一个只绑了 B 的「9.9 一口价」在「A 5000 元 + B」的车里会算成 `5009.9 − 9.9`，**整车按 9.9 成交**。
- **算不出来返回 null = 本活动不适用，不是"减 0 元"**：0 元会以 0 参与 MAX 竞争并挤掉别的真能减钱的活动。缺订单金额、折数越界 [不在 (0,10)]、折扣型无封顶、缺逐行单价、订单比一口价还便宜、作用域基数不可知——全部按"不适用"处理。
- **随机红包是确定性随机**：金额 = `SHA-256(活动id|版本|userId|购物车指纹)` 落进 [min,max] 区间，同一上下文永远抽到同一个数（刷新不变价、可重放、可对账）。购物车指纹是 `canonical(orderAmount)|canonical(quantity)|randomSeedSpu`，因为决策入口没有订单号。**这条串的任何一个字节都不能动**：
  - 数值段过 `canonical()`（`stripTrailingZeros().toPlainString()`）规范化，否则客户端把金额写成 `100` 还是 `100.00` 会得到两个种子、两个红包金额——「同一笔订单刷新不变价」正是这套机制存在的全部理由。
  - SPU 段刻意读 `randomSeedSpu` 而**不是** `spuId`：后者已从「购物车第一件」改成整个列表（作用域改造），`toString()` 从 `990011` 变成 `[990011]`。`DecisionEligibilityService` 专门维持 `randomSeedSpu` = 列表第一件，唯一职责就是把这条种子链钉住。**改它等于改所有历史金额**（全量随机红包一次性重抽，用户刷新变价、历史对账全部对不上）。
  - 发放流水表 `activity_grant` 现在有了，但随机红包仍是**确定性派生**、没有改成真抽奖。
  - **判别顺序**：先由 `BenefitForm` 判单位；只有 `AMOUNT` 才继续看 `redPackageTakeType=2`。因此 API 手造的「折 + takeType=2」仍按折扣算，不会被随机分支抢走。进入 `AMOUNT` 后，随机分支仍必须排在 `redPackageAmount == null` guard 前，否则「只配区间」的随机活动会被静默跳过。未知 takeType 回落固定金额（不抛异常，一条脏数据不该打断整批候选的算额）。
- **写平面校验（`validateBenefitForm`）**——四种形态各有硬校验，违反一律 400：
  | 形态 | 校验 | 为什么 |
  | ---- | ---- | ---- |
  | 折扣型（`折`） | 必须有折数 + 封顶（`>0`），且**不允许同时配阶梯** | 阶梯 reward 是「元」，两种形态打架 |
  | 一口价（`价`） | `redPackageAmount>0` **且 `inventory≥1`** | 0/负数不是运营本意；库存 null/0 时 `claim` 的原子 UPDATE 永远更新 0 行，活动看着健康、实际永远抢不到 |
  | 第 N 件折（`件折`） | 折数必填且须在 **(0,10)** | 10 折=不打折、0 折=白送，都按配置错误拒绝 |
  | 金额型（`元`/null） | 不允许填封顶 | 封顶只对折扣型有意义 |

**生产红包固定由 `BenefitEvaluator` 实现六种形态**（固定、随机、阶梯、折扣、一口价、第 N 件折）。
`java-benefit-eval=false` 现在只是个**从不被读取**的兼容属性（`@Value` 字段带 `@SuppressWarnings("unused")`，
两份 `application.yml` 里也没有这个 key），进程内**没有任何开关**能把求值切回 DRL；不要再用它当回滚手段。
真正的回滚手段是**部署级回滚上一版 jar** + decision 侧快照代际 `rollback`。

## 跑起来

> **M2.1 起是 Maven 四模块**（`activity-common` 共享库：domain/engine/persistence/tenant + 只读查询服务 / `drools-lab` Step1–18 教学 / `activity-console` 写平面 app:8081 / `activity-decision` 只读决策 app:8082）。根 `./mvnw spring-boot:run` **不再可用**（父是聚合 pom，无 main），起服务要 `-pl` 指定 app 模块。拆分详情见下节「决策平面拆分」。

```bash
# 控制台写平面（活动创建/编辑/上下线 + 前端 /ui/ + Step1–18），默认 8081；H2 profile 免装 MySQL
./mvnw -pl activity-console spring-boot:run -Dspring-boot.run.profiles=h2
# 只读决策热路径（/decision/v1/* + 发布代际轮询预热），默认 8082
./mvnw -pl activity-decision spring-boot:run -Dspring-boot.run.profiles=h2
# 顺带把 Vue SPA 构建拷进 static/ui/（否则前端用 frontend/ 的 Vite dev server :5173）
./mvnw -pl activity-console -Pfrontend spring-boot:run
```

浏览器打开 `http://localhost:8081/ui/`（根 `/` 是构建无关落地页，跳 `/ui/`；旧原生演示台已于 F3 退役）→ 活动配置台 `/ui/console/activities`，即可用报表式表单创建活动、拖出资格条件树、上线，在「优惠验证」页 `/ui/console/validate` 查命中。

浏览器里还有 `/ui/console/playbooks`「玩法模板目录」：12 张玩法卡给已有能力起名字并预填编辑器（满 X 减 Y / 第二件半价 / 限时秒杀 / 加价购…），当前均可创建。`/ui/console/validate` 从这 12 份 playbook 直接派生验证场景，并额外提供 random 场景；页面按 discount / gifts / addon 三通道调用真实接口，展示结构化结果与 trace，而不是只打印原始 JSON。场景不指定活动、不强制命中；第 N 件折只编辑订单行，`spuIdList / orderAmount / quantity / lines` 从行项唯一导出。该页**默认打决策平面**（`/api/decision/*`），并可切「控制台走库」或「两条都打对拍」——此前它固定打 console 的 legacy 读端点，而 console 进程里 store 恒空、必然走库，于是「用来自证优惠有没有生效的工具」恰好是唯一照不到快照侧问题（陈旧快照、绑定收窄、轮询延迟）的那条路。页面另带物料来源徽章（`provenance` + 落后几代）、逐活动明细（含被淘汰候选与原因）与快照探针；**「决策服务不可达」与「决策未命中」是两种状态**，401/403 单独判为「可达但未授权」而不降级回走库（降级只会掩盖权限配置问题）。

开关：
- `activity.marketing.rule-engine.enabled`（默认 true）：false 时仍先跑共享资格，再用 `BenefitEvaluator` 安全重算六种形态。它保留 `DecisionDataLoader.resolveStrategy()` 从快照/当前 bizLine 解析的 `STACK / PRIORITY / MAX`（以及同类的 MUTEX），不会统一退化成 MAX。开关关闭或空决策都不能退回只认固定金额的算法。
- `activity.marketing.rule-engine.java-benefit-eval`（默认 **true**）：旧配置兼容属性；代码只绑定但不读取，false 不改变生产红包六形态求值器。
- `activity.marketing.rule-engine.java-eligibility-eval`（默认 **true**）：旧配置兼容属性；代码只绑定但不读取，false 不改变 discount/gifts/addon 共用 `DecisionEligibilityService` 的主路径。
  > 两个旧属性的目的是让已部署的环境不必立即删配置，不是新的灰度/回滚机制。
- `activity.marketing.snapshot.max-age-ms`（默认 **60000**，≤0 关闭）：decision 侧快照的**兜底重建阈值**——轮询器每轮扫完代际后，把年龄超过它的快照按数据库真相重建一遍（见下节「发布代际轮询预热」）。⚠️ **两份 `application.yml` 里都没有这个 key，默认值只在 `GenerationWarmService` 的 `@Value` 里**；想改必须自己加。
- `activity.marketing.seed-demo-data`（默认 true）：启动时种入 4 个 demo 商品 + 商品池（poolId=1，圈电子类 100~200 元），让商品池自动圈选在浏览器可演示；测试中不开，不污染断言。
- `activity.tenant.dev-default-enabled`（默认 **false**，`application.yml` dev-run 显式开为 true）：多租户开关，见下节「多租户隔离」。本地开着时不带 `X-Tenant-Id` 也能跑（回落单租户 `__dev__`），下面的 curl 示例照常工作。

## REST 接口（全部在 `/activity-marketing`）

| 方法 | 路径 | 说明 |
| ---- | ---- | ---- |
| POST | `/create` | 创建/编辑（带 `activityId` 即编辑，version+1；`requestId` 幂等）。入参含 `userInventory`（每人限领份数，null/≤0 = 不限）。返回体新增 `warnings[]` |
| POST | `/{id}/status` | 上下线 `{version,targetStatus}`（0 待上线/1 上线/2 下线）。**非法流转直接拒**（迁移表 from×to）；`targetStatus=3`（待生效）写入口封死——它无生产者也无消费者，置成该状态的活动进不了任何读路径。四眼开启时提交人自审返回 **403**（不是 409，见下） |
| POST | `/bulk-status` | **批量**上下线 `{items:[{activityId,version}],targetStatus}`，回执 `{succeeded[],failed[{activityId,reason}]}`。部分失败仍是 200；但 `targetStatus` 本身非法时**进循环前**就返回 400（否则几十条各失败一次） |
| POST | `/{id}/claim` | **抢占秒杀库存**（`?version=&quantity=&userId=&orderId=`）：抢到 200；没抢到按失败种类分流——**400**（缺 activityId / 数量非正 / 限领活动没带 `userId`）、**404**（活动或版本不存在）、**409**（余量不足、不在可用窗口、超出每人限领）。`orderId` 是幂等键的一半（**不传就退化成不幂等**）；auth 档受已配置的 `console-write-authority` 保护 |
| POST | `/{id}/release?orderId=` | **冲正**：把发放记录置 RELEASED 并归还库存与限领额度。幂等（重复释放不重复加库存）。缺参/空 `orderId` 返回 **400**，确实没有对应发放记录才 **404**。同受 `console-write-authority` 保护——不设防的话反复调它就能把限量活动的库存刷到任意大 |
| GET | `/grants?orderId=` | 按单查发放记录（客服「这一单用了哪些优惠」的数据源） |
| GET | `/generation?bizLine=` | 库里当前发布代际——决策响应里 `provenance.generation` 的**参照物**（行不存在返回 0）。只看决策侧那个数判断不了「我刚发布的那次进去了没有」 |
| GET | `/list` | 活动列表（当前版本） |
| GET | `/{id}` | 详情（manage/rules/conditions/bindings/gifts/poolRefs） |
| POST | `/spu-discount` | 红包优惠查询（资格→阶梯→折扣合并 + 回退 + trace）。控制台是运营的调试入口，**显式 `explain=true`** |
| POST | `/gifts` | 买赠查询（同样 `explain=true`） |
| POST | `/addon/options` | 加价购第一阶段：列出当前请求上下文可选的换购品（空列表是正常结果） |
| POST | `/addon/quote?activityId=&item=` | 加价购第二阶段：按当前配置权威重报价；失效/伪造选项返回 **409** |
| POST | `/preview` | 资格条件树预览（翻译+试编译，不落库；恒 200 读 `ok`） |
| GET | `/field-dict` | 字段白名单 + 运算符 + 枚举（前端下拉的唯一来源，防漂移） |
| GET | `/auth-config` | 前端 OIDC 引导配置。**auth 档下是这个前缀里唯一匿名可读的路径**（安全链一 permitAll + `JwtTenantFilter` 跳过），auth 关时只返回 `{authEnabled:false}`。定义在 `activity-common` 的 `AuthConfigController`，由 console 暴露；`deployment.md` 拿它做最小验收 |

几条容易踩的语义：

- **批量接口的 `version` 必须显式传**。P0-4 之后线上版与草稿**并存**（见下），只给 id 就会落到「最高未删除版本」= 那个还没发布的草稿，于是「批量下线 23 个」把 23 个草稿置成下线、**正在发钱的线上版一个都没停**，回执还报全部成功。工作台按它在列表里看到的那一行传版本。逐条捕获异常、互不影响，部分失败是正常结果（恒 200）；但**单条并不原子**——`bulkChangeStatus` 自身无 `@Transactional`，循环里是同类自调用 `changeStatus(...)`，代理式 AOP 下 `changeStatus` 上的事务注解不生效，「退役其它 ONLINE 版本 → 保存本行 → 推代际」中途失败会留下半成品状态。
- **编辑不再下线正在服务的版本**（P0-4）：当前版本已上线时保留它继续服务、另建 v+1 草稿；当前版本还是草稿时直接顶掉。发布草稿时在同一事务里把该活动其它仍 ONLINE 的版本退役 = **原子指针切换**。
- **`inventory` 是声明式的**：字段存得下，**决策链路不读取、不扣减**。create 返回的 `warnings[]` 会把这一点明说（沉默最危险：运营以为配了就生效）。真要限量走 `/{id}/claim`。
- **`claim` 才是权威扣减**：`decrementInventory` 把「判余量」和「减一」压进同一条 `UPDATE ... WHERE inventory >= :n`，靠数据库对同一行的串行化防超发；**不能改成先查后减**（check-then-act 竞态，低并发测不出、大促必现）。谓词里另含活动状态与时间窗——否则已下线/未开始/已结束的版本库存都能被扣干净。它与 create/status/release 同属写端点：当 `activity.tenant.auth.console-write-authority` 非空时，token 必须具备该 authority，否则 403；默认空值仅为 demo 兼容。决策接口只报价，**不能拿决策成功当作抢到了**。
- **`claim` 现在幂等，靠的是先插流水再扣库存**（`activity_grant`，唯一约束 `tenant+order_id+activity_id`）：命中已有流水直接返回**首次结果**、不再扣减。**顺序不能反**——唯一约束必须在任何扣减发生之前就拦住并发的同一单重复提交；反过来（先扣后插）要靠事务边界一路不出错才能救回库存。扣减失败会把刚插的流水**删掉**：留着一条 HELD 却没有对应扣减的记录，在对账上就是「有账无货」，还会永久占掉该用户的限领额度、并让这一单再也 claim 不了（幂等分支会命中它）。
- **不传 `version` 时 `claim` 解析成当前 ONLINE 版本**（不是最高版本）。此前取最高未删除版本 = **草稿**，而决策发的是最高 ONLINE 版本——防超发的闸门装在了另一行数据上：线上版本库存一件没少、草稿的库存被扣干净。
- **每人限领 `userInventory` 有执行路径了**：按 `activity_grant` 里 (活动, 用户) 的已领份数计。配了限领的活动，`claim` **不带 `userId` 一律拒绝**——无从判断是不是同一个人时放行，等于这条限制不存在。冲正走 `POST /{id}/release?orderId=`，把库存与限领额度一起还回去（归还刻意不判活动状态与时间窗：活动结束之后仍可能有退款进来）。

创建红包活动示例（资格 orderAmount≥100 + 手动绑定 spu 1001）：

```bash
curl -X POST localhost:8081/activity-marketing/create -H 'Content-Type: application/json' -d '{
  "activityName":"新人红包","bizLine":"mall","activityType":1,
  "activityStartTime":1780000000000,"activityEndTime":1790000000000,
  "activityAreaType":1,"priority":1,"inventory":100,
  "redPackageTakeType":1,"redPackageAmount":50,"redPackageAmountUnit":"元","discountStrategy":"MAX",
  "eligibilityConditionTree":{"logic":"AND","children":[{"field":"orderAmount","op":"ge","value":100}]},
  "spuBindings":[{"storeId":1,"spuId":1001}]
}'
# → {"activityId":"ACT...","version":1,"status":0,"idempotentHit":false,"autoBoundCount":0,
#    "warnings":["库存（100）当前为声明式：决策链路不读取、不扣减，不构成超发防护。…"]}
# 上线后 POST /spu-discount {"spuIdList":[1001],"orderAmount":200} → hit=true, amount=50
```

折扣型（八折、最多减 30）与一口价（9.9 秒杀）只是换 `redPackageAmountUnit`：

```bash
# 八折券：单位=折，折数写进 redPackageAmount，封顶必填
#   "redPackageAmount":8,"redPackageAmountUnit":"折","redPackageMaxDiscount":30
#   → 200 元订单减 40 → 封顶截到 30
# 9.9 秒杀：单位=价，redPackageAmount 是"卖多少"
#   "redPackageAmount":9.9,"redPackageAmountUnit":"价"
#   → 200 元订单减 190.1；随后 POST /activity-marketing/{id}/claim?version=1&quantity=1 才算抢到
# 第二件半价：单位=件折 + {"nth":2}，且决策请求必须带 lines（逐行单价）
#   "redPackageAmount":5,"redPackageAmountUnit":"件折","redPackageRangeAmount":"{\"nth\":2}"
#   请求：{"spuIdList":[1001],"lines":[{"spuId":1001,"unitPrice":100,"quantity":2}]} → 减 50
```

## 决策平面拆分（console / decision）

M2 把本模块沿**读写平面**拆成两个独立 Spring Boot 应用，共用 `activity-common`（domain/engine/persistence/tenant + 只读查询服务 `ActivityQueryService`）：

| 应用 | 端口 | 承载 | Maven 依赖 |
| ---- | ---- | ---- | ---- |
| `activity-console` | 8081 | 写平面（create/status/幂等/四眼）+ Step1–18 教学 + 前端 `/ui/` | `activity-common` + `drools-lab`（全量，含 kie-ci/dmn/decisiontables） |
| `activity-decision` | 8082 | 只读决策热路径 `/decision/v1/*` + 发布代际轮询预热 | 仅 `activity-common`（甩掉 `drools-lab` 带来的 kie-ci/dmn/decisiontables 与全部写面依赖，更轻） |

**`/decision/v1` 是决策热路径将来物理拆出去的稳定契约**——`DecisionPlaneController` 复用与控制台**同一份** `ActivityQueryService`（与 `/activity-marketing/spu-discount` 走同一代码，金额一致）；旧 `/activity-marketing/*` 路径保留、不弃用，前端与旧脚本不受影响。

唯一的差别是 **`explain`**：热路径默认 `explain=false`，构建期就不生成 `result.trace(...)`，正常命中时 `traces` 为空（回退/说明性文案仍会出现）。此前四处调用都走默认 true 重载，线上每次决策都在拼 trace 字符串、装 List、序列化进响应体——纯浪费，还把规则内部结构（命中活动、命中策略、金额推导）暴露给下游。控制台试算显式传 true，运营照旧看得到链路。

| 决策平面路径（decision, 8082） | 等价的控制台路径（console, 8081） |
| ---- | ---- |
| POST `/decision/v1/spu-discount` | POST `/activity-marketing/spu-discount`（控制台版 `explain=true`） |
| POST `/decision/v1/gifts` | POST `/activity-marketing/gifts`（同上） |
| POST `/decision/v1/addon/options` | POST `/activity-marketing/addon/options` |
| POST `/decision/v1/addon/quote?activityId=&item=` | POST `/activity-marketing/addon/quote?activityId=&item=` |

决策平面**独有**的观测端点（控制台没有等价物）：

| 方法 | 路径 | 说明 |
| ---- | ---- | ---- |
| GET | `/decision/v1/metrics` | 决策耗时 / 回退计数聚合（**单实例视角**，跨实例汇总仍看 Prometheus） |
| GET | `/decision/v1/by-activity` | 按活动的命中量 `hits` 与**发出的减免金额** `amounts`（带基数上限，见「决策指标」） |
| GET | `/decision/v1/snapshot[?activityId=]` | 本租户的快照桶清单（bizLine / generation / builtAt / ageSeconds / activityCount）；带 `activityId` 时直接回答**在哪个桶 / 不在任何桶** |

**`/snapshot` 这个诊断端点为什么必须存在**：`provenance` 三个值在最要命的那条故障上**全绿**——活动的 `bizLine` 为空（写平面不强制必填）时它进不了任何桶（`DecisionSnapshotBuilder` 按 bizLine **精确匹配**收活动），而兜底重建只遍历**已存在**的桶、永远建不出不存在的那个。此时决策照常走快照、代际是别的业务线的正常数、快照也很新，只是这个活动根本不在里面，页面上看到的「未命中」与「活动确实不该命中」完全同形。`/snapshot` 是这个困惑的终点。它**只读、不发起决策**，因此不会把诊断流量混进 `activity.decision.{hit,amount}`，也不消耗 `ACTIVITY_TAG_CAP` 的标签位。

> ⚠️ 它返回的 `ageSeconds` 取的是**本租户**的桶，与 gauge `activity.decision.snapshot.age.seconds`（`DecisionSnapshotStore.oldestAgeSeconds`，**跨租户**统计——调度线程与指标线程没有租户上下文）**不是同一个数**，多租户下两者永远对不上，别拿来互相印证。

**加价购为什么必须两阶段**：卡点不在算钱而在交互形状——既有链路是「一次调用返回最终优惠」，加价购必须先列清单、等用户挑一个、再二次定价。第一阶段与红包、买赠复用 `DecisionEligibilityService` 的请求上下文与 fail-closed 资格判断；第二阶段**不发 quoteToken、不读客户端传来的价格**，只接受「哪个活动的哪个换购品」，并重新跑资格、重新读取当前选项与价格，失效或伪造返回 409。数据上复用 `activity_gift` 承载换购品，`giftName` 是品名、`absoluteAmount` 是**加价金额**（不是赠品价值）；加价金额 ≤0 的选项直接排除（那不是加价购）。console 别名沿用同一服务与既有租户/JWT 边界。两阶段都只查询/报价、不会调用 `claim` 或占库存；秒杀验证也只是试算，只有显式调用写平面的 `/{id}/claim` 才会扣库存。

- **角色门控**（`RoleGateFilter`，靠 `activity.role`，仅显式设置该属性时才装配）：`decision` 只放行 `/decision/v1/**` + `/actuator/**`；`console` 屏蔽 `/decision/v1/**`、放行写面 + Step1–18 + SPA；`all`（默认，本地/测试）全开。这是**部署角色边界**而非安全边界（同一份代码），真隔离仍靠 Casdoor 验签 + `@TenantId`。
- **发布代际轮询预热（M1.4）+ 代际快照包（P1-1）**：console 的**任何活动状态变化**（上线 / 下线 / 回待上线）都在同一事务里 bump `(tenant,bizLine)` 代际——只在上线时发的后果是**下线传播不出去**，decision 的快照继续按原配置发钱。`bizLine` 为空时跳过 bump（但状态照常落库）：`activity_generation.biz_line` 是 NOT NULL，插 null 会在同事务里抛约束违例、把刚写下的状态一起回滚，「下线传播不出去」会升级成「下线根本做不到」；而没有 bizLine 的活动本来就进不了任何快照，也就没有代际可传播。decision 后台按 `activity.marketing.generation-poll.interval-ms`（默认 3000ms）轮询，见代际增长即①**构建并发布决策快照**、②预热该 `(tenant,bizLine)` 的全部 ACTIVE artifact。顺序不能反：先建好快照再切指针，请求线程永远读到自洽物料。
- **快照兜底重建**：轮询器每轮扫完代际后，把年龄超过 `activity.marketing.snapshot.max-age-ms`（默认 60s）的快照按数据库真相重建一遍。它守的不是某个已知 bug，而是「信号漏发」这**一整类**故障（bump 因异常没提交、轮询线程被拖死后恢复、构建抛异常导致 lastSeen 没更新……都表现为快照静默过期而决策照常成功）——代际信号依赖每个写入口都记得发信号，那是一条要人持续维护的纪律，本仓库已经在它上面失手过一次（下线不 bump）。有了兜底，后果从**永久**降为**一轮**。它走 `DecisionSnapshotStore.refresh` 而**不是** `publish`：这不是一次发布，不能占用回滚槽位，否则 `rollback` 会退到几十秒前的自己 = 等于没回滚；代际号也保持不变。
- **decision 不碰 DDL**：`activity-decision/application.yml` 的 `ddl-auto` 已从 `update` 改回 **`validate`**（此前只有 compose 的环境变量盖住它，按文档化的 `./mvnw -pl activity-decision spring-boot:run` 本地起会带 DDL 权限跑），并由 `DecisionDdlGuardTest` 钉死防漂回。建表仍由 console 独占。
- **网关**（`deploy/docker-compose.yml`：mysql + console + decision + nginx）：nginx 把 `/api/decision/*`→decision、`/api/console/*`→console、`/ui/*` 及其余→console；host 端口 **8095**（`http://localhost:8095/ui/console`）。`docker compose stop console` 后 `/api/decision/*` 仍可决策，可当场演示拆分价值。

### 取数：快照优先，回落批量查库

热路径取数已从 `ActivityQueryService` 拆到 `DecisionDataLoader`（求值留在原处，编排层只剩几十行）：

- **命中代际快照 → 零数据库查询**。`DecisionSnapshot` 是某 `(tenant,bizLine,generation)` 的不可变物料包：SPU→活动 id 倒排索引、候选模板、已翻译的受控约束、已解析的条件树、合并策略，全部在**发布侧的后台线程**建好。`DecisionSnapshotStore.publish()` 是**唯一**的指针切换点，保留上一代供 `rollback()`（发布出事切回去即可，不必反向再发一次、也不必重启）。
- **快照不存在 → 回落逐请求查库，固定 5 次**（按 SPU 查绑定 / 批量查活动全部未删除版本 / 批量查规则行 / 批量查资格条件行 / 查合并策略；买赠场景第 3 步换成批量查赠品行，仍是 5 次）。原实现是 `3N+2` 次往返（N=候选活动数），索引全建对了也救不了 round-trip 次数。
- 这个回落**自调节、无开关**：`DecisionSnapshotBuilder` 虽然是 `activity-common` 里的 bean（两个 app 都有），但只有 decision 的代际轮询器会调它；console 从不构建快照，store 恒空，legacy 读端点天然走库。两条路语义由 `SnapshotParityTest` 对拍。
- **刻意不在构建期预过滤时间窗**：活动生效窗仍在请求时用 `Instant.now()` 判——否则快照会在时间跨过窗边界时悄悄过期（20:00 开始的活动要等下一次发布才出现）。
- **走库侧的候选身份也按版本收窄**：绑定查询不带 `version`、旧版本的绑定行也不软删，于是「v1 绑 A/B → 编辑成 v2 只绑 A」之后单查 B，走库路径仍会把它当候选（作用域为空）；而 AMOUNT 形态**不看基数**，会把 `redPackageAmount` 原样发出去——走库照发 50 元、走快照根本不是候选。判据与快照侧对齐：**当前线上版本的绑定 ∩ 本次请求 SPU 为空 ⇒ 不是候选**。这不会误伤全场券：走库路径的候选身份本来就只从绑定行推出来，没有任何绑定的活动压根进不了候选集。由 `SnapshotParityTest#narrowedBindingStopsPayingOnBothPaths` 守。
- **候选在两条路的合流点统一按 activityId 定序**（`DecisionDataLoader.ordered()`）：`pickByAmount` / `pickByPriority` 打平时是严格 `>` 比较 = 先到先得，而两条装配路径的天然顺序都不可靠——快照侧倒排索引的值是 `Set.copyOf`，迭代序由 JDK SALT 决定（**同一进程内稳定，每次 JVM 启动翻面**，表现是 decision 重启后一整片决策的赢家可能翻面，比逐请求抖动更难认出来）；走库侧跟着 SQL 返回顺序走，没有 order by 就没有承诺。定序把「金额并列时谁赢」变成确定且可解释的结果。

### 物料溯源（`provenance`）

`DiscountView` / `GiftView` / `AddOnOptions` / `AddOnQuote` 四个响应契约都带 `provenance`（都保留了不带它的兼容构造，缺省取**保守值 `db`**——缺省成 snapshot 会让一条从没碰过快照的路径谎称走了快照）：

| 字段 | 含义 |
| ---- | ---- |
| `source` | `snapshot` = 物料来自代际快照（零数据库查询）；`db` = 逐请求查库 |
| `generation` | 参与本次决策的快照桶里**最落后的那一代**；`source=db` 时为 null |
| `buckets` | 参与本次决策的桶数 |

- **为什么它必须进业务响应而不是只当指标**：`activity.decision.source` 回答的是「整体上多少比例走了快照」，而运营/QA 在验证页上问的是「**我这一次**看到的结论，是照着数据库现状算的，还是照着一份可能落后的快照算的」。后者只能由响应自己回答。
- **`generation` 取下确界而不是最大值**：一次决策会合并该租户**所有业务线**的桶，这个数要回答的是「我刚发布的那次进去了没有」——任何一个桶落后都意味着「还没全进去」，取最大值会把落后的那个桶藏起来。`buckets > 1` 时它是多个桶的下确界而非某一个桶的真值，`buckets` 字段就是这个约定的**诚实声明**。拿它跟 console 的 `GET /activity-marketing/generation?bizLine=` 对照，才知道快照跟没跟上。
- 加价购第二阶段的 `provenance` 来自 **quote 自己那次**重新装载，不是第一阶段的：两阶段之间快照可能已经换代，而「这个价是按哪一代报的」正是这个端点最该自证的事。
- 决策审计日志（**仅红包 `spu-discount` 通道**：买赠生成了 decisionId 但不落盘，加价购两个 record 没有 decisionId 字段）同步落 `source` / `generation`：只有 `hitVersion`（活动版本）而没有代际时，「活动版本对、但快照是旧代」这类事故在日志里查不出来——而它恰恰是客服最难缠的那类工单。

### 决策指标（`DecisionMetrics`）

改造前 `activity-*` 主源码里一处 `MeterRegistry` 都没有，Prometheus 抓的全是 JVM/HTTP/CPU——**决策本身是黑的**，而 `safeRun` 的 fail-safe 回退会**改变实际发给用户的金额**却只打一条 `log.warn`。现在：

| 指标 | 类型 | 标签 | 用途 |
| ---- | ---- | ---- | ---- |
| `activity.decision.duration` | Timer | scene, mode | 决策耗时分位（区分 rule-engine / legacy） |
| `activity.decision.fallback` | Counter | scene, reason | **回退率——头号告警项** |
| `activity.decision.candidates` | Summary | scene | 候选数分布 |
| `activity.decision.source` | Counter | scene, source | 物料来自 `snapshot`（零查询）还是 `db`（逐请求查库） |
| `activity.decision.hit` | Counter | scene, activityId | 按活动的命中量（控制台工作台的数据来源） |
| `activity.decision.amount` | Summary | scene, activityId | **实际发出的减免金额**分布（带 percentile histogram）。补它之前，「满 300 减 50」被配成「满 3 减 50」在监控上全盘绿灯：回退率 0、耗时正常、命中数只是稍高。**当前只在红包出口调用**（买赠发的是实物、加价购无埋点） |
| `activity.decision.clamped` | Counter | — | 减免额超过订单金额被截断的次数。**正常业务恒 0，出现一次即疑似配错**（面额多打个零 / 门槛写反 / 叠加没上限），建议按「>0 即告警」配 |
| `activity.decision.reject` | Counter | scene, reason | **「配了但不发」的唯一信号**。此前淘汰只写在候选的 `rejectReason` 上，而热路径 `explain=false`，那个字段与 trace 两个出口在生产上都不打开 |
| `activity.decision.snapshot.count` | Gauge | — | 本进程持有的快照桶数。0 = 该实例全部决策在走库 |
| `activity.decision.snapshot.age.seconds` | Gauge | — | **最旧**快照的年龄（跨租户；-1 = 无快照）。**下线传播断掉时唯一会动的读数**，建议阈值取轮询间隔的数倍 |
| `activity.rule.compile` | Timer | outcome | KieBase 编译次数与耗时（落在请求线程上的那部分 = P99 尖刺证据） |
| `activity.rule.fire.ceiling` | Counter | scene | fire 触顶（runaway 护栏被触发） |
| `activity.rule.cache.entries` / `.hit.ratio` / … | Gauge | — | KieBase 缓存条目数 / 命中率 / 足迹（Caffeine stats 绑上来） |

- **`fallback` 的 reason 标签是有限集**：`compile-error` / `fire-ceiling` / `eval-error`（`safeRun` 抛异常）、`empty-decision`（跑通了但没结论）、`engine-disabled`、`condition-tree-unavailable`。**绝不把异常全文塞进标签**——编译错误带行号与 DRL 片段，会直接爆掉基数。
- **`reject` 的 reason 也是有限集**，两段来源：资格阶段 `ineligible`（用户不符合门槛，**正常业务**）/ `condition-unavailable`（条件树不可判定，**是故障**——两者分开计数是关键，处理方式完全相反）；算额阶段 `no-ladder-tier` / `missing-lines` / `bad-random-range` / `bad-ratio` / `price-above-base` / `out-of-scope`（基数算不出来时统一归到它）。<br>⚠️ **`reject` 的 `scene` 有第四个取值 `benefit`**，它表示**阶段**（算额）而不是通道，与 `spu-discount` / `gifts` / `addon` 并列出现——按 scene join 两组指标会踩坑。<br>⚠️ `DecisionMetrics` 里那段 javadoc 把原因码写成 `price-above-order`，**代码实际发的是 `price-above-base`**（`BenefitEvaluator` 的 FIXED_PRICE 分支），以代码为准。
- **`activityId` 标签有基数上限** `DecisionMetrics.ACTIVITY_TAG_CAP = 200`，超出并进 `__over_cap__` 哨兵：总量仍准，只是分不出是哪几个。活动数是运营行为不是工程可控量，基数爆炸的代价是整套监控在大促当天一起挂。
- **命中计数**：红包打在决策的**唯一出口**上，不打在"引擎命中"那个分支里——否则回退路径（legacy 也会命中活动）系统性少计，而**少计的指标比没有指标更危险**：它看起来是权威的，而回退恰恰是最该盯的时刻。买赠在 DRL 命中路径与回退路径**各打一次**（两条路互斥）。**加价购通道自己一个埋点都没有**（`AddOnPurchaseService` 里没有 `DecisionMetrics` 引用）：options/quote 的耗时、候选数、命中、金额全不可观测，只有共享的取数层与资格淘汰**间接**产出 `source` 与 `reject`。<br>⚠️ 而且这两组的 `scene` 标签**对不上**：`duration`/`hit`/`reject` 用的是 `spu-discount` / `gifts` / `addon`，`source` 用的却是 `ActivityType` 的枚举名 `RED_PACKAGE` / `BUY_AND_GET` / `ADD_ON_PURCHASE`——按 scene join 两组指标会得到空结果。
- 建议告警：`rate(activity_decision_fallback_total[5m]) / rate(activity_decision_duration_count[5m]) > 0.001`。
- **Gauge 的状态对象必须是 cache 本身**：Micrometer 对状态对象持弱引用，传构造期临时 lambda 会在构造返回后被 GC，指标变成 NaN——而 NaN 在面板上看起来只是"没数据"，是最难察觉的埋点失效（本项目已在 docker 验证中踩到一次）。

```bash
# 直连 decision 服务的决策别名（等价 console 的 /activity-marketing/spu-discount）
curl -X POST localhost:8082/decision/v1/spu-discount -H 'X-Tenant-Id: acme' \
  -H 'Content-Type: application/json' -d '{"spuIdList":[1001],"orderAmount":200}'
# 经网关：POST localhost:8095/api/decision/spu-discount（同 body）
# Compose 默认 auth=true，需再带 Authorization: Bearer <valid-token>

# 加价购两阶段
curl -X POST localhost:8082/decision/v1/addon/options -H 'X-Tenant-Id: acme' \
  -H 'Content-Type: application/json' -d '{"spuIdList":[1001],"orderAmount":200}'
curl -X POST 'localhost:8082/decision/v1/addon/quote?activityId=ACT...&item=保温杯' \
  -H 'X-Tenant-Id: acme' -H 'Content-Type: application/json' -d '{"spuIdList":[1001],"orderAmount":200}'

# 决策指标（单实例视角）
curl localhost:8082/decision/v1/metrics -H 'X-Tenant-Id: acme'
curl localhost:8082/decision/v1/by-activity -H 'X-Tenant-Id: acme'

# 「我的活动到底在不在决策服务眼里」——只读诊断，不发起决策、不占指标标签位
curl 'localhost:8082/decision/v1/snapshot?activityId=ACT...' -H 'X-Tenant-Id: acme'
# 库里当前代际（写平面），拿它跟上面 provenance.generation 对照才知道快照跟没跟上
curl 'localhost:8081/activity-marketing/generation?bizLine=mall' -H 'X-Tenant-Id: acme'
```

## 多租户隔离（P0-4，Track B）

活动数据按租户隔离，**靠机制不靠纪律**：14 张实体表里 **13 张**带 `@TenantId`（Hibernate 判别式多租户），引擎对**每条 SQL 自动追加 `tenant_id = ?` 谓词**、insert 自动落租户——业务代码不手动拼 where、不手动 set 租户，漏不掉（`TenantArchGuardTest` 另外钉死「不手写 tenant 谓词、不用 `nativeQuery`」）。最新一张是发放流水 `activity_grant`（带 `@TenantId`，唯一约束 `uk_grant_tenant_order_activity` = claim 的幂等键）。

> **第 14 张 `activity_generation` 刻意不加 `@TenantId`**，改用显式 `tenant_id` 列。原因是 decision 侧的代际轮询跑在**后台线程**、没有 `TenantContext`，若带 `@TenantId` 则 `findAll()` 会被自动追加 `tenant_id = NO_TENANT` 而**恒空**，什么都扫不到。这是例外不是遗漏，改它之前先读该实体的类注释。

- **租户来源（可插拔接缝，两档）**：`activity.tenant.auth.enabled=false` 时从 HTTP 头 `X-Tenant-Id` 取（仅 dev/header 档）；`=true`（Compose 默认）时 `/activity-marketing/**` 与 `/decision/v1/**` 都需带 Casdoor 验签 JWT，**租户从 `aud`(client_id) 解析**（命脉实测：Casdoor client_credentials 的 `owner`=admin 非组织；`aud` 由 Casdoor 绑定到已认证 client + 独立 secret → 不可伪造，比 owner 更实在），信封 `X-Tenant-Id` 只校验（≠解析出的租户→403）、绝不作来源。两档都写进同一个 `TenantContext`(ThreadLocal)，下游 `@TenantId` 隔离机制一行不动。
  - **aud→tenant 解析**：`AudienceTenantResolver` —— `client-tenant-map` 显式映射优先（生产推荐），`activity-{tenant}-cid` 家族反解兜底；`AudienceTenantValidator` 常开，aud 解析不到租户即拒（401）。
  - **浏览器 Casdoor 档**：`./deploy.sh --provision-auth` 幂等创建 acme/beta public SPA client 与 8095 callback；M2M 调用方仍使用 `scratchpad/casdoor-m2m-verify.sh` 的独立 client_credentials。
- **前端**：dev 档显示 `X-Tenant-Id` 切换条；Casdoor 档使用 Authorization Code + PKCE、state、sessionStorage token 和 Bearer，登录回调为 `/ui/auth/callback`，统一门户只跳目标 `/ui/login` 而不接触 token。
- **fail-closed**：`TenantContextFilter` 是面向用户的闸，挂在**两个平面**上——`/activity-marketing/*` **与 `/decision/v1/*`**。无 `X-Tenant-Id` 且 dev-default 关时直接 **403**；`X-Tenant-Id` 含非法字符（非 `[A-Za-z0-9_-]{1,64}`）**400**。其它 Step（1~18）不挂此过滤器、不受影响。
  - 该过滤器写于 P0-4，当时只有 `/activity-marketing/*`；M1.1 加决策平面时**漏了同步扩 URL 模式**，于是 header 档下 `/decision/v1/*` 完全不解析租户——`X-Tenant-Id` 被静默忽略，全部落到 `TenantIdentifierResolver` 的兜底（dev-default 或 NO_TENANT），即 A 租户查到的是 dev-default 的活动。auth 档不受影响（`JwtTenantFilter` 挂的安全链本来就同时匹配两个平面）。这条由 docker 端到端验证发现——单元测试全跑在 dev-default 下，恰好绕过缺口；回归由 `DecisionTenantHeaderTest` 钉死。
- **dev-only 默认租户**：`activity.tenant.dev-default-enabled=true` 时，不带头的请求回落到单租户 `__dev__`，方便本地/前端手点；**生产必须关**（默认就是关 = 无头即 403）。
- **只做数据行隔离**：字段 schema（`/field-dict` 白名单）当前仍全租户共享（`RuleSchemaRegistry` 仍走 `DEFAULT_TENANT`）；按租户定制字段元数据属后续（P0-1 的 Track B 扩展），不在 P0-4。
- **结构守卫**：`TenantArchGuardTest` 钉死两条不变量——每个 `@Entity` 必带 `@TenantId`（全局表走白名单显式豁免）、仓库不得用 `nativeQuery`（原生 SQL 会绕过租户过滤）。加了 `@TenantId` 后裸 `findAll()` 已被机制自动加谓词、本身安全。

```bash
# 显式带租户（生产形态）：acme 建的活动，globex 看不到
curl -X POST localhost:8081/activity-marketing/create -H 'X-Tenant-Id: acme' -H 'Content-Type: application/json' -d '{...}'
curl localhost:8081/activity-marketing/list -H 'X-Tenant-Id: acme'    # 含刚建的
curl localhost:8081/activity-marketing/list -H 'X-Tenant-Id: globex'  # []
```

## 来源字段映射（收敛，非 1:1）

| 本 demo | 来源（mall-shop / mall-common） | 取舍 |
| ---- | ---- | ---- |
| `ActivityManageEntity` (activity_manage) | `ActivityAdminPlatformManage` | 去掉合伙人/审核/权益系统 id 等 |
| `ActivityRuleEntity` (activity_rule) | `ActivityDynamicRules` | 保留红包/阶梯字段；本仓库新增 `red_package_max_discount`（折扣型封顶），`red_package_amount_unit` 由自由文本收成受控判别位 |
| `ActivitySpuBindingEntity` | `ActivityAdminStoreSpuProduct` | 保留 bindSource/effective/poolId |
| `ActivityConditionEntity` | `ActivityRuleExpression` + 条件树 | 存条件树 JSON + 翻译后 DRL |
| `ActivityStrategyEntity` | `ActivityRuleStrategy` | bizLine 级合并策略 |
| `ActivityGiftEntity` | `BuyAndGetConfig.GiftConfig`（来源存 extraData JSON） | 拆成结构化行；加价购复用该表，`absoluteAmount` 改读作**加价金额** |
| `ProductPool*/PoolRef` | `ActivityProductPool(Item/Rule)/ActivityPoolRef` | 圈选维度简化为 价格/类目/标签 |
| `DemoProductEntity` (demo_product) | 真实商品/车辆表 | **替身表**，仅供圈选演示 |
| `RuleSchemaRegistry` + `SchemaField` | `activity_rule_field_dict` 表 | 内置白名单（原 `RuleField` 枚举），按 (tenant,bizLine) 解析、单租户 stub |

facts（`ActivityCandidate/ActivityRuleContext/ActivityRuleResult/GiftResult`）与来源 `engine/fact/*` 对齐。

**决策入参（`SpuDiscountRequest`）新增两个字段，都是纯增量**（老调用方走兼容构造，行为一个字节不变）：

- `storeId`——条件白名单里一直有「店铺」，但决策入参没有这个键，于是**配了 `storeId` 条件的活动永远不命中**，且因为 fail-closed 是「静默不发」而不是报错。写侧其实完整建模了店铺（`DemoProductEntity` / `ActivitySpuBindingEntity` / 编辑器的「店铺ID」列），只有入参漏了，故补入参而不是删白名单。语义是「这一单来自哪个门店」，不是「活动绑在哪个店」。
- `lines: [{spuId, unitPrice, quantity}]`——「第 N 件折」必需的逐行单价。整单金额 ÷ 件数是均价，拿均价当第二件的价去打折，在混着贵重与便宜商品的车里会**静默算错钱**。不传 `lines` 时该形态返回 null（不适用），而不是拿均价瞎算。按行不按件：算「第 N 件」只需 (单价, 件数)。

请求维度 → 属性袋的映射收敛成**一张表** `DecisionEligibilityService.requestAttributes()`；`ActivityQueryService` 的兼容入口只委托给它。红包、买赠、加价购都复用这份上下文与候选淘汰，并由 `DecisionContextFieldsTest` 钉死不变量「白名单里的每个 key 都必须在这里有来源」——此前是手写 `putAttr` 与 `RuleSchemaRegistry` 白名单两处独立维护，两个方向都漏过。当前白名单 6 个字段：`orderAmount` / `quantity` / `userDistrictId` / `userTags` / `spuId` / `storeId`；`userId`、`orderLines` 与 `randomSeedSpu` 三个入袋但**不进白名单**（运营写不出、也不该能写「第 3 行单价 > 100」或「随机种子 = X」这种条件）。`randomSeedSpu` 是随机红包种子专用的那个标量（= `spuIdList` 第一件），见上「随机红包是确定性随机」。

## 本次未迁移（来源存在）

砍价/拼团/门店拼团/抽奖等其它玩法、CPS 订单分润、红包合伙人签名校验、真实商品/权益/钉钉集成、活动版本历史浏览/回滚。`COUPONS/CPS/RIGHT_COUPON` 三类活动类型保留枚举位但未实现（后端 400、前端禁用）。

## 已知落差（配置得下 ≠ 会执行）

这些不是"待办清单"，是**当前代码的真实边界**，写在这里是因为沉默最危险——运营以为配了就生效：

- **验证/决策报价不占库存**：`inventory` / `userInventory` 虽然进了 `ActivityCandidate`，discount、gifts、加价购 options/quote 与秒杀试算都不扣减，不构成超发防护。写平面的 `/{id}/claim` 仍是 `inventory` 唯一会被真正扣减的地方——但它现在**幂等**（`activity_grant` 流水 + `tenant+order_id+activity_id` 唯一约束），且 `userInventory`（每人限领）**已有执行路径**（按流水计数；配了限领却不传 `userId` 一律拒绝）。冲正走 `POST /{id}/release`（幂等，把库存与限领额度一起还回去）。create 的 `warnings[]` 会把 `inventory` 这条明说。
- **旧红包 DRL 不是生产回退**：它自身不支持一口价 / 第 N 件折 / 随机红包，所以六形态已固定由 `BenefitEvaluator` 求值。两个 `java-*` 旧属性即使配为 false 也不会把生产切回 DRL；灰度时只能使用明确支持当前六形态与合并策略的路径。
- ~~**`STACK` 下多张折扣券会累加，可能超过订单金额**~~ **已修**：出口统一 `hitAmount = min(hitAmount, orderAmount)`，被截断时 `clamped=true` 并计 `activity.decision.clamped`。逐活动明细（`items[]`）仍如实报<b>封顶前</b>各自的金额——封顶是订单级的，不改写各活动自己算出的减免。订单金额缺省时不封顶（红包面额本就与订单金额无关），这是有意保留的边界。
- **同时配了阶梯与「固定金额（元）」的历史脏数据**：`computeAmounts` 的覆盖语义（阶梯只设 `computedAmount` **不设** `amountComputed`，随后被固定金额 `setComputedAmount(redPackageAmount)` **覆盖**）原样保留。看着像 bug，但它是当前线上语义、金标用例依赖它，改它要单独立项。<br>⚠️ 打架的是**阶梯 vs 固定金额**，不是「阶梯 vs 折扣」——折扣型（单位=折）在写平面就被禁止同时配阶梯，根本走不到这里。<br>⚠️ 也**没有「两条路对拍」可做了**：算额的第二条路（DRL）已删，现在只剩 `BenefitEvaluator` 一条，守它的是 `DecisionGoldenSetTest` 的 52 例金标。
- **权益作用域的两处已知落差**：① 作用域覆盖整单时用 `orderAmount`、是真子集时用 Σ作用域订单行，两者口径可能不同（运费/补贴算不算进 `orderAmount`，入参契约没规定）；② 调用方若只传「想查的那件」的 `spuIdList` 却给整单 `orderAmount`，覆盖判定会返回 true，商品级活动重新退化成按整单算钱且**无任何报错**。收敛方向是在入参契约里要求订单行或写死 `orderAmount` 口径。
- **`spuId` 资格条件已放宽**：字段类型由 NUMBER 改 ARRAY，属性袋装整个 `spuIdList`（此前是 `get(0)`＝「购物车第一件」，同样两件商品换个加购顺序结论就相反）。存量 `eq/in/notIn` 由求值器与翻译器映射成集合语义，**无需数据迁移**；代价是多 SPU 请求下「第一件是 X」放宽成「购物车含 X」，更容易命中——但同批改动里作用域把「命中后发多少」收窄到了活动自己的商品上，合起来不是放大敞口。
- **决策快照的回滚只有原语、没有入口**：`DecisionSnapshotStore.rollback()` 能把 `(tenant,bizLine)` 的指针切回上一代（只留一代），但没有 REST 端点触发；活动本身的版本历史浏览/回滚仍未迁移（见上节）。
- **验证流量会污染业务指标**：控制台「优惠验证」页默认打决策平面，而 `/decision/v1/*` 与生产流量走**同一份** `ActivityQueryService`——每点一次验证就真的记一次 `activity.decision.hit`（红包通道另记一次 `activity.decision.amount`）。**这两个指标只能读作「决策发生了多少次 / 报了多少钱」，不能读作「花了多少预算」**：控制台若把 `/by-activity` 的 `amounts` 渲染成预算消耗，那是拿自己造的流量记账（真正的账在 `activity_grant` 里，那张表只有 `claim` 才写）。e2e 脚本同理。<br>唯一不脏指标的诊断入口是 `GET /decision/v1/snapshot`（只读、不发起决策）。
- **`activityId` 标签位是 200 个且不可回收**：`DecisionMetrics.cappedTag` 用一个只增不减的 `taggedActivities` 集合做先到先得，进程生命周期内**没有任何淘汰**。后果是 e2e / 验证页反复跑造出的临时活动会**永久**占住标签位，等真实活动上线时只能落进 `__over_cap__`——总量仍准，但「按活动看命中量」这块面板对新活动一片空白。重启 decision 才会清零。
- **双打对拍只能照出取数层的分歧**：验证页的「两条平面都打」比的是候选集、版本、绑定、代际这类**取数**差异；两条路共用同一份 `BenefitEvaluator`，**求值口径错了两边会一起错**——绿 ≠ 算对了。对拍还刻意排除五类正常差异（`decisionId`、`traces`（两侧 explain 档位不同）、`mode`、`items` 顺序、`strategy`（合法瞬态：策略行在 create 时 upsert，而代际只在状态流转时推进）），并在**两侧 source 都是 snapshot 时判红**——那说明有人给 console 也加了预热，对拍已经退化成「拿快照跟它自己比」，而永久绿比飘红更彻底地骗人。
