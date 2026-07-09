import { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'

// ── Badge ─────────────────────────────────────────────────────────────────────

const STATUS_STYLE: Record<string, string> = {
  // Piece statuses
  available:                 'bg-success/10 text-success border-success/20',
  reserved:                  'bg-warning/10 text-warning border-warning/20',
  packed:                    'bg-brand/10 text-brand border-brand/20',
  awaiting_pickup:           'bg-brand/10 text-brand border-brand/20',
  with_courier:              'bg-cyan/10 text-cyan border-cyan/20',
  delivered:                 'bg-success/10 text-success border-success/20',
  return_in_transit:         'bg-warning/10 text-warning border-warning/20',
  return_pending_inspection: 'bg-warning/10 text-warning border-warning/20',
  damaged:                   'bg-danger/10 text-danger border-danger/20',
  lost:                      'bg-danger/10 text-danger border-danger/20',
  destroyed:                 'bg-muted/10 text-muted border-muted/20',
  // Order statuses
  new:                       'bg-muted/10 text-muted border-muted/20',
  confirmed:                 'bg-accent/10 text-accent border-accent/20',
  ready_to_pick:             'bg-warning/10 text-warning border-warning/20',
  picking:                   'bg-warning/10 text-warning border-warning/20',
  awaiting_pickup_order:     'bg-brand/10 text-brand border-brand/20',
  with_courier_order:        'bg-cyan/10 text-cyan border-cyan/20',
  self_pickup_pending:       'bg-accent/10 text-accent border-accent/20',
  returning:                 'bg-warning/10 text-warning border-warning/20',
  returned:                  'bg-muted/10 text-muted border-muted/20',
  cancelled:                 'bg-muted/10 text-muted border-muted/20',
}

export function Badge({
  status,
  label,
  className = '',
}: {
  status?: string
  label?: string
  className?: string
}) {
  const { t, i18n } = useTranslation()
  const key = status ?? ''
  const style = STATUS_STYLE[key] ?? 'bg-muted/10 text-muted border-muted/20'
  const text = label ?? t(`lookup.pieceStatus.${key}`, { defaultValue: key.replace(/_/g, ' ') })
  const isAr = i18n.language === 'ar'

  return (
    <span className={`badge border ${style} ${className}`}>
      {isAr ? t(`lookup.pieceStatus.${key}`, { defaultValue: text }) : text}
    </span>
  )
}

// ── OrderBadge (maps order statuses to the shared badge) ─────────────────────

const ORDER_STATUS_KEY: Record<string, string> = {
  awaiting_pickup: 'awaiting_pickup_order',
  with_courier:    'with_courier_order',
}

export function OrderBadge({ status }: { status: string }) {
  const mapped = ORDER_STATUS_KEY[status] ?? status
  const style  = STATUS_STYLE[mapped] ?? STATUS_STYLE[status] ?? 'bg-muted/10 text-muted border-muted/20'
  return (
    <span className={`badge border ${style}`}>
      {status.replace(/_/g, ' ')}
    </span>
  )
}

// ── DeliveryBadge — maps shipment internal_state → friendly label + colour ────

const DELIVERY_STATE_STYLE: Record<string, string> = {
  created:    'bg-brand/10 text-brand border-brand/20',
  with_courier: 'bg-cyan/10 text-cyan border-cyan/20',
  delivered:  'bg-success/10 text-success border-success/20',
  returning:  'bg-warning/10 text-warning border-warning/20',
  returned:   'bg-muted/10 text-muted border-muted/20',
  exception:  'bg-danger/10 text-danger border-danger/20',
  terminated: 'bg-danger/10 text-danger border-danger/20',
  cancelled:  'bg-muted/10 text-muted border-muted/20',
  lost:       'bg-danger/10 text-danger border-danger/20',
}

export function DeliveryBadge({
  state,
  exceptionReason,
  className = '',
}: {
  state?: string | null
  exceptionReason?: string | null
  className?: string
}) {
  const { t } = useTranslation()
  if (!state) {
    return (
      <span className={`badge border bg-muted/10 text-muted border-muted/20 ${className}`}>
        {t('delivery.state.awaiting')}
      </span>
    )
  }
  const style  = DELIVERY_STATE_STYLE[state] ?? 'bg-muted/10 text-muted border-muted/20'
  const label  = t(`delivery.state.${state}`, { defaultValue: state.replace(/_/g, ' ') })
  const reason = state === 'exception' && exceptionReason ? exceptionReason : null

  return (
    <span className={`inline-flex flex-col items-start gap-0.5 ${className}`}>
      <span className={`badge border ${style}`}>{label}</span>
      {reason && (
        <span className="text-caption text-muted leading-tight">{reason}</span>
      )}
    </span>
  )
}

// ── Card ──────────────────────────────────────────────────────────────────────

export function Card({ children, className = '' }: { children: ReactNode; className?: string }) {
  return (
    <div className={`card p-5 ${className}`}>
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
}: {
  label: string
  value: string | number
  delta?: number
  deltaLabel?: string
  accent?: boolean
}) {
  const deltaPositive = (delta ?? 0) >= 0
  return (
    <div className="card p-5 flex flex-col gap-2">
      <p className="text-small text-muted uppercase tracking-wider">{label}</p>
      <p className={`text-display font-light ${accent ? 'text-brand' : 'text-primary'}`}>
        {typeof value === 'number' ? value.toLocaleString() : value}
      </p>
      {delta !== undefined && (
        <p className={`text-small font-medium flex items-center gap-1 ${deltaPositive ? 'text-success' : 'text-danger'}`}>
          <span>{deltaPositive ? '↑' : '↓'}</span>
          <span>{Math.abs(delta).toLocaleString()} {deltaLabel}</span>
        </p>
      )}
    </div>
  )
}

// ── Button ────────────────────────────────────────────────────────────────────

export function Button({
  children,
  variant = 'brand',
  size = 'md',
  disabled,
  onClick,
  type = 'button',
  className = '',
}: {
  children: ReactNode
  variant?: 'brand' | 'outline' | 'danger' | 'ghost'
  size?: 'sm' | 'md' | 'lg'
  disabled?: boolean
  onClick?: () => void
  type?: 'button' | 'submit'
  className?: string
}) {
  const variantClass = {
    brand:   'btn-brand',
    outline: 'btn-outline',
    danger:  'btn-danger',
    ghost:   'btn-ghost',
  }[variant]

  const sizeClass = {
    sm: 'px-3 py-1.5 text-small',
    md: 'px-4 py-2 text-body',
    lg: 'px-5 py-3 text-body font-semibold',
  }[size]

  return (
    <button
      type={type}
      disabled={disabled}
      onClick={onClick}
      className={`btn ${variantClass} ${sizeClass} ${className}`}
    >
      {children}
    </button>
  )
}

// ── Input ─────────────────────────────────────────────────────────────────────

export function Input({
  scan,
  className = '',
  ...props
}: React.InputHTMLAttributes<HTMLInputElement> & { scan?: boolean }) {
  return (
    <input
      {...props}
      className={`${scan ? 'input-scan' : 'input'} ${className}`}
    />
  )
}

// ── Spinner ───────────────────────────────────────────────────────────────────

export function Spinner({ size = 20 }: { size?: number }) {
  return (
    <svg
      width={size} height={size}
      viewBox="0 0 24 24"
      fill="none"
      className="animate-spin text-brand"
      aria-hidden
    >
      <circle cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="2" strokeOpacity="0.2" />
      <path d="M12 2 a10 10 0 0 1 10 10" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
    </svg>
  )
}

// ── Empty state ───────────────────────────────────────────────────────────────

export function EmptyState({ message, icon = '✦' }: { message: string; icon?: string }) {
  return (
    <div className="flex flex-col items-center justify-center py-16 gap-3 text-muted">
      <span className="text-4xl opacity-30">{icon}</span>
      <p className="text-body">{message}</p>
    </div>
  )
}

// ── Severity badge for exceptions ─────────────────────────────────────────────

const SEV_STYLE = {
  CRITICAL: 'bg-danger/10 text-danger border-danger/20',
  HIGH:     'bg-warning/10 text-warning border-warning/20',
  MEDIUM:   'bg-brand/10 text-brand border-brand/20',
  LOW:      'bg-accent/10 text-accent border-accent/20',
} as const

export function SeverityBadge({ severity }: { severity: keyof typeof SEV_STYLE }) {
  return (
    <span className={`badge border ${SEV_STYLE[severity] ?? SEV_STYLE.LOW}`}>
      {severity}
    </span>
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
      className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-50 p-4"
      onClick={onClose}
    >
      <div
        className="bg-panel rounded-xl border border-line shadow-elevated w-full max-w-md animate-fadeIn"
        onClick={e => e.stopPropagation()}
      >
        {title && (
          <div className="flex items-center justify-between px-6 py-4 border-b border-line">
            <h3 className="text-h3 text-primary">{title}</h3>
            <button
              onClick={onClose}
              className="text-muted hover:text-primary text-lg leading-none transition-colors"
            >
              ✕
            </button>
          </div>
        )}
        <div className="p-6">{children}</div>
      </div>
    </div>
  )
}
