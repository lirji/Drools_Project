<script setup lang="ts">
// 订单折扣定制摘要：展示原价→折后 + 命中折扣原因。
import { computed } from 'vue'
const props = defineProps<{ data: unknown }>()
const d = computed(() => (props.data || {}) as Record<string, any>)
function money(v: unknown): string { const n = Number(v); return isNaN(n) ? '-' : n.toFixed(2) }
</script>
<template>
  <div class="order">
    <div class="price">
      <span class="orig">¥{{ money(d.totalAmount) }}</span>
      <span class="arrow">→</span>
      <span class="final">¥{{ money(d.finalAmount) }}</span>
    </div>
    <div v-if="d.orderId" class="oid mono">订单 {{ d.orderId }}</div>
    <div v-if="(d.discountReasons || d.appliedPromotions)?.length" class="reasons">
      <span v-for="(r, i) in (d.discountReasons || d.appliedPromotions)" :key="i" class="tag">{{ typeof r === 'string' ? r : (r.message || r.name || JSON.stringify(r)) }}</span>
    </div>
  </div>
</template>
<style scoped>
.order { display: flex; flex-direction: column; gap: var(--sp-2); }
.price { display: flex; align-items: baseline; gap: var(--sp-3); }
.orig { color: var(--text-faint); text-decoration: line-through; }
.arrow { color: var(--text-soft); }
.final { color: var(--accent); font-size: 22px; font-weight: 600; }
.oid { font-size: 12px; color: var(--text-soft); }
.reasons { display: flex; flex-wrap: wrap; gap: var(--sp-1); }
.tag { background: var(--green-soft); color: var(--green); font-size: 12px; padding: 2px var(--sp-2); border-radius: var(--radius-sm); }
.mono { font-family: var(--mono); }
</style>
