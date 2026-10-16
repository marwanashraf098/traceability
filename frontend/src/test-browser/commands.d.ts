// Custom browser command registered in vitest.browser.config.ts.
declare module 'vitest/browser' {
  interface BrowserCommands {
    scannerBurst: (codes: string[], keyDelayMs: number) => Promise<void>
    layoutBurst: (scans: { code: string; key: string; shift: boolean; vk: number }[][], keyDelayMs: number) => Promise<void>
  }
}
export {}
