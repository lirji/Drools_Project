<script setup lang="ts">
/**
 * 全局身份/上下文条（重设计）：把 ConsoleShell 的 auth-bar / tenant-bar / actor-bar 逐字迁到顶部工具条右侧，
 * 移出内容流。testid、v-if authEnabled 逻辑、三个 store 接口全部零改。
 * - C2：auth 档 actor 文本必须留在带 data-testid="auth-bar" 的元素内部（e2e-oidc 断言 auth-bar innerText 含 act-alice）。
 * - 响应式：≥561 内联可见（守 tablet-smoke 768 的 tenant-bar 可见）；≤560 收进 popover。
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/auth/useAuthStore'
import { useTenantStore } from '@/stores/useTenantStore'
import { useActorStore } from '@/stores/useActorStore'
import Icon from '@/shared/ui/Icon.vue'

const auth = useAuthStore()
const tenantStore = useTenantStore()
const actorStore = useActorStore()
const router = useRouter()

const quickTenants = ['acme', 'beta', '__dev__']
const open = ref(false) // ≤560 popover

function doLogout(): void {
  auth.logout()
  router.push({ name: 'login' })
}
</script>

<template>
  <div class="identity">
    <button class="pop-toggle" aria-label="上下文" :aria-expanded="open" @click="open = !open">
      <span>上下文</span><Icon name="chevron-down" :size="15" />
    </button>
    <div class="bars" :class="{ open }">
      <!-- auth 档：租户由 token aud 定，只读 -->
      <div v-if="auth.authEnabled" class="bar" data-testid="auth-bar">
        <span class="lbl">租户</span>
        <span class="chip chip-active" data-testid="auth-tenant">{{ auth.tenant || '-' }}</span>
        <span class="who">操作者 {{ auth.actor || '-' }}</span>
        <button class="chip chip-btn" data-testid="logout" @click="doLogout">
          <Icon name="log-out" :size="14" /><span>登出</span>
        </button>
      </div>

      <!-- dev/header 档：可切租户 + 操作者 -->
      <template v-else>
        <div class="bar" data-testid="tenant-bar">
          <span class="lbl">租户</span>
          <input
            class="in"
            data-testid="tenant-input"
            :value="tenantStore.tenant"
            @change="tenantStore.setTenant(($event.target as HTMLInputElement).value.trim())"
          />
          <button
            v-for="t in quickTenants"
            :key="t"
            class="chip"
            :class="{ 'chip-active': t === tenantStore.tenant }"
            :data-testid="'tenant-chip-' + t"
            @click="tenantStore.setTenant(t)"
          >{{ t }}</button>
        </div>
        <div class="bar" data-testid="actor-bar">
          <span class="lbl">操作者</span>
          <input
            class="in"
            data-testid="actor-input"
            :value="actorStore.actor"
            placeholder="如 alice（四眼上线需≠提交人）"
            @change="actorStore.setActor(($event.target as HTMLInputElement).value.trim())"
          />
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.identity { position: relative; display: flex; align-items: center; }
.pop-toggle { display: none; }
.bars { display: flex; align-items: center; gap: var(--sp-3); flex-wrap: wrap; }
.bar { display: flex; align-items: center; gap: var(--sp-2); }
.lbl { font-size: var(--fs-xs); color: var(--text-soft); font-weight: var(--fw-semibold); }
.who { font-size: var(--fs-xs); color: var(--text-faint); }
.in {
  padding: var(--sp-1) var(--sp-2); border: 1px solid var(--border);
  border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--text);
  font-size: var(--fs-sm); width: 130px;
}
.chip {
  min-height: 28px; padding: var(--sp-1) var(--sp-3);
  border: 1px solid var(--border); border-radius: var(--radius-pill);
  background: var(--bg-elev); color: var(--text); font-size: var(--fs-xs); cursor: pointer;
}
.chip-active { background: var(--accent); color: var(--text-invert); border-color: var(--accent); }
.chip { transition: background .12s ease, border-color .12s ease; }
.chip:hover { background: var(--bg-hover); }
.chip-active:hover { background: var(--accent-hover); }
.chip-btn { display: inline-flex; align-items: center; gap: 4px; }
@media (pointer: coarse) { .chip { min-height: var(--touch-min); } }

/* ≤560：收进 popover（该档无自动化 e2e） */
@media (max-width: 560px) {
  .pop-toggle {
    display: inline-flex; align-items: center; gap: 4px; min-height: var(--touch-min);
    padding: var(--sp-1) var(--sp-3); border: 1px solid var(--border);
    border-radius: var(--radius-sm); background: var(--bg-soft); color: var(--text);
    font-size: var(--fs-sm); cursor: pointer;
  }
  .bars {
    display: none; position: absolute; top: calc(100% + 6px); right: 0;
    flex-direction: column; align-items: flex-start; gap: var(--sp-3);
    background: var(--bg-elev); border: 1px solid var(--border); border-radius: var(--radius);
    box-shadow: var(--shadow-lg); padding: var(--sp-4); min-width: 260px; z-index: var(--z-dropdown);
  }
  .bars.open { display: flex; }
}
</style>
