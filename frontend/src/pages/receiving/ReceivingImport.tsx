import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { AlertTriangle, ChevronDown, Download, FileSpreadsheet, FileUp, UploadCloud, X } from 'lucide-react'
import { authFetch } from '../../api'
import { Alert, Badge, Button, ProductThumb, SegmentedControl, cn, useToast } from '../../components/ui'

/**
 * Receiving import (slice 2b; panel redesigned 2026-10-25): ONE header button "Import from Excel" opens a panel
 * with three numbered steps — download the template (.xlsx or .csv), type what arrived in "On hand (new)",
 * upload the file (drag and drop or browse). The import is a preview first — nothing is added until the user
 * confirms, and nothing is RECEIVED until the session's normal finalize. Server: ReceivingImportController.
 *
 * A raw Shopify export leaves "On hand (new)" empty (the stock is in "On hand (current)"): the panel says so and
 * offers "Use On hand (current) instead" — an explicit second preview of the same file, never automatic.
 */

export interface MatchedRow {
  rowNumber: number; variantId: string; productTitle: string; variantTitle: string; sku: string | null
  imageUrl: string | null; quantity: number; onHandCurrent: number | null; sameAsShopify: boolean; matchedBy: string
}
export interface UnmatchedRow {
  rowNumber: number; handle: string; title: string; options: string; sku: string; value: string; reason: string
}
export interface ImportPreview {
  location: string | null; units: number; matched: MatchedRow[]; unmatched: UnmatchedRow[]; skipped: number
  /** Which column was read as the quantity: "new" (On hand (new)) or "current" (only when asked for). */
  quantityColumn?: 'new' | 'current'
  /** Every stocked row of the file has an empty On hand (new). */
  newColumnEmpty?: boolean
}

const MAX_BYTES = 5 * 1024 * 1024
type TemplateFormat = 'xlsx' | 'csv'

/** The server's {message_en, message_ar} in the user's language; never a raw body. */
async function errorMessage(res: Response, lang: string, fallback: string): Promise<string> {
  const body = await res.json().catch(() => null) as { message_en?: string; message_ar?: string } | null
  return (lang.startsWith('ar') ? body?.message_ar : body?.message_en) || body?.message_en || fallback
}

function download(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

/** The session header's one import control. */
export function ImportFromExcelButton({ onClick }: { onClick: () => void }) {
  const { t } = useTranslation()
  return (
    <Button size="sm" variant="secondary" iconStart={FileSpreadsheet} onClick={onClick}>
      {t('receiving.import.open')}
    </Button>
  )
}

function Step({ n, title, children }: { n: number; title: string; children?: React.ReactNode }) {
  return (
    <li className="flex gap-3">
      <span className="flex-none w-6 h-6 rounded-full bg-trace-blue/10 text-trace-blue text-caption font-semibold
        flex items-center justify-center tabular-nums" aria-hidden>{n}</span>
      <div className="min-w-0 flex-1 space-y-2">
        <p className="text-body font-medium text-primary">{title}</p>
        {children}
      </div>
    </li>
  )
}

export function ImportPanel({ sessionId, onClose, onApplied, runMode }: {
  sessionId?: string; onClose: () => void; onApplied: () => void
  /** Factory runs (flexible labeling slice 6a): the preview goes to the runs endpoint, "On hand (new)" is the
   *  EXPECTED quantity (no "use On hand (current)" fallback), and applying hands the kept rows to the caller. */
  runMode?: { onRows: (rows: MatchedRow[]) => void }
}) {
  const { t, i18n } = useTranslation()
  const { toast } = useToast()
  const input = useRef<HTMLInputElement>(null)
  const [format, setFormat] = useState<TemplateFormat>('xlsx')
  const [downloading, setDownloading] = useState(false)
  const [file, setFile] = useState<File | null>(null)
  const [dragging, setDragging] = useState(false)
  const [preview, setPreview] = useState<ImportPreview | null>(null)
  const [removed, setRemoved] = useState<Set<number>>(new Set())
  const [showUnmatched, setShowUnmatched] = useState(false)
  const [busy, setBusy] = useState<'upload' | 'apply' | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function template() {
    setDownloading(true); setError(null)
    try {
      const res = await authFetch(`/receiving/import/template?format=${format}`)
      if (!res.ok) throw new Error(await errorMessage(res, i18n.language, t('receiving.import.templateFailed')))
      download(await res.blob(), `traced-receiving-template.${format}`)
    } catch (e: unknown) {
      setError((e as Error).message || t('receiving.import.templateFailed'))
    } finally {
      setDownloading(false)
    }
  }

  async function runPreview(f: File, column: 'new' | 'current') {
    setError(null); setPreview(null); setRemoved(new Set()); setShowUnmatched(false)
    if (f.size > MAX_BYTES) { setError(t('receiving.import.tooLarge')); return }
    setBusy('upload')
    try {
      const fd = new FormData()
      fd.append('file', f)
      if (column === 'current') fd.append('column', 'current')
      const res = await authFetch(runMode ? '/label-runs/import/preview' : `/receiving/sessions/${sessionId}/import/preview`,
        { method: 'POST', body: fd })
      if (!res.ok) throw new Error(await errorMessage(res, i18n.language, t('receiving.import.failed')))
      setPreview(await res.json() as ImportPreview)
    } catch (e: unknown) {
      setError((e as Error).message || t('receiving.import.failed'))
    } finally {
      setBusy(null)
      if (input.current) input.current.value = ''
    }
  }

  function choose(f: File | undefined) {
    if (!f) return
    setFile(f)
    void runPreview(f, 'new')
  }

  function clearFile() {
    setFile(null); setPreview(null); setRemoved(new Set()); setError(null)
  }

  const usingCurrent = preview?.quantityColumn === 'current'
  const newEmpty = !!preview && !usingCurrent && !!preview.newColumnEmpty
  const kept = preview ? preview.matched.filter(r => !removed.has(r.rowNumber)) : []
  const units = kept.reduce((s, r) => s + r.quantity, 0)

  async function apply() {
    if (runMode) {
      runMode.onRows(kept)
      onApplied()
      return
    }
    setBusy('apply'); setError(null)
    try {
      const res = await authFetch(`/receiving/sessions/${sessionId}/import/apply`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ lines: kept.map(r => ({ variantId: r.variantId, quantity: r.quantity })) }),
      })
      if (!res.ok) throw new Error(await errorMessage(res, i18n.language, t('receiving.import.failed')))
      toast({ tone: 'success', message: t('receiving.import.added', { count: units }) })
      onApplied()
    } catch (e: unknown) {
      setError((e as Error).message || t('receiving.import.failed'))
    } finally {
      setBusy(null)
    }
  }

  return (
    <section className="card p-5 space-y-5" data-testid="import-panel">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 className="text-h3 text-primary">{t('receiving.import.title')}</h2>
          {usingCurrent && (
            <p className="text-small text-info-text mt-0.5" data-testid="import-using-current">{t('receiving.import.usingCurrent')}</p>
          )}
        </div>
        <button type="button" onClick={onClose} aria-label={t('receiving.import.close')}
          className="text-muted hover:text-primary transition-colors p-0.5 flex-none">
          <X size={18} strokeWidth={2} />
        </button>
      </div>

      <ol className="space-y-5">
        <Step n={1} title={t('receiving.import.step1')}>
          <div className="flex flex-wrap items-center gap-2">
            <Button size="sm" variant="secondary" iconStart={Download} loading={downloading} onClick={template}>
              {t('receiving.import.template')}
            </Button>
            <SegmentedControl value={format} onChange={v => setFormat(v as TemplateFormat)} options={[
              { value: 'xlsx', label: t('receiving.import.formatXlsx') },
              { value: 'csv', label: t('receiving.import.formatCsv') },
            ]} />
          </div>
          <p className="text-caption text-muted">{t('receiving.import.shopifyExportNote')}</p>
        </Step>

        <Step n={2} title={t(runMode ? 'labelRuns.import.step2' : 'receiving.import.step2')} />

        <Step n={3} title={t('receiving.import.step3')}>
          <p className="flex items-start gap-1.5 text-caption text-warning-text" data-testid="import-shopify-warning">
            <AlertTriangle size={14} strokeWidth={2} className="flex-none mt-0.5" />
            <span>{t('receiving.import.shopifyWarning')}</span>
          </p>
          <input ref={input} type="file" accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            className="sr-only" data-testid="import-file" tabIndex={-1}
            onChange={e => choose(e.target.files?.[0])} />
          {file ? (
            <div className="flex items-center gap-3 rounded-xl border border-line px-3 py-2.5" data-testid="import-chosen">
              <FileSpreadsheet size={18} strokeWidth={2} className="text-muted flex-none" />
              <span className="text-body text-primary truncate flex-1" dir="ltr">{file.name}</span>
              {busy === 'upload' && <span className="text-caption text-muted">{t('receiving.import.reading')}</span>}
              <button type="button" onClick={clearFile} aria-label={t('receiving.import.removeFile')}
                className="text-muted hover:text-critical transition-colors p-1 flex-none" data-testid="import-remove-file">
                <X size={16} strokeWidth={2} />
              </button>
            </div>
          ) : (
            <button type="button" data-testid="import-dropzone"
              onClick={() => input.current?.click()}
              onDragOver={e => { e.preventDefault(); setDragging(true) }}
              onDragLeave={() => setDragging(false)}
              onDrop={e => { e.preventDefault(); setDragging(false); choose(e.dataTransfer.files?.[0]) }}
              className={cn('w-full rounded-xl border-2 border-dashed px-4 py-6 flex flex-col items-center gap-1.5 text-center transition-colors',
                dragging ? 'border-trace-blue bg-trace-blue/[0.06]' : 'border-line hover:border-trace-blue')}>
              <UploadCloud size={24} strokeWidth={1.75} className="text-muted" />
              <span className="text-body font-medium text-primary">{t('receiving.import.drop')}</span>
              <span className="text-caption text-muted">{t('receiving.import.formats')}</span>
            </button>
          )}
        </Step>
      </ol>

      {error && <Alert tone="critical" title={error} />}

      {preview && newEmpty && file && (
        <div className="space-y-3" data-testid="import-new-empty">
          <Alert tone="warning" title={t(runMode ? 'labelRuns.import.newEmpty' : 'receiving.import.newEmpty')} />
          {!runMode && (
            <Button variant="secondary" iconStart={FileUp} loading={busy === 'upload'}
              onClick={() => runPreview(file, 'current')}>
              {t('receiving.import.useCurrent')}
            </Button>
          )}
        </div>
      )}

      {preview && !newEmpty && (
        <div className="space-y-4" data-testid="import-preview">
          <div className="flex flex-wrap gap-2" data-testid="import-summary">
            <Badge tone="success" label={t('receiving.import.chipMatched', { count: kept.length, units })} />
            {preview.unmatched.length > 0 && (
              <Badge tone="warning" label={t('receiving.import.chipNotAdded', { count: preview.unmatched.length })} />
            )}
            {preview.skipped > 0 && <Badge tone="neutral" label={t('receiving.import.chipSkipped', { count: preview.skipped })} />}
          </div>

          {kept.length > 0 && (
            <ul className="divide-y divide-line border-y border-line" data-testid="import-matched">
              {kept.map(r => (
                <li key={r.rowNumber} className="flex items-center gap-3 py-2.5" data-testid={`import-row-${r.rowNumber}`}>
                  <ProductThumb src={r.imageUrl} alt={r.productTitle} size={40} />
                  <div className="min-w-0 flex-1">
                    <p className="text-body font-medium text-primary truncate"><bdi>{r.productTitle}</bdi></p>
                    <p className="text-small text-muted truncate">
                      <bdi>{r.variantTitle}</bdi>
                      {r.sku && <> · <span className="font-mono" dir="ltr">{r.sku}</span></>}
                    </p>
                    {r.sameAsShopify && !runMode && (
                      <p className="text-caption text-warning-text mt-0.5" data-testid="same-as-shopify">
                        {t('receiving.import.sameAsShopify')}
                      </p>
                    )}
                  </div>
                  <span className="text-body font-semibold text-primary tabular-nums" dir="ltr">{r.quantity}</span>
                  <button type="button" aria-label={t('receiving.import.remove')}
                    onClick={() => setRemoved(prev => new Set(prev).add(r.rowNumber))}
                    className="text-muted hover:text-critical transition-colors p-1 flex-none">
                    <X size={16} strokeWidth={2} />
                  </button>
                </li>
              ))}
            </ul>
          )}

          {preview.unmatched.length > 0 && (
            <div className="space-y-2" data-testid="import-unmatched">
              <button type="button" onClick={() => setShowUnmatched(v => !v)} aria-expanded={showUnmatched}
                className="flex items-center gap-1.5 text-small font-semibold text-primary">
                <ChevronDown size={16} strokeWidth={2} className={cn('transition-transform', showUnmatched ? '' : '-rotate-90 rtl:rotate-90')} />
                {t('receiving.import.notAdded', { count: preview.unmatched.length })}
              </button>
              {showUnmatched && (
                <ul className="space-y-1.5" data-testid="import-unmatched-list">
                  {preview.unmatched.map(r => (
                    <li key={`${r.rowNumber}-${r.reason}`} className="flex flex-wrap items-baseline gap-x-2 text-small">
                      <span className="text-muted tabular-nums" dir="ltr">{t('receiving.import.row', { n: r.rowNumber })}</span>
                      <span className="text-primary"><bdi>{[r.title, r.options].filter(Boolean).join(' · ')}</bdi></span>
                      {r.value && <span className="text-muted font-mono" dir="ltr">{r.value}</span>}
                      <Badge tone="warning" label={t(`receiving.import.reason.${r.reason}`, { defaultValue: r.reason })} />
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}

          <div className="flex flex-wrap items-center gap-2">
            <Button loading={busy === 'apply'} disabled={units === 0} onClick={apply}>
              {t(runMode ? 'labelRuns.import.apply' : 'receiving.import.apply', { count: units })}
            </Button>
            <Button variant="secondary" onClick={onClose}>{t('common.cancel')}</Button>
            {units === 0 && (
              <span className="text-small text-muted" data-testid="import-apply-reason">
                {preview.matched.length > 0 ? t('receiving.import.allRemoved') : t('receiving.import.nothingMatched')}
              </span>
            )}
          </div>
        </div>
      )}
    </section>
  )
}
