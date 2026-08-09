<script setup lang="ts">
/**
 * 页头（重设计）：逐页标题 + 面包屑 + 主操作槽。替代原先各页各搓 toolbar/孤立返回按钮、无逐页标题的现状。
 */
defineProps<{
  title: string
  subtitle?: string
  breadcrumb?: { label: string; to?: object }[]
  /** PR-2 新增：标题上方的小字眉标（纯拉丁，加大字距）。不传则不渲染，对既有调用点零影响。 */
  kicker?: string
}>()
</script>

<template>
  <header class="page-header">
    <nav v-if="breadcrumb && breadcrumb.length" class="crumbs" aria-label="面包屑">
      <template v-for="(c, i) in breadcrumb" :key="i">
        <router-link v-if="c.to" class="crumb link" :to="c.to">{{ c.label }}</router-link>
        <span v-else class="crumb">{{ c.label }}</span>
        <span v-if="i < breadcrumb.length - 1" class="sep">/</span>
      </template>
    </nav>
    <div class="row">
      <div class="titles">
        <span v-if="kicker" class="kicker">{{ kicker }}</span>
        <h1 class="title">{{ title }}</h1>
        <p v-if="subtitle" class="subtitle">{{ subtitle }}</p>
      </div>
      <div v-if="$slots.actions" class="actions"><slot name="actions" /></div>
    </div>
  </header>
</template>

<style scoped>
.page-header { margin-bottom: var(--sp-5); }
.crumbs { display: flex; align-items: center; gap: var(--sp-2); font-size: var(--fs-xs); color: var(--text-faint); margin-bottom: var(--sp-2); }
.crumb.link { text-decoration: none; color: var(--text-soft); }
.crumb.link:hover { color: var(--accent); }
.sep { color: var(--text-faint); }
.row { display: flex; align-items: flex-end; justify-content: space-between; gap: var(--sp-4); flex-wrap: wrap; }
.kicker {
  display: block; margin-bottom: 3px;
  font-family: var(--mono); font-size: var(--fs-xs); font-weight: var(--fw-bold);
  letter-spacing: .16em; text-transform: uppercase; color: var(--text-faint);
}
.title { margin: 0; font-size: var(--fs-2xl); font-weight: var(--fw-bold); line-height: var(--lh-tight); letter-spacing: -.02em; }
.subtitle { margin: var(--sp-2) 0 0; font-size: var(--fs-md); color: var(--text-soft); line-height: var(--lh-normal); }
.actions { display: flex; align-items: center; gap: var(--sp-2); }
</style>
