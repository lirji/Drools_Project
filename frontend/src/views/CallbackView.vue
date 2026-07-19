<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/auth/useAuthStore'

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
    <div v-if="!err" class="cb-msg">正在完成登录…</div>
    <div v-else class="cb-err">
      <p>登录回调处理失败：{{ err }}</p>
      <router-link to="/login">返回登录</router-link>
    </div>
  </main>
</template>

<style scoped>
.cb-wrap { display: flex; justify-content: center; padding: var(--sp-6); }
.cb-msg { color: var(--text-soft); }
.cb-err { color: var(--err); text-align: center; }
</style>
