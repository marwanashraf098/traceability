import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect } from 'vitest'

/**
 * The CSP contract (P1 fix → P3): every image src a page renders must be allowed by the img-src of
 * the nginx server block that serves it (deploy/nginx.conf, the HTTPS 443 block of that host).
 * jsdom enforces no CSP and loads no images, so this is the check.
 */
export function imgSrcOf(host: 'app.tracedtech.com' | 'returns.tracedtech.com'): string[] {
  const conf = readFileSync(resolve(__dirname, '../../../deploy/nginx.conf'), 'utf8')
  const blocks = conf.split(/\n\s*server\s*\{/)
  const block = blocks.find(b => new RegExp(`server_name\\s+${host.replace(/\./g, '\\.')};`).test(b) && /listen\s+443/.test(b))
  expect(block, `${host} 443 server block`).toBeDefined()
  const csp = block!.match(/add_header\s+Content-Security-Policy\s+"([^"]+)"/)
  expect(csp, `CSP header in the ${host} block`).not.toBeNull()
  const img = csp![1].split(';').map(d => d.trim()).find(d => d.startsWith('img-src'))
  expect(img, `img-src directive for ${host}`).toBeDefined()
  return img!.split(/\s+/).slice(1)
}

/** Would img-src `sources` on https://{host} allow an <img> with this src? */
export function imgAllowed(host: string, sources: string[], src: string): boolean {
  const url = new URL(src, `https://${host}/`)
  if (url.protocol === 'data:' || url.protocol === 'blob:') return sources.includes(url.protocol)
  if (url.origin === `https://${host}`) return sources.includes("'self'")
  return sources.some(s => s.startsWith('https://') && url.href.startsWith(s))
}
