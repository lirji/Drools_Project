<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getDetail } from '../activityApi'
import { useDictStore } from '@/stores/useDictStore'
import { isoToLocal } from '../logic'
import { errText } from '@/shared/apiClient'
import Card from '@/shared/ui/Card.vue'
import Kv from '@/shared/ui/Kv.vue'
import Skeleton from '@/shared/ui/Skeleton.vue'
import Banner from '@/shared/ui/Banner.vue'
import PageHeader from '@/shared/ui/PageHeader.vue'
import Button from '@/shared/ui/Button.vue'
import Badge from '@/shared/ui/Badge.vue'
import WindowBar from '@/shared/viz/WindowBar.vue'
import Receipt from '@/shared/viz/Receipt.vue'
import Seam from '@/shared/viz/Seam.vue'
import { parseLadder } from '../logic'
import Icon from '@/shared/ui/Icon.vue'

const route = useRoute()
const dict = useDictStore()
const id = computed(() => String(route.params.id || ''))

const detail = ref<Record<string, any> | null>(null)
const loading = ref(false)
const err = ref('')
let ctrl: AbortController | null = null
let loadSequence = 0

const manage = computed(() => detail.value?.manage || null)
const rule = computed(() => detail.value?.rules?.[0] || null)
const condition = computed(() => detail.value?.conditions?.[0] || null)
const bindings = computed<Record<string, any>[]>(() => detail.value?.bindings || [])
const conditionTree = computed(() => prettyCode(condition.value?.conditionTreeJson))

function typeLabel(code: number): string {
  return dict.cache['__default__']?.activityTypes.find((item) => item.code === code)?.label ?? String(code)
}

/** 阶梯档位 → 票据行。命中判定留给决策沙盘，这里只做展示。 */
const ladderLines = computed(() => {
  const json = rule.value?.redPackageRangeAmount
  if (!json) return []
  return parseLadder(String(json)).map((t) => ({
    label: t.max === '' || t.max == null ? `满 ${t.min} 以上` : `满 ${t.min} 至 ${t.max}`,
    amount: t.reward,
  }))
})

/** 生效窗是否已过——决定甘特条用「已结束」的灰斜纹还是实心。 */
const windowEnded = computed(() => {
  const end = manage.value?.activityEndTime
  return end ? new Date(end as string).getTime() < Date.now() : false
})

function statusLabel(code: number): string {
  return dict.cache['__default__']?.statuses.find((item) => item.code === code)?.label ?? String(code)
}

function money(value: unknown): string {
  const number = Number(value)
  return Number.isNaN(number) ? '-' : number.toFixed(2)
}

function prettyCode(value: unknown): string {
  if (!value) return ''
  if (typeof value !== 'string') return JSON.stringify(value, null, 2)
  try { return JSON.stringify(JSON.parse(value), null, 2) } catch { return value }
}

async function load(): Promise<void> {
  const sequence = ++loadSequence
  loading.value = true
  err.value = ''
  detail.value = null
  ctrl?.abort()
  const controller = new AbortController()
  ctrl = controller
  try {
    await dict.load()
    const response = await getDetail(id.value, controller.signal)
    if (sequence !== loadSequence) return
    if (!response.ok) {
      err.value = errText(response)
      return
    }
    detail.value = response.json as Record<string, any>
  } catch (error) {
    if (sequence === loadSequence && (error as Error).name !== 'AbortError') err.value = (error as Error).message
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

watch(id, load, { immediate: true })
onUnmounted(() => {
  ctrl?.abort()
  loadSequence += 1
})
</script>

<template>
  <section data-testid="detail-view">
    <PageHeader
      :title="manage?.activityName || '活动详情'"
      :subtitle="manage ? `${typeLabel(manage.activityType)} · ${manage.bizLine || '未分类业务线'}` : '查看活动配置、资格条件与商品绑定'"
      :breadcrumb="[{ label: '控制台' }, { label: '活动列表', to: { name: 'activities' } }, { label: '详情' }]"
    >
      <template #actions>
        <Button variant="ghost" :to="{ name: 'activities' }"><Icon name="arrow-left" :size="15" /> 返回列表</Button>
        <Button v-if="manage" variant="primary" :to="{ name: 'activity-edit', params: { id } }"><Icon name="code" :size="15" /> 编辑活动</Button>
      </template>
    </PageHeader>

    <Skeleton v-if="loading" :rows="7" />
    <Banner v-else-if="err" kind="err" role="alert" class="detail-error">
      <strong>活动详情加载失败</strong><span>{{ err }}</span><button type="button" @click="load">重新加载</button>
    </Banner>
    <template v-else-if="manage">
      <section class="activity-hero" data-testid="detail-loaded">
        <div class="hero-main">
          <span class="hero-icon"><Icon name="badge-check" :size="22" /></span>
          <div>
            <div class="hero-status">
              <Badge :kind="manage.activityStatus === 1 ? 'ok' : (manage.activityStatus === 3 ? 'blue' : 'neutral')"
                     :shape="manage.activityStatus === 1 ? 'dot' : (manage.activityStatus === 3 ? 'triangle' : (manage.activityStatus === 2 ? 'hatch' : 'square'))">{{ statusLabel(manage.activityStatus) }}</Badge>
              <span class="version">VERSION {{ manage.version }}</span>
            </div>
            <h2>{{ manage.activityName }}</h2>
            <code>{{ manage.activityId }}</code>
          </div>
        </div>
        <div class="hero-timeline">
          <span><small>生效时间</small><strong>{{ isoToLocal(manage.activityStartTime) }}</strong></span>
          <Icon name="arrow-right" :size="15" />
          <span><small>结束时间</small><strong>{{ isoToLocal(manage.activityEndTime) }}</strong></span>
        </div>
        <!-- 共享时间轴：不读两个日期就知道「还剩几天 / 几天后开跑 / 已经跑完」 -->
        <div class="hero-window">
          <WindowBar :start="manage.activityStartTime" :end="manage.activityEndTime"
                     :muted="manage.activityStatus === 2"
                     :state="manage.activityStatus === 3 ? 'warmup' : (windowEnded ? 'ended' : 'live')" />
        </div>
      </section>

      <div class="summary-grid" aria-label="活动关键指标">
        <article><span><Icon name="workflow" :size="17" /></span><div><small>业务线</small><strong>{{ manage.bizLine || '-' }}</strong></div></article>
        <article><span><Icon name="layers" :size="17" /></span><div><small>活动类型</small><strong>{{ typeLabel(manage.activityType) }}</strong></div></article>
        <article><span><Icon name="gauge" :size="17" /></span><div><small>优先级</small><strong>{{ manage.priority }}</strong></div></article>
        <article><span><Icon name="inbox" :size="17" /></span><div><small>可用库存</small><strong>{{ manage.inventory }}</strong></div></article>
      </div>

      <div class="detail-grid">
        <div class="main-column">
          <Card title="优惠配置">
            <div v-if="rule" class="benefit-card">
              <span class="benefit-icon"><Icon :name="manage.activityType === 1 ? 'badge-check' : 'inbox'" :size="21" /></span>
              <div v-if="rule.redPackageRangeAmount" class="ladder-block">
                <small>阶梯红包规则</small><strong>按订单金额分档计算</strong>
                <Seam />
                <!-- 票据式排版：小数点对齐后，档位之间的量级差是"看"出来的 -->
                <Receipt :lines="ladderLines" />
              </div>
              <!-- 折扣型：redPackageAmount 是**折数**不是钱。按金额渲染的话，
                   「打 8 折」会显示成「8 元」——运营据此复核就会以为配错了（或者更糟，以为配对了） -->
              <div v-else-if="rule.redPackageAmountUnit === '折'" data-testid="detail-ratio">
                <small>折扣优惠</small>
                <strong class="amount">{{ rule.redPackageAmount }} <i>折</i></strong>
                <span>最多减 {{ money(rule.redPackageMaxDiscount) }} 元 · 减免向下取整到分</span>
              </div>
              <div v-else>
                <small>固定优惠金额</small><strong class="amount">{{ money(rule.redPackageAmount) }} <i>{{ rule.redPackageAmountUnit || '元' }}</i></strong>
                <span>领取方式 {{ rule.redPackageTakeType || '-' }}</span>
              </div>
            </div>
            <div v-else class="muted">没有红包规则配置</div>
          </Card>

          <Card v-if="detail?.gifts?.length" title="买赠赠品">
            <div class="gift-list">
              <div v-for="(gift, index) in detail.gifts" :key="index" class="gift-row">
                <span class="gift-index">{{ index + 1 }}</span>
                <span><strong>{{ gift.giftName }}</strong><small>{{ gift.giftType || gift.rightType || '赠品' }}</small></span>
                <b>×{{ gift.giftNum }} · {{ money(gift.absoluteAmount) }}</b>
              </div>
            </div>
          </Card>

          <Card title="资格条件">
            <div class="card-note"><Icon name="info" :size="15" /> 条件由白名单字段翻译为受控 Drools，空条件表示所有用户均可参与。</div>
            <div v-if="conditionTree" class="code-panel">
              <div class="code-head"><span><i /> CONDITION TREE</span><small>JSON</small></div>
              <pre>{{ conditionTree }}</pre>
            </div>
            <div v-else class="pass-all"><Icon name="badge-check" :size="18" /><span><strong>无资格限制</strong><small>所有用户恒通过</small></span></div>
            <template v-if="condition?.generatedDrl">
              <div class="sub-label">翻译后的 Drools 约束</div>
              <div class="code-panel drl"><div class="code-head"><span><i /> GENERATED DRL</span><small>READ ONLY</small></div><pre>{{ condition.generatedDrl }}</pre></div>
            </template>
          </Card>
        </div>

        <aside class="side-column">
          <Card title="活动元数据">
            <Kv k="活动 ID" mono>{{ manage.activityId }}</Kv>
            <Kv k="版本">v{{ manage.version }}</Kv>
            <Kv k="地域">{{ manage.activityAreaType === 2 ? (manage.districtIds || '指定地域') : '全国' }}</Kv>
            <Kv v-if="manage.submittedBy" k="提交人">{{ manage.submittedBy }}</Kv>
            <Kv k="合并策略" mono>{{ manage.discountStrategy || detail?.strategy || '-' }}</Kv>
          </Card>

          <Card :title="`商品绑定 · ${bindings.length}`">
            <div v-if="bindings.length" class="binding-list">
              <div v-for="(binding, index) in bindings" :key="index" class="binding-row">
                <span><Icon name="inbox" :size="14" /></span>
                <div><strong>SPU {{ binding.spuId }}</strong><small>{{ binding.bindSource === 1 ? '商品池自动圈选' : '手动绑定' }}</small></div>
                <i :class="binding.effective === 1 ? 'effective' : 'inactive'">{{ binding.effective === 1 ? '生效' : '失效' }}</i>
              </div>
            </div>
            <div v-else class="muted">没有绑定商品</div>
          </Card>

          <div class="next-action">
            <span><Icon name="info" :size="16" /></span>
            <div><strong>{{ manage.activityStatus === 1 ? '活动正在参与线上决策' : '活动当前不会参与决策' }}</strong><p>{{ manage.activityStatus === 1 ? '如需调整配置，请编辑生成新版本后重新上线。' : '确认配置无误后，可回到列表执行上线。' }}</p></div>
          </div>
        </aside>
      </div>
    </template>
  </section>
</template>

<style scoped>
/* 甘特条挂在 hero 里，占满一行 */
.hero-window { grid-column: 1 / -1; margin-top: var(--gap-inline); }
/* 撕线的缺口底色必须等于「卡片背后那一层」——券面是白的，背后是页底 */
.ladder-block { --notch-bg: var(--bg-elev); }

.detail-error { display: flex; flex-direction: column; align-items: flex-start; gap: var(--sp-1); padding: var(--sp-4); }.detail-error span { font-size: var(--fs-xs); }.detail-error button { margin-top: var(--sp-1); padding: var(--sp-1) var(--sp-3); border: 1px solid currentColor; border-radius: var(--radius-sm); background: transparent; color: inherit; cursor: pointer; }
.activity-hero { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-5); margin-bottom: var(--sp-4); padding: var(--sp-5); border: 1px solid var(--border); border-radius: var(--radius-lg); background: linear-gradient(110deg, var(--bg-elev), color-mix(in srgb, var(--accent-soft) 68%, var(--bg-elev))); box-shadow: var(--shadow-sm); }
.hero-main { display: flex; align-items: center; gap: var(--sp-3); min-width: 0; }.hero-icon { display: inline-flex; align-items: center; justify-content: center; flex: 0 0 auto; width: 48px; height: 48px; border-radius: 14px; background: var(--accent); color: var(--text-invert); box-shadow: 0 8px 20px color-mix(in srgb, var(--accent) 22%, transparent); }.hero-status { display: flex; align-items: center; gap: var(--sp-2); }.hero-status :deep(.badge) i { display: inline-block; width: 6px; height: 6px; margin-right: 3px; border-radius: 50%; background: currentColor; }.version { color: var(--text-faint); font-family: var(--mono); font-size: 9px; letter-spacing: .08em; }.hero-main h2 { overflow: hidden; margin: 5px 0 1px; font-size: var(--fs-xl); text-overflow: ellipsis; white-space: nowrap; }.hero-main code { color: var(--text-faint); font-size: 10px; }
.hero-timeline { display: flex; align-items: center; gap: var(--sp-3); flex: 0 0 auto; }.hero-timeline span { display: flex; flex-direction: column; }.hero-timeline small { color: var(--text-faint); font-size: 9px; }.hero-timeline strong { margin-top: 2px; font-size: 10px; font-variant-numeric: tabular-nums; }.hero-timeline :deep(svg) { color: var(--text-faint); }
.summary-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--sp-3); margin-bottom: var(--sp-4); }.summary-grid article { display: flex; align-items: center; gap: var(--sp-3); padding: var(--sp-3) var(--sp-4); border: 1px solid var(--border); border-radius: var(--radius-lg); background: var(--bg-elev); }.summary-grid article > span { display: inline-flex; padding: var(--sp-2); border-radius: 9px; background: var(--bg-soft); color: var(--accent); }.summary-grid small, .summary-grid strong { display: block; }.summary-grid small { color: var(--text-faint); font-size: 9px; }.summary-grid strong { overflow: hidden; max-width: 140px; margin-top: 1px; font-size: var(--fs-sm); text-overflow: ellipsis; white-space: nowrap; }
.detail-grid { display: grid; grid-template-columns: minmax(0, 1.55fr) minmax(280px, .75fr); gap: var(--sp-4); align-items: start; }.main-column, .side-column { min-width: 0; }.side-column { position: sticky; top: var(--sp-4); }
.benefit-card { display: flex; align-items: center; gap: var(--sp-4); padding: var(--sp-4); border: 1px solid var(--accent-line); border-radius: var(--radius-sm); background: var(--accent-soft); }.benefit-icon { display: inline-flex; align-items: center; justify-content: center; width: 42px; height: 42px; border-radius: 12px; background: var(--bg-elev); color: var(--accent); }.benefit-card div { min-width: 0; }.benefit-card small, .benefit-card strong, .benefit-card span, .benefit-card code { display: block; }.benefit-card small { color: var(--text-faint); font-size: 9px; }.benefit-card strong { margin-top: 2px; font-size: var(--fs-sm); }.benefit-card .amount { color: var(--accent); font-size: 24px; font-variant-numeric: tabular-nums; }.benefit-card .amount i { font-size: 11px; font-style: normal; }.benefit-card span, .benefit-card code { overflow: hidden; margin-top: 3px; color: var(--text-soft); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.gift-list, .binding-list { display: flex; flex-direction: column; }.gift-row { display: grid; grid-template-columns: auto 1fr auto; align-items: center; gap: var(--sp-3); padding: var(--sp-2) 0; border-bottom: 1px solid var(--border); }.gift-row:last-child { border-bottom: 0; }.gift-index { display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; border-radius: 8px; background: var(--bg-soft); color: var(--text-faint); font-size: 10px; font-variant-numeric: tabular-nums; }.gift-row strong, .gift-row small { display: block; }.gift-row strong { font-size: var(--fs-xs); }.gift-row small { color: var(--text-faint); font-size: 9px; }.gift-row b { color: var(--text-soft); font-size: 10px; font-variant-numeric: tabular-nums; }
.card-note { display: flex; align-items: flex-start; gap: var(--sp-2); margin-bottom: var(--sp-3); color: var(--text-soft); font-size: 10px; line-height: 1.6; }.code-panel { overflow: hidden; border: 1px solid color-mix(in srgb, var(--on-deep) 14%, transparent); border-radius: var(--radius-sm); background: var(--surface-deep); }.code-head { display: flex; align-items: center; justify-content: space-between; padding: var(--sp-2) var(--sp-3); border-bottom: 1px solid color-mix(in srgb, var(--on-deep) 14%, transparent); background: var(--surface-deep-2); color: var(--on-deep-faint); font-family: var(--mono); font-size: 9px; }.code-head span { display: inline-flex; align-items: center; gap: var(--sp-2); }.code-head i { width: 7px; height: 7px; border-radius: 50%; background: var(--ok); }.code-head small { font-size: 8px; }.code-panel pre { max-height: 360px; overflow: auto; margin: 0; padding: var(--sp-3); color: var(--on-deep); font-family: var(--mono); font-size: 10px; line-height: 1.65; white-space: pre-wrap; word-break: break-word; }.code-panel.drl { margin-top: var(--sp-2); }.sub-label { margin-top: var(--sp-4); color: var(--text-soft); font-size: 10px; font-weight: var(--fw-semibold); }.pass-all { display: flex; align-items: center; gap: var(--sp-2); padding: var(--sp-3); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--green-soft); color: var(--green); }.pass-all strong, .pass-all small { display: block; }.pass-all strong { font-size: var(--fs-xs); }.pass-all small { font-size: 9px; }
.binding-row { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: var(--sp-2); padding: var(--sp-2) 0; border-bottom: 1px solid var(--border); }.binding-row:last-child { border-bottom: 0; }.binding-row > span { display: inline-flex; padding: 6px; border-radius: 7px; background: var(--bg-soft); color: var(--text-faint); }.binding-row strong, .binding-row small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.binding-row strong { font-size: 10px; }.binding-row small { color: var(--text-faint); font-size: 8px; }.binding-row > i { padding: 3px 6px; border-radius: var(--radius-pill); font-size: 8px; font-style: normal; }.binding-row > i.effective { background: var(--green-soft); color: var(--green); }.binding-row > i.inactive { background: var(--red-soft); color: var(--red); }
.next-action { display: grid; grid-template-columns: auto 1fr; gap: var(--sp-2); padding: var(--sp-3); border: 1px solid var(--accent-line); border-radius: var(--radius-lg); background: var(--accent-soft); color: var(--accent); }.next-action > span { display: inline-flex; }.next-action strong { color: var(--text); font-size: 10px; }.next-action p { margin: 2px 0 0; color: var(--text-soft); font-size: 9px; line-height: 1.5; }.muted { color: var(--text-faint); font-size: var(--fs-xs); }
@media (max-width: 1180px) { .activity-hero { align-items: flex-start; flex-direction: column; }.summary-grid { grid-template-columns: repeat(2, 1fr); }.detail-grid { grid-template-columns: 1fr; }.side-column { position: static; } }
@media (max-width: 560px) { .activity-hero { padding: var(--sp-4); }.hero-timeline { width: 100%; align-items: flex-start; flex-direction: column; }.hero-timeline :deep(svg) { display: none; }.summary-grid { grid-template-columns: 1fr 1fr; }.summary-grid article { padding: var(--sp-3); }.benefit-card { align-items: flex-start; }.gift-row { grid-template-columns: auto 1fr; }.gift-row > b { grid-column: 2; } }
</style>
