import { ReactNode } from 'react'
import { UseScannerResult } from '../hooks/useScanner'

// FR-21 Step 6.2 — thin visual wrapper around useScanner. SAFETY-CRITICAL flash
// overlay + input markup copied verbatim from Fulfill.tsx's PickScreen (the
// z-50 full-screen overlay + raw <input className="input-scan">, not the
// shared Input component — PickScreen bypasses it for direct ref/autoFocus/
// onKeyDown control, and this preserves that exactly). No logic lives here —
// everything stateful is in useScanner; this component only renders it.

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
  // SAFETY-CRITICAL — flash overlay computation: do not modify.
  const flashOverlay =
    scanner.flash === 'success' ? 'fixed inset-0 bg-success/20 pointer-events-none z-50 animate-flash'
    : scanner.flash === 'error' ? 'fixed inset-0 bg-danger/20 pointer-events-none z-50 animate-flash'
    : 'hidden'

  return (
    <>
      {/* SAFETY-CRITICAL flash overlay — do not modify */}
      <div className={flashOverlay} />

      {/* SAFETY-CRITICAL scan input — autoFocus, ref, onKeyDown, disabled=scanning: do not modify */}
      <input
        ref={scanner.inputRef}
        type="text"
        placeholder={placeholder}
        className="input-scan w-full"
        disabled={disabled || scanner.scanning}
        onKeyDown={e => {
          if (e.key === 'Enter') scanner.handleScan((e.target as HTMLInputElement).value)
        }}
        autoFocus
      />

      {children}
    </>
  )
}
