import {
  ReactNode, createContext, useContext, useState, useEffect, useRef,
} from 'react'
import type { LucideIcon } from 'lucide-react'
import {
  Check, Minus, ChevronDown, X,
  CheckCircle2, AlertCircle, AlertTriangle, Info,
  Loader2, Plus,
} from 'lucide-react'
import { useTranslation } from 'react-i18next'

// ── Utility ───────────────────────────────────────────────────────────────────

export function cn(...classes: (string | false | null | undefined)[]): string {
  return classes.filter(Boolean).join(' ')
}

// ── Badge ─────────────────────────────────────────────────────────────────────

type BadgeTone = 'neutral' | 'success' | 'info' | 'warning' | 'critical'

const TONE_STYLE: Record<BadgeTone, string> = {
  neutral:  'bg-muted/10 text-muted border-muted/20',
  success:  'bg-success/10 text-success border-success/20',
  info:     'bg-info/10 text-info border-info/20',
  warning:  'bg-warning/10 text-warning border-warning/20',
  critical: 'bg-critical/10 text-critical border-critical/20',
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
  reserved:                  'warning',
  ready_to_pick:             'warning',
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
        (interactive || hoverable) && 'hover:border-grey-600 hover:shadow-e2 transition-shadow cursor-pointer',
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
    <div className="card p-5 flex flex-col gap-2">
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

// ── Button ────────────────────────────────────────────────────────────────────

type ButtonVariant = 'primary' | 'secondary' | 'tertiary' | 'destructive' | 'brand' | 'outline' | 'danger' | 'ghost'
type ButtonSize    = 'sm' | 'md' | 'lg'

const BTN_VARIANT: Record<ButtonVariant, string> = {
  primary:     'bg-trace-blue hover:bg-trace-blue-hover active:bg-trace-blue-active text-white',
  brand:       'bg-trace-blue hover:bg-trace-blue-hover active:bg-trace-blue-active text-white',
  secondary:   'bg-transparent border border-line text-primary hover:bg-white/5',
  outline:     'bg-transparent border border-line text-primary hover:bg-white/5',
  tertiary:    'text-trace-blue hover:underline hover:bg-trace-blue/10 bg-transparent',
  ghost:       'text-muted hover:text-primary hover:bg-elevated bg-transparent',
  destructive: 'bg-critical hover:bg-critical/90 text-white',
  danger:      'bg-critical hover:bg-critical/90 text-white',
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
        'btn rounded-xl transition-colors disabled:opacity-40 disabled:cursor-not-allowed',
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
            invalid && 'border-critical focus:border-critical focus:ring-critical',
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
    <div className="flex flex-col items-center justify-center py-16 gap-3 text-muted">
      <span className="text-4xl opacity-30">{icon}</span>
      <p className="text-body">{message}</p>
      {action && (
        <button
          onClick={action.onClick}
          className="text-small text-trace-blue hover:underline mt-1"
        >
          {action.label}
        </button>
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
  tabs: Array<{ key: string; label: string }>
  activeKey: string
  onChange: (key: string) => void
}) {
  return (
    <div className="flex border-b border-line gap-6">
      {tabs.map(tab => (
        <button
          key={tab.key}
          type="button"
          onClick={() => onChange(tab.key)}
          className={cn(
            'pb-3 text-body transition-colors border-b-2 -mb-px',
            tab.key === activeKey
              ? 'text-primary border-trace-blue'
              : 'text-muted border-transparent hover:text-primary'
          )}
        >
          {tab.label}
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

// ── Alert ─────────────────────────────────────────────────────────────────────

const ALERT_STYLE: Record<string, { border: string; bg: string; icon: typeof CheckCircle2; text: string }> = {
  success:  { border: 'border-s-4 border-success',  bg: 'bg-success/10',  icon: CheckCircle2,  text: 'text-success' },
  info:     { border: 'border-s-4 border-info',     bg: 'bg-info/10',     icon: Info,          text: 'text-info' },
  warning:  { border: 'border-s-4 border-warning',  bg: 'bg-warning/10',  icon: AlertTriangle, text: 'text-warning' },
  critical: { border: 'border-s-4 border-critical', bg: 'bg-critical/10', icon: AlertCircle,   text: 'text-critical' },
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
    <div className={cn('flex gap-3 rounded-lg p-4', s.border, s.bg)}>
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

const TOAST_STYLE: Record<ToastTone, { icon: typeof CheckCircle2; text: string; border: string }> = {
  success: { icon: CheckCircle2,  text: 'text-success',  border: 'border-s-2 border-success' },
  info:    { icon: Info,          text: 'text-info',     border: 'border-s-2 border-info' },
  warning: { icon: AlertTriangle, text: 'text-warning',  border: 'border-s-2 border-warning' },
  error:   { icon: AlertCircle,   text: 'text-critical', border: 'border-s-2 border-critical' },
}

function ToastCard({ tone, message, action, onDismiss }: Omit<ToastItem, 'id' | 'duration'> & { onDismiss: () => void }) {
  const s    = TOAST_STYLE[tone]
  const Icon = s.icon
  return (
    <div className={cn(
      'flex items-start gap-3 bg-elevated border border-line rounded-lg shadow-e3 px-4 py-3 min-w-[280px] max-w-sm animate-fadeIn',
      s.border
    )}>
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
    <div className={cn('w-full bg-white/10 rounded-full h-1.5 overflow-hidden', className)}>
      <div
        className={cn(
          'h-full rounded-full bg-trace-blue',
          indeterminate ? 'w-2/5 animate-pulse' : 'transition-all duration-300'
        )}
        style={indeterminate ? undefined : { width: `${Math.min(100, Math.max(0, value ?? 0))}%` }}
      />
    </div>
  )
}

// ── Skeleton ──────────────────────────────────────────────────────────────────

export function Skeleton({ className = '' }: { className?: string }) {
  return <div className={cn('animate-pulse bg-white/5 rounded', className)} />
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
              className={cn('tbl-row', onRowClick && 'cursor-pointer')}
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
