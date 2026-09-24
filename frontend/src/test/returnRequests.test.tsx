import { test, expect, describe, vi, beforeEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import ExchangesRefunds from '../pages/ExchangesRefunds'
import type { ReturnRequestDetail, ReturnRequestRow, PortalSettings } from '../api'

// Returns portal Step 4e-A — the Requests tab (M1), its drawer (M2) and reject form (M3).
// Fakes match the real response shapes of ReturnRequestService / PortalSettingsService.

let role: 'owner' | 'manager' | 'worker' = 'owner'
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => role) }
})

const now = new Date()
const ROWS: ReturnRequestRow[] = [
  {
    id: 'rr-1', reference: 'RR-7K3F9M', orderNumber: '#1047', customerName: 'Mariam Saleh', itemCount: 1,
    reasonCodes: ['wrong_size'], status: 'requested', createdAt: now.toISOString(),
  },
  {
    id: 'rr-2', reference: 'RR-Q8HT2C', orderNumber: '#1039', customerName: 'Omar Khaled', itemCount: 2,
    reasonCodes: ['damaged', 'wrong_size'], status: 'requested', createdAt: '2026-09-20T10:00:00Z',
  },
  {
    id: 'rr-3', reference: 'RR-M4ZP6W', orderNumber: '#1021', customerName: 'Salma Adel', itemCount: 1,
    reasonCodes: ['changed_mind'], status: 'approved', createdAt: '2026-09-19T10:00:00Z',
  },
]

function detailFor(id: string, status = 'requested'): ReturnRequestDetail {
  const row = ROWS.find(r => r.id === id)!
  return {
    id, reference: row.reference, orderId: 'order-' + id, orderNumber: row.orderNumber,
    customerName: row.customerName, customerPhone: '01012345678', type: 'refund',
    status: status as ReturnRequestDetail['status'], email: 'mariam@example.com',
    note: 'It runs small.', createdAt: row.createdAt, deliveredAt: '2026-09-18T10:00:00.000+00:00',
    pickupCity: 'Cairo', pickupZone: 'Nasr City',
    decidedAt: status === 'requested' ? null : '2026-09-24T10:00:00.000+00:00',
    decidedBy: status === 'requested' ? null : 'user-1',
    decidedByName: status === 'requested' ? null : 'Owner',
    rejectionReason: status === 'rejected' ? 'Worn items' : null, returnShipmentId: null,
    items: [{
      id: 'item-1', pieceId: 'piece-1', shortCode: 'P000245', variantId: 'v-1', productTitle: 'Linen Shirt',
      variantTitle: 'White / M', imageUrl: null, reasonCode: 'wrong_size', active: status !== 'rejected',
    }],
  }
}

const SETTINGS: PortalSettings = {
  slug: 'nourstudio', enabled: true, autoApprove: false, returnWindowDays: 30,
  logoUrl: null, brandColor: null, policyText: null, pickupBooking: false,
}

function fakeResponse(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    statusText: status === 409 ? 'Conflict' : 'OK',
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
    text: async () => JSON.stringify(data),
  })
}

let statuses: Record<string, string>
let approveStatus: number
let calls: Array<{ method: string; url: string; body?: unknown }>

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  calls.push({ method, url, body: opts.body ? JSON.parse(opts.body as string) : undefined })
  const current = () => ROWS.map(r => ({ ...r, status: statuses[r.id] as ReturnRequestRow['status'] }))

  if (url.includes('/return-requests') && url.endsWith('/approve') && method === 'POST') {
    const id = url.split('/return-requests/')[1].split('/')[0]
    if (approveStatus === 409) { statuses[id] = 'approved'; return fakeResponse(null, 409) }
    statuses[id] = 'approved'
    return fakeResponse(null, 204)
  }
  if (url.includes('/return-requests') && url.endsWith('/reject') && method === 'POST') {
    const id = url.split('/return-requests/')[1].split('/')[0]
    statuses[id] = 'rejected'
    return fakeResponse(null, 204)
  }
  if (url.includes('/return-requests/') && method === 'GET') {
    const id = url.split('/return-requests/')[1].split('?')[0]
    return fakeResponse(detailFor(id, statuses[id]))
  }
  if (url.includes('/return-requests?') && method === 'GET') {
    const status = new URL(url, 'http://x').searchParams.get('status')
    const items = current().filter(r => !status || r.status === status)
    return fakeResponse({ items, total: items.length })
  }
  if (url.includes('/tenant/portal-settings')) return fakeResponse(SETTINGS)
  if (url.includes('/exchanges') || url.includes('/refunds')) return fakeResponse([])
  return fakeResponse({})
}

beforeEach(() => {
  role = 'owner'
  statuses = { 'rr-1': 'requested', 'rr-2': 'requested', 'rr-3': 'approved' }
  approveStatus = 204
  calls = []
  stubFetchWithShellDefaults(vi.fn(backend))
})

async function openRequestsTab() {
  const user = userEvent.setup()
  renderWithProviders(<ExchangesRefunds />)
  const tab = await screen.findByRole('button', { name: /Requests/ })
  await user.click(tab)
  await screen.findByText('RR-7K3F9M')
  return user
}

async function openDrawer(user: ReturnType<typeof userEvent.setup>, reference = 'RR-7K3F9M') {
  await user.click(screen.getByText(reference))
  const drawer = screen.getByTestId('return-request-drawer')
  await within(drawer).findByText('It runs small.')
  return drawer
}

describe('Requests tab', () => {
  test('badge shows the count of requested requests', async () => {
    renderWithProviders(<ExchangesRefunds />)
    const badge = await screen.findByTestId('requests-new-badge')
    expect(badge).toHaveTextContent('2 new')
  })

  test('list renders M1 columns: reference, short customer, order, items, reasons, sent, status', async () => {
    await openRequestsTab()
    const row = screen.getByText('RR-Q8HT2C').closest('tr')!
    expect(within(row).getByText('Omar K.')).toBeInTheDocument()
    expect(within(row).getByText('#1039')).toBeInTheDocument()
    expect(within(row).getByText('2')).toBeInTheDocument()
    expect(within(row).getByText('Damaged or faulty, Wrong size')).toBeInTheDocument()
    expect(within(row).getByText('Requested')).toBeInTheDocument()
    const today = screen.getByText('RR-7K3F9M').closest('tr')!
    expect(within(today).getByText(/^Today, /)).toBeInTheDocument()
    expect(within(screen.getByText('RR-M4ZP6W').closest('tr')!).getByText('Approved')).toBeInTheDocument()
    // Own paginated fetch.
    expect(calls.some(c => c.method === 'GET' && c.url.includes('/return-requests?page=0&size=25'))).toBe(true)
  })

  test('row opens the drawer with all detail fields, items with short codes and reason, and the note', async () => {
    const user = await openRequestsTab()
    const drawer = await openDrawer(user)
    expect(within(drawer).getByText('Mariam S.')).toBeInTheDocument()
    expect(within(drawer).getByText('01012345678')).toBeInTheDocument()
    expect(within(drawer).getByText('#1047')).toBeInTheDocument()
    expect(within(drawer).getByText(/delivered/)).toBeInTheDocument()
    expect(within(drawer).getByText('Cairo · Nasr City')).toBeInTheDocument()
    expect(within(drawer).getByText('mariam@example.com')).toBeInTheDocument()
    expect(within(drawer).getByText('Items (1)')).toBeInTheDocument()
    const item = within(drawer).getByTestId('request-item')
    expect(within(item).getByText('Linen Shirt')).toBeInTheDocument()
    expect(within(item).getByText('P000245')).toBeInTheDocument()
    expect(within(item).getByText('Wrong size')).toBeInTheDocument()
  })

  test('pre-4c footer helper text, not the mockup\'s booking line', async () => {
    const user = await openRequestsTab()
    const drawer = await openDrawer(user)
    expect(await within(drawer).findByText('After approving, book the pickup in Bosta.')).toBeInTheDocument()
    expect(within(drawer).queryByText(/Approving books a Bosta pickup/)).not.toBeInTheDocument()
  })

  test('Approve calls the endpoint, the drawer shows Approved, and the list and badge refresh', async () => {
    const user = await openRequestsTab()
    const drawer = await openDrawer(user)
    await user.click(within(drawer).getByRole('button', { name: 'Approve' }))

    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/return-requests/rr-1/approve'))).toBe(true))
    await waitFor(() => expect(within(drawer).getByText('Approved')).toBeInTheDocument())
    expect(within(drawer).queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
    await waitFor(() => expect(screen.getByTestId('requests-new-badge')).toHaveTextContent('1 new'))
    const row = screen.getByText('RR-7K3F9M', { selector: 'td bdi' }).closest('tr')!
    await waitFor(() => expect(within(row).getByText('Approved')).toBeInTheDocument())
  })

  test('Reject requires a reason, shows the live counter, then calls the endpoint', async () => {
    const user = await openRequestsTab()
    const drawer = await openDrawer(user)
    await user.click(within(drawer).getByRole('button', { name: 'Reject' }))

    const form = within(drawer).getByTestId('reject-form')
    expect(within(form).getByText('— the customer will see this')).toBeInTheDocument()
    expect(within(form).getByText('0 / 300')).toBeInTheDocument()

    await user.click(within(form).getByRole('button', { name: 'Reject request' }))
    expect(within(form).getByRole('alert')).toHaveTextContent('Add a reason for rejecting.')
    expect(calls.some(c => c.url.endsWith('/reject'))).toBe(false)

    await user.type(within(form).getByLabelText(/Reason for rejecting/), 'Worn items')
    expect(within(form).getByText('10 / 300')).toBeInTheDocument()
    await user.click(within(form).getByRole('button', { name: 'Reject request' }))

    await waitFor(() => {
      const call = calls.find(c => c.method === 'POST' && c.url.endsWith('/return-requests/rr-1/reject'))
      expect(call?.body).toEqual({ reason: 'Worn items' })
    })
    await waitFor(() => expect(within(drawer).getByText('Rejected')).toBeInTheDocument())
  })

  test('Cancel in the reject form returns to the detail footer', async () => {
    const user = await openRequestsTab()
    const drawer = await openDrawer(user)
    await user.click(within(drawer).getByRole('button', { name: 'Reject' }))
    await user.click(within(drawer).getByRole('button', { name: 'Cancel' }))
    expect(within(drawer).queryByTestId('reject-form')).not.toBeInTheDocument()
    expect(within(drawer).getByRole('button', { name: 'Approve' })).toBeInTheDocument()
  })

  test('a 409 shows "already decided — refreshed" and reloads', async () => {
    approveStatus = 409
    const user = await openRequestsTab()
    const drawer = await openDrawer(user)
    const detailCallsBefore = calls.filter(c => c.url.endsWith('/return-requests/rr-1')).length
    await user.click(within(drawer).getByRole('button', { name: 'Approve' }))

    expect(await within(drawer).findByText('This request was already decided — refreshed.')).toBeInTheDocument()
    expect(calls.filter(c => c.url.endsWith('/return-requests/rr-1')).length).toBeGreaterThan(detailCallsBefore)
    await waitFor(() => expect(within(drawer).getByText('Approved')).toBeInTheDocument())
  })

  test('manager sees the Requests tab', async () => {
    role = 'manager'
    renderWithProviders(<ExchangesRefunds />)
    expect(await screen.findByRole('button', { name: /Requests/ })).toBeInTheDocument()
  })

  test('worker does not see the Requests tab and never calls the endpoint', async () => {
    role = 'worker'
    renderWithProviders(<ExchangesRefunds />)
    await screen.findByRole('button', { name: /All/ })
    await waitFor(() => expect(calls.some(c => c.url.includes('/refunds'))).toBe(true))
    expect(screen.queryByRole('button', { name: /Requests/ })).not.toBeInTheDocument()
    expect(calls.some(c => c.url.includes('/return-requests'))).toBe(false)
  })
})
