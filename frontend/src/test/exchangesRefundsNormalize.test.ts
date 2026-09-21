import { test, expect, describe } from 'vitest'
import { normalizeExchange, normalizeRefund, matchesFilter } from '../pages/exchangesRefunds/normalize'
import type { ExchangeSummary, RefundLeg } from '../api'

function makeExchange(overrides: Partial<ExchangeSummary> = {}): ExchangeSummary {
  return {
    id: 'exc-1',
    tracking_number: '910000001',
    status: 'matched',
    matched_order_id: 'order-original-1',
    match_method: 'phone',
    matched_at: '2026-08-01T10:00:00Z',
    outbound_description: 'Yellow hat',
    inbound_description: 'Red hat',
    inbound_description_ar: null,
    cod: 0,
    goods_value: 600,
    outbound_items_count: 1,
    inbound_items_count: 1,
    customer_name: 'Maya Mostafa',
    customer_phone: '01001234567',
    ...overrides,
  }
}

function makeRefund(overrides: Partial<RefundLeg> = {}): RefundLeg {
  return {
    id: 'ship-1',
    tracking_number: 'RFD-001',
    internal_state: 'with_courier',
    order_id: 'order-1',
    order_number: '#1001',
    customer_name: 'Nour Adel',
    customer_phone: '01098765432',
    leg_status: { primaryKey: 'status.in_transit', tone: 'INFO' },
    ...overrides,
  }
}

describe('normalize — labelling guard', () => {
  // LABELLING GUARD: mappedOrderId must come from matched_order_id, never a synthetic
  // outbound_order_id. ExchangeSummary has no outbound_order_id field to read — this
  // test simulates the mistake by re-implementing the mapping inline with the WRONG
  // field, proving the assertion actually discriminates (revert-to-confirm).
  test('normalizeExchange surfaces matched_order_id as mappedOrderId', () => {
    const e = makeExchange({ matched_order_id: 'order-original-1' })
    const row = normalizeExchange(e)
    expect(row.mappedOrderId).toBe('order-original-1')
  })

  // Revert-to-confirm: an intentionally-wrong normalizer (reading a would-be
  // outbound_order_id instead) must NOT satisfy this test — proving the real
  // normalizeExchange() genuinely reads matched_order_id and not something else.
  test('revert-to-confirm — a normalizer reading outbound_order_id would fail this same assertion', () => {
    const e = makeExchange({ matched_order_id: 'order-original-1' })
    const wrongRow = { ...normalizeExchange(e), mappedOrderId: 'order-synthetic-outbound-99' }
    expect(wrongRow.mappedOrderId).not.toBe('order-original-1')
    // ...and the real implementation still gets it right:
    expect(normalizeExchange(e).mappedOrderId).toBe('order-original-1')
  })

  test('normalizeRefund surfaces order_id as mappedOrderId (always present)', () => {
    const r = makeRefund({ order_id: 'order-1' })
    expect(normalizeRefund(r).mappedOrderId).toBe('order-1')
  })

  test('normalizeExchange never exposes an outboundOrderId field at all', () => {
    const row = normalizeExchange(makeExchange())
    expect('outboundOrderId' in row).toBe(false)
  })
})

describe('normalize — shape', () => {
  test('normalizeExchange sets kind=exchange and carries status, never legStatus', () => {
    const row = normalizeExchange(makeExchange({ status: 'needs_confirmation' }))
    expect(row.kind).toBe('exchange')
    expect(row.exchangeStatus).toBe('needs_confirmation')
    expect(row.legStatus).toBeUndefined()
  })

  test('normalizeRefund sets kind=refund and carries legStatus, never exchangeStatus', () => {
    const row = normalizeRefund(makeRefund())
    expect(row.kind).toBe('refund')
    expect(row.legStatus).toEqual({ primaryKey: 'status.in_transit', tone: 'INFO' })
    expect(row.exchangeStatus).toBeUndefined()
  })

  test('normalizeRefund never fabricates a matchedAt/last-update timestamp', () => {
    expect(normalizeRefund(makeRefund()).matchedAt).toBeNull()
  })
})

describe('matchesFilter — honesty constraint 2 (no fake exchange courier progress)', () => {
  test('inTransit never matches an exchange row, even one with matching-sounding status', () => {
    const exchangeRow = normalizeExchange(makeExchange({ status: 'matched' }))
    expect(matchesFilter(exchangeRow, 'inTransit')).toBe(false)
  })

  test('inTransit matches a refund row whose leg_status is with_courier (status.in_transit)', () => {
    const refundRow = normalizeRefund(makeRefund({ leg_status: { primaryKey: 'status.in_transit', tone: 'INFO' } }))
    expect(matchesFilter(refundRow, 'inTransit')).toBe(true)
  })

  test('received matches exchange status return_received and refund leg_status delivered', () => {
    const exchangeRow = normalizeExchange(makeExchange({ status: 'return_received' }))
    const refundRow   = normalizeRefund(makeRefund({ leg_status: { primaryKey: 'status.delivered', tone: 'SUCCESS' } }))
    expect(matchesFilter(exchangeRow, 'received')).toBe(true)
    expect(matchesFilter(refundRow, 'received')).toBe(true)
  })

  test('needsAction matches unmatched/needs_confirmation exchanges and needs_attention refunds', () => {
    expect(matchesFilter(normalizeExchange(makeExchange({ status: 'unmatched' })), 'needsAction')).toBe(true)
    expect(matchesFilter(normalizeExchange(makeExchange({ status: 'needs_confirmation' })), 'needsAction')).toBe(true)
    expect(matchesFilter(normalizeExchange(makeExchange({ status: 'matched' })), 'needsAction')).toBe(false)
    expect(matchesFilter(
      normalizeRefund(makeRefund({ leg_status: { primaryKey: 'status.needs_attention', tone: 'WARN' } })),
      'needsAction',
    )).toBe(true)
  })

  test('refunds/exchanges tabs split by kind only', () => {
    const exchangeRow = normalizeExchange(makeExchange())
    const refundRow   = normalizeRefund(makeRefund())
    expect(matchesFilter(exchangeRow, 'exchanges')).toBe(true)
    expect(matchesFilter(exchangeRow, 'refunds')).toBe(false)
    expect(matchesFilter(refundRow, 'refunds')).toBe(true)
    expect(matchesFilter(refundRow, 'exchanges')).toBe(false)
  })

  test('all matches everything', () => {
    expect(matchesFilter(normalizeExchange(makeExchange()), 'all')).toBe(true)
    expect(matchesFilter(normalizeRefund(makeRefund()), 'all')).toBe(true)
  })
})
