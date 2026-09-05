import js from '@eslint/js'
import jsxA11y from 'eslint-plugin-jsx-a11y'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import typescriptEslint from 'typescript-eslint'

const typescriptFiles = [
    '**/*.{ts,tsx}',
]

export default [
    {
        ignores: [
            'dist/**',
            'coverage/**',
            'node_modules/**',
        ],
    },

    js.configs.recommended,

    ...typescriptEslint.configs.recommended,

    {
        files: typescriptFiles,

        languageOptions: {
            parserOptions: {
                projectService: true,
                tsconfigRootDir: import.meta.dirname,
            },
        },

        plugins: {
            'jsx-a11y': jsxA11y,
            'react-hooks': reactHooks,
            'react-refresh': reactRefresh,
        },

        rules: {
            'no-console': [
                'error',
                {
                    allow: [
                        'warn',
                        'error',
                    ],
                },
            ],

            'no-debugger': 'error',

            /*
             * Стандартные правила ESLint не учитывают
             * все особенности синтаксиса TypeScript.
             */
            'no-undef': 'off',
            'no-unused-vars': 'off',

            '@typescript-eslint/no-explicit-any': 'error',

            '@typescript-eslint/no-floating-promises': 'error',

            '@typescript-eslint/no-misused-promises': [
                'error',
                {
                    checksVoidReturn: {
                        attributes: false,
                    },
                },
            ],

            '@typescript-eslint/no-unused-vars': [
                'error',
                {
                    argsIgnorePattern: '^_',
                    caughtErrorsIgnorePattern: '^_',
                    varsIgnorePattern: '^_',
                },
            ],

            ...reactHooks.configs.flat.recommended.rules,

            /*
             * Приложение не использует React Compiler. Эти два правила из
             * compiler-oriented preset дают ложные срабатывания на штатные
             * эффекты загрузки данных и измерения DOM; остальные Hooks rules
             * (включая dependencies и rules-of-hooks) остаются обязательными.
             */
            'react-hooks/set-state-in-effect': 'off',
            'react-hooks/purity': 'off',

            'react-refresh/only-export-components': [
                'error',
                {
                    allowConstantExport: true,
                },
            ],

            'jsx-a11y/alt-text': 'error',
            'jsx-a11y/anchor-is-valid': 'error',
            'jsx-a11y/aria-props': 'error',
            'jsx-a11y/aria-role': 'error',
            'jsx-a11y/aria-unsupported-elements': 'error',
            'jsx-a11y/heading-has-content': 'error',
            'jsx-a11y/html-has-lang': 'error',

            'jsx-a11y/label-has-associated-control': [
                'error',
                {
                    assert: 'either',
                    depth: 3,
                },
            ],

            'jsx-a11y/no-access-key': 'error',
            'jsx-a11y/tabindex-no-positive': 'error',
        },
    },
]
