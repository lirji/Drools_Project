<script setup lang="ts">
/**
 * OIDC 回调中转页（视觉换代 0809 · 步骤 6）。
 * 换代前这里只有一行纯文字「正在完成登录…」——它是登录流程里唯一会短暂停留的一屏，
 * 没有任何视觉反馈时用户会以为卡死。现在给品牌感的加载态，失败态保留原有兜底出口。
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/auth/useAuthStore'
import Icon from '@/shared/ui/Icon.vue'

const auth = useAuthStore()
const router = useRouter()
const err = ref('')

onMounted(async () => {
  try {
    await auth.ensureConfig()
    const returnTo = await auth.completeCallback(window.location.search)
    // 清 ?code= 防重放：用 router.replace 到 returnTo（保路由态，不残留 query）
    await router.replace(returnTo)
  } catch (e) {
    err.value = (e as Error).message
  }
})
</script>

<template>
  <main class="cb-wrap" data-testid="callback-page">
    <div v-if="!err" class="cb-msg" role="status" aria-live="polite">
      <span class="cb-mark"><Icon name="logo" :size="26" /></span>
      <span class="cb-spin" aria-hidden="true" />
      <p>正在完成登录…</p>
      <small>正在与统一身份服务交换令牌，请稍候。</small>
    </div>
    <div v-else class="cb-err" role="alert">
      <span class="cb-mark err"><Icon name="alert-triangle" :size="24" /></span>
      <p>登录回调处理失败：{{ err }}</p>
      <router-link to="/login">返回登录</router-link>
    </div>
  </main>
</template>

<style scoped>
.cb-wrap {
  display: flex; align-items: center; justify-content: center;
  min-height: 100dvh; padding: var(--sp-6);
  background: var(--hero-bg);
}
.cb-msg, .cb-err {
  display: flex; flex-direction: column; align-items: center; gap: var(--sp-3);
  padding: var(--sp-8) var(--sp-7); text-align: center;
  border: 1px solid color-mix(in srgb, var(--on-deep) 14%, transparent);
  border-radius: var(--radius-lg);
  background: color-mix(in srgb, var(--surface-deep) 55%, transparent);
  box-shadow: var(--shadow-md);
}
.cb-mark {
  display: inline-flex; align-items: center; justify-content: center;
  width: 52px; height: 52px; border-radius: var(--radius);
  background: linear-gradient(180deg, var(--accent-hover), var(--accent));
  color: var(--text-invert); box-shadow: inset 0 1px 0 rgba(255, 255, 255, .22), var(--glow);
}
.cb-mark.err { background: var(--err-soft); color: var(--err); box-shadow: none; }
/* 关键帧走 effects.css 的全局 spin。 */
.cb-spin {
  width: 20px; height: 20px; border-radius: 50%;
  border: 2px solid color-mix(in srgb, var(--on-deep) 22%, transparent);
  border-top-color: var(--accent-2);
  animation: spin .9s linear infinite;
}
.cb-msg p { margin: 0; color: var(--on-deep); font-size: var(--fs-lg); font-weight: var(--fw-medium); }
.cb-msg small { color: var(--on-deep-faint); font-size: var(--fs-sm); }
.cb-err p { margin: 0; color: var(--err); }
.cb-err a { color: var(--accent-2); }
</style>
