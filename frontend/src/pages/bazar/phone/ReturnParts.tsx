import { useCallback, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Camera, CameraOff, Keyboard, Loader2, WifiOff } from 'lucide-react'
import CameraReader, { type CameraError } from '../../scanpair/CameraReader'
import type { BazarError, BazarSale, ReturnCondition } from '../../../bazar/api'
import { formatMoney, saleTime, sellErrorText } from '../../../bazar/format'
import type { CommandState } from '../../../bazar/useRetryingCommand'
import { cn } from '../../../components/ui'

// Bazar POS B3 — pieces shared by the phone's Return and Exchange screens (mockup page 1, step 7).

/** The camera reports the same code many times a second: one read per code per 2 s (the Sell screen's rule). */
const DUPLICATE_MS = 2_000

/** Sell · Return · Exchange — the segmented switch under the phone header. */
export function PhoneTabs({ eventId, active }: { eventId: string; active: 'sell' | 'return' | 'exchange' }) {
  const { t } = useTranslation()
  const tabs: { key: typeof active; label: string }[] = [
    { key: 'sell', label: t('bazar.tabs.sell') },
    { key: 'return', label: t('bazar.tabs.return') },
    { key: 'exchange', label: t('bazar.tabs.exchange') },
  ]
  return (
    <nav className="grid grid-cols-3 gap-1 p-[3px] rounded-[10px] bg-[rgba(38,44,54,0.8)]" aria-label={t('bazar.tabs.label')}>
      {tabs.map(tab => (
        <Link key={tab.key} to={`/bazar/${encodeURIComponent(eventId)}/${tab.key}`}
          aria-current={tab.key === active ? 'page' : undefined}
          className={cn('h-[34px] rounded-lg flex items-center justify-center text-small',
            tab.key === active ? 'bg-[#F2F4F7] text-[#111827] font-semibold' : 'text-sidebar-text hover:text-sidebar-active')}>
          {tab.label}
        </Link>
      ))}
    </nav>
  )
}

/**
 * The one code entry of a Return / Exchange screen: the phone's camera, plus a field a HID scanner types into (or the
 * seller, after "Type a code"). Every code goes to {@code onCode}.
 */
export function CodeEntry({ onCode, disabled, hint, noCamera }: {
  onCode: (code: string) => void
  disabled?: boolean
  hint: string
  /** B5 desktop: the scan field only (a HID scanner) — no camera box. */
  noCamera?: boolean
}) {
  const { t } = useTranslation()
  const [cameraOn, setCameraOn] = useState(true)
  const [cameraError, setCameraError] = useState<CameraError | null>(null)
  const [typing, setTyping] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)
  const lastRead = useRef<{ code: string; at: number } | null>(null)

  const submit = useCallback((raw: string) => {
    const code = raw.replace(/\s+/g, '')
    if (!code || disabled) return
    if (inputRef.current) inputRef.current.value = ''
    onCode(code)
  }, [onCode, disabled])

  const onCameraRead = useCallback((raw: string) => {
    const code = raw.trim()
    const now = Date.now()
    const last = lastRead.current
    if (!code || (last && last.code === code && now - last.at < DUPLICATE_MS)) return
    lastRead.current = { code, at: now }
    submit(code)
  }, [submit])
  const onCameraError = useCallback((e: CameraError) => setCameraError(e), [])

  return (
    <div className="flex flex-col gap-2">
      {!noCamera && <div className="relative h-36 rounded-2xl bg-[#1A1F27] overflow-hidden flex items-center" data-testid="bazar-camera-box">
        {cameraOn && !cameraError && !disabled
          ? <div className="w-full"><CameraReader active onRead={onCameraRead} onError={onCameraError} /></div>
          : (
            <p className="w-full px-6 text-center text-small text-[#D1D5DB]">
              {cameraError ? t(`bazar.sell.camera.${cameraError}`) : t('bazar.sell.camera.off')}
            </p>
          )}
        <span className="absolute bottom-2 inset-x-0 text-center text-small text-[#D1D5DB] pointer-events-none">{hint}</span>
        <button type="button" onClick={() => { setCameraError(null); setCameraOn(v => !v) }}
          aria-label={cameraOn ? t('bazar.sell.cameraOff') : t('bazar.sell.cameraOn')}
          className="absolute top-2 end-2 w-11 h-11 rounded-full bg-black/55 text-white flex items-center justify-center">
          {cameraOn ? <CameraOff size={18} /> : <Camera size={18} />}
        </button>
      </div>}
      <div className="flex items-center gap-2">
        <input
          ref={inputRef}
          type="text"
          inputMode={typing ? 'text' : 'none'}
          autoComplete="off"
          autoCapitalize="characters"
          spellCheck={false}
          dir="auto"
          autoFocus
          disabled={disabled}
          aria-label={t('bazar.sell.codeLabel')}
          placeholder={typing ? t('bazar.sell.typePlaceholder') : t('bazar.sell.hidPlaceholder')}
          className={cn('input w-full font-mono placeholder:font-sans', typing ? 'h-11 text-body-lg' : 'h-10 text-small')}
          onKeyDown={e => { if (e.key === 'Enter') submit((e.target as HTMLInputElement).value) }}
          data-testid="bazar-code-input"
        />
        <button type="button" aria-pressed={typing} onClick={() => {
          const next = !typing
          setTyping(next)
          if (next) setTimeout(() => inputRef.current?.focus(), 0)
        }} className={cn('h-11 px-3 rounded-lg border border-line flex items-center gap-1.5 text-small font-semibold shrink-0',
            typing ? 'bg-trace-blue text-white border-trace-blue' : 'bg-surface text-primary')}>
          <Keyboard size={16} aria-hidden />{t('bazar.sell.typeCode')}
        </button>
      </div>
    </div>
  )
}

/** A piece row: title, a second line, its price. Tapping it (when {@code onClear}) scans it again. */
export function PieceRow({ title, line, price, onClear, testId, struck }: {
  title: string
  line: React.ReactNode
  price: string | number | null
  onClear?: () => void
  testId?: string
  struck?: boolean
}) {
  const { t } = useTranslation()
  return (
    <div className="flex items-center gap-2.5 p-3 rounded-xl border border-line bg-surface" data-testid={testId}>
      <div className="flex-1 min-w-0 flex flex-col">
        <span className={cn('font-semibold text-primary truncate', struck && 'line-through text-muted')}>{title}</span>
        <span className="text-small text-muted truncate">{line}</span>
      </div>
      <span className="font-semibold tabular-nums shrink-0">{price == null ? '—' : formatMoney(price).replace(/^EGP /, '')}</span>
      {onClear && (
        <button type="button" onClick={onClear} className="min-h-[44px] px-2 text-small font-semibold text-trace-blue shrink-0">
          {t('bazar.ret.rescan')}
        </button>
      )}
    </div>
  )
}

/** "Sold today 12:41 · Salma · Nour 010*****678" — the sale line on a Return / Exchange card. */
export function soldLine(t: (k: string, o?: Record<string, unknown>) => string, sale: BazarSale): string {
  const customer = [sale.customer_name, sale.customer_phone].filter(Boolean).join(' ')
  return [t('bazar.ret.soldAt', { number: sale.sale_number, time: saleTime(sale.sold_at), seller: sale.seller_name }),
          customer].filter(Boolean).join(' · ')
}

export function ConditionToggle({ value, onChange, disabled }: {
  value: ReturnCondition
  onChange: (c: ReturnCondition) => void
  disabled?: boolean
}) {
  const { t } = useTranslation()
  return (
    <div className="grid grid-cols-2 gap-2" role="group" aria-label={t('bazar.ret.condition')}>
      {(['good', 'damaged'] as const).map(c => {
        const on = c === value
        return (
          <button key={c} type="button" aria-pressed={on} disabled={disabled} onClick={() => onChange(c)}
            className={cn('min-h-[46px] px-2 rounded-[10px] font-semibold text-small flex flex-col items-center justify-center',
              on ? c === 'damaged' ? 'border-[1.5px] border-critical bg-critical/10 text-critical-text'
                                   : 'border-[1.5px] border-trace-blue bg-trace-blue/10 text-trace-blue'
                 : 'border border-line bg-surface text-primary')}>
            {t(`bazar.ret.cond.${c}`)}
            <span className="font-normal text-muted text-[12px] leading-4">{t(`bazar.ret.cond.${c}Hint`)}</span>
          </button>
        )
      })}
    </div>
  )
}

/** Sending / checking (weak signal) / refused — never a success before the server confirms. */
export function CommandStatus({ state }: { state: CommandState<unknown> }) {
  const { t } = useTranslation()
  if (state.kind === 'sending') {
    return (
      <section role="status" className="rounded-2xl bg-trace-blue/10 border border-trace-blue/30 px-4 py-3.5 flex items-center gap-3"
               data-testid="cmd-sending">
        <Loader2 size={20} className="animate-spin text-trace-blue" aria-hidden />
        <span className="font-semibold text-primary">{t('bazar.ret.sending')}</span>
      </section>
    )
  }
  if (state.kind === 'checking') {
    return (
      <section role="status" className="rounded-2xl bg-warning/10 border border-warning/30 px-4 py-3.5 flex flex-col gap-1"
               data-testid="cmd-checking">
        <span className="flex items-center gap-2 font-semibold text-warning-text">
          <WifiOff size={18} aria-hidden />{t('bazar.sell.checking')}
          <Loader2 size={16} className="animate-spin ms-auto" aria-hidden />
        </span>
        <span className="text-small text-primary">{t('bazar.ret.checkingBody')}</span>
      </section>
    )
  }
  if (state.kind === 'error') return <ErrorCard error={state.error} />
  return null
}

export function ErrorCard({ error }: { error: BazarError }) {
  const { t, i18n } = useTranslation()
  const { title, detail } = sellErrorText(t, i18n.language, error)
  return (
    <section role="alert" className="rounded-2xl bg-critical/10 border border-critical/30 px-4 py-3.5 flex flex-col gap-1"
             data-testid="cmd-error">
      <span className="font-semibold text-critical-text">{title}</span>
      {detail && <span className="text-small text-primary">{detail}</span>}
    </section>
  )
}

/** The big money line: "Give back EGP 960" / "Customer pays EGP 240" / "Even swap". */
export function MoneyBanner({ kind, amount, testId, label }: {
  kind: 'pay' | 'giveBack' | 'even'
  amount: number
  testId?: string
  /** The small line above (default: "Difference" / "No money changes hands"). */
  label?: string
}) {
  const { t } = useTranslation()
  const tone = kind === 'pay' ? 'bg-warning/[0.08] border-warning/30 text-warning-text'
    : kind === 'giveBack' ? 'bg-trace-blue/[0.06] border-trace-blue/30 text-trace-blue'
    : 'bg-success/10 border-success/30 text-success-text'
  return (
    <section className={cn('px-4 py-3.5 rounded-2xl border flex flex-col items-center gap-0.5', tone)} data-testid={testId}>
      <span className="text-small font-semibold">{label ?? t(kind === 'even' ? 'bazar.exch.noMoney' : 'bazar.exch.difference')}</span>
      <span className="text-h3 font-bold text-primary tabular-nums text-center">
        {kind === 'pay' ? t('bazar.exch.customerPays', { amount: formatMoney(amount) })
          : kind === 'giveBack' ? t('bazar.exch.giveBack', { amount: formatMoney(amount) })
          : t('bazar.exch.even')}
      </span>
    </section>
  )
}

/** The screen's big confirm / next button (a plain button so it carries its own test id). */
export function BigButton({ children, onClick, busy, disabled, testId, variant = 'primary' }: {
  children: React.ReactNode
  onClick: () => void
  busy?: boolean
  disabled?: boolean
  testId?: string
  variant?: 'primary' | 'secondary'
}) {
  return (
    <button type="button" onClick={onClick} disabled={disabled || busy} data-testid={testId}
      className={cn('btn w-full h-[52px] rounded-xl text-body-lg font-semibold transition-colors flex items-center justify-center gap-2 disabled:opacity-70',
        variant === 'primary' ? 'bg-trace-blue hover:bg-trace-blue-hover active:bg-trace-blue-active text-white'
                              : 'border border-line bg-surface text-primary')}>
      {busy && <Loader2 size={16} className="animate-spin" aria-hidden />}
      {children}
    </button>
  )
}
