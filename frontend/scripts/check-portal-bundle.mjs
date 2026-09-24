// Returns portal bundle check (Step 4e-B). Run from frontend/ after `npm run build`:
//   node scripts/check-portal-bundle.mjs
// Follows every script, modulepreload and stylesheet that static/portal.html loads and fails
// if any of them contains the merchant app's token-refresh code path or a merchant-only
// locale string. Prints the size of each file the portal page loads.
import { readFileSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const staticDir = join(dirname(fileURLToPath(import.meta.url)), '../../src/main/resources/static')
const html = readFileSync(join(staticDir, 'portal.html'), 'utf8')
const files = [...html.matchAll(/(?:src|href)="\/(assets\/[^"]+)"/g)].map(m => m[1])
if (!files.some(f => /^assets\/portal-[^/]+\.js$/.test(f))) {
  console.error('FAIL: portal.html does not load a portal-*.js entry')
  process.exit(1)
}

const appLocale = JSON.parse(readFileSync(join(staticDir, '../../../../frontend/src/locales/en.json'), 'utf8'))
const forbidden = [
  '/auth/refresh',                     // api.ts doRefresh() — the token refresh path
  'refresh_failed',                    // api.ts doRefresh() error
  appLocale.exchangesRefunds.title,    // "Exchanges & Refunds"
  appLocale.exchangesRefunds.status.mapped,
  appLocale.settings.portal.link.help,
  appLocale.exchangesRefunds.requests.drawer.helperManual,   // "After approving, book the pickup in Bosta."
].filter(s => typeof s === 'string' && s.length > 0)

let failed = false
for (const f of files) {
  const body = readFileSync(join(staticDir, f), 'utf8')
  const hits = forbidden.filter(s => body.includes(s))
  console.log(`${f.padEnd(48)} ${String(Buffer.byteLength(body)).padStart(8)} bytes${hits.length ? '  FORBIDDEN: ' + hits.join(' | ') : ''}`)
  if (hits.length) failed = true
}
if (failed) { console.error('FAIL: the portal bundle contains merchant-app code or strings'); process.exit(1) }
console.log(`OK: ${files.length} files, none contain: ${forbidden.map(s => JSON.stringify(s)).join(', ')}`)
