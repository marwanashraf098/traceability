import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { renderWithProviders, screen, waitFor } from './renderWithProviders'
import * as api from '../api'
import ScanPairPage from '../pages/scanpair/ScanPairPage'

// S6 — the phone page (/scan/:pairCode): claims the code once (device secret in sessionStorage),
// shows whose station / which order, sends each read once with an increasing seq and shows the
// tablet's verdict — or exactly what went wrong; never queues or retries silently.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, claimScanPair: vi.fn(), sendPhoneScan: vi.fn(), getPhoneScan: vi.fn(), getPhoneStatus: vi.fn() }
})

// The camera: a button per code that "reads" it.
vi.mock('../pages/scanpair/CameraReader', () => ({
  default: ({ onRead }: { onRead: (c: string) => void }) => (
    <div data-testid="camera">
      {['D-07-74821903', 'P000001', 'BOSTA_8484805699'].map(c => (
        <button key={c} onClick={() => onRead(c)}>read {c}</button>
      ))}
    </div>
  ),
}))

const CONTEXT: api.PhoneContext = { state: 'connected', workerName: 'Ahmed', expiresAt: '2026-10-02T19:00:00Z',
  order: { number: '#1047', customerName: 'Youssef Adel', scanned: 1, required: 2 } }

let storage: Map<string, string>

function renderAt(code = 'PAIRCODE123') {
  return renderWithProviders(<Routes><Route path="/scan/:pairCode" element={<ScanPairPage />} /></Routes>,
    { initialEntries: [`/scan/${code}`] })
}

beforeEach(() => {
  storage = new Map()
  vi.stubGlobal('sessionStorage', {
    getItem: (k: string) => storage.get(k) ?? null,
    setItem: (k: string, v: string) => { storage.set(k, v) },
    removeItem: (k: string) => { storage.delete(k) },
  })
  vi.stubGlobal('AudioContext', undefined)
  Object.defineProperty(navigator, 'vibrate', { value: vi.fn(), configurable: true })
  vi.mocked(api.claimScanPair).mockResolvedValue({ deviceSecret: 'secret-1', context: CONTEXT })
  vi.mocked(api.getPhoneStatus).mockResolvedValue(CONTEXT)
  vi.mocked(api.sendPhoneScan).mockResolvedValue({ eventId: 'ev-1' })
})
afterEach(() => { vi.clearAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers() })

async function startScanning() {
  const user = userEvent.setup()
  renderAt()
  await user.click(await screen.findByTestId('start-scanning'))
  await screen.findByTestId('camera')
  return user
}

describe('pairing', () => {
  test('claims the code once, keeps the device secret for this tab, and shows whose station + the order', async () => {
    renderAt()
    expect(await screen.findByText("Connected to Ahmed's station")).toBeInTheDocument()
    expect(screen.getByTestId('pair-order')).toHaveTextContent('Order #1047 · Youssef Adel · 1/2 pieces')
    expect(api.claimScanPair).toHaveBeenCalledWith('PAIRCODE123')
    expect(storage.get('scanPair.secret.PAIRCODE123')).toBe('secret-1')
  })

  test('a reload with the secret stored doesn\'t claim again', async () => {
    storage.set('scanPair.secret.PAIRCODE123', 'secret-1')
    renderAt()
    expect(await screen.findByText("Connected to Ahmed's station")).toBeInTheDocument()
    expect(api.claimScanPair).not.toHaveBeenCalled()
    expect(api.getPhoneStatus).toHaveBeenCalledWith('secret-1')
  })

  test('a used / expired code: says so, and to scan the tablet\'s QR again', async () => {
    vi.mocked(api.claimScanPair).mockRejectedValue(new api.PhonePairError('ended', 'ended'))
    renderAt()
    expect(await screen.findByTestId('pair-ended')).toHaveTextContent('This code was already used or has expired')
    expect(screen.getByTestId('pair-ended')).toHaveTextContent('Scan the QR code on the tablet again')
  })
})

describe('reads', () => {
  test('accepted: sent once with an increasing seq, green banner with the tablet\'s message, short vibration', async () => {
    vi.mocked(api.getPhoneScan).mockResolvedValueOnce({ eventId: 'ev-1', status: 'delivered', message: null })
      .mockResolvedValue({ eventId: 'ev-1', status: 'accepted', message: 'Order #1047 opened' })
    const user = await startScanning()
    await user.click(screen.getByText('read D-07-74821903'))
    const banner = await screen.findByTestId('pair-result')
    await waitFor(() => expect(banner).toHaveAttribute('data-kind', 'accepted'))
    expect(banner).toHaveTextContent('Order #1047 opened')
    expect(navigator.vibrate).toHaveBeenCalledWith(60)
    const [secret, seq1, code] = vi.mocked(api.sendPhoneScan).mock.calls[0]
    expect([secret, code]).toEqual(['secret-1', 'D-07-74821903'])

    vi.mocked(api.sendPhoneScan).mockResolvedValue({ eventId: 'ev-2' })
    await user.click(screen.getByText('read P000001'))
    await waitFor(() => expect(api.sendPhoneScan).toHaveBeenCalledTimes(2))
    expect(vi.mocked(api.sendPhoneScan).mock.calls[1][1]).toBeGreaterThan(seq1)
  })

  test('rejected: red banner with the tablet\'s message, long vibration', async () => {
    vi.mocked(api.getPhoneScan).mockResolvedValue({ eventId: 'ev-1', status: 'rejected', message: 'Waybill not found' })
    const user = await startScanning()
    await user.click(screen.getByText('read D-07-74821903'))
    await waitFor(() => expect(screen.getByTestId('pair-result')).toHaveAttribute('data-kind', 'rejected'))
    expect(screen.getByTestId('pair-result')).toHaveTextContent('Waybill not found')
    expect(navigator.vibrate).toHaveBeenCalledWith([220, 90, 220])
  })

  test('the same code within 2 s is sent once', async () => {
    vi.mocked(api.getPhoneScan).mockResolvedValue({ eventId: 'ev-1', status: 'accepted', message: 'Cargo pants 2/2' })
    const user = await startScanning()
    await user.click(screen.getByText('read P000001'))
    await waitFor(() => expect(screen.getByTestId('pair-result')).toHaveAttribute('data-kind', 'accepted'))
    await user.click(screen.getByText('read P000001'))
    await new Promise(r => setTimeout(r, 50))
    expect(api.sendPhoneScan).toHaveBeenCalledTimes(1)
  })

  test('no verdict within 4 s → "Not confirmed — check the tablet"', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    vi.mocked(api.getPhoneScan).mockResolvedValue({ eventId: 'ev-1', status: 'delivered', message: null })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderAt()
    await user.click(await screen.findByTestId('start-scanning'))
    await user.click(await screen.findByText('read P000001'))
    await vi.advanceTimersByTimeAsync(4500)
    await waitFor(() => expect(screen.getByTestId('pair-result')).toHaveAttribute('data-kind', 'unconfirmed'))
    expect(screen.getByTestId('pair-result')).toHaveTextContent('Not confirmed — check the tablet')
    expect(api.sendPhoneScan).toHaveBeenCalledTimes(1)                    // not retried
  })

  test('a send that fails on the network → "Not sent — scan again", nothing retried', async () => {
    vi.mocked(api.sendPhoneScan).mockRejectedValue(new api.PhonePairError('network', 'network'))
    const user = await startScanning()
    await user.click(screen.getByText('read P000001'))
    await waitFor(() => expect(screen.getByTestId('pair-result')).toHaveAttribute('data-kind', 'notSent'))
    expect(screen.getByTestId('pair-result')).toHaveTextContent('Not sent — scan again')
    expect(api.sendPhoneScan).toHaveBeenCalledTimes(1)
    expect(api.getPhoneScan).not.toHaveBeenCalled()
  })

  test('the pairing ended (401) → "Disconnected", the stored secret is dropped', async () => {
    vi.mocked(api.sendPhoneScan).mockRejectedValue(new api.PhonePairError('ended', 'ended'))
    const user = await startScanning()
    await user.click(screen.getByText('read P000001'))
    expect(await screen.findByTestId('pair-ended')).toHaveTextContent('Disconnected — pairing ended')
    expect(storage.has('scanPair.secret.PAIRCODE123')).toBe(false)
  })
})
