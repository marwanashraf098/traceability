import { useEffect, useRef, type RefObject } from 'react'

// Bazar POS B6c review — a HID scanner types a code as a FAST burst of keys followed by Enter. When the person has a
// text field focused (the cart's discount / customer boxes, the "+ Customer" sheet), the scanner's keys land in that
// field instead of the scan input. This hook watches keydowns inside {@code container} (capture phase): a run of at
// least {@link burstTiming.minChars} printable keys, each at most {@link burstTiming.maxGapMs} after the previous one,
// then Enter within {@link burstTiming.enterGapMs}, is a scan — the field is put back exactly as it was before the
// run started and the code goes to {@code onBurst} (the screen's own scan path). Anything slower is a person typing
// and is left alone (Enter included). It never touches useScanner (SAFETY-CRITICAL) and never acts on the scan input
// itself (keys there are the scanner's own). A phone's soft keyboard sends key "Unidentified", so it never forms a run.

export const burstTiming = { maxGapMs: 30, enterGapMs: 60, minChars: 4 }

/** Sets an input's value the way the browser would, so React's onChange sees it (controlled inputs). */
function restoreValue(input: HTMLInputElement, value: string) {
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set
  setter?.call(input, value)
  input.dispatchEvent(new Event('input', { bubbles: true }))
}

export function useScanBurst(container: RefObject<HTMLElement>, onBurst: ((code: string) => void) | undefined) {
  const handler = useRef(onBurst)
  handler.current = onBurst
  const enabled = onBurst !== undefined

  useEffect(() => {
    const el = container.current
    if (!el || !enabled) return
    let run: { field: HTMLInputElement; before: string; chars: string; last: number } | null = null
    const onKey = (e: KeyboardEvent) => {
      const field = e.target
      if (!(field instanceof HTMLInputElement) || field.dataset.scanInput !== undefined) { run = null; return }
      const now = performance.now()
      if (e.key.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey) {
        if (!run || run.field !== field || now - run.last > burstTiming.maxGapMs) {
          run = { field, before: field.value, chars: '', last: now }
        }
        run.chars += e.key
        run.last = now
        return
      }
      if (e.key === 'Shift') return                    // scanners press Shift for capitals — part of the run
      if (e.key === 'Enter' && run && run.field === field && run.chars.trim().length >= burstTiming.minChars
          && now - run.last <= burstTiming.enterGapMs) {
        e.preventDefault()
        e.stopPropagation()
        const { before, chars } = run
        run = null
        restoreValue(field, before)
        handler.current?.(chars.trim())
        return
      }
      run = null
    }
    el.addEventListener('keydown', onKey, true)
    return () => el.removeEventListener('keydown', onKey, true)
  }, [container, enabled])
}
