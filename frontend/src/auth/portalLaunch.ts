import type { AuthConfig } from '@/shared/types'

export interface PortalLaunch {
  clientId: string
  returnTo: string
}

/** 只允许目标 SPA 内的单斜杠绝对路径。 */
export function sanitizeInternalPath(raw: unknown): string | null {
  if (typeof raw !== 'string' || !raw.startsWith('/') || raw.startsWith('//')) return null
  if (raw.includes('\\') || [...raw].some((character) => {
    const code = character.codePointAt(0) ?? 0
    return code <= 0x1f || code === 0x7f
  })) return null
  return raw
}

/**
 * 自动登录必须同时满足：显式 portal 请求、auth 档开启、clientId 精确命中后端 auth-config allowlist。
 * 公开门户传来的 clientId 只是提示，目标项目自己的配置才是信任源。
 */
export function resolvePortalLaunch(query: Record<string, unknown>, config: AuthConfig | null): PortalLaunch | null {
  if (query.source !== 'portal' || query.auto !== '1' || !config?.authEnabled) return null
  if (typeof query.clientId !== 'string') return null
  const allowed = (config.webClients ?? []).some((item) => item.clientId === query.clientId)
  if (!allowed) return null
  return {
    clientId: query.clientId,
    returnTo: sanitizeInternalPath(query.returnTo) ?? '/home',
  }
}
