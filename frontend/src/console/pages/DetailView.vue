<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getDetail } from '../activityApi'
import { useDictStore } from '@/stores/useDictStore'
import { useDistrictStore } from '@/stores/useDistrictStore'
import { buildIndex, labelOf, parseCodes, pathOf } from '../district/districtLogic'
import { benefitFormOf, isoToLocal, parseLadder, parseNth, parseRandomRange, type BenefitForm } from '../logic'
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
import Icon from '@/shared/ui/Icon.vue'
import EmptyState from '@/shared/ui/EmptyState.vue'

const route = useRoute()
const dict = useDictStore()
const districtStore = useDistrictStore()
const id = computed(() => String(route.params.id || ''))

const detail = ref<Record<string, any> | null>(null)
const loading = ref(false)
const err = ref('')
let ctrl: AbortController | null = null
let loadSequence = 0

const manage = computed(() => detail.value?.manage || null)

/**
 * 地域回显：把裸码翻成中文。
 *
 * <p>字典自己在这个页面独立拉（EditorView 那份是 prop 下发的，这里够不着），
 * 且**拿不到字典时回退成裸码而不是空白**——运营宁可看见一串数字，也不能看见一个空格子
 * 然后以为这个活动没配地域。
 *
 * <p>另外修掉一处误导：原来 `districtIds` 为空时回显「指定地域」，
 * 而那正是「areaType=2 却一个地域都没选」的空投放状态，看着却像配好了。
 */
const districtIndex = computed(() => buildIndex(districtStore.items))
const districtCodes = computed(() => parseCodes(manage.value?.districtIds))
const districtLabel = computed(() => {
  if (!manage.value) return '-'
  if (manage.value.activityAreaType !== 2) return '全国'
  if (!districtCodes.value.length) return '未选择地域（等同不投放）'
  const names = districtCodes.value.map((c) => labelOf(districtIndex.value, c))
  return names.length <= 4 ? names.join('、') : `${names.slice(0, 4).join('、')} 等 ${names.length} 个`
})
/** 悬停给全路径，省得为了看清是哪个「鼓楼区」还要跳去编辑页。 */
const districtTitle = computed(() =>
  districtCodes.value.map((c) => pathOf(districtIndex.value, c)).join('\n'))
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

/** 编辑器与复核屏共用同一判别函数；页面只负责渲染结果。 */
const benefitShape = computed(() => benefitFormOf(rule.value))
const benefitForm = computed(() => benefitShape.value.form)

const BENEFIT_LABELS: Record<BenefitForm, string> = {
  fixed: '固定金额', random: '随机金额', ladder: '阶梯分档', ratio: '折扣', price: '一口价', nth: '第 N 件折',
}

const isAddOn = computed(() => manage.value?.activityType === 6)
const nthValue = computed(() => parseNth(rule.value?.redPackageRangeAmount as string | null))
const randomRange = computed(() =>
  benefitForm.value === 'random'
    ? parseRandomRange(rule.value?.redPackageRangeAmount as string | null)
    : null)
const benefitLabel = computed(() => manage.value?.activityType === 6
  ? '加价购'
  : manage.value?.activityType === 5
    ? '买赠'
    : BENEFIT_LABELS[benefitForm.value])

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
    // 只有「指定地域」的活动才需要字典。拿不到就算了——districtLabel 会回退成裸码，
    // 不该因为一个展示增强让详情页整页报错。
    if ((detail.value?.manage?.activityAreaType) === 2 && !districtStore.items) {
      void districtStore.load(controller.signal)
    }
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
      :subtitle="manage ? `${typeLabel(manage.activityType)} · ${benefitLabel} · ${manage.bizLine || '未分类业务线'}` : '查看活动配置、资格条件与商品绑定'"
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
    <!-- 缺陷 F4：此前 `v-else-if="manage"` 之后没有 v-else —— manage 为空且无错时整页空白，
         用户看到的是"页面坏了"而不是"没有这个活动"。 -->
    <template v-else-if="manage">
      <section class="activity-hero" data-testid="detail-loaded">
        <div class="hero-main">
          <span class="hero-icon"><Icon name="badge-check" :size="22" /></span>
          <div>
            <div class="hero-status">
              <Badge :kind="manage.activityStatus === 1 ? 'ok' : (manage.activityStatus === 3 ? 'blue' : 'neutral')"
                     :shape="manage.activityStatus === 1 ? 'dot' : (manage.activityStatus === 3 ? 'triangle' : (manage.activityStatus === 2 ? 'hatch' : 'square'))">{{ statusLabel(manage.activityStatus) }}</Badge>
              <Badge kind="neutral" shape="square" data-testid="detail-benefit-form">{{ benefitLabel }}</Badge>
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
            <!-- 加价购**根本不落 rule 行**（写平面 saveRule 见 redPackageAmount/RangeAmount 全空即 return），
                 所以它必须在 `v-if="rule"` 之外先接住：否则这一屏会显示「没有红包规则配置」，
                 看起来像运营漏配了什么，而实际上这个玩法的优惠就长在下面的换购清单里。 -->
            <div v-if="isAddOn" class="benefit-card" data-testid="detail-addon">
              <span class="benefit-icon"><Icon name="inbox" :size="21" /></span>
              <div>
                <small>加价购</small>
                <strong>优惠形态是换购，不是减钱</strong>
                <span>可换购清单见下方「加价购换购品」</span>
              </div>
            </div>
            <div v-else-if="rule" class="benefit-card">
              <span class="benefit-icon"><Icon :name="manage.activityType === 1 ? 'badge-check' : 'inbox'" :size="21" /></span>
              <!-- 分支顺序按**判别位**来（见 benefitForm）：先认形态，再谈怎么画。 -->
              <div v-if="benefitForm === 'ladder'" class="ladder-block">
                <small>阶梯红包规则</small><strong>按订单金额分档计算</strong>
                <span v-if="!benefitShape.parsed" class="warn-line">档位数据损坏，请在编辑器中重填后再上线</span>
                <Seam v-else />
                <!-- 票据式排版：小数点对齐后，档位之间的量级差是"看"出来的 -->
                <Receipt v-if="benefitShape.parsed" :lines="ladderLines" />
              </div>
              <!-- 折扣型：redPackageAmount 是**折数**不是钱。按金额渲染的话，
                   「打 8 折」会显示成「8 元」——运营据此复核就会以为配错了（或者更糟，以为配对了） -->
              <div v-else-if="benefitForm === 'ratio'" data-testid="detail-ratio">
                <small>折扣优惠</small>
                <strong class="amount">{{ rule.redPackageAmount }} <i>折</i></strong>
                <span>最多减 {{ money(rule.redPackageMaxDiscount) }} 元 · 减免向下取整到分</span>
              </div>
              <!-- 一口价：这个数字是「卖多少」，不是「减多少」。两者在复核屏上必须一眼可辨，
                   否则「9.9」既可能是一件 9.9 元的秒杀，也可能是一张减 9.9 元的券 -->
              <div v-else-if="benefitForm === 'price'" data-testid="detail-price">
                <small>一口价（秒杀）</small>
                <strong class="amount">{{ money(rule.redPackageAmount) }} <i>元/件</i></strong>
                <span>不管原价多少就卖这个数 · 减免 = 订单金额 − 一口价</span>
              </div>
              <div v-else-if="benefitForm === 'nth'" data-testid="detail-nth">
                <small>第 N 件折</small>
                <strong class="amount">第 {{ nthValue ?? '?' }} 件 <i>{{ rule.redPackageAmount }} 折</i></strong>
                <span v-if="nthValue">按同款逐行计算 · 调用方须传订单行，否则本活动不适用</span>
                <span v-else class="warn-line">第几件（nth）缺失或非法，决策侧会判定本活动不适用</span>
              </div>
              <div v-else-if="benefitForm === 'random'" data-testid="detail-random">
                <small>随机金额红包</small>
                <strong v-if="randomRange" class="amount">{{ money(randomRange.min) }} ~ {{ money(randomRange.max) }} <i>元</i></strong>
                <strong v-else class="amount">? ~ ? <i>元</i></strong>
                <span v-if="randomRange">确定性随机：同一用户同一购物车金额固定，刷新不变价</span>
                <span v-else class="warn-line">随机区间数据损坏，请在编辑器中重填后再上线</span>
              </div>
              <div v-else>
                <small>固定优惠金额</small><strong class="amount">{{ money(rule.redPackageAmount) }} <i>元</i></strong>
                <span v-if="benefitShape.parsed">固定金额发放</span>
                <span v-else class="warn-line">权益单位无法识别，请在编辑器中复核后再上线</span>
              </div>
            </div>
            <!-- 买赠同样不落 rule 行——它的优惠长在下面的赠品清单里，这里不该说「没配红包」误导运营 -->
            <div v-else-if="manage.activityType === 5" class="muted">买赠活动：优惠内容见下方赠品清单，不配置红包规则</div>
            <div v-else class="muted">没有红包规则配置</div>
          </Card>

          <!-- 加价购与买赠共用这张表，但金额列含义不同：买赠是赠品价值，加价购是**加多少钱换购**。
               复核屏上必须写清楚——「9.90」在两种活动下是完全相反的现金流向。 -->
          <Card v-if="detail?.gifts?.length" :title="isAddOn ? '加价购换购品' : '买赠赠品'">
            <div class="card-note" v-if="isAddOn">
              <Icon name="info" :size="15" /> 金额是<b>加价额</b>（用户再付这些钱换购），不是赠品价值；第二阶段按品名匹配选项。
            </div>
            <div class="gift-list">
              <div v-for="(gift, index) in detail.gifts" :key="index" class="gift-row" data-testid="detail-gift-row">
                <span class="gift-index">{{ index + 1 }}</span>
                <span><strong>{{ gift.giftName }}</strong><small>{{ gift.giftType || gift.rightType || '赠品' }}</small></span>
                <b>×{{ gift.giftNum }} · {{ isAddOn ? '加 ' : '' }}{{ money(gift.absoluteAmount) }} 元</b>
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
            <Kv k="地域" :title="districtTitle">{{ districtLabel }}</Kv>
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
    <EmptyState
      v-else
      icon="inbox"
      title="没有找到这个活动"
      hint="它可能已被删除，或不属于当前租户。"
    >
      <template #action>
        <Button variant="primary" :to="{ name: 'activities' }">
          <Icon name="list" :size="16" /><span>返回活动列表</span>
        </Button>
      </template>
    </EmptyState>
  </section>
</template>

<style scoped>
/* 甘特条挂在 hero 里，占满一行 */
.hero-window { grid-column: 1 / -1; margin-top: var(--gap-inline); }
/* 撕线的缺口底色必须等于「卡片背后那一层」——券面是白的，背后是页底 */
.ladder-block { --notch-bg: var(--bg-elev); }

.detail-error { display: flex; flex-direction: column; align-items: flex-start; gap: var(--sp-1); padding: var(--sp-4); }.detail-error span { font-size: var(--fs-xs); }.detail-error button { margin-top: var(--sp-1); padding: var(--sp-1) var(--sp-3); border: 1px solid currentColor; border-radius: var(--radius-sm); background: transparent; color: inherit; cursor: pointer; }
.activity-hero { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-5); margin-bottom: var(--sp-4); padding: var(--sp-5); border: 1px solid var(--border); border-radius: var(--radius-lg); background: linear-gradient(110deg, var(--bg-elev), color-mix(in srgb, var(--accent-soft) 68%, var(--bg-elev))); box-shadow: var(--shadow-sm); }
.hero-main { display: flex; align-items: center; gap: var(--sp-3); min-width: 0; }.hero-icon { display: inline-flex; align-items: center; justify-content: center; flex: 0 0 auto; width: 48px; height: 48px; border-radius: 14px; background: var(--accent); color: var(--text-invert); box-shadow: 0 8px 20px color-mix(in srgb, var(--accent) 22%, transparent); }.hero-status { display: flex; align-items: center; gap: var(--sp-2); }.hero-status :deep(.badge) i { display: inline-block; width: 6px; height: 6px; margin-right: 3px; border-radius: 50%; background: currentColor; }.version { color: var(--text-faint); font-family: var(--mono); font-size: var(--fs-2xs); letter-spacing: .08em; }.hero-main h2 { overflow: hidden; margin: 5px 0 1px; font-size: var(--fs-xl); text-overflow: ellipsis; white-space: nowrap; }.hero-main code { color: var(--text-faint); font-size: var(--fs-xs); }
.hero-timeline { display: flex; align-items: center; gap: var(--sp-3); flex: 0 0 auto; }.hero-timeline span { display: flex; flex-direction: column; }.hero-timeline small { color: var(--text-faint); font-size: var(--fs-2xs); }.hero-timeline strong { margin-top: 2px; font-size: var(--fs-xs); font-variant-numeric: tabular-nums; }.hero-timeline :deep(svg) { color: var(--text-faint); }
.summary-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--sp-3); margin-bottom: var(--sp-4); }.summary-grid article { display: flex; align-items: center; gap: var(--sp-3); padding: var(--sp-3) var(--sp-4); border: 1px solid var(--border); border-radius: var(--radius-lg); background: var(--bg-elev); }.summary-grid article > span { display: inline-flex; padding: var(--sp-2); border-radius: 9px; background: var(--bg-soft); color: var(--accent); }.summary-grid small, .summary-grid strong { display: block; }.summary-grid small { color: var(--text-faint); font-size: var(--fs-2xs); }.summary-grid strong { overflow: hidden; max-width: 140px; margin-top: 1px; font-size: var(--fs-sm); text-overflow: ellipsis; white-space: nowrap; }
.detail-grid { display: grid; grid-template-columns: minmax(0, 1.55fr) minmax(280px, .75fr); gap: var(--sp-4); align-items: start; }.main-column, .side-column { min-width: 0; }.side-column { position: sticky; top: var(--sp-4); }
.benefit-card { display: flex; align-items: center; gap: var(--sp-4); padding: var(--sp-4); border: 1px solid var(--accent-line); border-radius: var(--radius-sm); background: var(--accent-soft); }.benefit-icon { display: inline-flex; align-items: center; justify-content: center; width: 42px; height: 42px; border-radius: 12px; background: var(--bg-elev); color: var(--accent); }.benefit-card div { min-width: 0; }.benefit-card small, .benefit-card strong, .benefit-card span, .benefit-card code { display: block; }.benefit-card small { color: var(--text-faint); font-size: var(--fs-2xs); }.benefit-card strong { margin-top: 2px; font-size: var(--fs-sm); }.benefit-card .amount { color: var(--accent); font-size: 24px; font-variant-numeric: tabular-nums; }.benefit-card .amount i { font-size: 11px; font-style: normal; }.benefit-card span, .benefit-card code { overflow: hidden; margin-top: 3px; color: var(--text-soft); font-size: var(--fs-xs); text-overflow: ellipsis; white-space: nowrap; }
/* 配置残缺的说明必须比正常说明显眼：它讲的是「这个活动上线了但不会生效」 */
.benefit-card .warn-line { color: var(--warn); white-space: normal; }
.gift-list, .binding-list { display: flex; flex-direction: column; }.gift-row { display: grid; grid-template-columns: auto 1fr auto; align-items: center; gap: var(--sp-3); padding: var(--sp-2) 0; border-bottom: 1px solid var(--border); }.gift-row:last-child { border-bottom: 0; }.gift-index { display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; border-radius: 8px; background: var(--bg-soft); color: var(--text-faint); font-size: var(--fs-xs); font-variant-numeric: tabular-nums; }.gift-row strong, .gift-row small { display: block; }.gift-row strong { font-size: var(--fs-xs); }.gift-row small { color: var(--text-faint); font-size: var(--fs-2xs); }.gift-row b { color: var(--text-soft); font-size: var(--fs-xs); font-variant-numeric: tabular-nums; }
.card-note { display: flex; align-items: flex-start; gap: var(--sp-2); margin-bottom: var(--sp-3); color: var(--text-soft); font-size: var(--fs-xs); line-height: 1.6; }.code-panel { overflow: hidden; border: 1px solid color-mix(in srgb, var(--on-deep) 14%, transparent); border-radius: var(--radius-sm); background: var(--surface-deep); }.code-head { display: flex; align-items: center; justify-content: space-between; padding: var(--sp-2) var(--sp-3); border-bottom: 1px solid color-mix(in srgb, var(--on-deep) 14%, transparent); background: var(--surface-deep-2); color: var(--on-deep-faint); font-family: var(--mono); font-size: var(--fs-2xs); }.code-head span { display: inline-flex; align-items: center; gap: var(--sp-2); }.code-head i { width: 7px; height: 7px; border-radius: 50%; background: var(--ok); }.code-head small { font-size: var(--fs-2xs); }.code-panel pre { max-height: 360px; overflow: auto; margin: 0; padding: var(--sp-3); color: var(--on-deep); font-family: var(--mono); font-size: var(--fs-xs); line-height: 1.65; white-space: pre-wrap; word-break: break-word; }.code-panel.drl { margin-top: var(--sp-2); }.sub-label { margin-top: var(--sp-4); color: var(--text-soft); font-size: var(--fs-xs); font-weight: var(--fw-semibold); }.pass-all { display: flex; align-items: center; gap: var(--sp-2); padding: var(--sp-3); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--green-soft); color: var(--green); }.pass-all strong, .pass-all small { display: block; }.pass-all strong { font-size: var(--fs-xs); }.pass-all small { font-size: var(--fs-2xs); }
.binding-row { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: var(--sp-2); padding: var(--sp-2) 0; border-bottom: 1px solid var(--border); }.binding-row:last-child { border-bottom: 0; }.binding-row > span { display: inline-flex; padding: 6px; border-radius: 7px; background: var(--bg-soft); color: var(--text-faint); }.binding-row strong, .binding-row small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.binding-row strong { font-size: var(--fs-xs); }.binding-row small { color: var(--text-faint); font-size: var(--fs-2xs); }.binding-row > i { padding: 3px 6px; border-radius: var(--radius-pill); font-size: var(--fs-2xs); font-style: normal; }.binding-row > i.effective { background: var(--green-soft); color: var(--green); }.binding-row > i.inactive { background: var(--red-soft); color: var(--red); }
.next-action { display: grid; grid-template-columns: auto 1fr; gap: var(--sp-2); padding: var(--sp-3); border: 1px solid var(--accent-line); border-radius: var(--radius-lg); background: var(--accent-soft); color: var(--accent); }.next-action > span { display: inline-flex; }.next-action strong { color: var(--text); font-size: var(--fs-xs); }.next-action p { margin: 2px 0 0; color: var(--text-soft); font-size: var(--fs-2xs); line-height: 1.5; }.muted { color: var(--text-faint); font-size: var(--fs-xs); }
@media (max-width: 1180px) { .activity-hero { align-items: flex-start; flex-direction: column; }.summary-grid { grid-template-columns: repeat(2, 1fr); }.detail-grid { grid-template-columns: 1fr; }.side-column { position: static; } }
@media (max-width: 560px) { .activity-hero { padding: var(--sp-4); }.hero-timeline { width: 100%; align-items: flex-start; flex-direction: column; }.hero-timeline :deep(svg) { display: none; }.summary-grid { grid-template-columns: 1fr 1fr; }.summary-grid article { padding: var(--sp-3); }.benefit-card { align-items: flex-start; }.gift-row { grid-template-columns: auto 1fr; }.gift-row > b { grid-column: 2; } }
</style>
