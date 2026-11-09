import { test, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import portalEn from '../portal/locales/en.json'
import portalAr from '../portal/locales/ar.json'
import { CARRIER_STATUS_KEYS } from '../components/ui'

/**
 * 2026-11-09 — i18next renders a placeholder whose value isn't passed as the literal text ("With {{carrier}}" in
 * the order drawer). Two guards:
 *  1. every t('literal.key', {...}) call in the app passes every {{placeholder}} its EN / AR text uses
 *     (a call that passes a variable instead of an object literal, or spreads one, isn't checked);
 *  2. every status.* key that names a carrier is in CARRIER_STATUS_KEYS — those keys come from the server
 *     (derivedStatus.primaryKey), so no literal call exists to check; OrderStatus / LegStatusBadge pass the
 *     carrier for exactly that set.
 */

type Dict = Record<string, string>
const PLURALS = ['zero', 'one', 'two', 'few', 'many', 'other']

function flat(o: unknown, prefix = '', out: Dict = {}): Dict {
  for (const [k, v] of Object.entries(o as Record<string, unknown>)) {
    const key = prefix ? `${prefix}.${k}` : k
    if (v && typeof v === 'object') flat(v, key, out)
    else out[key] = String(v)
  }
  return out
}

const MAIN = [flat(en), flat(ar)]
const PORTAL = [flat(portalEn), flat(portalAr)]

function variants(key: string) { return [key, ...PLURALS.map(p => `${key}_${p}`)] }
function exists(dicts: Dict[], key: string) { return dicts.some(d => variants(key).some(k => d[k] != null)) }
function placeholders(dicts: Dict[], key: string): Set<string> {
  const s = new Set<string>()
  for (const d of dicts) for (const k of variants(key)) {
    if (d[k] == null) continue
    for (const m of d[k].matchAll(/\{\{\s*(\w+)[^}]*\}\}/g)) s.add(m[1])
  }
  return s
}

/** Index just past the string literal starting at i (quote at src[i]). */
function skipString(src: string, i: number): number {
  const q = src[i]
  i++
  while (i < src.length && src[i] !== q) { if (src[i] === '\\') i++; i++ }
  return i + 1
}

/** Splits the top level of `body` at commas (brackets and strings respected). */
function splitTop(body: string): string[] {
  const parts: string[] = []
  let depth = 0, start = 0
  for (let i = 0; i < body.length; i++) {
    const c = body[i]
    if (c === "'" || c === '"' || c === '`') { i = skipString(body, i) - 1; continue }
    if ('({['.includes(c)) depth++
    else if (')}]'.includes(c)) depth--
    else if (c === ',' && depth === 0) { parts.push(body.slice(start, i)); start = i + 1 }
  }
  parts.push(body.slice(start))
  return parts
}

export function missingPlaceholders(src: string, dicts: Dict[]): { line: number; key: string; missing: string[] }[] {
  const found: { line: number; key: string; missing: string[] }[] = []
  const re = /\bt\(/g
  let m: RegExpExecArray | null
  while ((m = re.exec(src))) {
    // the call's argument text, up to its closing paren
    let i = m.index + 2, depth = 1
    const start = i
    while (i < src.length && depth > 0) {
      const c = src[i]
      if (c === "'" || c === '"' || c === '`') { i = skipString(src, i); continue }
      if ('({['.includes(c)) depth++
      else if (')}]'.includes(c)) depth--
      i++
    }
    const [first, ...rest] = splitTop(src.slice(start, i - 1))
    const keys = [...first.matchAll(/'([\w.]+)'|"([\w.]+)"/g)]
      .map(x => x[1] ?? x[2]).filter(k => k.includes('.') && exists(dicts, k))
    if (keys.length === 0) continue
    const opts = rest.join(',').trim()
    if (opts && !opts.startsWith('{')) continue // a variable — not checkable here
    const passed = new Set<string>()
    let spread = false
    if (opts) {
      let d = 0, end = 1
      for (; end < opts.length; end++) {
        const c = opts[end]
        if (c === "'" || c === '"' || c === '`') { end = skipString(opts, end) - 1; continue }
        if ('({['.includes(c)) d++
        else if (')}]'.includes(c)) { if (d === 0) break; d-- }
      }
      for (const seg of splitTop(opts.slice(1, end))) {
        const s = seg.trim()
        if (s.startsWith('...')) spread = true
        const name = s.match(/^['"]?(\w+)['"]?\s*(:|$)/)
        if (name) passed.add(name[1])
      }
    }
    if (spread) continue
    for (const key of keys) {
      const missing = [...placeholders(dicts, key)].filter(v => !passed.has(v))
      if (missing.length) found.push({ line: src.slice(0, m.index).split('\n').length, key, missing })
    }
  }
  return found
}

function sourceFiles(dir: string, out: string[] = []): string[] {
  for (const f of fs.readdirSync(dir)) {
    const p = path.join(dir, f)
    if (fs.statSync(p).isDirectory()) { if (f !== 'test' && f !== 'locales') sourceFiles(p, out) }
    else if (/\.tsx?$/.test(f) && !/\.test\.tsx?$/.test(f)) out.push(p)
  }
  return out
}

const SRC = path.resolve(__dirname, '..')

test('the scanner catches a call that leaves a placeholder out', () => {
  const probe = [
    "t('pickups.courier')",
    "t('pickups.courier', { other: 1 })",
    "t(x ? 'orders.carrierFilter.option' : 'a.b', { count: 2 })",
    "t('pickups.courier', { carrier: name })",
    "t('pickups.courier', opts)",
  ].join('\n')
  expect(missingPlaceholders(probe, MAIN).map(f => `${f.line}:${f.key}:${f.missing.join(',')}`)).toEqual([
    '1:pickups.courier:carrier',
    '2:pickups.courier:carrier',
    '3:orders.carrierFilter.option:carrier',
  ])
})

test('every t() call passes every placeholder its EN / AR text uses', () => {
  const problems: string[] = []
  for (const file of sourceFiles(SRC)) {
    const dicts = file.includes(`${path.sep}portal${path.sep}`) ? PORTAL : MAIN
    for (const f of missingPlaceholders(fs.readFileSync(file, 'utf8'), dicts)) {
      problems.push(`${path.relative(SRC, file)}:${f.line} ${f.key} — missing ${f.missing.join(', ')}`)
    }
  }
  expect(problems).toEqual([])
})

test('every server-sent status key that names a carrier is rendered with one (CARRIER_STATUS_KEYS)', () => {
  const carrierKeys = Object.keys(MAIN[0]).concat(Object.keys(MAIN[1]))
    .filter(k => k.startsWith('status.') && /\{\{\s*carrier/.test(MAIN[0][k] ?? MAIN[1][k]))
  expect([...new Set(carrierKeys)].sort()).toEqual([...CARRIER_STATUS_KEYS].sort())
})
