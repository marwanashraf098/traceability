import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import { playwright } from '@vitest/browser-playwright'
import type { BrowserCommand } from 'vitest/node'

// Real-browser tests (Vitest browser mode, Playwright provider) — Chromium AND WebKit.
// Separate from the jsdom suite (vite.config.ts → `npm test`); run with `npm run test:browser`.
// jsdom can't show what these check: a browser moves focus off an input that becomes
// disabled, and a hardware scanner types into whatever has focus with no click first.

/**
 * Types each code like a USB/HID barcode scanner: one key every `keyDelayMs`, then Enter,
 * scans back to back — real keyboard events from Playwright, never a click.
 */
const scannerBurst: BrowserCommand<[codes: string[], keyDelayMs: number]> = async (ctx, codes, keyDelayMs) => {
  for (const code of codes) {
    await ctx.page.keyboard.type(code, { delay: keyDelayMs })
    await ctx.page.keyboard.press('Enter')
  }
}

export default defineConfig({
  plugins: [react()],
  test: {
    include: ['src/test-browser/**/*.browser.test.tsx'],
    testTimeout: 60_000,
    browser: {
      enabled: true,
      headless: true,
      provider: playwright(),
      instances: [{ browser: 'chromium' }, { browser: 'webkit' }],
      commands: { scannerBurst },
    },
  },
})
