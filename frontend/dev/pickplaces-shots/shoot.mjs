import { createServer } from 'vite'
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'
// Pick & Pack places screenshots, EN + AR: the gather list (screen + A6 print preview), the pack screen in queue
// mode and in waybill mode, waybill mode's printed-but-not-packed list.
//   node dev/pickplaces-shots/shoot.mjs <outDir>   (SHOTS_PORT, default 5291)
const out = process.argv[2] ?? 'pickplaces-shots'
const port = Number(process.env.SHOTS_PORT ?? 5291)
const server = await createServer({ root: process.cwd(), server: { port, strictPort: true }, logLevel: 'error' })
mkdirSync(out, { recursive: true })
await server.listen()
const browser = await chromium.launch()
const base = (lang, view) => `http://localhost:${port}/dev/pickplaces-shots/harness.html?lang=${lang}&view=${view}`
const START = { en: 'Start pack session', ar: 'ابدأ جلسة تغليف' }
for (const lang of ['en', 'ar']) {
  {
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, deviceScaleFactor: 2 })
    const p = await ctx.newPage()
    await p.goto(base(lang, 'gather'))
    await p.waitForSelector('[data-testid="gather-places-table"]')
    await p.waitForTimeout(400)
    await p.screenshot({ path: `${out}/gather-${lang}.png`, fullPage: true })
    await ctx.close()
  }
  {
    // A6 print preview: the page as the browser prints it (print media, A6 width ≈ 105 mm).
    const ctx = await browser.newContext({ viewport: { width: 397, height: 560 }, deviceScaleFactor: 3 })
    const p = await ctx.newPage()
    await p.goto(base(lang, 'gather'))
    await p.waitForSelector('[data-testid="gather-places-table"]')
    await p.emulateMedia({ media: 'print' })
    await p.waitForTimeout(400)
    await p.screenshot({ path: `${out}/gather-print-${lang}.png`, fullPage: true })
    await p.pdf({ path: `${out}/gather-print-${lang}.pdf`, format: 'A6', printBackground: true })
    await ctx.close()
  }
  {
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, deviceScaleFactor: 2 })
    const p = await ctx.newPage()
    await p.goto(base(lang, 'queue'))
    await p.click('text=#16501')
    await p.waitForSelector('[data-testid="line-places-item-1"]')
    await p.waitForTimeout(500)
    await p.screenshot({ path: `${out}/pack-queue-${lang}.png` })
    await ctx.close()
  }
  {
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, deviceScaleFactor: 2 })
    const p = await ctx.newPage()
    await p.goto(base(lang, 'waybill'))
    await p.click(`button:has-text("${START[lang]}")`)
    const input = await p.waitForSelector('input.input-scan')
    await input.type('74821903')
    await input.press('Enter')
    await p.waitForSelector('[data-testid="line-places-item-1"]')
    await p.waitForTimeout(900)
    await p.screenshot({ path: `${out}/pack-waybill-${lang}.png` })
    await ctx.close()
  }
  {
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, deviceScaleFactor: 2 })
    const p = await ctx.newPage()
    await p.goto(base(lang, 'lists'))
    await p.waitForSelector('[data-testid="not-packed-places"]')
    await p.waitForTimeout(400)
    await p.screenshot({ path: `${out}/waybill-lists-${lang}.png`, fullPage: true })
    await ctx.close()
  }
}
await browser.close()
await server.close()
