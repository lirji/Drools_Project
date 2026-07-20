<script setup lang="ts">
/**
 * 全局主导航（重设计）：收编原 App.vue 顶部 nav（工作台/演示台）+ ConsoleShell 三 tab（列表/新建/验证）为单一持久左侧栏。
 * testid 逐字保留：nav-console / nav-demos / tab-list / tab-new / tab-validate。演示台的 18-Step 目录（DemoNav）
 * 仍留在 demos 内容区做次级导航（保懒加载、防 catalog.ts 进主包），本侧栏只放「演示台」入口。
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const emit = defineEmits<{ (e: 'navigate'): void }>()

// 承接 ConsoleShell 的 activeTab：detail/edit 归到「活动列表」
const consoleTab = computed(() => {
  const n = route.name as string
  if (n === 'activity-detail' || n === 'activity-edit' || n === 'activities') return 'activities'
  if (n === 'activity-new') return 'activity-new'
  if (n === 'validate') return 'validate'
  return ''
})
const inConsole = computed(() => typeof route.path === 'string' && route.path.startsWith('/console'))
const inDemos = computed(() => typeof route.path === 'string' && route.path.startsWith('/demos'))
</script>

<template>
  <nav class="side">
    <div class="section">
      <router-link
        class="group-link"
        :class="{ current: inConsole }"
        :to="{ name: 'activities' }"
        data-testid="nav-console"
        @click="emit('navigate')"
      >控制台</router-link>
      <div class="items">
        <router-link
          class="nav-item item"
          :class="{ active: consoleTab === 'activities' }"
          :to="{ name: 'activities' }"
          data-testid="tab-list"
          @click="emit('navigate')"
        >活动列表</router-link>
        <router-link
          class="nav-item item"
          :class="{ active: consoleTab === 'activity-new' }"
          :to="{ name: 'activity-new' }"
          data-testid="tab-new"
          @click="emit('navigate')"
        >新建活动</router-link>
        <router-link
          class="nav-item item"
          :class="{ active: consoleTab === 'validate' }"
          :to="{ name: 'validate' }"
          data-testid="tab-validate"
          @click="emit('navigate')"
        >优惠验证</router-link>
      </div>
    </div>

    <div class="section">
      <router-link
        class="group-link"
        :class="{ current: inDemos }"
        :to="{ name: 'demos' }"
        data-testid="nav-demos"
        @click="emit('navigate')"
      >演示台</router-link>
      <div class="items">
        <span class="hint">Drools 18 Step · 目录在右侧</span>
      </div>
    </div>
  </nav>
</template>

<style scoped>
.side { display: flex; flex-direction: column; gap: var(--sp-5); }
.section { display: flex; flex-direction: column; gap: var(--sp-1); }
.group-link {
  display: block; text-decoration: none; font-size: var(--fs-xs);
  font-weight: var(--fw-semibold); letter-spacing: .04em; text-transform: uppercase;
  color: var(--text-faint); padding: var(--sp-1) var(--sp-2);
}
.group-link.current { color: var(--accent); }
.items { display: flex; flex-direction: column; gap: 2px; }
.item {
  display: flex; align-items: center; padding: var(--sp-2) var(--sp-3);
  border-radius: var(--radius-sm); text-decoration: none;
  color: var(--text-soft); font-size: var(--fs-md);
}
.item:hover { background: var(--bg-hover); color: var(--text); }
.item.active { background: var(--accent-soft); color: var(--accent); font-weight: var(--fw-medium); }
.hint { font-size: var(--fs-xs); color: var(--text-faint); padding: var(--sp-1) var(--sp-3); }
</style>
