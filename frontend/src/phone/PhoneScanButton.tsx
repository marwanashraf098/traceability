import { ReactNode, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ChevronDown, Smartphone, WifiOff } from 'lucide-react'
import { Button, cn } from '../components/ui'
import { usePhoneStatus, type PhoneStatus } from './PhoneScanProvider'

// Phone as scanner — the in-flow controls (no floating control anywhere):
//   • PhoneScanButton sits INSIDE a phone-capable scan screen's own header, next to its header
//     actions (pack session, PickScreen — and every future phone-capable screen): "Use phone" →
//     the QR modal; "Waiting for the phone…" / "Phone connected · <device>" / "Phone link
//     reconnecting…" → a small menu with Unpair (or Cancel). Below 640 px wide it collapses to an
//     icon button in the same slot.
//   • PhoneTopbarIcon sits in the app top bar (Layout, beside the bell) and shows ONLY while a
//     phone is paired: a phone icon with the status and Unpair in a menu.
// Both only present PhoneScanProvider's state; pairing, the stream and routing live there.

const NARROW = '(max-width: 639px)'

function useNarrow(): boolean {
  const query = () => typeof window.matchMedia === 'function' && window.matchMedia(NARROW).matches
  const [narrow, setNarrow] = useState(query)
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return
    const mq = window.matchMedia(NARROW)
    const on = () => setNarrow(mq.matches)
    mq.addEventListener('change', on)
    return () => mq.removeEventListener('change', on)
  }, [])
  return narrow
}

interface View { live: boolean; paired: boolean; label: string; tone: string; Icon: typeof Smartphone; state: string }

function view(phone: PhoneStatus, t: (k: string, o?: Record<string, unknown>) => string): View {
  const status = phone.pairing?.status ?? 'none'
  const live = status === 'connected' || status === 'waiting'
  return {
    live,
    paired: status === 'connected',
    label: phone.reconnecting ? t('fulfill.waybill.phone.reconnecting')
      : status === 'connected' ? t('fulfill.waybill.phone.connected', { device: phone.pairing?.deviceLabel ?? '' })
      : status === 'waiting' ? t('fulfill.waybill.phone.waiting')
      : t('fulfill.waybill.phone.usePhone'),
    tone: phone.reconnecting ? 'text-warning-text' : status === 'connected' ? 'text-success-text' : 'text-muted',
    Icon: phone.reconnecting ? WifiOff : Smartphone,
    state: phone.reconnecting ? 'reconnecting' : status,
  }
}

/** A trigger with a small menu under it (end-aligned); closes on an outside click or Escape. */
function MenuTrigger({ trigger, children, testId }: {
  trigger: (open: boolean, toggle: () => void) => ReactNode
  children: (close: () => void) => ReactNode
  testId: string
}) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const onClick = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) setOpen(false) }
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('click', onClick)
    document.addEventListener('keydown', onKey)
    return () => { document.removeEventListener('click', onClick); document.removeEventListener('keydown', onKey) }
  }, [open])
  return (
    <div ref={ref} className="relative flex-shrink-0" data-testid={testId}>
      {trigger(open, () => setOpen(o => !o))}
      {open && (
        <div role="menu" data-testid={`${testId}-menu`}
          className="absolute end-0 top-full mt-2 z-50 min-w-[220px] bg-panel border border-line rounded-xl shadow-e3 p-3 space-y-2">
          {children(() => setOpen(false))}
        </div>
      )}
    </div>
  )
}

function StatusMenu({ phone, v, close }: { phone: PhoneStatus; v: View; close: () => void }) {
  const { t } = useTranslation()
  return (
    <>
      <p className={cn('flex items-center gap-1.5 text-small font-semibold', v.tone)}>
        <v.Icon size={14} />{v.label}
      </p>
      <Button variant="secondary" size="sm" className="w-full" disabled={phone.busy}
        onClick={() => { close(); phone.unpair() }}>
        {v.paired ? t('fulfill.waybill.phone.unpair') : t('fulfill.waybill.phone.cancel')}
      </Button>
    </>
  )
}

/** The phone control of a scan screen's header. */
export function PhoneScanButton() {
  const { t } = useTranslation()
  const phone = usePhoneStatus()
  const narrow = useNarrow()
  if (!phone || !phone.pairing) return null
  const v = view(phone, t)

  if (!v.live) {
    return narrow ? (
      <button type="button" aria-label={v.label} title={v.label} onClick={phone.startPairing} disabled={phone.busy}
        data-testid="phone-button"
        className="w-9 h-9 flex-shrink-0 rounded-lg border border-line bg-panel text-primary flex items-center justify-center hover:bg-elevated disabled:opacity-50">
        <Smartphone size={17} />
      </button>
    ) : (
      <span className="flex-shrink-0" data-testid="phone-button">
        <Button variant="secondary" size="sm" iconStart={Smartphone} onClick={phone.startPairing} disabled={phone.busy}>
          {v.label}
        </Button>
      </span>
    )
  }

  return (
    <MenuTrigger testId="phone-button" trigger={(open, toggle) => narrow ? (
      <button type="button" aria-label={v.label} title={v.label} aria-expanded={open} onClick={toggle}
        data-testid="phone-chip" data-state={v.state}
        className={cn('w-9 h-9 rounded-lg border border-line bg-panel flex items-center justify-center hover:bg-elevated', v.tone)}>
        <v.Icon size={17} />
      </button>
    ) : (
      <button type="button" aria-expanded={open} onClick={toggle} data-testid="phone-chip" data-state={v.state}
        className={cn('inline-flex items-center gap-1.5 rounded-full border border-line bg-panel ps-3 pe-2 py-1 text-small font-semibold whitespace-nowrap hover:bg-elevated', v.tone)}>
        <v.Icon size={14} />{v.label}<ChevronDown size={14} className="text-muted" />
      </button>
    )}>
      {close => <StatusMenu phone={phone} v={v} close={close} />}
    </MenuTrigger>
  )
}

/** The app top bar's phone icon: shown only while a phone is paired. */
export function PhoneTopbarIcon() {
  const { t } = useTranslation()
  const phone = usePhoneStatus()
  if (!phone || phone.pairing?.status !== 'connected') return null
  const v = view(phone, t)
  return (
    <MenuTrigger testId="phone-topbar" trigger={(open, toggle) => (
      <button type="button" aria-label={v.label} title={v.label} aria-expanded={open} onClick={toggle}
        data-state={v.state} className={cn('relative flex items-center transition-colors hover:text-primary', v.tone)}>
        <v.Icon size={17} strokeWidth={1.75} />
      </button>
    )}>
      {close => <StatusMenu phone={phone} v={v} close={close} />}
    </MenuTrigger>
  )
}
