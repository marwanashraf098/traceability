// S2 (approved 2026-10-17) — keyboard-layout- and Caps-Lock-independent scan capture.
//
// A USB / Bluetooth barcode scanner is a keyboard: it sends physical key positions
// (KeyboardEvent.code) plus Shift for upper case and symbols. The character the browser
// inserts (KeyboardEvent.key) is decided by the OS layout and Caps Lock, so the same label
// arrives as "p000123" (Caps Lock), "؛000123" (Windows Arabic 101) or "[٠٠٠١٢٣" (macOS
// Arabic). Scan inputs therefore take the character from `code` + Shift through a fixed US
// table, whatever the layout and Caps Lock say (a reported upper-case Latin letter may only
// raise a letter's case — see scanKeyCharFor).
//
// Only a key the table knows is captured. The browser's default runs untouched for:
// Ctrl / Cmd / Alt held (shortcuts, Alt+numpad character entry, AltGr), IME composition
// (isComposing, keyCode 229 — virtual keyboards), an empty or unknown `code`, and named
// keys (Enter, Tab, Backspace, arrows, Escape, NumLock-off numpad keys, …) — so Enter
// detection and every existing handler see exactly what they saw before.

/** code → [without Shift, with Shift] on a US keyboard. Caps Lock plays no part. */
const US_KEYS: Readonly<Record<string, readonly [string, string]>> = (() => {
  const t: Record<string, readonly [string, string]> = {}
  for (const c of 'ABCDEFGHIJKLMNOPQRSTUVWXYZ') t['Key' + c] = [c.toLowerCase(), c]
  const shiftedDigits = ')!@#$%^&*('
  for (let d = 0; d <= 9; d++) {
    t['Digit' + d] = [String(d), shiftedDigits[d]]
    t['Numpad' + d] = [String(d), String(d)]
  }
  Object.assign(t, {
    NumpadSubtract: ['-', '-'], NumpadAdd: ['+', '+'], NumpadMultiply: ['*', '*'],
    NumpadDivide: ['/', '/'], NumpadDecimal: ['.', '.'],
    Minus: ['-', '_'], Equal: ['=', '+'],
    BracketLeft: ['[', '{'], BracketRight: [']', '}'], Backslash: ['\\', '|'],
    Semicolon: [';', ':'], Quote: ["'", '"'],
    Comma: [',', '<'], Period: ['.', '>'], Slash: ['/', '?'],
    Backquote: ['`', '~'], Space: [' ', ' '],
  })
  return t
})()

/** The US character for a physical key, or null when the key isn't in the table. */
export function usKeyChar(code: string, shiftKey: boolean): string | null {
  const pair = US_KEYS[code]
  return pair ? pair[shiftKey ? 1 : 0] : null
}

/**
 * A named key value ("Enter", "ArrowLeft", "F2", "Shift", "End" from a NumLock-off numpad…):
 * ASCII, more than one character, starting with a capital. "Unidentified" and "Dead" are not
 * treated as named — a layout that produces no character for a key (macOS Arabic Shift+F)
 * reports one of them, and the physical key is still known.
 * A multi-character layout output such as the ligature "لإ" (Arabic Shift+T) is not named.
 */
function isNamedKey(key: string): boolean {
  if (key === 'Unidentified' || key === 'Dead') return false
  return key.length > 1 && /^[A-Z][A-Za-z0-9]*$/.test(key)
}

/**
 * The character to insert for this keydown, or null to leave the browser default alone.
 *
 * A letter is upper case when Shift is held, or when the browser itself reports that key's
 * upper-case Latin letter. The second half covers a scanner that compensates for Caps Lock
 * (it sends the letter WITHOUT Shift while Caps Lock is on and expects upper case); it can
 * only ever raise a letter to upper case, so Caps Lock can't lower a Shift+letter scan, and
 * on a non-Latin layout (Arabic glyphs) Shift alone decides.
 */
export function scanKeyCharFor(e: KeyboardEvent): string | null {
  if (e.ctrlKey || e.metaKey || e.altKey) return null
  if (e.isComposing || e.keyCode === 229) return null
  if (!e.code) return null
  if (isNamedKey(e.key)) return null
  const ch = usKeyChar(e.code, e.shiftKey)
  if (ch !== null && /^Key[A-Z]$/.test(e.code) && e.key === e.code.slice(3)) return e.key
  return ch
}

const nativeValueSetter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set

/**
 * Inserts `text` at the caret / over the selection the way typing would: respects maxLength,
 * writes through the native value setter and fires a bubbling 'input' event, so an
 * uncontrolled input reads the new value on Enter and a controlled React input's onChange
 * sees it too.
 */
export function insertAtCaret(input: HTMLInputElement, text: string): void {
  const value = input.value
  const start = input.selectionStart ?? value.length
  const end = input.selectionEnd ?? value.length
  if (input.maxLength >= 0 && value.length - (end - start) + text.length > input.maxLength) return
  const next = value.slice(0, start) + text + value.slice(end)
  if (nativeValueSetter) nativeValueSetter.call(input, next)
  else input.value = next
  const caret = start + text.length
  try { input.setSelectionRange(caret, caret) } catch { /* input types without a selection */ }
  input.dispatchEvent(new InputEvent('input', { bubbles: true, data: text, inputType: 'insertText' }))
}

/**
 * Handles one keydown aimed at a scan input: when the key is in the US table, the layout's
 * character is replaced by the table's. Returns whether it handled the key.
 */
export function captureScanKey(e: KeyboardEvent, input: HTMLInputElement): boolean {
  if (input.readOnly || input.disabled) return false
  const ch = scanKeyCharFor(e)
  if (ch === null) return false
  e.preventDefault()
  insertAtCaret(input, ch)
  return true
}
