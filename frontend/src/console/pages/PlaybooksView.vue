<script setup lang="ts">
/**
 * 玩法模板屏（PR-6 · 屏 2）。
 *
 * <p>这一屏**不新增任何后端能力**——它把「红包 + 6 个条件字段」这套已有能力，
 * 按运营的说法起名字并给出起点。过去运营在编辑器里看到的是「活动类型：红包」，
 * 看不出自己能配出「满 300 减 50」还是「新客专享立减」；类型看着只有两个，
 * 能表达的玩法其实有八个。
 *
 * <p>不可用的四个一张都不删，标灰并写明缺什么——运营看得到边界，才不会拿一个
 * 配不出来的玩法去排期。
 */
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '@/shared/ui/PageHeader.vue'
import Icon from '@/shared/ui/Icon.vue'
import Seam from '@/shared/viz/Seam.vue'
import Receipt from '@/shared/viz/Receipt.vue'
import {
  PLAYBOOKS, PLAYBOOK_GROUPS, filterPlaybooks, countByGroup, isReady,
  type PlaybookGroup,
} from '../playbooks'

const router = useRouter()
const active = ref<PlaybookGroup | 'all'>('all')
const shown = computed(() => filterPlaybooks(active.value))
const readyCount = computed(() => PLAYBOOKS.filter(isReady).length)

function use(id: string): void {
  router.push({ name: 'activity-new', query: { playbook: id } })
}
</script>

<template>
  <section data-testid="playbooks-view">
    <PageHeader kicker="PLAYBOOK LIBRARY" title="玩法模板" subtitle="挑一个玩法开始，模板会把类型、金额与人群条件先填好">
      <template #actions>
        <button class="blank" type="button" data-testid="playbook-blank" @click="router.push({ name: 'activity-new' })">
          <Icon name="plus" :size="16" /> 从空白新建
        </button>
      </template>
    </PageHeader>

    <aside class="note" data-testid="playbooks-note">
      <strong>这些模板不新增后端能力</strong>
      <p>
        后端只有两个活动类型（红包 / 买赠），权益形态只有「固定金额」与「阶梯金额」。
        但它们配上 6 个可用条件字段（订单金额 / 购买数量 / 用户地域 / 用户标签 / 商品 SPU / 店铺），
        本来就能表达 <b>{{ readyCount }}</b> 种玩法——这一屏做的是给已有能力起名字并给出起点。
        另外 {{ PLAYBOOKS.length - readyCount }} 个标灰的，每个都写明了缺什么。
      </p>
    </aside>

    <div class="filters" role="group" aria-label="玩法分类">
      <button
        v-for="g in PLAYBOOK_GROUPS"
        :key="g.key"
        type="button"
        class="chip"
        :class="{ on: active === g.key }"
        :aria-pressed="active === g.key"
        :data-testid="'playbook-filter-' + g.key"
        @click="active = g.key"
      >{{ g.label }} <i>{{ countByGroup(g.key) }}</i></button>
    </div>

    <div class="grid">
      <article
        v-for="p in shown"
        :key="p.id"
        class="card"
        :class="{ blocked: !isReady(p) }"
        :data-testid="'playbook-card-' + p.id"
      >
        <div class="head">
          <h3>{{ p.name }}</h3>
          <span v-if="!isReady(p)" class="tag">暂不支持</span>
        </div>
        <p class="plain">{{ p.plain }}</p>

        <!-- 撕线以上是券面（玩法身份），以下是副券（金额与操作）。缺口底色由卡片给。 -->
        <Seam :inset="-16" />

        <div class="foot">
          <Receipt v-if="isReady(p)" :lines="p.receipt.map((l) => ({ label: l.label, amount: l.amount, hit: true }))" />
          <p v-else class="why"><strong>缺什么：</strong>{{ p.blockedReason }}</p>

          <button
            v-if="isReady(p)"
            type="button"
            class="use"
            :data-testid="'playbook-use-' + p.id"
            @click="use(p.id)"
          >用它新建 <Icon name="arrow-right" :size="14" /></button>
        </div>
      </article>
    </div>
  </section>
</template>

<style scoped>
.blank { display: inline-flex; align-items: center; gap: var(--sp-1); min-height: 36px; padding: 0 var(--sp-3); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--text); cursor: pointer; font: inherit; font-size: var(--fs-sm); }
.blank:hover { background: var(--bg-hover); }
.note { padding: var(--sp-3) var(--sp-4); margin-bottom: var(--gap-group); border: 1px dashed var(--border-strong); border-radius: var(--radius-lg); background: var(--bg-soft); }
.note strong { display: block; margin-bottom: 3px; font-size: var(--fs-sm); }
.note p { margin: 0; color: var(--text-faint); font-size: var(--fs-xs); line-height: var(--lh-normal); }
.note b { color: var(--text); font-family: var(--mono); }

.filters { display: flex; flex-wrap: wrap; gap: var(--sp-2); margin-bottom: var(--gap-group); }
.chip { display: inline-flex; align-items: center; gap: 5px; min-height: 32px; padding: 0 var(--sp-3); border: 1px solid var(--border); border-radius: var(--radius-pill); background: var(--bg-elev); color: var(--text-soft); cursor: pointer; font: inherit; font-size: var(--fs-xs); }
.chip:hover { background: var(--bg-hover); }
.chip.on { border-color: var(--accent); color: var(--accent); background: var(--accent-soft); }
.chip i { font-family: var(--mono); font-style: normal; font-size: var(--fs-xs); color: var(--text-faint); }
.chip.on i { color: var(--accent); }

.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(100%, 296px), 1fr)); gap: var(--sp-4); }
.card {
  /* Seam 的缺口要咬进券卡纸面，底色必须由使用点给（评审 X3） */
  --notch-bg: var(--bg-elev);
  display: flex; flex-direction: column; min-width: 0;
  padding: var(--sp-4); border: 1px solid var(--border); border-radius: var(--radius-lg);
  background: var(--bg-elev); box-shadow: var(--shadow-sm);
}
.card.blocked { --notch-bg: var(--bg-soft); background: var(--bg-soft); box-shadow: none; border-style: dashed; }
.head { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-2); }
.head h3 { margin: 0; font-size: var(--fs-md); }
.card.blocked .head h3 { color: var(--text-soft); }
.tag { flex: none; padding: 1px var(--sp-2); border: 1px solid var(--border-strong); border-radius: var(--radius-pill); color: var(--text-faint); font-size: var(--fs-xs); }
.plain { margin: var(--sp-2) 0 0; color: var(--text-faint); font-size: var(--fs-xs); line-height: var(--lh-normal); }
.foot { display: flex; flex-direction: column; gap: var(--sp-3); margin-top: auto; }
.why { margin: 0; color: var(--text-faint); font-size: var(--fs-xs); line-height: var(--lh-normal); }
.why strong { color: var(--text-soft); }
.use { display: inline-flex; align-items: center; justify-content: center; gap: var(--sp-1); min-height: 34px; border: 1px solid var(--accent); border-radius: var(--radius-sm); background: var(--accent); color: var(--text-invert); cursor: pointer; font: inherit; font-size: var(--fs-sm); }
.use:hover { background: var(--accent-hover); border-color: var(--accent-hover); }
@media (pointer: coarse) { .use, .chip, .blank { min-height: var(--touch-min); } }
</style>
