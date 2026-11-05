import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import { I18nextProvider } from 'react-i18next'
import { renderWithProviders, screen, waitFor, fireEvent } from './renderWithProviders'
import i18n from '../i18n'
import { ReturnReceiveBanner, ReturnedLines } from '../pages/receiving/ReturnReceiveBanner'
import { withReturnSource } from '../receivingReturn'
import type { Line } from '../pages/Receiving'

/**
 * S1b — Receiving a returned unit from a Return To Receive alert.
 *   rl1  the alert's link carries the return (?return=<key>); no key → the plain URL (unchanged)
 *   rl2  banner: the order, the item, the "Shopify already restocked" warning; Add posts a line LINKED to the return
 *   rl3  nothing restocked in Shopify → no warning
 *   rl4  outside a session: no Add button, tells the manager to open a session
 *   rl5  returned lines: order + "already restocked — not sent again"; Remove deletes the line
 *   rl6  Arabic
 */

const KEY = 'untracked_unit:11111111-2222-3333-4444-555555555555'
const PREVIEW = (already: number) => ({
  source: KEY, orderNumber: '#1006', unitLevel: true,
  items: [{ variant_id: 'v1', product_title: 'Linen Shirt', variant_title: 'M', sku: 'LS-M', alreadyInShopify: already }],
})

let calls: { url: string; init?: RequestInit }[]
function stubFetch(preview: unknown) {
  calls = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    calls.push({ url: String(url), init })
    if (String(url).includes('/receiving/return-source')) {
      return new Response(JSON.stringify(preview), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }
    if (init?.method === 'POST') {
      return new Response(JSON.stringify({ lineId: 'l1' }), { status: 201, headers: { 'Content-Type': 'application/json' } })
    }
    return new Response(null, { status: 204 })
  }))
}

describe('Receiving — returned unit link', () => {
  beforeEach(() => { vi.clearAllMocks() })
  afterEach(async () => { vi.unstubAllGlobals(); await i18n.changeLanguage('en') })

  test('rl1 — the alert link carries the return; no key, URL unchanged', () => {
    expect(withReturnSource('/receiving', KEY)).toBe(`/receiving?return=${encodeURIComponent(KEY)}`)
    expect(withReturnSource('/receiving', undefined)).toBe('/receiving')
    expect(withReturnSource('/receiving?x=1', 'shipment:a')).toBe('/receiving?x=1&return=shipment%3Aa')
  })

  test('rl2 — warning shown; Add posts a line linked to the return', async () => {
    stubFetch(PREVIEW(1))
    const onAdded = vi.fn()
    renderWithProviders(<ReturnReceiveBanner returnKey={KEY} sessionId="s1" canAdd linkedLines={[]}
      onAdded={onAdded} onDismiss={() => {}} />)
    expect(await screen.findByText('Receiving a return of order #1006')).toBeInTheDocument()
    expect(screen.getByTestId('return-already-in-shopify')).toHaveTextContent(
      "Shopify already restocked 1 unit of this item for order #1006. It won't be added to Shopify again")
    fireEvent.click(screen.getByRole('button', { name: 'Add 1 to this session' }))
    await waitFor(() => expect(onAdded).toHaveBeenCalled())
    const post = calls.find(c => c.init?.method === 'POST')!
    expect(post.url).toContain('/receiving/sessions/s1/lines')
    expect(JSON.parse(String(post.init!.body))).toEqual({ variantId: 'v1', quantity: 1, returnSource: KEY })
  })

  test('rl3 — nothing restocked in Shopify, no warning', async () => {
    stubFetch(PREVIEW(0))
    renderWithProviders(<ReturnReceiveBanner returnKey={KEY} sessionId="s1" canAdd linkedLines={[]}
      onAdded={() => {}} onDismiss={() => {}} />)
    expect(await screen.findByText('Linen Shirt')).toBeInTheDocument()
    expect(screen.queryByTestId('return-already-in-shopify')).toBeNull()
  })

  test('rl4 — outside a session: no Add, asks to open a session', async () => {
    stubFetch(PREVIEW(1))
    renderWithProviders(<ReturnReceiveBanner returnKey={KEY} canAdd={false} linkedLines={[]}
      onAdded={() => {}} onDismiss={() => {}} />)
    expect(await screen.findByText(/Open or start a Receiving session/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Add 1 to this session' })).toBeNull()
  })

  test('rl5 — returned lines show the order and the skipped units; Remove deletes', async () => {
    stubFetch(PREVIEW(0))
    const onChanged = vi.fn()
    const line: Line = { id: 'l9', variant_id: 'v1', variant_title: 'M', sku: 'LS-M', product_title: 'Linen Shirt',
      quantity: 1, piece_count: 1, return_source: KEY, return_order_number: '#1006', shopify_restocked_units: 1 }
    renderWithProviders(<ReturnedLines lines={[line]} sessionId="s1" canEdit onChanged={onChanged} />)
    expect(screen.getByTestId('returned-lines')).toHaveTextContent('Return of order #1006 · 1 unit')
    expect(screen.getByTestId('returned-lines')).toHaveTextContent('1 already restocked in Shopify — not sent again')
    fireEvent.click(screen.getByRole('button', { name: 'Remove' }))
    await waitFor(() => expect(onChanged).toHaveBeenCalled())
    expect(calls.some(c => c.init?.method === 'DELETE' && c.url.includes('/receiving/sessions/s1/lines/l9'))).toBe(true)
  })

  test('rl6 — Arabic', async () => {
    await i18n.changeLanguage('ar')
    stubFetch(PREVIEW(1))
    renderWithProviders(
      <I18nextProvider i18n={i18n}>
        <ReturnReceiveBanner returnKey={KEY} sessionId="s1" canAdd linkedLines={[]} onAdded={() => {}} onDismiss={() => {}} />
      </I18nextProvider>)
    expect(await screen.findByText('استلام مرتجع للطلب #1006')).toBeInTheDocument()
    expect(screen.getByTestId('return-already-in-shopify')).toHaveTextContent('أعاد Shopify بالفعل قطعة واحدة')
  })
})
