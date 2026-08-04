import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
    plugins: [
        react(),
    ],

    server: {
        host: '127.0.0.1',
        port: 5173,
        strictPort: true,

        proxy: {
            '/api': {
                target: 'http://127.0.0.1:8080',
                changeOrigin: true,
            },
        },
    },

    preview: {
        host: '127.0.0.1',
        port: 4173,
        strictPort: true,
    },

    build: {
        target: 'es2022',
        sourcemap: false,
        manifest: true,
        cssCodeSplit: true,
        reportCompressedSize: true,
        chunkSizeWarningLimit: 700,
    },

    test: {
        environment: 'jsdom',
        globals: true,
        setupFiles: ['./src/test/setup.ts'],
        restoreMocks: true,
        clearMocks: true,
        mockReset: true,

        coverage: {
            provider: 'v8',
            reporter: [
                'text',
                'json-summary',
                'html',
            ],
            reportsDirectory: './coverage',
            include: [
                'src/**/*.{ts,tsx}',
            ],
            exclude: [
                'src/**/*.d.ts',
                'src/main.tsx',
                'src/test/**',
                'src/**/*.test.{ts,tsx}',
                'src/**/*.spec.{ts,tsx}',
            ],
        },
    },
})
