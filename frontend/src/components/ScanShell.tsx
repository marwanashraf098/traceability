import { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Loader2 } from 'lucide-react'
import { UseScannerResult } from '../hooks/useScanner'

// FR-21 Step 6.2 — thin visual wrapper around useScanner. SAFETY-CRITICAL flash
// overlay + input markup copied verbatim from Fulfill.tsx's PickScreen (the
// z-50 full-screen overlay + raw <input className="input-scan">, not the
// shared Input component — PickScreen bypasses it for direct ref/autoFocus/
// onKeyDown control, and this preserves that exactly). No logic lives here —
// everything stateful is in useScanner; this component only renders it.
//
// 2026-10-01 (approved by Marawan — see useScanner's header): the input is no longer
// disabled while a scan is in flight (keystrokes of the next scan must land; useScanner
// queues them). It is disabled only when the screen passes `disabled` for its own
// reasons. In-flight state is shown without disabling: a spinner + aria-busy, plus
// "N waiting" and the queue-full error under the input.

export function ScanShell({
  scanner,
  placeholder,
  disabled,
  children,
}: {
  scanner: UseScannerResult
  placeholder?: string
  disabled?: boolean
  children?: ReactNode
}) {
  const { t } = useTranslation()

  // SAFETY-CRITICAL — flash overlay computation: do not modify.
  const flashOverlay =
    scanner.flash === 'success' ? 'fixed inset-0 bg-success/20 pointer-events-none z-50 animate-flash'
    : scanner.flash === 'error' ? 'fixed inset-0 bg-danger/20 pointer-events-none z-50 animate-flash'
    : 'hidden'

  return (
    <>
      {/* SAFETY-CRITICAL flash overlay — do not modify */}
      <div className={flashOverlay} />

      {/* SAFETY-CRITICAL scan input — autoFocus, ref, onKeyDown: do not modify. Disabled ONLY
          by the screen's own `disabled` (never while scanning — approved 2026-10-01). */}
      <div className="relative">
        <input
          ref={scanner.inputRef}
          type="text"
          placeholder={placeholder}
          className="input-scan w-full"
          disabled={disabled}
          aria-busy={scanner.scanning}
          onKeyDown={e => {
            if (e.key === 'Enter') scanner.handleScan((e.target as HTMLInputElement).value)
          }}
          autoFocus
        />
        {scanner.scanning && (
          <Loader2
            size={18}
            strokeWidth={2}
            className="absolute end-3 top-1/2 -translate-y-1/2 animate-spin text-muted pointer-events-none"
            data-testid="scan-in-flight"
          />
        )}
      </div>
      {(scanner.pending > 0 || scanner.queueFull) && (
        <div className="mt-1.5 flex flex-wrap items-center gap-x-3 text-small" aria-live="polite">
          {scanner.pending > 0 && (
            <span className="text-muted" data-testid="scan-pending">
              {t('scanner.waiting', { count: scanner.pending })}
            </span>
          )}
          {scanner.queueFull && (
            <span className="font-semibold text-critical-text" role="alert" data-testid="scan-queue-full">
              {t('scanner.queueFull')}
            </span>
          )}
        </div>
      )}

      {children}
    </>
  )
}
