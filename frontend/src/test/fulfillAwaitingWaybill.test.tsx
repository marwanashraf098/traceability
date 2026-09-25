import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { Routes, Route } from 'react-router-dom'
import { renderWithProviders, screen } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import Fulfill from '../pages/Fulfill'

/**
 * Pick & Pack empty state (2026-09-25, App Store review): an empty queue must say WHY
 * when open orders are held out only for lack of a Bosta waybill — the reviewer's
 * draft-sourced orders had no Bosta shipment and the queue just said "No orders ready
 * to pick". Three variants:
 *   1. count > 0, Bosta NOT connected → waybill message + "Connect Bosta" link
 *   2. count > 0, Bosta connected     → waybill message, no link
 *   3. count = 0                      → plain "No orders ready to pick", no message
 * URL-routed fetch fake with the real response shapes (GET /fulfill/queue = [],
 * GET /fulfill/queue/awaiting-waybill-count = {count}, GET /connections = ConnectionsStatus).
 */

function jsonOk(data: unknown) {
  return Promise.resolve({ ok: true, status: 200, json: async () => structuredClone(data) })
}

function connections(bostaConnected: boolean) {
  return {
    shopify: { connected: true, storeId: 's1', shopDomain: 'reviewer.myshopify.com', connectionType: 'oauth',
               status: 'connected', importStatus: 'completed', lastSyncAt: null },
    bosta: { connected: bostaConnected, businessName: null, pickupMode: null, awbFormat: null, awbLang: null },
    customAppAvailable: false,
    oauthAvailable: true,
    shopifySetup: { appUrl: '', redirectUrl: '', webhookApiVersion: '2024-10', scopes: [] },
  }
}

function routeFetch(count: number, bostaConnected: boolean) {
  return vi.fn((url: string) => {
    if (url.endsWith('/fulfill/queue/awaiting-waybill-count')) return jsonOk({ count })
    if (url.endsWith('/fulfill/queue'))                        return jsonOk([])
    if (url.endsWith('/connections'))                          return jsonOk(connections(bostaConnected))
    return jsonOk({})
  })
}

function renderFulfill() {
  return renderWithProviders(
    <Routes>
      <Route path="/fulfill" element={<Fulfill />} />
      <Route path="/settings" element={<p>settings-page</p>} />
    </Routes>,
    { initialEntries: ['/fulfill'] },
  )
}

describe('Fulfill — empty queue explains orders waiting for a Bosta waybill', () => {
  beforeEach(() => {
    vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
  })
  afterEach(() => vi.unstubAllGlobals())

  test('count > 0 and Bosta not connected → pluralized message + Connect Bosta link to Settings → Connections', async () => {
    const fetchFn = routeFetch(12, false)
    stubFetchWithShellDefaults(fetchFn)
    renderFulfill()

    expect(await screen.findByText(
      '12 orders are waiting for a Bosta waybill. Orders appear here once a waybill is created in Bosta.',
    )).toBeInTheDocument()
    const link = await screen.findByTestId('fulfill-connect-bosta')
    expect(link).toHaveTextContent('Connect Bosta')
    expect(link).toHaveAttribute('href', '/settings?tab=connections')

    await userEvent.click(link)
    expect(await screen.findByText('settings-page')).toBeInTheDocument()
  })

  test('count = 1 uses the singular form; Bosta connected → no Connect link', async () => {
    stubFetchWithShellDefaults(routeFetch(1, true))
    renderFulfill()

    expect(await screen.findByText(
      '1 order is waiting for a Bosta waybill. Orders appear here once a waybill is created in Bosta.',
    )).toBeInTheDocument()
    expect(screen.queryByTestId('fulfill-connect-bosta')).toBeNull()
  })

  test('count = 0 → keeps the plain "No orders ready to pick" and never asks /connections', async () => {
    const fetchFn = routeFetch(0, false)
    stubFetchWithShellDefaults(fetchFn)
    renderFulfill()

    expect(await screen.findByText('No orders ready to pick')).toBeInTheDocument()
    await vi.waitFor(() =>
      expect(fetchFn.mock.calls.some(([u]) => String(u).endsWith('/awaiting-waybill-count'))).toBe(true))
    expect(screen.queryByTestId('fulfill-awaiting-waybill')).toBeNull()
    expect(fetchFn.mock.calls.some(([u]) => String(u).endsWith('/connections'))).toBe(false)
  })
})
