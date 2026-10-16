import { describe, expect, test, vi, beforeEach, afterEach } from 'vitest'
import { commands, server } from 'vitest/browser'
import { cleanup } from '@testing-library/react'
import { renderWithProviders } from '../test/renderWithProviders'
import { useScanner, ScanOutcome } from '../hooks/useScanner'
import { ScanShell } from '../components/ScanShell'
import macArabic from '../test/fixtures/keyboardLayouts/macos-Arabic.json'
import macArabicPc from '../test/fixtures/keyboardLayouts/macos-ArabicPC.json'

// S2 in a real browser: three scans back to back, 2 ms per key, sent through Chromium's own
// input pipeline with Arabic-layout key events (code = the US position, key/text = what the
// layout types — ligatures, Arabic-Indic digits, and keys that type nothing). The scan worker
// takes ~150 ms per scan, so the 2nd and 3rd scans are typed while the 1st is in flight.
// All three must arrive complete, in order, one at a time.

type Cell = { plain: string; shift: string; caps: string; shiftCaps: string }
type Layout = Record<string, Cell>
type LayoutKey = { code: string; key: string; shift: boolean; vk: number }

const WIN_101: Layout = JSON.parse(JSON.stringify(macArabicPc))
for (const d of '0123456789') { WIN_101['Digit' + d].plain = d; WIN_101['Digit' + d].caps = d }

function keysFor(layout: Layout, code: string): LayoutKey[] {
  return [...code].map(ch => {
    if (ch === '-') return { code: 'Minus', key: layout.Minus.plain, shift: false, vk: 189 }
    if (ch === '_') return { code: 'Minus', key: layout.Minus.shift, shift: true, vk: 189 }
    if (/[0-9]/.test(ch)) return { code: 'Digit' + ch, key: layout['Digit' + ch].plain, shift: false, vk: ch.charCodeAt(0) }
    return { code: 'Key' + ch, key: layout['Key' + ch].shift, shift: true, vk: ch.charCodeAt(0) }
  })
}

const SCANS: [Layout, string][] = [
  [macArabic as Layout, 'PC-01HZX4K7M9QW2E3R5T6Y8V0BNS'],     // macOS Arabic: Shift+X types nothing, ٠-٩ digits
  [macArabicPc as Layout, 'BOSTA_8484805699'],                // ligatures (B → لآ, T → لإ), ٠-٩ digits
  [WIN_101, 'P000123'],                                       // ؛ for P
]

function Harness({ onScan }: { onScan: (c: string) => Promise<ScanOutcome> }) {
  const scanner = useScanner({ onScan })
  return <ScanShell scanner={scanner} placeholder="Scan" />
}

beforeEach(() => {
  vi.stubGlobal('AudioContext', undefined)
  vi.stubEnv('VITE_SCAN_KEY_CAPTURE', 'true')                 // the build flag that turns capture on
})
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.unstubAllEnvs() })

describe('Arabic-layout scanner burst', () => {
  test.skipIf(server.browser !== 'chromium')('3 scans at 2 ms per key arrive complete, in order, one at a time', async () => {
    const seen: string[] = []
    let inFlight = 0, maxInFlight = 0
    renderWithProviders(<Harness onScan={async c => {
      seen.push(c)
      inFlight++; maxInFlight = Math.max(maxInFlight, inFlight)
      await new Promise(r => setTimeout(r, 150))
      inFlight--
      return { success: true }
    }} />)
    await expect.poll(() => document.querySelector('input.input-scan')).toBeTruthy()
    document.querySelector<HTMLInputElement>('input.input-scan')!.focus()

    // The scans really contain a key the layout types nothing for, and ligature keys.
    expect(keysFor(macArabic as Layout, 'PC-01HZX4K7M9QW2E3R5T6Y8V0BNS').some(k => k.key === '')).toBe(true)
    expect(keysFor(macArabicPc as Layout, 'BOSTA_8484805699').some(k => [...k.key].length > 1)).toBe(true)

    await commands.layoutBurst(SCANS.map(([layout, code]) => keysFor(layout, code)), 2)

    await expect.poll(() => seen.length, { timeout: 10_000, interval: 50 }).toBe(3)
    await new Promise(r => setTimeout(r, 300))
    expect(seen).toEqual(SCANS.map(([, code]) => code))
    expect(maxInFlight).toBe(1)
  })
})
