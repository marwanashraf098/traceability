import { createServer } from 'vite'
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'
// Put-away lists (S4) screenshots, EN + AR: the buttons (finalized receipt, Tag stock summary, closed returns summary),
// the transfer reconcile end screen, the transfer send-out collect list, the put-away list (screen + A6 / A4 print)
// and the scan screen opened from a list ("X / Y placed").
//   node dev/putawaylists-shots/shoot.mjs <outDir>   (SHOTS_PORT, default 5292)
const out = process.argv[2] ?? 'putawaylists-shots'
const port = Number(process.env.SHOTS_PORT ?? 5292)
const server = await createServer({ root: process.cwd(), server: { port, strictPort: true }, logLevel: 'error' })
mkdirSync(out, { recursive: true })
await server.listen()
const browser = await chromium.launch()
const base = (lang, view) => `http://localhost:${port}/dev/putawaylists-shots/harness.html?lang=${lang}&view=${view}`
const CLOSE = { en: ['Close Transfer', 'Confirm Close'], ar: ['إغلاق عملية النقل', 'تأكيد الإغلاق'] }
const A4 = { en: 'Print A4', ar: 'طباعة A4' }

async function shot(lang, view, wait, file, { before, full = true, height = 900 } = {}) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height }, deviceScaleFactor: 2 })
  const p = await ctx.newPage()
  await p.goto(base(lang, view))
  if (before) await before(p)
  await p.waitForSelector(wait)
  await p.waitForTimeout(500)
  await p.screenshot({ path: `${out}/${file}-${lang}.png`, fullPage: full })
  await ctx.close()
}

for (const lang of ['en', 'ar']) {
  await shot(lang, 'receiving', '[data-testid="put-away-list-button-receiving"]', 'receiving-button')
  await shot(lang, 'tagstock', '[data-testid="put-away-list-button-tag_session"]', 'tagstock-button')
  await shot(lang, 'returns', '[data-testid="put-away-list-button-return_session"]', 'returns-summary-button', {
    before: async p => { await p.click('text=RT-A7C3') },
  })
  await shot(lang, 'reconcile', '[data-testid="transfer-reconcile-end"]', 'transfer-end', {
    before: async p => {
      await p.click(`button:has-text("${CLOSE[lang][0]}")`)
      await p.click(`button:has-text("${CLOSE[lang][1]}")`)
    },
  })
  await shot(lang, 'scanout', '[data-testid="transfer-collect-list"]', 'transfer-collect', { height: 1500 })
  await shot(lang, 'list', '[data-testid="put-away-list"]', 'list')
  await shot(lang, 'scan', '[data-testid="put-away-scope-progress"]', 'scan-from-list', { full: false })
  for (const size of ['A6', 'A4']) {
    // The page as the browser prints it (print media; the page's own @page rule sets the paper size).
    const width = size === 'A6' ? 397 : 794
    const ctx = await browser.newContext({ viewport: { width, height: 1123 }, deviceScaleFactor: 2 })
    const p = await ctx.newPage()
    await p.addInitScript(() => { window.print = () => {} })
    await p.goto(base(lang, 'list'))
    await p.waitForSelector('[data-testid="put-away-list"]')
    if (size === 'A4') await p.click(`button:has-text("${A4[lang]}")`)
    await p.emulateMedia({ media: 'print' })
    await p.waitForTimeout(400)
    await p.screenshot({ path: `${out}/list-print-${size}-${lang}.png`, fullPage: true })
    await p.pdf({ path: `${out}/list-print-${size}-${lang}.pdf`, preferCSSPageSize: true, printBackground: true })
    await ctx.close()
  }
}
await browser.close()
await server.close()
