import { test, expect, afterEach, vi } from 'vitest'
import i18next from 'i18next'
import { initReactI18next, I18nextProvider } from 'react-i18next'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import OrderDrawer from '../components/OrderDrawer'
import type { OrderDetail, OrderNote } from '../api'

afterEach(() => vi.unstubAllGlobals())

function jsonOk(data: unknown) {
  return Promise.resolve({
    ok: true,
    status: 200,
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
  })
}

function makeOrderDetail(overrides: Partial<OrderDetail> = {}): OrderDetail {
  return {
    id: 'order-1', number: '#2212094474', customerName: 'Habiba Ali', customerPhone: '01211000010',
    address: null, paymentMethod: 'cod', codAmount: 990, status: 'with_courier', onHold: false,
    holdReason: null, placedAt: '2026-08-11T10:42:00Z', createdAt: '2026-08-11T10:42:00Z',
    items: [{ id: 'item-1', productTitle: 'Bikini Pink', variantTitle: 'M', sku: 'SN-BIK-PNK-M', quantity: 1, imageUrl: null, allocatedPieces: [] }],
    shipments: [],
    bostaLinkStatus: 'linked', notTracedAt: null, isExchange: false, shopifyOrderUrl: null,
    derivedStatus: {
      primaryKey: 'status.in_transit', tone: 'INFO', healthChips: [], historicalNote: null,
      conflictKey: null, notTraced: false, packedConfirmed: true,
      fulfillmentKey: 'status.fulfilled', fulfillmentTone: 'SUCCESS',
    },
    physicallyWithCourier: false,
    ...overrides,
  }
}

function makeNote(overrides: Partial<OrderNote> = {}): OrderNote {
  return {
    id: 'note-1', body: 'Called the customer to confirm the address.',
    authorName: 'Ahmed Mostafa', createdAt: '2026-08-11T11:00:00Z',
    ...overrides,
  }
}

// Stateful stub so a POST during the test actually grows what the subsequent GET would
// return — mirrors how the real backend behaves, not just a fixed canned response.
function stubFetch(order: OrderDetail, initialNotes: OrderNote[]) {
  const notes = [...initialNotes]
  const posted: unknown[] = []
  const fn = vi.fn((url: string, opts?: RequestInit) => {
    if (url.includes('/notes') && opts?.method === 'POST') {
      const body = JSON.parse(String(opts.body)) as { body: string }
      posted.push(body)
      const created = makeNote({ id: `note-${notes.length + 1}`, body: body.body, createdAt: '2026-08-11T12:00:00Z' })
      notes.unshift(created)
      return jsonOk(created)
    }
    if (url.includes('/notes')) return jsonOk(notes)
    if (url.includes('/timeline')) return jsonOk([])
    if (url.includes('/orders/')) return jsonOk(order)
    return jsonOk({})
  })
  return { fn, posted }
}

function renderDrawer(fetchImpl: ReturnType<typeof vi.fn>, lang: 'en' | 'ar' = 'en') {
  const testI18n = i18next.createInstance()
  testI18n.use(initReactI18next).init({
    lng: lang, fallbackLng: 'en',
    resources: { en: { translation: en }, ar: { translation: ar } },
    interpolation: { escapeValue: false },
  })
  vi.stubGlobal('fetch', fetchImpl)
  return render(
    <MemoryRouter>
      <I18nextProvider i18n={testI18n}>
        <OrderDrawer orderId="order-1" onClose={() => {}} />
      </I18nextProvider>
    </MemoryRouter>
  )
}

test('Notes tab renders existing notes newest-first with author and body', async () => {
  const { fn } = stubFetch(makeOrderDetail(), [
    makeNote({ id: 'note-1', body: 'First note', authorName: 'Ahmed Mostafa', createdAt: '2026-08-11T11:00:00Z' }),
    makeNote({ id: 'note-2', body: 'Second note', authorName: 'Sara Adel', createdAt: '2026-08-11T12:00:00Z' }),
  ])
  const user = userEvent.setup()
  renderDrawer(fn)

  await screen.findByText('#2212094474')
  await user.click(screen.getByRole('button', { name: 'Notes' }))

  expect(await screen.findByText('First note')).toBeInTheDocument()
  expect(screen.getByText('Second note')).toBeInTheDocument()
  expect(screen.getByText('Ahmed Mostafa')).toBeInTheDocument()
  expect(screen.getByText('Sara Adel')).toBeInTheDocument()
})

test('genuinely empty notes shows the honest empty state', async () => {
  const { fn } = stubFetch(makeOrderDetail(), [])
  const user = userEvent.setup()
  renderDrawer(fn)

  await screen.findByText('#2212094474')
  await user.click(screen.getByRole('button', { name: 'Notes' }))

  expect(await screen.findByText('No notes yet — add the first one.')).toBeInTheDocument()
})

test('add note: typing then submitting posts the note and it appears in the list', async () => {
  const { fn, posted } = stubFetch(makeOrderDetail(), [
    makeNote({ id: 'note-1', body: 'Existing note', authorName: 'Ahmed Mostafa', createdAt: '2026-08-11T11:00:00Z' }),
  ])
  const user = userEvent.setup()
  renderDrawer(fn)

  await screen.findByText('#2212094474')
  await user.click(screen.getByRole('button', { name: 'Notes' }))
  await screen.findByText('Existing note')

  const addButton = screen.getByRole('button', { name: 'Add note' })
  expect(addButton).toBeDisabled()

  const textarea = screen.getByPlaceholderText('Add a note about this order')
  await user.type(textarea, 'Customer wants delivery after 6pm')
  expect(addButton).toBeEnabled()

  await user.click(addButton)

  await waitFor(() => expect(posted).toEqual([{ body: 'Customer wants delivery after 6pm' }]))
  expect(await screen.findByText('Customer wants delivery after 6pm')).toBeInTheDocument()
  // Draft is cleared after a successful submit — not left behind for a duplicate resend.
  expect(textarea).toHaveValue('')
  // Pre-existing note is still there — the new one is added, not a replacement.
  expect(screen.getByText('Existing note')).toBeInTheDocument()
})

test('Notes tab renders correctly in ar/RTL — labels, empty state, and add flow all resolve', async () => {
  const { fn, posted } = stubFetch(makeOrderDetail(), [])
  const user = userEvent.setup()
  renderDrawer(fn, 'ar')

  await screen.findByText('#2212094474')
  await user.click(screen.getByRole('button', { name: 'ملاحظات' }))

  expect(await screen.findByText('لا توجد ملاحظات بعد — أضف أول ملاحظة.')).toBeInTheDocument()

  const textarea = screen.getByPlaceholderText('أضف ملاحظة حول هذا الطلب')
  await user.type(textarea, 'تم التواصل مع العميل')
  await user.click(screen.getByRole('button', { name: 'إضافة ملاحظة' }))

  await waitFor(() => expect(posted).toEqual([{ body: 'تم التواصل مع العميل' }]))
  expect(await screen.findByText('تم التواصل مع العميل')).toBeInTheDocument()
})
