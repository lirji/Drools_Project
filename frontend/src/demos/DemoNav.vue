<script setup lang="ts">
import { useRoute } from 'vue-router'
import { GROUPS, DEMOS } from './catalog'

const route = useRoute()
// 只列内容组的 demo；external 组（跳回 /console）已随重设计移除——工作台入口在全局左侧栏。
const contentGroups = GROUPS.filter((g) => !g.external)
function demosOf(gid: string) {
  return DEMOS.filter((d) => d.group === gid)
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
          <span class="meth" :class="d.method === 'GET' ? 'get' : 'post'">{{ d.method }}</span>
          <span class="i-title">{{ d.title }}</span>
          <span class="i-step">S{{ d.step }}</span>
        </router-link>
      </div>
    </template>
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
.item:hover { background: var(--bg-hover); color: var(--text); }
.item.active { background: var(--accent-soft); color: var(--accent); font-weight: var(--fw-medium); }
.i-title { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.i-step { font-size: 11px; color: var(--text-faint); font-family: var(--mono); flex: 0 0 auto; }
.meth {
  flex: 0 0 auto; width: 38px; text-align: center; font-size: 9px; font-weight: var(--fw-semibold);
  font-family: var(--mono); padding: 2px 0; border-radius: var(--radius-sm); letter-spacing: .02em;
}
.meth.get { background: var(--blue-soft); color: var(--blue); }
.meth.post { background: var(--accent-soft); color: var(--accent); }
@media (pointer: coarse) { .item { min-height: var(--touch-min); } }
</style>
