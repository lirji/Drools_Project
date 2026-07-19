# 交互逻辑疑似问题

以下项目与测试缺口分开管理。草案中的对应测试均以 `@Disabled("TODO(issue-…)")` 标记，避免把当前疑似错误行为固化成正确契约。

## ISSUE-01（P0）dev default 绕过租户格式/保留值校验，并可破坏 resolver“永不 null”承诺

- 预期行为：不论租户来自 header 还是 dev default，都应满足同一套 1–64 位白名单且不得为保留哨兵；`resolveCurrentTenantIdentifier()` 在任何配置下都不得返回 null/blank。
- 现状：`TenantContextFilter.doFilterInternal` 在 header 缺失时直接把 `props.getDevDefault()` 赋给 tenant，后面的格式与 `NO_TENANT` 判断位于 `else if`，不会执行。`TenantIdentifierResolver` 在开关开启时也直接返回 `props.getDevDefault()`。
- 复现路径：`devDefaultEnabled=true`，分别设置 `devDefault=null`、`"bad tenant"`、`"__no_tenant__"`；发送无 `X-Tenant-Id` 的 `GET /activity-marketing/list`。null/非法值可进入下游；resolver 对 null 配置可返回 null。
- 建议处置：配置绑定阶段加 validation；抽取一处租户 ID validator，header、dev default、map value 共用；resolver 对无效 default 回落 `NO_TENANT` 并告警。修复前只保留 TODO 预期测试。

## ISSUE-02（P0）header 过滤器的早退分支不清理已有 ThreadLocal

- 预期行为：活动请求的所有出口（成功、下游异常、403、400）都清理 `TenantContext`，即使线程因其他入口已有残留也应防御式清除。
- 现状：`TenantContextFilter` 只有执行到下游 chain 的路径进入 `try/finally`；缺 header、非法 header、保留值三个早退分支没有 `TenantContext.clear()`。相对地，`JwtTenantFilter` 的早退分支会主动 clear。
- 复现路径：同一线程先执行 `TenantContext.set("stale")`，再调用 header filter，输入缺 header 且 dev default 关闭，或 header=`__no_tenant__`；响应为 403/400，但 `TenantContext.get()` 仍为 `stale`。
- 建议处置：filter 入口先 clear，或用覆盖整个方法的 `try/finally`；增加同一单线程 executor 连续两次请求的回归测试。

## ISSUE-03（P0）auth 显式 map 的 tenant value 未执行租户 ID 校验，且内部 `__single__` 未保留

- 预期行为：aud map 的 value 应与 header/pattern 反解使用同一 grammar，并拒绝 `NO_TENANT` 及其他内部占位值；可信 JWT 只证明 client，配置仍不能产生非法租户键。
- 现状：`AudienceTenantResolver` 对 map value 只检查非 blank，并只剔除精确的 `__no_tenant__`。`"bad tenant"`、超长值、`"__single__"` 会被接受并写入 `TenantContext`。`RuleSchemaRegistry` 又把 `__single__` 用作 null tenant 的内部 key。
- 复现路径：配置 `clientTenantMap={client-x: "bad tenant"}` 或 `{client-x: "__single__"}`，JWT aud=`client-x`；validator 成功，filter 放行。后者可与无上下文 schema override 共用 key。
- 建议处置：构造 resolver 时验证并复制配置；统一保留值集合；非法配置应启动失败。修复前 TODO 测试期待 resolver 返回 empty/配置异常。

## ISSUE-04（P1）bizLine 级 schema 与 field-dict/preview 的解析维度不一致

- 预期行为：运营注册 `(tenant,bizLine)` 字段后，前端 field-dict、保存前 preview、最终 create 应看到同一 schema。
- 现状：`ActivityMarketingService.create` 调用 `resolve(currentTenant(), req.bizLine())`；但 `ActivityMarketingController.fieldDict()` 固定调用 `resolveFields(TenantContext.get(), null)`，`previewEligibility()` 也固定 `resolve(currentTenant(), null)`，接口没有 bizLine 输入。
- 复现路径：`register("acme", "travel", [completedTrips])`；acme 的 field-dict 不包含 `completedTrips`，preview 该字段失败，但带 `bizLine="travel"` 的 create 可成功。
- 建议处置：field-dict 增加可选 bizLine query；preview 请求携带 bizLine；三条路径共用解析函数。修复前只锁定 registry/create 的正确维度，不断言当前 field-dict/preview 错误。

## ISSUE-05（P1）blank requestId 被查询逻辑视为“无”，却被原样写入唯一列

- 预期行为：null、空串、纯空白 requestId 都应标准化为 null，表示不启用幂等；多次普通创建不能因空白键互相冲突。
- 现状：`create` 的幂等预读仅在 `requestId != null && !isBlank()` 时执行；`saveManage` 却在 v1 上无条件 `setRequestId(req.requestId())`。空串/空白会进入 `(tenant_id,request_id)` 唯一约束。
- 复现路径：同一 tenant 连续创建两个不同活动，requestId 都为 `" "`。第一次写入空白，第二次跳过幂等查询后在 `saveAndFlush` 触发唯一冲突并转成 409。
- 建议处置：进入服务即 normalize；实体写入与预读使用同一规范值；增加 H2 回归。

## ISSUE-06（P1）所有 `DataIntegrityViolationException` 都被误报为并发重复 requestId

- 预期行为：只有 `uk_am_tenant_request` 冲突转为幂等并发 409；长度、非空、其他约束错误应保留真实原因并按参数/服务错误分类。
- 现状：`saveManage` 对 `saveAndFlush` 的任何 `DataIntegrityViolationException` 都抛 `IllegalStateException("并发重复请求(requestId)…")`，即使 requestId 为 null。
- 复现路径：提交超过 `activity_name varchar(128)` 的名称（服务只校验非 blank，未校验长度），由 H2/MySQL 抛数据完整性异常；响应会被 controller 转成“并发重复 requestId”409。
- 建议处置：优先补齐输入长度校验；仅识别目标 constraint name 时转幂等冲突，其余异常透传/分类。测试应同时断言状态与错误语义。

## ISSUE-07（P1）活动编辑后，原始幂等键无法再返回首次结果

- 预期行为：类注释所述“同 requestId 重复提交返回首次结果”在活动后续版本化后仍成立，或产品明确限定有效期并返回清晰冲突。
- 现状：幂等查询限定 `isDel=0`；编辑会把持有 requestId 的 v1 逻辑删除，而 v2 按设计不保存 requestId。之后重放原始 create 查不到 v1，又会因唯一约束仍包含已删除 v1 而转成并发重复 409。
- 复现路径：tenant acme 用 requestId=`K` 创建 v1；编辑成 v2；原样重放首个 create(K)。预读为空，insert 与已删除 v1 的唯一键冲突。
- 建议处置：建立独立幂等记录/请求结果表，或查询不受 `isDel` 限制并定义返回哪个版本；补充生命周期回归测试。

## ISSUE-08（P2，产品语义待确认）`warmupEnabled` 名为 fail-fast，但失败只 WARN、启动继续

- 预期行为：若“fail-fast”含义是配置错或 JWKS 不可达时阻止 auth 服务就绪，则 runner 应导致启动/就绪失败；若只要求提前告警，则当前行为可以接受，但命名与验收标准应改清楚。
- 现状：`JwksWarmupRunner.run` 捕获所有异常并只记录 WARN；不会中断启动，也不预热 Nimbus decoder 自身缓存。类注释已诚实写明这一点，与被测面描述中的“fail-fast 自检”存在语义张力。
- 复现路径：auth=true、warmup=true、jwkSetUri 指向未监听端口；应用仍启动，首次真实 JWT 验签再次拉 JWKS 并失败。
- 建议处置：产品选择 hard fail、readiness fail 或 warn-only 之一；测试按选择锁定。当前草案只锁定 warmup 开关和“调用被尝试”，不锁定启动策略。

## ISSUE-09（P2）audienceTemplates 含 null 会在 resolver 构造时非诊断性 NPE

- 预期行为：配置项应被明确拒绝并带属性路径，或忽略空项；不得在 `compile` 的 `template.indexOf` 处裸 NPE。
- 现状：构造器仅处理整个 list 为 null，不处理 list 内元素为 null。
- 复现路径：`new AudienceTenantResolver(Map.of(), Arrays.asList(null, "activity-{tenant}-cid"))`。
- 建议处置：配置 validation 或 stream 中过滤 null/blank；增加明确异常消息。

## 未发现为 bug、但需要回归锁定的点

- 多 aud 中“未知 aud + 恰好一个可解析 tenant”当前返回该 tenant；这符合“解析出的不同租户集合大小恰为 1”的实现契约，不应误写成歧义拒绝。
- 同一 tenant 由 map 与 pattern 两个 aud 重复解析时允许通过；不同 tenant 才拒绝。
- `JwtTenantFilter` 在下游改变 `SecurityContext` 时，当前请求的 `TenantContext` 仍保持进入 filter 时的一次解析值，避免请求中途 TOCTOU 改租户；请求结束 clear。
- Caffeine 的 key 使用 tenant + NUL + DRL，同一 DRL 不跨有效租户复用；空/blank tenant 的潜在碰撞由 ISSUE-01/03 的入口校验负责解决。
