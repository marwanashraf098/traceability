import { test, expect, describe, vi, afterEach } from 'vitest'
import { render, screen, act, waitFor } from '@testing-library/react'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import { resolveState, sharedSearch, addDays, daysBetween } from '../analytics/period'
import { makeFmt, relChange } from '../analytics/format'
import { useAnalyticsQuery, clearAnalyticsCache, CACHE_TTL_MS } from '../analytics/useAnalyticsQuery'
import { LineChart } from '../components/analytics/charts/LineChart'
import { HBars } from '../components/analytics/charts/HBars'
import { Sparkline } from '../components/analytics/charts/Sparkline'
import { niceMax } from '../components/analytics/charts/chartUtils'
import { queryString } from '../analyticsApi'

// ── Locale parity (every key, both languages) ───────────────────────────────

type Tree = { [k: string]: string | Tree }
const PLURAL = /_(zero|one|two|few|many|other)$/

function flatten(t: Tree, prefix = ''): string[] {
  return Object.entries(t).flatMap(([k, v]) => (typeof v === 'string' ? [prefix + k] : flatten(v, `${prefix}${k}.`)))
}
// Arabic carries more plural forms than English (_zero/_two/_few/_many) — compare base keys.
const base = (keys: string[]) => new Set(keys.map(k => k.replace(PLURAL, '')))

describe('locale parity', () => {
  test('p1 — every English key exists in Arabic and vice versa (plural forms folded)', () => {
    const e = base(flatten(en as Tree)), a = base(flatten(ar as Tree))
    expect([...e].filter(k => !a.has(k)), 'missing in ar.json').toEqual([])
    expect([...a].filter(k => !e.has(k)), 'missing in en.json').toEqual([])
  })

  test('p2 — no empty strings in the analytics block', () => {
    for (const [name, tree] of [['en', en], ['ar', ar]] as const) {
      const block = (tree as unknown as Tree).analytics as Tree
      const empty = flatten(block).filter(k => {
        const v = k.split('.').reduce<unknown>((o, p) => (o as Tree)[p], block)
        // an Arabic "zero" form may legitimately be empty only where the copy is never shown for zero
        return v === '' && !k.endsWith('_zero')
      })
      expect(empty, name).toEqual([])
    }
  })
})

// ── URL state ───────────────────────────────────────────────────────────────

describe('period state', () => {
  const TODAY = '2026-10-09'
  const st = (q: string) => resolveState(new URLSearchParams(q), TODAY)

  test('s1 — default is the last 30 days, realized, no compare', () => {
    expect(st('')).toMatchObject({ periodKey: '30d', params: { period: '30d' }, from: '2026-09-10', to: TODAY, days: 30, mode: 'realized', compare: false })
  })

  test('s2 — presets the backend takes go through as-is; yesterday / this month become from-to', () => {
    expect(st('period=7d').params).toEqual({ period: '7d' })
    expect(st('period=today').params).toEqual({ period: 'today' })
    expect(st('period=yesterday')).toMatchObject({ params: { from: '2026-10-08', to: '2026-10-08' }, days: 1 })
    expect(st('period=month')).toMatchObject({ params: { from: '2026-10-01', to: TODAY }, days: 9 })
  })

  test('s3 — a custom range is used as-is up to 366 days; longer, reversed or malformed falls back to 30 days', () => {
    expect(st('from=2026-01-01&to=2026-03-31')).toMatchObject({ periodKey: 'custom', days: 90 })
    expect(st(`from=${addDays(TODAY, -365)}&to=${TODAY}`).days).toBe(366)
    expect(st(`from=${addDays(TODAY, -366)}&to=${TODAY}`).periodKey).toBe('30d')
    expect(st('from=2026-03-01&to=2026-01-01').periodKey).toBe('30d')
    expect(st('from=junk&to=2026-01-01').periodKey).toBe('30d')
  })

  test('s4 — compare and mode come from the URL; sharedSearch keeps only the shared keys', () => {
    expect(st('compare=1&mode=booked')).toMatchObject({ compare: true, mode: 'booked' })
    expect(sharedSearch(new URLSearchParams('period=7d&mode=booked&sku=abc&view=stuck'))).toBe('?period=7d&mode=booked')
    expect(sharedSearch(new URLSearchParams(''))).toBe('')
    expect(daysBetween('2026-01-01', '2026-01-01')).toBe(1)
  })

  test('s5 — query strings skip empty values and false flags', () => {
    expect(queryString({ period: '7d', compare: false, q: '', x: undefined, y: null })).toBe('?period=7d')
    expect(queryString({ compare: true, ids: 'a,b' })).toBe('?compare=true&ids=a%2Cb')
  })
})

// ── Formatting ──────────────────────────────────────────────────────────────

describe('formatting', () => {
  test('f1 — money: "EGP 1,234" in English, "1,234 ج.م" in Arabic, Latin digits, null is a dash', () => {
    const e = makeFmt('en'), a = makeFmt('ar')
    expect(e.money(1234.4)).toBe('EGP 1,234')
    expect(a.money(1234.4)).toBe('1,234 ج.م')
    expect(e.money(-50)).toBe('−EGP 50')
    expect(e.money(null)).toBe('—')
    expect(a.num(1234567)).toBe('1,234,567')
  })

  test('f2 — compact money and percentages', () => {
    const e = makeFmt('en')
    expect(e.moneyK(1033500)).toBe('EGP 1.03M')
    expect(e.moneyK(45210)).toBe('EGP 45k')
    expect(e.moneyK(9876)).toBe('EGP 9,876')
    expect(e.pct(0.85)).toBe('85.0%')
    expect(e.pct(null)).toBe('—')
    expect(e.pts(0.024)).toBe('2.4 pts')
  })

  test('f3 — relative change needs a non-zero base', () => {
    expect(relChange(110, 100)).toBeCloseTo(0.1)
    expect(relChange(5, 0)).toBeNull()
    expect(relChange(5, null)).toBeNull()
  })
})

// ── Cache hook ──────────────────────────────────────────────────────────────

function Probe({ k, fetcher }: { k: string | null; fetcher: (s: AbortSignal) => Promise<string> }) {
  const q = useAnalyticsQuery(k, fetcher)
  return <div data-testid="probe">{q.error ? `error:${q.error.message}` : q.data ?? 'loading'}<button onClick={q.retry}>retry</button></div>
}

describe('useAnalyticsQuery', () => {
  afterEach(() => { clearAnalyticsCache(); vi.useRealTimers() })

  test('q1 — a fresh cached answer is reused without a request; a stale one refetches', async () => {
    const fetcher = vi.fn(async () => 'v1')
    const { unmount } = render(<Probe k="a" fetcher={fetcher} />)
    await screen.findByText('v1')
    unmount()
    render(<Probe k="a" fetcher={fetcher} />)
    expect(screen.getByTestId('probe').textContent).toContain('v1')
    expect(fetcher).toHaveBeenCalledTimes(1)

    const now = Date.now()
    vi.spyOn(Date, 'now').mockReturnValue(now + CACHE_TTL_MS + 1)
    fetcher.mockResolvedValue('v2')
    act(() => { window.dispatchEvent(new Event('focus')) })
    await screen.findByText('v2')
    expect(fetcher).toHaveBeenCalledTimes(2)
    vi.restoreAllMocks()
  })

  test('q2 — changing the key aborts the request in flight', async () => {
    const signals: AbortSignal[] = []
    const fetcher = (s: AbortSignal) => { signals.push(s); return new Promise<string>(() => {}) }
    const { rerender } = render(<Probe k="p1" fetcher={fetcher} />)
    rerender(<Probe k="p2" fetcher={fetcher} />)
    expect(signals[0].aborted).toBe(true)
    expect(signals[1].aborted).toBe(false)
  })

  test('q3 — an error shows per call and retry fetches again', async () => {
    let n = 0
    const fetcher = vi.fn(async () => { n++; if (n === 1) throw new Error('500: boom'); return 'ok' })
    render(<Probe k="e" fetcher={fetcher} />)
    await screen.findByText(/error:500/)
    act(() => { screen.getByText('retry').click() })
    await waitFor(() => expect(screen.getByTestId('probe').textContent).toContain('ok'))
  })
})

// ── Charts ──────────────────────────────────────────────────────────────────

function pathXs(container: HTMLElement): number[] {
  const d = container.querySelector('path[stroke]')!.getAttribute('d')!
  return [...d.matchAll(/[ML]([\d.]+),/g)].map(m => Number(m[1]))
}

describe('charts', () => {
  test('c1 — niceMax rounds up to a readable axis top', () => {
    expect(niceMax(87)).toBe(100)
    expect(niceMax(1030)).toBe(1200)
    expect(niceMax(0)).toBe(1)
  })

  test('c2 — the line chart runs left to right in English and right to left in Arabic', () => {
    const props = { labels: ['1', '2', '3'], series: [{ name: 'A', color: '#000', values: [1, 2, 3] }], formatY: String, ariaLabel: 'x' }
    const ltr = render(<LineChart {...props} />)
    const xsL = pathXs(ltr.container)
    expect(xsL[0]).toBeLessThan(xsL[2])
    ltr.unmount()
    const rtl = render(<LineChart {...props} rtl />)
    const xsR = pathXs(rtl.container)
    expect(xsR[0]).toBeGreaterThan(xsR[2])
    // Text stays unflipped: labels are plain <text>, never inside a scale(-1) transform.
    expect(rtl.container.querySelector('[transform*="scale(-1"]')).toBeNull()
  })

  test('c3 — horizontal bars scale to the largest row and grow from the start edge', () => {
    const { container } = render(<HBars rows={[
      { key: 'a', label: 'A', value: 50, display: '50' },
      { key: 'b', label: 'B', value: 100, display: '100' },
    ]} />)
    const bars = [...container.querySelectorAll('i')] as HTMLElement[]
    expect(bars[0].style.width).toBe('50%')
    expect(bars[1].style.width).toBe('100%')
    expect(bars[0].className).toContain('start-0')
  })

  test('c4 — a sparkline marks its latest point on the reading end', () => {
    const l = render(<Sparkline values={[1, 3, 2]} />)
    expect(Number(l.container.querySelector('circle')!.getAttribute('cx'))).toBeGreaterThan(36)
    l.unmount()
    const r = render(<Sparkline values={[1, 3, 2]} rtl />)
    expect(Number(r.container.querySelector('circle')!.getAttribute('cx'))).toBeLessThan(36)
  })
})
