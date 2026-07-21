// OIDC 授权码 + PKCE 客户端 —— 框架无关纯模块，忠实平移自 static/assets/activity.js:17-160。
// 抽成纯模块的目的（决策 D2/D3）：Pinia store 只做响应式包装；未来 BFF 接管 OIDC 时只换本模块实现，UI 不动。
//
// 安全不变量（与后端 JwtTenantFilter/AudienceTenantResolver 硬绑定，一个都不能改）：
//   - token 存 sessionStorage 不落 localStorage（降 XSS 持久窃取面，52-*.md §3.4）
//   - state 校验防 CSRF；回调换 token 后清 ?code= 防重放
//   - auth 档只发 Authorization: Bearer，绝不发 X-Tenant-Id（发了≠aud 后端 403）
//   - sessionStorage 四件套 key 名沿用旧值，可平滑接续已登录会话
import type { AuthConfig } from '@/shared/types'

const SS_TOKEN = 'actOidcTok'
const SS_VERIFIER = 'actPkceV'
const SS_STATE = 'actOauthState'
const SS_CID = 'actOauthCid'
const SS_RETURN = 'actOauthReturn'

export interface TokenState {
  token: string | null
  refresh: string | null
  expiresAt: number
}

/** 回调着陆的 redirect_uri = origin + base(/ui/) + auth/callback（决策 D4：前端派生含 base）。
 *  dev(:5173) 下 import.meta.env.BASE_URL='/ui/' 亦成立；须登记进 Casdoor redirectUris 白名单。 */
export function redirectUri(): string {
  const base = import.meta.env.BASE_URL || '/'
  return window.location.origin + base.replace(/\/$/, '') + '/auth/callback'
}

export function loadToken(): TokenState {
  try {
    const raw = sessionStorage.getItem(SS_TOKEN)
    if (raw) {
      const t = JSON.parse(raw) as TokenState
      return { token: t.token, refresh: t.refresh, expiresAt: t.expiresAt || 0 }
    }
  } catch {
    /* 解析失败当未登录 */
  }
  return { token: null, refresh: null, expiresAt: 0 }
}

export function storeToken(accessToken: string, expiresIn: number | string, refreshToken?: string): TokenState {
  const prev = loadToken()
  const t: TokenState = {
    token: accessToken || null,
    refresh: refreshToken || prev.refresh,
    expiresAt: Date.now() + (Number(expiresIn) || 3600) * 1000,
  }
  try {
    sessionStorage.setItem(SS_TOKEN, JSON.stringify(t))
  } catch {
    /* 隐私模式忽略 */
  }
  return t
}

export function clearToken(): void {
  for (const k of [SS_TOKEN, SS_VERIFIER, SS_STATE, SS_CID]) {
    try {
      sessionStorage.removeItem(k)
    } catch {
      /* ignore */
    }
  }
}

export function isExpiring(expiresAt: number): boolean {
  return expiresAt > 0 && Date.now() > expiresAt - 30000
}

/** JWT payload 观察（不验签——验签是后端的事，前端只取展示信息） */
export function jwtPayload(tok: string | null): Record<string, unknown> {
  try {
    const b = (tok || '').split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
    return JSON.parse(atob(b)) as Record<string, unknown>
  } catch {
    return {}
  }
}

export function tokenAud(tok: string | null): string {
  const aud = jwtPayload(tok).aud
  return Array.isArray(aud) ? String(aud[0]) : String(aud ?? '')
}

/** 登录租户：token 的 aud 反查 webClients（clientId→tenant），查不到显示原 aud */
export function tokenTenant(tok: string | null, cfg: AuthConfig | null): string {
  const aud = tokenAud(tok)
  const hit = (cfg?.webClients || []).find((w) => w.clientId === aud)
  return hit ? hit.tenant : aud
}

/** 操作者展示名：Casdoor 用户 token 的 sub 是 UUID，展示优先 name/preferred_username（后端四眼仍用原始 sub） */
export function tokenSub(tok: string | null): string {
  const p = jwtPayload(tok)
  return String(p.name || p.preferred_username || p.sub || '')
}

// ---- PKCE 助手（Web Crypto）----
function b64url(buf: ArrayBuffer): string {
  return btoa(String.fromCharCode(...new Uint8Array(buf)))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')
}
function randomVerifier(): string {
  const a = new Uint8Array(32)
  crypto.getRandomValues(a)
  return b64url(a.buffer)
}
async function challenge(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))
  return b64url(digest)
}

/** 发起登录：记 verifier/state/clientId/returnTo → 重定向 Casdoor authorize */
export async function login(cfg: AuthConfig, clientId: string, returnTo: string): Promise<void> {
  const verifier = randomVerifier()
  const st = randomVerifier()
  try {
    sessionStorage.setItem(SS_VERIFIER, verifier)
    sessionStorage.setItem(SS_STATE, st)
    sessionStorage.setItem(SS_CID, clientId)
    sessionStorage.setItem(SS_RETURN, returnTo)
  } catch {
    throw new Error('sessionStorage 不可用，无法登录（隐私模式？）')
  }
  const chal = await challenge(verifier)
  const u = new URL(cfg.authorizeEndpoint as string)
  u.search = new URLSearchParams({
    response_type: 'code',
    client_id: clientId,
    redirect_uri: redirectUri(),
    scope: cfg.scope || 'openid profile',
    state: st,
    code_challenge: chal,
    code_challenge_method: 'S256',
  }).toString()
  window.location.assign(u.toString())
}

export interface CallbackResult {
  token: TokenState
  returnTo: string
}

/** 回调换 token（公有客户端：code_verifier，无 secret）。校验 state 防 CSRF。 */
export async function handleCallback(cfg: AuthConfig, search: string): Promise<CallbackResult> {
  const p = new URLSearchParams(search)
  const code = p.get('code')
  if (!code) throw new Error('回调缺少 code')
  if (p.get('state') !== sessionStorage.getItem(SS_STATE)) {
    throw new Error('state 不匹配（可能的 CSRF），已拒绝回调')
  }
  const res = await fetch(cfg.tokenEndpoint as string, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'authorization_code',
      code,
      redirect_uri: redirectUri(),
      client_id: sessionStorage.getItem(SS_CID) || '',
      code_verifier: sessionStorage.getItem(SS_VERIFIER) || '',
    }),
  })
  const t = (await res.json()) as { access_token?: string; expires_in?: number; refresh_token?: string }
  if (!t.access_token) throw new Error('换 token 失败: ' + JSON.stringify(t))
  const token = storeToken(t.access_token, t.expires_in ?? 3600, t.refresh_token)
  const returnTo = sessionStorage.getItem(SS_RETURN) || '/home'
  try {
    sessionStorage.removeItem(SS_VERIFIER)
    sessionStorage.removeItem(SS_STATE)
    sessionStorage.removeItem(SS_RETURN)
  } catch {
    /* ignore */
  }
  return { token, returnTo }
}

/** silent refresh：refresh_token 换新 token；失败抛错（调用方 clearToken 回登录页） */
export async function refresh(cfg: AuthConfig, refreshToken: string): Promise<TokenState> {
  const res = await fetch(cfg.tokenEndpoint as string, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'refresh_token',
      refresh_token: refreshToken,
      client_id: sessionStorage.getItem(SS_CID) || '',
    }),
  })
  const t = (await res.json()) as { access_token?: string; expires_in?: number; refresh_token?: string }
  if (!t.access_token) throw new Error('refresh 失败')
  return storeToken(t.access_token, t.expires_in ?? 3600, t.refresh_token)
}
