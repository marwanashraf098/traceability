import { createServer } from 'vite'
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'
// Unpack-order screenshots, EN + AR: the packed list (waybill page, queue page's Packed tab, phone, worker), the unpack
// dialog (default and "Other"), the success notice, the pickup-session refusal, piece history, the alert.
//   node dev/unpack-shots/shoot.mjs <outDir>   (SHOTS_PORT, default 5294)
const out = process.argv[2] ?? 'unpack-shots'
const port = Number(process.env.SHOTS_PORT ?? 5294)
const server = await createServer({ root: process.cwd(), server: { port, strictPort: true }, logLevel: 'error' })
mkdirSync(out, { recursive: true })
await server.listen()
const browser = await chromium.launch()
const base = (lang, view, role = 'manager') =>
  `http://localhost:${port}/dev/unpack-shots/harness.html?lang=${lang}&view=${view}&role=${role}`

async function page(lang, view, { width = 1280, height = 1200, role } = {}) {
  const ctx = await browser.newContext({ viewport: { width, height }, deviceScaleFactor: 2 })
  const p = await ctx.newPage()
  await p.goto(base(lang, view, role))
  return { ctx, p }
}

for (const lang of ['en', 'ar']) {
  for (const view of ['waybill', 'queue']) {
    const { ctx, p } = await page(lang, view)
    await p.waitForSelector('[data-testid="packed-row"]')
    await p.waitForTimeout(400)
    const section = await p.$('[data-testid="packed-orders"]')
    await p.screenshot({ path: `${out}/${view}-page-${lang}.png`, fullPage: true })
    await section.screenshot({ path: `${out}/${view}-packed-list-${lang}.png` })
    await ctx.close()
  }
  const unpackBtn = lang === 'ar' ? 'فكّ التغليف' : 'Unpack'
  const confirmBtn = lang === 'ar' ? 'فكّ تغليف الطلب' : 'Unpack order'
  const otherLbl = lang === 'ar' ? 'سبب آخر' : 'Other'
  {
    const { ctx, p } = await page(lang, 'waybill', { height: 1000 })
    await p.waitForSelector('[data-testid="packed-row"]')
    await p.click(`[data-testid="packed-row"] button:has-text("${unpackBtn}")`)
    await p.waitForSelector('[data-testid="unpack-dialog"]')
    await p.waitForTimeout(300)
    await p.screenshot({ path: `${out}/dialog-${lang}.png` })
    await p.click(`label:has-text("${otherLbl}")`)
    await p.fill('[data-testid="unpack-note"]', lang === 'ar' ? 'أُرسل مقاس خاطئ' : 'Wrong size sent')
    await p.waitForTimeout(200)
    await p.screenshot({ path: `${out}/dialog-other-${lang}.png` })
    await p.click(`[data-testid="unpack-dialog"] button:has-text("${confirmBtn}")`)
    await p.waitForSelector('[data-testid="unpack-dialog"]', { state: 'detached' })
    await p.waitForTimeout(400)
    await (await p.$('[data-testid="packed-orders"]')).screenshot({ path: `${out}/after-unpack-${lang}.png` })
    await ctx.close()
  }
  {
    const { ctx, p } = await page(lang, 'refused', { height: 1000 })
    await p.waitForSelector('[data-testid="packed-row"]')
    await p.click(`[data-testid="packed-row"] button:has-text("${unpackBtn}")`)
    await p.click(`[data-testid="unpack-dialog"] button:has-text("${confirmBtn}")`)
    await p.waitForSelector('[data-testid="unpack-error"]')
    await p.waitForTimeout(300)
    await p.screenshot({ path: `${out}/dialog-refused-${lang}.png` })
    await ctx.close()
  }
  {
    const { ctx, p } = await page(lang, 'waybill', { role: 'worker' })
    await p.waitForSelector('[data-testid="packed-row"]')
    await p.waitForTimeout(400)
    await (await p.$('[data-testid="packed-orders"]')).screenshot({ path: `${out}/worker-packed-list-${lang}.png` })
    await ctx.close()
  }
  {
    const { ctx, p } = await page(lang, 'lookup', { height: 1000 })
    await p.waitForSelector('input')
    const inputs = await p.$$('input')
    const input = inputs[inputs.length - 1]       // the page's own field, not the topbar search
    await input.type('P103801')
    await input.press('Enter')
    await p.waitForTimeout(800)
    await p.screenshot({ path: `${out}/piece-history-${lang}.png` })
    await ctx.close()
  }
  {
    const { ctx, p } = await page(lang, 'alerts', { height: 800 })
    await p.waitForTimeout(1200)
    await p.screenshot({ path: `${out}/alert-${lang}.png` })
    await ctx.close()
  }
  {
    const { ctx, p } = await page(lang, 'waybill', { width: 390, height: 1600 })
    await p.waitForSelector('[data-testid="packed-row"]')
    await p.waitForTimeout(400)
    await (await p.$('[data-testid="packed-orders"]')).screenshot({ path: `${out}/waybill-packed-list-phone-${lang}.png` })
    await ctx.close()
  }
}
await browser.close()
await server.close()
console.log('shots written to', out)
