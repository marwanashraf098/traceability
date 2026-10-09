import { createServer } from 'vite'
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'
// Shell menu screenshots (phone build): phone + tablet (menu closed and open) and desktop, EN + AR.
//   node dev/analytics-shots/shoot-menu.mjs <outDir>   (SHOTS_PORT, default 5288)
const out = process.argv[2] ?? 'menu-shots'
const port = Number(process.env.SHOTS_PORT ?? 5288)
process.env.VITE_ANALYTICS_ENABLED = 'true'
const server = await createServer({ root: process.cwd(), server: { port, strictPort: true }, logLevel: 'error' })
mkdirSync(out, { recursive: true })
await server.listen()
const browser = await chromium.launch()
for (const lang of ['en', 'ar']) {
  for (const [w, label] of [[390, 'phone'], [880, 'tablet'], [1100, 'desktop']]) {
    const ctx = await browser.newContext({ viewport: { width: w, height: 844 }, deviceScaleFactor: 2 })
    const p = await ctx.newPage()
    await p.goto(`http://localhost:${port}/dev/analytics-shots/harness.html?fixture=broek&lang=${lang}&path=/analytics/summary`)
    await p.waitForSelector('[data-testid="pipeline"]')
    await p.waitForTimeout(500)
    await p.screenshot({ path: `${out}/menu-${label}-${lang}-closed.png` })
    if (w < 900) {
      await p.click('[data-testid="nav-menu-button"]')
      await p.waitForTimeout(350)
      await p.screenshot({ path: `${out}/menu-${label}-${lang}-open.png` })
    }
    await ctx.close()
  }
}
await browser.close(); await server.close()
console.log('done')
