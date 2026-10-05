import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Smartphone, WifiOff } from 'lucide-react'
import { Button, cn } from '../components/ui'
import { usePhoneStatus } from './PhoneScanProvider'

// Q1 — the floating phone control, on every authenticated page (RequireAuth renders it beside
// the page — full-screen scan screens included, which have no Layout): "Use phone" (the QR
// modal), "Waiting for the phone…" / "Phone connected · <device>" with Cancel / Unpair, and
// "Phone link reconnecting…" after 5 s down. Mounting it is what tells PhoneScanProvider an
// authenticated page is showing (load the pairing, open the stream).
//
// It never covers an actionable control. It places itself: every visible button / link / field
// on the page is an obstacle, and the control takes the first free spot scanning up the end
// edge from the bottom corner, then up the start edge. If the full chip fits nowhere it
// collapses to a 40 px icon button (tapping it shows the status and Unpair, or starts pairing)
// placed the same way. Re-placed on resize, scroll and any DOM change (once per frame).

const ACTIONABLE = 'button, a[href], input:not([type="hidden"]), select, textarea, [role="button"], [role="link"], ' +
  '[role="checkbox"], [role="radio"], [role="tab"], [tabindex]:not([tabindex="-1"])'
const MARGIN = 16
const STEP = 8
const GAP = 6
const COMPACT = 40

type Place = { left: number; top: number; compact: boolean }

interface Box { left: number; top: number; right: number; bottom: number }

/** The first spot of size w×h (end edge, bottom-up; then start edge) that touches no obstacle. */
function findSpot(w: number, h: number, obstacles: Box[], rtl: boolean): { left: number; top: number } | null {
  const W = window.innerWidth
  const H = window.innerHeight
  if (w + 2 * MARGIN > W || h + 2 * MARGIN > H) return null
  const endX = rtl ? MARGIN : W - MARGIN - w
  const startX = rtl ? W - MARGIN - w : MARGIN
  for (const left of [endX, startX]) {
    for (let top = H - MARGIN - h; top >= MARGIN; top -= STEP) {
      const free = obstacles.every(o =>
        left + w + GAP <= o.left || left - GAP >= o.right || top + h + GAP <= o.top || top - GAP >= o.bottom)
      if (free) return { left, top }
    }
  }
  return null
}

export default function PhoneControl() {
  const { t } = useTranslation()
  const phone = usePhoneStatus()
  const setActive = phone?.setActive
  useEffect(() => {
    if (!setActive) return
    setActive(true)
    return () => setActive(false)
  }, [setActive])

  const boxRef = useRef<HTMLDivElement>(null)
  const fullSize = useRef<{ w: number; h: number }>({ w: 0, h: 0 })
  const [place, setPlace] = useState<Place | null>(null)
  const [measuring, setMeasuring] = useState(true)
  const [open, setOpen] = useState(false)

  const status = phone?.pairing?.status ?? null
  const live = status === 'connected' || status === 'waiting'
  const contentKey = `${status}|${phone?.reconnecting}|${phone?.pairing?.deviceLabel ?? ''}|${t('fulfill.waybill.phone.usePhone')}`

  const placeNow = useCallback(() => {
    const box = boxRef.current
    if (!box) return
    const rtl = getComputedStyle(document.documentElement).direction === 'rtl'
    const obstacles: Box[] = []
    for (const el of document.querySelectorAll<HTMLElement>(ACTIONABLE)) {
      if (box.contains(el)) continue
      const r = el.getBoundingClientRect()
      if (r.width > 0 && r.height > 0 && r.bottom > 0 && r.right > 0 && r.top < window.innerHeight && r.left < window.innerWidth) {
        obstacles.push({ left: r.left, top: r.top, right: r.right, bottom: r.bottom })
      }
    }
    const full = findSpot(fullSize.current.w, fullSize.current.h, obstacles, rtl)
    const next: Place = full ? { ...full, compact: false }
      : { ...(findSpot(COMPACT, COMPACT, obstacles, rtl)
            ?? { left: rtl ? MARGIN : window.innerWidth - MARGIN - COMPACT, top: window.innerHeight - MARGIN - COMPACT }),
          compact: true }
    setPlace(prev => (prev && prev.left === next.left && prev.top === next.top && prev.compact === next.compact ? prev : next))
  }, [])

  // The content changed (status, language): measure the full chip again, then place.
  useLayoutEffect(() => { setMeasuring(true); setOpen(false) }, [contentKey])
  useLayoutEffect(() => {
    if (!measuring || !boxRef.current) return
    fullSize.current = { w: boxRef.current.offsetWidth, h: boxRef.current.offsetHeight }
    setMeasuring(false)
    placeNow()
  }, [measuring, placeNow, status])

  useEffect(() => {
    if (!status) return
    let frame = 0
    const schedule = () => {
      if (frame) return
      frame = requestAnimationFrame(() => { frame = 0; placeNow() })
    }
    const observer = new MutationObserver(schedule)
    observer.observe(document.body, { childList: true, subtree: true, attributes: true, characterData: true })
    window.addEventListener('resize', schedule)
    window.addEventListener('scroll', schedule, true)
    return () => {
      observer.disconnect()
      window.removeEventListener('resize', schedule)
      window.removeEventListener('scroll', schedule, true)
      if (frame) cancelAnimationFrame(frame)
    }
  }, [status, placeNow])

  // A tap outside the compact popover closes it.
  useEffect(() => {
    if (!open) return
    const close = (e: MouseEvent) => { if (!boxRef.current?.contains(e.target as Node)) setOpen(false) }
    document.addEventListener('click', close)
    return () => document.removeEventListener('click', close)
  }, [open])

  if (!phone || !phone.pairing) return null

  const label = phone.reconnecting ? t('fulfill.waybill.phone.reconnecting')
    : status === 'connected' ? t('fulfill.waybill.phone.connected', { device: phone.pairing.deviceLabel ?? '' })
    : status === 'waiting' ? t('fulfill.waybill.phone.waiting')
    : t('fulfill.waybill.phone.usePhone')
  const tone = phone.reconnecting ? 'text-warning-text' : status === 'connected' ? 'text-success-text' : 'text-muted'
  const Icon = phone.reconnecting ? WifiOff : Smartphone

  const chip = live ? (
    <span className="inline-flex items-center gap-1 rounded-full bg-panel border border-line shadow-e2 ps-3 pe-1 py-1 whitespace-nowrap"
      data-testid="phone-chip" data-state={phone.reconnecting ? 'reconnecting' : status}>
      <span className={'inline-flex items-center gap-1.5 text-small font-semibold ' + tone}>
        <Icon size={14} />
        {label}
      </span>
      <Button variant="ghost" size="sm" onClick={phone.unpair} disabled={phone.busy}>
        {status === 'connected' ? t('fulfill.waybill.phone.unpair') : t('fulfill.waybill.phone.cancel')}
      </Button>
    </span>
  ) : (
    <Button variant="secondary" size="sm" iconStart={Smartphone} onClick={phone.startPairing} disabled={phone.busy}
      className="shadow-e2 whitespace-nowrap">
      {t('fulfill.waybill.phone.usePhone')}
    </Button>
  )

  const compact = !measuring && !!place?.compact
  const lowerHalf = (place?.top ?? 0) > window.innerHeight / 2
  return (
    <div ref={boxRef} className="fixed z-40 print:hidden" data-testid="phone-control"
      data-mode={measuring ? 'measuring' : compact ? 'compact' : 'full'}
      style={place && !measuring
        ? { left: place.left, top: place.top }
        : { right: MARGIN, bottom: MARGIN, visibility: measuring && place ? 'hidden' : undefined }}>
      {!compact ? chip : (
        <>
          <button type="button" aria-label={label} title={label} data-testid="phone-compact"
            data-state={phone.reconnecting ? 'reconnecting' : status ?? undefined}
            onClick={() => (live ? setOpen(o => !o) : phone.startPairing())} disabled={phone.busy && !live}
            className={cn('w-10 h-10 rounded-full bg-panel border border-line shadow-e2 flex items-center justify-center', tone)}>
            <Icon size={18} />
          </button>
          {open && live && (
            <div className={cn('absolute end-0', lowerHalf ? 'bottom-full mb-2' : 'top-full mt-2')}>{chip}</div>
          )}
        </>
      )}
    </div>
  )
}
