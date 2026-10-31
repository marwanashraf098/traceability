import { createServer } from 'vite'
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'
// Places rollout screenshots, EN + AR: Settings › Places switched off (the switch only), switched on with no zone yet
// (today's quick setup), the turn-off confirm; the gather list and the pack screen (queue + waybill) of a store that
// switched places on but has no shelf or box — they look like today.
//   node dev/placesrollout-shots/shoot.mjs <outDir>   (SHOTS_PORT, default 5294)
const out = process.argv[2] ?? 'placesrollout-shots'
const port = Number(process.env.SHOTS_PORT ?? 5294)
const server = await createServer({ root: process.cwd(), server: { port, strictPort: true }, logLevel: 'error' })
mkdirSync(out, { recursive: true })
await server.listen()
const browser = await chromium.launch()
const base = (lang, view) => `http://localhost:${port}/dev/placesrollout-shots/harness.html?lang=${lang}&view=${view}`
const START = { en: 'Start pack session', ar: 'ابدأ جلسة تغليف' }
async function page(lang, view) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, deviceScaleFactor: 2 })
  const p = await ctx.newPage()
  await p.goto(base(lang, view))
  return { ctx, p }
}
for (const lang of ['en', 'ar']) {
  {
    const { ctx, p } = await page(lang, 'settings-off')
    await p.waitForSelector('[data-testid="places-switch"]')
    await p.waitForTimeout(400)
    await p.screenshot({ path: `${out}/settings-off-${lang}.png` })
    await ctx.close()
  }
  {
    const { ctx, p } = await page(lang, 'settings-on')
    await p.waitForSelector('[data-testid="places-empty"]')
    await p.waitForTimeout(500)
    await p.screenshot({ path: `${out}/settings-on-empty-${lang}.png`, fullPage: true })
    await p.click('[data-testid="places-switch"] [role="switch"]')
    await p.waitForSelector('[data-testid="places-switch-confirm"]')
    await p.waitForTimeout(400)
    await p.screenshot({ path: `${out}/settings-turn-off-confirm-${lang}.png` })
    await ctx.close()
  }
  {
    const { ctx, p } = await page(lang, 'gather')
    await p.waitForSelector('[data-testid="gather-list"], table')
    await p.waitForTimeout(600)
    await p.screenshot({ path: `${out}/gather-not-set-up-${lang}.png`, fullPage: true })
    await ctx.close()
  }
  {
    const { ctx, p } = await page(lang, 'queue')
    await p.click('text=#16501')
    await p.waitForSelector('[data-testid="fulfill-pick"]')
    await p.waitForTimeout(600)
    await p.screenshot({ path: `${out}/pack-queue-not-set-up-${lang}.png` })
    await ctx.close()
  }
  {
    const { ctx, p } = await page(lang, 'waybill')
    await p.click(`button:has-text("${START[lang]}")`)
    const input = await p.waitForSelector('input.input-scan')
    await input.type('74821903')
    await input.press('Enter')
    await p.waitForSelector('[data-testid="order-card"]')
    await p.waitForTimeout(900)
    await p.screenshot({ path: `${out}/pack-waybill-not-set-up-${lang}.png` })
    await ctx.close()
  }
}
await browser.close()
await server.close()
