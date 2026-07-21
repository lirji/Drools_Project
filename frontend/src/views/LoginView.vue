<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/auth/useAuthStore'

const auth = useAuthStore()
const route = useRoute()
const clients = ref<Array<{ tenant: string; clientId: string }>>([])
const err = ref('')

onMounted(async () => {
  await auth.ensureConfig()
  clients.value = auth.cfg?.webClients || []
})

async function doLogin(clientId: string): Promise<void> {
  const returnTo = (route.query.returnTo as string) || '/home'
  try {
    await auth.beginLogin(clientId, returnTo)
  } catch (e) {
    err.value = (e as Error).message
  }
}
</script>

<template>
  <main class="login-wrap" data-testid="login-page">
    <div class="login-card">
      <h2>活动引擎控制台 · 登录</h2>
      <p class="hint">
        已开启 Casdoor 鉴权（auth 档）：访问活动数据需先登录，租户由登录应用的 token aud 决定。
      </p>
      <p class="pick">请选择租户登录 —— 跳转 Casdoor 完成授权码 + PKCE 登录后自动返回本页。</p>
      <div class="btns">
        <button
          v-for="w in clients"
          :key="w.clientId"
          class="run-btn"
          :data-testid="'login-' + w.tenant"
          @click="doLogin(w.clientId)"
        >
          🔐 登录 {{ w.tenant }}
        </button>
      </div>
      <p v-if="!clients.length" class="err">auth-config 未配置 web-client-map，无可用登录应用。</p>
      <p v-if="err" class="err">{{ err }}</p>
    </div>
  </main>
</template>

<style scoped>
.login-wrap { display: flex; justify-content: center; padding: var(--sp-6) var(--sp-4); }
.login-card {
  max-width: 460px; width: 100%; background: var(--bg-elev);
  border: 1px solid var(--border); border-radius: var(--radius);
  padding: var(--sp-5); box-shadow: var(--shadow);
}
.login-card h2 { margin: 0 0 var(--sp-3); }
.hint, .pick { font-size: 13px; color: var(--text-soft); line-height: 1.6; }
.btns { display: flex; flex-wrap: wrap; gap: var(--sp-3); margin-top: var(--sp-4); }
.run-btn {
  min-height: var(--touch-min); padding: 0 var(--sp-5);
  border: none; border-radius: var(--radius-sm);
  background: var(--accent); color: #fff; font-size: 14px; cursor: pointer;
}
.err { color: var(--err); font-size: 13px; margin-top: var(--sp-3); }
</style>
