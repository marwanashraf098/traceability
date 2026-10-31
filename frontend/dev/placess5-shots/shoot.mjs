import { createServer } from 'vite'
import { chromium } from 'playwright'
import { mkdirSync, readFileSync } from 'node:fs'
// Warehouse places S5 screenshots, EN + AR: the one-place count (scanning, Finish confirm, summary), the full stock
// take with a place sticker scanned, the Inventory place filter, the "Where is it" drawer, Move a box.
//   node dev/placess5-shots/shoot.mjs <outDir>   (SHOTS_PORT, default 5293)
const out = process.argv[2] ?? 'placess5-shots'
const port = Number(process.env.SHOTS_PORT ?? 5293)
const server = await createServer({ root: process.cwd(), server: { port, strictPort: true }, logLevel: 'error' })
mkdirSync(out, { recursive: true })
await server.listen()
const browser = await chromium.launch()
const base = (lang, view) => `http://localhost:${port}/dev/placess5-shots/harness.html?lang=${lang}&view=${view}`
const tr = Object.fromEntries(['en', 'ar'].map(l => [l, JSON.parse(readFileSync(`src/locales/${l}.json`, 'utf8'))]))
const T = (lang, path) => path.split('.').reduce((o, k) => o[k], tr[lang])

async function page(lang, view, height = 900) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height }, deviceScaleFactor: 2 })
  const p = await ctx.newPage()
  await p.goto(base(lang, view))
  return { ctx, p }
}
async function scan(p, code) {
  const input = await p.waitForSelector('input.input-scan')
  await input.type(code)
  await input.press('Enter')
  await p.waitForTimeout(250)
}

for (const lang of ['en', 'ar']) {
  {
    const { ctx, p } = await page(lang, 'count', 1300)
    await p.waitForSelector('[data-testid="place-count-scan"]')
    await scan(p, 'P103801')
    await scan(p, 'P103890')
    await scan(p, 'Q000012')
    await p.screenshot({ path: `${out}/count-scanning-${lang}.png` })
    await p.click(`footer button:has-text("${T(lang, 'placeCount.finish')}")`)
    await p.waitForSelector('[data-testid="place-count-confirm"]')
    await p.waitForTimeout(300)
    await p.screenshot({ path: `${out}/count-confirm-${lang}.png` })
    await ctx.close()
  }
  {
    const { ctx, p } = await page(lang, 'countdone')
    await p.waitForSelector('[data-testid="place-count-done"]')
    await p.waitForTimeout(300)
    await p.screenshot({ path: `${out}/count-summary-${lang}.png` })
    await ctx.close()
  }
  {
    const { ctx, p } = await page(lang, 'stocktake')
    await p.waitForSelector('[data-testid="stocktake-scan"]')
    await scan(p, 'Q000002')
    await scan(p, 'P000101')
    await scan(p, 'P000102')
    await p.waitForSelector('[data-testid="stocktake-counting-place"]')
    await p.screenshot({ path: `${out}/stocktake-place-sticker-${lang}.png`, fullPage: true })
    await ctx.close()
  }
  {
    const { ctx, p } = await page(lang, 'inventory')
    await p.click(`button:has-text("${T(lang, 'inventory.stock.allLocations')}")`)
    await p.click(`li button:has-text("${lang === 'ar' ? 'المخزن الرئيسي' : 'Main Warehouse'}")`)
    await p.click(`button:has-text("${T(lang, 'placeFilter.all')}")`)
    await p.waitForTimeout(300)
    await p.screenshot({ path: `${out}/inventory-place-picker-${lang}.png` })
    await p.click('li button:has-text("S2")')
    await p.click(`text=${lang === 'ar' ? 'تيشيرت أسود' : 'Black T-shirt'}`)
    await p.waitForTimeout(300)
    await p.screenshot({ path: `${out}/inventory-place-filter-${lang}.png` })
    await p.click('[data-testid="where-is-it-v1"]')
    await p.waitForSelector('[data-testid="where-is-it-place"]')
    await p.waitForTimeout(300)
    await p.screenshot({ path: `${out}/where-is-it-${lang}.png` })
    await ctx.close()
  }
  {
    const { ctx, p } = await page(lang, 'movebox')
    await p.waitForSelector('[data-testid="move-box"]')
    await scan(p, 'Q000012')
    await p.waitForSelector('[data-testid="move-box-chosen"]')
    await p.screenshot({ path: `${out}/move-box-chosen-${lang}.png` })
    await scan(p, 'Q000004')
    await p.waitForSelector('[data-testid="move-box-done"]')
    await p.screenshot({ path: `${out}/move-box-done-${lang}.png` })
    await ctx.close()
  }
}
await browser.close()
await server.close()
