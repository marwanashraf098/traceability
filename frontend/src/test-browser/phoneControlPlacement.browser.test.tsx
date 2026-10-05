import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { page } from 'vitest/browser'
import { cleanup } from '@testing-library/react'
import { Route, Routes } from 'react-router-dom'
import '../index.css'
import { renderWithProviders } from '../test/renderWithProviders'
import { stubFetchWithShellDefaults } from '../test/mockShellFetch'
import { setAccessToken, clearAccessToken } from '../auth'
import type { PackSessionView } from '../api'
import type { RelayHandlers } from '../phone/relayStream'
import { PhoneScanProvider } from '../phone/PhoneScanProvider'
import PhoneControl from '../phone/PhoneControl'
import PackSessionScreen from '../pages/fulfill/PackSessionScreen'
import Fulfill from '../pages/Fulfill'
import StockTakeScan from '../pages/StockTakeScan'

// Q1 — the floating phone control never covers an actionable control: on PickScreen (its
// full-width bottom action bar), the waybill pack session (an open order) and the stock-take
// scan screen, at 1280×800, 768×1024 and 390×844, in a real browser with the app's CSS — for
// both the connected chip and "Use phone".

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => 'owner') }
})
vi.mock('../phone/relayStream', () => ({
  openRelayStream: (_d: string, _h: RelayHandlers) => () => {},
}))

const ACTIONABLE = 'button, a[href], input:not([type="hidden"]), select, textarea, [role="button"], [role="link"], ' +
  '[role="checkbox"], [role="radio"], [role="tab"], [tabindex]:not([tabindex="-1"])'

const json = (data: unknown) => Promise.resolve({ ok: true, status: 200, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
  json: async () => structuredClone(data) })

const CONNECTED = { status: 'connected', pairingId: 'p1', deviceLabel: 'iPhone · Safari', pairCodeExpiresAt: null,
  claimedAt: null, expiresAt: null, reason: null }
const NONE = { status: 'none', pairingId: null, deviceLabel: null, pairCodeExpiresAt: null, claimedAt: null,
  expiresAt: null, reason: null }

const CARD = { id: 'order-1', number: '#1047', customer_name: 'Youssef Adel', payment_method: 'cod', cod_amount: '1250.00',
  tracking_number: '74821903', area: 'Nasr City', courierType: 'delivery', batchNo: 3, batchPrintedAt: '2026-10-05T07:00:00Z',
  items: [{ id: 'line-1', variant_id: 'v1', sku: 'CP-M', variant_title: 'M', product_title: 'Cargo pants', imageUrl: null,
    quantity: 2, allocated: 1, allocatedPieces: [{ piece_id: 'p1', barcode: 'P000001' }] }] }
const VIEW = { id: 'sess-1', mode: 'waybill_scan', status: 'open', startedAt: '2026-10-05T07:00:00Z', workerName: 'Ahmed',
  counters: { packed: 4, setAside: 1, rejected: 0, left: 7 }, recent: [], openOrder: CARD } as unknown as PackSessionView
const PICK = { id: 'order-1', number: '#1047', customer_name: 'Youssef Adel', customer_phone: null, status: 'new',
  payment_method: 'cod', cod_amount: '1250.00', locked_by: null, is_self_pickup: false, cancel_requested_at: null,
  shipment_id: 'sh-1', tracking_number: '74821903', shipment_has_courier: true, awbPrinted: false,
  items: [{ id: 'i1', variant_id: 'v1', sku: 'CP-M', variant_title: 'M', product_title: 'Cargo pants', quantity: 2, allocated: 2,
    allocatedPieces: [{ piece_id: 'p1', barcode: 'P000001', allocation_status: 'active' },
      { piece_id: 'p2', barcode: 'P000002', allocation_status: 'active' }] }] }
const STOCK_TAKE = { sessionId: 'st-1', status: 'open', scopeType: 'all', locationId: 'loc-1', completeCount: false,
  openedBy: 'u1', openedByName: 'Owner', openedAt: '2026-10-05T07:00:00Z', finalizedBy: null, finalizedByName: null,
  finalizedAt: null, note: null, shopifySync: null }

function backend(pairing: unknown) {
  stubFetchWithShellDefaults((url: string) => {
    if (url.includes('/station/pairings/current')) return json(pairing)
    if (url.endsWith('/pack-sessions/sess-1')) return json(VIEW)
    if (url.endsWith('/fulfill/queue')) return json([{ id: 'order-1', number: '#1047', customer_name: 'Youssef Adel',
      status: 'new', payment_method: 'cod', cod_amount: '1250.00', total_units: 2, scanned_units: 2, locked_by: null,
      locked_at: null, is_self_pickup: false, is_exchange: false }])
    if (url.endsWith('/fulfill/order-1')) return json(PICK)
    if (url.endsWith('/stock-takes/sessions/st-1')) return json(STOCK_TAKE)
    return json({})
  })
}

type Screen = 'pick' | 'pack' | 'stockTake'

async function open(screen: Screen) {
  if (screen === 'pack') {
    renderWithProviders(<PhoneScanProvider><PackSessionScreen initial={VIEW} onEnded={() => {}} /><PhoneControl /></PhoneScanProvider>)
    await expect.poll(() => document.querySelector('[data-testid="order-card"]')).toBeTruthy()
  } else if (screen === 'pick') {
    renderWithProviders(<PhoneScanProvider><Fulfill /><PhoneControl /></PhoneScanProvider>)
    await expect.poll(() => document.body.textContent?.includes('#1047')).toBe(true)
    ;[...document.querySelectorAll<HTMLElement>('*')].reverse().find(el => el.textContent === '#1047')!.click()
    await expect.poll(() => document.querySelector('[data-testid="fulfill-pick"]')).toBeTruthy()
  } else {
    renderWithProviders(<PhoneScanProvider><Routes><Route path="/stock-take/:id/scan" element={<StockTakeScan />} /></Routes>
      <PhoneControl /></PhoneScanProvider>, { initialEntries: ['/stock-take/st-1/scan'] })
    await expect.poll(() => document.querySelector('input')).toBeTruthy()
  }
  await expect.poll(() => document.querySelector('[data-testid="phone-control"]')?.getAttribute('data-mode')).not.toBe('measuring')
  await new Promise(r => setTimeout(r, 250))                    // a late layout change is re-placed (one frame)
}

/** Every actionable element the control's box intersects (none expected). */
function covered(): string[] {
  const control = document.querySelector<HTMLElement>('[data-testid="phone-control"]')!
  const c = control.getBoundingClientRect()
  expect(c.width).toBeGreaterThan(0)
  expect(c.left).toBeGreaterThanOrEqual(0)
  expect(c.top).toBeGreaterThanOrEqual(0)
  expect(c.right).toBeLessThanOrEqual(window.innerWidth)
  expect(c.bottom).toBeLessThanOrEqual(window.innerHeight)
  return [...document.querySelectorAll<HTMLElement>(ACTIONABLE)]
    .filter(el => !control.contains(el))
    .filter(el => {
      const r = el.getBoundingClientRect()
      return r.width > 0 && r.height > 0 && c.left < r.right && c.right > r.left && c.top < r.bottom && c.bottom > r.top
    })
    .map(el => `${el.tagName} "${(el.textContent || (el as HTMLInputElement).placeholder || '').trim().slice(0, 30)}"`)
}

const SIZES: Array<[number, number]> = [[1280, 800], [768, 1024], [390, 844]]

beforeEach(() => {
  vi.stubGlobal('AudioContext', undefined)
  setAccessToken('h.' + btoa(JSON.stringify({ sub: 'u1', tenant: 't1', role: 'owner' })).replace(/=+$/, '') + '.s')
})
afterEach(async () => {
  cleanup()
  clearAccessToken()
  vi.unstubAllGlobals()
  await page.viewport(1280, 800)
})

describe('the phone control covers no actionable control', () => {
  for (const screen of ['pick', 'pack', 'stockTake'] as Screen[]) {
    for (const [w, h] of SIZES) {
      for (const [state, pairing] of [['connected', CONNECTED], ['Use phone', NONE]] as const) {
        test(`${screen} at ${w}×${h}, ${state}`, async () => {
          await page.viewport(w, h)
          backend(pairing)
          await open(screen)
          expect(covered()).toEqual([])
        })
      }
    }
  }
})

function Crowded() {
  // Buttons tile the whole viewport except a 100×100 gap in the bottom-end corner.
  const cells: Array<{ left: number; top: number }> = []
  for (let top = 0; top < window.innerHeight; top += 44) {
    for (let left = 0; left < window.innerWidth; left += 84) {
      if (left + 80 > window.innerWidth - 100 && top + 40 > window.innerHeight - 100) continue
      cells.push({ left, top })
    }
  }
  return <>{cells.map((c, i) => (
    <button key={i} style={{ position: 'fixed', left: c.left, top: c.top, width: 80, height: 40 }}>b{i}</button>
  ))}</>
}

test('no room for the chip anywhere → it collapses to an icon button in the free gap; tapping it shows the status and Unpair', async () => {
  await page.viewport(768, 1024)
  backend(CONNECTED)
  renderWithProviders(<PhoneScanProvider><Crowded /><PhoneControl /></PhoneScanProvider>)
  await expect.poll(() => document.querySelector('[data-testid="phone-control"]')?.getAttribute('data-mode')).toBe('compact')
  expect(covered()).toEqual([])
  const icon = document.querySelector<HTMLButtonElement>('[data-testid="phone-compact"]')!
  expect(icon.getAttribute('aria-label')).toBe('Phone connected · iPhone · Safari')
  icon.click()
  await expect.poll(() => document.querySelector('[data-testid="phone-chip"]')?.textContent).toContain('Unpair')
})
