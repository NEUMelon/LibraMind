import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// 开发时代理到后端，保持同源：Session Cookie 与 CSRF 不需要跨域配置。
const backend = process.env.LIBRAMIND_BACKEND ?? 'http://127.0.0.1:8090'

export default defineConfig({
  plugins: [vue()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': { target: backend, changeOrigin: false },
      '/assets': { target: backend, changeOrigin: false },
      '/openapi.json': { target: backend, changeOrigin: false },
    },
  },
  preview: {
    host: '127.0.0.1',
    port: 4173,
    proxy: {
      '/api': { target: backend, changeOrigin: false },
      '/assets': { target: backend, changeOrigin: false },
      '/openapi.json': { target: backend, changeOrigin: false },
    },
  },
  // 后端封面使用 /assets，前端构建资源使用独立路径，避免预览和同源部署时被代理到后端。
  build: { assetsDir: 'web-assets', chunkSizeWarningLimit: 900 },
})
