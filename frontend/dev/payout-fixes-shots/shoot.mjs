// Payout fixes (2026-11-27) screenshots: Money (payout check) and Summary (pipeline), EN / LTR and AR / RTL.
//   node dev/payout-fixes-shots/shoot.mjs <outDir>   (SHOTS_PORT, default 5293)
import { createServer } from 'vite'
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'

const out = process.argv[2] ?? 'payout-fixes-shots'
const port = Number(process.env.SHOTS_PORT ?? 5293)
mkdirSync(out, { recursive: true })
process.env.VITE_ANALYTICS_ENABLED = 'true'
const server = await createServer({ server: { port, strictPort: true }, logLevel: 'error' })
await server.listen()
const browser = await chromium.launch()
try {
  for (const [name, path, sel] of [['money', '/analytics/money', '[data-testid="payouts-table"]'],
                                   ['summary', '/analytics/summary', '[data-testid="fees-to-deduct"]']]) {
    for (const lang of ['en', 'ar']) {
      const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 }, deviceScaleFactor: 1 })
      const p = await ctx.newPage()
      const errors = []
      p.on('pageerror', e => errors.push(String(e)))
      await p.goto(`http://localhost:${port}/dev/payout-fixes-shots/harness.html?fixture=broek&lang=${lang}&path=${encodeURIComponent(path)}`)
      await p.waitForSelector(sel, { timeout: 15000 })
      await p.waitForLoadState('networkidle')
      await p.waitForTimeout(400)
      await p.addStyleTag({ content: '.h-screen{height:auto!important} .overflow-hidden{overflow:visible!important} main{overflow:visible!important}' })
      await p.screenshot({ path: `${out}/${name}-${lang}.png`, fullPage: true })
      const card = await p.$(name === 'money' ? '[data-testid="card-payouts"]' : '[data-testid="pipeline"]')
      if (card) await card.screenshot({ path: `${out}/${name}-${lang}-detail.png` })
      if (errors.length) console.log(name, lang, 'page errors:', errors)
      await ctx.close()
    }
  }
} finally {
  await browser.close()
  await server.close()
}
