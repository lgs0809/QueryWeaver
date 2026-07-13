import { globalIgnores } from 'eslint/config'
import pluginVue from 'eslint-plugin-vue'
import {
  defineConfigWithVueTs,
  vueTsConfigs,
} from '@vue/eslint-config-typescript'
import skipFormatting from '@vue/eslint-config-prettier/skip-formatting'

export default defineConfigWithVueTs(
  {
    name: 'queryweaver/files-to-lint',
    files: ['**/*.{js,mjs,cjs,ts,mts,cts,jsx,tsx,vue}'],
  },
  globalIgnores(['dist/**', 'node_modules/**']),
  pluginVue.configs['flat/essential'],
  vueTsConfigs.recommended,
  {
    rules: {
      'vue/block-lang': 'off',
      'vue/multi-word-component-names': 'off',
    },
  },
  skipFormatting,
)
