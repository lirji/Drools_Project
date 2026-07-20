<script setup lang="ts">
/**
 * 顶部工具条（重设计）：左=汉堡(<768) + 品牌；右=全局身份条 + 主题切换。
 * 主题逻辑自 App.vue 迁入（保留 theme-btn testid + onMounted 回读 data-theme 校准，避免首次点击方向错，评审 I6）。
 */
import { onMounted, ref } from 'vue'
import IdentityBar from './IdentityBar.vue'

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
      <button class="hamburger" aria-label="打开导航" data-testid="nav-toggle" @click="$emit('toggle-nav')">☰</button>
      <span class="logo">◆</span>
      <span class="brand">活动引擎控制台</span>
    </div>
    <div class="right">
      <IdentityBar />
      <button class="theme-btn" title="切换主题" data-testid="theme-btn" @click="toggleTheme">◐</button>
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
  font-size: 16px; cursor: pointer;
}
.logo { font-size: 20px; color: var(--accent); }
.brand { font-size: var(--fs-lg); font-weight: var(--fw-semibold); white-space: nowrap; }
.right { display: flex; align-items: center; gap: var(--sp-4); }
.theme-btn {
  width: 36px; height: 36px; border-radius: var(--radius-sm);
  border: 1px solid var(--border); background: var(--bg-soft); color: var(--text); cursor: pointer;
}
@media (pointer: coarse) { .theme-btn { min-height: var(--touch-min); } }
@media (max-width: 767px) {
  .hamburger { display: inline-flex; }
  .brand { font-size: var(--fs-md); }
}
</style>
