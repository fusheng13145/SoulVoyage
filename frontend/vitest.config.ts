import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

/**
 * 前端测试与覆盖率配置。
 * 与 vite.config.ts 分离：测试字段不进生产构建配置，避免构建产物被测试关注点污染。
 * - environment 选 happy-dom：stores/ui 依赖 localStorage 与 window.setTimeout。
 * - coverage 用 v8：text 供人读、html 供排查、json-summary 供 CI 读取阈值。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  test: {
    environment: 'happy-dom',
    include: ['src/**/*.spec.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'json-summary'],
      /*
       * 口径：单元覆盖率只统计可断言的逻辑层（utils/stores/composables/api）。
       * views 与 components 属展示层，其验证手段是无头浏览器走查，不靠单元测试——
       * 把展示层计入分母只会用大量结构性 DOM 稀释指标，让数字失去指导意义。
       */
      include: ['src/utils/**/*.ts', 'src/stores/**/*.ts', 'src/composables/**/*.ts', 'src/api/**/*.ts'],
      // schema.d.ts 是 openapi-typescript 生成的纯类型，无可执行语句
      exclude: ['src/**/*.spec.ts', 'src/api/schema.d.ts'],
      /*
       * 阈值一次设足到目标线、不做逐级抬升。当前实测（Stmts 13.35%）远未达标，
       * 覆盖缺口要靠补测用例清偿，而不是靠下调阈值抹平——阈值的职责是拦住未达标状态。
       */
      thresholds: {
        statements: 80,
        lines: 80,
        functions: 80,
        branches: 70,
      },
    },
  },
})
