import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import { LeftoversPanel } from '../pages/bazar/Leftovers'
import { resetCapabilitiesCache } from '../capabilities'
import type { BazarLeftovers } from '../bazar/api'
import { clearAccessToken, setAccessToken } from '../auth'
import { sellTiming } from '../bazar/useQuickSell'

// Bazar POS B7d-2 — the leftovers panel (phone /end and the computer's /end-event both render it; `wide` on the
// computer): "Bring back by count" for leftovers without a barcode (the B7a picker in back mode, − / +, one request
// with a request id), shown only while an untagged piece is still out; "Bring home" on a damaged leftover without a
// barcode (a labelled one is scanned).

vi.mock('../pages/scanpair/CameraReader', () => ({ default: () => null }))

const testI18n = i18next.createInstance()
testI18n.use(initReactI18next).init({
  lng: 'en', fallbackLng: 'en', initImmediate: false,
  resources: { en: { translation: en } }, interpolation: { escapeValue: false },
})

const EVENT = 'e-1'

function json(status: number, body: unknown) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => body,
  } as unknown as Response)
}

type Handler = { method: string; path: string; fn: () => Promise<Response> }
let calls: { url: string; method: string; body: unknown }[] = []

function install(...hs: Handler[]) {
  calls = []
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    const method = (init?.method ?? 'GET').toUpperCase()
    let body: unknown = undefined
    try { body = init?.body ? JSON.parse(String(init.body)) : undefined } catch { body = init?.body }
    calls.push({ url, method, body })
    const path = url.replace(/^https?:\/\/[^/]+/, '').split('?')[0].replace(/^\/api\/v1/, '')
    for (const h of hs) if (h.method === method && h.path === path) return h.fn()
    return json(404, {})
  }))
}
const on = (method: string, path: string, fn: () => Promise<Response>): Handler => ({ method, path, fn })
const posts = (end: string) => calls.filter(c => c.method === 'POST' && c.url.endsWith(end))

function wrap(node: React.ReactNode) {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <I18nextProvider i18n={testI18n}><Routes><Route path="*" element={node} /></Routes></I18nextProvider>
    </MemoryRouter>,
  )
}

const piece = (id: string, code: string | null, title: string, variant: string | null = null) =>
  ({ piece_id: id, piece_code: code, product_title: title, variant_title: variant, sku: code ? null : 'SH-M' })

function leftovers(over: Partial<BazarLeftovers> = {}): BazarLeftovers {
  return {
    event_id: EVENT, event_status: 'ended', transfer_id: 't-12345678', transfer_status: 'reconciling',
    counts: { sent: 10, sold: 5, back: 0, damaged: 1, damaged_at_bazar: 1, lost: 0, outstanding: 4 },
    balanced: false,
    outstanding: [piece('p-a', null, 'Linen shirt', 'White / M'), piece('p-b', null, 'Linen shirt', 'White / M'),
                  piece('p-c', null, 'Linen shirt', 'White / M'), piece('p-l', 'P000250', 'Knit scarf')],
    damaged_to_bring_home: [piece('p-d', null, 'Wool cardigan', 'Beige / M')], ...over,
  }
}

const STOCK = {
  event_id: EVENT, event_name: 'Zamalek Winter Bazar', event_status: 'ended', categories: [],
  products: [{ number: 12, product_id: 'p-shirt', title: 'Linen shirt', colour: 'White', image_url: null, category: null,
    left: 3, price_min: '960.00', price_max: '960.00', sizes: [
      { variant_id: 'v-m', title: 'White / M', size: 'M', price: '960.00', left: 3 }] }],
}

const STOCK_GET = on('GET', `/bazar-events/${EVENT}/bring-back-stock`, () => json(200, STOCK))

async function chooseM(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByTestId('bring-back-choose'))
  const sheet = await screen.findByTestId('bazar-pick-sheet')
  expect(sheet).toHaveTextContent('Bring back from the bazar')
  await user.click(within(sheet).getAllByTestId('pick-product')[0].querySelector('button')!)
  expect(within(sheet).getAllByTestId('pick-size')[0]).toHaveTextContent('3 without labels still out')
  await user.click(within(sheet).getAllByTestId('pick-size')[0])
  await user.click(within(sheet).getByTestId('pick-sell'))
}

beforeEach(() => {
  clearAccessToken()
  resetCapabilitiesCache()
  setAccessToken(`h.${btoa(JSON.stringify({ role: 'worker', sub: 'u-worker' }))}.s`)
  sellTiming.timeoutMs = 10_000
  sellTiming.retryDelaysMs = [5, 5, 5]
})
afterEach(() => { vi.unstubAllGlobals(); cleanup() })

describe('bring back by count', () => {
  for (const wide of [false, true]) {
    test(`${wide ? 'computer /end-event' : 'phone /end'}: pick the size, how many, bring them back — one request`, async () => {
      const after = leftovers({
        counts: { sent: 10, sold: 5, back: 2, damaged: 1, damaged_at_bazar: 1, lost: 0, outstanding: 2 },
        outstanding: [piece('p-c', null, 'Linen shirt', 'White / M'), piece('p-l', 'P000250', 'Knit scarf')],
      })
      install(STOCK_GET, on('POST', `/bazar-events/${EVENT}/bring-back-by-count`, () => json(200, {
        result: 'back_good', count: 2, piece_ids: ['p-a', 'p-b'], variant_id: 'v-m', leftovers: after, replayed: false })))
      wrap(<LeftoversPanel eventId={EVENT} initial={leftovers()} onClosed={() => {}} noCamera wide={wide} />)
      const user = userEvent.setup()
      await chooseM(user)
      expect(await screen.findByTestId('bring-back-size')).toHaveTextContent('Linen shirt · White / M')
      await user.click(screen.getByTestId('bring-back-more'))
      expect(screen.getByTestId('bring-back-qty')).toHaveTextContent('2')
      await user.click(screen.getByTestId('bring-back-go'))
      await waitFor(() => expect(posts('/bring-back-by-count')).toHaveLength(1))
      const body = posts('/bring-back-by-count')[0].body as Record<string, unknown>
      expect(body).toMatchObject({ variant_id: 'v-m', quantity: 2 })
      expect(typeof body.client_request_id).toBe('string')
      expect(await screen.findByTestId('bring-back-done')).toHaveTextContent('2 pieces back in stock.')
      expect(screen.getByTestId('count-back')).toHaveTextContent('2')
      expect(screen.getByTestId('count-out')).toHaveTextContent('2')
    })
  }

  test('never more than are still out', async () => {
    install(STOCK_GET)
    wrap(<LeftoversPanel eventId={EVENT} initial={leftovers()} onClosed={() => {}} noCamera />)
    const user = userEvent.setup()
    await chooseM(user)
    await user.click(await screen.findByTestId('bring-back-more'))
    await user.click(screen.getByTestId('bring-back-more'))
    expect(screen.getByTestId('bring-back-qty')).toHaveTextContent('3')
    expect(screen.getByTestId('bring-back-more')).toBeDisabled()
  })

  test('the server refuses (labelled ones must be scanned): its reason is shown', async () => {
    install(STOCK_GET, on('POST', `/bazar-events/${EVENT}/bring-back-by-count`, () => json(409, {
      code: 'BAZAR_NOT_ENOUGH_UNTAGGED', message_en: 'These pieces have a barcode — scan them back one by one.',
      message_ar: '…', details: { untagged: 0, labelled: 2 } })))
    wrap(<LeftoversPanel eventId={EVENT} initial={leftovers()} onClosed={() => {}} noCamera />)
    const user = userEvent.setup()
    await chooseM(user)
    await user.click(await screen.findByTestId('bring-back-go'))
    expect(await screen.findByTestId('bring-back-error')).toHaveTextContent('scan them back one by one')
  })

  test('not offered when every leftover has a barcode, or once the transfer is closed', async () => {
    install(STOCK_GET)
    const { unmount } = wrap(<LeftoversPanel eventId={EVENT} onClosed={() => {}} noCamera
      initial={leftovers({ outstanding: [piece('p-l', 'P000250', 'Knit scarf')] })} />)
    await screen.findByTestId('leftovers-panel')
    expect(screen.queryByTestId('bring-back-by-count')).not.toBeInTheDocument()
    unmount()
    wrap(<LeftoversPanel eventId={EVENT} onClosed={() => {}} noCamera initial={leftovers({ transfer_status: 'closed' })} />)
    await screen.findByTestId('leftovers-panel')
    expect(screen.queryByTestId('bring-back-by-count')).not.toBeInTheDocument()
  })
})

describe('damaged without a barcode', () => {
  test('tap "Bring home" on its row: one request with the piece id; a labelled damaged one has no button', async () => {
    const after = leftovers({ damaged_to_bring_home: [piece('p-x', 'P000777', 'Silk scarf')],
      counts: { sent: 10, sold: 5, back: 0, damaged: 2, damaged_at_bazar: 1, lost: 0, outstanding: 4 } })
    install(STOCK_GET, on('POST', `/bazar-events/${EVENT}/bring-home-damaged`, () => json(200, {
      result: 'back_damaged', piece: piece('p-d', null, 'Wool cardigan', 'Beige / M'), leftovers: after, replayed: false })))
    wrap(<LeftoversPanel eventId={EVENT} onClosed={() => {}} noCamera wide
      initial={leftovers({ damaged_to_bring_home: [piece('p-d', null, 'Wool cardigan', 'Beige / M'),
                                                    piece('p-x', 'P000777', 'Silk scarf')] })} />)
    const user = userEvent.setup()
    const box = await screen.findByTestId('leftovers-damaged')
    expect(within(box).queryByTestId('bring-home-p-x')).not.toBeInTheDocument()
    await user.click(within(box).getByTestId('bring-home-p-d'))
    await waitFor(() => expect(posts('/bring-home-damaged')).toHaveLength(1))
    const body = posts('/bring-home-damaged')[0].body as Record<string, unknown>
    expect(body).toMatchObject({ piece_id: 'p-d' })
    expect(typeof body.client_request_id).toBe('string')
    expect(await screen.findByTestId('scan-back-back_damaged')).toHaveTextContent('Wool cardigan')
    await waitFor(() => expect(screen.queryByTestId('bring-home-p-d')).not.toBeInTheDocument())
  })
})
