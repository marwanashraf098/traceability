import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { page } from 'vitest/browser'
import { cleanup } from '@testing-library/react'
import '../index.css'
import { renderWithProviders } from '../test/renderWithProviders'
import { stubFetchWithShellDefaults } from '../test/mockShellFetch'
import { setAccessToken, clearAccessToken } from '../auth'
import type { PackSessionView } from '../api'
import type { RelayHandlers } from '../phone/relayStream'
import { PhoneScanProvider } from '../phone/PhoneScanProvider'
import PhoneControl from '../phone/PhoneControl'
import Layout from '../components/Layout'
import PackSessionScreen from '../pages/fulfill/PackSessionScreen'
import Fulfill from '../pages/Fulfill'

// Phone as scanner, in a real browser with the app's CSS (Chromium + WebKit): the phone button is
// part of the scan screen's header (inside its box, in its flow) and overlaps no other control —
// pack session and PickScreen, unpaired and paired, at 1280×800, 768×1024 and 390×844 (an icon
// button below 640 px); Layout's top bar icon (paired) overlaps nothing either.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => 'owner') }
})
vi.mock('../phone/relayStream', () => ({ openRelayStream: (_d: string, _h: RelayHandlers) => () => {} }))

const ACTIONABLE = 'button, a[href], input:not([type="hidden"]), select, textarea, [role="button"], [role="link"], [tabindex]:not([tabindex="-1"])'

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
  shipment_id: 'sh-1', tracking_number: '74821903', shipment_has_courier: true, awbPrinted: false, is_exchange: true,
  items: [{ id: 'i1', variant_id: 'v1', sku: 'CP-M', variant_title: 'M', product_title: 'Cargo pants', quantity: 2, allocated: 2,
    allocatedPieces: [{ piece_id: 'p1', barcode: 'P000001', allocation_status: 'active' },
      { piece_id: 'p2', barcode: 'P000002', allocation_status: 'active' }] }] }

function backend(pairing: unknown) {
  stubFetchWithShellDefaults((url: string) => {
    if (url.includes('/station/pairings/current')) return json(pairing)
    if (url.endsWith('/station/pairings')) return json({ pairingId: 'p1', pairUrl: 'https://app.tracedtech.com/scan/AbC_123-xyz',
      pairCodeExpiresAt: new Date(Date.now() + 90_000).toISOString(), expiresAt: new Date(Date.now() + 3600_000).toISOString() })
    if (url.endsWith('/pack-sessions/sess-1')) return json(VIEW)
    if (url.endsWith('/fulfill/queue')) return json([{ id: 'order-1', number: '#1047', customer_name: 'Youssef Adel',
      status: 'new', payment_method: 'cod', cod_amount: '1250.00', total_units: 2, scanned_units: 2, locked_by: null,
      locked_at: null, is_self_pickup: false, is_exchange: true }])
    if (url.endsWith('/fulfill/order-1')) return json(PICK)
    return json({})
  }, { me: { name: 'Mona Abdelrahman', email: 'mona@shop.example', role: 'owner' } })
}

type Screen = 'pick' | 'pack' | 'topbar'

/** Renders the screen; returns its header box (the element the phone control must live in). */
async function open(screen: Screen): Promise<HTMLElement> {
  if (screen === 'pack') {
    renderWithProviders(<PhoneScanProvider><PackSessionScreen initial={VIEW} onEnded={() => {}} /><PhoneControl /></PhoneScanProvider>)
    await expect.poll(() => document.querySelector('[data-testid="order-card"]')).toBeTruthy()
    await expect.poll(() => document.querySelector('[data-testid="phone-button"]')).toBeTruthy()
    return document.querySelector('[data-testid="pack-session"] > div')!
  }
  if (screen === 'pick') {
    renderWithProviders(<PhoneScanProvider><Fulfill /><PhoneControl /></PhoneScanProvider>)
    await expect.poll(() => document.body.textContent?.includes('#1047')).toBe(true)
    ;[...document.querySelectorAll<HTMLElement>('*')].reverse().find(el => el.textContent === '#1047')!.click()
    await expect.poll(() => document.querySelector('[data-testid="fulfill-pick"] [data-testid="phone-button"]')).toBeTruthy()
    return document.querySelector('[data-testid="phone-button"]')!.parentElement!
  }
  renderWithProviders(<PhoneScanProvider><Layout><p>page</p></Layout><PhoneControl /></PhoneScanProvider>)
  await expect.poll(() => document.querySelector('[data-testid="phone-topbar"]')).toBeTruthy()
  return document.querySelector('[data-testid="phone-topbar"]')!.closest('header, div')!.parentElement!
}

function overlaps(control: HTMLElement): string[] {
  const c = control.getBoundingClientRect()
  return [...document.querySelectorAll<HTMLElement>(ACTIONABLE)]
    .filter(el => !control.contains(el) && !el.contains(control))
    .filter(el => {
      const r = el.getBoundingClientRect()
      return r.width > 0 && r.height > 0 && c.left < r.right && c.right > r.left && c.top < r.bottom && c.bottom > r.top
    })
    .map(el => `${el.tagName} "${(el.textContent || '').trim().slice(0, 30)}"`)
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

describe('the phone control is in the header flow and overlaps no other control', () => {
  for (const [w, h] of SIZES) {
    for (const screen of ['pick', 'pack'] as Screen[]) {
      for (const [state, pairing] of [['unpaired', NONE], ['paired', CONNECTED]] as const) {
        test(`${screen} header at ${w}×${h}, ${state}`, async () => {
          await page.viewport(w, h)
          backend(pairing)
          const header = await open(screen)
          const control = document.querySelector<HTMLElement>('[data-testid="phone-button"]')!
          expect(header.contains(control)).toBe(true)
          const c = control.getBoundingClientRect()
          const hd = header.getBoundingClientRect()
          expect(c.width).toBeGreaterThan(0)
          expect(c.top).toBeGreaterThanOrEqual(hd.top - 0.5)
          expect(c.bottom).toBeLessThanOrEqual(hd.bottom + 0.5)
          expect(c.left).toBeGreaterThanOrEqual(0)
          expect(c.right).toBeLessThanOrEqual(window.innerWidth + 0.5)
          expect(getComputedStyle(control).position).not.toBe('fixed')
          expect(overlaps(control)).toEqual([])
          if (w < 640) expect(control.textContent?.trim()).toBe('')          // icon only
          else expect(control.textContent).toMatch(state === 'paired' ? /Phone connected/ : /Use phone/)
        })
      }
    }
    test(`top bar icon at ${w}×${h}, paired`, async () => {
      await page.viewport(w, h)
      backend(CONNECTED)
      await open('topbar')
      const icon = document.querySelector<HTMLElement>('[data-testid="phone-topbar"]')!
      expect(icon.getBoundingClientRect().width).toBeGreaterThan(0)
      expect(overlaps(icon)).toEqual([])
    })
  }

  test('pack session: the header button opens the QR modal', async () => {
    backend(NONE)
    await open('pack')
    document.querySelector<HTMLElement>('[data-testid="phone-button"] button')!.click()
    await expect.poll(() => document.querySelector('[data-testid="pair-qr"]')).toBeTruthy()
  })
})
