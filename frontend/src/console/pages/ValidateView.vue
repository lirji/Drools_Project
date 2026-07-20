<script setup lang="ts">
import { ref } from 'vue'
import { spuDiscount, queryGifts } from '../activityApi'
import { splitNums, splitStrs, numOrNull } from '../logic'
import { errText } from '@/shared/apiClient'
import type { SpuDiscountRequest } from '@/shared/types'
import Card from '@/shared/ui/Card.vue'
import Kv from '@/shared/ui/Kv.vue'
import Banner from '@/shared/ui/Banner.vue'
import PageHeader from '@/shared/ui/PageHeader.vue'
import EmptyState from '@/shared/ui/EmptyState.vue'

const form = ref({ spu: '', user: '1001', district: '', tags: '', amount: '200', qty: '1' })
const busy = ref(false)
const err = ref('')
const result = ref<Record<string, unknown> | null>(null)
const mode = ref<'discount' | 'gifts'>('discount')
let ctrl: AbortController | null = null

function fmtMoney(v: unknown): string {
  const n = Number(v)
  return isNaN(n) ? '-' : n.toFixed(2)
}

async function run(kind: 'discount' | 'gifts'): Promise<void> {
  busy.value = true
  err.value = ''
  result.value = null
  mode.value = kind
  ctrl?.abort()
  ctrl = new AbortController()
  const body: SpuDiscountRequest = {
    spuIdList: splitNums(form.value.spu),
    userId: numOrNull(form.value.user),
    userDistrictId: form.value.district || null,
    userTags: splitStrs(form.value.tags),
    orderAmount: numOrNull(form.value.amount),
    quantity: numOrNull(form.value.qty),
  }
  try {
    const r = kind === 'discount' ? await spuDiscount(body) : await queryGifts(body)
    if (!r.ok) {
      err.value = errText(r)
      return
    }
    result.value = r.json
  } catch (e) {
    if ((e as Error).name !== 'AbortError') err.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section data-testid="validate-view">
    <PageHeader title="优惠验证" subtitle="对已上线活动跑决策，看命中与决策轨迹（为什么命中）" />
    <div class="grid">
    <div class="col">
      <div class="col-label">商品与用户上下文</div>
      <div class="fg">
        <label>SPU 列表 (逗号)<input v-model="form.spu" placeholder="如 9001,9002" data-testid="v-spu" /></label>
        <label>用户ID<input v-model="form.user" type="number" /></label>
        <label>用户地域<input v-model="form.district" placeholder="如 110000" /></label>
        <label>用户标签 (逗号)<input v-model="form.tags" placeholder="如 vip,new" /></label>
        <label>订单金额<input v-model="form.amount" type="number" /></label>
        <label>数量<input v-model="form.qty" type="number" /></label>
      </div>
      <div class="btns">
        <button class="primary" :disabled="busy" data-testid="v-discount" @click="run('discount')">查红包优惠</button>
        <button class="ghost" :disabled="busy" data-testid="v-gifts" @click="run('gifts')">查买赠赠品</button>
      </div>
    </div>

    <div class="col">
      <div class="col-label">决策结果</div>
      <Banner v-if="busy" kind="info">查询中…</Banner>
      <Banner v-else-if="err" kind="err" data-testid="v-error">{{ err }}</Banner>
      <template v-else-if="result">
        <Card v-if="mode === 'gifts'" :title="'买赠结果 · ' + (result.mode || '-')">
          <div v-if="(result.gifts as unknown[])?.length">
            <div v-for="(g, i) in (result.gifts as Record<string, unknown>[])" :key="i" class="batch">
              <span>{{ g.giftName }}</span><span>×{{ g.giftNum }} · {{ fmtMoney(g.absoluteAmount) }}</span>
            </div>
          </div>
          <div v-else class="muted">无生效买赠活动</div>
        </Card>
        <Card v-else :title="'命中结果 · ' + (result.mode || '-')">
          <Kv k="命中">{{ result.hit ? '是' : '否' }}</Kv>
          <template v-if="result.hit">
            <Kv k="优惠金额">{{ fmtMoney(result.hitAmount) }} 元</Kv>
            <Kv k="命中活动" mono>{{ result.hitActivityName }} ({{ result.hitActivityId }})</Kv>
            <Kv k="策略">{{ result.strategy }}</Kv>
          </template>
        </Card>
        <Card title="决策轨迹 traces">
          <div v-if="(result.traces as unknown[])?.length" class="traces">
            <div v-for="(t, i) in (result.traces as string[])" :key="i" class="tl">
              <span class="seq">#{{ i + 1 }}</span><span>{{ t }}</span>
            </div>
          </div>
          <div v-else class="muted">无</div>
        </Card>
      </template>
      <EmptyState v-else icon="⚖" title="尚未查询" hint="左侧填上下文，点「查红包优惠」或「查买赠赠品」" />
    </div>
    </div>
  </section>
</template>

<style scoped>
.grid { display: grid; grid-template-columns: 1fr 1.1fr; gap: var(--sp-4); }
.col-label { font-weight: 600; margin-bottom: var(--sp-3); font-size: 13px; }
.fg { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-3); }
.fg label { display: flex; flex-direction: column; gap: var(--sp-1); font-size: 12px; color: var(--text-soft); }
.fg input { padding: var(--sp-2); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--text); }
.btns { display: flex; gap: var(--sp-2); margin-top: var(--sp-4); }
.primary { background: var(--accent); color: #fff; border: none; border-radius: var(--radius-sm); padding: var(--sp-2) var(--sp-4); cursor: pointer; }
.ghost { background: var(--bg-soft); color: var(--text); border: 1px solid var(--border); border-radius: var(--radius-sm); padding: var(--sp-2) var(--sp-4); cursor: pointer; }
.primary:disabled, .ghost:disabled { opacity: .5; cursor: not-allowed; }
.batch { display: flex; justify-content: space-between; padding: var(--sp-1) 0; font-size: 13px; }
.traces { display: flex; flex-direction: column; gap: var(--sp-1); }
.tl { display: flex; gap: var(--sp-2); font-size: 12px; }
.seq { color: var(--accent); font-family: var(--mono); }
.muted { color: var(--text-faint); font-size: 13px; }
.idle { padding: var(--sp-4); }
@media (max-width: 980px) { .grid { grid-template-columns: 1fr; } }
</style>
