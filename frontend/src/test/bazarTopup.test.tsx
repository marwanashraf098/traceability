import { describe, test, expect, beforeEach, afterEach, vi } from 'vitest'
import { screen, render, cleanup, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import BazarAddPieces from '../pages/bazar/BazarAddPieces'
import { LiveView } from '../pages/bazar/r3/LiveView'
import { BazarMenu } from '../pages/bazar/phone/BazarMenu'
import { StationProvider } from '../components/StationProvider'
import { ToastProvider } from '../components/ui'
import { clearAccessToken, setAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import { sellTiming } from '../bazar/useQuickSell'
import type { BazarEventDetail } from '../bazar/api'

// Bazar top-up (V245) — "Add more pieces" on a live bazar: the scan-out screen on the bazar's open batch (scan, count,
// ✕ change the LIST; "Send these to the bazar" sends them; Discard drops the list), the Live page's entry / open-batch
// banner / "N sizes have no price · Set prices" / "Sent in N batches", and the phone menu's entry for owner / manager.

vi.mock('../pages/scanpair/CameraReader', () => ({ default: () => null }))

const testI18n = i18next.createInstance()
testI18n.use(initReactI18next).init({
  lng: 'en', fallbackLng: 'en', initImmediate: false,
  resources: { en: { translation: en }, ar: { translation: ar } }, interpolation: { escapeValue: false },
})

const EVENT = 'e-1'
const TID = 't-1'
const BATCH = 'b-1'

function fakeJwt(role: 'owner' | 'manager' | 'worker', sub = `u-${role}`): string {
  return `h.${btoa(JSON.stringify({ role, sub }))}.s`
}

function json(status: number, body: unknown) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => body,
  } as unknown as Response)
}

type Handler = (url: string, init: RequestInit | undefined) => Promise<Response> | undefined
let calls: { url: string; method: string; body: unknown }[] = []

function install(...hs: Handler[]) {
  calls = []
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    const method = (init?.method ?? 'GET').toUpperCase()
    let body: unknown = undefined
    try { body = init?.body ? JSON.parse(String(init.body)) : undefined } catch { body = init?.body }
    calls.push({ url, method, body })
    for (const h of hs) {
      const r = h(url, init)
      if (r) return r
    }
    return json(404, {})
  }))
}

const on = (method: string, end: string, fn: (init: RequestInit | undefined) => Promise<Response>): Handler =>
  (url, init) => ((init?.method ?? 'GET').toUpperCase() === method && url.split('?')[0].endsWith(end) ? fn(init) : undefined)

const posts = (end: string) => calls.filter(c => c.method === 'POST' && c.url.endsWith(end))

function batch(lines: { variant_id: string; count: number; codes?: string[] }[] = []) {
  return {
    id: BATCH, batch_no: 2, status: 'open',
    lines: lines.map(l => ({ variant_id: l.variant_id, sku: 'SH-' + l.variant_id, variant_title: 'White / L',
      product_title: 'Linen shirt', count: l.count, codes: l.codes ?? [] })),
    total: lines.reduce((n, l) => n + l.count, 0),
  }
}

function transfer() {
  return {
    id: TID, transfer_type: 'bazar', transfer_mode: 'round_trip', status: 'sent', note: null,
    expected_return_at: null, created_by: 'u-owner', created_at: new Date().toISOString(), closed_by: null,
    closed_at: null, sent_at: new Date().toISOString(), sent_by: 'u-owner', cancelled_at: null, cancelled_by: null,
    reconcile_started_at: null, reconcile_started_by: null, destination_location_id: 'd-1',
    destination_location_name: 'Zamalek Hall', source_location_id: null, source_location_name: null,
    lines: [{ id: 'l-0', variant_id: 'v-first', sku: 'X', variant_title: 'Black / S', product_title: 'First send',
      qty_out: 7, qty_returned_good: 0, qty_condemned: 0, qty_sold: 0, qty_lost: 0 }],
  }
}

const STOCK = {
  event_id: EVENT, event_name: 'Zamalek Winter Bazar', event_status: 'live', categories: [],
  products: [{ number: null, product_id: 'p-shirt', title: 'Linen shirt', colour: 'White', image_url: null, category: null,
    left: 5, price_min: '960.00', price_max: '960.00', sizes: [
      { variant_id: 'v-l', title: 'White / L', size: 'L', price: '960.00', left: 5, left_unlabelled: 2, committed: 0 },
    ] }],
}

/** The add-pieces screen's reads; {@code current} is what GET …/batches/current answers (changes as the list does). */
function screenBase(state: { current: ReturnType<typeof batch> | null }, onlineStock: 'remove' | 'leave' = 'remove'): Handler[] {
  return [
    on('GET', '/api/v1/me', () => json(200, { name: 'X', email: 'x@y', role: 'owner', bazar: true })),
    on('GET', `/bazar-events/${EVENT}/send-stock`, () => json(200, STOCK)),
    on('GET', `/bazar-events/${EVENT}/batches/current`, () => json(200, { batch: state.current })),
    on('POST', `/bazar-events/${EVENT}/batches`, () => json(200, state.current ?? batch())),
    on('GET', `/bazar-events/${EVENT}`, () => json(200, { id: EVENT, transfer_id: TID, status: 'live', online_stock: onlineStock })),
    on('GET', `/transfers/${TID}`, () => json(200, transfer())),
  ]
}

function renderAt(path: string, routes: React.ReactNode) {
  return render(
    <StationProvider>
      <MemoryRouter initialEntries={[path]}>
        <I18nextProvider i18n={testI18n}>
          <ToastProvider>
            <Routes>{routes}</Routes>
          </ToastProvider>
        </I18nextProvider>
      </MemoryRouter>
    </StationProvider>,
  )
}

const routes = <>
  <Route path="/bazar/:id/add-pieces" element={<BazarAddPieces />} />
  <Route path="/bazar/:id" element={<p data-testid="event-page">event page</p>} />
</>

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  clearAccessToken()
  resetCapabilitiesCache()
  testI18n.changeLanguage('en')
  sellTiming.timeoutMs = 10_000
  sellTiming.retryDelaysMs = [5, 5, 5]
  setAccessToken(fakeJwt('owner'))
})

afterEach(() => {
  vi.unstubAllGlobals()
  cleanup()
})

describe('add pieces — the batch screen', () => {
  test('opens the batch, a scan lists the piece on the BATCH (never the first send), the lines are the batch\'s', async () => {
    const state = { current: batch() as ReturnType<typeof batch> | null }
    install(on('POST', `/batches/${BATCH}/scan`, () => {
      state.current = batch([{ variant_id: 'v-l', count: 1, codes: ['P0000001'] }])
      return json(200, { success: true, code: 'SCANNED', pieceId: 'pc-1', barcode: 'P0000001', variantId: 'v-l',
        lineId: null, qtyOut: 1 })
    }), ...screenBase(state))
    renderAt(`/bazar/${EVENT}/add-pieces`, routes)
    const user = userEvent.setup()
    expect(await screen.findByTestId('batch-title')).toHaveTextContent('Batch 2 · pieces to add')
    expect(posts(`/bazar-events/${EVENT}/batches`)).toHaveLength(1)
    expect(screen.queryByText(/First send/)).not.toBeInTheDocument()   // the transfer's lines are not this list
    expect(screen.getByRole('button', { name: 'Send these 0 to the bazar' })).toBeDisabled()

    await user.type(screen.getByPlaceholderText('Scan piece barcode…'), 'P0000001{Enter}')
    await waitFor(() => expect(posts(`/batches/${BATCH}/scan`)).toHaveLength(1))
    expect(posts('/scan-out')).toHaveLength(0)
    expect(await screen.findByText('Linen shirt · White / L')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Send this piece to the bazar' })).toBeEnabled()
    expect(screen.getByTestId('batch-online-note')).toHaveTextContent('this piece leaves online stock')
    expect(screen.getAllByRole('textbox')).toHaveLength(1)
  })

  test('Send: one request with a request id, then back to the event; Keep says online stock stays', async () => {
    const state = { current: batch([{ variant_id: 'v-l', count: 3 }]) as ReturnType<typeof batch> | null }
    install(on('POST', `/batches/${BATCH}/send`, () => json(200, { sent: 3, piece_ids: ['a', 'b', 'c'], replayed: false,
      event: { id: EVENT } })), ...screenBase(state, 'leave'))
    renderAt(`/bazar/${EVENT}/add-pieces`, routes)
    const user = userEvent.setup()
    expect(await screen.findByTestId('batch-online-note')).toHaveTextContent('Online stock stays as it is.')
    await user.click(screen.getByRole('button', { name: 'Send these 3 to the bazar' }))
    await screen.findByTestId('event-page')
    expect(posts(`/batches/${BATCH}/send`)).toHaveLength(1)
    expect(typeof (posts(`/batches/${BATCH}/send`)[0].body as Record<string, unknown>).client_request_id).toBe('string')
  })

  test('a listed piece that changed: nothing sent, the server names it, the screen stays', async () => {
    const state = { current: batch([{ variant_id: 'v-l', count: 2 }]) as ReturnType<typeof batch> | null }
    install(on('POST', `/batches/${BATCH}/send`, () => json(409, { code: 'BAZAR_BATCH_PIECES_CHANGED',
      message_en: "1 piece on this batch isn't available any more — nothing was sent. Remove it and send again: P0000009",
      message_ar: 'x', details: { blocked_codes: ['P0000009'] } })), ...screenBase(state))
    renderAt(`/bazar/${EVENT}/add-pieces`, routes)
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Send these 2 to the bazar' }))
    expect(await screen.findByTestId('batch-send-error')).toHaveTextContent('P0000009')
    expect(screen.queryByTestId('event-page')).not.toBeInTheDocument()
  })

  test('by count adds to the list ("Add N"); fewer without labels → the server\'s "N added · M have labels"', async () => {
    const state = { current: batch() as ReturnType<typeof batch> | null }
    install(on('POST', `/batches/${BATCH}/count`, () => {
      state.current = batch([{ variant_id: 'v-l', count: 2 }])
      return json(200, { success: true, code: 'SENT_PARTIAL', variantId: 'v-l', count: 2, pieceIds: ['a', 'b'],
        qtyOut: 2, replayed: false, requested: 2, labelled_at_main: 3,
        message_en: '2 added · 3 have labels — scan them', message_ar: 'x' })
    }), ...screenBase(state))
    renderAt(`/bazar/${EVENT}/add-pieces`, routes)
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Choose product and size' }))
    const sheet = await screen.findByTestId('bazar-pick-sheet')
    await user.click(within(sheet).getAllByTestId('pick-product')[0].querySelector('button')!)
    await user.click(within(sheet).getAllByTestId('pick-size')[0])
    await user.click(within(sheet).getByTestId('pick-sell'))
    expect(screen.getByTestId('send-by-count-numbers')).toHaveTextContent('2 without labels · 3 with labels')
    await user.click(screen.getByTestId('send-by-count-more'))
    await user.click(screen.getByRole('button', { name: 'Add 2' }))
    await waitFor(() => expect(posts(`/batches/${BATCH}/count`)).toHaveLength(1))
    expect(posts('/scan-out-by-count')).toHaveLength(0)
    expect(await screen.findByTestId('send-by-count-partial')).toHaveTextContent('2 added · 3 have labels — scan them')
    expect(screen.getByTestId('send-by-count-done')).toHaveTextContent('2 added to the list')
  })

  test('✕ and Remove N take pieces off the LIST (the batch endpoint)', async () => {
    const state = { current: batch([{ variant_id: 'v-l', count: 2 }]) as ReturnType<typeof batch> | null }
    install(on('POST', `/batches/${BATCH}/remove`, () => {
      state.current = batch([{ variant_id: 'v-l', count: 1 }])
      return json(200, { success: true, code: 'SENT', variantId: 'v-l', count: 1, pieceIds: ['a'], qtyOut: 1, replayed: false })
    }), ...screenBase(state))
    renderAt(`/bazar/${EVENT}/add-pieces`, routes)
    const user = userEvent.setup()
    const row = (await screen.findByText('Linen shirt · White / L')).closest('tr')!
    await user.click(within(row).getByRole('button', { name: 'Remove' }))
    await user.click(screen.getByRole('button', { name: 'Remove 1' }))
    await waitFor(() => expect(posts(`/batches/${BATCH}/remove`)).toHaveLength(1))
    expect(posts(`/batches/${BATCH}/remove`)[0].body).toMatchObject({ variant_id: 'v-l', quantity: 1 })
    expect(posts('/remove-from-send')).toHaveLength(0)
  })

  test('Discard needs a second tap, then goes back; nothing is sent', async () => {
    const state = { current: batch([{ variant_id: 'v-l', count: 1 }]) as ReturnType<typeof batch> | null }
    install(on('POST', `/batches/${BATCH}/discard`, () => json(200, { id: EVENT })), ...screenBase(state))
    renderAt(`/bazar/${EVENT}/add-pieces`, routes)
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Discard this list' }))
    expect(posts(`/batches/${BATCH}/discard`)).toHaveLength(0)
    await user.click(screen.getByRole('button', { name: 'Discard? Tap again' }))
    await screen.findByTestId('event-page')
    expect(posts(`/batches/${BATCH}/discard`)).toHaveLength(1)
    expect(posts(`/batches/${BATCH}/send`)).toHaveLength(0)
  })

  test('an ended bazar: the server\'s refusal is shown, no screen', async () => {
    install(on('POST', `/bazar-events/${EVENT}/batches`, () => json(409, { code: 'BAZAR_EVENT_ENDED',
      message_en: 'This bazar has ended.', message_ar: 'انتهى هذا البازار.' })), ...screenBase({ current: null }))
    renderAt(`/bazar/${EVENT}/add-pieces`, routes)
    expect(await screen.findByRole('alert')).toHaveTextContent('This bazar has ended.')
    expect(screen.queryByTestId('transfer-scan-out')).not.toBeInTheDocument()
  })
})

// ── Live page / phone menu ─────────────────────────────────────────────────

function liveEvent(over: Partial<BazarEventDetail> = {}): BazarEventDetail {
  return {
    id: EVENT, name: 'Zamalek Winter Bazar', starts_on: '2026-11-26', ends_on: '2026-11-28', status: 'live',
    location_id: 'd-1', location_name: 'Zamalek Hall', transfer_id: TID, transfer_status: 'sent',
    transfer_sent_at: '2026-11-26T08:00:00Z', transfer_shopify_sync_mode: 'remove', online_stock: 'remove',
    payment_methods: ['cash'], seller_roles: ['owner'], discount_pct: null, round_to: null,
    started_at: '2026-11-26T09:00:00Z', ended_at: null,
    counts: { sent: 12, at_bazar: 10, sold: 2, back: 0, lost: 0 },
    today: { business_day: '2026-11-27', sales: 0, revenue: '0', by_method: {} },
    batches: [{ batch_no: 1, sent_at: '2026-11-26T08:00:00Z', pieces: 9 },
              { batch_no: 2, sent_at: '2026-11-27T12:30:00Z', pieces: 3 }],
    open_batch: null, no_price_count: 0, ...over,
  } as unknown as BazarEventDetail
}

function renderLive(ev: BazarEventDetail, path = `/bazar/${EVENT}`) {
  install(on('GET', '/api/v1/me', () => json(200, { name: 'X', email: 'x@y', role: 'owner', bazar: true })))
  return renderAt(path, <>
    <Route path="/bazar/:id" element={<LiveView ev={ev} reload={async () => {}} />} />
    <Route path="/bazar/:id/add-pieces" element={<p data-testid="add-pieces-page">add</p>} />
  </>)
}

describe('Live page', () => {
  test('More ▾ → "Add more pieces" opens the batch screen', async () => {
    renderLive(liveEvent())
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: /More/ }))
    await user.click(screen.getByRole('menuitem', { name: 'Add more pieces' }))
    await screen.findByTestId('add-pieces-page')
  })

  test('an open batch: the menu says Continue, a banner links to it', async () => {
    renderLive(liveEvent({ open_batch: { id: BATCH, batch_no: 3, pieces: 4 } }))
    const user = userEvent.setup()
    expect(await screen.findByTestId('bazar-open-batch')).toHaveTextContent("You're adding 4 pieces to this bazar — not sent yet.")
    await user.click(screen.getByRole('button', { name: /More/ }))
    expect(screen.getByRole('menuitem', { name: 'Continue adding pieces (4 listed)' })).toBeInTheDocument()
  })

  test('"N sizes have no price · Set prices" opens the Prices tab; none → no warning', async () => {
    renderLive(liveEvent({ no_price_count: 2 }))
    expect(await screen.findByTestId('bazar-no-price')).toHaveTextContent('2 sizes have no price')
    cleanup()
    renderLive(liveEvent({ no_price_count: 0 }))
    await screen.findByTestId('bazar-live')
    expect(screen.queryByTestId('bazar-no-price')).not.toBeInTheDocument()
  })

  test('Pieces tab: "Sent in 2 batches" with each batch; one send → no card', async () => {
    renderLive(liveEvent(), `/bazar/${EVENT}?tab=pieces`)
    const card = await screen.findByTestId('bazar-batches')
    expect(card).toHaveTextContent('Sent in 2 batches')
    expect(card).toHaveTextContent('Batch 1')
    expect(card).toHaveTextContent('9 pieces')
    expect(card).toHaveTextContent('3 pieces')
    cleanup()
    renderLive(liveEvent({ batches: [{ batch_no: 1, sent_at: '2026-11-26T08:00:00Z', pieces: 12 }] }), `/bazar/${EVENT}?tab=pieces`)
    await screen.findByTestId('bazar-live')
    expect(screen.queryByTestId('bazar-batches')).not.toBeInTheDocument()
  })
})

describe('phone menu', () => {
  function renderMenu() {
    install(on('GET', '/api/v1/me', () => json(200, { name: 'X', email: 'x@y', role: 'owner', bazar: true })))
    return renderAt(`/bazar/${EVENT}/sell`, <>
      <Route path="/bazar/:id/sell" element={<BazarMenu eventId={EVENT} />} />
      <Route path="/bazar/:id/add-pieces" element={<p data-testid="add-pieces-page">add</p>} />
    </>)
  }

  test('owner / manager: "Add more pieces" opens the batch screen', async () => {
    setAccessToken(fakeJwt('manager'))
    renderMenu()
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('bazar-menu'))
    await user.click(screen.getByTestId('menu-add-pieces'))
    await screen.findByTestId('add-pieces-page')
  })

  test('a worker has no "Add more pieces"', async () => {
    setAccessToken(fakeJwt('worker'))
    renderMenu()
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('bazar-menu'))
    expect(screen.queryByTestId('menu-add-pieces')).not.toBeInTheDocument()
  })
})
