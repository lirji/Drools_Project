<script setup lang="ts">
// 通用结构化响应渲染：把 JSON 美化 + 顶层键值高亮。覆盖全部 33 demo 的诚实兜底。
// 旧原生页保留了 21 个 per-Step 定制可视化（作为教学展品与回退），此处走统一结构化视图。
defineProps<{ data: unknown }>()

function pretty(v: unknown): string {
  try { return JSON.stringify(v, null, 2) } catch { return String(v) }
}
function topEntries(v: unknown): Array<[string, unknown]> {
  if (v && typeof v === 'object' && !Array.isArray(v)) return Object.entries(v as Record<string, unknown>)
  return []
}
function scalar(v: unknown): boolean {
  return v == null || typeof v !== 'object'
}
</script>

<template>
  <div class="gen">
    <div v-if="topEntries(data).length" class="kv-grid">
      <template v-for="[k, val] in topEntries(data)" :key="k">
        <div v-if="scalar(val)" class="kv">
          <span class="k">{{ k }}</span>
          <span class="v" :class="{ mono: typeof val === 'number' || typeof val === 'boolean' }">{{ val }}</span>
        </div>
      </template>
    </div>
    <details class="raw" open>
      <summary>完整响应 JSON</summary>
      <pre class="box">{{ pretty(data) }}</pre>
    </details>
  </div>
</template>

<style scoped>
.kv-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: var(--sp-1) var(--sp-4); margin-bottom: var(--sp-3); }
.kv { display: flex; justify-content: space-between; gap: var(--sp-2); font-size: 13px; padding: var(--sp-1) 0; border-bottom: 1px dashed var(--border); }
.k { color: var(--text-soft); }
.v { color: var(--text); }
.mono { font-family: var(--mono); color: var(--accent); }
.raw summary { cursor: pointer; font-size: 12px; color: var(--text-soft); }
.box { font-family: var(--mono); font-size: 12px; background: var(--bg-soft); border: 1px solid var(--border); border-radius: var(--radius-sm); padding: var(--sp-3); overflow-x: auto; white-space: pre; }
</style>
