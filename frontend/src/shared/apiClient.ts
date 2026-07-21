// API 客户端 —— 服务注册表式（继承旧 activity.js 的教训：API base 不散写）。
// 现有两个 service：root（规则能力端点）、marketing（/activity-marketing）。
// 后端拆微服务后只在此加 decision/console 条目 + 改 base 映射，页面代码零改动（决策 D3 对齐点）。

import type { ApiResult } from './types'

export type ServiceKey = 'root' | 'marketing'

// 各 service 的 base 前缀。运行时可被 VITE_API_BASE / 未来 config.json 覆盖（网关期切前缀）。
const BASES: Record<ServiceKey, string> = {
  root: import.meta.env.VITE_API_BASE ?? '',
  marketing: (import.meta.env.VITE_API_BASE ?? '') + '/activity-marketing',
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
