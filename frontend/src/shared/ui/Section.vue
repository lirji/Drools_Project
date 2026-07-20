<script setup lang="ts">
/**
 * 内容分组（重设计）：带可选编号徽章 + 标题 + 描述的卡片式分区，替代 EditorView 的 .sec-title / DetailView 裸卡。
 */
defineProps<{ title: string; num?: number | string; desc?: string }>()
</script>

<template>
  <section class="section">
    <header class="head">
      <span v-if="num !== undefined" class="num">{{ num }}</span>
      <div class="ht">
        <h2 class="title">{{ title }}</h2>
        <p v-if="desc" class="desc">{{ desc }}</p>
      </div>
      <div v-if="$slots.actions" class="sec-actions"><slot name="actions" /></div>
    </header>
    <div class="body"><slot /></div>
  </section>
</template>

<style scoped>
.section {
  background: var(--bg-elev); border: 1px solid var(--border);
  border-radius: var(--radius); box-shadow: var(--shadow-sm);
  padding: var(--sp-4); margin-bottom: var(--sp-4);
}
.head { display: flex; align-items: flex-start; gap: var(--sp-3); margin-bottom: var(--sp-3); }
.num {
  flex-shrink: 0; width: 24px; height: 24px; border-radius: var(--radius-pill);
  display: inline-flex; align-items: center; justify-content: center;
  background: var(--accent-soft); color: var(--accent); font-size: var(--fs-xs); font-weight: var(--fw-semibold);
}
.ht { flex: 1; min-width: 0; }
.title { margin: 0; font-size: var(--fs-lg); font-weight: var(--fw-semibold); line-height: var(--lh-tight); }
.desc { margin: 2px 0 0; font-size: var(--fs-xs); color: var(--text-soft); }
.sec-actions { display: flex; gap: var(--sp-2); }
</style>
