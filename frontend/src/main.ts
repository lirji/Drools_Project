import { createApp } from 'vue'
import { createPinia } from 'pinia'
import router from './router'
import App from './App.vue'
import './shared/styles/tokens.css'

// 主题：沿用旧前端 localStorage key `drools-theme` + data-theme（平滑接续用户偏好）
const THEME_KEY = 'drools-theme'
try {
  const saved = localStorage.getItem(THEME_KEY)
  if (saved) document.documentElement.setAttribute('data-theme', saved)
} catch {
  /* ignore */
}

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
