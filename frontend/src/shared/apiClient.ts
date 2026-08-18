// API 客户端 —— 服务注册表式（继承旧 activity.js 的教训：API base 不散写）。
// 现有两个 service：root（规则能力端点）、marketing（/activity-marketing）。
// 后端拆微服务后只在此加 decision/console 条目 + 改 base 映射，页面代码零改动（决策 D3 对齐点）。

import type { ApiResult } from './types'

export type ServiceKey = 'root' | 'marketing' | 'decision'

// 各 service 的 base 前缀。运行时可被 VITE_API_BASE / 未来 config.json 覆盖（网关期切前缀）。
//
// ⚠ decision 的 base 必须是网关前缀 `/api/decision`，**不能**写成后端的真实路径 `/decision/v1`：
//    · 网关只有 `location /api/decision/`（deploy/nginx.conf）会 rewrite 到 decision:8082 的 /decision/v1/；
//      写 /decision/v1 会落到兜底 `location /` 打到 console，而 console 的 classpath 上根本没有
//      DecisionPlaneController（它在 activity-decision 模块），必 404。
//    · vite dev 下另有一条独立 proxy（见 vite.config.ts）把 ^/api/decision 转到 :8082；
//      已有的 'decision' 前缀用于兼容旧规则执行端点 /decision/calculate，指向 console，撞不得。
// TS 只校验这里有没有 decision 这个键，**不校验字符串对不对**——写错了 typecheck 与全部单测都会绿，
// 只在真实浏览器里以 404 暴露。改这一行时请一并跑 e2e。
const BASES: Record<ServiceKey, string> = {
  root: import.meta.env.VITE_API_BASE ?? '',
  marketing: (import.meta.env.VITE_API_BASE ?? '') + '/activity-marketing',
  decision: (import.meta.env.VITE_DECISION_BASE ?? import.meta.env.VITE_API_BASE ?? '') + '/api/decision',
}

// 请求前注入 header 的钩子（认证/租户由 auth 层注册，避免 apiClient 反向依赖 store）。
type HeaderProvider = () => Record<string, string>
let headerProvider: HeaderProvider = () => ({})
export function setHeaderProvider(fn: HeaderProvider): void {
  headerProvider = fn
}

// 401 全局回调（auth 档 token 失效 → 清 token 回登录页），由 auth 层注册。
let onUnauthorized: (() => void) | null = null
export function setUnauthorizedHandler(fn: () => void): void {
  onUnauthorized = fn
}

export interface ApiOptions {
  signal?: AbortSignal
  // 文本响应（如 /actuator/prometheus）：不尝试 JSON.parse
  raw?: boolean
}

export async function api<T = unknown>(
  service: ServiceKey,
  method: string,
  path: string,
  body?: unknown,
  opts: ApiOptions = {},
): Promise<ApiResult<T>> {
  const headers: Record<string, string> = { ...headerProvider() }
  const init: RequestInit = { method, headers, signal: opts.signal }
  if (body !== undefined && body !== null) {
    headers['Content-Type'] = 'application/json'
    init.body = JSON.stringify(body)
  }
  const res = await fetch(BASES[service] + path, init)
  if (res.status === 401 && onUnauthorized) onUnauthorized()
  const text = await res.text()
  let json: T | null = null
  if (!opts.raw) {
    try {
      json = text ? (JSON.parse(text) as T) : null
    } catch {
      /* 非 JSON 响应，json 保持 null，调用方读 text */
    }
  }
  return { ok: res.ok, status: res.status, json, text }
}

/** 便捷：从 ApiResult 抽错误文案（对齐旧 errText） */
export function errText(r: ApiResult): string {
  const j = r.json as { error?: string; message?: string } | null
  return (j && (j.error || j.message)) || 'HTTP ' + r.status
}
