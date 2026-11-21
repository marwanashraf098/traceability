import { createServer } from 'vite'
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'
// Bazar R4 screenshots, EN/LTR + AR/RTL at 1280 and 390:
//   Sell quick (SOLD card), Sell cart (3 pieces + 60 EGP discount), the pick sheet ("#12"), Return (found sale),
//   Exchange (customer pays), Find the sale (several matches).
//   node dev/bazar-r4-shots/shoot.mjs <outDir>   (SHOTS_PORT, default 5291)
const out = process.argv[2] ?? 'bazar-r4-shots'
const port = Number(process.env.SHOTS_PORT ?? 5291)
const server = await createServer({ root: process.cwd(), server: { port, strictPort: true, fs: { strict: false } }, logLevel: 'error' })
mkdirSync(out, { recursive: true })
await server.listen()
const browser = await chromium.launch()
const shots = []

async function open(ctx, lang, path, waitFor) {
  const p = await ctx.newPage()
  await p.goto(`http://localhost:${port}/dev/bazar-r4-shots/harness.html?lang=${lang}&path=${path}`)
  await p.waitForSelector(waitFor, { timeout: 20000 })
  await p.waitForTimeout(400)
  return p
}
async function shot(p, name) {
  await p.waitForTimeout(300)
  const path = `${out}/${name}.png`
  await p.screenshot({ path, fullPage: true })
  shots.push(path)
}
async function scan(p, sel, code) {
  await p.fill(sel, code)
  await p.press(sel, 'Enter')
}

for (const lang of ['en', 'ar']) {
  for (const [w, h, label] of [[1280, 960, 'computer'], [390, 844, 'phone']]) {
    const ctx = await browser.newContext({ viewport: { width: w, height: h }, deviceScaleFactor: 2 })
    const tag = `${label}-${lang}`
    {
      const p = await open(ctx, lang, 'sell', '[data-testid="bazar-scan-input"]')
      await scan(p, '[data-testid="bazar-scan-input"]', 'P000231')
      await p.waitForSelector('[data-testid="sell-card-sold"]')
      await shot(p, `sell-quick-${tag}`)
      await p.close()
    }
    {
      const p = await open(ctx, lang, 'sell', '[data-testid="bazar-scan-input"]')
      await p.click('[data-testid="bazar-mode-switch"] [role=radio] >> nth=1')
      for (const c of ['P000228', 'P000240', 'P000227']) await scan(p, '[data-testid="bazar-scan-input"]', c)
      await p.waitForFunction(() => document.querySelectorAll('[data-testid="cart-line"]').length === 3)
      await p.fill('[data-testid="cart-discount"]', '60')
      await shot(p, `sell-cart-${tag}`)
      await p.close()
    }
    {
      const p = await open(ctx, lang, 'sell', '[data-testid="bazar-scan-input"]')
      await scan(p, '[data-testid="bazar-scan-input"]', '#12')
      await p.waitForSelector('[data-testid="bazar-pick-sheet"]')
      await p.click('[data-testid="pick-size"] >> nth=1')
      await shot(p, `pick-sheet-${tag}`)
      await p.close()
    }
    {
      const p = await open(ctx, lang, 'return', '[data-testid="bazar-code-input"]')
      await scan(p, '[data-testid="bazar-code-input"]', 'P000228')
      await p.waitForSelector('[data-testid="return-sale"]')
      await shot(p, `return-${tag}`)
      await p.close()
    }
    {
      const p = await open(ctx, lang, 'return', '[data-testid="bazar-code-input"]')
      await p.click('[data-testid="return-find"]')
      await p.click('[data-testid="pick-product"] >> nth=2 >> button >> nth=0')
      await p.click('[data-testid="pick-size"] >> nth=0')
      await p.click('[data-testid="pick-sell"]')
      await p.click('[data-testid="find-go"]')
      await p.waitForSelector('[data-testid="find-several"]')
      await shot(p, `return-find-sale-${tag}`)
      await p.close()
    }
    {
      const p = await open(ctx, lang, 'exchange', '[data-testid="bazar-code-input"]')
      await scan(p, '[data-testid="bazar-code-input"]', 'P000228')
      await p.waitForSelector('[data-testid="exchange-old"]')
      await scan(p, '[data-testid="bazar-code-input"]', 'P000244')
      await p.waitForSelector('[data-testid="exchange-difference"]')
      await shot(p, `exchange-${tag}`)
      await p.close()
    }
    await ctx.close()
  }
}
await browser.close()
await server.close()
console.log(shots.join('\n'))
