import { createServer } from 'vite'
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'
// Put away screenshots, EN + AR: the waiting list (desktop), the scan loop (tablet, after a few scans), Lookup with a
// place (desktop).   node dev/putaway-shots/shoot.mjs <outDir>   (SHOTS_PORT, default 5290)
const out = process.argv[2] ?? 'putaway-shots'
const port = Number(process.env.SHOTS_PORT ?? 5290)
const server = await createServer({ root: process.cwd(), server: { port, strictPort: true }, logLevel: 'error' })
mkdirSync(out, { recursive: true })
await server.listen()
const browser = await chromium.launch()
const base = (lang, view) => `http://localhost:${port}/dev/putaway-shots/harness.html?lang=${lang}&view=${view}`
for (const lang of ['en', 'ar']) {
  {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 })
    const p = await ctx.newPage()
    await p.goto(base(lang, 'list'))
    await p.waitForSelector('[data-testid="put-away-row-P104233"]')
    await p.waitForSelector('[data-testid="nav-badge-put-away"]')
    await p.waitForTimeout(400)
    await p.screenshot({ path: `${out}/put-away-list-${lang}.png`, fullPage: true })
    await ctx.close()
  }
  {
    const ctx = await browser.newContext({ viewport: { width: 1180, height: 820 }, deviceScaleFactor: 2 })
    const p = await ctx.newPage()
    await p.goto(base(lang, 'scan'))
    const input = await p.waitForSelector('input.input-scan')
    for (const code of ['P104240', 'Q999999', 'Q000107', 'P103918', 'P104233']) {
      await input.type(code)
      await input.press('Enter')
      await p.waitForTimeout(250)
    }
    await p.waitForTimeout(900)     // flash overlay gone
    await p.screenshot({ path: `${out}/put-away-scan-${lang}.png` })
    await ctx.close()
  }
  {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 }, deviceScaleFactor: 2 })
    const p = await ctx.newPage()
    await p.goto(base(lang, 'lookup'))
    await p.waitForSelector('[data-testid="piece-place"]')
    await p.waitForTimeout(400)
    await p.screenshot({ path: `${out}/lookup-place-${lang}.png`, fullPage: true })
    await p.click('[data-testid="move-place-btn"]')
    await p.waitForSelector('[data-testid="move-place-option-Z1-R01-S2"]')
    await p.waitForTimeout(300)
    await p.screenshot({ path: `${out}/lookup-move-${lang}.png` })
    await ctx.close()
  }
}
await browser.close()
await server.close()
