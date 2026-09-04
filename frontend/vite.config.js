import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

const proxyTarget = process.env.VITE_PROXY_TARGET || 'http://127.0.0.1:8088'
const apiProxy = {
  '/api': {
    target: proxyTarget,
    changeOrigin: true,
    secure: true
  }
}

export default defineConfig({
  root: fileURLToPath(new URL('.', import.meta.url)),
  plugins: [vue()],
  server: {
    proxy: apiProxy
  },
  preview: {
    proxy: apiProxy
  }
})
