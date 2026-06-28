import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import { resolve } from 'path'

export default defineConfig({
  plugins: [react()],
  build: {
    // Maven frontend-maven-plugin runs `npm run build` from the frontend/
    // directory; output lands directly in Spring's static resource path.
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
    rollupOptions: {
      // Two separate entry points: standalone SPA (index.html) and the
      // embedded Shopify App Bridge shell (embedded.html).
      // App Bridge + Polaris are only in the embedded bundle — NOT in the
      // standalone bundle. Vite tree-shakes and code-splits automatically.
      input: {
        main:     resolve(__dirname, 'index.html'),
        embedded: resolve(__dirname, 'embedded.html'),
      },
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
    fs: {
      allow: ['..'],
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    // Explicit vitest imports in each test file — no globals:true needed.
    include: ['src/test/**/*.test.{ts,tsx}'],
  },
})
