import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { screen, within } from '@testing-library/react'
import { renderWithProviders, waitFor } from './renderWithProviders'
import FeeBreakdown from '../pages/exchangesRefunds/FeeBreakdown'
import { CloseDialog } from '../pages/exchangesRefunds/RequestLifecycle'
import type { RequestAmounts, ReturnRequestDetail } from '../api'

// Returns portal fees F2 — the drawer's frozen fee breakdown.
//   refund   — item value − fee = refund due; waive / change before approval (the exact PUT bodies)
//   locked   — after approval (canOverride false): no buttons
//   exchange — the fee as "collect" instruction, no refund line
//   absent   — no amounts → nothing
//   close    — a nothing-owed refund offers "Nothing owed" (selected) and sends it

const AMOUNTS: RequestAmounts = {
  kind: 'refund', currency: 'EGP', itemValue: '900.00', approximate: false, fee: '75.00', feeWaived: false,
  waiveReason: null, calculatedFee: '75.00', mode: 'fixed', percent: null, areaSource: 'rule', refundDue: '825.00',
  collect: '0.00', nothingOwed: false, overriddenAt: null, overriddenByName: null, canOverride: true,
}

function detail(amounts?: RequestAmounts): ReturnRequestDetail {
  return { id: 'rr-1', reference: 'RR-ABCD', type: amounts?.kind ?? 'refund', status: 'requested', items: [], amounts } as unknown as ReturnRequestDetail
}

let calls: Array<{ method: string; url: string; body?: unknown }>

beforeEach(() => {
  calls = []
  vi.stubGlobal('fetch', vi.fn((url: string, opts: RequestInit = {}) => {
    calls.push({ method: (opts.method ?? 'GET').toUpperCase(), url, body: opts.body ? JSON.parse(opts.body as string) : undefined })
    return Promise.resolve({ ok: true, status: 204, statusText: '', headers: { get: () => null },
      json: async () => ({}), text: async () => '' })
  }))
})

afterEach(() => { vi.unstubAllGlobals() })

const writes = () => calls.filter(c => c.method !== 'GET')

describe('fee breakdown', () => {
  test('refund: value − fee = due; waive and change send the exact bodies', async () => {
    const user = userEvent.setup()
    const onChanged = vi.fn(async () => {})
    renderWithProviders(<FeeBreakdown detail={detail(AMOUNTS)} onChanged={onChanged} />)
    const box = screen.getByTestId('fee-breakdown')
    expect(within(box).getByTestId('fee-item-value')).toHaveTextContent('900')
    expect(within(box).getByTestId('fee-amount')).toHaveTextContent('75')
    expect(within(box).getByTestId('fee-due')).toHaveTextContent('825')

    await user.click(within(box).getByRole('button', { name: 'Waive fee' }))
    await waitFor(() => expect(writes()).toHaveLength(1))
    expect(writes()[0]).toMatchObject({ method: 'PUT', url: expect.stringContaining('/return-requests/rr-1/amounts'), body: { waived: true } })
    await waitFor(() => expect(onChanged).toHaveBeenCalled())

    await user.click(within(box).getByRole('button', { name: 'Change fee' }))
    const input = within(box).getByLabelText('New fee (EGP, whole number)')
    await user.clear(input)
    await user.type(input, '12.5')
    await user.click(within(box).getByRole('button', { name: 'Save' }))
    expect(within(box).getByRole('alert')).toHaveTextContent('Enter a whole amount from 0 to 30,000.')
    await user.clear(input)
    await user.type(input, '40')
    await user.click(within(box).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(writes()).toHaveLength(2))
    expect(writes()[1].body).toEqual({ fee: 40 })
  })

  test('locked after approval: no buttons', () => {
    renderWithProviders(<FeeBreakdown detail={detail({ ...AMOUNTS, canOverride: false })} onChanged={async () => {}} />)
    expect(screen.queryByRole('button')).toBeNull()
  })

  test('exchange: the fee is an instruction to collect', () => {
    renderWithProviders(<FeeBreakdown detail={detail({ ...AMOUNTS, kind: 'exchange', fee: '40.00', collect: '40.00',
      refundDue: null, canOverride: false })} onChanged={async () => {}} />)
    expect(screen.getByTestId('fee-collect')).toHaveTextContent("The customer owes")
    expect(screen.getByTestId('fee-collect')).toHaveTextContent('40')
    expect(screen.queryByTestId('fee-due')).toBeNull()
  })

  test('absent: no amounts, nothing rendered', () => {
    renderWithProviders(<FeeBreakdown detail={detail()} onChanged={async () => {}} />)
    expect(screen.queryByTestId('fee-breakdown')).toBeNull()
  })

  test('close: a nothing-owed refund closes as "Nothing owed"', async () => {
    const user = userEvent.setup()
    const d = { ...detail({ ...AMOUNTS, fee: '1000.00', refundDue: '0.00', nothingOwed: true }), status: 'refund_pending' } as ReturnRequestDetail
    renderWithProviders(<CloseDialog detail={d} onClose={() => {}} onDone={async () => {}} />)
    expect(screen.getByRole('radio', { name: /Nothing owed/ })).toBeChecked()
    await user.click(screen.getByRole('button', { name: /Close request|Close/ }))
    await waitFor(() => expect(writes()).toHaveLength(1))
    expect(writes()[0].body).toMatchObject({ reason: 'nothing_owed' })
  })

  test('close: without amounts there is no "Nothing owed" choice', () => {
    renderWithProviders(<CloseDialog detail={{ ...detail(), status: 'refund_pending' } as ReturnRequestDetail} onClose={() => {}} onDone={async () => {}} />)
    expect(screen.queryByRole('radio', { name: /Nothing owed/ })).toBeNull()
  })
})
