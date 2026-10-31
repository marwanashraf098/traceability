import { createServer } from 'vite'
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'
// Unpack-order screenshots, EN + AR: the packed list on the waybill page and on the queue page's Packed tab.
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
