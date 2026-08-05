import { test, expect, describe } from 'vitest'
import { renderWithProviders, screen } from './renderWithProviders'
import { OrderStatus } from '../components/ui'
import type { DerivedOrderStatus } from '../api'

// B2 — the A3 conflict chip now links to the matching Part-B exception-queue entry.

function makeDerived(overrides: Partial<DerivedOrderStatus> = {}): DerivedOrderStatus {
  return {
    primaryKey: 'status.cancelled',
    tone: 'NEUTRAL',
    healthChips: [],
    historicalNote: null,
    conflictKey: null,
    notTraced: false,
    ...overrides,
  }
}

describe('OrderStatus — B2 conflict chip links to the matching exception', () => {
  test('live_shipment conflict links to /exceptions?type=cancelled_live_shipment', () => {
    renderWithProviders(
      <OrderStatus derived={makeDerived({ conflictKey: 'status.conflict.live_shipment' })} />,
    )
    const link = screen.getByText('Shipment still live — cancel the AWB').closest('a')
    expect(link).not.toBeNull()
    expect(link).toHaveAttribute('href', '/exceptions?type=cancelled_live_shipment')
  })

  test('cancelled_but_delivered conflict links to /exceptions?type=cancelled_but_delivered', () => {
    renderWithProviders(
      <OrderStatus derived={makeDerived({ conflictKey: 'status.conflict.cancelled_but_delivered' })} />,
    )
    const link = screen.getByText('Cancelled · but delivered').closest('a')
    expect(link).not.toBeNull()
    expect(link).toHaveAttribute('href', '/exceptions?type=cancelled_but_delivered')
  })

  test('no conflictKey — no link rendered at all', () => {
    renderWithProviders(<OrderStatus derived={makeDerived()} />)
    expect(screen.queryByRole('link')).toBeNull()
  })
})
