import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { Route, Routes, useLocation } from 'react-router-dom'
import i18n from '../i18n'
import * as api from '../api'
import { setAccessToken } from '../auth'
import { resetCapabilitiesCache } from '../capabilities'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import FulfillRoute from '../pages/fulfill/FulfillRoute'
import LookupPage from '../pages/Lookup'
import ExceptionsPage from '../pages/Exceptions'

// Unpack-order S4 — the Unpack button (owner / manager only) on the packed list, the confirm dialog (reason, "Other"
// note, Bosta COD reminder, the server's EN / AR refusal with the pickup-session link), the alert's deep link
// (?order=&unpack=1 opens the dialog), the reason in piece history, and the edit-conflict alert's "Unpack order".

function json(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
  })
}

const ROW = {
  orderId: 'o1', orderNumber: '#1047', customerName: 'Youssef Adel', status: 'awaiting_pickup', selfPickup: false,
  trackingNumber: '74821655', courier: 'bosta', carrierName: null, packedAt: '2026-10-31T09:12:00Z',
  packedByName: 'Ahmed', pieces: 2, pickupSessionId: null, pickupDate: null, pickupTimeSlot: null,
  editConflict: true, codMismatch: true, bostaCod: 1250, orderCod: 900,
}

let calls: Array<{ method: string; url: string; body: unknown }>
let packed: unknown[]
let unpackAnswer: { status: number; body: unknown }

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  calls.push({ method, url, body: opts.body ? JSON.parse(String(opts.body)) : null })
  if (url.endsWith('/fulfill/mode')) return json({ mode: 'waybill_scan' })
  if (url.endsWith('/fulfill/queue')) return json([])
  if (url.endsWith('/pack-sessions/summary')) return json({ packedToday: 0, openSessionId: null })
  if (url.endsWith('/fulfill/print-batches/today')) return json([])
  if (url.endsWith('/fulfill/printed-not-packed')) return json([])
  if (url.endsWith('/fulfill/packed')) return json(packed)
  if (url.endsWith('/fulfill/o1/unpack-order') && method === 'POST') {
    if (unpackAnswer.status === 200) packed = []
    return json(unpackAnswer.body, unpackAnswer.status)
  }
  return json({})
}

function role(r: 'owner' | 'manager' | 'worker') {
  setAccessToken(`h.${btoa(JSON.stringify({ role: r, exp: Math.floor(Date.now() / 1000) + 3600 }))}.s`)
}

function Where() { const l = useLocation(); return <p data-testid="where">{l.pathname + l.search}</p> }

function renderFulfill(path = '/fulfill') {
  return renderWithProviders(
    <I18nextProvider i18n={i18n}>
      <Routes>
        <Route path="/fulfill" element={<FulfillRoute />} />
        <Route path="/pickups" element={<p>pickups</p>} />
      </Routes>
      <Where />
    </I18nextProvider>,
    { initialEntries: [path] },
  )
}

const posts = () => calls.filter(c => c.method === 'POST' && c.url.endsWith('/unpack-order'))

beforeEach(async () => {
  vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
  await i18n.changeLanguage('en')
  resetCapabilitiesCache()
  role('manager')
  calls = []
  packed = [ROW]
  unpackAnswer = { status: 200, body: { orderId: 'o1', orderNumber: '#1047', pieces: 2, previousStatus: 'awaiting_pickup',
    status: 'new', selfPickup: false } }
  stubFetchWithShellDefaults(vi.fn(backend))
})
afterEach(async () => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  await i18n.changeLanguage('en')
})

describe('unpack an order', () => {
  test('a worker sees the packed list but no Unpack button', async () => {
    role('worker')
    renderFulfill()
    await screen.findAllByTestId('packed-row')
    expect(screen.queryByRole('button', { name: 'Unpack' })).toBeNull()
  })

  test('manager: confirm with the default reason → POST, success notice, list reloads', async () => {
    const user = userEvent.setup()
    renderFulfill()
    await user.click(await screen.findByRole('button', { name: 'Unpack' }))
    const dialog = screen.getByTestId('unpack-dialog')
    expect(within(dialog).getByText(/All 2 packed pieces go back to stock/)).toBeInTheDocument()
    expect(within(dialog).getByText(/the same waybill opens the order again/)).toBeInTheDocument()
    expect(within(dialog).getByTestId('unpack-cod'))
      .toHaveTextContent('Bosta still has 1,250 EGP, the order is now 900 EGP — update the COD in Bosta.')
    expect(within(dialog).getByLabelText('Customer edited the order')).toBeChecked()

    await user.click(within(dialog).getByRole('button', { name: 'Unpack order' }))
    await waitFor(() => expect(posts()).toHaveLength(1))
    expect(posts()[0].body).toEqual({ reason: 'customer_edited_order' })
    expect(await screen.findByText('Order #1047 unpacked — pack it again from Pick & Pack.')).toBeInTheDocument()
    expect(screen.queryByTestId('unpack-dialog')).toBeNull()
    await waitFor(() => expect(screen.getByTestId('packed-empty')).toBeInTheDocument())
  })

  test('"Other" needs a note; it is sent with the reason', async () => {
    const user = userEvent.setup()
    renderFulfill()
    await user.click(await screen.findByRole('button', { name: 'Unpack' }))
    const dialog = screen.getByTestId('unpack-dialog')
    await user.click(within(dialog).getByLabelText('Other'))
    const confirm = within(dialog).getByRole('button', { name: 'Unpack order' })
    expect(confirm).toBeDisabled()
    await user.type(within(dialog).getByTestId('unpack-note'), 'Wrong size sent')
    expect(within(dialog).getByText('15/200')).toBeInTheDocument()
    await user.click(confirm)
    await waitFor(() => expect(posts()).toHaveLength(1))
    expect(posts()[0].body).toEqual({ reason: 'other', note: 'Wrong size sent' })
  })

  test('a refusal shows the server text; an open pickup session links to it', async () => {
    unpackAnswer = { status: 409, body: { code: 'IN_PICKUP_SESSION',
      message_en: 'Order #1047 is in an open pickup session (2026-10-31). Remove its waybill from that pickup session first, then unpack it.',
      message_ar: 'الطلب #1047 في جلسة استلام مفتوحة (2026-10-31). أزل بوليصته من جلسة الاستلام أولاً، ثم فُكّه.',
      details: { pickupSessionId: 'pu-9', pickupDate: '2026-10-31', pickupTimeSlot: null } } }
    const user = userEvent.setup()
    renderFulfill()
    await user.click(await screen.findByRole('button', { name: 'Unpack' }))
    await user.click(within(screen.getByTestId('unpack-dialog')).getByRole('button', { name: 'Unpack order' }))
    const error = await screen.findByTestId('unpack-error')
    expect(error).toHaveTextContent('Remove its waybill from that pickup session first')
    expect(within(error).getByTestId('unpack-open-pickup')).toHaveAttribute('href', '/pickups?session=pu-9')
    expect(screen.getByTestId('unpack-dialog')).toBeInTheDocument()
    expect(screen.getAllByTestId('packed-row')).toHaveLength(1)
  })

  test('the alert deep link (?order=&unpack=1) opens that order\'s dialog for a manager', async () => {
    renderFulfill('/fulfill?tab=packed&order=o1&unpack=1')
    const dialog = await screen.findByTestId('unpack-dialog')
    expect(within(dialog.parentElement!.parentElement!).getByText('Unpack order #1047?')).toBeInTheDocument()
  })

  test('the alert deep link does not open the dialog for a worker', async () => {
    role('worker')
    renderFulfill('/fulfill?tab=packed&order=o1&unpack=1')
    await screen.findAllByTestId('packed-row')
    expect(screen.queryByTestId('unpack-dialog')).toBeNull()
  })

  test('Arabic', async () => {
    await i18n.changeLanguage('ar')
    const user = userEvent.setup()
    renderFulfill()
    await user.click(await screen.findByRole('button', { name: 'فكّ التغليف' }))
    const dialog = screen.getByTestId('unpack-dialog')
    expect(within(dialog).getByLabelText('العميل عدّل الطلب')).toBeChecked()
    expect(within(dialog).getByRole('button', { name: 'فكّ تغليف الطلب' })).toBeInTheDocument()
    expect(within(dialog).getByTestId('unpack-cod')).toHaveTextContent('حدّث مبلغ التحصيل في Bosta')
  })
})

test('piece history: "Unpacked from order … to pack again — <reason>"', async () => {
  role('owner')
  vi.spyOn(api, 'lookup').mockResolvedValue({
    type: 'piece', id: 'piece-1', barcode: 'PC-1', status: 'available', receivedAt: new Date().toISOString(),
    variant: { id: 'v1', productTitle: 'Linen Shirt', title: 'S', sku: 'SHIRT-S' },
    currentLocation: { id: 'l', name: 'Main' }, currentOrder: null, currentShipment: null, receivingSession: null,
    timeline: [
      { id: 2, eventType: 'unpacked', phraseKey: 'unpacked_order', actor: 'Mona', isSystem: false,
        fromStatus: 'awaiting_pickup', toStatus: 'available', orderNumber: '#1047', orderId: 'o1', trackingNumber: null,
        locationName: null, metadata: null, occurredAt: new Date().toISOString(), unpackReason: 'other', unpackNote: 'Wrong size sent' },
      { id: 1, eventType: 'unpacked', phraseKey: 'unpacked_order', actor: 'Mona', isSystem: false,
        fromStatus: 'packed', toStatus: 'available', orderNumber: '#1047', orderId: 'o1', trackingNumber: null,
        locationName: null, metadata: null, occurredAt: new Date().toISOString(), unpackReason: 'customer_edited_order', unpackNote: null },
    ],
  } as api.PieceLookupResult)
  renderWithProviders(<I18nextProvider i18n={i18n}><LookupPage /></I18nextProvider>)
  await userEvent.type(screen.getByRole('textbox'), 'P000101{Enter}')
  expect(await screen.findByText('Unpacked from order #1047 to pack again — Wrong size sent')).toBeInTheDocument()
  expect(screen.getByText('Unpacked from order #1047 to pack again — Customer edited the order')).toBeInTheDocument()
})

test('the edit-conflict alert offers "Unpack order" when the server sends unpackUrl', async () => {
  role('manager')
  stubFetchWithShellDefaults(vi.fn((url: string) => {
    if (url.includes('/exceptions?') || url.endsWith('/exceptions')) {
      return json({ total: 2, page: 0, size: 50, counts: { critical: 0, high: 2, medium: 0, low: 0 }, items: [
        { type: 'shopify_edit_conflict', severity: 'HIGH', subject_type: 'order', subject_key: 'shopify_edit_conflict:order:o1',
          order_id: 'o1', order_number: '#1047', occurred_at: '2026-10-31T08:00:00Z', ageSeconds: 600,
          descriptionEn: 'Shopify edited order #1047 (awaiting_pickup) after it entered the fulfillment flow — line items changed',
          descriptionAr: 'x', suggestedAction: 'review_order', actionUrl: '/orders?order=o1',
          unpackUrl: '/fulfill?tab=packed&order=o1&unpack=1' },
        { type: 'shopify_edit_conflict', severity: 'HIGH', subject_type: 'order', subject_key: 'shopify_edit_conflict:order:o2',
          order_id: 'o2', order_number: '#1050', occurred_at: '2026-10-31T08:00:00Z', ageSeconds: 600,
          descriptionEn: 'Shopify edited order #1050 (new) after it entered the fulfillment flow — line items changed',
          descriptionAr: 'x', suggestedAction: 'review_order', actionUrl: '/orders?order=o2' },
      ] })
    }
    return json({})
  }))
  const user = userEvent.setup()
  renderWithProviders(
    <I18nextProvider i18n={i18n}>
      <Routes>
        <Route path="/exceptions" element={<ExceptionsPage />} />
        <Route path="/fulfill" element={<p>fulfill</p>} />
      </Routes>
      <Where />
    </I18nextProvider>,
    { initialEntries: ['/exceptions'] },
  )
  const buttons = await screen.findAllByRole('button', { name: 'Unpack order' })
  expect(buttons).toHaveLength(1)
  await user.click(buttons[0])
  expect(screen.getByTestId('where').textContent).toBe('/fulfill?tab=packed&order=o1&unpack=1')
})

// Decision 2 (pin): an unpacked self-pickup order is back at status 'new' with is_self_pickup kept — the waybill page
// counts it under "Self-pickup orders", never in the waybill queue (Ready to pack).
test('an unpacked self-pickup order shows under self-pickup, not in the waybill queue', async () => {
  role('manager')
  stubFetchWithShellDefaults(vi.fn((url: string, opts: RequestInit = {}) => {
    if (url.endsWith('/fulfill/queue')) {
      return json([{ id: 'o4', number: '#1058', customer_name: 'Nour', customer_phone: null, status: 'new',
        payment_method: 'cod', cod_amount: 100, placed_at: '2026-10-31T08:00:00Z', locked_by: null, locked_at: null,
        is_self_pickup: true, is_exchange: false, total_units: 1, scanned_units: 0, awb_printed: false }])
    }
    return backend(url, opts)
  }))
  renderFulfill()
  const tiles = await screen.findByTestId('waybill-tiles')
  expect(within(tiles).getByText('Ready to pack').parentElement).toHaveTextContent('0')
  expect(screen.getByTestId('self-pickup-entry')).toHaveTextContent('Self-pickup orders (1)')
})
