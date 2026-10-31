import { test, expect, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { Route, Routes, useLocation } from 'react-router-dom'
import { renderWithProviders, screen } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import FulfillRoute from '../pages/fulfill/FulfillRoute'

// Unpack-order S1 — waybill mode has the same pick list (gather list) button as the queue page:
// /fulfill/gather with no batch, i.e. every pickable order.

function json(data: unknown) {
  return Promise.resolve({
    ok: true, status: 200, statusText: '',
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
  })
}

function backend(url: string) {
  if (url.endsWith('/fulfill/mode')) return json({ mode: 'waybill_scan' })
  if (url.endsWith('/fulfill/queue')) return json([])
  if (url.endsWith('/pack-sessions/summary')) return json({ packedToday: 0, openSessionId: null })
  if (url.endsWith('/fulfill/print-batches/today')) return json([])
  if (url.endsWith('/fulfill/printed-not-packed')) return json([])
  if (url.endsWith('/fulfill/packed')) return json([])
  return json({})
}

function Where() { const l = useLocation(); return <p data-testid="where">{l.pathname + l.search}</p> }

beforeEach(() => {
  vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
  stubFetchWithShellDefaults(vi.fn(backend))
})
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

test('waybill page: the Gather button opens the unscoped pick list', async () => {
  renderWithProviders(
    <><Routes>
      <Route path="/fulfill" element={<FulfillRoute />} />
      <Route path="/fulfill/gather" element={<p>gather page</p>} />
    </Routes><Where /></>,
    { initialEntries: ['/fulfill'] },
  )
  await screen.findByTestId('waybill-pack-page')
  await userEvent.click(screen.getByRole('button', { name: 'Gather' }))
  expect(screen.getByTestId('where').textContent).toBe('/fulfill/gather')
})
