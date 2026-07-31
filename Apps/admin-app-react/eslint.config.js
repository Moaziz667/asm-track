import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  // `public/keycloakify-dev-resources` is vendored by keycloakify, not ours to lint or fix.
  globalIgnores(['dist', 'public/keycloakify-dev-resources']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      globals: globals.browser,
    },
    rules: {
      // The codebase already writes `_color`, `_copy`, `_pod` for "required by the signature,
      // deliberately unused". The rule just never knew about the convention, so it reported
      // them — which teaches people to ignore the rule rather than to name things clearly.
      '@typescript-eslint/no-unused-vars': ['error', {
        argsIgnorePattern: '^_',
        varsIgnorePattern: '^_',
        caughtErrorsIgnorePattern: '^_',
        destructuredArrayIgnorePattern: '^_',
      }],

      // ── Known debt, deliberately warnings ────────────────────────────────
      // Everything below is a real finding, not a false positive. They are warnings rather
      // than errors because fixing them changes runtime behaviour — an effect that calls
      // setState is usually synchronising state onto a prop, and rewriting it alters what
      // renders and when. With the test suite as thin as it is, a batch rewrite would trade
      // a lint number for a regression nobody notices.
      //
      // CI caps the warning count (see .gitlab-ci.yml), so this debt can shrink but not grow.
      // Fix them where you are already working, one at a time, with the page in front of you.
      'react-hooks/set-state-in-effect': 'warn',
      'react-hooks/static-components': 'warn',
      'react-hooks/preserve-manual-memoization': 'warn',
      'react-hooks/refs': 'warn',
      'react-hooks/purity': 'warn',
      'react-hooks/immutability': 'warn',
      // Fast-refresh only: a file exporting both a component and a constant reloads the whole
      // module instead of hot-swapping. A developer-experience cost, never a production one.
      'react-refresh/only-export-components': 'warn',
      // Each `any` needs the shape it stands for to be worked out. Worth doing, not worth
      // blocking a delivery pipeline on.
      '@typescript-eslint/no-explicit-any': 'warn',
    },
  },
])
