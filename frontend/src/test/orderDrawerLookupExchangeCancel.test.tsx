import { test, expect, afterEach, vi } from 'vitest'
import i18next from 'i18next'
import { initReactI18next, I18nextProvider } from 'react-i18next'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import OrderDrawer from '../components/OrderDrawer'
import type { OrderDetail } from '../api'

// C2 — Orders' Cancel order on a Lookup exchange's replacement order: the server refuses with its own copy naming the
// request and pointing to Cancel exchange (LOOKUP_EXCHANGE_ORDER); the drawer shows that copy as sent, EN and AR.
// The bare-409 courier gate keeps its own message (orderDrawerCancelHoldGate.test.tsx c).

afterEach(() => vi.unstubAllGlobals())

const MESSAGE_EN = 'This is the replacement order of exchange RR-7K3F9M. Use Cancel exchange in that return request to '
  + 'cancel it — the piece goes back to available and the request is closed.'
const MESSAGE_AR = 'هذا طلب البديل للاستبدال RR-7K3F9M. استخدم إلغاء الاستبدال في طلب الإرجاع هذا لإلغائه — تعود القطعة '
  + 'متاحة ويُغلق الطلب.'

function json(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: status === 409 ? 'Conflict' : 'OK',
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
  })
}

const ORDER: OrderDetail = {
  id: 'order-1', number: 'EXC-RR-7K3F9M', customerName: 'Mona Adel', customerPhone: '01012345678',
  address: null, paymentMethod: 'cod', codAmount: 0, status: 'confirmed', onHold: false,
  holdReason: null, placedAt: '2026-10-28T10:42:00Z', createdAt: '2026-10-28T10:42:00Z',
  items: [{ id: 'item-1', productTitle: 'Linen Shirt', variantTitle: 'White / M', sku: 'LIN-WHT-M', quantity: 1, imageUrl: null, allocatedPieces: [] }],
  shipments: [],
  bostaLinkStatus: 'linked', notTracedAt: null, isExchange: true, shopifyOrderUrl: null,
  derivedStatus: {
    primaryKey: 'status.in_transit', tone: 'INFO', healthChips: [], historicalNote: null,
    conflictKey: null, notTraced: false, packedConfirmed: false,
    fulfillmentKey: 'status.fulfilled', fulfillmentTone: 'SUCCESS',
  },
  physicallyWithCourier: false,
} as OrderDetail

function renderDrawer(lang: 'en' | 'ar') {
  const cancelCalls: string[] = []
  vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
    if (url.includes('/cancel') && init?.method === 'POST') {
      cancelCalls.push(url)
      return json({ code: 'LOOKUP_EXCHANGE_ORDER', message_en: MESSAGE_EN, message_ar: MESSAGE_AR }, 409)
    }
    if (url.includes('/notes') || url.includes('/timeline')) return json([])
    if (url.includes('/orders/')) return json(ORDER)
    return json({})
  }))
  const i18n = i18next.createInstance()
  i18n.use(initReactI18next).init({
    lng: lang, fallbackLng: 'en', resources: { en: { translation: en }, ar: { translation: ar } },
    interpolation: { escapeValue: false },
  })
  render(
    <MemoryRouter>
      <I18nextProvider i18n={i18n}>
        <OrderDrawer orderId="order-1" onClose={() => {}} />
      </I18nextProvider>
    </MemoryRouter>,
  )
  return { cancelCalls, i18n }
}

test('oc1 — EN: the refusal names the request and points to Cancel exchange', async () => {
  const { cancelCalls } = renderDrawer('en')
  const user = userEvent.setup()
  await screen.findByText('EXC-RR-7K3F9M')
  await user.click(screen.getByRole('button', { name: 'Cancel order' }))
  const bodyText = await screen.findByText("Are you sure you want to cancel this order? This can't be undone.")
  const modal = bodyText.closest('.bg-surface') as HTMLElement
  await user.click(within(modal).getByRole('button', { name: 'Cancel order' }))
  await waitFor(() => expect(cancelCalls).toEqual(['/api/v1/fulfill/order-1/cancel']))
  expect(await screen.findByText(MESSAGE_EN)).toBeInTheDocument()
  expect(screen.queryByText(/contact Bosta/)).toBeNull()
})

test('oc2 — AR: the Arabic refusal', async () => {
  const { i18n } = renderDrawer('ar')
  const user = userEvent.setup()
  await screen.findByText('EXC-RR-7K3F9M')
  const cancel = i18n.t('orderDetail.cancel', { defaultValue: '' }) || 'إلغاء الطلب'
  await user.click(screen.getByRole('button', { name: cancel }))
  const confirm = await screen.findAllByRole('button', { name: cancel })
  await user.click(confirm[confirm.length - 1])
  expect(await screen.findByText(MESSAGE_AR)).toBeInTheDocument()
})
