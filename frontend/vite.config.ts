import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

// 25 Step 演示台端点（含进阶能力）是根路径散点（/hello、/discount/… 等），只能用前缀正则清单让 dev proxy 反代到后端。
// 与活动面 /activity-marketing、/actuator 一并转发；改后端端口用 VITE_PROXY_TARGET（dev 档 8081 / auth 档 8099）。
// 此清单必须与 examples.js 的 demo path 前缀同步——F2 迁 catalog 时加一条 lint 校验（计划 A2）。
const API_PREFIXES = [
  'hello', 'discount', 'cart', 'risk', 'pipeline', 'decision', 'stateless',
  'fraud', 'backward', 'hot', 'scanner', 'loyalty', 'tms', 'guard', 'metrics',
  'dmn', 'campaign', 'activity-marketing', 'actuator',
  // Step 19–24 进阶能力（根路径散点，同样要进前缀清单，否则 dev 会把它们当 SPA 路由）
  'quantifier', 'dispatch', 'traits', 'fireuntilhalt', 'template', 'pmml',
]

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const target = env.VITE_PROXY_TARGET || 'http://localhost:8081'
  const proxyEntry = { target, changeOrigin: true }
  const proxy: Record<string, typeof proxyEntry | (typeof proxyEntry & { rewrite: (p: string) => string })> = {}
  for (const p of API_PREFIXES) proxy['^/' + p + '(/|$)'] = proxyEntry

  // 决策平面（只读服务，另一个进程）。**必须是独立条目，不能并进 API_PREFIXES**：
  //   · 上面那条 'decision' 前缀是 Step 7 教学端点 `POST /decision/calculate`，指向 console；
  //     两者路径前缀相同而后端不同，混在一起会让 /decision/v1/* 被静默转给 console 拿 404。
  //   · 浏览器侧统一走网关前缀 `/api/decision/*`（生产由 nginx rewrite），dev 这里做同样的 rewrite，
  //     于是前端代码在两种形态下一字不差。
  //   · 不加这条的失败形态极具迷惑性：dev server 会把 /api/decision/* 当成 SPA 路由返回 index.html，
  //     于是 `ok:true` 但 `json=null`，页面报「决策响应为空」而不是 404 —— 看起来像后端 bug。
  proxy['^/api/decision(/|$)'] = {
    target: env.VITE_DECISION_TARGET || 'http://localhost:8082',
    changeOrigin: true,
    rewrite: (path: string) => path.replace(/^\/api\/decision/, '/decision/v1'),
  }

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
