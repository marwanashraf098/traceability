import { test, expect, describe, vi, beforeEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen } from './renderWithProviders'
import * as api from '../api'
import type { ReturnRequestDetail } from '../api'
import ExchangeProgressView from '../pages/exchangesRefunds/ExchangeProgressView'

// Review mode S2 — "Book now" on a simulated-courier tenant: the backend answers 409
// REVIEW_MODE_UNAVAILABLE and the merchant sees "Not available in review mode" (not the generic
// "action failed").

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, bookExchangeNow: vi.fn() }
})

function detail(): ReturnRequestDetail {
  return {
    id: 'rr-1', reference: 'RR-8J4M2Q', orderId: 'order-1', orderNumber: '#1047', customerName: 'Mariam Saleh',
    customerPhone: '01012345678', type: 'exchange', status: 'approved', email: null, note: null,
    createdAt: '2026-09-26T12:00:00Z', deliveredAt: '2026-09-18T10:00:00Z', pickupCity: 'Cairo', pickupZone: 'Nasr City',
    pickupCityId: null, pickupCityName: null, pickupDistrictId: null, pickupDistrictName: null, pickupDistrictNameAr: null,
    bookingStatus: null, bostaTrackingNumber: null, bookingError: null,
    decidedAt: '2026-09-26T13:01:00Z', decidedBy: 'u1', decidedByName: 'Mohamed A.', rejectionReason: null, returnShipmentId: null,
    refundFallbackOk: true,
    history: [],
    items: [{ id: 'item-1', pieceId: 'piece-1', shortCode: 'P000245', variantId: 'v-1', productTitle: 'Linen Shirt',
      variantTitle: 'White · M', imageUrl: null, reasonCode: 'wrong_size', active: true, itemStatus: 'awaiting',
      replacementVariantId: 'v-2', replacementVariantTitle: 'White · L' }],
    exchange: null,
    bookNowAvailable: true,
  } as ReturnRequestDetail
}

describe('Book now — review mode', () => {
  beforeEach(() => vi.clearAllMocks())

  test('a REVIEW_MODE_UNAVAILABLE refusal shows "Not available in review mode"', async () => {
    vi.mocked(api.bookExchangeNow).mockRejectedValue(new api.TransferCommandError({
      code: 'REVIEW_MODE_UNAVAILABLE',
      message_en: 'Not available in review mode.',
      message_ar: 'غير متاح في وضع المراجعة.',
    }))
    const user = userEvent.setup()
    renderWithProviders(<ExchangeProgressView detail={detail()} onReload={async () => {}} />)

    await user.click(await screen.findByRole('button', { name: 'Book now' }))

    expect(api.bookExchangeNow).toHaveBeenCalledWith('rr-1')
    expect(await screen.findByText('Not available in review mode.')).toBeInTheDocument()
  })
})
