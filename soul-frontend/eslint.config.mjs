import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import { defineConfigWithVueTs, vueTsConfigs } from '@vue/eslint-config-typescript'
import skipFormatting from 'eslint-config-prettier/flat'

export default defineConfigWithVueTs(
  { name: 'app/ignores', ignores: ['dist/**', 'node_modules/**', 'src/api/schema.d.ts'] },
  {
    // 离线服务 Worker：跑在 SW 全局作用域，不是浏览器 window
    name: 'app/service-worker',
    files: ['public/**/*.js'],
    languageOptions: {
      globals: {
        self: 'readonly', caches: 'readonly', clients: 'readonly',
        fetch: 'readonly', URL: 'readonly', Response: 'readonly', Request: 'readonly',
      },
    },
  },
  js.configs.recommended,
  pluginVue.configs['flat/recommended'],
  vueTsConfigs.recommended,
  skipFormatting,
  {
    name: 'app/rules',
    rules: {
      'vue/multi-word-component-names': 'off',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
      '@typescript-eslint/no-explicit-any': 'warn',
    },
  },
)
