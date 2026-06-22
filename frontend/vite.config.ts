import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  build: {
    // Maven frontend-maven-plugin runs `npm run build` from the frontend/
    // directory; output lands directly in Spring's static resource path.
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    // Explicit vitest imports in each test file — no globals:true needed.
    include: ['src/test/**/*.test.{ts,tsx}'],
  },
})
