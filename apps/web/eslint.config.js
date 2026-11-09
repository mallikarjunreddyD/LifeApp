import parser from '@typescript-eslint/parser';

export default [
  { ignores: ['dist/**', 'playwright-report/**', 'test-results/**'] },
  {
    files: ['**/*.ts', '**/*.tsx', '**/*.js', '**/*.mjs'],
    languageOptions: { parser, parserOptions: { ecmaFeatures: { jsx: true } } },
    rules: {
      'eqeqeq': 'error',
      'no-debugger': 'error',
      'no-duplicate-imports': ['error', { allowSeparateTypeImports: true }],
      'no-fallthrough': 'error',
      'no-unreachable': 'error',
      'no-var': 'error',
      'prefer-const': 'error',
    },
  },
];
