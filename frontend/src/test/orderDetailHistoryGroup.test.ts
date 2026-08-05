import { test, expect, describe } from 'vitest'
import { groupHistory, toRawDisplay } from '../pages/OrderDetail'
import type { DeliveryHistoryEntry } from '../api'

// A4 — collapses consecutive identical internal_state rows into one grouped entry
// { stateKey, count, firstAt, lastAt }, e.g. "In transit · 14 scans · Jul 25–30".
// Exception rows and terminal transitions must never be folded, even if consecutive
// identical rows occur — each stays its own milestone.

function entry(state: string, occurredAt: string, exceptionReason: string | null = null): DeliveryHistoryEntry {
  return { state, providerState: null, exceptionCode: null, exceptionReason, occurredAt }
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

  test('exception rows are never folded, even when consecutive and identical', () => {
    const history = [
      entry('with_courier', '2026-07-20T08:00:00Z'),
      entry('exception', '2026-07-21T08:00:00Z', 'Customer not answering'),
      entry('exception', '2026-07-22T08:00:00Z', 'Postponed by customer'),
    ]
    const grouped = groupHistory(history)
    expect(grouped).toHaveLength(3)
    expect(grouped[1]).toMatchObject({ state: 'exception', count: 1, exceptionReason: 'Customer not answering' })
    expect(grouped[2]).toMatchObject({ state: 'exception', count: 1, exceptionReason: 'Postponed by customer' })
  })

  test('a terminal transition is always its own milestone, never merged into a preceding run', () => {
    const history = [
      entry('with_courier', '2026-07-20T08:00:00Z'),
      entry('with_courier', '2026-07-21T08:00:00Z'),
      entry('delivered', '2026-07-22T08:00:00Z'),
    ]
    const grouped = groupHistory(history)
    expect(grouped).toHaveLength(2)
    expect(grouped[0]).toMatchObject({ state: 'with_courier', count: 2 })
    expect(grouped[1]).toMatchObject({ state: 'delivered', count: 1 })
  })

  test('a state after a milestone never merges backward into it', () => {
    const history = [
      entry('exception', '2026-07-20T08:00:00Z', 'Postponed by customer'),
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
})
