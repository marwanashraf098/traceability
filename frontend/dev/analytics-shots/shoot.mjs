// Screenshots of the analytics pages for review: every fixture × EN/AR × desktop/390 px.
//   node dev/analytics-shots/shoot.mjs <outDir> [path=/analytics/summary]
// Starts a Vite dev server with VITE_ANALYTICS_ENABLED=true, then drives headless Chromium.
import { createServer } from 'vite'
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'
import { join } from 'node:path'

const out = process.argv[2] ?? 'analytics-shots'
const path = process.argv[3] ?? '/analytics/summary'
// '/analytics/money?drill=extra' → 'money-drill-extra'
const page = path.replace(/^\/analytics\//, '').replace(/[^a-z0-9]+/gi, '-').replace(/-+$/, '')
mkdirSync(out, { recursive: true })

process.env.VITE_ANALYTICS_ENABLED = 'true'
const server = await createServer({ server: { port: 5199, strictPort: true }, logLevel: 'error' })
await server.listen()
const browser = await chromium.launch()
// mobile = the real shell at 390 px; mobile-content = same width with the sidebar hidden, to judge
// the page content alone (the shell keeps its 224 px sidebar at every width today).
const viewports = { desktop: { width: 1440, height: 1000 }, mobile: { width: 390, height: 844 }, 'mobile-content': { width: 390, height: 844 } }
try {
  for (const fixture of ['broek', 'femine', 'highline']) {
    for (const lang of ['en', 'ar']) {
      for (const [vp, size] of Object.entries(viewports)) {
        const ctx = await browser.newContext({ viewport: size, deviceScaleFactor: vp === 'desktop' ? 1 : 2 })
        const p = await ctx.newPage()
        const errors = []
        p.on('pageerror', e => errors.push(String(e)))
        await p.goto(`http://localhost:5199/dev/analytics-shots/harness.html?fixture=${fixture}&lang=${lang}&path=${encodeURIComponent(path)}`)
        await p.waitForSelector('[data-testid="pipeline"], [data-testid^="analytics-"]', { timeout: 15000 })
        await p.waitForLoadState('networkidle')
        await p.waitForTimeout(400)
        // The app scrolls inside <main>; let it grow so the full page is captured.
        await p.addStyleTag({ content: '.h-screen{height:auto!important} .overflow-hidden{overflow:visible!important} main{overflow:visible!important}'
          + (vp === 'mobile-content' ? ' aside.w-56{display:none!important}' : '') })
        const file = join(out, `${page}-${fixture}-${lang}-${vp}.png`)
        await p.screenshot({ path: file, fullPage: true })
        console.log(file, errors.length ? `ERRORS: ${errors.join(' | ')}` : '')
        await ctx.close()
      }
    }
  }
} finally {
  await browser.close()
  await server.close()
}
