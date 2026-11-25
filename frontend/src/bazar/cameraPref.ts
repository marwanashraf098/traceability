import { useCallback, useState } from 'react'

// Bazar C1 (2026-11-24) — is the camera box shown on this device?
//
// A computer (no touch screen: a fine pointer, no coarse one, no touch points) sells with a USB scanner, so it starts
// with the camera hidden ("Use camera" brings it back); a phone or tablet starts with it shown ("Hide camera"). The
// seller's choice is remembered per device (localStorage — a convenience: unavailable storage just means the default).
// A browser without matchMedia (jsdom) counts as a phone.

const KEY = 'traced.bazar.camera'

export function isComputer(): boolean {
  try {
    if (typeof window.matchMedia !== 'function') return false
    const coarse = window.matchMedia('(any-pointer: coarse)').matches
    const fine = window.matchMedia('(pointer: fine)').matches
    return !coarse && fine && (navigator.maxTouchPoints ?? 0) === 0
  } catch {
    return false
  }
}

function stored(): boolean | null {
  try {
    const v = window.localStorage.getItem(KEY)
    return v === 'shown' ? true : v === 'hidden' ? false : null
  } catch {
    return null
  }
}

/** [shown, setShown] — the device's camera choice, defaulting to "shown unless this is a computer". */
export function useCameraShown(): [boolean, (shown: boolean) => void] {
  const [shown, setState] = useState<boolean>(() => stored() ?? !isComputer())
  const setShown = useCallback((v: boolean) => {
    setState(v)
    try { window.localStorage.setItem(KEY, v ? 'shown' : 'hidden') } catch { /* private mode */ }
  }, [])
  return [shown, setShown]
}
