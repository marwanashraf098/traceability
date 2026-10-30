import { test, expect, vi, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import { initReactI18next, I18nextProvider } from 'react-i18next'
import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import OrderDrawer from '../components/OrderDrawer'
import type { OrderDetail } from '../api'

// Returned pieces (2026-10-30): a piece that came back stays on its order's line, labelled
// "Returned · pending inspection / restocked / damaged". "No pieces allocated yet" is only for a line that
// never had a piece. Red on main: a restocked piece is absent from the DTO there, and the drawer shows the
// piece's current status ("Available") — not its return.

function json(data: unknown) {
  return Promise.resolve({
    ok: true, status: 200, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
  })
}

afterEach(() => { vi.unstubAllGlobals() })

const ORDER = {
  id: 'order-1', number: '#16520', customerName: 'Mona Said', customerPhone: '01022334455',
  address: null, paymentMethod: 'cod', codAmount: 540, status: 'awaiting_pickup', onHold: false,
  holdReason: null, placedAt: '2026-10-20T13:51:58Z', createdAt: '2026-10-20T13:52:05Z',
  items: [
    { id: 'item-shirt', productTitle: 'Linen Shirt', variantTitle: 'White / M', sku: 'LIN-WHT-M', quantity: 3,
      imageUrl: null, allocatedPieces: [
        { pieceId: 'p-kept', barcode: 'P104233', status: 'delivered' },
        { pieceId: 'p-restocked', barcode: 'P104234', status: 'available', returnState: 'restocked' },
        { pieceId: 'p-damaged', barcode: 'P104235', status: 'damaged', returnState: 'damaged' },
      ] },
    { id: 'item-belt', productTitle: 'Leather Belt', variantTitle: 'Brown', sku: 'BLT-BRN', quantity: 1,
      imageUrl: null, allocatedPieces: [
        { pieceId: 'p-back', barcode: 'P104236', status: 'return_pending_inspection', returnState: 'pending_inspection' },
      ] },
    { id: 'item-cap', productTitle: 'Cotton Cap', variantTitle: 'Navy', sku: 'CAP-NVY', quantity: 1,
      imageUrl: null, allocatedPieces: [] },
  ],
  shipments: [],
  bostaLinkStatus: null, notTracedAt: null, isExchange: false, shopifyOrderUrl: null,
  derivedStatus: {
    primaryKey: 'status.delivered', tone: 'SUCCESS', healthChips: [], historicalNote: null,
    conflictKey: null, notTraced: false, packedConfirmed: true,
    fulfillmentKey: 'status.fulfilled', fulfillmentTone: 'SUCCESS',
  },
  physicallyWithCourier: false,
} as unknown as OrderDetail

async function openItems(lang: 'en' | 'ar') {
  const testI18n = i18next.createInstance()
  testI18n.use(initReactI18next).init({
    lng: lang, fallbackLng: 'en',
    resources: { en: { translation: en }, ar: { translation: ar } },
    interpolation: { escapeValue: false },
  })
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    if (url.includes('/notes') || url.includes('/timeline')) return json([])
    if (url.includes('/orders/')) return json(ORDER)
    return json({})
  }))
  render(
    <MemoryRouter>
      <I18nextProvider i18n={testI18n}>
        <OrderDrawer orderId="order-1" onClose={() => {}} />
      </I18nextProvider>
    </MemoryRouter>,
  )
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: lang === 'en' ? 'Items (3)' : 'المنتجات (3)' }))
}

const chip = (barcode: string) => screen.getByText(barcode).parentElement!

test('returned pieces stay on their line with their return state; the others keep their status', async () => {
  await openItems('en')
  expect(within(chip('P104234')).getByText('Returned · restocked')).toBeInTheDocument()
  expect(within(chip('P104234')).queryByText('Available')).not.toBeInTheDocument()
  expect(within(chip('P104235')).getByText('Returned · damaged')).toBeInTheDocument()
  expect(within(chip('P104236')).getByText('Returned · pending inspection')).toBeInTheDocument()
  expect(within(chip('P104233')).getByText('Delivered')).toBeInTheDocument()
  // Only the line that never had a piece says so.
  expect(screen.getAllByText('No pieces allocated yet')).toHaveLength(1)
  expect(within(screen.getByText('Cotton Cap').closest('li')!).getByText('No pieces allocated yet')).toBeInTheDocument()
})

test('Arabic', async () => {
  await openItems('ar')
  expect(within(chip('P104234')).getByText('مرتجع · أُعيد للمخزون')).toBeInTheDocument()
  expect(within(chip('P104235')).getByText('مرتجع · تالف')).toBeInTheDocument()
  expect(within(chip('P104236')).getByText('مرتجع · ينتظر الفحص')).toBeInTheDocument()
  expect(screen.getAllByText('لا توجد قطع مخصصة بعد')).toHaveLength(1)
})
