import { test, expect, describe, vi, beforeEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen } from './renderWithProviders'
import ShopifySyncState from '../pages/exchangesRefunds/ShopifySyncState'
import { checkShopifySync, markShopifySyncChecked, retryShopifySync } from '../api'
import type { ReturnRequestDetail, ShopifySyncState as Sync } from '../api'
import en from '../locales/en.json'
import ar from '../locales/ar.json'

// S5a drawer actions, prod 2026-11-09 (RR-ZMCAUG): Try again answered a bare 404 every time. These pin the exact
// URLs the drawer calls — the same strings ShopifyReturnSyncHttpAppUserTest posts to the real app as app_user — and
// give a code-less 404 its own message instead of the generic "Couldn't do that".

const ID = '021fe81a-85da-422f-a11b-c4c3d308862a'
let answer: { status: number; body: unknown }
let fetchMock: ReturnType<typeof vi.fn>

function fakeResponse(data: unknown, status: number) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: status === 404 ? 'Not Found' : '',
    headers: { get: (k: string) => (k === 'content-type' && data != null ? 'application/json' : null) },
    json: async () => (data == null ? Promise.reject(new SyntaxError('empty')) : structuredClone(data)),
    text: async () => (data == null ? '' : JSON.stringify(data)),
  })
}

beforeEach(() => {
  answer = { status: 202, body: { status: 'queued', fromStatus: 'failed' } }
  fetchMock = vi.fn(() => fakeResponse(answer.body, answer.status))
  vi.stubGlobal('fetch', fetchMock)
})

describe('drawer Shopify actions — exact paths', () => {
  test.each([
    ['retry', retryShopifySync],
    ['check', checkShopifySync],
    ['checked', markShopifySyncChecked],
  ] as const)('%s → POST /api/v1/return-requests/{id}/shopify-sync/%s', async (action, call) => {
    await call(ID)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, opts] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe(`/api/v1/return-requests/${ID}/shopify-sync/${action}`)
    expect(opts.method).toBe('POST')
  })
})

describe('drawer — a 404 without a code', () => {
  test('shows the not-found message, not the generic one', async () => {
    answer = { status: 404, body: null }
    const onChanged = vi.fn(async () => {})
    renderWithProviders(<ShopifySyncState
      detail={{ id: ID, reference: 'RR-ZMCAUG',
        shopifySync: { status: 'failed', attemptsExhausted: true, canRetry: true } as Sync } as unknown as ReturnRequestDetail}
      onChanged={onChanged} />)
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    const row = await screen.findByTestId('shopify-sync-refused')
    expect(row).toHaveTextContent("Traced couldn't find this request for Shopify")
    expect(row).not.toHaveTextContent("Couldn't do that")
    expect(onChanged).not.toHaveBeenCalled()
  })

  test('the message exists in English and Arabic', () => {
    const k = (o: unknown) => (o as { exchangesRefunds: { requests: { drawer: { shopify: Record<string, string> } } } })
      .exchangesRefunds.requests.drawer.shopify.notFound
    expect(k(en)).toMatch(/couldn't find this request/)
    expect(k(ar)).toMatch(/لم يتمكن Traced/)
  })
})
