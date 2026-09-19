import { createApp } from 'vue'
import { createPinia } from 'pinia'
import router from './router'
import App from './App.vue'
import { vHaptic } from './directives/haptic'
import './composables/theme' // 模块级 useColorMode：任何入口页都要即时应用主题，不等懒加载路由
import './styles/tokens.css'
import './styles/base.css'

const app = createApp(App)
app.directive('haptic', vHaptic)
app.use(createPinia()).use(router).mount('#app')

// PWA：生产环境注册 SW（仅缓存静态资源，不碰 /api）
if (import.meta.env.PROD && 'serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(() => { /* 无 SW 环境降级 */ })
  })
}
