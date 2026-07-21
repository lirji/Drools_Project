<script setup lang="ts">
import { computed, ref } from 'vue'
import { GROUPS, DEMOS, type DemoDef } from './catalog'
import Icon from '@/shared/ui/Icon.vue'

const query = ref('')
const activeGroup = ref('all')
const contentGroups = GROUPS.filter((group) => !group.external)

const groupVisuals: Record<string, { icon: string; eyebrow: string; tone: string }> = {
  basics: { icon: 'play', eyebrow: '核心执行', tone: 'indigo' },
  reasoning: { icon: 'workflow', eyebrow: '组合与推理', tone: 'violet' },
  table: { icon: 'table', eyebrow: '业务可维护', tone: 'blue' },
  event: { icon: 'radio', eyebrow: '实时事件', tone: 'cyan' },
  hot: { icon: 'zap', eyebrow: '动态发布', tone: 'amber' },
  ops: { icon: 'gauge', eyebrow: '稳定性与观测', tone: 'green' },
  model: { icon: 'layers', eyebrow: '标准决策模型', tone: 'rose' },
}

function demosOf(groupId: string): DemoDef[] {
  return DEMOS.filter((demo) => demo.group === groupId)
}

function matches(demo: DemoDef): boolean {
  const needle = query.value.trim().toLocaleLowerCase()
  if (!needle) return true
  return [demo.title, demo.desc, demo.path, demo.method, `c${String(demo.step).padStart(2, '0')}`, `能力 ${demo.step}`]
    .some((value) => value.toLocaleLowerCase().includes(needle))
}

const visibleGroups = computed(() => contentGroups
  .filter((group) => activeGroup.value === 'all' || activeGroup.value === group.id)
  .map((group) => ({ ...group, demos: demosOf(group.id).filter(matches) }))
  .filter((group) => group.demos.length > 0))

const visibleCount = computed(() => visibleGroups.value.reduce((sum, group) => sum + group.demos.length, 0))
const recommendedIds = ['hello', 'discount-calculate', 'fraud-check']
const recommended = recommendedIds
  .map((id) => DEMOS.find((demo) => demo.id === id))
  .filter((demo): demo is DemoDef => !!demo)

function selectGroup(groupId: string): void {
  activeGroup.value = groupId
}

function clearFilters(): void {
  query.value = ''
  activeGroup.value = 'all'
}
</script>

<template>
  <div class="demo-home" data-testid="demo-home">
    <section class="hero">
      <div class="hero-copy">
        <div class="eyebrow"><Icon name="flask" :size="15" /> RULE CAPABILITY CENTER</div>
        <h1>规则能力中心</h1>
        <p>面向业务决策与生产运维，按能力域调用规则服务、调整请求参数并查看结构化执行结果。</p>
        <div class="hero-actions">
          <router-link class="primary-action" :to="{ name: 'demo', params: { demoId: 'hello' } }">
            <Icon name="play" :size="16" /> 执行推荐场景
          </router-link>
          <a class="secondary-action" href="#capability-map">浏览全部能力</a>
        </div>
      </div>
      <div class="hero-stats" aria-label="规则能力统计">
        <div><strong>{{ DEMOS.length }}</strong><span>项已接入能力</span></div>
        <div><strong>{{ contentGroups.length }}</strong><span>个能力域</span></div>
        <div><strong>API</strong><span>在线调用验证</span></div>
      </div>
    </section>

    <section class="recommended" aria-labelledby="recommended-title">
      <div class="section-heading">
        <div>
          <span class="section-kicker">FREQUENTLY USED</span>
          <h2 id="recommended-title">常用能力</h2>
        </div>
        <p>优先展示常用规则调用场景，便于快速验证服务状态与决策结果。</p>
      </div>
      <div class="quick-grid">
        <router-link
          v-for="(demo, index) in recommended"
          :key="demo.id"
          class="quick-card"
          :to="{ name: 'demo', params: { demoId: demo.id } }"
        >
          <span class="quick-index">0{{ index + 1 }}</span>
          <div>
            <span class="quick-meta">能力 C{{ String(demo.step).padStart(2, '0') }} · {{ demo.method }}</span>
            <h3>{{ demo.title }}</h3>
            <p>{{ demo.desc }}</p>
          </div>
          <Icon name="arrow-up-right" :size="18" class="quick-arrow" />
        </router-link>
      </div>
    </section>

    <section id="capability-map" class="catalog" aria-labelledby="catalog-title">
      <div class="section-heading catalog-heading">
        <div>
          <span class="section-kicker">CAPABILITY MAP</span>
          <h2 id="catalog-title">能力目录</h2>
        </div>
        <span class="result-count">显示 {{ visibleCount }} / {{ DEMOS.length }} 项能力</span>
      </div>

      <div class="catalog-tools">
        <label class="search-box">
          <Icon name="search" :size="17" />
          <span class="sr-only">搜索规则能力</span>
          <input v-model="query" type="search" placeholder="搜索场景、能力、接口路径或编号…" data-testid="demo-search" />
          <button v-if="query" type="button" aria-label="清空搜索" @click="query = ''"><Icon name="x" :size="15" /></button>
        </label>
        <div class="filters" aria-label="能力分组筛选">
          <button type="button" :class="{ active: activeGroup === 'all' }" @click="selectGroup('all')">全部</button>
          <button
            v-for="group in contentGroups"
            :key="group.id"
            type="button"
            :class="{ active: activeGroup === group.id }"
            @click="selectGroup(group.id)"
          >
            {{ group.title.split('：')[0] }}
            <span>{{ demosOf(group.id).length }}</span>
          </button>
        </div>
      </div>

      <div v-if="visibleGroups.length" class="groups">
        <article v-for="group in visibleGroups" :key="group.id" class="group-card" :class="groupVisuals[group.id]?.tone">
          <header class="group-head">
            <span class="group-icon"><Icon :name="groupVisuals[group.id]?.icon || 'layers'" :size="20" /></span>
            <div class="group-title-wrap">
              <span>{{ groupVisuals[group.id]?.eyebrow }}</span>
              <h3>{{ group.title }}</h3>
              <p>{{ group.subtitle }}</p>
            </div>
            <span class="group-count">{{ group.demos.length }} 项</span>
          </header>

          <div class="demo-list">
            <router-link
              v-for="demo in group.demos"
              :key="demo.id"
              class="demo-item"
              :to="{ name: 'demo', params: { demoId: demo.id } }"
              :data-testid="'demo-home-' + demo.id"
            >
              <span class="step-badge">C{{ String(demo.step).padStart(2, '0') }}</span>
              <span class="demo-copy">
                <strong>{{ demo.title }}</strong>
                <small>{{ demo.desc }}</small>
              </span>
              <span class="endpoint">
                <b :class="demo.method === 'GET' ? 'get' : 'post'">{{ demo.method }}</b>
                <code>{{ demo.path }}</code>
              </span>
              <Icon name="chevron-right" :size="17" class="row-arrow" />
            </router-link>
          </div>
        </article>
      </div>

      <div v-else class="empty-search">
        <span><Icon name="search" :size="24" /></span>
        <h3>没有找到匹配的能力</h3>
        <p>试试搜索「折扣」「动态发布」或「C08」。</p>
        <button type="button" @click="clearFilters">清除筛选</button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.demo-home { display: flex; flex-direction: column; gap: var(--sp-8); }
.hero {
  position: relative; overflow: hidden; display: grid; grid-template-columns: minmax(0, 1.55fr) minmax(280px, .7fr);
  gap: var(--sp-7); align-items: end; padding: clamp(28px, 5vw, 56px); color: #fff;
  border-radius: 22px; background:
    radial-gradient(circle at 88% 12%, rgba(129, 140, 248, .42), transparent 34%),
    radial-gradient(circle at 2% 100%, rgba(59, 130, 246, .24), transparent 36%),
    linear-gradient(135deg, #171a34 0%, #29235b 54%, #312e81 100%);
  box-shadow: var(--shadow-lg);
}
.hero::after { content: ''; position: absolute; inset: 0; pointer-events: none; opacity: .13; background-image: radial-gradient(#fff 1px, transparent 1px); background-size: 22px 22px; mask-image: linear-gradient(to left, #000, transparent 70%); }
.hero-copy, .hero-stats { position: relative; z-index: 1; }
.eyebrow, .section-kicker { display: inline-flex; align-items: center; gap: var(--sp-2); font-family: var(--mono); font-size: 11px; font-weight: var(--fw-semibold); letter-spacing: .14em; }
.eyebrow { color: #c7d2fe; }
.hero h1 { margin: var(--sp-3) 0; font-size: clamp(34px, 5vw, 54px); line-height: 1.08; letter-spacing: -.045em; }
.hero-copy > p { max-width: 700px; margin: 0; color: #d8dcf0; font-size: 15px; line-height: 1.8; }
.hero-actions { display: flex; align-items: center; flex-wrap: wrap; gap: var(--sp-3); margin-top: var(--sp-5); }
.primary-action, .secondary-action { display: inline-flex; align-items: center; justify-content: center; min-height: 42px; padding: 0 var(--sp-4); border-radius: var(--radius-sm); text-decoration: none; font-weight: var(--fw-semibold); }
.primary-action { gap: var(--sp-2); background: #fff; color: #312e81; box-shadow: 0 8px 24px rgba(0, 0, 0, .2); }
.primary-action:hover { background: #eef2ff; }
.secondary-action { color: #e0e7ff; border: 1px solid rgba(255,255,255,.28); }
.secondary-action:hover { background: rgba(255,255,255,.08); }
.hero-stats { display: grid; grid-template-columns: repeat(3, 1fr); gap: 1px; padding: 1px; border: 1px solid rgba(255,255,255,.16); border-radius: var(--radius-lg); background: rgba(255,255,255,.12); backdrop-filter: blur(10px); }
.hero-stats div { padding: var(--sp-4) var(--sp-3); background: rgba(19, 20, 45, .48); text-align: center; }
.hero-stats div:first-child { border-radius: 12px 0 0 12px; }
.hero-stats div:last-child { border-radius: 0 12px 12px 0; }
.hero-stats strong, .hero-stats span { display: block; }
.hero-stats strong { font-family: var(--mono); font-size: 24px; }
.hero-stats span { margin-top: 2px; color: #c8cde0; font-size: 11px; white-space: nowrap; }
.section-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: var(--sp-5); margin-bottom: var(--sp-4); }
.section-heading h2 { margin: var(--sp-1) 0 0; font-size: var(--fs-xl); letter-spacing: -.02em; }
.section-heading > p { max-width: 480px; margin: 0; color: var(--text-soft); font-size: var(--fs-sm); text-align: right; }
.section-kicker { color: var(--accent); }
.quick-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--sp-3); }
.quick-card { position: relative; min-width: 0; display: grid; grid-template-columns: auto minmax(0, 1fr) auto; gap: var(--sp-3); padding: var(--sp-4); border: 1px solid var(--border); border-radius: var(--radius-lg); background: var(--bg-elev); color: var(--text); text-decoration: none; box-shadow: var(--shadow-sm); transition: transform .16s ease, box-shadow .16s ease, border-color .16s ease; }
.quick-card:hover { transform: translateY(-2px); border-color: var(--accent-line); box-shadow: var(--shadow-md); }
.quick-index { font-family: var(--mono); color: var(--accent); font-size: 12px; font-weight: var(--fw-bold); }
.quick-meta { color: var(--text-faint); font-family: var(--mono); font-size: 10px; text-transform: uppercase; }
.quick-card h3 { margin: 3px 0 var(--sp-1); font-size: var(--fs-md); }
.quick-card p { display: -webkit-box; overflow: hidden; margin: 0; color: var(--text-soft); font-size: 12px; line-height: 1.55; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.quick-arrow { color: var(--text-faint); }
.catalog { scroll-margin-top: var(--sp-5); }
.catalog-heading { margin-bottom: var(--sp-3); }
.result-count { color: var(--text-faint); font-size: var(--fs-xs); }
.catalog-tools { position: sticky; top: 0; z-index: var(--z-sticky); display: flex; gap: var(--sp-3); align-items: center; margin-bottom: var(--sp-4); padding: var(--sp-3); border: 1px solid var(--border); border-radius: var(--radius-lg); background: color-mix(in srgb, var(--bg-elev) 92%, transparent); box-shadow: var(--shadow-sm); backdrop-filter: blur(12px); }
.search-box { flex: 0 1 320px; display: flex; align-items: center; gap: var(--sp-2); min-height: 38px; padding: 0 var(--sp-3); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-soft); color: var(--text-faint); }
.search-box:focus-within { border-color: var(--accent); box-shadow: var(--focus-ring); }
.search-box input { width: 100%; min-width: 0; border: 0; outline: 0; background: transparent; color: var(--text); font: inherit; font-size: var(--fs-sm); }
.search-box button { display: inline-flex; padding: 3px; border: 0; background: transparent; color: var(--text-faint); cursor: pointer; }
.filters { flex: 1; display: flex; gap: var(--sp-1); overflow-x: auto; scrollbar-width: thin; }
.filters button { display: inline-flex; align-items: center; gap: var(--sp-1); flex: 0 0 auto; padding: 7px 10px; border: 1px solid transparent; border-radius: var(--radius-pill); background: transparent; color: var(--text-soft); cursor: pointer; font: inherit; font-size: var(--fs-xs); }
.filters button:hover { background: var(--bg-hover); color: var(--text); }
.filters button.active { border-color: var(--accent-line); background: var(--accent-soft); color: var(--accent); font-weight: var(--fw-semibold); }
.filters button span { font-family: var(--mono); font-size: 10px; opacity: .76; }
.groups { display: flex; flex-direction: column; gap: var(--sp-4); }
.group-card { --group-color: var(--accent); overflow: hidden; border: 1px solid var(--border); border-radius: var(--radius-lg); background: var(--bg-elev); box-shadow: var(--shadow-sm); }
.group-card.violet { --group-color: #7c3aed; } .group-card.blue { --group-color: #2563eb; } .group-card.cyan { --group-color: #0891b2; } .group-card.amber { --group-color: #b45309; } .group-card.green { --group-color: #15803d; } .group-card.rose { --group-color: #be185d; }
.group-head { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; gap: var(--sp-3); align-items: start; padding: var(--sp-4) var(--sp-5); border-bottom: 1px solid var(--border); background: linear-gradient(105deg, color-mix(in srgb, var(--group-color) 7%, var(--bg-soft)), var(--bg-elev) 64%); }
.group-icon { display: inline-flex; align-items: center; justify-content: center; width: 42px; height: 42px; border-radius: 11px; background: color-mix(in srgb, var(--group-color) 12%, var(--bg-elev)); color: var(--group-color); }
.group-title-wrap > span { color: var(--group-color); font-size: 10px; font-weight: var(--fw-bold); letter-spacing: .1em; text-transform: uppercase; }
.group-title-wrap h3 { margin: 2px 0 0; font-size: var(--fs-lg); }
.group-title-wrap p { margin: 2px 0 0; color: var(--text-faint); font-family: var(--mono); font-size: 11px; }
.group-count { margin-top: 2px; color: var(--text-faint); font-size: var(--fs-xs); }
.demo-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); }
.demo-item { position: relative; display: grid; grid-template-columns: auto minmax(0, 1fr) minmax(120px, .7fr) auto; align-items: center; gap: var(--sp-3); min-width: 0; padding: var(--sp-3) var(--sp-4); border-bottom: 1px solid var(--border); color: var(--text); text-decoration: none; transition: background .12s ease; }
.demo-item:nth-child(odd) { border-right: 1px solid var(--border); }
.demo-item:hover { background: var(--bg-hover); }
.demo-item:hover .row-arrow { color: var(--accent); transform: translateX(2px); }
.step-badge { display: inline-flex; align-items: center; justify-content: center; width: 34px; height: 30px; border-radius: var(--radius-sm); background: var(--bg-soft); color: var(--text-soft); font-family: var(--mono); font-size: 11px; font-weight: var(--fw-semibold); }
.demo-copy { min-width: 0; }
.demo-copy strong, .demo-copy small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.demo-copy strong { font-size: var(--fs-sm); }
.demo-copy small { margin-top: 2px; color: var(--text-faint); font-size: 11px; }
.endpoint { min-width: 0; }
.endpoint b { display: block; width: max-content; font-family: var(--mono); font-size: 9px; }
.endpoint b.get { color: var(--blue); } .endpoint b.post { color: var(--accent); }
.endpoint code { display: block; overflow: hidden; margin-top: 2px; color: var(--text-faint); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.row-arrow { color: var(--text-faint); transition: color .12s ease, transform .12s ease; }
.empty-search { padding: var(--sp-8); border: 1px dashed var(--border-strong); border-radius: var(--radius-lg); text-align: center; }
.empty-search > span { display: inline-flex; padding: var(--sp-3); border-radius: 50%; background: var(--bg-soft); color: var(--text-faint); }
.empty-search h3 { margin: var(--sp-3) 0 var(--sp-1); }
.empty-search p { margin: 0 0 var(--sp-3); color: var(--text-soft); }
.empty-search button { padding: var(--sp-2) var(--sp-4); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--text); cursor: pointer; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; }
@media (max-width: 1180px) { .hero { grid-template-columns: 1fr; } .hero-stats { max-width: 480px; } .demo-list { grid-template-columns: 1fr; } .demo-item:nth-child(odd) { border-right: 0; } }
@media (max-width: 820px) { .quick-grid { grid-template-columns: 1fr; } .catalog-tools { align-items: stretch; flex-direction: column; } .search-box { flex-basis: auto; width: 100%; } .filters { width: 100%; } }
@media (max-width: 560px) { .demo-home { gap: var(--sp-6); } .hero { margin: calc(-1 * var(--sp-3)); padding: var(--sp-6) var(--sp-5); border-radius: 0 0 20px 20px; } .hero-stats { grid-template-columns: 1fr; } .hero-stats div:first-child { border-radius: 12px 12px 0 0; } .hero-stats div:last-child { border-radius: 0 0 12px 12px; } .section-heading { align-items: flex-start; flex-direction: column; gap: var(--sp-1); } .section-heading > p { text-align: left; } .catalog-tools { position: static; } .group-head { padding: var(--sp-4); } .group-count { display: none; } .demo-item { grid-template-columns: auto minmax(0, 1fr) auto; } .endpoint { display: none; } }
</style>
