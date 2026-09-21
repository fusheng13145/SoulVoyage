import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import uniPlugin from '@dcloudio/vite-plugin-uni'

/** 这个插件是带 __esModule 标记的 CJS：在 Node 的 ESM 视图里 default 拿到的是整个 exports 对象，
 *  真正的插件工厂还挂在它的 .default 上。少这一层解包就是 `uni is not a function`。 */
const uni = ((uniPlugin as unknown as { default?: typeof uniPlugin }).default ?? uniPlugin) as typeof uniPlugin

export default defineConfig({
  plugins: [uni()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    // 与 Web 端 5173 错开：本机验证小程序 H5 产物时两端可以同时跑着对照。
    // strictPort 是刻意的：静默换端口会让走查脚本对着错的实例看，不如启动就失败。
    port: Number(process.env.SV_H5_PORT || 5174),
    strictPort: true,
    proxy: {
      // SV_ORIGIN：后端不在 8080 时（本机 8080 常被别的项目占）指到实际端口，与冒烟脚本同一个变量名
      '/api': {
        target: process.env.SV_ORIGIN || 'http://localhost:8080',
        changeOrigin: true,
        // 只有本机 H5 验证需要：经代理的请求把 Origin 摘掉，后端看到的就是同源请求，
        // 不必为每个临时端口往 CORS 白名单里加一行。真机走 HTTPS 备案域名，不经过这里。
        configure: (proxy) => {
          proxy.on('proxyReq', (req) => req.removeHeader('origin'))
        },
      },
    },
  },
})
