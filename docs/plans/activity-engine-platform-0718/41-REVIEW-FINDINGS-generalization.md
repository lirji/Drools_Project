# 独立评审汇总 · 通用化 + 多租户 + auth 接入

> 三视角对抗评审(正确性/可行性、安全/多租户隔离、性能/内存/运维)的合并结论 + 逐条处置。评审对象:`11-generalization-architecture.md`、`01-platform-generalization-product.md`、`12-auth-platform-integration.md`、后端 FINAL_PLAN §17、路线图 D9-D13。**只挑刺,不改设计;处置栏记录吸收/反驳。**

状态:⏳ 性能视角已归档;正确性、安全视角待并入。全部到齐后统一把 P0/P1 吸收进设计文档。

---

## A. 性能 / 内存 / 运维视角(已完成)

一句话:方向(单例论证、行级隔离、无状态、A→B 分步)大体成立,但自标的"命门"从头到尾是**"点名了要建模、但没建模"的占位符**,且验证被排到最后。

| ID | 严重度 | 问题 | 证据 | 处置(待定) |
|---|---|---|---|---|
| PERF-1 | **P0** | 内存模型是占位符无 sizing 数学;噪声邻居没真封顶;用"规则数"当 KieBase 权重会系统性误估(ladder 每档一个 alpha 节点才是主导,Caffeine weight 会错) | `11:279-281,462-469,485`、FINAL_PLAN §8.2:207、§17.3 全是 `<压测确定>` | 落地前产出堆预算表:单 snapshot 项 + 单 KieBase 实测保留堆(JOL/heap dump 抽样)+ `heap=f(tenants,activities,tiers)` 公式;`per-tenant-max-weight`=公平份额;定义大租户借用空闲配额是否可抢占 |
| PERF-2 | **P0** | 命门假设"单例 fact→Map 无索引代价"被排到 M4 验证,风险排序倒置——先把平台建在假设上、最后才验 | `11:196,483`、路线图 M4、§6.1:125 JMH 待验证 | **把 typed-vs-Map 微基准提到 M0 前 spike**,作选型准入门槛而非验收项;证伪即切 declared-type 逃生舱(此时翻译器输出不变,便宜) |
| PERF-3 | P1 | Map fact 丢的不止单例索引,是**多规则 alpha 节点共享**:多档 ladder/多资格约束下 `numberAttr("x")` 引擎认不出是同一提取器,退化成逐档 predicate + 装箱 | `ActivityDrlBuilder.java:161-176` | 与正确性视角合并核验;基准必须覆盖"多档 ladder+多资格+大候选"最坏形状,非只测单例存在性 |
| PERF-4 | P1 | 护栏阈值在 Map fact 下必须重定且不能是全局常量:`fireAllRules(max)` 数 fire 不数 CPU;多租户规则数差异大,全局 maxFires 会误杀大租户(撞限→静默 NO_PROMOTION=收入损失) | §8.2:203 单个全局占位符、§5.2:85 全局 fire budget | 护栏预算由 artifact/manifest 编译期规则数派生(per-tenant 上界),非全局常量 |
| PERF-5 | P1 | "热路径零网络"不成立:`NimbusJwtDecoder` 是 lazy fetch + kid-miss 触发拉取;**JWKS 轮转/冷启动**恰在 token 滚动时打向 Casdoor,Casdoor 慢/宕则带新 kid 的决策阻塞在拉取超时——与 fail-open 精神矛盾 | auth `性能与容量规划.md:53-55` 只保证存量已缓存 token | 决策实例**启动预热 JWKS** + 短的有界 fetch 超时 + 缓存 **last-good JWKS**(轮转/抖动先用旧集验签)+ 把"JWKS 不可达如何降级"写进 fail-open |
| PERF-6 | P1 | 每租户 QPS 配额在无状态水平扩展下无落地 substrate:本地计数=全局×N(限不住);共享计数=每请求往返+新 SPOF,且未进依赖清单/延迟预算 | `11:282,452` 只有类名+一句话 | 明确分布式限流基座(承认"每实例近似+网关层限流" 或 上 Redis token bucket 并计入延迟预算+定义降级) |
| PERF-7 | P1 | schema 演进:①"受影响活动重建"是 TODO(重建量/新旧混跑/中途失败回滚全无);②"优雅 null"静默改金额(违反不静默改金额纪律),且与"null 强转 ClassCastException"自相矛盾;③**信封用 live schema 校验、artifact 冻结在 pinned schema**,旧有新删的字段在信封期被 400 拒→冻结 artifact 跑不到,戳破"自包含扛得住 schema 变更" | §7.1:479、§17.4:389、§5.2、§8.1:192 | 与正确性视角合并;重建走原子产出新 manifest+失败隔离整体回滚;字段缺失显式分类(拒绝激活/显式降级带 reason),禁止静默淘汰;**信封按 artifact 的 pinned schema 校验**,非 live |
| PERF-8 | P1 | Track B 的 `tenant_id`/PK 改造被当加列,实为**热表改主键**(pointer PK `bizLine`→`(tenant_id,bizLine)`=整表重建锁表,发生在 M2 后有流量时,无 online-DDL 方案);**接缝清单漏了"缓存 key 从 Day1 带 tenant"**(最隐蔽越权);Track B 无回滚点 | §17.3:384、§7:177,186、§17.5:392 | 接缝清单补"所有缓存/registry/查询 key Day1 带 tenant(单租户时常量)";pointer PK 若必改预留 gh-ost/pt-osc + 定义 Track B 回滚锚点 |
| PERF-9 | P1 | KieBase 编译放大:多租户放大编译数量,有界缓存淘汰→**冷编译回热路径**(100ms~秒级 CPU);只隔离了堆没隔离**编译 CPU**;预热风暴+schema 批量重建的编译 burst 与决策线程抢核;snapshot 与 KieBase 双缓存独立淘汰→单请求可能双冷 miss=P99 无上界;淘汰 churn 下 classloader/Metaspace 回收未验证 | FINAL_PLAN §14、`11:484`、`ActivityRuleRuntimeService.java:88-98` | 独立限速编译线程池承接预热+重建,与决策隔离;定义"双冷 miss 不阻塞热路径"(异步重建期间 serve last-known 或显式降级);对淘汰 churn 做 heap-dump 验 classloader 回收 |
| PERF-10 | P1(实质) | 现 `ActivityRuleRuntimeService.java:41` 是**按 DRL 全文 key 的无界 ConcurrentHashMap 永不淘汰**;通用化后 DRL 随 tenant/schema/活动/档位爆炸→若有界缓存替换未与通用化**同批上线**,Track A 就是更快的 OOM | §6.1:133 | 有界缓存是通用化的**同步前置**,不是后续优化 |
| PERF-11 | P2 | trace 累积在执行层与 explain 无关:每条 RHS `result.trace(...)` 每 fire 追加字符串,大租户大规则集增 GC;§8.1 只在响应层关 trace | `ActivityDrlBuilder.java:58,67,106,139,151,173,191` | trace 按 explain 在**构建期**关,非响应期过滤 |
| PERF-12 | P2 | 折扣 MAX/PRIORITY 的 `not ActivityCandidate(...computedAmount>$c...)`=O(N²) 自连接;§5.3 "低基数兜底:载入全部 active 活动"对大租户是陷阱(N=该租户全部活动) | `ActivityDrlBuilder.java:102,116-118`、§5.3:393 | "全量载入"兜底设活动数上限,超限强制要求 selector |

**性能视角 top 3 先压测/建模**:①多租户堆容量模型(PERF-1)②typed-vs-Map 微基准最坏形状(PERF-2/3/4)③两个隐藏热路径依赖 JWKS 轮转 + 分布式限流(PERF-5/6)。

---

## B. 正确性 / 可行性视角(已完成)

一句话:骨架对、也难得诚实(承重机器复用、单例免索引、同类合并不破——经核验都站得住),但命门 (a) 最依赖的 `in`/`contains` 方法访问器约束被写成"已核实",官方证据其实没覆盖;外加否定运算符缺字段 fail-OPEN 静默超发。

| ID | 严重度 | 问题 | 证据 | 处置 |
|---|---|---|---|---|
| COR-1 | **P0** | 命门"已核实"是虚的:官方文档背书的是 `[]` 映射访问 `map["x"].valid`,**不是方法调用式** `numberAttr("x")`;而 `in`/`contains` 方法左值**无任何官方样例**,且**两条业务线的选择器+枚举字段全靠 in/contains**(是主干非逃生舱)。`in`/`contains` 是自定义 evaluator,方法左值历来脆弱 | `11:16,166-196`、FINAL_PLAN §17:372、context7 Drools 文档、pom `drools-mvel` 在 | **动手前一次性 spike**(20 行 DRL + KieHelper.build 跑真数据):`numberAttr>=`/`textAttr ==,in`/`listAttr contains,containsAny,not contains`/`between`/`not RuleContext(...)` 全过。**spike 绿前不得落地**;集合运算符方法左值不成立就退 `attrs["x"]` 映射式(Object 强转要再验)或 declared-type (c) |
| COR-2 | P1 | §17.4"取不到键 null 优雅失败"对**否定运算符是反的**→静默超发:正向缺字段→null→reject(fail-closed✅);否定(ne/notIn/notContains)缺字段→`null not in(黑名单)`判 true→候选**不淘汰**→放行=fail-OPEN,违背 D1 防超发;STRING→NUMBER 改型→ClassCastException 全量降级。§17.4 前后半句自相矛盾 | FINAL_PLAN §17.4:389、`RuleConditionTranslator.java:82,84`、`ActivityDrlBuilder.java:55` | 归一化区分"键不存在"vs"值 null";翻译器对**所有否定运算符**加存在性护栏 `(field!=null && field not in(...))`;删字段/改型**硬失效**引用它的 artifact;改掉"优雅失败"措辞 |
| COR-3 | P1 | benefit 泛化:同类折扣合并**不被破坏**(合并跑在 candidate 上与 result 形状无关,给肯定);但**跨异构权益合并**(现金+赠品+折扣同场)`11` 说已解、`01 §9.2#4` 说待澄清,两文档矛盾,且各场景独立 KieBase 无编排层。MVP 恰好绕开(两线无单场景异构) | `11:300-320`、`01:413`、`ActivityRuleRuntimeService.java:50-67` | 显式标"MVP 不支持跨类型合并,单场景单 benefit-type",或补 Java 编排层;别在 `11` 当已解 |
| COR-4 | P1 | 鉴权模型自相矛盾:`11 §5.1/§6.3` 还用 X-Api-Key + TenantContext 过滤器 + `activity_api_client`,**已被 §17.7 推翻但没同步**→照 `11` 落会建错误鉴权基座 | `11:348,448` vs FINAL_PLAN §17.7:402,412 | `11` 鉴权部分作废、统一指向 §17.7;信封 `tenantId` 只作校验(须=token.owner 否则 403),绝不作租户来源 |
| COR-5 | P1 | 落地节奏矛盾:§17.1 要 M0 就上 Map fact("晚改 10x"),§17.5 又要"先单租户跑通、回归对齐旧行为"——把**未 spike 的 Map fact** 塞进地基,还丢掉 typed 回归基线 | `01:368,423`、§17.1:376 vs §17.5:392 | "M0 是否上 Map fact"用 COR-1 spike 结果做闸;不绿则 M0 留 typed + 只立 tenant_id/release_key/翻译器字段来源接口三个便宜接缝 |
| COR-6~9 | P2 | ladder 泛化 `LadderRangeParser` 真不改(肯定),但"一处改动"低估(record/签名/caller/校验几处);翻译器 fail-closed 能保但须 emit **规范 key** 非用户输入;DATE 过早进类型矩阵而 (a) 恰做不好 DATE(过度设计,MVP 只加 ENUM);逃生舱 (a)→(c)"翻译器输出不变"错(方法调用 vs 属性访问 emit 必改) | `ActivityDrlBuilder.java:165`、`RuleConditionTranslator.java:107`、`11:72,207-211` | 逐条小修 |

## C. 安全 / 多租户隔离视角(已完成)

一句话:字段/值层注入防线(白名单+转义+fail-closed+硬上限)扎实、cache-key 加 tenant 枚举很全;但"防越权/防串租户"目前几乎全靠**人肉纪律没有机制**,且有 3 个 P0 隔离/越权硬伤。

| ID | 严重度 | 问题 | 证据 | 处置 |
|---|---|---|---|---|
| SEC-1 | **P0** | M2M 决策身份:owner **既未证实又可伪造**。①手册只说 client_credentials 的 `sub` 代表机器,对 `owner` 只字未提,PHASE0 里"claim 能否表达目标 tenant"是未决目标;②**Shared Application 派生 client 共用同一 secret**,派生 client_id 可猜(`<base>-org-<victim>`)→任一持共享 secret 的租户可换出 owner=别租户的 token→在无判权的决策热路径**跨租户冒充**。"命脉待实测"只测了"owner 在不在",没测"共享 secret 下可否顶替" | `统一登录手册:289-291`、`PHASE0-RESEARCH:44`、`deploy/casdoor-tenant-provision.sh:54,78` | **M2M 强制每租户独立 Casdoor Application(独立 client_id+唯一 secret),禁用共享 secret 派生**;冒烟测"租户 A 的 secret 换不出 owner=B 的 token" |
| SEC-2 | **P0** | 行级隔离**只有策略没有机制**,且 100% greenfield:现有 ~40 仓库方法无租户维度、`ActivityPoolMatchService:60` 直接 `findAll()`、grep tenantId/TenantContext/SecurityConfig 零命中;行级主线反而没 ORM 层强制(只给了 schema-per-tenant 逃生舱) | `ActivityManageRepository`、`ActivityPoolMatchService.java:60,72-96` | enforcement 下沉 ORM:Hibernate 6 `@TenantId`(discriminator,自动追加租户谓词)或 DB RLS + `@Filter` 常开;仓库基类/切面 TenantContext 未设即 fail-closed;裸 `findAll()`/跨表查询列 **CI 阻断**;`benefit_type` 全局行(tenant_id 空)作显式例外单独测 |
| SEC-3 | **P0** | aud 校验**误读参考 SecurityConfig**:真实是可选+精确单值+**默认空 client-id→根本不校验 aud**;仓库内**无任何前缀/家族通配**(`<base>-org-*` 只在仓库外 edge)。照抄→要么同 issuer 任何 token 都能打决策 API,要么钉死一个 client_id 多租户失效 | `auth SecurityConfig.java:58-72`、`application.yml:19` | **自写审计过的 audience 校验器**:常开、绝不默认空、前缀家族匹配 + **owner↔aud 绑定**(token.owner 须与 aud 里的 org 对应)、家族外拒绝 |
| SEC-4 | P1 | ReBAC 表达不了 D2 职责分离:recsys.zed 只有 platform/advertiser、无 activity/reviewer/publisher/自审阻断;"同一人不得审自己活动"是动态主体约束,ReBAC 要它必须写活动级 `edited_by` 元组→**恰违反"高频子资源不写元组"** | `recsys.zed:19-35`、`12:26` | 四眼/自审阻断在**应用层**强制(持久化提交人,`actor==submitter` 拒 approve/publish);别把"角色分离"当"防自审自发"宣称 |
| SEC-5 | P1 | "反查归属再判"是 IDOR 越权面,吊在 SEC-2 那条没机制化的租户过滤上;"只信 owner"防线 auth-platform **零继承 100% 自建**(真 enforcer 在仓库外 edge);决策契约读 body tenantId 本身即风险 | `FINAL_PLAN:422`、`12 §5.2` | 反查从资源**权威行**取作用域且带租户过滤;租户**只**从验签 token.owner 派生,body/header 的 tenantId 一律忽略或存在即拒 |
| SEC-6 | P1 | 合规/租户生命周期遗漏:无租户注销/级联清理路径(~13 表+不可变 artifact+缓存+SpiceDB `<tenant>_*` 元组+Casdoor org);PII(driverId)留存/访问控制未说明;平台运营跨租户读须合同许可+审计 | 安全视角 §3.1 自表"合规删除:难" | GA 前补租户注销 runbook + 级联清理 + 数据留存/擦除策略 |
| SEC-7 | P2 | `activityId` **未转义直接拼进 DRL 体/规则名/trace**(今天系统生成侥幸安全,"自助+租户自选 code"下即活注入);共享 JVM 的 app 级单例/静态须逐个审租户键化;Metaspace 未入内存模型(declared-type/codegen 灌类) | `ActivityDrlBuilder.java:54,58,167,171` | 所有拼进 DRL 的标识符过**同一**审计正则发射器;盘点 app 级状态租户键化;容量模型补 Metaspace |

**肯定项(避免只挑刺失真)**:cache-key 加租户维度枚举无漏项;字段/值层注入姿势稳(scalar 转义+fromCode fail-closed+MAX_DEPTH/NODES/LIST_LEN+field_key 正则+模板仅平台注册);"热路径只验签不碰 SpiceDB"分层经核验正确(RemoteAuthzEngine 每次网络调用、SDK 零缓存)。

---

## 跨视角去重 · 收敛点(独立命中=强信号)

| 收敛主题 | 命中的 finding | 合并结论 |
|---|---|---|
| **Map fact 命门未证** | COR-1(in/contains 未证)+ PERF-2(验证排最后)+ PERF-3(多规则 alpha 共享丢失)+ COR-5(M0 排序) | **最高优先**:M0 前做 spike 闸,覆盖 in/contains 方法左值 + 多档 ladder 最坏形状性能;不绿则 M0 留 typed / 切 declared-type。护栏阈值在 Map 形状下重定(PERF-4) |
| **schema 演进正确性** | COR-2(否定 fail-OPEN 静默超发)+ PERF-7(live-vs-pinned + 静默 null) | 信封按 artifact **pinned schema** 校验;否定运算符加存在性护栏;删/改字段硬失效 artifact;禁止静默淘汰,缺字段显式分类(拒绝/降级带 reason) |
| **租户隔离靠纪律非机制** | SEC-2(无 ORM 强制)+ PERF-8(缓存 key Day1 带 tenant)+ SEC-7(app 级单例键化) | ORM 层 `@TenantId`/RLS 强制 + 仓库基类 fail-closed + CI 阻断裸查询;**所有缓存/registry/单例 Day1 带 tenant**(单租户时常量) |
| **auth 接入有真洞** | SEC-1(owner 可伪造)+ SEC-3(aud 误读)+ SEC-5(owner 防线自建)+ COR-4(11 鉴权作废) | M2M 每租户独立 app 唯一 secret;自写 owner↔aud 绑定校验器;租户只从 token.owner;`11` 鉴权指向 §17.7 |
| **多租户内存/编译** | PERF-1(无 sizing 数学)+ PERF-9(编译 CPU 未隔离)+ SEC-7(Metaspace) | GA 前产出堆预算表(含 Metaspace)+ 公平份额淘汰 + 独立限速编译线程池 + 双冷 miss 不阻塞热路径 |

## 三视角一致的放行判断

**骨架对、方向对、承重论证(机器复用/单例免索引/同类合并不破)经核验站得住,且三份评审都称赞其诚实。但当前是「策略充分、机制缺位 + 命门写成已核实实则未证」。落地前必须先过这几道闸:**
1. **【P0 闸】Map fact spike** — **正确性部分 ✅ 已过(2026-07-18),性能部分 ⏳ 待基准**。
   - ✅ **正确性/可行性(COR-1)已实证**:14/14 编译+执行通过(Drools 8.44.2,KieHelper 路径,真数据)。方法左值 `numberAttr(...)>=`、`textAttr(...) == / in (...)`、`listAttr(...) contains / not contains`、containsAny 展开、`between`、`not SpikeCtx(...)` 资格淘汰包裹、出行 `completedTrips` 阶梯**全绿**——**不用退 declared-type / 不用切 `attrs[]` 映射式**。绑定变量退路也验证可用(用不上)。spike 见 `scratchpad/spike/`(throwaway,未进主代码)。
   - ✅ **COR-2 顺带钉死**:`notIn` 缺字段**实测 FIRE=fail-open 属实**;存在性护栏 `field != null && field not in(...)` **实测不 FIRE=修复有效**。
   - ✅ **性能部分(PERF-3)已基准(2026-07-19),吓人版本证伪**:最坏形状(1 ctx + 10 候选 + M 档)下 typed 与 map **都是线性 O(M)**——typed 并未拿到"免费 alpha 索引共享"(BigDecimal 区间两边都没跨规则索引);map 只比 typed **多付 ~0.12µs/档,200 档时 94 vs 82µs = 1.15x**,是温和常数开销**非悬崖**;真实 ladder 几档~十几档下可忽略(M=1 时 map 反而更快)。**真正成本驱动是规则/档位总数(M)非 fact 表示法** → 印证 PERF-4"护栏阈值按 per-artifact 规则数派生"。**故 Map 选型 (a) 性能上也成立,不因性能退 declared-type。**
   - ⚠ 基准局限(留给正式实现):手搓微基准非 JMH、未测分配量、只测 number `>=/<`、N/M 固定;正式落地建议补 JMH + 覆盖 in/contains 大规模 + 分配量,并据此定 per-artifact maxFires/timeout。
2. **【P0】M2M 身份不可伪造**(SEC-1):每租户独立 Casdoor app + 唯一 secret,测"A 换不出 B 的 owner"。
3. **【P0】隔离机制化**(SEC-2/PERF-8):ORM 强制 + 缓存 Day1 带 tenant + CI 阻断。
4. **【P0】aud 校验器自写**(SEC-3):owner↔aud 绑定,不抄默认空。
5. **【P0】内存容量模型**(PERF-1):sizing 数学 + Metaspace + 公平份额,GA 前。
6. **【P1】否定运算符 fail-OPEN + 信封 pinned-schema**(COR-2/PERF-7);**ReBAC 做不了 D2→四眼在应用层**(SEC-4);跨异构 benefit 合并 MVP 明确不支持(COR-3);JWKS 轮转热路径兜底(PERF-5)、分布式限流基座(PERF-6)、租户注销级联(SEC-6)。

修掉这些之前先 spike、别开建多租户落地;修掉之后,是一份可落地的好蓝图。
