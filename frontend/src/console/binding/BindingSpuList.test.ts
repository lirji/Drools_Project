import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import BindingSpuList from './BindingSpuList.vue'

function response(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: vi.fn().mockResolvedValue(JSON.stringify(body)),
  } as unknown as Response
}

function page(total: number, p: number, items: Array<Record<string, unknown>>) {
  return { total, page: p, size: 10, items }
}

function stubFetch(byPage: Record<number, Record<string, unknown>>) {
  const fetch = vi.fn().mockImplementation((url: string) => {
    const pageNo = Number(new URL(String(url), 'http://localhost').searchParams.get('page') ?? 0)
    return Promise.resolve(response(200, byPage[pageNo] ?? page(0, pageNo, [])))
  })
  vi.stubGlobal('fetch', fetch)
  return fetch
}

const spusUrls = (fetch: ReturnType<typeof vi.fn>) =>
  fetch.mock.calls.map((c) => String(c[0])).filter((u) => u.includes('/binding-spus'))

describe('BindingSpuList 分页与缓存', () => {
  beforeEach(() => { vi.stubGlobal('VITE_API_BASE', undefined) })
  afterEach(() => { vi.unstubAllGlobals() })

  it('翻页后回到已看过的页命中缓存，不再重复请求', async () => {
    const fetch = stubFetch({
      0: page(15, 0, [{ spuId: 1, spuName: 'A', price: 9, bindSource: 1, effective: 1, poolId: 1 }]),
      1: page(15, 1, [{ spuId: 2, spuName: 'B', price: 9, bindSource: 1, effective: 1, poolId: 1 }]),
    })
    const wrapper = mount(BindingSpuList, { props: { activityId: 'ACT1', version: 1, storeId: 10 } })
    await flushPromises()
    expect(wrapper.text()).toContain('A')

    await wrapper.get('[data-testid="binding-spu-next"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('B')

    await wrapper.get('[data-testid="binding-spu-prev"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('A')

    // page0 只请求一次（回退命中缓存），page1 一次 → 共 2 次唯一请求
    const urls = spusUrls(fetch)
    expect(urls.filter((u) => u.includes('page=0'))).toHaveLength(1)
    expect(urls.filter((u) => u.includes('page=1'))).toHaveLength(1)
  })

  it('该店铺无商品时显示空态', async () => {
    stubFetch({ 0: page(0, 0, []) })
    const wrapper = mount(BindingSpuList, { props: { activityId: 'ACT1', version: 1, storeId: 10 } })
    await flushPromises()
    expect(wrapper.text()).toContain('该店铺暂无商品')
  })

  it('storeId 为 null 时省略 storeId 参数（命中未指定门店桶）', async () => {
    const fetch = stubFetch({ 0: page(1, 0, [{ spuId: 9, spuName: '未分店铺商品', price: 1, bindSource: 0, effective: 1, poolId: null }]) })
    const wrapper = mount(BindingSpuList, { props: { activityId: 'ACT1', version: 1, storeId: null } })
    await flushPromises()
    expect(wrapper.text()).toContain('未分店铺商品')
    expect(spusUrls(fetch)[0]).not.toContain('storeId=')
  })
})
