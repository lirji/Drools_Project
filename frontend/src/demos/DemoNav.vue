<script setup lang="ts">
import { useRoute } from 'vue-router'
import { GROUPS, DEMOS } from './catalog'

const route = useRoute()
// external 组（活动营销）→ 链到 /console，其余按组列 demo
const contentGroups = GROUPS.filter((g) => !g.external)
const externalGroups = GROUPS.filter((g) => g.external)
function demosOf(gid: string) {
  return DEMOS.filter((d) => d.group === gid)
}
function methodClass(m: string): string {
  return 'dot ' + (m === 'GET' ? 'get' : 'post')
}
</script>

<template>
  <nav class="nav" data-testid="demo-nav">
    <template v-for="g in contentGroups" :key="g.id">
      <div v-if="demosOf(g.id).length" class="group">
        <div class="g-title">{{ g.title }}</div>
        <div class="g-sub">{{ g.subtitle }}</div>
        <router-link
          v-for="d in demosOf(g.id)"
          :key="d.id"
          class="item"
          :class="{ active: route.params.demoId === d.id }"
          :to="{ name: 'demo', params: { demoId: d.id } }"
          :data-testid="'demo-nav-' + d.id"
        >
          <span :class="methodClass(d.method)" />
          <span class="i-title">{{ d.title }}</span>
          <span class="i-step">S{{ d.step }}</span>
        </router-link>
      </div>
    </template>
    <div v-for="g in externalGroups" :key="g.id" class="group">
      <div class="g-title">{{ g.title }}</div>
      <div class="g-sub">{{ g.subtitle }}</div>
      <router-link class="item" :to="{ name: 'activities' }" data-testid="demo-nav-activity">
        <span class="dot post" /><span class="i-title">工作台 · 列表/新建/验证</span>
      </router-link>
    </div>
  </nav>
</template>

<style scoped>
.nav { display: flex; flex-direction: column; gap: var(--sp-3); }
.group { display: flex; flex-direction: column; }
.g-title { font-size: 12px; font-weight: 600; color: var(--text); margin-top: var(--sp-2); }
.g-sub { font-size: 11px; color: var(--text-faint); margin-bottom: var(--sp-1); }
.item {
  display: flex; align-items: center; gap: var(--sp-2); padding: var(--sp-2);
  border-radius: var(--radius-sm); text-decoration: none; color: var(--text-soft); font-size: 13px;
}
.item:hover { background: var(--bg-soft); }
.item.active { background: var(--accent-soft); color: var(--accent); }
.i-title { flex: 1; }
.i-step { font-size: 11px; color: var(--text-faint); font-family: var(--mono); }
.dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.dot.get { background: var(--blue); }
.dot.post { background: var(--accent); }
@media (pointer: coarse) { .item { min-height: var(--touch-min); } }
</style>
