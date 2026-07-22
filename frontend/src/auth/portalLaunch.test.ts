import { describe, expect, it } from 'vitest'
import type { AuthConfig } from '@/shared/types'
import { resolvePortalLaunch, sanitizeInternalPath } from './portalLaunch'

const config: AuthConfig = {
  authEnabled: true,
  webClients: [
    { tenant: 'acme', clientId: 'activity-acme-web-cid' },
    { tenant: 'beta', clientId: 'activity-beta-web-cid' },
  ],
}

describe('Drools portal launch contract', () => {
  it('只接受 auth-config allowlist 中的 public clientId', () => {
    expect(resolvePortalLaunch({
      source: 'portal', auto: '1', clientId: 'activity-acme-web-cid', returnTo: '/console/activities',
    }, config)).toEqual({ clientId: 'activity-acme-web-cid', returnTo: '/console/activities' })
    expect(resolvePortalLaunch({ source: 'portal', auto: '1', clientId: 'evil-cid' }, config)).toBeNull()
  })

  it('auth 关闭、非 portal、未 auto 均不触发', () => {
    expect(resolvePortalLaunch({ source: 'portal', auto: '1', clientId: 'activity-acme-web-cid' }, { ...config, authEnabled: false })).toBeNull()
    expect(resolvePortalLaunch({ source: 'direct', auto: '1', clientId: 'activity-acme-web-cid' }, config)).toBeNull()
    expect(resolvePortalLaunch({ source: 'portal', auto: '0', clientId: 'activity-acme-web-cid' }, config)).toBeNull()
  })

  it.each(['//evil.com', 'https://evil.com', '/\\evil', '/ok\u0000bad'])('拒绝危险 returnTo %s', (value) => {
    expect(sanitizeInternalPath(value)).toBeNull()
  })

  it('危险 returnTo 对合法 client 回退首页', () => {
    expect(resolvePortalLaunch({
      source: 'portal', auto: '1', clientId: 'activity-acme-web-cid', returnTo: '//evil.com',
    }, config)).toEqual({ clientId: 'activity-acme-web-cid', returnTo: '/home' })
  })
})
