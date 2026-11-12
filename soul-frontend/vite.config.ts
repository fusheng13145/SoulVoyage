import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  build: {
    /*
     * ECharts 与 zrender 全工程仅 InsightsView 使用，该路由已是动态 import。
     * 单独成块后：非洞察页路由不再解析到图表库，且图表库版本稳定，可独立长期缓存，
     * 业务代码改动不会使其缓存失效。
     * chunkSizeWarningLimit 提到 600：按需引入后 echarts 块本身约 550 kB，属成熟第三方库
     * 的固有体积，非业务代码膨胀；业务侧其余块均低于 150 kB。
     */
    chunkSizeWarningLimit: 600,
    rollupOptions: {
      output: {
        manualChunks(id: string) {
          if (/[\\/]node_modules[\\/](echarts|zrender)[\\/]/.test(id)) return 'echarts'
          return undefined
        },
      },
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
})
