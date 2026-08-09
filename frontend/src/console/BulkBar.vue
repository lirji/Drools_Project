<script setup lang="ts">
/**
 * 批量操作条（PR-5）。选中非空时从工具条下方压出，并**粘在顶栏底下**——
 * 一屏 16 行时选完往下滚，操作按钮不能跟着滚出视野。
 *
 * <p>「选中全部匹配的 N 项」是跨页选择：当前页勾满时才出现，避免运营以为
 * 「全选」= 全部数据，实际只勾了本页 20 条就点了下线。
 */
const props = defineProps<{
  /** 已选条数 */
  count: number
  /** 当前筛选条件下的总条数（本仓列表是全量拉取 + 前端筛选，故它就是真实的 totalMatched） */
  matched: number
  /** 当前页是否已全部勾选（决定要不要提示跨页全选） */
  pageAllSelected: boolean
  /** 是否已经处于「全部匹配项」选中态 */
  allMatchedSelected: boolean
  busy?: boolean
}>()

defineEmits<{
  (e: 'select-all-matched'): void
  (e: 'clear'): void
  (e: 'bulk', target: 1 | 2): void
}>()

void props
</script>

<template>
  <div class="bulkbar" role="group" aria-label="批量操作" data-testid="bulk-bar">
    <span class="cnt" data-testid="bulk-count">已选 <strong>{{ count }}</strong> 项</span>

    <button
      v-if="pageAllSelected && !allMatchedSelected && matched > count"
      type="button"
      class="link"
      data-testid="bulk-select-all-matched"
      @click="$emit('select-all-matched')"
    >选中全部匹配的 {{ matched }} 项</button>
    <span v-else-if="allMatchedSelected" class="hint" data-testid="bulk-all-matched">已选中全部匹配项</span>

    <span class="spacer" />

    <button type="button" class="act" :disabled="busy" data-testid="bulk-online" @click="$emit('bulk', 1)">
      批量上线
    </button>
    <button type="button" class="act danger" :disabled="busy" data-testid="bulk-offline" @click="$emit('bulk', 2)">
      批量下线
    </button>
    <button type="button" class="link" data-testid="bulk-clear" @click="$emit('clear')">清除选择</button>
  </div>
</template>

<style scoped>
.bulkbar {
  position: sticky; top: var(--shell-topbar-h); z-index: var(--z-sticky);
  display: flex; align-items: center; flex-wrap: wrap; gap: var(--sp-2);
  min-width: 0; padding: var(--sp-2) var(--sp-5);
  border-bottom: 1px solid var(--border);
  background: var(--accent-soft); color: var(--text);
  animation: bulk-in 180ms ease-out;
}
/* 「压出」——只在出现时放一次，不是常驻循环动画（反向验收禁止项） */
@keyframes bulk-in { from { transform: translateY(-6px); opacity: 0; } to { transform: none; opacity: 1; } }
.cnt { font-size: var(--fs-xs); }
.cnt strong { font-family: var(--mono); font-variant-numeric: tabular-nums; }
.hint { color: var(--text-faint); font-size: var(--fs-xs); }
.spacer { flex: 1 1 auto; min-width: 0; }
.act {
  min-height: 30px; padding: var(--sp-1) var(--sp-3);
  border: 1px solid var(--border-strong); border-radius: var(--radius-sm);
  background: var(--bg-elev); color: var(--text); cursor: pointer; font: inherit; font-size: var(--fs-xs);
}
.act:hover:not(:disabled) { background: var(--bg-hover); }
.act.danger { border-color: var(--err); color: var(--err); }
.act:disabled { cursor: wait; opacity: .6; }
.link { border: 0; background: transparent; color: var(--accent); cursor: pointer; font: inherit; font-size: var(--fs-xs); text-decoration: underline; }
@media (pointer: coarse) { .act, .link { min-height: var(--touch-min); } }
@media (max-width: 1023px) { .bulkbar { padding: var(--sp-2) var(--sp-3); border-radius: var(--radius); border: 1px solid var(--accent-line); } }
</style>
