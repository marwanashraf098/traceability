import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import { useState } from 'react'
import userEvent from '@testing-library/user-event'
import { act, renderWithProviders, screen, waitFor } from './renderWithProviders'
import { useScanner, MAX_QUEUED_SCANS, ScanOutcome } from '../hooks/useScanner'
import { ScanShell } from '../components/ScanShell'

// useScanner / ScanShell — no lost scans (approved 2026-10-01). jsdom contract tests; the
// real-browser proof (focus loss, scanner bursts, latency) is in src/test-browser/.

type Pending = { code: string; resolve: (o: ScanOutcome) => void }

/** A screen built like StockTakeScan: one ScanShell + useScanner. onScan resolves by hand. */
function Harness({ onScan, extra, focusPaused = false }: {
  onScan: (code: string) => Promise<ScanOutcome>
  extra?: (s: ReturnType<typeof useScanner>) => JSX.Element
  focusPaused?: boolean
}) {
  const scanner = useScanner({ onScan, focusPaused })
  return (
    <div>
      <ScanShell scanner={scanner} placeholder="Scan" />
      {extra?.(scanner)}
    </div>
  )
}

function controlledOnScan() {
  const pending: Pending[] = []
  const calls: string[] = []
  let inFlight = 0
  let maxInFlight = 0
  const onScan = vi.fn((code: string) => {
    calls.push(code)
    inFlight++
    maxInFlight = Math.max(maxInFlight, inFlight)
    return new Promise<ScanOutcome>(resolve => {
      pending.push({ code, resolve: o => { inFlight--; resolve(o) } })
    })
  })
  /** Resolve the oldest in-flight scan and let React settle. */
  async function releaseNext() {
    await waitFor(() => expect(pending.length).toBeGreaterThan(0))
    await act(async () => { pending.shift()!.resolve({ success: true }) })
  }
  return { onScan, calls, pending, releaseNext, maxInFlight: () => maxInFlight }
}

beforeEach(() => {
  vi.stubGlobal('AudioContext', undefined)   // playBeep's silent fallback
})
afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('useScanner — no lost scans', () => {
  test('focus is never called on a disabled input after a scan, and the input ends focused', async () => {
    const focusCalls: boolean[] = []
    const realFocus = HTMLInputElement.prototype.focus
    vi.spyOn(HTMLInputElement.prototype, 'focus').mockImplementation(function (this: HTMLInputElement, opts?: FocusOptions) {
      focusCalls.push(this.disabled)
      return realFocus.call(this, opts)
    })
    const c = controlledOnScan()
    const user = userEvent.setup()
    renderWithProviders(<Harness onScan={c.onScan} />)
    const input = screen.getByPlaceholderText('Scan')

    await user.type(input, 'P000001{Enter}')
    expect(input).not.toBeDisabled()                       // in flight: still typeable
    expect(screen.getByTestId('scan-in-flight')).toBeInTheDocument()
    focusCalls.length = 0
    await c.releaseNext()

    expect(focusCalls.every(disabled => !disabled)).toBe(true)
    expect(input).toHaveFocus()
    expect(input).not.toBeDisabled()
  })

  test('scans typed while one is in flight are queued and processed in order, one at a time', async () => {
    const c = controlledOnScan()
    const user = userEvent.setup()
    renderWithProviders(<Harness onScan={c.onScan} />)
    const input = screen.getByPlaceholderText('Scan')

    await user.type(input, 'S1{Enter}')                    // first scan: held open
    await waitFor(() => expect(c.calls).toEqual(['S1']))
    for (const code of ['S2', 'S3', 'S4', 'S5', 'S6']) {
      await user.keyboard(`${code}{Enter}`)               // no clicks — like a scanner
    }
    expect(input).toHaveValue('')
    expect(screen.getByTestId('scan-pending')).toHaveTextContent('5 scans waiting')
    expect(c.calls).toEqual(['S1'])                         // nothing runs in parallel

    for (let i = 0; i < 6; i++) await c.releaseNext()

    expect(c.calls).toEqual(['S1', 'S2', 'S3', 'S4', 'S5', 'S6'])
    expect(c.maxInFlight()).toBe(1)
    expect(screen.queryByTestId('scan-pending')).not.toBeInTheDocument()
    expect(input).toHaveFocus()
  })

  test(`queue cap: the scan after ${MAX_QUEUED_SCANS} waiting is refused visibly, never processed`, async () => {
    const c = controlledOnScan()
    const user = userEvent.setup()
    renderWithProviders(<Harness onScan={c.onScan} />)
    const input = screen.getByPlaceholderText('Scan')

    await user.type(input, 'Q0{Enter}')                    // in flight
    await waitFor(() => expect(c.calls).toEqual(['Q0']))
    for (let i = 1; i <= MAX_QUEUED_SCANS; i++) await user.keyboard(`Q${i}{Enter}`)
    expect(screen.queryByTestId('scan-queue-full')).not.toBeInTheDocument()

    await user.keyboard('OVERFLOW{Enter}')
    expect(screen.getByTestId('scan-queue-full')).toHaveTextContent('Too many scans waiting — slow down')
    expect(screen.getByTestId('scan-pending')).toHaveTextContent(`${MAX_QUEUED_SCANS} scans waiting`)

    for (let i = 0; i <= MAX_QUEUED_SCANS; i++) await c.releaseNext()
    expect(c.calls).toHaveLength(MAX_QUEUED_SCANS + 1)
    expect(c.calls).not.toContain('OVERFLOW')
  })

  test('clearQueue drops waiting scans; the one in flight still finishes', async () => {
    const c = controlledOnScan()
    const user = userEvent.setup()
    renderWithProviders(<Harness onScan={c.onScan}
      extra={s => <button type="button" onClick={s.clearQueue}>clear</button>} />)
    const input = screen.getByPlaceholderText('Scan')

    await user.type(input, 'W1{Enter}')
    await waitFor(() => expect(c.calls).toEqual(['W1']))
    await user.keyboard('W2{Enter}W3{Enter}')
    await user.click(screen.getByRole('button', { name: 'clear' }))
    expect(screen.queryByTestId('scan-pending')).not.toBeInTheDocument()

    await c.releaseNext()
    await new Promise(r => setTimeout(r, 20))
    expect(c.calls).toEqual(['W1'])
  })

  test('unmounting drops waiting scans', async () => {
    const c = controlledOnScan()
    const user = userEvent.setup()
    const { unmount } = renderWithProviders(<Harness onScan={c.onScan} />)
    await user.type(screen.getByPlaceholderText('Scan'), 'U1{Enter}')
    await waitFor(() => expect(c.calls).toEqual(['U1']))
    await user.keyboard('U2{Enter}U3{Enter}')
    unmount()
    await act(async () => { c.pending.shift()!.resolve({ success: true }) })
    await new Promise(r => setTimeout(r, 20))
    expect(c.calls).toEqual(['U1'])
  })

  test('each scan uses the latest onScan (state changed by the previous scan is seen)', async () => {
    // A screen whose onScan depends on what the previous scan did (like the pack session:
    // a waybill opens an order, the next scan is a piece).
    const seen: string[] = []
    function Stateful() {
      const [mode, setMode] = useState<'waybill' | 'piece'>('waybill')
      const scanner = useScanner({
        onScan: async (code) => {
          seen.push(`${mode}:${code}`)
          if (mode === 'waybill') setMode('piece')
          await new Promise(r => setTimeout(r, 5))
          return { success: true }
        },
      })
      return <ScanShell scanner={scanner} placeholder="Scan" />
    }
    const user = userEvent.setup()
    renderWithProviders(<Stateful />)
    await user.type(screen.getByPlaceholderText('Scan'), 'WB{Enter}')
    await user.keyboard('P1{Enter}P2{Enter}')
    await waitFor(() => expect(seen).toHaveLength(3))
    expect(seen).toEqual(['waybill:WB', 'piece:P1', 'piece:P2'])
  })

  test('never steals focus from another text field, nor while focus is paused', async () => {
    const c = controlledOnScan()
    const user = userEvent.setup()
    function WithField({ paused }: { paused: boolean }) {
      return <Harness onScan={c.onScan} focusPaused={paused} extra={() => <input aria-label="qty" />} />
    }
    const { rerender } = renderWithProviders(<WithField paused={false} />)
    await user.type(screen.getByPlaceholderText('Scan'), 'F1{Enter}')
    await waitFor(() => expect(c.calls).toEqual(['F1']))
    const qty = screen.getByLabelText('qty')
    act(() => qty.focus())
    await c.releaseNext()
    expect(qty).toHaveFocus()                                // a finishing scan didn't yank the cursor

    // A scan finishing while a dialog is open (focusPaused) doesn't pull focus back either.
    await user.type(screen.getByPlaceholderText('Scan'), 'F2{Enter}')
    await waitFor(() => expect(c.calls).toEqual(['F1', 'F2']))
    rerender(<WithField paused={true} />)
    act(() => (document.activeElement as HTMLElement).blur())
    await c.releaseNext()
    expect(screen.getByPlaceholderText('Scan')).not.toHaveFocus()
    // …and once the dialog closes, focus comes back.
    rerender(<WithField paused={false} />)
    expect(screen.getByPlaceholderText('Scan')).toHaveFocus()
  })
})
