import { useEffect, useState } from 'react'
import type { RefObject } from 'react'
import { useTranslation } from 'react-i18next'
import { Tag } from 'lucide-react'
import { Input } from '../../components/ui'
import {
  setStockTakeUntaggedCount, ServerMessageError, StockTakeUntaggedCount, StockTakeUntaggedRow,
} from '../../api'

// Flexible labeling slice 7 — untagged pieces (no label yet) can't be scanned, so a stock take counts them
// per product: the person types how many are on the shelf. Shown only when the server sends rows (switch on).
//
// The counting screen is BLIND: it gets StockTakeUntaggedCount rows (no expected number) and shows only
// "Untagged count [__]". Expected vs counted and the outcome ("−N written off" / "+N extra found") are on
// Review only, which gets StockTakeUntaggedRow.
//
// The count fields are plain text inputs (digits only) — never auto-focused, never a second scan input. On the
// counting screen the panel sits next to useScanner's scan bar: its container stops click propagation (the same
// technique as Modal and TransferReconcile) so the SAFETY-CRITICAL click-refocus listener, untouched, doesn't
// pull the cursor back to the scan bar while someone types here; leaving a field saves it, and Enter hands
// focus back to the scan bar. A value that isn't 1–5 digits is a scan that landed here: it is never saved and
// never handed to the scanner — the field clears and says so.

const MAX_DIGITS = 5

/** "" → null (not counted); 1–5 digits → that number; anything else (a letter, 6+ digits) → 'scan'. */
function parseCount(draft: string): number | null | 'scan' {
  const v = draft.trim()
  if (v === '') return null
  return new RegExp(`^\\d{1,${MAX_DIGITS}}$`).test(v) ? Number(v) : 'scan'
}

function isReviewRow(r: StockTakeUntaggedCount): r is StockTakeUntaggedRow {
  return 'expected' in r
}

export function UntaggedCountsPanel({ sessionId, rows, editable, onSaved, scanInputRef }: {
  sessionId: string
  /** Counting screen: blind rows. Review: rows with expected and the outcome. */
  rows: StockTakeUntaggedCount[] | StockTakeUntaggedRow[]
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
        {rows.map((r: StockTakeUntaggedCount) => (
          <UntaggedRow key={r.variantId} sessionId={sessionId} row={r} editable={editable}
            onSaved={onSaved} scanInputRef={scanInputRef} />
        ))}
      </ul>
    </section>
  )
}

function UntaggedRow({ sessionId, row, editable, onSaved, scanInputRef }: {
  sessionId: string
  row: StockTakeUntaggedCount
  editable: boolean
  onSaved: () => void | Promise<void>
  scanInputRef?: RefObject<HTMLInputElement | null>
}) {
  const { t, i18n } = useTranslation()
  const saved = row.counted === null ? '' : String(row.counted)
  const [draft, setDraft] = useState(saved)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // After a rejected scan the field is empty but the saved count is untouched: leaving it is not a "clear".
  const [rejected, setRejected] = useState(false)
  useEffect(() => { setDraft(saved) }, [saved])

  const name = row.productTitle ? `${row.productTitle} · ${row.variantTitle}` : row.variantTitle
  const review = isReviewRow(row)

  async function save() {
    const value = parseCount(draft)
    if (value === 'scan') {
      setDraft('')
      setRejected(true)
      setError(t('stocktake.untagged.looksLikeScan'))
      return
    }
    if (rejected && value === null) return
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
        {review && (
          <>
            <span className="text-muted">{t('stocktake.untagged.expected', { count: row.expected })}</span>
            <span className="text-muted" aria-hidden>·</span>
          </>
        )}
        {editable ? (
          <label className="flex items-center gap-2">
            <span className="text-muted">{t(review ? 'stocktake.untagged.counted' : 'stocktake.untagged.countLabel')}</span>
            <span className="w-20">
              <Input
                type="text"
                inputMode="numeric"
                autoComplete="off"
                className="font-mono"
                value={draft}
                disabled={saving}
                invalid={!!error}
                aria-label={t('stocktake.untagged.inputLabel', { variant: name })}
                data-testid={`untagged-input-${row.variantId}`}
                onChange={e => { setDraft(e.target.value); setRejected(false) }}
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
      {(review || error) && (
        <div className="basis-full">
          {review && <UntaggedOutcome row={row} open={editable} name={name} />}
          {error && <p className="text-caption text-danger mt-1" role="alert">{error}</p>}
        </div>
      )}
    </li>
  )
}

/** Review only: what finalize will do (open) or did (finalized) for one product's untagged pieces. */
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
