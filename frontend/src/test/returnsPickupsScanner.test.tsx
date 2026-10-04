import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import Returns from '../pages/Returns'
import PickupSessions from '../pages/PickupSessions'

// R1 — behaviour Scan returns and Pickups must keep after moving onto useScanner (the burst /
// focus guarantees themselves are proven in a real browser: test-browser/returnsPickupsScanner).

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => 'owner') }
})

const json = (data: unknown, status = 200) => Promise.resolve({
  ok: status < 400, status, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-length' ? '10' : 'application/json') },
  json: async () => structuredClone(data),
})
const noContent = () => Promise.resolve({ ok: true, status: 204, statusText: '',
  headers: { get: (k: string) => (k.toLowerCase() === 'content-length' ? '0' : null) }, json: async () => null })

type Reply = () => Promise<unknown>
let posts: { url: string; body: Record<string, unknown> }[]
let scanReply: (code: string) => Promise<unknown>
let hold: { release: () => void } | null

// The first scan can be held open so a second one queues behind it.
function maybeHeld(reply: Reply): Promise<unknown> {
  if (!hold) return reply()
  return new Promise<void>(r => { hold = { release: r } }).then(reply)
}

beforeEach(() => {
  vi.stubGlobal('AudioContext', undefined)
  posts = []; hold = null
})
afterEach(() => vi.unstubAllGlobals())

// ── Scan returns ─────────────────────────────────────────────────────────────

function returnsBackend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  if (method !== 'GET') posts.push({ url, body: opts.body ? JSON.parse(String(opts.body)) : {} })
  if (url.includes('/returns/awaiting-scan')) return json({ count: 0, items: [] })
  if (url.includes('/returns/sessions?')) return json({ items: [], total: 0 })
  if (url.endsWith('/returns/analytics')) return json({ totalReturns: 0, restockedCount: 0, damagedCount: 0, mismatchCount: 0,
    expectedNotScannedCount: 0, unassignedPendingCount: 0, unassignedPending: [] })
  if (url.endsWith('/returns/sessions') && method === 'POST') return json({ sessionId: 'rs-1' })
  if (url.endsWith('/returns/sessions/rs-1/scan')) {
    const code = JSON.parse(String(opts.body)).scan as string
    return maybeHeld(() => scanReply(code))
  }
  if (url.endsWith('/returns/sessions/rs-1/close')) return json({ sessionId: 'rs-1', pieceCount: 0, restockedCount: 0, damagedCount: 0,
    mismatchCount: 0, shipmentCount: 0, closedAt: '2026-10-04T09:00:00Z' })
  if (url.endsWith('/returns/sessions/rs-1') && method === 'DELETE') return noContent()
  if (url.endsWith('/returns/sessions/rs-1')) {
    return json({ id: 'rs-1', status: 'open', opened_by: 'u', opened_at: '2026-10-04T08:00:00Z', closed_by: null,
      closed_at: null, note: null, items: [], expectedPieces: [] })
  }
  return json({})
}

async function openReturns() {
  renderWithProviders(<Returns />)
  await userEvent.click(await screen.findByTestId('open-session-button'))
  return (await screen.findByTestId('scan-input')) as HTMLInputElement
}
const scanPosts = () => posts.filter(p => p.url.endsWith('/scan')).map(p => p.body.scan)

describe('R1 Scan returns', () => {
  beforeEach(() => {
    scanReply = () => noContent()
    stubFetchWithShellDefaults(vi.fn(returnsBackend))
  })

  test('rj1 internal whitespace / newlines are stripped before the POST; the input is cleared', async () => {
    const input = await openReturns()
    input.value = ' PC-12 34\n56 '
    await userEvent.type(input, '{Enter}')
    await waitFor(() => expect(scanPosts()).toEqual(['PC-123456']))
    expect(input.value).toBe('')
    expect(input).not.toBeDisabled()
  })

  test('rj2 a non-422 error shows its message; a 422 shows the rejected-scan toast with the cleaned code', async () => {
    scanReply = code => (code === 'BAD' ? json({ message_en: 'no match' }, 422) : json({ message_en: 'Boom from server' }, 500))
    const input = await openReturns()
    await userEvent.type(input, 'BAD{Enter}')
    expect(await screen.findByTestId('rejected-scan-toast')).toHaveTextContent('BAD')
    await userEvent.type(input, 'OTHER{Enter}')
    expect(await screen.findByText(/Boom from server/)).toBeInTheDocument()
    expect(input).not.toBeDisabled()
  })

  test('rj3 Close drops a scan still waiting in the queue (clearQueue) — it is never sent', async () => {
    hold = { release: () => {} }
    const input = await openReturns()
    await userEvent.type(input, 'FIRST{Enter}')
    await waitFor(() => expect(scanPosts()).toEqual(['FIRST']))
    await userEvent.type(input, 'SECOND{Enter}')                 // queued behind FIRST
    await userEvent.click(screen.getByTestId('close-session-button'))
    hold!.release()
    await waitFor(() => expect(posts.some(p => p.url.endsWith('/close'))).toBe(true))
    await new Promise(r => setTimeout(r, 50))
    expect(scanPosts()).toEqual(['FIRST'])
  })

  test('rj4 Abandon drops a scan still waiting in the queue (clearQueue) — it is never sent', async () => {
    hold = { release: () => {} }
    const input = await openReturns()
    await userEvent.type(input, 'FIRST{Enter}')
    await waitFor(() => expect(scanPosts()).toEqual(['FIRST']))
    await userEvent.type(input, 'SECOND{Enter}')
    await userEvent.click(screen.getByTestId('abandon-link'))
    const confirm = (await screen.findAllByRole('button', { name: /abandon/i })).pop()!
    await userEvent.click(confirm)
    hold!.release()
    await waitFor(() => expect(posts.some(p => p.url.endsWith('/returns/sessions/rs-1') && !p.url.endsWith('/scan'))).toBe(true))
    await new Promise(r => setTimeout(r, 50))
    expect(scanPosts()).toEqual(['FIRST'])
  })
})

// ── Pickups ──────────────────────────────────────────────────────────────────

const pickupSession = { id: 'pk-1', sessionStatus: 'open', scheduledDate: '2026-10-04', scheduledTimeSlot: null,
  scannedCount: 0, openedByName: 'Ahmed', createdAt: '2026-10-04T08:00:00Z', notes: null, closedByName: null,
  closedAt: null, scans: [] as unknown[] }
const accepted = (tn: string) => json({ outcome: 'ACCEPTED', entry: { shipmentId: `sh-${tn}`, trackingNumber: tn,
  orderNumber: '#1001', codAmount: 0, scannedAt: '2026-10-04T08:01:00Z', scannedByName: 'Ahmed' } })

function pickupsBackend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  if (method !== 'GET') posts.push({ url, body: opts.body ? JSON.parse(String(opts.body)) : {} })
  if (url.endsWith('/pickup-sessions') && method === 'GET') return json([pickupSession])
  if (url.endsWith('/pickup-sessions/pk-1') && method === 'GET') return json(pickupSession)
  if (url.endsWith('/pickup-sessions/pk-1/scans') && method === 'POST') {
    const tn = JSON.parse(String(opts.body)).trackingNumber as string
    return maybeHeld(() => scanReply(tn))
  }
  return json({})
}

async function openPickups() {
  renderWithProviders(<PickupSessions />)
  await waitFor(() => expect(document.querySelector('tr.tbl-row')).toBeTruthy())
  await userEvent.click(document.querySelector<HTMLElement>('tr.tbl-row')!)
  return (await screen.findByPlaceholderText('Scan or type AWB…')) as HTMLInputElement
}
const pickupPosts = () => posts.filter(p => p.url.endsWith('/scans')).map(p => p.body.trackingNumber)

describe('R1 Pickups', () => {
  beforeEach(() => {
    scanReply = tn => accepted(tn)
    stubFetchWithShellDefaults(vi.fn(pickupsBackend))
  })

  test('pj1 optimistic row while in flight → replaced by the real entry; "✓ Accepted" clears after 1.5 s; input cleared on Enter', async () => {
    hold = { release: () => {} }
    const input = await openPickups()
    await userEvent.type(input, '1234567890{Enter}')
    expect(input.value).toBe('')
    await waitFor(() => expect(document.querySelector('tr.opacity-50')).toHaveTextContent('1234567890'))
    hold!.release()
    expect(await screen.findByText('✓ Accepted')).toBeInTheDocument()
    await waitFor(() => expect(document.querySelector('tr.opacity-50')).toBeNull())
    expect(screen.getByText('#1001')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByText('✓ Accepted')).toBeNull(), { timeout: 2500 })
  })

  test('pj2 a rejected scan rolls the optimistic row back and shows the outcome banner (stays up)', async () => {
    scanReply = tn => json({ outcome: tn === '111' ? 'DUPLICATE' : 'UNKNOWN_AWB', entry: null })
    const input = await openPickups()
    await userEvent.type(input, '111{Enter}')
    expect(await screen.findByText('Already scanned in this session')).toBeInTheDocument()
    expect(document.querySelector('tr.opacity-50')).toBeNull()
    await userEvent.type(input, '222{Enter}')
    expect(await screen.findByText('AWB not found')).toBeInTheDocument()
    await new Promise(r => setTimeout(r, 1700))
    expect(screen.getByText('AWB not found')).toBeInTheDocument()
    expect(pickupPosts()).toEqual(['111', '222'])
  }, 6000)

  test('pj3 a network failure rolls back and shows "AWB not found"', async () => {
    scanReply = () => Promise.reject(new TypeError('Failed to fetch'))
    const input = await openPickups()
    await userEvent.type(input, '333{Enter}')
    expect(await screen.findByText('AWB not found')).toBeInTheDocument()
    expect(document.querySelector('tr.opacity-50')).toBeNull()
    expect(input).not.toBeDisabled()
  })

  test('pj4 Close session drops a scan still waiting in the queue (clearQueue) — it is never sent', async () => {
    const input = await openPickups()
    await userEvent.type(input, '100{Enter}')
    await screen.findByText('#1001')
    hold = { release: () => {} }
    await userEvent.type(input, '200{Enter}')
    await waitFor(() => expect(pickupPosts()).toEqual(['100', '200']))
    await userEvent.type(input, '300{Enter}')                     // queued behind 200
    await userEvent.click(screen.getByRole('button', { name: 'Close session' }))
    hold!.release()
    await screen.findByText('Confirm handover')
    await new Promise(r => setTimeout(r, 50))
    expect(pickupPosts()).toEqual(['100', '200'])
  })
})
