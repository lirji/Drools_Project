import { describe, it, expect, beforeEach, vi } from 'vitest'
import { isExpiring, jwtPayload, tokenAud, tokenTenant, tokenSub, storeToken, loadToken, clearToken } from './authClient'
import type { AuthConfig } from '@/shared/types'

// 构造一个未签名的 JWT（仅 payload 可解，验签是后端的事）
function fakeJwt(payload: Record<string, unknown>): string {
  const b64 = (o: unknown) => btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  return `${b64({ alg: 'RS256' })}.${b64(payload)}.sig`
}

describe('authClient 纯函数', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  it('isExpiring: 30s 提前量', () => {
    expect(isExpiring(0)).toBe(false) // 无 token
    expect(isExpiring(Date.now() + 60000)).toBe(false) // 还早
    expect(isExpiring(Date.now() + 10000)).toBe(true) // 30s 内
    expect(isExpiring(Date.now() - 1000)).toBe(true) // 已过期
  })

  it('jwtPayload / tokenAud: 解析 aud', () => {
    const jwt = fakeJwt({ aud: 'activity-acme-web-cid', sub: 'admin/acme' })
    expect(jwtPayload(jwt).sub).toBe('admin/acme')
    expect(tokenAud(jwt)).toBe('activity-acme-web-cid')
    // aud 为数组取第一个
    expect(tokenAud(fakeJwt({ aud: ['x-cid', 'y-cid'] }))).toBe('x-cid')
    expect(tokenAud(null)).toBe('')
  })

  it('tokenTenant: aud 反查 webClients', () => {
    const cfg: AuthConfig = {
      authEnabled: true,
      webClients: [{ tenant: 'acme', clientId: 'activity-acme-web-cid' }],
    }
    expect(tokenTenant(fakeJwt({ aud: 'activity-acme-web-cid' }), cfg)).toBe('acme')
    // 查不到显示原 aud
    expect(tokenTenant(fakeJwt({ aud: 'unknown-cid' }), cfg)).toBe('unknown-cid')
  })

  it('tokenSub: 优先 name/preferred_username 再 sub', () => {
    expect(tokenSub(fakeJwt({ name: 'alice', sub: 'uuid-1' }))).toBe('alice')
    expect(tokenSub(fakeJwt({ preferred_username: 'bob', sub: 'uuid-2' }))).toBe('bob')
    expect(tokenSub(fakeJwt({ sub: 'uuid-3' }))).toBe('uuid-3')
  })

  it('storeToken/loadToken/clearToken: sessionStorage 往返', () => {
    vi.spyOn(Date, 'now').mockReturnValue(1_000_000)
    storeToken('tok-abc', 3600, 'refresh-xyz')
    const loaded = loadToken()
    expect(loaded.token).toBe('tok-abc')
    expect(loaded.refresh).toBe('refresh-xyz')
    expect(loaded.expiresAt).toBe(1_000_000 + 3600 * 1000)
    clearToken()
    expect(loadToken().token).toBeNull()
    vi.restoreAllMocks()
  })

  it('storeToken: 保留旧 refresh 当新的缺省', () => {
    storeToken('tok-1', 3600, 'refresh-1')
    storeToken('tok-2', 3600) // 无新 refresh
    expect(loadToken().refresh).toBe('refresh-1')
  })
})
