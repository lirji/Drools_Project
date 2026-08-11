/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export default component
}

interface ImportMetaEnv {
  readonly VITE_PROXY_TARGET?: string
  readonly VITE_API_BASE?: string
  /** 决策平面的 base 覆盖。默认与 VITE_API_BASE 同源，仅在 decision 与 console 不同域时才需要单独设。 */
  readonly VITE_DECISION_BASE?: string
  /** vite dev 下决策服务的地址（默认 http://localhost:8082）。 */
  readonly VITE_DECISION_TARGET?: string
}
interface ImportMeta {
  readonly env: ImportMetaEnv
}
