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

/** One physical key a HID scanner presses: the US position, Shift, and the character the OS
 *  layout turns it into ('' = the layout types nothing for it). */
type LayoutKey = { code: string; key: string; shift: boolean; vk: number }

/**
 * S2 — types each scan through Chromium's DevTools input pipeline (CDP Input.dispatchKeyEvent),
 * so `code` and `key` can differ the way they do on an Arabic layout and the browser's own
 * default (insert `text` unless the keydown was prevented) is the one under test.
 * Chromium only: WebKit has no CDP.
 */
const layoutBurst: BrowserCommand<[scans: LayoutKey[][], keyDelayMs: number]> = async (ctx, scans, keyDelayMs) => {
  const cdp = await ctx.page.context().newCDPSession(ctx.page)
  const send = (type: 'keyDown' | 'keyUp', k: LayoutKey) => cdp.send('Input.dispatchKeyEvent', {
    type, code: k.code, key: k.key === '' ? 'Unidentified' : k.key,
    ...(type === 'keyDown' && k.key !== '' ? { text: k.key, unmodifiedText: k.key } : {}),
    windowsVirtualKeyCode: k.vk, modifiers: k.shift ? 8 : 0,
  })
  for (const keys of scans) {
    for (const k of keys) {
      await send('keyDown', k)
      await send('keyUp', k)
      await new Promise(r => setTimeout(r, keyDelayMs))
    }
    await cdp.send('Input.dispatchKeyEvent', { type: 'keyDown', code: 'Enter', key: 'Enter', text: '\r', windowsVirtualKeyCode: 13 })
    await cdp.send('Input.dispatchKeyEvent', { type: 'keyUp', code: 'Enter', key: 'Enter', windowsVirtualKeyCode: 13 })
  }
  await cdp.detach()
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
      commands: { scannerBurst, layoutBurst },
    },
  },
})
