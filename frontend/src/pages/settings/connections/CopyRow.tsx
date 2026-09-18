import { useState } from 'react'

// ── Copy-to-clipboard button ──────────────────────────────────────────────────
//
// Shared by BostaCard's webhook-secret reveal panel (ConnectionsTab.tsx) and the
// Shopify setup wizard's copyable config values (SetupWizard.tsx) — extracted from
// its original ConnectionsTab.tsx-local definition so both have one source.

async function writeToClipboard(text: string): Promise<void> {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text)
    return
  }
  // Fallback for HTTP contexts / older browsers
  const el = document.createElement('textarea')
  el.value = text
  el.style.cssText = 'position:fixed;opacity:0;pointer-events:none'
  document.body.appendChild(el)
  el.select()
  document.execCommand('copy')
  document.body.removeChild(el)
}

export function CopyButton({ value, label, copiedLabel }: { value: string; label: string; copiedLabel: string }) {
  const [copied, setCopied] = useState(false)

  async function handleCopy() {
    try {
      await writeToClipboard(value)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {/* ignore — user can select-copy manually */}
  }

  return (
    <button
      type="button"
      onClick={handleCopy}
      className={`flex-shrink-0 px-3 py-1.5 rounded text-xs font-medium border transition-colors ${
        copied
          ? 'bg-success/10 border-success/30 text-success'
          : 'bg-surface border-line text-muted hover:text-primary hover:border-brand/40'
      }`}
    >
      {copied ? copiedLabel : label}
    </button>
  )
}

// ── Copyable row (label + read-only value + copy button) ──────────────────────

export function CopyRow({ label, value, hint, copyLabel, copiedLabel }: {
  /** Caption line above the value — omit for a bare value+button row (e.g. the setup wizard's copyboxes, which have no caption). */
  label?: string
  value: string
  hint?: string
  copyLabel: string
  copiedLabel: string
}) {
  return (
    <div className="space-y-1">
      {(label || hint) && (
        <div className="flex items-center justify-between gap-1">
          {label && <span className="text-xs font-medium text-muted">{label}</span>}
          {hint && <span className="text-xs text-muted/60 italic">{hint}</span>}
        </div>
      )}
      <div className="flex gap-2 items-center">
        <input
          type="text"
          readOnly
          value={value}
          className="input flex-1 font-mono text-xs bg-surface/60 text-primary select-all"
          dir="ltr"
          onFocus={e => e.currentTarget.select()}
        />
        <CopyButton value={value} label={copyLabel} copiedLabel={copiedLabel} />
      </div>
    </div>
  )
}
