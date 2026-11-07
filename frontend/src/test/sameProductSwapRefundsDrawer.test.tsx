import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithProviders, waitFor } from './renderWithProviders'
import FeeBreakdown from '../pages/exchangesRefunds/FeeBreakdown'
import OwedPayment from '../pages/exchangesRefunds/OwedPayment'
import type { RequestAmounts, ReturnRequestDetail } from '../api'

// V223 — the drawer for a cheaper same-product swap the store doesn't refund (frozen difference 0): a note says so;
// none for a real 0 (same price) or a different-product difference. Harness as exchangePricingDrawer.test.tsx.

const AMOUNTS: RequestAmounts = {
  kind: 'exchange', currency: 'EGP', itemValue: '900.00', approximate: false, fee: '0.00', feeWaived: false, waiveReason: null,
  calculatedFee: '0.00', mode: null, percent: null, areaSource: null, refundDue: null, collect: '600.00', nothingOwed: false,
  overriddenAt: null, overriddenByName: null, canOverride: false,
  paidPrice: '900.00', replacementPrice: '1500.00', priceDifference: '600.00', customerOwed: '0.00',
}

function detail(amounts: RequestAmounts, status = 'approved'): ReturnRequestDetail {
  return { id: 'rr-9', reference: 'RR-EXCH', type: 'exchange', status, items: [], refunds: [], amounts, refundMethod: 'cash' } as unknown as ReturnRequestDetail
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    const body = url.includes('/refund-suggestion')
      ? { amount: '200.00', currency: 'EGP', source: 'frozen', approximate: false, customerOwed: '200.00', lines: [] }
      : {}
    return Promise.resolve({ ok: true, status: 200, statusText: '', headers: { get: () => 'application/json' },
      json: async () => body, text: async () => JSON.stringify(body) })
  }))
})

afterEach(() => { vi.unstubAllGlobals() })

describe('same-product swap refunds in the drawer', () => {
  const FLOORED: RequestAmounts = { ...AMOUNTS, itemValue: '720.00', fee: '10.00', calculatedFee: '10.00', mode: 'fixed',
    paidPrice: '720.00', replacementPrice: '550.00', priceDifference: '0.00', collect: '10.00', customerOwed: '0.00' }

  test('floored: the note is shown with the fee to collect', () => {
    renderWithProviders(<FeeBreakdown detail={detail(FLOORED)} onChanged={async () => {}} />)
    expect(screen.getByTestId('fee-difference-floored')).toHaveTextContent("Cheaper same-product swaps aren't refunded")
    expect(screen.getByTestId('fee-collect')).toHaveTextContent('10')
  })

  test('no note for a real 0 or a non-zero difference', () => {
    renderWithProviders(<FeeBreakdown detail={detail({ ...FLOORED, replacementPrice: '720.00' })} onChanged={async () => {}} />)
    expect(screen.queryByTestId('fee-difference-floored')).toBeNull()
  })
})
