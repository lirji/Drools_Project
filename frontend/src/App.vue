<script setup lang="ts">
import { onMounted, ref } from 'vue'

// 主题切换（平移旧 initTheme：data-theme + localStorage drools-theme）
const THEME_KEY = 'drools-theme'
const dark = ref(false)

onMounted(() => {
  const attr = document.documentElement.getAttribute('data-theme')
  dark.value = attr === 'dark'
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
  <header class="topbar">
    <div class="brand">
      <span class="logo">◆</span>
      <div class="brand-text">
        <h1>活动引擎控制台</h1>
        <span class="brand-sub">规则即数据 · 多租户营销活动决策</span>
      </div>
    </div>
    <div class="topbar-right">
      <a class="link" href="/index.html">旧演示台</a>
      <button class="theme-btn" title="切换主题" data-testid="theme-btn" @click="toggleTheme">◐</button>
    </div>
  </header>
  <router-view />
</template>

<style>
.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--sp-3) var(--sp-5);
  background: var(--bg-elev);
  border-bottom: 1px solid var(--border);
  box-shadow: var(--shadow-sm);
}
.brand { display: flex; align-items: center; gap: var(--sp-3); }
.logo { font-size: 22px; color: var(--accent); }
.brand-text h1 { margin: 0; font-size: 17px; }
.brand-sub { font-size: 12px; color: var(--text-soft); }
.topbar-right { display: flex; align-items: center; gap: var(--sp-4); }
.topbar-right .link { font-size: 13px; color: var(--text-soft); text-decoration: none; }
.topbar-right .link:hover { color: var(--accent); }
.theme-btn {
  width: 34px; height: 34px; border-radius: var(--radius-sm);
  border: 1px solid var(--border); background: var(--bg-soft);
  color: var(--text); cursor: pointer;
}
</style>
