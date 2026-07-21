<script setup lang="ts">
/**
 * 顶部工具条（重设计）：左=汉堡(<768) + 品牌；右=全局身份条 + 主题切换。
 * 主题逻辑自 App.vue 迁入（保留 theme-btn testid + onMounted 回读 data-theme 校准，避免首次点击方向错，评审 I6）。
 */
import { onMounted, ref } from 'vue'
import IdentityBar from './IdentityBar.vue'
import Icon from '@/shared/ui/Icon.vue'

defineEmits<{ (e: 'toggle-nav'): void }>()

const THEME_KEY = 'drools-theme'
const dark = ref(false)
onMounted(() => {
  dark.value = document.documentElement.getAttribute('data-theme') === 'dark'
})
function toggleTheme(): void {
  dark.value = !dark.value
  const t = dark.value ? 'dark' : 'light'
  document.documentElement.setAttribute('data-theme', t)
  try {
    localStorage.setItem(THEME_KEY, t)
  } catch {
    /* ignore */
  }
}
</script>

<template>
  <div class="topbar">
    <div class="left">
      <button class="hamburger" aria-label="打开导航" data-testid="nav-toggle" @click="$emit('toggle-nav')">
        <Icon name="menu" :size="20" />
      </button>
      <router-link class="brand-link" :to="{ name: 'home' }" aria-label="返回概览首页">
        <span class="logo"><Icon name="logo" :size="22" /></span>
        <span class="brand">活动引擎控制台</span>
      </router-link>
    </div>
    <div class="right">
      <IdentityBar />
      <button
        class="theme-btn"
        :title="dark ? '切换到浅色' : '切换到深色'"
        :aria-label="dark ? '切换到浅色主题' : '切换到深色主题'"
        :aria-pressed="dark"
        data-testid="theme-btn"
        @click="toggleTheme"
      ><Icon :name="dark ? 'sun' : 'moon'" :size="18" /></button>
    </div>
  </div>
</template>

<style scoped>
.topbar {
  height: 100%; display: flex; align-items: center; justify-content: space-between;
  gap: var(--sp-4); padding: 0 var(--sp-4);
}
.left { display: flex; align-items: center; gap: var(--sp-3); min-width: 0; }
.hamburger {
  display: none; align-items: center; justify-content: center;
  width: 40px; min-height: 40px; border: 1px solid var(--border);
  border-radius: var(--radius-sm); background: var(--bg-soft); color: var(--text);
  cursor: pointer; transition: background .12s ease, border-color .12s ease;
}
.hamburger:hover { background: var(--bg-hover); }
.brand-link {
  display: inline-flex; align-items: center; gap: var(--sp-2); min-width: 0;
  color: var(--text); text-decoration: none; border-radius: var(--radius-sm);
}
.brand-link:hover .brand { color: var(--accent); }
.logo {
  display: inline-flex; align-items: center; justify-content: center;
  width: 30px; height: 30px; border-radius: var(--radius-sm);
  background: var(--accent-soft); color: var(--accent);
}
.brand { font-size: var(--fs-lg); font-weight: var(--fw-semibold); white-space: nowrap; letter-spacing: -.01em; }
.right { display: flex; align-items: center; gap: var(--sp-4); }
.theme-btn {
  display: inline-flex; align-items: center; justify-content: center;
  width: 36px; height: 36px; border-radius: var(--radius-sm);
  border: 1px solid var(--border); background: var(--bg-soft); color: var(--text); cursor: pointer;
  transition: background .12s ease, border-color .12s ease;
}
.theme-btn:hover { background: var(--bg-hover); }
@media (pointer: coarse) { .theme-btn { min-height: var(--touch-min); } }
@media (max-width: 767px) {
  .hamburger { display: inline-flex; }
  .brand { font-size: var(--fs-md); }
}
</style>
