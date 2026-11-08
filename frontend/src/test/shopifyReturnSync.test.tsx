import { test, expect, describe, vi, beforeEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import ShopifySyncState from '../pages/exchangesRefunds/ShopifySyncState'
import ShopifyReturnsSyncCard from '../pages/settings/ShopifyReturnsSyncCard'
import type { ReturnRequestDetail, ShopifySyncState as Sync } from '../api'

// Shopify-native returns S5a — the request drawer's "Shopify" row and the owner's "Create returns in Shopify" switch.

let calls: Array<{ method: string; url: string; body?: unknown }>
let settings: Record<string, unknown>

function fakeResponse(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : k === 'content-length' && data == null ? '0' : null) },
    json: async () => structuredClone(data), text: async () => JSON.stringify(data),
  })
}

beforeEach(() => {
  calls = []
  settings = { visible: true, enabled: false, since: null, permissionGranted: true }
  vi.stubGlobal('fetch', vi.fn((url: string, opts: RequestInit = {}) => {
    const method = (opts.method ?? 'GET').toUpperCase()
    const body = opts.body ? JSON.parse(opts.body as string) : undefined
    calls.push({ method, url, body })
    if (url.includes('/tenant/shopify-returns-sync') && method === 'PUT') {
      settings = { ...settings, enabled: body.enabled, since: body.enabled ? '2026-11-07T10:00:00Z' : settings.since }
      return fakeResponse(settings)
    }
    if (url.includes('/tenant/shopify-returns-sync')) return fakeResponse(settings)
    if (url.includes('/shopify-sync/')) return fakeResponse(null, 204)
    return fakeResponse({})
  }))
})

function detail(sync?: Sync): ReturnRequestDetail {
  return { id: 'rr-1', reference: 'RR-7K3F9M', shopifySync: sync } as unknown as ReturnRequestDetail
}

function renderState(sync?: Sync) {
  const onChanged = vi.fn(async () => {})
  renderWithProviders(<ShopifySyncState detail={detail(sync)} onChanged={onChanged} />)
  return onChanged
}

describe('drawer — Shopify row', () => {
  test('absent block renders nothing', () => {
    renderState(undefined)
    expect(screen.queryByTestId('shopify-sync')).not.toBeInTheDocument()
  })

  test('created: the return name and a link to the order in Shopify, no buttons', () => {
    renderState({ status: 'created', returnName: '#1047-R1', adminUrl: 'https://admin.shopify.com/store/nour/orders/1',
      canRetry: false, canMarkChecked: false })
    expect(screen.getByTestId('shopify-sync-created')).toHaveTextContent('Return created in Shopify · #1047-R1')
    expect(screen.getByRole('link', { name: /Open the order in Shopify/ })).toHaveAttribute('href',
      'https://admin.shopify.com/store/nour/orders/1')
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  test('waiting for approval / not synced with its reason', () => {
    renderState({ status: 'waiting', reason: 'awaiting_approval' })
    expect(screen.getByTestId('shopify-sync-waiting')).toHaveTextContent('once approved')
  })

  test('not synced: the reason is shown, Try again re-evaluates', async () => {
    const onChanged = renderState({ status: 'skipped', reason: 'no_permission', canRetry: true })
    expect(screen.getByTestId('shopify-sync-skipped')).toHaveTextContent("Not synced to Shopify — Shopify's new permissions aren't approved yet")
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(onChanged).toHaveBeenCalled())
    expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/return-requests/rr-1/shopify-sync/retry'))).toBe(true)
  })

  test('not sure it was created: Check again, or "It isn\'t in Shopify — try again"', async () => {
    renderState({ status: 'failed_ambiguous', canRetry: true, canMarkChecked: false })
    expect(screen.getByTestId('shopify-sync-ambiguous')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Check again' }))
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/shopify-sync/check'))).toBe(true))
    await userEvent.click(screen.getByRole('button', { name: "It isn't in Shopify — try again" }))
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/shopify-sync/retry'))).toBe(true))
  })

  test('out of step after an undo: explained, "I checked it in Shopify"', async () => {
    renderState({ status: 'created', returnName: '#1047-R1', outOfStep: true, canMarkChecked: true, canRetry: false })
    expect(screen.getByTestId('shopify-sync-out-of-step')).toHaveTextContent("Traced won't change it in Shopify")
    await userEvent.click(screen.getByRole('button', { name: 'I checked it in Shopify' }))
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/shopify-sync/checked'))).toBe(true))
  })

  test('failed every attempt reads as refused', () => {
    renderState({ status: 'failed', attemptsExhausted: true, canRetry: true })
    expect(screen.getByTestId('shopify-sync-failed')).toHaveTextContent('Shopify refused to create the return')
  })
})

describe('settings — Create returns in Shopify', () => {
  test('hidden when the tenant is not in the rollout', async () => {
    settings = { visible: false }
    renderWithProviders(<ShopifyReturnsSyncCard owner />)
    await waitFor(() => expect(calls.some(c => c.url.includes('/tenant/shopify-returns-sync'))).toBe(true))
    expect(screen.queryByTestId('shopify-returns-sync-card')).not.toBeInTheDocument()
  })

  test('owner switches it on right away', async () => {
    renderWithProviders(<ShopifyReturnsSyncCard owner />)
    const sw = await screen.findByRole('switch')
    expect(sw).toHaveAttribute('aria-checked', 'false')
    await userEvent.click(sw)
    await waitFor(() => expect(screen.getByRole('switch')).toHaveAttribute('aria-checked', 'true'))
    expect(calls.find(c => c.method === 'PUT')?.body).toEqual({ enabled: true })
    expect(screen.getByText('Only requests approved from now on are created in Shopify.')).toBeInTheDocument()
  })

  test('manager sees it but cannot switch it', async () => {
    renderWithProviders(<ShopifyReturnsSyncCard owner={false} />)
    expect(await screen.findByRole('switch')).toBeDisabled()
  })

  test('on without the new permission: says so', async () => {
    settings = { visible: true, enabled: true, since: '2026-11-07T10:00:00Z', permissionGranted: false }
    renderWithProviders(<ShopifyReturnsSyncCard owner />)
    expect(await screen.findByTestId('shopify-returns-sync-permission')).toBeInTheDocument()
  })
})
