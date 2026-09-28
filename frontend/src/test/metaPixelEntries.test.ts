import { describe, test, expect } from 'vitest'
import { readFileSync, existsSync } from 'node:fs'
import { resolve, dirname } from 'node:path'

/**
 * The Meta Pixel (src/metaPixel.ts, fbevents.js) must never load inside the Shopify-embedded
 * app (embedded.html) or the customer returns portal (portal.html). Walks each entry's static
 * + dynamic relative import graph from source; `import type` is erased at build and skipped.
 * Positive control: the standalone entry (index.html → main.tsx) DOES reach metaPixel.ts,
 * proving the walker actually follows imports.
 */
const ROOT = resolve(__dirname, '../..')
const PIXEL = resolve(ROOT, 'src/metaPixel.ts')
const EXTS = ['', '.ts', '.tsx', '/index.ts', '/index.tsx']
const IMPORT_RE =
  /(?:import|export)\s+(type\s+)?(?:[^'"]*?\s+from\s+)?['"]([^'"]+)['"]|import\(\s*['"]([^'"]+)['"]\s*\)/g

function resolveImport(fromFile: string, spec: string): string | null {
  if (!spec.startsWith('.')) return null
  const base = resolve(dirname(fromFile), spec.replace(/\?raw$/, ''))
  for (const ext of EXTS) {
    const f = base + ext
    if (existsSync(f) && /\.(ts|tsx)$/.test(f)) return f
  }
  return null
}

function reachable(entry: string): Set<string> {
  const seen = new Set<string>()
  const stack = [entry]
  while (stack.length) {
    const f = stack.pop()!
    if (seen.has(f)) continue
    seen.add(f)
    const src = readFileSync(f, 'utf8')
    for (const m of src.matchAll(IMPORT_RE)) {
      if (m[1]) continue // import type — erased
      const next = resolveImport(f, m[2] ?? m[3])
      if (next) stack.push(next)
    }
  }
  return seen
}

function entryOf(html: string): string {
  const src = readFileSync(resolve(ROOT, html), 'utf8')
  const m = src.match(/<script type="module" src="\/([^"]+)"/)
  if (!m) throw new Error(`no module entry in ${html}`)
  return resolve(ROOT, m[1])
}

describe('Meta Pixel stays out of the embedded app and the returns portal', () => {
  test.each(['embedded.html', 'portal.html'])('%s never reaches metaPixel.ts or fbevents.js', html => {
    expect(readFileSync(resolve(ROOT, html), 'utf8')).not.toMatch(/facebook|fbq/i)
    const files = reachable(entryOf(html))
    expect(files.size).toBeGreaterThan(1)
    expect(files.has(PIXEL)).toBe(false)
    for (const f of files) expect(readFileSync(f, 'utf8'), f).not.toContain('connect.facebook.net')
  })

  test('index.html has no pixel in the HTML itself (it is also served at / for the Shopify iframe)', () => {
    expect(readFileSync(resolve(ROOT, 'index.html'), 'utf8')).not.toMatch(/facebook|fbq/i)
  })

  test('positive control: the standalone entry reaches metaPixel.ts via the Signup page', () => {
    expect(reachable(entryOf('index.html')).has(PIXEL)).toBe(true)
  })
})
