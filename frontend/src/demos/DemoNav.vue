<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { GROUPS, DEMOS, type DemoDef } from './catalog'
import Icon from '@/shared/ui/Icon.vue'

defineProps<{ embedded?: boolean }>()
const emit = defineEmits<{ (event: 'navigate'): void }>()

const route = useRoute()
const query = ref('')
const expanded = ref(new Set<string>())
const contentGroups = GROUPS.filter((group) => !group.external)

const groupIcons: Record<string, string> = {
  basics: 'play', reasoning: 'workflow', table: 'table', event: 'radio',
  hot: 'zap', ops: 'gauge', model: 'layers',
}

const activeId = computed(() => String(route.params.demoId || ''))
const activeDemo = computed(() => DEMOS.find((demo) => demo.id === activeId.value))
const activeGroup = computed(() => activeDemo.value?.group || '')

function demosOf(groupId: string): DemoDef[] {
  const needle = query.value.trim().toLocaleLowerCase()
  return DEMOS.filter((demo) => {
    if (demo.group !== groupId) return false
    if (!needle) return true
    return [demo.title, demo.desc, demo.path, `c${String(demo.step).padStart(2, '0')}`, `能力 ${demo.step}`]
      .some((value) => value.toLocaleLowerCase().includes(needle))
  })
}

const filteredGroups = computed(() => contentGroups
  .map((group) => ({ ...group, demos: demosOf(group.id) }))
  .filter((group) => group.demos.length > 0))

watch(activeGroup, (groupId) => {
  if (!groupId) return
  const next = new Set(expanded.value)
  next.add(groupId)
  expanded.value = next
}, { immediate: true })

function isExpanded(groupId: string): boolean {
  return !!query.value.trim() || expanded.value.has(groupId)
}

function toggle(groupId: string): void {
  const next = new Set(expanded.value)
  if (next.has(groupId)) next.delete(groupId)
  else next.add(groupId)
  expanded.value = next
}
</script>

<template>
  <component :is="embedded ? 'div' : 'nav'" class="nav" :class="{ embedded }" data-testid="demo-nav" :aria-label="embedded ? undefined : '规则能力导航'">
    <router-link class="catalog-link" :to="{ name: 'demos' }" :class="{ active: route.name === 'demos' }" @click="emit('navigate')">
      <span class="catalog-icon"><Icon name="flask" :size="18" /></span>
      <span><strong>{{ embedded ? '能力目录' : '规则能力中心' }}</strong><small>{{ embedded ? '全部能力分组' : '返回能力目录' }}</small></span>
      <Icon name="chevron-right" :size="16" />
    </router-link>

    <div v-if="activeDemo" class="now-running">
      <span class="pulse" />
      <span>当前能力</span>
      <strong>C{{ String(activeDemo.step).padStart(2, '0') }} · {{ activeDemo.title }}</strong>
    </div>

    <label class="nav-search">
      <Icon name="search" :size="15" />
      <span class="sr-only">搜索规则能力</span>
      <input v-model="query" type="search" placeholder="搜索 33 项能力…" />
      <button v-if="query" type="button" aria-label="清空搜索" @click="query = ''"><Icon name="x" :size="14" /></button>
    </label>

    <div class="nav-caption"><span>能力分组</span><span>{{ DEMOS.length }} CAPABILITIES</span></div>

    <div v-if="filteredGroups.length" class="group-list">
      <section v-for="group in filteredGroups" :key="group.id" class="group" :class="{ current: activeGroup === group.id }">
        <button
          type="button"
          class="group-toggle"
          :aria-expanded="isExpanded(group.id)"
          :aria-controls="'demo-group-' + group.id"
          @click="toggle(group.id)"
        >
          <span class="group-icon"><Icon :name="groupIcons[group.id] || 'layers'" :size="16" /></span>
          <span class="group-label">
            <strong>{{ group.title.split('：')[0] }}</strong>
            <small>{{ group.demos.length }} 项能力</small>
          </span>
          <Icon name="chevron-down" :size="15" class="chevron" :class="{ open: isExpanded(group.id) }" />
        </button>

        <div v-show="isExpanded(group.id)" :id="'demo-group-' + group.id" class="items">
          <router-link
            v-for="demo in group.demos"
            :key="demo.id"
            class="item"
            :class="{ active: activeId === demo.id }"
            :to="{ name: 'demo', params: { demoId: demo.id } }"
            :data-testid="'demo-nav-' + demo.id"
            @click="emit('navigate')"
          >
            <span class="rail-dot" />
            <span class="item-copy">
              <strong>{{ demo.title }}</strong>
              <small><b :class="demo.method === 'GET' ? 'get' : 'post'">{{ demo.method }}</b> {{ demo.path }}</small>
            </span>
            <span class="item-step">C{{ String(demo.step).padStart(2, '0') }}</span>
          </router-link>
        </div>
      </section>
    </div>

    <div v-else class="nav-empty">
      <Icon name="search" :size="20" />
      <span>没有匹配的能力</span>
    </div>
  </component>
</template>

<style scoped>
.nav { display: flex; flex-direction: column; gap: var(--sp-3); padding: var(--sp-3); border: 1px solid var(--border); border-radius: var(--radius-lg); background: var(--bg-elev); box-shadow: var(--shadow-sm); }
.nav.embedded { gap: var(--sp-2); padding: 0; border: 0; border-radius: 0; background: transparent; box-shadow: none; }
.catalog-link { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: var(--sp-2); padding: var(--sp-2); border-radius: var(--radius-sm); color: var(--text); text-decoration: none; }
.catalog-link:hover, .catalog-link.active { background: var(--accent-soft); color: var(--accent); }
.catalog-icon { display: inline-flex; align-items: center; justify-content: center; width: 34px; height: 34px; border-radius: 10px; background: var(--accent-soft); color: var(--accent); }
.catalog-link span:nth-child(2) { min-width: 0; }
.catalog-link strong, .catalog-link small { display: block; }
.catalog-link strong { font-size: var(--fs-sm); }
.catalog-link small { color: var(--text-faint); font-size: var(--fs-xs); font-weight: var(--fw-medium); }
.now-running { display: grid; grid-template-columns: auto 1fr; column-gap: var(--sp-2); padding: var(--sp-2) var(--sp-3); border: 1px solid var(--accent-line); border-radius: var(--radius-sm); background: var(--accent-soft); }
.now-running .pulse { grid-row: span 2; align-self: center; width: 7px; height: 7px; border-radius: 50%; background: var(--accent); box-shadow: 0 0 0 4px color-mix(in srgb, var(--accent) 14%, transparent); }
.now-running span:not(.pulse) { color: var(--accent); font-size: var(--fs-2xs); font-weight: var(--fw-bold); letter-spacing: .08em; text-transform: uppercase; }
.now-running strong { overflow: hidden; color: var(--text); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.nav-search { display: flex; align-items: center; gap: var(--sp-2); min-height: 36px; padding: 0 var(--sp-2); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-soft); color: var(--text-faint); }
.nav-search:focus-within { border-color: var(--accent); box-shadow: var(--focus-ring); }
.nav-search input { width: 100%; min-width: 0; border: 0; outline: 0; background: transparent; color: var(--text); font: inherit; font-size: 12px; }
.nav-search button { display: inline-flex; padding: 2px; border: 0; background: transparent; color: var(--text-faint); cursor: pointer; }
.nav-caption { display: flex; justify-content: space-between; padding: 0 var(--sp-1); color: var(--text-faint); font-size: var(--fs-2xs); font-weight: var(--fw-bold); letter-spacing: .1em; text-transform: uppercase; }
.group-list { display: flex; flex-direction: column; gap: 3px; }
.group { border-radius: var(--radius-sm); }
.group.current { background: color-mix(in srgb, var(--accent-soft) 46%, transparent); }
.group-toggle { width: 100%; display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: var(--sp-2); padding: 7px var(--sp-2); border: 0; border-radius: var(--radius-sm); background: transparent; color: var(--text); cursor: pointer; text-align: left; }
.group-toggle:hover { background: var(--bg-hover); }
.group-icon { display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; border-radius: 8px; background: var(--bg-soft); color: var(--text-soft); }
.group.current .group-icon { background: var(--accent-soft); color: var(--accent); }
.group-label strong, .group-label small { display: block; }
.group-label strong { font-size: 12px; }
.group-label small { color: var(--text-faint); font-size: var(--fs-2xs); }
.chevron { color: var(--text-faint); transition: transform .15s ease; }
.chevron.open { transform: rotate(180deg); }
.items { position: relative; margin-left: 21px; padding: 2px 0 var(--sp-2) 13px; }
.items::before { content: ''; position: absolute; top: 0; bottom: 8px; left: 0; width: 1px; background: var(--border); }
.item { position: relative; display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: var(--sp-2); min-height: 38px; padding: 5px var(--sp-2); border-radius: var(--radius-sm); color: var(--text-soft); text-decoration: none; }
.item:hover { background: var(--bg-hover); color: var(--text); }
.item.active { background: var(--accent-soft); color: var(--accent); }
.rail-dot { position: absolute; left: -16px; width: 7px; height: 7px; border: 2px solid var(--bg-elev); border-radius: 50%; background: var(--border-strong); }
.item.active .rail-dot { background: var(--accent); box-shadow: 0 0 0 2px var(--accent-line); }
.item-copy { min-width: 0; }
.item-copy strong, .item-copy small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.item-copy strong { font-size: 11px; font-weight: var(--fw-medium); }
.item-copy small { margin-top: 1px; color: var(--text-faint); font-family: var(--mono); font-size: var(--fs-2xs); }
.item-copy b { font-weight: var(--fw-bold); }
.item-copy b.get { color: var(--blue); } .item-copy b.post { color: var(--accent); }
.item-step { color: var(--text-faint); font-size: var(--fs-2xs); font-variant-numeric: tabular-nums; }
.nav-empty { display: flex; flex-direction: column; align-items: center; gap: var(--sp-2); padding: var(--sp-5); color: var(--text-faint); font-size: var(--fs-xs); }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; }
@media (max-width: 1023px) {
  .nav { display: grid; grid-template-columns: minmax(190px, .7fr) minmax(220px, 1fr); align-items: start; }
  .catalog-link { grid-column: 1; }
  .now-running { grid-column: 1; }
  .nav-search { grid-column: 2; grid-row: 1; }
  .nav-caption { display: none; }
  .group-list, .nav-empty { grid-column: 2; grid-row: 2 / span 2; max-height: 220px; overflow-y: auto; }
  .nav.embedded { display: flex; }
  .nav.embedded .catalog-link, .nav.embedded .now-running, .nav.embedded .nav-search, .nav.embedded .group-list, .nav.embedded .nav-empty { grid-column: auto; grid-row: auto; }
  .nav.embedded .group-list, .nav.embedded .nav-empty { max-height: none; overflow: visible; }
}
@media (max-width: 640px) {
  .nav { display: flex; }
  .group-list, .nav-empty { max-height: 280px; }
  .catalog-link small, .now-running { display: none; }
}
</style>
