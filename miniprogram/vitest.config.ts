import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'

/** B-20：小程序单测只覆盖逻辑层（api/http、stores、utils），不装配 uni 插件、不做 E2E */
export default defineConfig({
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.spec.ts'],
  },
})
