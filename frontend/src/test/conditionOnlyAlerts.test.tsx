import { test, expect, describe, vi, beforeEach } from 'vitest'
import { renderWithProviders, screen, within } from './renderWithProviders'
import * as api from '../api'
import Exceptions from '../pages/Exceptions'
import ar from '../locales/ar.json'

/**
 * Deleted-in-Bosta slice 2 (screen): the condition-only alerts show what clears them instead of a
 * Resolve button; every other alert keeps Resolve (cancelled_but_delivered included — review 2026-10-19).
 */

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, request: vi.fn() }
})

const CLEARS: Record<string, [string, string]> = {
  cancelled_live_shipment: ['BOSTA_LEG_ENDED', 'Cancel it in Bosta to stop a courier coming.'],
  guided_unpack:           ['ORDER_UNPACKED', 'Open the order (Go →) and unpack them.'],
  exchange_needs_mapping:  ['EXCHANGE_MAPPED', 'Open it (Go →) to choose them.'],
  exchange_unmapped_state: ['BOSTA_STATE_RECOGNISED', 'If it looks stuck, check the exchange in Bosta.'],
}

function alert(type: string, i: number, clearsWhen?: string) {
  return {
    type, severity: 'HIGH', subject_key: `${type}:order:${i}`, subject_type: 'order',
    order_id: `o-${i}`, order_number: `#${4100 + i}`, tracking_number: String(6979330980 + i), ageSeconds: 600,
    descriptionEn: `Alert ${type}`, descriptionAr: `تنبيه ${type}`, suggestedAction: 'x', actionUrl: '/orders',
    ...(clearsWhen ? { clearsWhen } : {}),
  }
}

describe('Exceptions — condition-only alerts', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    const items = [
      ...Object.entries(CLEARS).map(([type, [code]], i) => alert(type, i, code)),
      alert('stuck_shipment', 9),
      alert('cancelled_but_delivered', 8),
    ]
    vi.mocked(api.request).mockImplementation(async () => ({
      total: items.length, page: 0, size: 50, items, counts: { critical: 0, high: items.length, medium: 0, low: 0 },
    }))
  })

  test('the four show their clearsWhen text and no Resolve; stuck_shipment and cancelled_but_delivered keep Resolve', async () => {
    renderWithProviders(<Exceptions />)
    await screen.findByText('Alert stuck_shipment')

    for (const [type, [, text]] of Object.entries(CLEARS)) {
      const row = screen.getByText(`Alert ${type}`).closest<HTMLElement>('.card')!
      expect(within(row).getByTestId('exception-clears-when').textContent).toContain(text)
      expect(within(row).queryByRole('button', { name: 'Resolve' })).toBeNull()
    }
    for (const type of ['stuck_shipment', 'cancelled_but_delivered']) {
      const row = screen.getByText(`Alert ${type}`).closest<HTMLElement>('.card')!
      expect(within(row).getByRole('button', { name: 'Resolve' })).toBeTruthy()
      expect(within(row).queryByTestId('exception-clears-when')).toBeNull()
    }
    expect(screen.getAllByRole('button', { name: 'Resolve' })).toHaveLength(2)
  })

  test('every clearsWhen code has an Arabic text', () => {
    const texts = (ar as { exc: { clearsWhen: Record<string, string> } }).exc.clearsWhen
    for (const [code] of Object.values(CLEARS)) expect(texts[code]?.length).toBeGreaterThan(20)
  })
})
