import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { ArrowLeft, ArrowLeftRight, Camera, CameraOff, Keyboard, Undo2 } from 'lucide-react'
import CameraReader, { type CameraError } from '../../scanpair/CameraReader'
import { getMe } from '../../../api'
import type { BazarLive } from '../../../capabilities'
import { findVariant, type BazarCatalog, type BazarCatalogProduct } from '../../../bazar/catalog'
import { liveLabel } from '../../../bazar/format'
import { cn } from '../../../components/ui'
import { ProductThumb } from '../phone/PickSheet'
import { Tag, amount } from '../r1/parts'
import { Money, money } from '../r2/parts'

/**
 * Bazar R4 restyle — the Sell, Return and Exchange screens (design/bazar-v2: V2_SellQuick, V2_Sell, V2_SellPhone,
 * V2_SellPhoneCart, V2_SellPickPhone, V2_Return, V2_ReturnPhone). Appearance only: every part here is layout around
 * the screens' existing behaviour. The phone frame (phone/shared PhoneFrame) is left as it is for the other phone
 * screens; these three screens use R4Screen.
 */

/**
 * "880 EGP" for a sentence: in a right-to-left language the amount is wrapped in an LTR isolate (U+2066 … U+2069) so it
 * never reads "EGP 880"; left-to-right text is unchanged.
 */
export function useSentenceMoney(): (v: string | number | null | undefined) => string {
  const { i18n } = useTranslation()
  const rtl = i18n.dir() === 'rtl'
  return v => (rtl ? `\u2066${money(v)}\u2069` : money(v))
}

/** The full-screen frame: phone = one 480 px column with a dark top bar; computer = a wide page with a light header. */
export function R4Screen({ header, children, testId }: { header: ReactNode; children: ReactNode; testId?: string }) {
  return (
    <div className="min-h-[100dvh] bg-base flex justify-center" data-testid={testId}>
      <div className="w-full max-w-[480px] lg:max-w-[1240px] min-h-[100dvh] flex flex-col">
        {header}
        <main className="flex-1 flex flex-col gap-4 px-4 pb-4 lg:px-8 lg:pb-8 min-h-0">{children}</main>
      </div>
    </div>
  )
}

/**
 * One header row for both sizes (no duplicated controls): dark on a phone (the sidebar palette), light on a computer.
 * {@code back} is a link whose accessible name is its label; the phone shows only the arrow.
 */
export function R4Header({ live, back, title, wideTitle, sub, wideSub, today, action, testId }: {
  live: BazarLive
  back: { to: string; label: string; showLabel?: boolean }
  /** Phone title (the event name). */
  title: string
  /** Computer title ("Selling · Zamalek Winter Bazar"). */
  wideTitle: string
  /** Phone line under the title. */
  sub?: ReactNode
  /** Computer line under the title. */
  wideSub?: ReactNode
  /** The computer's dark "Today" pill. */
  today?: ReactNode
  action?: ReactNode
  testId?: string
}) {
  const { t } = useTranslation()
  return (
    <header data-testid={testId}
      className="bg-sidebar text-sidebar-active px-4 py-3 lg:bg-transparent lg:text-primary lg:px-8 lg:pt-6 lg:pb-5">
      <div className="flex items-center gap-3">
        <Link to={back.to} aria-label={back.label}
          className={cn('shrink-0 min-h-[44px] min-w-[44px] -ms-2 px-2 flex items-center gap-1.5 text-small font-semibold rounded-[10px]',
            'text-sidebar-active hover:text-white',
            'lg:ms-0 lg:h-10 lg:min-h-0 lg:px-3 lg:border lg:border-line lg:bg-surface lg:text-primary lg:hover:bg-charcoal lg:hover:text-primary')}>
          <ArrowLeft size={18} aria-hidden className="rtl:-scale-x-100" />
          <span aria-hidden className={cn(back.showLabel ? 'inline' : 'hidden lg:inline')}>{back.label}</span>
        </Link>
        <div className="flex-1 min-w-0 flex flex-col">
          <div className="flex items-center gap-2.5 min-w-0">
            <h1 className="truncate text-body-lg font-bold lg:text-[22px] lg:leading-7 lg:tracking-[-0.01em]">
              <span className="lg:hidden">{title}</span>
              <span className="hidden lg:inline">{wideTitle}</span>
            </h1>
            <span className="hidden lg:inline-flex shrink-0"><Tag tone="live" dot>{liveLabel(t, live.day, live.dayCount)}</Tag></span>
          </div>
          {sub && <span className="lg:hidden text-small text-sidebar-text truncate">{sub}</span>}
          {wideSub && <span className="hidden lg:block text-small text-muted truncate">{wideSub}</span>}
        </div>
        {today && <span className="hidden lg:flex shrink-0">{today}</span>}
        {action}
      </div>
    </header>
  )
}

/** "Today  18,920 EGP  23 sales" — the computer header's dark pill. */
export function TodayPill({ total, count }: { total: string | number | null | undefined; count: string }) {
  const { t } = useTranslation()
  return (
    <span className="h-10 px-3.5 rounded-[10px] bg-[#0D1117] text-[#F2F4F7] flex items-center gap-2.5 text-small whitespace-nowrap">
      <span className="text-sidebar-text">{t('bazar.r4.today')}</span>
      <Money value={total} className="font-bold text-body" unitClass="text-[0.85em] text-[#F2F4F7]" />
      <span className="text-sidebar-text">{count}</span>
    </span>
  )
}

/** "Today 18,920 EGP · 23 sales" — the phone header's line. */
export function TodayLine({ total, count }: { total: string | number | null | undefined; count: string }) {
  const { t } = useTranslation()
  return (
    <span>
      {t('bazar.r4.today')} <Money value={total} className="font-bold text-sidebar-active" unitClass="text-[0.85em] text-sidebar-active" /> · {count}
    </span>
  )
}

/** The seller's name for "Selling as …" (GET /me). null while loading or when it fails — the line is then hidden. */
export function useSellerName(): string | null {
  const [name, setName] = useState<string | null>(null)
  useEffect(() => {
    let cancelled = false
    getMe().then(me => { if (!cancelled && me && typeof me.name === 'string' && me.name.trim()) setName(me.name) })
      .catch(() => {})
    return () => { cancelled = true }
  }, [])
  return name
}

/** The left / right columns of the Sell, Return and Exchange screens (one column on a phone). */
export function Columns({ left, right, rightClass }: { left: ReactNode; right: ReactNode; rightClass?: string }) {
  if (!right) return <div className="flex flex-col gap-4 lg:max-w-[760px]">{left}</div>
  return (
    <div className="flex flex-col gap-4 lg:grid lg:grid-cols-[minmax(0,1fr)_400px] lg:gap-6 lg:items-start">
      <div className="flex flex-col gap-4 min-w-0">{left}</div>
      {/* On a phone the panel's pieces flow in the page (so a sticky button sticks to the screen's bottom). */}
      <div className={cn('contents lg:flex lg:flex-col lg:gap-4 lg:min-w-0 lg:rounded-2xl lg:border lg:border-line lg:bg-surface lg:p-5 lg:shadow-e1', rightClass)}>
        {right}
      </div>
    </div>
  )
}

/** The phone's sticky bottom action; in flow on a computer. */
export function StickyAction({ children }: { children: ReactNode }) {
  return (
    <div className="sticky bottom-0 z-10 -mx-4 px-4 pt-3 pb-[max(16px,env(safe-area-inset-bottom))] bg-surface border-t border-line lg:static lg:m-0 lg:p-0 lg:border-0 lg:bg-transparent">
      {children}
    </div>
  )
}

/** Small uppercase label. */
export function Label({ children, className }: { children: ReactNode; className?: string }) {
  return <span className={cn('text-[11px] leading-4 font-bold uppercase tracking-[0.08em] text-muted', className)}>{children}</span>
}

/** Quick · scan = sold / Cart. Accessible names stay "Quick" / "Cart". */
export function ModeTabs({ mode, onChange }: { mode: 'quick' | 'cart'; onChange: (m: 'quick' | 'cart') => void }) {
  const { t } = useTranslation()
  return (
    <div role="radiogroup" aria-label={t('bazar.cart.mode')} data-testid="bazar-mode-switch"
         className="grid grid-cols-2 gap-1 p-1 rounded-xl bg-[#F2F4F7] lg:bg-charcoal">
      {(['quick', 'cart'] as const).map(m => (
        <button key={m} type="button" role="radio" aria-checked={mode === m} aria-label={t(`bazar.cart.${m}`)}
          onClick={() => onChange(m)}
          className={cn('h-10 rounded-[10px] text-small font-semibold transition-colors',
            mode === m ? 'bg-surface text-primary shadow-e1' : 'text-muted hover:text-primary')}>
          {t(m === 'quick' ? 'bazar.r4.quickLabel' : 'bazar.cart.cart')}
        </button>
      ))}
    </div>
  )
}

/** Refund | Exchange — links between the two existing screens. */
export function KindSwitch({ eventId, active }: { eventId: string; active: 'return' | 'exchange' }) {
  const { t } = useTranslation()
  const tabs = [
    { key: 'return' as const, label: t('bazar.r4.refund'), icon: <Undo2 size={16} aria-hidden /> },
    { key: 'exchange' as const, label: t('bazar.tabs.exchange'), icon: <ArrowLeftRight size={16} aria-hidden /> },
  ]
  return (
    <nav className="grid grid-cols-2 gap-1 p-1 rounded-xl bg-[#F2F4F7] lg:bg-charcoal" aria-label={t('bazar.tabs.label')}>
      {tabs.map(tab => (
        <Link key={tab.key} to={`/bazar/${encodeURIComponent(eventId)}/${tab.key}`}
          aria-current={tab.key === active ? 'page' : undefined}
          className={cn('h-11 rounded-[10px] flex items-center justify-center gap-2 text-body font-semibold',
            tab.key === active ? 'bg-surface text-primary shadow-e1' : 'text-muted hover:text-primary')}>
          {tab.icon}<span>{tab.label}</span>
        </Link>
      ))}
    </nav>
  )
}

/** A picture for a sale / line: the product's image from the already-loaded catalog, else a letter tile. */
export function Thumb({ catalog, variantId, title, size = 44 }: {
  catalog: BazarCatalog | null
  variantId: string | null | undefined
  title: string
  size?: number
}) {
  const product: BazarCatalogProduct | undefined = variantId ? findVariant(catalog, variantId)?.product : undefined
  if (product) return <ProductThumb product={product} size={size} />
  return (
    <span aria-hidden style={{ width: size, height: size }}
          className="rounded-lg bg-charcoal text-muted font-bold flex items-center justify-center shrink-0">
      {title.trim().charAt(0).toUpperCase()}
    </span>
  )
}

/** "Scanner ready" — display only: shown while no sheet is open and the seller isn't typing. */
export function ReadyPill({ children }: { children: ReactNode }) {
  return (
    <span className="inline-flex items-center gap-1.5 h-7 px-2.5 rounded-md bg-[#F2F4F7] text-[12px] font-semibold text-neutral-text whitespace-nowrap"
          data-testid="scanner-ready">
      <span aria-hidden className="w-1.5 h-1.5 rounded-sm bg-[#22C55E]" />{children}
    </span>
  )
}

/** The scan-field wrapper: restyles the screen's own scan input (its props untouched) through descendant selectors. */
export const SCAN_FIELD = cn(
  'relative flex-1 min-w-0',
  '[&_input]:h-12 [&_input]:rounded-xl [&_input]:ps-11 [&_input]:text-body',
  'lg:[&_input]:h-[60px] lg:[&_input]:text-body-lg lg:[&_input]:border-2 lg:[&_input]:border-trace-blue lg:[&_input]:pe-40',
)

/** The dark camera band: the camera box with its chips (state, Type code, camera on / off). */
export function CameraChips({ state, typing, onType, cameraOn, onToggleCamera }: {
  state: string
  typing: boolean
  onType: () => void
  cameraOn: boolean
  onToggleCamera: () => void
}) {
  const { t } = useTranslation()
  return (
    <>
      <span className="absolute top-2.5 start-2.5 h-7 px-2.5 rounded-md bg-black/60 text-white text-[12px] font-semibold flex items-center pointer-events-none"
            data-testid="camera-state">{state}</span>
      <span className="absolute top-2.5 end-2.5 flex items-center gap-1.5">
        <button type="button" onClick={onType} aria-pressed={typing}
          className={cn('h-9 px-2.5 rounded-md text-[12px] font-semibold flex items-center gap-1.5',
            typing ? 'bg-trace-blue text-white' : 'bg-black/60 text-white')}>
          <Keyboard size={14} aria-hidden />{t('bazar.sell.typeCode')}
        </button>
        <button type="button" onClick={onToggleCamera}
          aria-label={cameraOn ? t('bazar.sell.cameraOff') : t('bazar.sell.cameraOn')}
          className="w-9 h-9 rounded-md bg-black/60 text-white flex items-center justify-center">
          {cameraOn ? <CameraOff size={16} /> : <Camera size={16} />}
        </button>
      </span>
    </>
  )
}

/** The camera reads the same code many times a second: one read per code per 2 s (ReturnParts' rule). */
const DUPLICATE_MS = 2_000

/**
 * R4 look of ReturnParts' CodeEntry (Return / Exchange): the camera band, plus the field a HID scanner types into (or
 * the seller, after "Type code"). Same behaviour as CodeEntry: whitespace stripped, one read per code per 2 s, every
 * code to {@code onCode}; same test ids.
 */
export function R4CodeEntry({ onCode, disabled, hint }: { onCode: (code: string) => void; disabled?: boolean; hint: string }) {
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
  const live = cameraOn && !cameraError && !disabled

  return (
    <div className="-mx-4 px-4 pb-4 bg-sidebar flex flex-col gap-2.5 lg:mx-0 lg:p-0 lg:bg-transparent">
      <div className="relative h-40 lg:h-24 rounded-2xl bg-[#1A1F27] overflow-hidden flex items-center" data-testid="bazar-camera-box">
        {live
          ? <div className="w-full"><CameraReader active onRead={onCameraRead} onError={onCameraError} /></div>
          : (
            <p className="w-full px-6 text-center text-small text-[#D1D5DB]">
              {cameraError ? t(`bazar.sell.camera.${cameraError}`) : t('bazar.sell.camera.off')}
            </p>
          )}
        <span className="absolute bottom-2 inset-x-0 text-center text-small text-[#D1D5DB] pointer-events-none">{hint}</span>
        <CameraChips state={live ? t('bazar.r4.cameraReady') : t('bazar.r4.cameraOff')} typing={typing}
          cameraOn={cameraOn} onToggleCamera={() => { setCameraError(null); setCameraOn(v => !v) }}
          onType={() => {
            const next = !typing
            setTyping(next)
            if (next) setTimeout(() => inputRef.current?.focus(), 0)
          }} />
      </div>
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
        className={cn('input w-full font-mono placeholder:font-sans rounded-xl', typing ? 'h-12 text-body-lg' : 'h-11 text-small')}
        onKeyDown={e => { if (e.key === 'Enter') submit((e.target as HTMLInputElement).value) }}
        data-testid="bazar-code-input"
      />
    </div>
  )
}

/** A piece card on Return / Exchange: picture, title, code, a line, price; "Change" scans it again. */
export function PieceCard({ catalog, variantId, title, code, line, price, onClear, testId, tone = 'plain' }: {
  catalog: BazarCatalog | null
  variantId: string | null | undefined
  title: string
  code?: string | null
  line: ReactNode
  price: string | number | null | undefined
  onClear?: () => void
  testId?: string
  tone?: 'plain' | 'new'
}) {
  const { t } = useTranslation()
  return (
    <div data-testid={testId}
      className={cn('flex items-center gap-3 p-3 rounded-xl border bg-surface', tone === 'new' ? 'border-success/40' : 'border-line')}>
      <Thumb catalog={catalog} variantId={variantId} title={title} size={52} />
      <div className="flex-1 min-w-0 flex flex-col">
        <span className="font-semibold text-primary truncate text-start" dir="auto">{title}</span>
        {code && <span className="text-small text-muted font-mono" dir="ltr">{code}</span>}
        <span className="text-small text-muted whitespace-normal break-words">{line}</span>
      </div>
      <span className="text-h4 font-bold tabular-nums shrink-0" dir="ltr">
        {price == null ? '—' : amount(price)}
      </span>
      {onClear && (
        <button type="button" onClick={onClear} className="min-h-[44px] px-2 text-small font-semibold text-trace-blue shrink-0">
          {t('bazar.ret.rescan')}
        </button>
      )}
    </div>
  )
}

/** Good / Damaged as two cards (same aria-pressed buttons as ConditionToggle). */
export function ConditionCards({ damaged, onChange, disabled }: { damaged: boolean; onChange: (damaged: boolean) => void; disabled?: boolean }) {
  const { t } = useTranslation()
  return (
    <div className="grid grid-cols-2 gap-2" role="group" aria-label={t('bazar.ret.condition')}>
      {([false, true] as const).map(d => {
        const on = d === damaged
        return (
          <button key={String(d)} type="button" aria-pressed={on} disabled={disabled} onClick={() => onChange(d)}
            className={cn('min-h-[64px] px-3 py-2.5 rounded-xl text-start flex flex-col gap-0.5 disabled:opacity-70',
              on ? d ? 'border-[1.5px] border-critical bg-critical/[0.06]' : 'border-[1.5px] border-trace-blue bg-trace-blue/[0.06]'
                 : 'border border-line bg-surface')}>
            <span className="font-semibold text-primary">{t(d ? 'bazar.ret.cond.damaged' : 'bazar.ret.cond.good')}</span>
            <span className="text-small text-muted leading-snug">{t(d ? 'bazar.r4.damagedHint' : 'bazar.r4.goodHint')}</span>
          </button>
        )
      })}
    </div>
  )
}

/**
 * The dark money box: "GIVE BACK" / "CUSTOMER PAYS" and the amount, a note under. DOM order is label, amount, note (the
 * grid puts the note under the label) so the box reads "Give back 960 EGP …".
 */
export function MoneyBox({ label, amount, note, testId, big }: {
  label: string
  amount: ReactNode
  note?: string
  testId?: string
  big?: boolean
}) {
  return (
    <section data-testid={testId}
      className={cn('rounded-2xl bg-[#0D1117] text-[#F2F4F7] px-5 py-4 grid gap-x-4 gap-y-1 items-center',
        big ? 'grid-cols-1' : 'grid-cols-[minmax(0,1fr)_auto]')}>
      <span className="text-[11px] leading-4 font-bold uppercase tracking-[0.08em] text-sidebar-text col-start-1 row-start-1">{label}</span>
      {' '}
      <span dir="ltr" className={cn('font-bold tabular-nums',
        big ? 'text-[44px] leading-[52px] col-start-1 row-start-2' : 'text-[34px] leading-10 col-start-2 row-start-1 row-span-2 text-end')}>
        {amount}
      </span>
      {note && <span className={cn('text-small text-sidebar-text', big ? 'col-start-1 row-start-3' : 'col-start-1 row-start-2')}>{note}</span>}
    </section>
  )
}

/** The big confirm button. */
export function ConfirmButton({ children, onClick, busy, disabled, testId, tone = 'blue' }: {
  children: ReactNode
  onClick: () => void
  busy?: boolean
  disabled?: boolean
  testId?: string
  tone?: 'blue' | 'green'
}) {
  return (
    <button type="button" onClick={onClick} disabled={disabled || busy} data-testid={testId}
      className={cn('w-full min-h-[56px] px-4 rounded-xl text-body-lg font-bold text-white flex items-center justify-center gap-2 transition-colors disabled:opacity-60',
        tone === 'green' ? 'bg-[#16A34A] hover:bg-[#15803D]' : 'bg-trace-blue hover:bg-trace-blue-hover active:bg-trace-blue-active')}>
      {busy && <span aria-hidden className="w-4 h-4 rounded-full border-2 border-white/40 border-t-white animate-spin" />}
      {children}
    </button>
  )
}

/** A numbered step's tick ("Piece coming back ✓"). */
export function StepTick({ done }: { done: boolean }) {
  return (
    <span aria-hidden className={cn('w-6 h-6 rounded-md flex items-center justify-center shrink-0 text-white text-[13px] font-bold',
      done ? 'bg-[#16A34A]' : 'bg-charcoal text-muted')}>
      {done ? '✓' : ''}
    </span>
  )
}
