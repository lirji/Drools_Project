import { createApp } from 'vue'
import { createPinia } from 'pinia'
import router from './router'
import App from './App.vue'
import { applyDensity, readDensity } from './shared/useDensity'
import './shared/styles/tokens.css'

// 主题：沿用旧前端 localStorage key `drools-theme` + data-theme（平滑接续用户偏好）
const THEME_KEY = 'drools-theme'
try {
  const saved = localStorage.getItem(THEME_KEY)
  if (saved) document.documentElement.setAttribute('data-theme', saved)
} catch {
  /* ignore */
}

// 表格密度（PR-5）：与主题同一套写法——必须在 mount 之前落到 <html> 上，
// 否则首屏会先按舒适档排一遍版再跳成紧凑档（可见的行高抖动）。
applyDensity(readDensity())

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
