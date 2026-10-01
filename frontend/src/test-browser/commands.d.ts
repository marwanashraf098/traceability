// Custom browser command registered in vitest.browser.config.ts.
declare module 'vitest/browser' {
  interface BrowserCommands {
    scannerBurst: (codes: string[], keyDelayMs: number) => Promise<void>
  }
}
export {}
