import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import { useState } from 'react'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import userEvent from '@testing-library/user-event'
import { fireEvent, renderWithProviders, screen, waitFor } from './renderWithProviders'
import { useScanner, ScanOutcome } from '../hooks/useScanner'
import { ScanShell } from '../components/ScanShell'
import { insertAtCaret, scanKeyCharFor } from '../hooks/scanKeys'
import macArabic from './fixtures/keyboardLayouts/macos-Arabic.json'
import macArabicPc from './fixtures/keyboardLayouts/macos-ArabicPC.json'

// S2 (approved 2026-10-17) — a USB / Bluetooth scanner is a keyboard. The tables in
// fixtures/keyboardLayouts were read from macOS itself (UCKeyTranslate, Step 0): what each
// physical key types on "Arabic" and "Arabic - PC", with and without Shift / Caps Lock.
// Windows "Arabic (101)" (KBDA1) has the Arabic - PC letter and Shift layers with a Latin
// number row. Each scan below is the exact keydown sequence a scanner produces — `code` + Shift
// from the US position, `key` from the layout — and must arrive as the Latin label code.

type Cell = { plain: string; shift: string; caps: string; shiftCaps: string }
type Layout = Record<string, Cell>

/** What a HID scanner sends for each character: the US physical key, and whether Shift is held. */
const SCANNER: Record<string, [string, boolean]> = { ' ': ['Space', false], '-': ['Minus', false], '_': ['Minus', true] }
for (const c of 'ABCDEFGHIJKLMNOPQRSTUVWXYZ') { SCANNER[c] = ['Key' + c, true]; SCANNER[c.toLowerCase()] = ['Key' + c, false] }
for (const d of '0123456789') SCANNER[d] = ['Digit' + d, false]

const US: Layout = {}
for (const c of 'ABCDEFGHIJKLMNOPQRSTUVWXYZ') US['Key' + c] = { plain: c.toLowerCase(), shift: c, caps: c, shiftCaps: c.toLowerCase() }
for (const d of '0123456789') US['Digit' + d] = { plain: d, shift: ')!@#$%^&*('[+d], caps: d, shiftCaps: ')!@#$%^&*('[+d] }
US.Minus = { plain: '-', shift: '_', caps: '-', shiftCaps: '_' }
US.Space = { plain: ' ', shift: ' ', caps: ' ', shiftCaps: ' ' }

const WIN_101: Layout = JSON.parse(JSON.stringify(macArabicPc))
for (const d of '0123456789') { WIN_101['Digit' + d].plain = d; WIN_101['Digit' + d].caps = d }

const SCENARIOS: [string, Layout, boolean][] = [
  ['US, Caps Lock on', US, true],
  ['Windows Arabic 101', WIN_101, false],
  ['Windows Arabic 101 + Caps Lock', WIN_101, true],
  ['macOS Arabic', macArabic as Layout, false],
  ['macOS Arabic + Caps Lock', macArabic as Layout, true],
  ['macOS Arabic - PC', macArabicPc as Layout, false],
  ['macOS Arabic - PC + Caps Lock', macArabicPc as Layout, true],
]

const ULID = '01HZX4K7M9QW2E3R5T6Y8V0BNS'
/** Every ❌ shape from Step 0 §2. */
const STEP0_CODES = ['P000123', `PC-${ULID}`, ULID, 'BOSTA_8484805699', '8484805699', 'G - 0 2 - 8 4 8 4 8 0 5 6 9 9']

/** The canonical Latin codes of the shared backend vectors (shared/scan-normalization-vectors.json):
 *  what S1's normalizer resolves to is exactly what S2 must capture. */
const SHARED_TARGETS: string[] = (() => {
  const doc = JSON.parse(readFileSync(resolve(__dirname, '../../../shared/scan-normalization-vectors.json'), 'utf8'))
  const canonical = /^(P[0-9]{6,}|PC-[0-9A-HJKMNP-TV-Z]{26}|[0-9A-HJKMNP-TV-Z]{26}|[0-9]+)$/
  return [...new Set<string>(doc.vectors.map((v: { piece: string }) => v.piece).filter((p: string) => canonical.test(p)))]
})()

function Harness({ onScan }: { onScan: (c: string) => Promise<ScanOutcome> }) {
  const scanner = useScanner({ onScan })
  return <ScanShell scanner={scanner} placeholder="Scan" />
}

/** The key one scanner keystroke produces on this layout ('' = the layout types nothing). */
function layoutKey(layout: Layout, caps: boolean, ch: string): { code: string; shift: boolean; key: string } {
  const [code, shift] = SCANNER[ch]
  const cell = layout[code]
  const out = caps ? (shift ? cell.shiftCaps : cell.caps) : (shift ? cell.shift : cell.plain)
  return { code, shift, key: out }
}

/** Presses one key on the focused input the way a browser does: keydown, then — unless the
 *  keydown was default-prevented — the layout's text is inserted. */
async function press(input: HTMLInputElement, k: { code: string; shift: boolean; key: string }) {
  if (k.key === '' || [...k.key].length > 1) {
    // A key the layout maps to nothing ("Unidentified") or to a ligature ("لإ", one keydown, two
    // code points): user-event can't insert either, so the browser's default is applied by hand.
    const key = k.key === '' ? 'Unidentified' : k.key
    const notPrevented = fireEvent.keyDown(input, { code: k.code, key, shiftKey: k.shift })
    if (notPrevented && k.key !== '') {
      input.value += k.key
      fireEvent.input(input, { data: k.key, inputType: 'insertText' })
    }
    fireEvent.keyUp(input, { code: k.code, key, shiftKey: k.shift })
    return
  }
  const user = userEvent.setup({ keyboardMap: [{ code: k.code, key: k.key }, { code: 'ShiftLeft', key: 'Shift' }] })
  await user.keyboard(k.shift ? `{Shift>}[${k.code}]{/Shift}` : `[${k.code}]`)
}

async function scanThrough(layout: Layout, caps: boolean, code: string): Promise<string> {
  const got: string[] = []
  const r = renderWithProviders(<Harness onScan={async c => { got.push(c); return { success: true } }} />)
  const input = screen.getByPlaceholderText('Scan') as HTMLInputElement
  input.focus()
  for (const ch of code) await press(input, layoutKey(layout, caps, ch))
  await userEvent.setup().keyboard('{Enter}')
  await waitFor(() => expect(got).toHaveLength(1))
  r.unmount()
  return got[0]
}

beforeEach(() => { vi.stubGlobal('AudioContext', undefined) })
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

describe('scan capture is independent of Caps Lock and the keyboard layout', () => {
  for (const [name, layout, caps] of SCENARIOS) {
    describe(name, () => {
      test.each(STEP0_CODES)('%s', async code => {
        expect(await scanThrough(layout, caps, code)).toBe(code)
      })
      test('every canonical code of the shared backend vectors', async () => {
        expect(SHARED_TARGETS).toEqual(expect.arrayContaining(['P000123', 'P1000000', `PC-${ULID}`, ULID, '8484805699']))
        for (const code of SHARED_TARGETS) expect(await scanThrough(layout, caps, code)).toBe(code)
      })
    })
  }

  test('US layout, Caps Lock off — unchanged control', async () => {
    for (const code of STEP0_CODES) expect(await scanThrough(US, false, code)).toBe(code)
  })
})

describe('what the listener leaves to the browser', () => {
  test('Alt+numpad character entry is not intercepted; the composed characters still land', async () => {
    const got: string[] = []
    renderWithProviders(<Harness onScan={async c => { got.push(c); return { success: true } }} />)
    const input = screen.getByPlaceholderText('Scan') as HTMLInputElement
    input.focus()
    const prevented: boolean[] = []
    // Windows Alt codes: Alt held, digits on the numpad, the OS delivers the character on Alt up.
    for (const [ch, digits] of [['P', '080'], ['0', '048'], ['0', '048'], ['0', '048'], ['1', '049'], ['2', '050'], ['3', '051']] as const) {
      prevented.push(!fireEvent.keyDown(input, { code: 'AltLeft', key: 'Alt', altKey: true }))
      for (const d of digits) {
        prevented.push(!fireEvent.keyDown(input, { code: 'Numpad' + d, key: d, altKey: true }))
        fireEvent.keyUp(input, { code: 'Numpad' + d, key: d, altKey: true })
      }
      fireEvent.keyUp(input, { code: 'AltLeft', key: 'Alt' })
      input.value += ch                                         // the OS-composed character
      fireEvent.input(input, { data: ch, inputType: 'insertText' })
    }
    expect(prevented.every(p => !p)).toBe(true)
    expect(input.value).toBe('P000123')                         // no numpad digit of ours mixed in
    await userEvent.setup().keyboard('{Enter}')
    await waitFor(() => expect(got).toEqual(['P000123']))
  })

  test('named keys, shortcuts and composition fall through', () => {
    const ev = (init: KeyboardEventInit & { keyCode?: number }) => new KeyboardEvent('keydown', init)
    expect(scanKeyCharFor(ev({ code: 'KeyP', key: 'ح' }))).toBe('p')
    expect(scanKeyCharFor(ev({ code: 'KeyP', key: '؛', shiftKey: true }))).toBe('P')
    expect(scanKeyCharFor(ev({ code: 'KeyF', key: 'Unidentified', shiftKey: true }))).toBe('F')   // macOS Arabic Shift+F
    expect(scanKeyCharFor(ev({ code: 'KeyT', key: 't', shiftKey: true }))).toBe('T')            // Caps Lock + Shift
    expect(scanKeyCharFor(ev({ code: 'KeyT', key: 'T' }))).toBe('T')                             // Caps-compensating scanner
    expect(scanKeyCharFor(ev({ code: 'KeyT', key: 'ف' }))).toBe('t')                             // Arabic, no Shift
    expect(scanKeyCharFor(ev({ code: 'KeyQ', key: 'A' }))).toBe('q')                             // another key's letter never leaks in
    expect(scanKeyCharFor(ev({ code: 'Numpad7', key: '7' }))).toBe('7')
    expect(scanKeyCharFor(ev({ code: 'Numpad7', key: 'Home' }))).toBeNull()                       // NumLock off
    for (const [code, key] of [['Enter', 'Enter'], ['NumpadEnter', 'Enter'], ['Tab', 'Tab'], ['Backspace', 'Backspace'],
                               ['Delete', 'Delete'], ['ArrowLeft', 'ArrowLeft'], ['Escape', 'Escape'], ['F2', 'F2']]) {
      expect(scanKeyCharFor(ev({ code, key }))).toBeNull()
    }
    expect(scanKeyCharFor(ev({ code: 'KeyV', key: 'ر', ctrlKey: true }))).toBeNull()
    expect(scanKeyCharFor(ev({ code: 'KeyV', key: 'v', metaKey: true }))).toBeNull()
    expect(scanKeyCharFor(ev({ code: 'KeyV', key: 'ر', altKey: true }))).toBeNull()
    expect(scanKeyCharFor(ev({ code: 'KeyP', key: 'ح', isComposing: true }))).toBeNull()
    expect(scanKeyCharFor(ev({ code: '', key: 'Unidentified', keyCode: 229 }))).toBeNull()       // virtual keyboard
    expect(scanKeyCharFor(ev({ code: 'KeyP', key: 'Process', keyCode: 229 }))).toBeNull()
    expect(scanKeyCharFor(ev({ code: 'IntlBackslash', key: '<' }))).toBeNull()                   // unmapped
  })

  test('Backspace still edits the scan field; the caret and selection are respected', async () => {
    const got: string[] = []
    renderWithProviders(<Harness onScan={async c => { got.push(c); return { success: true } }} />)
    const input = screen.getByPlaceholderText('Scan') as HTMLInputElement
    input.focus()
    const user = userEvent.setup()
    await user.keyboard('{Shift>}P{/Shift}0001x{Backspace}23')   // a keyboard holds Shift for 'P'
    expect(input.value).toBe('P000123')
    input.setSelectionRange(1, 4)                                // select "000"
    await user.keyboard('999')
    expect(input.value).toBe('P999123')
  })

  test('only the scanner\'s own input is captured — another field on the page keeps Arabic typing', async () => {
    function Page() {
      const scanner = useScanner({ onScan: async () => ({ success: true }) })
      const [note, setNote] = useState('')
      return (
        <div>
          <ScanShell scanner={scanner} placeholder="Scan" />
          <input aria-label="note" value={note} onChange={e => setNote(e.target.value)} />
        </div>
      )
    }
    renderWithProviders(<Page />)
    const note = screen.getByLabelText('note') as HTMLInputElement
    note.focus()
    const user = userEvent.setup({ keyboardMap: [
      { code: 'Semicolon', key: 'ك' }, { code: 'KeyH', key: 'ا' }, { code: 'KeyL', key: 'م' }] })
    await user.keyboard('[Semicolon][KeyH][KeyL]')
    expect(note.value).toBe('كام')
  })

  test('insertAtCaret keeps a controlled React input in sync and respects maxLength', async () => {
    function Controlled() {
      const [v, setV] = useState('')
      return <input aria-label="c" maxLength={3} value={v} onChange={e => setV(e.target.value)} />
    }
    renderWithProviders(<Controlled />)
    const input = screen.getByLabelText('c') as HTMLInputElement
    input.focus()
    for (const ch of 'AB') insertAtCaret(input, ch)
    await waitFor(() => expect(input.value).toBe('AB'))
    insertAtCaret(input, 'C')
    insertAtCaret(input, 'D')                                    // over maxLength — ignored
    await waitFor(() => expect(input.value).toBe('ABC'))
  })
})
