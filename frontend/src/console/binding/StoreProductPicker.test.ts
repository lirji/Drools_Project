import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import StoreProductPicker from './StoreProductPicker.vue'

function response(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: vi.fn().mockResolvedValue(JSON.stringify(body)),
  } as unknown as Response
}

function page(total: number, items: Array<Record<string, unknown>>, p = 0) {
  return { total, page: p, size: 10, items }
}

function stubFetch(stores: Array<Record<string, unknown>>, productsByPage: Record<number, Record<string, unknown>>) {
  const fetch = vi.fn().mockImplementation((url: string) => {
    const u = String(url)
    if (u.includes('/products')) {
      const pageNo = Number(new URL(u, 'http://localhost').searchParams.get('page') ?? 0)
      return Promise.resolve(response(200, productsByPage[pageNo] ?? page(0, [], pageNo)))
    }
    if (u.includes('/store-picker/stores')) return Promise.resolve(response(200, stores))
    return Promise.resolve(response(200, {}))
  })
  vi.stubGlobal('fetch', fetch)
  return fetch
}

const urls = (fetch: ReturnType<typeof vi.fn>) => fetch.mock.calls.map((c) => String(c[0]))

describe('StoreProductPicker', () => {
  afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers() })

  it('目录惰性拉：面板收起时不发请求，点开才拉店铺', async () => {
    const fetch = stubFetch([{ storeId: 1, storeName: '旗舰店', productCount: 2 }], {})
    const w = mount(StoreProductPicker)
    await flushPromises()
    expect(urls(fetch).some((u) => u.includes('/store-picker/stores'))).toBe(false)

    await w.get('[data-testid="store-picker-toggle"]').trigger('click')
    await flushPromises()
    expect(urls(fetch).some((u) => u.includes('/store-picker/stores'))).toBe(true)
    expect(w.get('[data-testid="store-picker-store-1"]').text()).toContain('旗舰店')
  })

  it('选店铺→拉该店商品；勾选+加入绑定 emit append 的 {storeId,spuId}', async () => {
    const fetch = stubFetch(
      [{ storeId: 1, storeName: '旗舰店', productCount: 2 }],
      { 0: page(2, [
        { spuId: 9101, spuName: '蓝牙耳机', price: 120, onShelf: 1 },
        { spuId: 9102, spuName: '机械键盘', price: 180, onShelf: 1 },
      ]) },
    )
    const w = mount(StoreProductPicker)
    await w.get('[data-testid="store-picker-toggle"]').trigger('click')
    await flushPromises()

    await w.get('[data-testid="store-picker-store-1"]').trigger('click')
    await flushPromises()
    expect(urls(fetch).some((u) => u.includes('/store-picker/stores/1/products'))).toBe(true)
    expect(w.get('[data-testid="store-picker-product-9101"]').text()).toContain('蓝牙耳机')

    await w.get('[data-testid="store-picker-product-9101"] input[type="checkbox"]').setValue(true)
    await w.get('[data-testid="store-picker-confirm"]').trigger('click')

    const appended = w.emitted('append')
    expect(appended).toBeTruthy()
    expect(appended![0][0]).toEqual([{ storeId: 1, spuId: 9101 }])
  })

  it('搜索带 keyword 打到服务端', async () => {
    vi.useFakeTimers()
    const fetch = stubFetch(
      [{ storeId: 1, storeName: '旗舰店', productCount: 5 }],
      { 0: page(1, [{ spuId: 9101, spuName: '蓝牙耳机', price: 120, onShelf: 1 }]) },
    )
    const w = mount(StoreProductPicker)
    await w.get('[data-testid="store-picker-toggle"]').trigger('click')
    await vi.runAllTimersAsync()
    await w.get('[data-testid="store-picker-store-1"]').trigger('click')
    await vi.runAllTimersAsync()

    await w.get('#sp-kw').setValue('蓝牙')
    await vi.runAllTimersAsync()
    expect(urls(fetch).some((u) => u.includes('/products') && u.includes('keyword='))).toBe(true)
  })

  it('无店铺时显示空态', async () => {
    stubFetch([], {})
    const w = mount(StoreProductPicker)
    await w.get('[data-testid="store-picker-toggle"]').trigger('click')
    await flushPromises()
    expect(w.text()).toContain('该租户暂无可选店铺')
  })
})
