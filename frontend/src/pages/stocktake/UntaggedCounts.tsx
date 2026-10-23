import { useEffect, useState } from 'react'
import type { RefObject } from 'react'
import { useTranslation } from 'react-i18next'
import { Tag } from 'lucide-react'
import { Input } from '../../components/ui'
import { setStockTakeUntaggedCount, ServerMessageError, StockTakeUntaggedRow } from '../../api'

// Flexible labeling slice 7 — untagged pieces (no label yet) can't be scanned, so a stock take counts them
// per product: the person types how many are on the shelf. Shown only when the server sends rows (switch on).
//
// The count fields are plain number inputs — never auto-focused, never a second scan input. On the counting
// screen the panel sits next to useScanner's scan bar: its container stops click propagation (the same
// technique as Modal and TransferReconcile) so the SAFETY-CRITICAL click-refocus listener, untouched, doesn't
// pull the cursor back to the scan bar while someone types here; leaving a field saves it, and Enter hands
// focus back to the scan bar.

/** "" → null (not counted yet); a whole number ≥ 0 → that number; anything else → undefined (invalid). */
function parseCount(draft: string): number | null | undefined {
  const v = draft.trim()
  if (v === '') return null
  return /^\d+$/.test(v) ? Number(v) : undefined
}

export function UntaggedCountsPanel({ sessionId, rows, editable, onSaved, scanInputRef }: {
  sessionId: string
  rows: StockTakeUntaggedRow[]
  /** Open session: counts can be typed. Finalized: the result only. */
  editable: boolean
  /** After a count is saved or cleared — the caller reloads its rows / plan. */
  onSaved: () => void | Promise<void>
  /** The counting screen's scan input: Enter in a count field returns focus there. */
  scanInputRef?: RefObject<HTMLInputElement | null>
}) {
  const { t } = useTranslation()
  if (rows.length === 0) return null
  return (
    <section
      className="card p-4 space-y-3"
      data-testid="untagged-counts"
      onClick={e => e.stopPropagation()}
    >
      <div className="flex items-start gap-2">
        <Tag size={16} strokeWidth={2} className="text-muted mt-0.5 flex-shrink-0" />
        <div>
          <h2 className="text-body font-semibold text-primary">{t('stocktake.untagged.title')}</h2>
          {editable && <p className="text-small text-muted">{t('stocktake.untagged.hint')}</p>}
        </div>
      </div>
      <ul className="divide-y divide-line">
        {rows.map(r => (
          <UntaggedRow key={r.variantId} sessionId={sessionId} row={r} editable={editable}
            onSaved={onSaved} scanInputRef={scanInputRef} />
        ))}
      </ul>
    </section>
  )
}

function UntaggedRow({ sessionId, row, editable, onSaved, scanInputRef }: {
  sessionId: string
  row: StockTakeUntaggedRow
  editable: boolean
  onSaved: () => void | Promise<void>
  scanInputRef?: RefObject<HTMLInputElement | null>
}) {
  const { t, i18n } = useTranslation()
  const saved = row.counted === null ? '' : String(row.counted)
  const [draft, setDraft] = useState(saved)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { setDraft(saved) }, [saved])

  const name = row.productTitle ? `${row.productTitle} · ${row.variantTitle}` : row.variantTitle

  async function save() {
    const value = parseCount(draft)
    if (value === undefined) { setError(t('stocktake.untagged.invalid')); return }
    if (value === row.counted) { setError(null); return }
    setSaving(true)
    setError(null)
    try {
      await setStockTakeUntaggedCount(sessionId, row.variantId, value)
      await onSaved()
    } catch (e: unknown) {
      setError(e instanceof ServerMessageError ? e.messageFor(i18n.language) : t('stocktake.untagged.saveError'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <li className="py-3 flex flex-wrap items-center gap-x-4 gap-y-2" data-testid={`untagged-row-${row.variantId}`}>
      <div className="min-w-0 flex-1">
        <div className="text-body font-medium text-primary truncate">{name}</div>
        {row.sku && <div className="text-caption text-muted font-mono">{row.sku}</div>}
      </div>
      <div className="flex items-center gap-2 text-small">
        <span className="text-muted">{t('stocktake.untagged.expected', { count: row.expected })}</span>
        <span className="text-muted" aria-hidden>·</span>
        {editable ? (
          <label className="flex items-center gap-2">
            <span className="text-muted">{t('stocktake.untagged.counted')}</span>
            <span className="w-20">
              <Input
                type="number"
                inputMode="numeric"
                min={0}
                step={1}
                className="font-mono"
                value={draft}
                disabled={saving}
                invalid={!!error}
                aria-label={t('stocktake.untagged.inputLabel', { variant: name })}
                data-testid={`untagged-input-${row.variantId}`}
                onChange={e => setDraft(e.target.value)}
                onBlur={save}
                onKeyDown={e => {
                  if (e.key !== 'Enter') return
                  e.preventDefault()
                  // Leaving the field saves it (onBlur) — once; the scan bar gets the cursor back.
                  if (scanInputRef?.current) scanInputRef.current.focus()
                  else e.currentTarget.blur()
                }}
              />
            </span>
          </label>
        ) : (
          <span className="text-primary font-mono">{t('stocktake.untagged.countedValue', { count: row.counted ?? 0 })}</span>
        )}
      </div>
      <div className="basis-full">
        <UntaggedOutcome row={row} open={editable} name={name} />
        {error && <p className="text-caption text-danger mt-1" role="alert">{error}</p>}
      </div>
    </li>
  )
}

/** What finalize will do (open) or did (finalized) for one product's untagged pieces. */
export function UntaggedOutcome({ row, open, name }: { row: StockTakeUntaggedRow; open: boolean; name: string }) {
  const { t } = useTranslation()
  if (row.counted === null) {
    return row.expected > 0
      ? <p className="text-caption text-warning font-medium" data-testid="untagged-missing">{t('stocktake.untagged.missing')}</p>
      : null
  }
  if (row.writeOff > 0) {
    return (
      <p className="text-caption text-danger font-medium" data-testid="untagged-writeoff">
        {t(open ? 'stocktake.untagged.writeOffOpen' : 'stocktake.untagged.writeOffDone', { count: row.writeOff })}
      </p>
    )
  }
  if (row.extra > 0) {
    return (
      <p className="text-caption text-info font-medium" data-testid="untagged-extra">
        {t('stocktake.untagged.extra', { count: row.extra, variant: name })}
      </p>
    )
  }
  return <p className="text-caption text-success font-medium">{t('stocktake.untagged.matches')}</p>
}
