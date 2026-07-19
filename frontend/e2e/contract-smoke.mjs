// 契约冒烟（Tier-1，平移自旧 31 号计划决策 5）：直接打后端端点，断言响应 shape 与前端 TS 类型对齐，
// 防「后端字段字典/枚举漂移」静默破坏前端。不需要浏览器，node fetch 即可。
// 用法：BASE=http://localhost:8081 TENANT=acme node e2e/contract-smoke.mjs（后端需起在 BASE）。
const BASE = process.env.BASE || 'http://localhost:8081'
const TENANT = process.env.TENANT || 'acme'
const H = { 'X-Tenant-Id': TENANT }

let pass = 0, fail = 0
const ok = (m) => { pass++; console.log('  ✅', m) }
const no = (m) => { fail++; console.log('  ❌', m) }

async function get(path) {
  const r = await fetch(BASE + path, { headers: H })
  const t = await r.text()
  let j = null
  try { j = t ? JSON.parse(t) : null } catch { /* non-json */ }
  return { ok: r.ok, status: r.status, json: j }
}

console.log(`契约冒烟 → ${BASE}（tenant=${TENANT}）`)

// 1. auth-config：至少有 authEnabled 布尔
{
  const r = await get('/activity-marketing/auth-config')
  r.ok && typeof r.json?.authEnabled === 'boolean'
    ? ok('auth-config.authEnabled 是布尔')
    : no(`auth-config shape 异常: ${r.status} ${JSON.stringify(r.json)}`)
}

// 2. field-dict：fields/operators/strategies/activityTypes 存在且 field 有 key/valueType/operators
{
  const r = await get('/activity-marketing/field-dict')
  const d = r.json || {}
  const shapeOk = Array.isArray(d.fields) && Array.isArray(d.operators) &&
    Array.isArray(d.strategies) && Array.isArray(d.activityTypes)
  shapeOk ? ok('field-dict 顶层数组齐全') : no(`field-dict 顶层 shape 异常: ${JSON.stringify(Object.keys(d))}`)
  const f = (d.fields || [])[0]
  if (f) {
    (f.key && f.valueType && Array.isArray(f.operators))
      ? ok('field-dict.fields[0] 有 key/valueType/operators')
      : no(`field 元素 shape 异常: ${JSON.stringify(f)}`)
  }
  const op = (d.operators || [])[0]
  if (op) {
    (op.code && op.operand)
      ? ok('field-dict.operators[0] 有 code/operand')
      : no(`operator 元素 shape 异常: ${JSON.stringify(op)}`)
  }
}

// 3. list：数组（可空）
{
  const r = await get('/activity-marketing/list')
  Array.isArray(r.json) ? ok('list 返回数组') : no(`list 非数组: ${r.status} ${JSON.stringify(r.json)}`)
}

console.log(`\n结果: pass=${pass} fail=${fail}`)
process.exit(fail > 0 ? 1 : 0)
