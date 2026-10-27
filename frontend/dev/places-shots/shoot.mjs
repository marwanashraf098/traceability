import { createServer } from 'vite'
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'
// Settings › Places screenshots, EN + AR, desktop + phone, a shelf selected.
//   node dev/places-shots/shoot.mjs <outDir>   (SHOTS_PORT, default 5289)
const out = process.argv[2] ?? 'places-shots'
const port = Number(process.env.SHOTS_PORT ?? 5289)
const server = await createServer({ root: process.cwd(), server: { port, strictPort: true }, logLevel: 'error' })
mkdirSync(out, { recursive: true })
await server.listen()
const browser = await chromium.launch()
for (const lang of ['en', 'ar']) {
  for (const [w, label] of [[1440, 'desktop'], [390, 'phone']]) {
    const ctx = await browser.newContext({ viewport: { width: w, height: 1000 }, deviceScaleFactor: 2 })
    const p = await ctx.newPage()
    await p.goto(`http://localhost:${port}/dev/places-shots/harness.html?lang=${lang}`)
    await p.waitForSelector('[data-testid="places-zone-layout"]')
    await p.click('[data-testid="places-shelf-Z1-R01-S2"]', { position: { x: 12, y: 10 } })
    await p.waitForSelector('[data-testid="places-contents"]')
    await p.waitForTimeout(400)
    await p.screenshot({ path: `${out}/places-${label}-${lang}.png`, fullPage: true })
    await ctx.close()
  }
}
await browser.close()
await server.close()
