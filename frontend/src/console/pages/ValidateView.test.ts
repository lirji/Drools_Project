import { flushPromises, mount } from '@vue/test-utils'
import { spuDiscount } from '../activityApi'
import ValidateView from './ValidateView.vue'

vi.mock('../activityApi', () => ({
  spuDiscount: vi.fn(),
  queryGifts: vi.fn(),
}))

describe('ValidateView', () => {
  it('缺少 SPU 时就地提示，不发送请求', async () => {
    const wrapper = mount(ValidateView, { global: { stubs: { RouterLink: true } } })
    await wrapper.get('[data-testid="v-discount"]').trigger('click')

    expect(vi.mocked(spuDiscount)).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="v-error"]').text()).toContain('至少填写一个 SPU')
  })

  it('请求期间锁定模式，完成后展示决策结果', async () => {
    let resolveRequest!: (value: { ok: boolean; status: number; json: Record<string, unknown>; text: string }) => void
    vi.mocked(spuDiscount).mockReturnValueOnce(new Promise((resolve) => { resolveRequest = resolve }))
    const wrapper = mount(ValidateView, { global: { stubs: { RouterLink: true } } })

    await wrapper.get('[data-testid="v-spu"]').setValue('990011')
    await wrapper.get('[data-testid="v-discount"]').trigger('click')
    expect(wrapper.findAll('.mode-picker > button').every((button) => button.attributes('disabled') !== undefined)).toBe(true)

    resolveRequest({ ok: true, status: 200, json: { hit: false, mode: 'MAX', traces: ['无候选活动'] }, text: '' })
    await flushPromises()

    expect(wrapper.get('[data-testid="validate-result"]').text()).toContain('本次未命中优惠')
    expect(wrapper.text()).toContain('无候选活动')

    await wrapper.findAll('.mode-picker > button')[1].trigger('click')
    expect(wrapper.find('[data-testid="validate-result"]').exists()).toBe(false)
  })
})
