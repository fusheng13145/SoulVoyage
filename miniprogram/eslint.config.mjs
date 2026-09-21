import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import { defineConfigWithVueTs, vueTsConfigs } from '@vue/eslint-config-typescript'
import skipFormatting from 'eslint-config-prettier/flat'

/** 与 Web 端同一套规则（少了 service-worker 一节：小程序没有 SW）；uni/wx 是端侧全局，不参与未定义检查 */
export default defineConfigWithVueTs(
  { name: 'app/ignores', ignores: ['dist/**', 'unpackage/**', 'node_modules/**', 'src/api/schema.d.ts'] },
  js.configs.recommended,
  pluginVue.configs['flat/recommended'],
  vueTsConfigs.recommended,
  skipFormatting,
  {
    name: 'app/rules',
    languageOptions: {
      globals: { uni: 'readonly', wx: 'readonly', plus: 'readonly' },
    },
    rules: {
      'vue/multi-word-component-names': 'off',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
      '@typescript-eslint/no-explicit-any': 'warn',
    },
  },
)
