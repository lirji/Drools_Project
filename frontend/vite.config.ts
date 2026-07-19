import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

// 18 Step 演示台端点是根路径散点（/hello、/discount/… 等），只能用前缀正则清单让 dev proxy 反代到后端。
// 与活动面 /activity-marketing、/actuator 一并转发；改后端端口用 VITE_PROXY_TARGET（dev 档 8081 / auth 档 8099）。
// 此清单必须与 examples.js 的 demo path 前缀同步——F2 迁 catalog 时加一条 lint 校验（计划 A2）。
const API_PREFIXES = [
  'hello', 'discount', 'cart', 'risk', 'pipeline', 'decision', 'stateless',
  'fraud', 'backward', 'hot', 'scanner', 'loyalty', 'tms', 'guard', 'metrics',
  'dmn', 'campaign', 'activity-marketing', 'actuator',
]

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const target = env.VITE_PROXY_TARGET || 'http://localhost:8081'
  const proxyEntry = { target, changeOrigin: true }
  const proxy: Record<string, typeof proxyEntry> = {}
  for (const p of API_PREFIXES) proxy['^/' + p + '(/|$)'] = proxyEntry

  return {
    // 与旧 index.html 同源并存：SPA 挂 /ui/，避免占用根路径欢迎页（决策 D3）
    base: '/ui/',
    plugins: [vue()],
    resolve: {
      alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
    },
    server: { port: 5173, proxy },
    build: {
      // 产物由 Maven frontend 插件拷进 target/classes/static/ui/（计划 A2）
      outDir: 'dist',
      emptyOutDir: true,
    },
    test: {
      environment: 'jsdom',
      globals: true,
    },
  }
})
