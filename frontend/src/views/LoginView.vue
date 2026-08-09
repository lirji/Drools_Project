<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/auth/useAuthStore'
import { resolvePortalLaunch, sanitizeInternalPath } from '@/auth/portalLaunch'
import Icon from '@/shared/ui/Icon.vue'

const auth = useAuthStore()
const route = useRoute()
const clients = ref<Array<{ tenant: string; clientId: string }>>([])
const tenant = ref('')
const configLoading = ref(true)
const configError = ref('')
const formError = ref('')
const authEnabled = ref(false)
const pending = ref('') // 正在跳转的 clientId（防重复点击）
const portalStarted = ref(false)

const formReady = computed(
  () => !configLoading.value && !configError.value && authEnabled.value && clients.value.length > 0,
)

const FEATURES = [
  { icon: 'workflow', title: '规则编排与决策', desc: '可视化维护活动规则与决策条件' },
  { icon: 'zap', title: '实时规则执行', desc: '快速验证 Drools 规则执行结果' },
  { icon: 'layers', title: '多租户安全隔离', desc: 'Casdoor 统一身份与租户边界校验' },
]

onMounted(async () => {
  try {
    await auth.ensureConfig()
    authEnabled.value = auth.cfg?.authEnabled === true
    clients.value = authEnabled.value ? auth.cfg?.webClients || [] : []

    const launch = resolvePortalLaunch(route.query, auth.cfg)
    if (launch && !portalStarted.value) {
      portalStarted.value = true
      await doLogin(launch.clientId, launch.returnTo)
    }
  } catch (e) {
    configError.value = `认证配置加载失败：${(e as Error).message}`
  } finally {
    configLoading.value = false
  }
})

async function doLogin(clientId: string, returnToOverride?: string): Promise<void> {
  if (pending.value) return
  pending.value = clientId
  formError.value = ''
  const returnTo = returnToOverride ?? sanitizeInternalPath(route.query.returnTo) ?? '/home'
  try {
    await auth.beginLogin(clientId, returnTo)
    // 成功时页面已跳转 Casdoor，不复位 pending
  } catch (e) {
    formError.value = (e as Error).message
    pending.value = ''
  }
}

async function loginTenant(): Promise<void> {
  if (pending.value || !formReady.value) return
  const normalized = tenant.value.trim()
  if (!normalized) {
    formError.value = '请输入租户'
    return
  }
  const selected = clients.value.find((client) => client.tenant === normalized)
  if (!selected) {
    formError.value = `未知租户 ${normalized}，请选择当前已开放的租户。`
    return
  }
  await doLogin(selected.clientId)
}

function selectTenant(value: string): void {
  if (!formReady.value || pending.value) return
  tenant.value = value
  formError.value = ''
}
</script>

<template>
  <main class="login-root" data-testid="login-page">
    <div class="login-aurora" aria-hidden="true">
      <span class="aurora-blob aurora-blob--one" />
      <span class="aurora-blob aurora-blob--two" />
      <span class="aurora-grid" />
    </div>

    <div class="login-stage">
      <section class="login-card" aria-labelledby="login-title">
        <aside class="login-brand" aria-label="活动引擎平台能力">
          <div>
            <div class="brand-logo">
              <span class="brand-logo__mark"><Icon name="logo" :size="28" /></span>
              <span>Activity Engine</span>
            </div>
            <p class="brand-kicker">DROOLS RULE PLATFORM</p>
            <h2>让每一次业务决策<br />清晰、可靠、可追溯</h2>
            <p class="brand-intro">从规则设计、版本管理到执行验证，在一个安全统一的工作台中完成。</p>
          </div>

          <div class="brand-features">
            <div v-for="feature in FEATURES" :key="feature.title" class="brand-feature">
              <span class="brand-feature__icon"><Icon :name="feature.icon" :size="19" /></span>
              <span>
                <strong>{{ feature.title }}</strong>
                <small>{{ feature.desc }}</small>
              </span>
            </div>
          </div>

          <p class="brand-foot">Enterprise Rules · Secure by Design</p>
        </aside>

        <section class="login-form-panel">
          <div class="login-compact-head">
            <span class="compact-logo"><Icon name="logo" :size="24" /></span>
            <span>
              <strong>Activity Engine</strong>
              <small>活动引擎控制台</small>
            </span>
          </div>

          <div class="form-heading">
            <p class="form-eyebrow">CASDOOR SSO · PKCE</p>
            <h1 id="login-title">欢迎进入活动引擎控制台</h1>
            <p>输入已开通的租户，继续前往统一身份认证。</p>
          </div>

          <div v-if="configLoading" class="login-status" role="status" aria-live="polite">
            <span class="status-spinner" aria-hidden="true" />
            <span>正在加载认证配置…</span>
          </div>
          <div v-else-if="configError" class="login-alert" role="alert">
            <Icon name="alert-triangle" :size="18" />
            <span>{{ configError }}</span>
          </div>
          <div v-else-if="!authEnabled" class="login-alert" role="alert">
            <Icon name="alert-triangle" :size="18" />
            <span>当前环境未开启 Casdoor 认证，请联系管理员。</span>
          </div>
          <div v-else-if="!clients.length" class="login-alert" role="alert">
            <Icon name="alert-triangle" :size="18" />
            <span>auth-config 未配置 web-client-map，无可用登录应用。</span>
          </div>

          <form class="tenant-form" @submit.prevent="loginTenant">
            <label for="login-tenant">租户 / 组织名</label>
            <div class="tenant-input-wrap">
              <Icon class="tenant-input-icon" name="layers" :size="18" />
              <input
                id="login-tenant"
                v-model="tenant"
                list="login-tenant-options"
                autocomplete="organization"
                spellcheck="false"
                :disabled="!formReady || !!pending"
                :aria-invalid="!!formError"
                :aria-describedby="clients.length ? (formError ? 'tenant-help tenant-error' : 'tenant-help') : (formError ? 'tenant-error' : undefined)"
                placeholder="例如 acme"
                @input="formError = ''"
              />
            </div>
            <datalist id="login-tenant-options">
              <option v-for="client in clients" :key="client.clientId" :value="client.tenant" />
            </datalist>

            <div v-if="clients.length" id="tenant-help" class="tenant-options">
              <span>当前可用租户：{{ clients.map((client) => client.tenant).join('、') }}</span>
              <div class="tenant-chips">
                <button
                  v-for="client in clients"
                  :key="client.clientId"
                  class="tenant-chip"
                  :class="{ 'tenant-chip--active': tenant === client.tenant }"
                  type="button"
                  :disabled="!formReady || !!pending"
                  @click="selectTenant(client.tenant)"
                >
                  {{ client.tenant }}
                </button>
              </div>
            </div>

            <div v-if="formError" id="tenant-error" class="login-alert login-alert--form" role="alert">
              <Icon name="alert-triangle" :size="17" />
              <span>{{ formError }}</span>
            </div>

            <button class="login-primary" type="submit" :disabled="!formReady || !!pending" data-testid="login-submit">
              <span v-if="pending" class="button-spinner" aria-hidden="true" />
              <Icon v-else name="log-in" :size="18" />
              <span>{{ pending ? '正在前往 Casdoor…' : '使用统一身份登录' }}</span>
              <Icon v-if="!pending" class="button-arrow" name="arrow-right" :size="17" />
            </button>
          </form>

          <div class="security-note">
            <Icon name="badge-check" :size="17" />
            <span>由 Casdoor 提供统一身份认证，使用 OIDC Authorization Code + PKCE 安全流程</span>
          </div>
        </section>
      </section>

      <p class="login-footer">Activity Engine Console · 规则驱动业务增长</p>
    </div>
  </main>
</template>

<style scoped>
.login-root {
  position: relative;
  display: grid;
  min-height: 100vh;
  min-height: 100dvh;
  overflow-x: hidden;
  overflow-y: auto;
  color: var(--text);
  background:
    radial-gradient(circle at 12% 15%, color-mix(in srgb, var(--accent) 15%, transparent), transparent 31%),
    radial-gradient(circle at 88% 82%, color-mix(in srgb, var(--accent-2) 18%, transparent), transparent 30%),
    linear-gradient(145deg, var(--bg) 0%, color-mix(in srgb, var(--accent-soft) 58%, var(--bg)) 48%, var(--bg) 100%);
}

.login-aurora,
.aurora-grid {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.aurora-grid {
  opacity: .28;
  background-image:
    linear-gradient(color-mix(in srgb, var(--accent) 9%, transparent) 1px, transparent 1px),
    linear-gradient(90deg, color-mix(in srgb, var(--accent) 9%, transparent) 1px, transparent 1px);
  background-size: 40px 40px;
  -webkit-mask-image: radial-gradient(circle at center, #000, transparent 72%);
  mask-image: radial-gradient(circle at center, #000, transparent 72%);
}

.aurora-blob {
  position: absolute;
  width: 360px;
  height: 360px;
  border-radius: 50%;
  filter: blur(16px);
  opacity: .2;
  animation: aurora-float 11s ease-in-out infinite alternate;
}

.aurora-blob--one { top: -150px; right: 4%; background: var(--accent); }
.aurora-blob--two { bottom: -180px; left: 3%; background: var(--accent-2); animation-delay: -4s; }

.login-stage {
  position: relative;
  z-index: 1;
  display: flex;
  width: 100%;
  min-height: 100%;
  padding: 40px 24px 24px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}

.login-card {
  display: grid;
  grid-template-columns: minmax(330px, .88fr) minmax(430px, 1.12fr);
  width: min(940px, 100%);
  min-height: 570px;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--border) 70%, transparent);
  border-radius: 26px;
  background: color-mix(in srgb, var(--bg-elev) 88%, transparent);
  box-shadow: 0 30px 80px rgba(30, 41, 89, .18), 0 8px 28px rgba(30, 41, 89, .09);
  backdrop-filter: blur(20px);
}

.login-brand {
  position: relative;
  display: flex;
  overflow: hidden;
  padding: 48px 40px 36px;
  flex-direction: column;
  justify-content: space-between;
  color: var(--on-deep);
  background:
    radial-gradient(circle at 12% 12%, rgba(255,255,255,.22), transparent 34%),
    radial-gradient(circle at 95% 82%, rgba(34,211,238,.3), transparent 38%),
    var(--hero-bg);
}

.login-brand::after {
  position: absolute;
  right: -110px;
  bottom: -120px;
  width: 310px;
  height: 310px;
  border: 1px solid rgba(255,255,255,.18);
  border-radius: 48%;
  content: '';
  transform: rotate(28deg);
}

.brand-logo { display: flex; align-items: center; gap: 12px; font-size: 18px; font-weight: 700; letter-spacing: .01em; }
.brand-logo__mark,
.compact-logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  border: 1px solid rgba(255,255,255,.3);
  border-radius: 14px;
  background: rgba(255,255,255,.15);
  box-shadow: inset 0 1px 0 rgba(255,255,255,.2);
}
.brand-kicker { margin: 28px 0 8px; color: rgba(255,255,255,.7); font-size: 10px; font-weight: 700; letter-spacing: .18em; }
.login-brand h2 { margin: 0; font-size: 28px; line-height: 1.36; letter-spacing: -.02em; }
.brand-intro { max-width: 310px; margin: 16px 0 0; color: rgba(255,255,255,.78); font-size: 13px; line-height: 1.7; }
.brand-features { position: relative; z-index: 1; display: grid; gap: 18px; margin: 34px 0; }
.brand-feature { display: flex; align-items: center; gap: 13px; }
.brand-feature__icon { display: inline-flex; width: 38px; height: 38px; flex: 0 0 auto; align-items: center; justify-content: center; border: 1px solid rgba(255,255,255,.24); border-radius: 11px; background: rgba(255,255,255,.12); }
.brand-feature span:last-child { display: grid; gap: 2px; }
.brand-feature strong { font-size: 13px; font-weight: 600; }
.brand-feature small { color: rgba(255,255,255,.68); font-size: 11px; line-height: 1.45; }
.brand-foot { position: relative; z-index: 1; margin: 0; color: rgba(255,255,255,.56); font-size: 10px; letter-spacing: .08em; }

.login-form-panel { display: flex; min-width: 0; padding: 48px 50px 38px; flex-direction: column; justify-content: center; }
.login-compact-head { display: none; }
.form-heading { margin-bottom: 26px; }
.form-eyebrow { margin: 0 0 8px; color: var(--accent); font-size: 10px; font-weight: 700; letter-spacing: .16em; }
.form-heading h1 { margin: 0; color: var(--text); font-size: 25px; line-height: 1.35; letter-spacing: -.025em; }
.form-heading > p:last-child { margin: 9px 0 0; color: var(--text-soft); font-size: 13px; line-height: 1.6; }

.login-status,
.login-alert { display: flex; align-items: flex-start; gap: 9px; margin: 0 0 18px; padding: 11px 12px; border: 1px solid var(--border); border-radius: 11px; background: var(--bg-soft); color: var(--text-soft); font-size: 12px; line-height: 1.55; }
.login-alert { border-color: color-mix(in srgb, var(--err) 28%, var(--border)); background: var(--err-soft); color: var(--err); }
.login-alert > svg { margin-top: 1px; flex: 0 0 auto; }
.login-alert--form { margin: 2px 0 0; }
.status-spinner,
.button-spinner { width: 16px; height: 16px; flex: 0 0 auto; border: 2px solid color-mix(in srgb, var(--accent) 25%, transparent); border-top-color: var(--accent); border-radius: 50%; animation: spin .75s linear infinite; }

.tenant-form { display: flex; flex-direction: column; gap: 10px; }
.tenant-form > label { color: var(--text); font-size: 12px; font-weight: 600; }
.tenant-input-wrap { position: relative; display: flex; align-items: center; }
.tenant-input-icon { position: absolute; left: 14px; z-index: 1; color: var(--text-faint); pointer-events: none; }
.tenant-input-wrap input { width: 100%; min-height: 48px; padding: 0 14px 0 43px; border: 1px solid var(--border-strong); border-radius: 11px; background: color-mix(in srgb, var(--bg-elev) 90%, transparent); color: var(--text); font: inherit; font-size: 14px; transition: border-color .18s ease, box-shadow .18s ease, background .18s ease; }
.tenant-input-wrap input::placeholder { color: var(--text-faint); }
.tenant-input-wrap input:hover:not(:disabled) { border-color: color-mix(in srgb, var(--accent) 52%, var(--border)); }
.tenant-input-wrap input:focus { outline: none; border-color: var(--accent); background: var(--bg-elev); box-shadow: var(--focus-ring); }
.tenant-input-wrap input[aria-invalid="true"] { border-color: var(--err); }
.tenant-input-wrap input:disabled { cursor: not-allowed; opacity: .58; }

.tenant-options { display: grid; gap: 8px; margin: 1px 0 4px; color: var(--text-faint); font-size: 11px; }
.tenant-chips { display: flex; flex-wrap: wrap; gap: 7px; }
.tenant-chip { min-height: 28px; padding: 4px 10px; border: 1px solid var(--accent-line); border-radius: 999px; background: var(--accent-soft); color: var(--accent); font: inherit; font-size: 11px; cursor: pointer; transition: transform .15s ease, border-color .15s ease, background .15s ease; }
.tenant-chip:hover:not(:disabled) { border-color: var(--accent); transform: translateY(-1px); }
.tenant-chip--active { border-color: var(--accent); background: var(--accent); color: var(--text-invert); }
.tenant-chip:disabled { cursor: not-allowed; opacity: .55; }

.login-primary { position: relative; display: inline-flex; width: 100%; min-height: 48px; margin-top: 8px; padding: 0 17px; align-items: center; justify-content: center; gap: 9px; overflow: hidden; border: 0; border-radius: 11px; background: linear-gradient(120deg, var(--accent) 0%, var(--accent-hover) 52%, var(--accent-2) 100%); background-size: 170% 170%; color: var(--text-invert); box-shadow: 0 11px 24px color-mix(in srgb, var(--accent) 30%, transparent); font: inherit; font-size: 14px; font-weight: 600; cursor: pointer; transition: transform .16s ease, box-shadow .2s ease, background-position .45s ease; }
.login-primary:hover:not(:disabled) { background-position: 100% 50%; box-shadow: 0 15px 30px rgba(79,70,229,.35); transform: translateY(-1px); }
.login-primary:active:not(:disabled) { transform: translateY(0); }
.login-primary:disabled { cursor: not-allowed; opacity: .58; box-shadow: none; }
.login-primary .button-spinner { border-color: rgba(255,255,255,.38); border-top-color: var(--text-invert); }
.button-arrow { position: absolute; right: 16px; opacity: .75; }

.security-note { display: flex; margin-top: 22px; align-items: flex-start; justify-content: center; gap: 7px; color: var(--text-faint); font-size: 10px; line-height: 1.55; text-align: center; }
.security-note > svg { flex: 0 0 auto; color: var(--ok); }
.login-footer { margin: 17px 0 0; color: var(--text-faint); font-size: 10px; letter-spacing: .05em; }

@keyframes aurora-float { to { transform: translate3d(24px, 18px, 0) scale(1.08); } }

@media (max-width: 760px) {
  .login-stage { padding: 24px 18px 18px; }
  .login-card { display: block; width: min(520px, 100%); min-height: auto; }
  .login-brand { display: none; }
  .login-form-panel { padding: 38px 38px 32px; }
  .login-compact-head { display: flex; margin-bottom: 28px; align-items: center; gap: 11px; }
  .compact-logo { width: 42px; height: 42px; border-color: var(--accent-line); background: var(--accent-soft); color: var(--accent); }
  .login-compact-head > span:last-child { display: grid; gap: 1px; }
  .login-compact-head strong { color: var(--text); font-size: 15px; }
  .login-compact-head small { color: var(--text-faint); font-size: 10px; }
}

@media (max-width: 460px) {
  .login-stage { justify-content: flex-start; padding: 14px; }
  .login-card { border-radius: 20px; }
  .login-form-panel { padding: 28px 22px 24px; }
  .login-compact-head { margin-bottom: 23px; }
  .form-heading { margin-bottom: 22px; }
  .form-heading h1 { font-size: 22px; }
  .login-footer { margin-top: 12px; }
}
</style>
