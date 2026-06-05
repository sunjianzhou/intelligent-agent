import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 3000,
    host: '0.0.0.0',
    proxy: {
      '/api': {
        target: process.env.VITE_BACKEND_URL || 'http://localhost:8080',
        changeOrigin: true
      },
      '/ws': {
        // WS target 从 BACKEND_URL 派生，避免两个环境变量不同步
        target: (process.env.VITE_WS_URL
          || (process.env.VITE_BACKEND_URL || 'http://localhost:8080').replace(/^http/, 'ws')),
        ws: true
      }
    }
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    rollupOptions: {
      output: {
        manualChunks: {
          'vendor-hljs':   ['highlight.js/lib/core'],
          'vendor-marked': ['marked'],
          'vendor-purify': ['dompurify'],
        }
      }
    }
  }
})