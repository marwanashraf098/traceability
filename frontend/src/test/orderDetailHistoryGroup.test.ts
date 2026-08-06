import { test, expect, describe } from 'vitest'
import { groupHistory, toRawDisplay } from '../pages/OrderDetail'
import type { DeliveryHistoryEntry } from '../api'

// A4 — collapses consecutive identical internal_state rows into one grouped entry
// { state, count, firstAt, lastAt }, e.g. "In transit · 14 scans · Jul 25–30".
// Terminals fold like any other state (no milestone exemption). Exception rows fold on
// (state + exception_code) together — same code (including null==null) merges, a
// different code starts a new group.

function entry(state: string, occurredAt: string, exceptionCode: number | null = null, exceptionReason: string | null = null): DeliveryHistoryEntry {
  return { state, providerState: null, exceptionCode, exceptionReason, occurredAt }
}

describe('groupHistory — A4 forward-leg history collapse', () => {
  test('folds N consecutive identical states into 1 grouped entry with correct count + range', () => {
    const history = [
      entry('with_courier', '2026-07-25T08:00:00Z'),
      entry('with_courier', '2026-07-26T08:00:00Z'),
      entry('with_courier', '2026-07-27T08:00:00Z'),
      entry('with_courier', '2026-07-30T08:00:00Z'),
    ]
    const grouped = groupHistory(history)
    expect(grouped).toHaveLength(1)
    expect(grouped[0]).toMatchObject({
      state: 'with_courier',
      count: 4,
      firstAt: '2026-07-25T08:00:00Z',
      lastAt: '2026-07-30T08:00:00Z',
    })
  })

  test('non-consecutive runs of the same state stay as separate groups', () => {
    const history = [
      entry('created', '2026-07-20T08:00:00Z'),
      entry('with_courier', '2026-07-21T08:00:00Z'),
      entry('created', '2026-07-22T08:00:00Z'), // regressed — separate group, not merged with the first
    ]
    const grouped = groupHistory(history)
    expect(grouped).toHaveLength(3)
    expect(grouped.map(g => g.state)).toEqual(['created', 'with_courier', 'created'])
    expect(grouped.every(g => g.count === 1)).toBe(true)
  })

  // tracking 7260147307 — terminal fold: a repeated terminal (duplicate-webhook redelivery
  // of the same 'delivered' state) folds like any other run; the old never-fold exemption
  // for terminals is gone.
  test('[created, wc x2, delivered x3] folds the terminal run too — Delivered count=3', () => {
    const history = [
      entry('created', '2026-07-20T08:00:00Z'),
      entry('with_courier', '2026-07-21T08:00:00Z'),
      entry('with_courier', '2026-07-22T08:00:00Z'),
      entry('delivered', '2026-07-23T08:00:00Z'),
      entry('delivered', '2026-07-23T09:00:00Z'),
      entry('delivered', '2026-07-23T10:00:00Z'),
    ]
    const grouped = groupHistory(history)
    expect(grouped).toHaveLength(3)
    expect(grouped[0]).toMatchObject({ state: 'created', count: 1 })
    expect(grouped[1]).toMatchObject({ state: 'with_courier', count: 2 })
    expect(grouped[2]).toMatchObject({
      state: 'delivered',
      count: 3,
      firstAt: '2026-07-23T08:00:00Z',
      lastAt: '2026-07-23T10:00:00Z',
    })
  })

  test('a single terminal row has count=1 (no "· 1 scan")', () => {
    const history = [
      entry('with_courier', '2026-07-20T08:00:00Z'),
      entry('delivered', '2026-07-21T08:00:00Z'),
    ]
    const grouped = groupHistory(history)
    expect(grouped[1]).toMatchObject({ state: 'delivered', count: 1 })
  })

  test('distinct adjacent states never merge, even terminal-to-terminal-like transitions', () => {
    const history = [
      entry('with_courier', '2026-07-20T08:00:00Z'),
      entry('returning', '2026-07-21T08:00:00Z'),
      entry('returned', '2026-07-22T08:00:00Z'),
    ]
    const grouped = groupHistory(history)
    expect(grouped.map(g => g.state)).toEqual(['with_courier', 'returning', 'returned'])
    expect(grouped.every(g => g.count === 1)).toBe(true)
  })

  // tracking 5698264896 — exception rows: null exception_code (confirmed 75/75 null in
  // prod today) folds null==null into one group, rendered via the §8.4 NDR fallback key
  // ("Delivery issue") rather than the always-null raw exception_reason text.
  test('[exception(null) x2] folds into one group — key resolves to the "Delivery issue" fallback', () => {
    const history = [
      entry('with_courier', '2026-07-20T08:00:00Z'),
      entry('exception', '2026-07-21T08:00:00Z', null),
      entry('exception', '2026-07-22T08:00:00Z', null),
    ]
    const grouped = groupHistory(history)
    expect(grouped).toHaveLength(2)
    expect(grouped[1]).toMatchObject({
      state: 'exception',
      count: 2,
      exceptionReason: 'orderDetail.historyDeliveryIssue',
    })
  })

  test('exception rows with a mapped NDR code resolve to the shared chip.* key (reused, not forked)', () => {
    const history = [
      entry('exception', '2026-07-21T08:00:00Z', 8), // customer refused
      entry('exception', '2026-07-22T08:00:00Z', 8),
    ]
    const grouped = groupHistory(history)
    expect(grouped).toHaveLength(1)
    expect(grouped[0]).toMatchObject({ state: 'exception', count: 2, exceptionReason: 'chip.customer_refused' })
  })

  test('a different exception_code starts a new group even though internal_state repeats', () => {
    const history = [
      entry('exception', '2026-07-21T08:00:00Z', 8),  // customer refused
      entry('exception', '2026-07-22T08:00:00Z', 3),  // postponed
    ]
    const grouped = groupHistory(history)
    expect(grouped).toHaveLength(2)
    expect(grouped[0]).toMatchObject({ exceptionReason: 'chip.customer_refused' })
    expect(grouped[1]).toMatchObject({ exceptionReason: 'chip.postponed' })
  })

  test('a state after an exception never merges backward into it', () => {
    const history = [
      entry('exception', '2026-07-20T08:00:00Z', 3),
      entry('with_courier', '2026-07-21T08:00:00Z'),
      entry('with_courier', '2026-07-22T08:00:00Z'),
    ]
    const grouped = groupHistory(history)
    expect(grouped).toHaveLength(2)
    expect(grouped[0]).toMatchObject({ state: 'exception', count: 1 })
    expect(grouped[1]).toMatchObject({ state: 'with_courier', count: 2 })
  })

  test('empty history returns empty groups', () => {
    expect(groupHistory([])).toEqual([])
  })
})

describe('toRawDisplay — return-leg passthrough, no grouping', () => {
  test('every raw entry becomes its own count=1 display entry, in order', () => {
    const history = [
      entry('created', '2026-07-20T08:00:00Z'),
      entry('created', '2026-07-21T08:00:00Z'), // would fold under groupHistory — must NOT fold here
    ]
    const display = toRawDisplay(history)
    expect(display).toHaveLength(2)
    expect(display.every(d => d.count === 1)).toBe(true)
  })

  test('raw exception_reason text passes through untouched (not routed through the NDR map)', () => {
    const history = [entry('exception', '2026-07-20T08:00:00Z', null, 'Customer not answering')]
    const display = toRawDisplay(history)
    expect(display[0].exceptionReason).toBe('Customer not answering')
  })
})
