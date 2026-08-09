<script setup lang="ts">
/**
 * 批量操作的**影响摘要**弹窗（PR-5）。
 *
 * <p>不用 `useConfirm`：那个只有标题 + 一段文字，而这里要先把「会发生什么」按状态拆开算给运营看，
 * 并在规模够大时要求手输数量。批量下线是大促前最高危的操作，
 * 「点错了」和「点对了但没意识到范围」是两种不同的事故，后者只有影响摘要能防。
 */
import { computed, nextTick, ref, watch } from 'vue'
import { lockScroll, unlockScroll } from '@/shared/useScrollLock'
import type { BenchRow } from './benchModel'

/** 超过这个规模就要求手输数量确认——小批量加摩擦只会让人形成"闭眼确认"的肌肉记忆 */
const TYPE_TO_CONFIRM_THRESHOLD = 10
/** 摘要里最多点名几个活动，其余折成计数 */
const NAME_PREVIEW = 5

const props = defineProps<{
  open: boolean
  target: 1 | 2
  rows: BenchRow[]
}>()

const emit = defineEmits<{ (e: 'confirm'): void; (e: 'cancel'): void }>()

const typed = ref('')
const okBtn = ref<HTMLButtonElement | null>(null)
const input = ref<HTMLInputElement | null>(null)
let locked = false

const count = computed(() => props.rows.length)
const isOffline = computed(() => props.target === 2)
const needsTyping = computed(() => count.value >= TYPE_TO_CONFIRM_THRESHOLD)
const canConfirm = computed(() => count.value > 0 && (!needsTyping.value || typed.value.trim() === String(count.value)))

/** 正在服务的那些——下线会**立刻**停止它们参与决策命中，这是唯一有即时线上后果的一类 */
const liveCount = computed(() => props.rows.filter((r) => r.state === 'live').length)
const draftCount = computed(() => props.rows.filter((r) => r.state === 'draft').length)
const withDraft = computed(() => props.rows.filter((r) => r.draftVersion !== null).length)
const names = computed(() => props.rows.slice(0, NAME_PREVIEW).map((r) => r.activityName))

watch(() => props.open, async (isOpen) => {
  if (isOpen) {
    typed.value = ''
    if (!locked) { lockScroll(); locked = true }
    window.addEventListener('keydown', onKey, true)
    await nextTick()
    // 需要手输时焦点给输入框，否则给确认键——少一次 Tab
    ;(needsTyping.value ? input.value : okBtn.value)?.focus()
  } else {
    window.removeEventListener('keydown', onKey, true)
    if (locked) { unlockScroll(); locked = false }
  }
})

function onKey(e: KeyboardEvent): void {
  if (e.key === 'Escape') {
    e.stopPropagation()
    emit('cancel')
  }
}
</script>

<template>
  <div v-if="open" class="scrim" data-testid="bulk-confirm" @click.self="emit('cancel')">
    <div class="dialog" role="dialog" aria-modal="true" :aria-label="`批量${isOffline ? '下线' : '上线'} ${count} 个活动`">
      <h2 class="ttl">批量{{ isOffline ? '下线' : '上线' }} <b>{{ count }}</b> 个活动？</h2>

      <ul class="impact">
        <template v-if="isOffline">
          <li v-if="liveCount" class="hot">
            其中 <b>{{ liveCount }}</b> 个<strong>正在生效</strong>，下线后立即停止参与线上优惠决策。
          </li>
          <li v-if="count - liveCount">
            另外 <b>{{ count - liveCount }}</b> 个当前未在生效（草稿或已过期），仅状态被置为已下线。
          </li>
          <li v-if="withDraft" class="note">
            有 <b>{{ withDraft }}</b> 个存在更高版本的草稿——本次下线的是**正在服务的那一版**，草稿不受影响。
          </li>
        </template>
        <template v-else>
          <li class="hot">上线是一次<strong>真实发布</strong>：会推进发布代际、并退役该活动其它仍在线的版本。</li>
          <li v-if="draftCount">其中 <b>{{ draftCount }}</b> 个当前是草稿，发布后立即开始参与决策。</li>
          <li class="note">若开启了四眼审批，提交人不能发布自己提交的活动——被拒的会逐条出现在回执里。</li>
        </template>
      </ul>

      <p class="names">
        <span v-for="n in names" :key="n" class="chip">{{ n }}</span>
        <span v-if="count > names.length" class="more">等共 {{ count }} 项</span>
      </p>

      <label v-if="needsTyping" class="typebox">
        <span>影响范围较大，请输入 <b>{{ count }}</b> 以确认：</span>
        <input
          ref="input"
          v-model="typed"
          type="text"
          inputmode="numeric"
          autocomplete="off"
          :placeholder="String(count)"
          data-testid="bulk-confirm-count"
        />
      </label>

      <div class="acts">
        <button type="button" class="btn" data-testid="bulk-confirm-cancel" @click="emit('cancel')">取消</button>
        <button
          ref="okBtn"
          type="button"
          class="btn go"
          :class="{ danger: isOffline }"
          :disabled="!canConfirm"
          data-testid="bulk-confirm-ok"
          @click="emit('confirm')"
        >确认{{ isOffline ? '下线' : '上线' }}</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.scrim {
  position: fixed; inset: 0; z-index: var(--z-modal);
  display: flex; align-items: center; justify-content: center; padding: var(--sp-4);
  background: var(--scrim);
}
.dialog {
  width: 100%; max-width: 480px; max-height: calc(100dvh - 2 * var(--sp-4)); overflow-y: auto;
  padding: var(--sp-5); background: var(--bg-elev);
  border: 1px solid var(--border); border-radius: var(--radius-lg); box-shadow: var(--shadow-lg);
}
.ttl { margin: 0 0 var(--sp-3); font-size: var(--fs-lg); }
.ttl b { font-family: var(--mono); font-variant-numeric: tabular-nums; }
.impact { margin: 0 0 var(--sp-3); padding-left: 1.1em; color: var(--text-soft); font-size: var(--fs-sm); line-height: var(--lh-normal); }
.impact li { margin-bottom: 3px; }
.impact b { font-family: var(--mono); font-variant-numeric: tabular-nums; color: var(--text); }
.impact .hot strong { color: var(--err); }
.impact .note { color: var(--text-faint); font-size: var(--fs-xs); }
.names { display: flex; flex-wrap: wrap; gap: var(--sp-1); margin: 0 0 var(--sp-4); }
.chip {
  max-width: 100%; overflow: hidden; padding: 1px var(--sp-2);
  border: 1px solid var(--border); border-radius: var(--radius-pill);
  color: var(--text-soft); font-size: var(--fs-xs); text-overflow: ellipsis; white-space: nowrap;
}
.more { color: var(--text-faint); font-size: var(--fs-xs); align-self: center; }
.typebox { display: block; margin-bottom: var(--sp-4); font-size: var(--fs-xs); color: var(--text-soft); }
.typebox b { font-family: var(--mono); color: var(--text); }
.typebox input {
  display: block; width: 100%; min-height: 38px; margin-top: var(--sp-2);
  padding: 0 var(--sp-3); border: 1px solid var(--border); border-radius: var(--radius-sm);
  background: var(--bg-elev); color: var(--text); font: inherit; font-family: var(--mono);
}
.typebox input:focus { outline: none; border-color: var(--accent); box-shadow: var(--focus-ring); }
.acts { display: flex; justify-content: flex-end; gap: var(--sp-2); }
.btn {
  min-height: 38px; padding: var(--sp-2) var(--sp-4); border-radius: var(--radius-sm);
  border: 1px solid var(--border); background: var(--bg-elev); color: var(--text);
  font: inherit; font-size: var(--fs-sm); cursor: pointer;
}
.btn:hover:not(:disabled) { background: var(--bg-hover); }
/* 压在 accent 上的字走 --text-invert，不写死 #fff——深色态 accent 是浅粉，白字读不出来（评审 X7） */
.btn.go { background: var(--accent); border-color: var(--accent); color: var(--text-invert); }
.btn.go.danger { background: var(--err); border-color: var(--err); color: var(--text-invert); }
.btn:disabled { cursor: not-allowed; opacity: .5; }
@media (pointer: coarse) { .btn { min-height: var(--touch-min); } }
</style>
