// Analytics slices 4–6 (2026-11-27) screenshots: Summary, Delivery, Customers, Order finances, EN / LTR and AR / RTL.
//   node dev/shipping-refused-shots/shoot.mjs <outDir>   (SHOTS_PORT, default 5294)
import { createServer } from 'vite'
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'

const out = process.argv[2] ?? 'shipping-refused-shots'
const port = Number(process.env.SHOTS_PORT ?? 5294)
mkdirSync(out, { recursive: true })
process.env.VITE_ANALYTICS_ENABLED = 'true'
const server = await createServer({ server: { port, strictPort: true }, logLevel: 'error' })
await server.listen()
const browser = await chromium.launch()
try {
  for (const [name, path, sel, card] of [['summary', '/analytics/summary', '[data-testid="kpi-success"]', null],
                                         ['delivery', '/analytics/delivery', '[data-testid="watch-table"]', '[data-testid="card-watch"]'],
                                         ['customers', '/analytics/customers', '[data-testid="watch-table"]', '[data-testid="card-watch"]'],
                                         ['orders', '/analytics/orders', '[data-testid="order-shipping"]', null],
                                         ['revenue', '/analytics/revenue', '[data-testid="kpi-shipping-income"]', null]]) {
    for (const lang of ['en', 'ar']) {
      const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 }, deviceScaleFactor: 1 })
      const p = await ctx.newPage()
      const errors = []
      p.on('pageerror', e => errors.push(String(e)))
      await p.goto(`http://localhost:${port}/dev/shipping-refused-shots/harness.html?fixture=broek&lang=${lang}&path=${encodeURIComponent(path)}`)
      await p.waitForSelector(sel, { timeout: 15000 })
      await p.waitForLoadState('networkidle')
      await p.waitForTimeout(400)
      await p.addStyleTag({ content: '.h-screen{height:auto!important} .overflow-hidden{overflow:visible!important} main{overflow:visible!important}' })
      await p.screenshot({ path: `${out}/${name}-${lang}.png`, fullPage: true })
      const el = card ? await p.$(card) : null
      if (el) await el.screenshot({ path: `${out}/${name}-${lang}-detail.png` })
      if (errors.length) console.log(name, lang, 'page errors:', errors)
      await ctx.close()
    }
  }
} finally {
  await browser.close()
  await server.close()
}
