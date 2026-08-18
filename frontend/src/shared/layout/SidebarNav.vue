<script setup lang="ts">
/**
 * 全局主导航：概览 + 活动控制台的持久左侧栏。
 * 每项包含图标与简短说明，active 态使用 soft 填充和左侧强调条。
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import Icon from '@/shared/ui/Icon.vue'

const route = useRoute()
const emit = defineEmits<{ (e: 'navigate'): void }>()

// 承接 ConsoleShell 的 activeTab：detail/edit 归到「活动列表」
const consoleTab = computed(() => {
  const n = route.name as string
  if (n === 'activity-detail' || n === 'activity-edit' || n === 'activities') return 'activities'
  if (n === 'activity-new') return 'activity-new'
  if (n === 'playbooks') return 'playbooks'
  if (n === 'validate') return 'validate'
  return ''
})
const inConsole = computed(() => typeof route.path === 'string' && route.path.startsWith('/console'))
const inHome = computed(() => route.name === 'home')
</script>

<template>
  <nav class="side">
    <div class="section">
      <router-link
        class="nav-item item solo"
        :class="{ active: inHome }"
        :to="{ name: 'home' }"
        data-testid="nav-home"
        @click="emit('navigate')"
      >
        <span class="ic"><Icon name="home" :size="18" /></span>
        <span class="txt"><span class="label">概览</span><span class="desc">活动运营 · 最近活动</span></span>
      </router-link>
    </div>

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
        >
          <span class="ic"><Icon name="list" :size="18" /></span>
          <span class="txt"><span class="label">活动列表</span><span class="desc">浏览 · 复核 · 上下线</span></span>
        </router-link>
        <router-link
          class="nav-item item"
          :class="{ active: consoleTab === 'playbooks' }"
          :to="{ name: 'playbooks' }"
          data-testid="tab-playbooks"
          @click="emit('navigate')"
        >
          <span class="ic"><Icon name="layers" :size="18" /></span>
          <span class="txt"><span class="label">玩法模板</span><span class="desc">挑一个玩法开始</span></span>
        </router-link>
        <router-link
          class="nav-item item"
          :class="{ active: consoleTab === 'activity-new' }"
          :to="{ name: 'activity-new' }"
          data-testid="tab-new"
          @click="emit('navigate')"
        >
          <span class="ic"><Icon name="plus" :size="18" /></span>
          <span class="txt"><span class="label">新建活动</span><span class="desc">配置红包 / 买赠 / 加价购</span></span>
        </router-link>
        <router-link
          class="nav-item item"
          :class="{ active: consoleTab === 'validate' }"
          :to="{ name: 'validate' }"
          data-testid="tab-validate"
          @click="emit('navigate')"
        >
          <span class="ic"><Icon name="badge-check" :size="18" /></span>
          <span class="txt"><span class="label">优惠验证</span><span class="desc">对已上线活动跑决策</span></span>
        </router-link>
      </div>
    </div>

  </nav>
</template>

<style scoped>
.side { display: flex; flex-direction: column; gap: var(--sp-5); }
.section { display: flex; flex-direction: column; gap: var(--sp-1); }
.group-link {
  display: block; text-decoration: none; font-size: var(--fs-xs);
  font-weight: var(--fw-semibold); letter-spacing: .06em; text-transform: uppercase;
  color: var(--text-faint); padding: var(--sp-1) var(--sp-2);
}
.group-link.current { color: var(--accent); }
.items { display: flex; flex-direction: column; gap: 2px; }
.item.solo { margin: 0; }
.item {
  position: relative; display: flex; align-items: center; gap: var(--sp-3);
  padding: var(--sp-2) var(--sp-3); border-radius: var(--radius-sm);
  text-decoration: none; color: var(--text-soft);
  transition: background var(--dur-fast) var(--ease-out), color var(--dur-fast) var(--ease-out);
}
.ic { display: inline-flex; color: var(--text-faint); transition: color var(--dur-fast) var(--ease-out); flex: 0 0 auto; }
.txt { display: flex; flex-direction: column; min-width: 0; line-height: var(--lh-tight); }
.label { font-size: var(--fs-md); font-weight: var(--fw-medium); }
.desc { font-size: var(--fs-xs); color: var(--text-faint); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.item:hover { background: var(--bg-hover); color: var(--text); }
.item:hover .ic { color: var(--text-soft); }
.item.active { background: var(--accent-soft); color: var(--accent); }
.item.active .ic { color: var(--accent); }
.item.active .label { font-weight: var(--fw-semibold); }
.item.active .desc { color: var(--accent-2); }
/* active 左 3px accent 条 */
.item.active::before {
  content: ''; position: absolute; left: 0; top: 6px; bottom: 6px;
  width: 3px; border-radius: var(--radius-pill); background: var(--accent);
}
</style>
