<script setup lang="ts">
/**
 * 概览首页（UX 重设计 Phase C）：着陆页，一眼说清两大区域（控制台 / 演示台）+ 快捷入口 + 最近活动。
 * 数据源全复用现成 API：listActivities（按租户 header 隔离）取前 8 条 + useDictStore 出类型/状态标签。
 * 边界：介绍卡/快捷入口为静态，不依赖请求；最近活动区自带 loading/empty/error 三态，后端不可达时降级不白屏。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listActivities } from '@/console/activityApi'
import { useDictStore } from '@/stores/useDictStore'
import { errText } from '@/shared/apiClient'
import type { ActivityListRow } from '@/shared/types'
import PageHeader from '@/shared/ui/PageHeader.vue'
import Skeleton from '@/shared/ui/Skeleton.vue'
import Banner from '@/shared/ui/Banner.vue'
import EmptyState from '@/shared/ui/EmptyState.vue'
import Badge from '@/shared/ui/Badge.vue'
import Button from '@/shared/ui/Button.vue'
import Icon from '@/shared/ui/Icon.vue'

const router = useRouter()
const dict = useDictStore()

const rows = ref<ActivityListRow[]>([])
const loading = ref(false)
const loadErr = ref('')
let ctrl: AbortController | null = null

const recent = computed(() => rows.value.slice(0, 8))

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
    rows.value = r.json || []
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
    <PageHeader
      title="概览"
      subtitle="多租户活动引擎平台 · 配套 Drools 规则引擎学习台"
    />

    <!-- 两大区域介绍 + 快捷入口 -->
    <div class="areas">
      <article class="area-card">
        <div class="head">
          <span class="area-ic console"><Icon name="badge-check" :size="22" /></span>
          <div>
            <h2 class="area-title">活动控制台</h2>
            <p class="area-desc">配置、复核、上下线营销活动：红包 / 买赠规则 + 白名单条件树，保存即生成受控 Drools。</p>
          </div>
        </div>
        <div class="entries">
          <Button variant="subtle" :to="{ name: 'activities' }" data-testid="home-go-list">
            <Icon name="list" :size="16" /><span>活动列表</span>
          </Button>
          <Button variant="subtle" :to="{ name: 'activity-new' }" data-testid="home-go-new">
            <Icon name="plus" :size="16" /><span>新建活动</span>
          </Button>
          <Button variant="subtle" :to="{ name: 'validate' }">
            <Icon name="scale" :size="16" /><span>优惠验证</span>
          </Button>
        </div>
      </article>

      <article class="area-card">
        <div class="head">
          <span class="area-ic demos"><Icon name="flask" :size="22" /></span>
          <div>
            <h2 class="area-title">Drools 演示台</h2>
            <p class="area-desc">18 个可运行 Step，从 Hello World 到 CEP 滑窗 / DMN / 规则热加载，浏览器直接发请求看规则触发。</p>
          </div>
        </div>
        <div class="entries">
          <Button variant="subtle" :to="{ name: 'demos' }" data-testid="home-go-demos">
            <Icon name="flask" :size="16" /><span>打开演示台</span>
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
            <span class="rid mono">{{ a.activityId }}</span>
          </span>
          <span class="rmeta">
            <span class="rtype">{{ typeLabel(a.activityType) }}</span>
            <Badge :kind="a.activityStatus === 1 ? 'ok' : 'neutral'">{{ statusLabel(a.activityStatus) }}</Badge>
            <Icon name="chevron-right" :size="16" class="rchev" />
          </span>
        </button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.areas { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-4); margin-bottom: var(--sp-6); }
.area-card {
  display: flex; flex-direction: column; gap: var(--sp-4);
  background: var(--bg-elev); border: 1px solid var(--border);
  border-radius: var(--radius-lg); padding: var(--sp-5); box-shadow: var(--shadow-sm);
  transition: box-shadow .16s ease, border-color .16s ease;
}
.area-card:hover { box-shadow: var(--shadow-md); border-color: var(--border-strong); }
.head { display: flex; gap: var(--sp-3); align-items: flex-start; }
.area-ic {
  display: inline-flex; align-items: center; justify-content: center; flex: 0 0 auto;
  width: 44px; height: 44px; border-radius: var(--radius); color: #fff;
}
.area-ic.console { background: var(--accent); }
.area-ic.demos { background: var(--accent-2); }
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
  padding: var(--sp-3) var(--sp-4); border: none; border-bottom: 1px solid var(--border);
  background: var(--bg-elev); color: var(--text); cursor: pointer; text-align: left;
  font-family: inherit; font-size: var(--fs-md); transition: background .12s ease;
}
.rrow:last-child { border-bottom: none; }
.rrow:hover { background: var(--bg-hover); }
.rmain { display: flex; flex-direction: column; min-width: 0; gap: 2px; }
.rname { font-weight: var(--fw-medium); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rid { font-size: var(--fs-xs); color: var(--text-faint); overflow-wrap: anywhere; }
.rmeta { display: flex; align-items: center; gap: var(--sp-3); flex: 0 0 auto; }
.rtype { font-size: var(--fs-sm); color: var(--text-soft); }
.rchev { color: var(--text-faint); }

@media (max-width: 1023px) {
  .areas { grid-template-columns: 1fr; }
}
@media (max-width: 560px) {
  .rtype { display: none; }
}
</style>
