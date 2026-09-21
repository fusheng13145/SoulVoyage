import { createSSRApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'

// uni-app 的入口由框架调用：导出 createApp，返回 { app } 即可（小程序侧没有 mount）
export function createApp() {
  const app = createSSRApp(App)
  app.use(createPinia())
  return { app }
}
