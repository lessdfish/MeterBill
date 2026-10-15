import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  publicDir: '../docs/prototypes/meterbill-console/public',
  server: {
    proxy: {
      '/api': 'http://127.0.0.1:8086',
      '/actuator': 'http://127.0.0.1:8086',
    },
  },
})
