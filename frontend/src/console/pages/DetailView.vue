<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getDetail } from '../activityApi'
import { useDictStore } from '@/stores/useDictStore'
import { isoToLocal } from '../logic'
import { errText } from '@/shared/apiClient'
import Card from '@/shared/ui/Card.vue'
import Kv from '@/shared/ui/Kv.vue'
import Skeleton from '@/shared/ui/Skeleton.vue'
import Banner from '@/shared/ui/Banner.vue'

const route = useRoute()
const router = useRouter()
const dict = useDictStore()
const id = route.params.id as string

const d = ref<Record<string, any> | null>(null)
const loading = ref(false)
const err = ref('')
let ctrl: AbortController | null = null

function typeLabel(c: number): string { return dict.cache['__default__']?.activityTypes.find((t) => t.code === c)?.label ?? String(c) }
function statusLabel(c: number): string { return dict.cache['__default__']?.statuses.find((s) => s.code === c)?.label ?? String(c) }
function money(v: unknown): string { const n = Number(v); return isNaN(n) ? '-' : n.toFixed(2) }

async function load(): Promise<void> {
  loading.value = true; err.value = ''
  ctrl?.abort(); ctrl = new AbortController()
  try {
    await dict.load()
    const r = await getDetail(id, ctrl.signal)
    if (!r.ok) { err.value = errText(r); return }
    d.value = r.json as Record<string, any>
  } catch (e) {
    if ((e as Error).name !== 'AbortError') err.value = (e as Error).message
  } finally { loading.value = false }
}
onMounted(load)
onUnmounted(() => ctrl?.abort())
</script>

<template>
  <section data-testid="detail-view">
    <div class="bar">
      <button class="ghost" @click="router.push({ name: 'activities' })">← 返回列表</button>
      <button class="ghost" @click="router.push({ name: 'activity-edit', params: { id } })">编辑</button>
    </div>
    <Skeleton v-if="loading" :rows="6" />
    <Banner v-else-if="err" kind="err">{{ err }}</Banner>
    <div v-else-if="d" class="grid">
      <div>
        <Card :title="'基础信息 · ' + d.manage.activityId + ' (v' + d.manage.version + ')'">
          <Kv k="名称">{{ d.manage.activityName }}</Kv>
          <Kv k="类型">{{ typeLabel(d.manage.activityType) }}</Kv>
          <Kv k="业务线">{{ d.manage.bizLine || '-' }}</Kv>
          <Kv k="状态">{{ statusLabel(d.manage.activityStatus) }}</Kv>
          <Kv k="时间">{{ isoToLocal(d.manage.activityStartTime) }} ~ {{ isoToLocal(d.manage.activityEndTime) }}</Kv>
          <Kv k="优先级">{{ d.manage.priority }}</Kv>
          <Kv k="库存">{{ d.manage.inventory }}</Kv>
          <Kv v-if="d.manage.submittedBy" k="提交人">{{ d.manage.submittedBy }}</Kv>
        </Card>
        <Card v-if="d.rules && d.rules[0]" title="红包规则">
          <Kv v-if="d.rules[0].redPackageRangeAmount" k="阶梯" mono>{{ d.rules[0].redPackageRangeAmount }}</Kv>
          <Kv v-else k="固定金额">{{ money(d.rules[0].redPackageAmount) }} {{ d.rules[0].redPackageAmountUnit || '元' }}</Kv>
        </Card>
        <Card v-if="d.gifts && d.gifts.length" title="买赠赠品">
          <div v-for="(g, i) in d.gifts" :key="i" class="batch"><span>{{ g.giftName }}</span><span>×{{ g.giftNum }} · {{ money(g.absoluteAmount) }}</span></div>
        </Card>
      </div>
      <div>
        <Card title="资格条件">
          <div v-if="d.conditions && d.conditions[0] && d.conditions[0].conditionTreeJson" class="mono-box">{{ d.conditions[0].conditionTreeJson }}</div>
          <div v-else class="muted">无 (恒通过)</div>
          <template v-if="d.conditions && d.conditions[0] && d.conditions[0].generatedDrl">
            <div class="sub-label">翻译后的 Drools 约束</div>
            <div class="mono-box">{{ d.conditions[0].generatedDrl }}</div>
          </template>
        </Card>
        <Card :title="'商品绑定 (' + (d.bindings ? d.bindings.length : 0) + ')'">
          <div class="tags">
            <template v-if="d.bindings && d.bindings.length">
              <span v-for="(b, i) in d.bindings" :key="i" class="tag" :class="b.effective === 1 ? 'green' : 'red'">spu{{ b.spuId }}{{ b.bindSource === 1 ? '(池)' : '' }}</span>
            </template>
            <span v-else class="muted">无</span>
          </div>
        </Card>
      </div>
    </div>
  </section>
</template>

<style scoped>
.bar { display: flex; gap: var(--sp-2); margin-bottom: var(--sp-3); }
.ghost { border: 1px solid var(--border); background: var(--bg-soft); color: var(--text); border-radius: var(--radius-sm); padding: var(--sp-2) var(--sp-3); cursor: pointer; }
.grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-4); }
.batch { display: flex; justify-content: space-between; padding: var(--sp-1) 0; font-size: 13px; }
.mono-box { font-family: var(--mono); font-size: 12px; background: var(--bg-soft); border: 1px solid var(--border); border-radius: var(--radius-sm); padding: var(--sp-2); white-space: pre-wrap; word-break: break-all; margin: var(--sp-1) 0; }
.sub-label { font-size: 12px; color: var(--text-soft); margin-top: var(--sp-2); }
.muted { color: var(--text-faint); font-size: 13px; }
.tags { display: flex; flex-wrap: wrap; gap: var(--sp-1); }
.tag { font-size: 12px; padding: 2px var(--sp-2); border-radius: var(--radius-sm); }
.tag.green { background: var(--green-soft); color: var(--green); }
.tag.red { background: var(--red-soft); color: var(--red); }
@media (max-width: 980px) { .grid { grid-template-columns: 1fr; } }
</style>
