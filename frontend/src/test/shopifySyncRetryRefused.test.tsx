import { test, expect, describe, vi, beforeEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import ShopifySyncState from '../pages/exchangesRefunds/ShopifySyncState'
import type { ReturnRequestDetail, ShopifySyncState as Sync } from '../api'

// S5a "Try again" fix (2026-11-08, RR-ZMCAUG): a refused Try again says why IN the drawer row (not a silent no-op),
// and an accepted one (202 with a JSON body) refreshes the drawer.

let answer: { status: number; body: unknown }

function fakeResponse(data: unknown, status: number) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data), text: async () => JSON.stringify(data),
  })
}

beforeEach(() => {
  answer = { status: 202, body: { status: 'queued', fromStatus: 'failed' } }
  vi.stubGlobal('fetch', vi.fn(() => fakeResponse(answer.body, answer.status)))
})

function render(sync: Sync) {
  const onChanged = vi.fn(async () => {})
  renderWithProviders(<ShopifySyncState
    detail={{ id: 'rr-1', reference: 'RR-ZMCAUG', shopifySync: sync } as unknown as ReturnRequestDetail}
    onChanged={onChanged} />)
  return onChanged
}

describe('drawer — Try again', () => {
  test('refused: the reason is shown in the row', async () => {
    answer = { status: 409, body: { code: 'SHOPIFY_SYNC_OFF',
      message_en: 'Creating returns in Shopify is switched off (Settings › Returns portal), so nothing was started.',
      message_ar: 'إنشاء المرتجعات في شوبيفاي متوقف' } }
    const onChanged = render({ status: 'failed', attemptsExhausted: true, canRetry: true })
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByTestId('shopify-sync-refused')).toHaveTextContent('switched off')
    expect(onChanged).not.toHaveBeenCalled()
  })

  test('accepted (202 + JSON body): refreshed, nothing refused', async () => {
    const onChanged = render({ status: 'failed', attemptsExhausted: true, canRetry: true })
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(onChanged).toHaveBeenCalled())
    expect(screen.queryByTestId('shopify-sync-refused')).not.toBeInTheDocument()
  })
})
