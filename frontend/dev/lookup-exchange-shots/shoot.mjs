import { createServer } from 'vite'
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'
// C2 Lookup Exchange screenshots, EN + AR, desktop + phone:
//   Lookup — the 3 dialog steps, each refusal, an untagged piece (no Exchange), a piece reserved to an exchange
//   drawer — reserved / no waybill yet, the Cancel exchange confirm, after dispatch
//   node dev/lookup-exchange-shots/shoot.mjs <outDir>   (SHOTS_PORT, default 5290)
const out = process.argv[2] ?? 'lookup-exchange-shots'
const port = Number(process.env.SHOTS_PORT ?? 5290)
const server = await createServer({ root: process.cwd(), server: { port, strictPort: true }, logLevel: 'error' })
mkdirSync(out, { recursive: true })
await server.listen()
const browser = await chromium.launch()

const REFUSALS = ['NOT_AT_MAIN_WAREHOUSE', 'PIECE_UNTAGGED', 'PIECE_NOT_AVAILABLE', 'LOOKUP_EXCHANGE_UNAVAILABLE',
  'ORDER_NOT_FOUND', 'RETURNING_NOT_ELIGIBLE', 'SAME_AS_OUTBOUND', 'TRACKING_TAKEN']
const shots = []

async function open(ctx, qs, waitFor) {
  const p = await ctx.newPage()
  await p.goto(`http://localhost:${port}/dev/lookup-exchange-shots/harness.html?${qs}`)
  await p.waitForSelector(waitFor, { timeout: 20000 })
  await p.waitForTimeout(300)
  return p
}
async function shot(p, name) {
  await p.waitForTimeout(250)
  const path = `${out}/${name}.png`
  await p.screenshot({ path, fullPage: true })
  shots.push(path)
}
async function toStep(p, step) {
  await p.click('[data-testid="exchange-open-btn"]')
  await p.fill('[data-testid="lookup-exchange-search"]', 'Mona')
  await p.waitForSelector('[data-testid="lookup-exchange-orders"]')
  if (step === 1) return
  await p.click('[data-testid="lookup-exchange-orders"] button >> nth=0')
  await p.waitForSelector('[data-testid="lookup-exchange-returnables"]')
  await p.click('[data-testid="lookup-exchange-returnables"] input[type=radio] >> nth=0')
  await p.selectOption('#lookup-exchange-reason', 'wrong_size')
  if (step === 2) return
  await p.fill('[data-testid="lookup-exchange-outbound"]', '7712 0048 3391')
}

for (const lang of ['en', 'ar']) {
  for (const [w, h, label] of [[1440, 1000, 'desktop'], [390, 844, 'phone']]) {
    const ctx = await browser.newContext({ viewport: { width: w, height: h }, deviceScaleFactor: 2 })
    const tag = `${label}-${lang}`

    for (const step of [1, 2, 3]) {
      const p = await open(ctx, `lang=${lang}&page=lookup`, '[data-testid="exchange-open-btn"]')
      await toStep(p, step)
      // the dialog scrolls inside the modal: show the step being filled
      await p.evaluate(() => {
        const box = document.querySelector('[data-testid="lookup-exchange-dialog"]')
        if (box) box.scrollTop = box.scrollHeight
      })
      await shot(p, `lookup-dialog-step${step}-${tag}`)
      await p.close()
    }
    for (const code of REFUSALS) {
      const p = await open(ctx, `lang=${lang}&page=lookup&refusal=${code}`, '[data-testid="exchange-open-btn"]')
      await toStep(p, 3)
      if (code === 'SAME_AS_OUTBOUND') await p.fill('#lookup-exchange-return', '7712 0048 3391')
      await p.click('[data-testid="lookup-exchange-submit"]')
      await p.waitForSelector('[data-testid="lookup-exchange-error"]')
      await p.evaluate(() => {
        const box = document.querySelector('[data-testid="lookup-exchange-dialog"]')
        if (box) box.scrollTop = box.scrollHeight
      })
      await shot(p, `lookup-refusal-${code}-${tag}`)
      await p.close()
    }
    {
      const p = await open(ctx, `lang=${lang}&page=lookup&piece=untagged`, '[data-testid="piece-untagged-note"]')
      await shot(p, `lookup-untagged-piece-no-exchange-${tag}`)
      await p.close()
    }
    {
      const p = await open(ctx, `lang=${lang}&page=lookup&piece=reserved`, '[data-testid="lookup-exchange-note"]')
      await shot(p, `lookup-reserved-piece-${tag}`)
      await p.close()
    }
    {
      const p = await open(ctx, `lang=${lang}&page=drawer&drawer=reserved`, '[data-testid="lookup-exchange-reserved"]')
      await p.locator('[data-testid="lookup-exchange"]').scrollIntoViewIfNeeded()
      await shot(p, `drawer-reserved-no-waybill-${tag}`)
      await p.click('[data-testid="lookup-exchange-cancel"]')
      await p.waitForSelector('[data-testid="lookup-exchange-cancel-confirm"]')
      await p.locator('[data-testid="lookup-exchange-cancel-confirm"]').scrollIntoViewIfNeeded()
      await shot(p, `drawer-cancel-confirm-${tag}`)
      await p.close()
    }
    {
      const p = await open(ctx, `lang=${lang}&page=drawer&drawer=left`, '[data-testid="lookup-exchange-left"]')
      await p.locator('[data-testid="lookup-exchange"]').scrollIntoViewIfNeeded()
      await shot(p, `drawer-after-dispatch-${tag}`)
      await p.close()
    }
    await ctx.close()
  }
}
await browser.close()
await server.close()
console.log(shots.length + ' screenshots in ' + out)
