import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithProviders, waitFor } from './renderWithProviders'
import FeeBreakdown from '../pages/exchangesRefunds/FeeBreakdown'
import OwedPayment from '../pages/exchangesRefunds/OwedPayment'
import type { RequestAmounts, ReturnRequestDetail } from '../api'

// Returns portal fees F3 — the drawer for an exchange priced by its difference.
//   collect — paid vs new price, the difference, "The customer owes … collect it yourself"
//   owed    — "Owed to the customer"; once the old item is back, the payment form, prefilled with what is owed
//   early   — not back yet (approved): no payment form

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

describe('exchange pricing in the drawer', () => {
  test('collect: the difference and the instruction to collect it', () => {
    renderWithProviders(<FeeBreakdown detail={detail(AMOUNTS)} onChanged={async () => {}} />)
    expect(screen.getByText('Exchange price')).toBeInTheDocument()
    expect(screen.getByTestId('fee-paid-price')).toHaveTextContent('900')
    expect(screen.getByTestId('fee-new-price')).toHaveTextContent('1,500')
    expect(screen.getByTestId('fee-difference')).toHaveTextContent('+')
    expect(screen.getByTestId('fee-collect')).toHaveTextContent('The customer owes')
    expect(screen.getByTestId('fee-collect')).toHaveTextContent('600')
    expect(screen.queryByTestId('fee-owed')).toBeNull()
  })

  test('owed: shown, and once back the payment form is prefilled with what is owed', async () => {
    const owed = { ...AMOUNTS, replacementPrice: '700.00', priceDifference: '-200.00', collect: '0.00', customerOwed: '200.00' }
    renderWithProviders(<FeeBreakdown detail={detail(owed, 'exchanged')} onChanged={async () => {}} />)
    expect(screen.getByTestId('fee-difference')).toHaveTextContent('−')
    expect(screen.getByTestId('fee-owed')).toHaveTextContent('Owed to the customer')
    expect(screen.queryByTestId('fee-collect')).toBeNull()

    renderWithProviders(<OwedPayment detail={detail(owed, 'exchanged')} onChanged={async () => {}} />)
    expect(await screen.findByTestId('refund-form')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByTestId('refund-suggestion')).toHaveTextContent('200'))
  })

  test('early: not back yet → no payment form', () => {
    const owed = { ...AMOUNTS, priceDifference: '-200.00', collect: '0.00', customerOwed: '200.00' }
    renderWithProviders(<OwedPayment detail={detail(owed, 'approved')} onChanged={async () => {}} />)
    expect(screen.queryByTestId('owed-payment')).toBeNull()
  })
})
