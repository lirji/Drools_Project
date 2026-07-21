import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import ConfirmDialog from './ConfirmDialog.vue'
import { useConfirm } from '@/shared/useConfirm'

describe('ConfirmDialog + useConfirm（Promise 化确认）', () => {
  beforeEach(() => {
    // 清掉可能残留的挂起状态
    const { active, settle } = useConfirm()
    if (active.value) settle(false)
  })

  it('confirm() 打开对话框，点确定 resolve(true)', async () => {
    const w = mount(ConfirmDialog)
    const { confirm } = useConfirm()
    const p = confirm({ title: '删除？', body: '不可撤销' })
    await w.vm.$nextTick()
    const dlg = w.find('[data-testid="confirm-dialog"]')
    expect(dlg.exists()).toBe(true)
    expect(dlg.attributes('role')).toBe('dialog')
    expect(dlg.attributes('aria-modal')).toBe('true')
    await w.find('[data-testid="confirm-ok"]').trigger('click')
    expect(await p).toBe(true)
    await w.vm.$nextTick()
    expect(w.find('[data-testid="confirm-dialog"]').exists()).toBe(false)
  })

  it('点取消 resolve(false)', async () => {
    const w = mount(ConfirmDialog)
    const { confirm } = useConfirm()
    const p = confirm({ title: '离开？' })
    await w.vm.$nextTick()
    await w.find('[data-testid="confirm-cancel"]').trigger('click')
    expect(await p).toBe(false)
  })

  it('scrim 点击 = 取消', async () => {
    const w = mount(ConfirmDialog, { attachTo: document.body })
    const { confirm } = useConfirm()
    const p = confirm({ title: '离开？' })
    await w.vm.$nextTick()
    await w.find('.scrim').trigger('click')
    expect(await p).toBe(false)
  })

  it('单飞：新 confirm 使旧挂起 resolve(false)', async () => {
    const w = mount(ConfirmDialog)
    const { confirm } = useConfirm()
    const p1 = confirm({ title: '第一个' })
    const p2 = confirm({ title: '第二个' })
    expect(await p1).toBe(false)
    await w.vm.$nextTick()
    expect(w.find('[data-testid="confirm-dialog"]').text()).toContain('第二个')
    const { settle } = useConfirm()
    settle(true)
    expect(await p2).toBe(true)
  })

  it('danger 用告警图标样式', async () => {
    const w = mount(ConfirmDialog)
    const { confirm } = useConfirm()
    confirm({ title: '下线？', danger: true })
    await w.vm.$nextTick()
    expect(w.find('.d-ic.danger').exists()).toBe(true)
    expect(w.find('[data-testid="confirm-ok"]').classes()).toContain('danger')
  })
})
