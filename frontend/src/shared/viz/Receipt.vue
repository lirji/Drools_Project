<script setup lang="ts">
/**
 * 票据式金额排版（PR-3）。标签左对齐 → 圆点 leader 拉过去 → 金额右对齐等宽 tabular-nums；
 * 合计上方画**会计双线**（2px + 上方 1px 双道）。
 *
 * 三个可用性理由，都不是审美：
 * ① 跨行比金额时眼睛沿点线走，不会串行；
 * ② 小数点垂直对齐后，量级差是「看」出来的不是「读」出来的；
 * ③ 会计双线是财务人员的既有约定，零学习成本地讲清「这是结果不是参数」。
 */
export interface ReceiptLine { label: string; amount: number | string; hit?: boolean; muted?: boolean }
defineProps<{ lines: ReceiptLine[]; total?: { label: string; amount: number | string } }>()

function money(v: number | string): string {
  const n = Number(v)
  return Number.isNaN(n) ? String(v) : n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
</script>

<template>
  <div class="rc">
    <div v-for="(l, i) in lines" :key="i" class="row" :class="{ hit: l.hit, muted: l.muted }">
      <span class="k">{{ l.label }}</span>
      <span class="lead" />
      <span class="amt"><s>¥</s>{{ money(l.amount) }}</span>
    </div>
    <div v-if="total" class="total">
      <div class="row">
        <span class="k">{{ total.label }}</span>
        <span class="lead" />
        <span class="amt big"><s>¥</s>{{ money(total.amount) }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.rc { font-size: var(--fs-sm); }
.row { display: flex; align-items: baseline; gap: var(--gap-inline); padding: 5px 0; }
.k { white-space: nowrap; }
.lead {
  flex: 1; min-width: 14px; height: 1px; align-self: flex-end; margin-bottom: 4px;
  background: radial-gradient(circle, var(--seam) 0 .9px, transparent 1px) 0 50% / 5px 2px repeat-x;
}
.amt {
  font-family: var(--mono); font-variant-numeric: tabular-nums;
  min-width: 104px; text-align: right; letter-spacing: -.01em; font-size: var(--fs-md);
}
.amt s { font-size: var(--fs-xs); color: var(--text-faint); text-decoration: none; margin-right: 1px; }
.row.hit { background: var(--accent-soft); margin-inline: -8px; padding-inline: 8px; border-radius: var(--radius-sm); }
.row.hit .k, .row.hit .amt { color: var(--accent); font-weight: var(--fw-bold); }
.row.muted .k, .row.muted .amt { color: var(--text-faint); }
/* 会计双线：2px 主线 + 上方 4px 处一道 1px 同色线 */
.total { position: relative; margin-top: var(--gap-inline); padding-top: var(--gap-inline); border-top: 2px solid var(--rule); }
.total::before { content: ''; position: absolute; left: 0; right: 0; top: -4px; height: 1px; background: var(--rule); }
.amt.big { font-size: var(--fs-xl); font-weight: var(--fw-medium); letter-spacing: -.02em; }
</style>
