<script setup lang="ts">
/**
 * 演示台目录页（UX 重设计 Phase F）：从一句话升级为分组卡片目录，一屏看清 18 Step 按能力分了哪些组、各含什么。
 * 修既有「侧栏说目录在右侧 / 本页说从左侧选」的措辞矛盾——本页本身即目录，点卡片直达。catalog 仍在 demos 懒 chunk。
 */
import { GROUPS, DEMOS } from './catalog'
import PageHeader from '@/shared/ui/PageHeader.vue'

const contentGroups = GROUPS.filter((g) => !g.external)
function demosOf(gid: string) {
  return DEMOS.filter((d) => d.group === gid)
}
</script>

<template>
  <div class="home" data-testid="demo-home">
    <PageHeader
      title="Drools 演示台"
      :subtitle="`${DEMOS.length} 个可运行 Step，按规则引擎能力分组 —— 选一个填参数、发请求，看规则实际触发效果。`"
    />
    <div class="groups">
      <section v-for="g in contentGroups" :key="g.id" class="gcard">
        <header class="ghead">
          <h2 class="gtitle">{{ g.title }}</h2>
          <p class="gsub">{{ g.subtitle }}</p>
        </header>
        <ul class="dlist">
          <li v-for="d in demosOf(g.id)" :key="d.id">
            <router-link class="ditem" :to="{ name: 'demo', params: { demoId: d.id } }" :data-testid="'demo-home-' + d.id">
              <span class="meth" :class="d.method === 'GET' ? 'get' : 'post'">{{ d.method }}</span>
              <span class="dtitle">{{ d.title }}</span>
              <span class="dstep">S{{ d.step }}</span>
            </router-link>
          </li>
        </ul>
      </section>
    </div>
  </div>
</template>

<style scoped>
.home { padding: 0; }
.groups { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(300px, 100%), 1fr)); gap: var(--sp-4); }
.gcard {
  background: var(--bg-elev); border: 1px solid var(--border);
  border-radius: var(--radius-lg); padding: var(--sp-4); box-shadow: var(--shadow-sm);
  transition: box-shadow .16s ease, border-color .16s ease;
}
.gcard:hover { box-shadow: var(--shadow-md); border-color: var(--border-strong); }
.ghead { margin-bottom: var(--sp-3); }
.gtitle { margin: 0; font-size: var(--fs-md); font-weight: var(--fw-semibold); }
.gsub { margin: 2px 0 0; font-size: var(--fs-xs); color: var(--text-faint); font-family: var(--mono); }
.dlist { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 2px; }
.ditem {
  display: flex; align-items: center; gap: var(--sp-2); padding: var(--sp-2);
  border-radius: var(--radius-sm); text-decoration: none; color: var(--text-soft);
  font-size: var(--fs-sm); transition: background .12s ease, color .12s ease;
}
.ditem:hover { background: var(--bg-hover); color: var(--text); }
.meth {
  flex: 0 0 auto; width: 40px; text-align: center; font-size: 10px; font-weight: var(--fw-semibold);
  font-family: var(--mono); padding: 2px 0; border-radius: var(--radius-sm); letter-spacing: .02em;
}
.meth.get { background: var(--blue-soft); color: var(--blue); }
.meth.post { background: var(--accent-soft); color: var(--accent); }
.dtitle { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dstep { font-size: var(--fs-xs); color: var(--text-faint); font-family: var(--mono); flex: 0 0 auto; }
@media (pointer: coarse) { .ditem { min-height: var(--touch-min); } }
</style>
