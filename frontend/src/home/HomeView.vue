<script setup lang="ts">
/**
 * 概览首页（视觉换代 0809 · 步骤 6 Tier A）：登录后的第一屏，也是换代前全站最朴素的一页
 * （一张白卡 + 一个纯文本列表）。现在结构为 hero + 核心工作区 + 最近活动。
 *
 * 数据源全复用现成 API：listActivities（按租户 header 隔离）+ useDictStore 出类型/状态标签。
 *
 * **hero 里的三个统计必须是真数**（总数 / 正在生效 / 7 日内到期）——它们由 benchModel.summarize
 * 从同一批行算出，与工作台口径同源。本项目已把「不画假图」写成产品立场，门面页更不能开这个口子。
 *
 * 修缺陷 F6：换代前这里是 `rows.slice(0, 8)`——既没排序也没按 activityId 归并。
 * `GET /list` 返回的是**行**不是活动，线上 v1 与草稿 v2 会各占一行，于是同一个活动
 * 在首页出现两次，且 `:key="a.activityId"` 直接撞车。现改为复用工作台的 mergeRows
 * （主版本取"正在服务的那一版"），再按生效开始时间倒序取前 8。
 *
 * 边界：介绍卡/快捷入口为静态，不依赖请求；最近活动区自带 loading/empty/error 三态，
 * 后端不可达时降级不白屏。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listActivities } from '@/console/activityApi'
import { mergeRows, summarize, type BenchRow } from '@/console/benchModel'
import { useDictStore } from '@/stores/useDictStore'
import { errText } from '@/shared/apiClient'
import Hero from '@/shared/ui/Hero.vue'
import Stat from '@/shared/ui/Stat.vue'
import Skeleton from '@/shared/ui/Skeleton.vue'
import Banner from '@/shared/ui/Banner.vue'
import EmptyState from '@/shared/ui/EmptyState.vue'
import Badge from '@/shared/ui/Badge.vue'
import Button from '@/shared/ui/Button.vue'
import Icon from '@/shared/ui/Icon.vue'

const router = useRouter()
const dict = useDictStore()

const rows = ref<BenchRow[]>([])
const loading = ref(false)
const loadErr = ref('')
let ctrl: AbortController | null = null

/** 按生效开始时间倒序；缺开始时间的排最后（缺失不该被当成 1970 年顶到最前）。 */
const recent = computed(() =>
  rows.value
    .slice()
    .sort((a, b) => {
      if (a.start === null && b.start === null) return 0
      if (a.start === null) return 1
      if (b.start === null) return -1
      return b.start - a.start
    })
    .slice(0, 8),
)

const stats = computed(() => summarize(rows.value, Date.now()))

function typeLabel(code: number): string {
  return dict.cache['__default__']?.activityTypes.find((t) => t.code === code)?.label ?? String(code)
}
function statusLabel(code: number): string {
  return dict.cache['__default__']?.statuses.find((s) => s.code === code)?.label ?? String(code)
}

async function load(): Promise<void> {
  loading.value = true
  loadErr.value = ''
  ctrl?.abort()
  ctrl = new AbortController()
  try {
    await dict.load()
    const r = await listActivities(ctrl.signal)
    if (!r.ok) {
      loadErr.value = errText(r)
      return
    }
    rows.value = mergeRows(r.json || [], Date.now())
  } catch (e) {
    if ((e as Error).name !== 'AbortError') loadErr.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

onMounted(load)
onUnmounted(() => ctrl?.abort())
</script>

<template>
  <section data-testid="home-view">
    <Hero
      kicker="ACTIVITY ENGINE PLATFORM"
      title="概览"
      desc="多租户活动与规则决策平台。统一管理活动配置、发布代际与只读决策快照。"
    >
      <template #actions>
        <Button variant="primary" :to="{ name: 'activity-new' }" data-testid="home-go-new">
          <Icon name="plus" :size="16" /><span>新建活动</span>
        </Button>
        <Button :to="{ name: 'activities' }" data-testid="home-go-list">
          <Icon name="list" :size="16" /><span>活动列表</span>
        </Button>
      </template>
      <!-- 三个数字全部来自同一批行，与工作台口径同源；没有数据源的指标一个都不放。 -->
      <template #stats>
        <Stat on-deep label="活动总数" :value="stats.total" />
        <Stat on-deep live label="正在生效" :value="stats.live" />
        <Stat on-deep label="7 日内到期" :value="stats.endingSoon" />
      </template>
    </Hero>

    <!-- 核心工作区介绍 + 快捷入口 -->
    <div class="areas u-stagger">
      <article class="area-card">
        <div class="head">
          <span class="area-ic console"><Icon name="badge-check" :size="22" /></span>
          <div>
            <h2 class="area-title">活动控制台</h2>
            <p class="area-desc">配置、复核、上下线营销活动：红包 / 买赠 / 加价购 + 白名单条件树，保存即生成受控 Drools。</p>
          </div>
        </div>
        <div class="entries">
          <Button variant="subtle" :to="{ name: 'activities' }">
            <Icon name="list" :size="16" /><span>活动列表</span>
          </Button>
          <Button variant="subtle" :to="{ name: 'playbooks' }">
            <Icon name="layers" :size="16" /><span>玩法模板</span>
          </Button>
          <Button variant="subtle" :to="{ name: 'validate' }">
            <Icon name="scale" :size="16" /><span>优惠验证</span>
          </Button>
        </div>
      </article>

    </div>

    <!-- 最近活动 -->
    <div class="recent">
      <div class="recent-head">
        <h2 class="sec-title">最近活动</h2>
        <router-link class="see-all" :to="{ name: 'activities' }">
          <span>全部</span><Icon name="chevron-right" :size="15" />
        </router-link>
      </div>

      <Skeleton v-if="loading" :rows="4" />
      <Banner v-else-if="loadErr" kind="err" data-testid="home-error">
        加载失败：{{ loadErr }} <button class="retry" @click="load">重试</button>
      </Banner>
      <EmptyState
        v-else-if="!recent.length"
        icon="inbox"
        title="暂无活动"
        hint="还没有活动，去控制台创建第一个"
      >
        <template #action>
          <Button variant="primary" :to="{ name: 'activity-new' }">
            <Icon name="plus" :size="16" /><span>新建活动</span>
          </Button>
        </template>
      </EmptyState>
      <div v-else class="rlist">
        <button
          v-for="a in recent"
          :key="a.activityId"
          class="rrow"
          :data-testid="'home-recent-' + a.activityId"
          @click="router.push({ name: 'activity-detail', params: { id: a.activityId } })"
        >
          <span class="rmain">
            <span class="rname">{{ a.activityName }}</span>
            <span class="rid mono">{{ a.activityId }} · v{{ a.version }}</span>
          </span>
          <span class="rmeta">
            <span class="rtype">{{ typeLabel(a.activityType) }}</span>
            <Badge :kind="a.state === 'live' ? 'ok' : 'neutral'">{{ statusLabel(a.activityStatus) }}</Badge>
            <Icon name="chevron-right" :size="16" class="rchev" />
          </span>
        </button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.areas { display: grid; grid-template-columns: 1fr; gap: var(--sp-4); margin-bottom: var(--gap-block); }
.area-card {
  display: flex; flex-direction: column; gap: var(--sp-4);
  background: var(--bg-elev); border: 1px solid var(--border);
  border-radius: var(--radius-lg); padding: var(--sp-5); box-shadow: var(--shadow-sm);
  transition: box-shadow var(--dur-mid) var(--ease-out), border-color var(--dur-mid) var(--ease-out),
              transform var(--dur-mid) var(--ease-out);
}
/* 纯装饰性的 hover 位移只给真有 hover 的设备；触屏给 :active，否则等于没有反馈。 */
@media (hover: hover) and (pointer: fine) {
  .area-card:hover { box-shadow: var(--shadow-md); border-color: var(--border-strong); transform: translateY(-2px); }
}
.area-card:active { border-color: var(--border-strong); }
.head { display: flex; gap: var(--sp-3); align-items: flex-start; }
.area-ic {
  display: inline-flex; align-items: center; justify-content: center; flex: 0 0 auto;
  width: 44px; height: 44px; border-radius: var(--radius); color: var(--text-invert);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, .22);
}
.area-ic.console { background: linear-gradient(180deg, var(--accent-hover), var(--accent)); }
.area-title { margin: 0 0 var(--sp-1); font-size: var(--fs-lg); font-weight: var(--fw-semibold); }
.area-desc { margin: 0; font-size: var(--fs-sm); color: var(--text-soft); line-height: var(--lh-normal); }
.entries { display: flex; flex-wrap: wrap; gap: var(--sp-2); }

.recent-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: var(--sp-3); }
.sec-title { margin: 0; font-size: var(--fs-lg); font-weight: var(--fw-semibold); }
.see-all { display: inline-flex; align-items: center; gap: 2px; font-size: var(--fs-sm); color: var(--text-soft); text-decoration: none; }
.see-all:hover { color: var(--accent); }
.retry { margin-left: var(--sp-2); cursor: pointer; }

.rlist { display: flex; flex-direction: column; background: var(--bg-elev); border: 1px solid var(--border); border-radius: var(--radius); box-shadow: var(--shadow-sm); overflow: hidden; }
.rrow {
  display: flex; align-items: center; justify-content: space-between; gap: var(--sp-3);
  padding: var(--sp-3) var(--sp-4); border: none; border-bottom: 1px solid var(--rule-faint);
  background: var(--bg-elev); color: var(--text); cursor: pointer; text-align: left;
  font-family: inherit; font-size: var(--fs-md); transition: background var(--dur-fast) var(--ease-out);
}
.rrow:last-child { border-bottom: none; }
.rrow:hover, .rrow:active { background: var(--bg-hover); }
.rmain { display: flex; flex-direction: column; min-width: 0; gap: 2px; }
.rname { font-weight: var(--fw-medium); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rid { font-family: var(--mono); font-variant-numeric: tabular-nums; font-size: var(--fs-xs); color: var(--text-faint); overflow-wrap: anywhere; }
.rmeta { display: flex; align-items: center; gap: var(--sp-3); flex: 0 0 auto; }
.rtype { font-size: var(--fs-sm); color: var(--text-soft); }
.rchev { color: var(--text-faint); }

@media (max-width: 560px) {
  .rtype { display: none; }
}
</style>
