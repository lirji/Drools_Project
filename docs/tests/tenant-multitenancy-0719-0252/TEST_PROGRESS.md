# TEST_PROGRESS · tenant-multitenancy

## 结论
Codex 多视角设计出 9 个交互逻辑疑点（P0×3/P1×4/P2×2）。Claude 跨模型逐条核验后：
**7 个真 bug → 修生产 + 加测试锁定正确行为**（含 ISSUE-04）；1 个延后（ISSUE-07 需独立幂等表，over-scope）；1 个评审轮已修（ISSUE-08）。
`./mvnw test` = **70 绿**（55 → 70，+15）。

## 处置（用户「都做」→ 采用 B：修生产再补测）

| ISSUE | 级别 | 判定 | 处置 |
|---|---|---|---|
| 01 dev-default 绕过语法/保留值校验、resolver 可返回 null | P0 | 真 | 修：`TenantIds` 统一 grammar+保留值；filter 统一校验最终租户；resolver 非法 dev-default→哨兵(非 null)。测 `TenantIdentifierResolverTest`(5) + `TenantContextFilterTest.invalidDevDefault`。 |
| 02 header filter 早退分支不清 ThreadLocal | P0 | 真 | 修：filter 入口 `TenantContext.clear()`。测 `TenantContextFilterTest.staleContextClearedOnReject`。 |
| 03 aud map value 未校验、`__single__` 未保留 | P0 | 真 | 修：`AudienceTenantResolver` 对 map/pattern 结果统一 `TenantIds.isValidExternal`（含保留值 `__single__`）。测 `AudienceTenantResolutionTest`(reservedMapValue/invalidGrammar/singleReservedViaPattern)。 |
| 05 空白 requestId 写入唯一列致误撞 | P1 | 真 | 修：`blankToNull` 归一。测 `ActivityIdempotencyTest.blankRequestId_twoCreatesOk`。 |
| 06 所有 DataIntegrityViolation 误判成并发重复 | P1 | 真 | 修：加名称长度校验(400) + catch 只认 `uk_am_tenant_request` 冲突转 409、其余透传。测 `ActivityIdempotencyTest.overlongName_badRequestNotDuplicate`。 |
| 09 audienceTemplates 含 null 元素 NPE | P2 | 真 | 修：构造期过滤 null/blank 模板。测 `AudienceTenantResolutionTest.nullTemplateElement_noNpe`。 |
| 04 bizLine 级 schema 与 field-dict 维度不一致 | P1 | 真→**已修** | field-dict 加可选 `?bizLine=`，与 create 同维度解析 `resolve(tenant,bizLine)`。测 `RuleSchemaRegistryTest.perTenantBizLineOverride`。（preview 仍租户级=保存前粗校验，可接受。） |
| 07 编辑后原始 requestId 无法再返回首次结果 | P1 | 真但**延后** | 需独立幂等记录表；当前语义=编辑后重放原 requestId 得 409（at-most-once create），可接受，文档标注。 |
| 08 warmup 命名 vs warn-only 语义张力 | P2 | 评审轮已处理 | JWKS「预热」措辞已改「连通/fail-fast 自检」并诚实说明不预热 decoder。 |

## 新增/改动测试
- 新：`TenantIdentifierResolverTest`(5)、`ActivityIdempotencyTest`(3)。
- 扩：`AudienceTenantResolutionTest`(+4：reserved map/invalid grammar/single via pattern/null template)、`TenantContextFilterTest`(+2：stale clear/invalid dev-default)。
- （评审轮已加：`TenantIsolationTest` +跨租户写/bulk、`JwtTenantFilterTest` fail-closed。）

## 跑测
- `./mvnw test` = 69 绿，BUILD SUCCESS（一轮通过，无迭代）。

## 遗留待办
- ISSUE-04：field-dict/preview 增加可选 bizLine 入参 + schema 注册 API（随 schema 编辑系统）。
- ISSUE-07：独立幂等记录表（若产品要求编辑后仍可幂等重放）。
- Codex 独立验收结论见 `scratchpad/ct-accept-verdict.txt`（跑完回填要点）。
