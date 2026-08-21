import {
  ReactNode, createContext, useContext, useState, useEffect, useRef,
} from 'react'
import type { LucideIcon } from 'lucide-react'
import {
  Check, Minus, ChevronDown, X,
  CheckCircle2, AlertCircle, AlertTriangle, Info,
  Loader2, Plus, Package,
} from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import type { Me } from '../api'

// ── Utility ───────────────────────────────────────────────────────────────────

export function cn(...classes: (string | false | null | undefined)[]): string {
  return classes.filter(Boolean).join(' ')
}

// ── Badge ─────────────────────────────────────────────────────────────────────

type BadgeTone = 'neutral' | 'success' | 'info' | 'warning' | 'critical'

const TONE_STYLE: Record<BadgeTone, string> = {
  neutral:  'bg-muted/[0.14] text-neutral-text border-muted/[0.30]',
  success:  'bg-success/[0.14] text-success-text border-success/[0.30]',
  info:     'bg-info/[0.14] text-info-text border-info/[0.30]',
  warning:  'bg-warning/[0.14] text-warning-text border-warning/[0.30]',
  critical: 'bg-critical/[0.14] text-critical-text border-critical/[0.30]',
}

const STATUS_TONE: Record<string, BadgeTone> = {
  available:                 'success',
  delivered:                 'success',
  with_courier:              'info',
  with_courier_order:        'info',
  confirmed:                 'info',
  awaiting_pickup_order:     'info',
  self_pickup_pending:       'info',
  packed:                    'info',
  awaiting_pickup:           'info',
  return_in_transit:         'warning',
  return_pending_inspection: 'warning',
  returned:                  'warning',
  reserved:                  'info',
  ready_to_pick:             'info',
  picking:                   'warning',
  returning:                 'warning',
  damaged:                   'critical',
  lost:                      'critical',
  exception:                 'critical',
  terminated:                'critical',
  destroyed:                 'neutral',
  new:                       'neutral',
  cancelled:                 'neutral',
  created:                   'neutral',
  out_on_transfer:           'warning',
  sold:                      'neutral',
}

export function Badge({
  status,
  tone,
  label,
  className = '',
}: {
  status?: string
  tone?: BadgeTone
  label?: string
  className?: string
}) {
  const { t, i18n } = useTranslation()
  const key   = status ?? ''
  const resolvedTone = tone ?? STATUS_TONE[key] ?? 'neutral'
  const style = TONE_STYLE[resolvedTone]
  const text  = label ?? t(`lookup.pieceStatus.${key}`, { defaultValue: key.replace(/_/g, ' ') })
  const isAr  = i18n.language === 'ar'

  return (
    <span className={cn('badge border', style, className)}>
      {isAr ? t(`lookup.pieceStatus.${key}`, { defaultValue: text }) : text}
    </span>
  )
}

// ── OrderBadge ────────────────────────────────────────────────────────────────

const ORDER_STATUS_KEY: Record<string, string> = {
  awaiting_pickup: 'awaiting_pickup_order',
  with_courier:    'with_courier_order',
}

export function OrderBadge({ status }: { status: string }) {
  const mapped = ORDER_STATUS_KEY[status] ?? status
  const tone   = STATUS_TONE[mapped] ?? STATUS_TONE[status] ?? 'neutral'
  return (
    <span className={cn('badge border', TONE_STYLE[tone])}>
      {status.replace(/_/g, ' ')}
    </span>
  )
}

// ── DeliveryBadge ─────────────────────────────────────────────────────────────

export function DeliveryBadge({
  state,
  exceptionReason,
  shipmentLeg,
  className = '',
}: {
  state?: string | null
  exceptionReason?: string | null
  shipmentLeg?: string | null
  className?: string
}) {
  const { t } = useTranslation()
  if (!state) {
    return (
      <span className={cn('badge border', TONE_STYLE.neutral, className)}>
        {t('delivery.state.awaiting')}
      </span>
    )
  }
  const tone  = STATUS_TONE[state] ?? 'neutral'
  const label = shipmentLeg === 'return'
    ? t(`delivery.state.return.${state}`, { defaultValue: t(`delivery.state.${state}`, { defaultValue: state.replace(/_/g, ' ') }) })
    : t(`delivery.state.${state}`, { defaultValue: state.replace(/_/g, ' ') })
  const reason = state === 'exception' && exceptionReason ? exceptionReason : null

  return (
    <span className={cn('inline-flex flex-col items-start gap-0.5', className)}>
      <span className={cn('badge border', TONE_STYLE[tone])}>{label}</span>
      {reason && <span className="text-caption text-muted leading-tight">{reason}</span>}
    </span>
  )
}

// ── OrderStatus ─────────────────────────────────────────────────────────────────
// FR-7/FR-11: the one derived headline (see OrderStatusDeriver on the backend).
// Folds DeliveryBadge's tone tokens (TONE_STYLE) in — this replaces the old
// independent pipeline-status / shipment-status pills, not a second badge next to them.

const DERIVED_TONE_STYLE: Record<import('../api').DerivedTone, BadgeTone> = {
  NEUTRAL: 'neutral',
  INFO:    'info',
  SUCCESS: 'success',
  WARN:    'warning',
  DANGER:  'critical',
}

// B1: OrderStatusDeriver.computeConflictKey() and ExceptionService's two cancellation
// detectors read the exact same predicate (order.status='cancelled' + the latest forward
// shipment's internal_state) — so whenever conflictKey is set, the matching exception is
// guaranteed to exist. Maps the display key to the exception `type` Exceptions.tsx filters
// on (?type=), not a second copy of the detection logic.
const CONFLICT_EXCEPTION_TYPE: Record<string, string> = {
  'status.conflict.live_shipment':          'cancelled_live_shipment',
  'status.conflict.cancelled_but_delivered': 'cancelled_but_delivered',
}

export function OrderStatus({
  derived,
  className = '',
}: {
  derived: import('../api').DerivedOrderStatus
  className?: string
}) {
  const { t } = useTranslation()

  return (
    <span className={cn('inline-flex flex-wrap items-center gap-1.5', className)}>
      <span className={cn('badge border', TONE_STYLE[DERIVED_TONE_STYLE[derived.tone]])}>
        {t(derived.primaryKey, { defaultValue: derived.primaryKey.replace(/^status\./, '').replace(/_/g, ' ') })}
      </span>

      {/* A3/B2: cancelled-order conflict flag — always DANGER-toned, links to the matching
          Part-B exception (filtered exception-queue view). */}
      {derived.conflictKey && (
        <Link
          to={`/exceptions?type=${CONFLICT_EXCEPTION_TYPE[derived.conflictKey] ?? ''}`}
          className={cn('badge border', TONE_STYLE.critical, 'hover:opacity-80 transition-opacity')}
        >
          {t(derived.conflictKey, { defaultValue: derived.conflictKey.replace(/^status\.conflict\./, '').replace(/_/g, ' ') })}
        </Link>
      )}

      {derived.healthChips.map((chip, i) => (
        <span
          key={i}
          className={cn('badge border', TONE_STYLE[DERIVED_TONE_STYLE[chip.tone]])}
        >
          {chip.count != null
            ? t(chip.key, { count: chip.count, defaultValue: `${chip.count}` })
            : t(chip.key, { defaultValue: chip.key.replace(/^chip\./, '').replace(/_/g, ' ') })}
        </span>
      ))}

      {derived.historicalNote && (
        <span className="text-caption text-muted leading-tight">
          {t(derived.historicalNote.key, { count: derived.historicalNote.count })}
        </span>
      )}

      {derived.notTraced && (
        <span className={cn('badge border', TONE_STYLE.neutral)}>
          {t('tag.not_traced')}
        </span>
      )}
    </span>
  )
}

// ── LegStatusBadge ────────────────────────────────────────────────────────────
// A3.1: a SINGLE shipment leg's own status — no order-level precedence (no cancelled/
// conflict/chips/notes, those are order-scoped). Backend computes the label from that
// shipment's internal_state alone (OrderStatusDeriver.deriveLegStatus) — this component
// only renders it. Used by the return-leg ShipmentCard; never the forward leg, whose
// status lives solely in the OrderStatus header above.

export function LegStatusBadge({
  legStatus,
  className = '',
}: {
  legStatus: { primaryKey: string; tone: import('../api').DerivedTone }
  className?: string
}) {
  const { t } = useTranslation()
  return (
    <span className={cn('badge border', TONE_STYLE[DERIVED_TONE_STYLE[legStatus.tone]], className)}>
      {t(legStatus.primaryKey, { defaultValue: legStatus.primaryKey.replace(/^status\./, '').replace(/_/g, ' ') })}
    </span>
  )
}

// ── SeverityBadge ─────────────────────────────────────────────────────────────

const SEV_TONE: Record<string, BadgeTone> = {
  CRITICAL: 'critical',
  HIGH:     'warning',
  MEDIUM:   'info',
  LOW:      'neutral',
}

export function SeverityBadge({ severity }: { severity: string }) {
  const tone = SEV_TONE[severity] ?? 'neutral'
  return (
    <span className={cn('badge border', TONE_STYLE[tone])}>
      {severity}
    </span>
  )
}

// ── Card ──────────────────────────────────────────────────────────────────────

export function Card({
  children,
  className = '',
  interactive = false,
  hoverable = false,
}: {
  children: ReactNode
  className?: string
  interactive?: boolean
  hoverable?: boolean
}) {
  return (
    <div
      className={cn(
        'card p-5',
        (interactive || hoverable) && 'hover:border-grey-600 hover:shadow-e3 transition-shadow cursor-pointer',
        className
      )}
    >
      {children}
    </div>
  )
}

// ── StatCard ─────────────────────────────────────────────────────────────────

export function StatCard({
  label,
  value,
  delta,
  deltaLabel,
  accent = false,
  sparkline,
}: {
  label: string
  value: string | number
  delta?: number
  deltaLabel?: string
  accent?: boolean
  sparkline?: ReactNode
}) {
  const deltaPositive = (delta ?? 0) >= 0
  return (
    <div className={cn('card p-5 flex flex-col gap-2', accent && 'border-trace-blue shadow-ring-accent')}>
      <p className="text-small text-muted uppercase tracking-wider">{label}</p>
      <p className={cn('text-h2 font-mono', accent ? 'text-trace-blue' : 'text-primary')}>
        {typeof value === 'number' ? value.toLocaleString() : value}
      </p>
      {delta !== undefined && (
        <p className={cn('text-small font-medium flex items-center gap-1', deltaPositive ? 'text-success' : 'text-critical')}>
          <span>{deltaPositive ? '↑' : '↓'}</span>
          <span>{Math.abs(delta).toLocaleString()} {deltaLabel}</span>
        </p>
      )}
      {sparkline && <div className="mt-1">{sparkline}</div>}
    </div>
  )
}

// ── MiniStat ──────────────────────────────────────────────────────────────────
// Denser, icon-topped tile — distinct from StatCard (label/value/delta) for the
// Overview dashboard's "Needs attention" row. Navigation stays external (wrap in
// <Link>), same convention StatCard's own callers already use.

const MINI_STAT_TONE: Record<'neutral' | 'warning' | 'critical', string> = {
  neutral:  'border-line',
  warning:  'border-warning/30',
  critical: 'border-critical/30',
}

export function MiniStat({
  icon: Icon,
  value,
  label,
  tone = 'neutral',
}: {
  icon: LucideIcon
  value: string | number
  label: ReactNode
  tone?: 'neutral' | 'warning' | 'critical'
}) {
  return (
    <div className={cn('card p-3.5 flex flex-col gap-1.5 border', MINI_STAT_TONE[tone])}>
      <Icon size={16} strokeWidth={1.75} className="text-muted" />
      <p className="text-h3 font-mono text-primary">
        {typeof value === 'number' ? value.toLocaleString() : value}
      </p>
      <p className="text-caption text-muted">{label}</p>
    </div>
  )
}

// ── SectionHeader ─────────────────────────────────────────────────────────────
// note is optional — omit it for a bare uppercase label (e.g. dashboard zone
// headers); pass it for the fuller title+subtitle+badge treatment.

export function SectionHeader({
  title,
  note,
  badge,
}: {
  title: string
  note?: string
  badge?: string
}) {
  if (!note) {
    return (
      <div className="flex items-center gap-2 mb-2">
        <h2 className="text-caption font-bold text-muted uppercase tracking-wider">{title}</h2>
        {badge && <Badge tone="info" label={badge} />}
      </div>
    )
  }
  return (
    <div className="flex items-center gap-3 mb-3">
      <div>
        <h2 className="text-h3 text-primary inline-flex items-center gap-2">
          {title}
          {badge && <Badge tone="info" label={badge} />}
        </h2>
        <p className="text-caption text-muted mt-0.5">{note}</p>
      </div>
    </div>
  )
}

// ── Button ────────────────────────────────────────────────────────────────────

type ButtonVariant = 'primary' | 'secondary' | 'tertiary' | 'destructive' | 'brand' | 'outline' | 'danger' | 'ghost'
type ButtonSize    = 'sm' | 'md' | 'lg'

const BTN_VARIANT: Record<ButtonVariant, string> = {
  primary:     'bg-trace-blue hover:bg-trace-blue-hover hover:shadow-glow active:bg-trace-blue-active active:shadow-none text-white',
  brand:       'bg-trace-blue hover:bg-trace-blue-hover hover:shadow-glow active:bg-trace-blue-active active:shadow-none text-white',
  secondary:   'bg-transparent border border-line text-primary hover:bg-elevated hover:border-[#3A4250] active:bg-charcoal',
  outline:     'bg-transparent border border-line text-primary hover:bg-elevated hover:border-[#3A4250] active:bg-charcoal',
  tertiary:    'text-trace-blue bg-transparent hover:bg-trace-blue/10 hover:text-trace-blue-hover active:bg-trace-blue/[0.18] active:text-trace-blue-active',
  ghost:       'text-muted hover:text-primary hover:bg-elevated bg-transparent',
  destructive: 'bg-critical hover:bg-[#B91C1C] active:bg-[#991B1B] text-white',
  danger:      'bg-critical hover:bg-[#B91C1C] active:bg-[#991B1B] text-white',
}

const BTN_SIZE: Record<ButtonSize, string> = {
  sm: 'px-3 py-1.5 text-small gap-1.5',
  md: 'px-4 py-2 text-body',
  lg: 'px-5 py-3 text-body font-semibold',
}

export function Button({
  children,
  variant = 'primary',
  size = 'md',
  disabled,
  loading,
  onClick,
  type = 'button',
  className = '',
  iconStart,
  iconEnd,
}: {
  children?: ReactNode
  variant?: ButtonVariant
  size?: ButtonSize
  disabled?: boolean
  loading?: boolean
  onClick?: () => void
  type?: 'button' | 'submit'
  className?: string
  iconStart?: LucideIcon
  iconEnd?: LucideIcon
}) {
  const IconStart = iconStart
  const IconEnd   = iconEnd
  return (
    <button
      type={type}
      disabled={disabled ?? loading}
      onClick={onClick}
      className={cn(
        'btn rounded-xl transition-colors',
        BTN_VARIANT[variant],
        BTN_SIZE[size],
        className
      )}
    >
      {loading
        ? <Loader2 size={16} strokeWidth={2} className="animate-spin" />
        : IconStart && <IconStart size={16} strokeWidth={2} />
      }
      {children}
      {!loading && IconEnd && <IconEnd size={16} strokeWidth={2} />}
    </button>
  )
}

// ── Input ─────────────────────────────────────────────────────────────────────

export function Input({
  scan,
  variant,
  invalid,
  error,
  iconStart,
  className = '',
  ...props
}: React.InputHTMLAttributes<HTMLInputElement> & {
  scan?: boolean
  variant?: 'default' | 'scan'
  invalid?: boolean
  error?: string
  iconStart?: LucideIcon
}) {
  const isScan = scan || variant === 'scan'
  const IconS  = iconStart
  return (
    <div className="flex flex-col gap-1 w-full">
      <div className="relative w-full">
        {IconS && (
          <span className="absolute start-3 top-1/2 -translate-y-1/2 text-muted pointer-events-none">
            <IconS size={16} strokeWidth={2} />
          </span>
        )}
        <input
          {...props}
          className={cn(
            isScan ? 'input-scan' : 'input',
            IconS && 'ps-9',
            invalid && 'border-critical focus:border-critical focus:ring-critical/20',
            className
          )}
        />
      </div>
      {error && <p className="text-small text-critical">{error}</p>}
    </div>
  )
}

// ── Spinner ───────────────────────────────────────────────────────────────────

export function Spinner({ size = 20 }: { size?: number }) {
  return <Loader2 size={size} strokeWidth={2} className="animate-spin text-trace-blue" aria-hidden />
}

// ── EmptyState ────────────────────────────────────────────────────────────────

export function EmptyState({
  message,
  icon = '✦',
  action,
}: {
  message: string
  icon?: string
  action?: { label: string; onClick: () => void }
}) {
  return (
    <div className="flex flex-col items-center justify-center py-16 gap-3 text-center">
      <div className="w-12 h-12 rounded-xl bg-elevated flex items-center justify-center text-xl">
        {icon}
      </div>
      <p className="text-body font-semibold text-primary">{message}</p>
      {action && (
        <Button variant="primary" size="sm" onClick={action.onClick} className="mt-1">
          {action.label}
        </Button>
      )}
    </div>
  )
}

// ── Modal ─────────────────────────────────────────────────────────────────────

export function Modal({
  children,
  onClose,
  title,
}: {
  children: ReactNode
  onClose: () => void
  title?: string
}) {
  return (
    <div
      className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-overlay p-4"
      onClick={onClose}
    >
      <div
        className="bg-surface rounded-2xl border border-line shadow-e4 w-full max-w-md animate-fadeIn z-modal"
        onClick={e => e.stopPropagation()}
      >
        {title && (
          <div className="flex items-center justify-between px-6 py-4 border-b border-line">
            <h3 className="text-h3 text-primary">{title}</h3>
            <button
              onClick={onClose}
              className="text-muted hover:text-primary transition-colors p-0.5"
            >
              <X size={18} strokeWidth={2} />
            </button>
          </div>
        )}
        <div className="p-6">{children}</div>
      </div>
    </div>
  )
}

// ── Select ────────────────────────────────────────────────────────────────────

export type SelectOption = { value: string; label: string }

export function Select({
  value,
  onChange,
  options,
  placeholder = 'Select…',
  disabled = false,
  allowCreate = false,
  onCreate,
  className = '',
}: {
  value?: string
  onChange: (value: string) => void
  options: SelectOption[]
  placeholder?: string
  disabled?: boolean
  allowCreate?: boolean
  onCreate?: (label: string) => void
  className?: string
}) {
  const [open, setOpen]               = useState(false)
  const [search, setSearch]           = useState('')
  const [highlighted, setHighlighted] = useState(0)
  const containerRef = useRef<HTMLDivElement>(null)
  const searchRef    = useRef<HTMLInputElement>(null)

  const filtered = options.filter(o =>
    o.label.toLowerCase().includes(search.toLowerCase())
  )
  const selected = options.find(o => o.value === value)

  useEffect(() => {
    function onOutside(e: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false)
      }
    }
    if (open) {
      document.addEventListener('mousedown', onOutside)
      setTimeout(() => searchRef.current?.focus(), 0)
    }
    return () => document.removeEventListener('mousedown', onOutside)
  }, [open])

  function handleSelect(opt: SelectOption) {
    onChange(opt.value)
    setOpen(false)
    setSearch('')
    setHighlighted(0)
  }

  function handleKeyDown(e: React.KeyboardEvent) {
    if (!open) { if (e.key === 'Enter' || e.key === ' ') setOpen(true); return }
    if (e.key === 'ArrowDown') { e.preventDefault(); setHighlighted(h => Math.min(h + 1, filtered.length - 1)) }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setHighlighted(h => Math.max(h - 1, 0)) }
    else if (e.key === 'Enter') { e.preventDefault(); if (filtered[highlighted]) handleSelect(filtered[highlighted]) }
    else if (e.key === 'Escape') { setOpen(false); setSearch('') }
  }

  return (
    <div ref={containerRef} className={cn('relative', className)}>
      <button
        type="button"
        disabled={disabled}
        onClick={() => setOpen(o => !o)}
        onKeyDown={handleKeyDown}
        className={cn(
          'w-full flex items-center justify-between gap-2 input text-start',
          open && 'border-brand ring-1 ring-brand',
          disabled && 'opacity-40 cursor-not-allowed'
        )}
      >
        <span className={selected ? 'text-primary' : 'text-muted'}>
          {selected ? selected.label : placeholder}
        </span>
        <ChevronDown size={16} strokeWidth={2} className={cn('text-muted transition-transform flex-shrink-0', open && 'rotate-180')} />
      </button>

      {open && (
        <div className="absolute z-dropdown top-full mt-1 w-full bg-surface border border-line rounded-lg shadow-e3 overflow-hidden">
          <div className="p-2 border-b border-line">
            <input
              ref={searchRef}
              type="text"
              value={search}
              onChange={e => { setSearch(e.target.value); setHighlighted(0) }}
              onKeyDown={handleKeyDown}
              placeholder="Search…"
              className="w-full bg-elevated border border-line rounded text-body text-primary px-3 py-1.5 placeholder-muted focus:border-brand outline-none text-small"
            />
          </div>
          <ul className="max-h-52 overflow-y-auto py-1">
            {filtered.map((opt, i) => (
              <li key={opt.value}>
                <button
                  type="button"
                  onClick={() => handleSelect(opt)}
                  className={cn(
                    'w-full flex items-center justify-between px-3 py-2 text-body text-start transition-colors',
                    i === highlighted ? 'bg-white/5 text-primary' : 'text-muted hover:bg-white/5 hover:text-primary'
                  )}
                >
                  <span>{opt.label}</span>
                  {opt.value === value && <Check size={14} strokeWidth={2.5} className="text-trace-blue" />}
                </button>
              </li>
            ))}
            {filtered.length === 0 && !allowCreate && (
              <li className="px-3 py-4 text-small text-muted text-center">No results</li>
            )}
            {allowCreate && onCreate && search && (
              <li>
                <button
                  type="button"
                  onClick={() => { onCreate(search); setOpen(false); setSearch('') }}
                  className="w-full flex items-center gap-2 px-3 py-2 text-body text-trace-blue hover:bg-white/5 transition-colors"
                >
                  <Plus size={14} strokeWidth={2} />
                  Create "{search}"
                </button>
              </li>
            )}
          </ul>
        </div>
      )}
    </div>
  )
}

// ── Checkbox ──────────────────────────────────────────────────────────────────

export function Checkbox({
  checked,
  onChange,
  label,
  disabled = false,
  indeterminate = false,
}: {
  checked: boolean
  onChange: (checked: boolean) => void
  label?: string
  disabled?: boolean
  indeterminate?: boolean
}) {
  const ref = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (ref.current) ref.current.indeterminate = indeterminate && !checked
  }, [indeterminate, checked])

  return (
    <label className={cn('inline-flex items-center gap-2 cursor-pointer select-none', disabled && 'opacity-40 cursor-not-allowed')}>
      <input
        ref={ref}
        type="checkbox"
        checked={checked}
        onChange={e => onChange(e.target.checked)}
        disabled={disabled}
        className="sr-only"
      />
      <span className={cn(
        'w-4 h-4 rounded-sm border flex items-center justify-center flex-shrink-0 transition-colors',
        checked || indeterminate ? 'bg-trace-blue border-trace-blue' : 'bg-surface border-line'
      )}>
        {checked && <Check size={11} strokeWidth={3} className="text-white" />}
        {!checked && indeterminate && <Minus size={11} strokeWidth={3} className="text-white" />}
      </span>
      {label && <span className="text-body text-primary">{label}</span>}
    </label>
  )
}

// ── Radio ─────────────────────────────────────────────────────────────────────

export function Radio({
  value,
  checked,
  onChange,
  label,
  disabled = false,
}: {
  value: string
  checked: boolean
  onChange: (value: string) => void
  label?: string
  disabled?: boolean
}) {
  return (
    <label className={cn('inline-flex items-center gap-2 cursor-pointer select-none', disabled && 'opacity-40 cursor-not-allowed')}>
      <input
        type="radio"
        checked={checked}
        onChange={() => onChange(value)}
        disabled={disabled}
        className="sr-only"
      />
      <span className={cn(
        'w-4 h-4 rounded-full border flex items-center justify-center flex-shrink-0 transition-colors',
        checked ? 'border-trace-blue' : 'border-line bg-surface'
      )}>
        {checked && <span className="w-2 h-2 rounded-full bg-trace-blue" />}
      </span>
      {label && <span className="text-body text-primary">{label}</span>}
    </label>
  )
}

// ── Toggle ────────────────────────────────────────────────────────────────────

export function Toggle({
  checked,
  onChange,
  label,
  disabled = false,
  size = 'md',
}: {
  checked: boolean
  onChange: (checked: boolean) => void
  label?: string
  disabled?: boolean
  size?: 'sm' | 'md'
}) {
  // Knob uses start-* (inset-inline-start) so it slides toward the correct
  // physical edge in both LTR and RTL without any rtl: variant override.
  const knobOn  = size === 'sm' ? 'start-[18px]' : 'start-[22px]'
  const knobOff = 'start-0.5'

  return (
    <label className={cn('inline-flex items-center gap-3 cursor-pointer select-none', disabled && 'opacity-40 cursor-not-allowed')}>
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        disabled={disabled}
        onClick={() => !disabled && onChange(!checked)}
        className={cn(
          'relative rounded-full transition-colors duration-200 flex-shrink-0',
          size === 'sm' ? 'w-9 h-5' : 'w-11 h-6',
          checked ? 'bg-trace-blue' : 'bg-line'
        )}
      >
        <span
          className={cn(
            'absolute top-0.5 rounded-full bg-white shadow-e1 transition-all duration-200',
            size === 'sm' ? 'w-4 h-4' : 'w-5 h-5',
            checked ? knobOn : knobOff
          )}
        />
      </button>
      {label && <span className="text-body text-primary">{label}</span>}
    </label>
  )
}

// ── Tabs ──────────────────────────────────────────────────────────────────────

export function Tabs({
  tabs,
  activeKey,
  onChange,
}: {
  tabs: Array<{ key: string; label: string; count?: number; disabled?: boolean }>
  activeKey: string
  onChange: (key: string) => void
}) {
  return (
    <div className="flex border-b border-line gap-6">
      {tabs.map(tab => (
        <button
          key={tab.key}
          type="button"
          disabled={tab.disabled}
          onClick={() => onChange(tab.key)}
          className={cn(
            'pb-3 text-body transition-colors border-b-2 -mb-px inline-flex items-center gap-2',
            tab.key === activeKey
              ? 'text-primary border-trace-blue'
              : 'text-muted border-transparent hover:text-primary',
            tab.disabled && 'cursor-default'
          )}
        >
          {tab.label}
          {tab.count != null && (
            <span className="text-caption font-semibold text-muted bg-elevated border border-line rounded-full px-2 py-0.5">
              {tab.count.toLocaleString()}
            </span>
          )}
        </button>
      ))}
    </div>
  )
}

// ── SegmentedControl ──────────────────────────────────────────────────────────

export function SegmentedControl({
  options,
  value,
  onChange,
}: {
  options: Array<{ value: string; label: string }>
  value: string
  onChange: (value: string) => void
}) {
  return (
    <div className="inline-flex bg-charcoal border border-line rounded-lg p-1 gap-0.5">
      {options.map(opt => (
        <button
          key={opt.value}
          type="button"
          onClick={() => onChange(opt.value)}
          className={cn(
            'px-3 py-1.5 text-small rounded-md transition-colors',
            opt.value === value
              ? 'bg-surface text-primary shadow-e1'
              : 'text-muted hover:text-primary'
          )}
        >
          {opt.label}
        </button>
      ))}
    </div>
  )
}

// ── FilterChip ────────────────────────────────────────────────────────────────

export function FilterChip({
  label,
  onRemove,
  dateRange,
}: {
  label?: string
  onRemove?: () => void
  dateRange?: { start: string; end: string }
}) {
  const text = dateRange ? `${dateRange.start} → ${dateRange.end}` : (label ?? '')
  return (
    <span className="inline-flex items-center gap-1.5 bg-white/5 border border-line rounded-full text-small text-primary px-3 py-1">
      {text}
      {onRemove && (
        <button
          type="button"
          onClick={onRemove}
          className="text-muted hover:text-primary transition-colors ms-0.5"
        >
          <X size={12} strokeWidth={2.5} />
        </button>
      )}
    </span>
  )
}

// ── Tooltip ───────────────────────────────────────────────────────────────────

export function Tooltip({
  content,
  title,
  children,
}: {
  content: string
  title?: string
  children: ReactNode
}) {
  return (
    <div className="relative inline-block group">
      {children}
      <div
        className={cn(
          'absolute z-dropdown bottom-full mb-2 left-1/2 -translate-x-1/2',
          'hidden group-hover:block pointer-events-none',
          'bg-elevated border border-line rounded-md shadow-e2',
          'text-small text-primary px-3 py-2 whitespace-nowrap max-w-xs'
        )}
      >
        {title && <p className="font-medium mb-0.5">{title}</p>}
        <p className="text-muted">{content}</p>
      </div>
    </div>
  )
}

// ── Avatar ────────────────────────────────────────────────────────────────────

function initials(name: string): string {
  return name.split(' ').slice(0, 2).map(w => w[0]).join('').toUpperCase()
}

export function Avatar({
  name,
  role,
  size = 'md',
}: {
  name: string
  role?: string
  size?: 'sm' | 'md'
}) {
  return (
    <div className="inline-flex items-center gap-2.5">
      <span className={cn(
        'rounded-full bg-trace-blue/15 text-trace-blue font-medium flex items-center justify-center flex-shrink-0',
        size === 'sm' ? 'w-7 h-7 text-small' : 'w-9 h-9 text-body'
      )}>
        {initials(name)}
      </span>
      {(name || role) && (
        <span className="flex flex-col">
          <span className="text-body text-primary leading-tight">{name}</span>
          {role && <span className="text-small text-muted leading-tight">{role}</span>}
        </span>
      )}
    </div>
  )
}

// ── ProductThumb ──────────────────────────────────────────────────────────────

// Shopify CDN resize param — list/card thumbnail only, never the full-size image.
// Purely a render-time transform on the URL we already have; nothing is stored or
// re-hosted. Width is caller-supplied (not a single hardcoded value) so a wider
// card can request a proportionally larger source instead of upscaling a tiny one.
function shopifyThumbUrl(url: string, width: number): string {
  return `${url}${url.includes('?') ? '&' : '?'}width=${width}`
}

/**
 * Tile for every state (image / null / empty / failed load) so a row's or card's
 * layout never shifts depending on whether this particular product has an image
 * yet. Shared across Catalog (fixed 40x40 row thumbnail) and Receiving's
 * product-selection grid (fill-mode card photo) — one thumbnail implementation,
 * not forked per screen.
 *
 * fill mode stretches to 100% of the parent's box with NO aspect ratio of its
 * own — the caller controls aspect (square, short thumbnail strip, etc.) via its
 * own wrapper height/aspect class, since different callers want different shapes.
 */
export function ProductThumb({
  src,
  alt,
  size = 40,
  fill = false,
  rounded = 'lg',
  placeholderLabel,
  cdnWidth = 96,
  objectFit = 'cover',
  className = '',
}: {
  src: string | null
  alt: string
  size?: number
  fill?: boolean
  /** 'none' when nesting inside an already-rounded, overflow-hidden card — avoids double-rounded corners. */
  rounded?: 'lg' | 'none'
  placeholderLabel?: string
  /** Shopify CDN source width to request — size for the actual rendered width, not a one-size-fits-all default. */
  cdnWidth?: number
  /** 'contain' shows the whole product (letterboxed) inside the same frame; 'cover' (default) crops to fill it. */
  objectFit?: 'cover' | 'contain'
  className?: string
}) {
  const [failed, setFailed] = useState(false)
  const showImage = !!src && !failed
  const iconSize = fill ? 22 : Math.max(12, Math.round(size * 0.4))

  return (
    <div
      className={cn(
        'bg-elevated border border-line flex items-center justify-center flex-shrink-0 overflow-hidden',
        rounded === 'lg' && 'rounded-lg',
        fill && 'w-full h-full',
        className
      )}
      style={fill ? undefined : { width: size, height: size }}
    >
      {showImage ? (
        <img
          src={shopifyThumbUrl(src, cdnWidth)}
          alt={alt}
          loading="lazy"
          className={cn('w-full h-full', objectFit === 'contain' ? 'object-contain' : 'object-cover')}
          onError={() => setFailed(true)}
        />
      ) : (
        <div className="flex flex-col items-center gap-1">
          <Package size={iconSize} strokeWidth={1.75} className="text-muted" />
          {placeholderLabel && (
            <span className="text-[10px] text-muted/70 leading-none">{placeholderLabel}</span>
          )}
        </div>
      )}
    </div>
  )
}

// ── Alert ─────────────────────────────────────────────────────────────────────

const ALERT_STYLE: Record<string, { border: string; bg: string; icon: typeof CheckCircle2; text: string }> = {
  success:  { border: 'border-success/30',  bg: 'bg-success/[0.14]',  icon: CheckCircle2,  text: 'text-success-text' },
  info:     { border: 'border-info/30',     bg: 'bg-info/[0.14]',     icon: Info,          text: 'text-info-text' },
  warning:  { border: 'border-warning/30',  bg: 'bg-warning/[0.14]',  icon: AlertTriangle, text: 'text-warning-text' },
  critical: { border: 'border-critical/30', bg: 'bg-critical/[0.14]', icon: AlertCircle,   text: 'text-critical-text' },
}

export function Alert({
  tone,
  title,
  children,
  dismissible = false,
  onDismiss,
}: {
  tone: 'success' | 'info' | 'warning' | 'critical'
  title: string
  children?: ReactNode
  dismissible?: boolean
  onDismiss?: () => void
}) {
  const s    = ALERT_STYLE[tone]
  const Icon = s.icon
  return (
    <div className={cn('flex gap-3 rounded-lg px-3.5 py-3 border', s.border, s.bg)}>
      <Icon size={18} strokeWidth={2} className={cn('flex-shrink-0 mt-0.5', s.text)} />
      <div className="flex-1 min-w-0">
        <p className={cn('text-body font-medium', s.text)}>{title}</p>
        {children && <div className="text-body text-primary mt-1">{children}</div>}
      </div>
      {dismissible && (
        <button
          type="button"
          onClick={onDismiss}
          className="text-muted hover:text-primary transition-colors flex-shrink-0 self-start"
        >
          <X size={16} strokeWidth={2} />
        </button>
      )}
    </div>
  )
}

// ── Toast ─────────────────────────────────────────────────────────────────────

type ToastTone = 'success' | 'info' | 'warning' | 'error'

interface ToastItem {
  id: string
  tone: ToastTone
  message: string
  action?: { label: string; onClick: () => void }
  duration?: number
}

const TOAST_STYLE: Record<ToastTone, { icon: typeof CheckCircle2; text: string }> = {
  success: { icon: CheckCircle2,  text: 'text-success-text' },
  info:    { icon: Info,          text: 'text-info-text' },
  warning: { icon: AlertTriangle, text: 'text-warning-text' },
  error:   { icon: AlertCircle,   text: 'text-critical-text' },
}

function ToastCard({ tone, message, action, onDismiss }: Omit<ToastItem, 'id' | 'duration'> & { onDismiss: () => void }) {
  const s    = TOAST_STYLE[tone]
  const Icon = s.icon
  return (
    <div className="flex items-start gap-3 bg-elevated border border-line rounded-lg shadow-e2 px-4 py-3 min-w-[280px] max-w-sm animate-fadeIn">
      <Icon size={16} strokeWidth={2} className={cn('flex-shrink-0 mt-0.5', s.text)} />
      <p className="text-body text-primary flex-1 min-w-0">{message}</p>
      {action && (
        <button
          type="button"
          onClick={action.onClick}
          className="text-small text-trace-blue hover:underline flex-shrink-0"
        >
          {action.label}
        </button>
      )}
      <button
        type="button"
        onClick={onDismiss}
        className="text-muted hover:text-primary transition-colors flex-shrink-0"
      >
        <X size={14} strokeWidth={2} />
      </button>
    </div>
  )
}

// ── Me (shell identity) ──────────────────────────────────────────────────────
// Layout is the sole fetcher of /me (its own real-identity-in-sidebar effect).
// Other pages that need the identity (e.g. Overview's greeting) read it from
// here instead of issuing their own /me call — same pattern as ToastContext
// below: a context whose only provider is Layout, thrown if read outside it.

const MeContext = createContext<Me | null | undefined>(undefined)

export function useMe(): Me | null {
  const ctx = useContext(MeContext)
  if (ctx === undefined) throw new Error('useMe must be used within MeProvider')
  return ctx
}

export function MeProvider({ me, children }: { me: Me | null; children: ReactNode }) {
  return <MeContext.Provider value={me}>{children}</MeContext.Provider>
}

const ToastContext = createContext<{ toast: (item: Omit<ToastItem, 'id'>) => void } | null>(null)

export function useToast() {
  const ctx = useContext(ToastContext)
  if (!ctx) throw new Error('useToast must be used within ToastProvider')
  return ctx
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastItem[]>([])
  const timers = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map())

  function dismiss(id: string) {
    setToasts(prev => prev.filter(t => t.id !== id))
    const t = timers.current.get(id)
    if (t) { clearTimeout(t); timers.current.delete(id) }
  }

  function toast(item: Omit<ToastItem, 'id'>) {
    const id  = Math.random().toString(36).slice(2)
    const dur = item.duration ?? 4000
    setToasts(prev => [...prev, { ...item, id }])
    const t = setTimeout(() => dismiss(id), dur)
    timers.current.set(id, t)
  }

  return (
    <ToastContext.Provider value={{ toast }}>
      {children}
      {/* Toast stack: fixed bottom, end side — end-4 flips to left in RTL */}
      <div className="fixed bottom-4 end-4 z-dropdown flex flex-col gap-2 pointer-events-none">
        {toasts.map(item => (
          <div key={item.id} className="pointer-events-auto">
            <ToastCard {...item} onDismiss={() => dismiss(item.id)} />
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}

// ── Progress ──────────────────────────────────────────────────────────────────

export function Progress({
  value,
  className = '',
}: {
  value?: number   // 0-100; undefined = indeterminate
  className?: string
}) {
  const indeterminate = value === undefined
  return (
    <div className={cn('w-full bg-white/10 rounded-full h-1.5 overflow-hidden relative', className)}>
      <div
        className={cn(
          'rounded-full bg-trace-blue',
          indeterminate ? 'absolute w-2/5 h-full animate-indet' : 'h-full transition-all duration-300'
        )}
        style={indeterminate ? undefined : { width: `${Math.min(100, Math.max(0, value ?? 0))}%` }}
      />
    </div>
  )
}

// ── Skeleton ──────────────────────────────────────────────────────────────────

export function Skeleton({ className = '' }: { className?: string }) {
  return (
    <div
      className={cn(
        'rounded animate-shimmer bg-[length:400px_100%]',
        'bg-[linear-gradient(90deg,#161B22_25%,#1E2530_37%,#161B22_63%)]',
        className
      )}
    />
  )
}

export function TableSkeleton({ rows = 5, cols = 6 }: { rows?: number; cols?: number }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full">
        <thead>
          <tr className="border-b border-line">
            {Array.from({ length: cols }).map((_, i) => (
              <th key={i} className="tbl-header">
                <Skeleton className="h-3 w-20" />
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {Array.from({ length: rows }).map((_, r) => (
            <tr key={r} className="border-b border-line">
              {Array.from({ length: cols }).map((_, c) => (
                <td key={c} className="tbl-cell">
                  <Skeleton className={cn('h-4', c === 0 ? 'w-32' : 'w-24')} />
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

// ── DataTable ─────────────────────────────────────────────────────────────────

export type DataTableColumn<T> = {
  key: string
  header: string
  render: (row: T) => ReactNode
  align?: 'start' | 'end' | 'center'
  mono?: boolean
}

const ALIGN_CLASS = { start: 'text-start', end: 'text-end', center: 'text-center' }

export function DataTable<T extends { id: string }>({
  columns,
  rows,
  loading = false,
  emptyMessage = 'No data',
  skeletonRows = 5,
  onRowClick,
}: {
  columns: DataTableColumn<T>[]
  rows: T[]
  loading?: boolean
  emptyMessage?: string
  skeletonRows?: number
  onRowClick?: (row: T) => void
}) {
  if (loading) return <TableSkeleton rows={skeletonRows} cols={columns.length} />

  return (
    <div className="overflow-x-auto">
      <table className="w-full">
        <thead>
          <tr className="border-b border-line">
            {columns.map(col => (
              <th key={col.key} className={cn('tbl-header', ALIGN_CLASS[col.align ?? 'start'])}>
                {col.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 ? (
            <tr>
              <td colSpan={columns.length}>
                <EmptyState message={emptyMessage} />
              </td>
            </tr>
          ) : rows.map(row => (
            <tr
              key={row.id}
              className={cn('tbl-row group', onRowClick && 'cursor-pointer')}
              onClick={() => onRowClick?.(row)}
            >
              {columns.map(col => (
                <td
                  key={col.key}
                  className={cn('tbl-cell', ALIGN_CLASS[col.align ?? 'start'], col.mono && 'font-mono')}
                >
                  {col.render(row)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
