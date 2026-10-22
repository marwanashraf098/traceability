import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Download, FileUp, X } from 'lucide-react'
import { authFetch } from '../../api'
import { Alert, Badge, Button, ProductThumb, useToast } from '../../components/ui'

/**
 * Receiving import (slice 2b): "Import from file" (a Shopify inventory export, .csv or .xlsx) and
 * "Download template". The import is a preview first — nothing is added until the user confirms, and
 * nothing is RECEIVED until the session's normal finalize. Server: ReceivingImportController.
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
}

const MAX_BYTES = 5 * 1024 * 1024

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

export function ReceivingImportControls({ sessionId, onApplied }: { sessionId: string; onApplied: () => void }) {
  const { t, i18n } = useTranslation()
  const [open, setOpen] = useState(false)
  const [templateError, setTemplateError] = useState<string | null>(null)
  const [downloading, setDownloading] = useState<'xlsx' | 'csv' | null>(null)

  async function template(format: 'xlsx' | 'csv') {
    setDownloading(format); setTemplateError(null)
    try {
      const res = await authFetch(`/receiving/import/template?format=${format}`)
      if (!res.ok) throw new Error(await errorMessage(res, i18n.language, t('receiving.import.templateFailed')))
      download(await res.blob(), `traced-receiving-template.${format}`)
    } catch (e: unknown) {
      setTemplateError((e as Error).message || t('receiving.import.templateFailed'))
    } finally {
      setDownloading(null)
    }
  }

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <Button size="sm" variant="secondary" iconStart={FileUp}
          onClick={() => setOpen(true)}>
          {t('receiving.import.open')}
        </Button>
        <Button size="sm" variant="tertiary" iconStart={Download}
          loading={downloading === 'xlsx'} onClick={() => template('xlsx')}>
          {t('receiving.import.template')}
        </Button>
        <Button size="sm" variant="ghost" loading={downloading === 'csv'} onClick={() => template('csv')}>
          {t('receiving.import.templateCsv')}
        </Button>
      </div>
      {templateError && <Alert tone="critical" title={templateError} />}
      {open && (
        <ImportPanel sessionId={sessionId} onClose={() => setOpen(false)}
          onApplied={() => { setOpen(false); onApplied() }} />
      )}
    </div>
  )
}

function ImportPanel({ sessionId, onClose, onApplied }: {
  sessionId: string; onClose: () => void; onApplied: () => void
}) {
  const { t, i18n } = useTranslation()
  const { toast } = useToast()
  const input = useRef<HTMLInputElement>(null)
  const [preview, setPreview] = useState<ImportPreview | null>(null)
  const [removed, setRemoved] = useState<Set<number>>(new Set())
  const [busy, setBusy] = useState<'upload' | 'apply' | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function upload(file: File) {
    setError(null); setPreview(null); setRemoved(new Set())
    if (file.size > MAX_BYTES) { setError(t('receiving.import.tooLarge')); return }
    setBusy('upload')
    try {
      const fd = new FormData()
      fd.append('file', file)
      const res = await authFetch(`/receiving/sessions/${sessionId}/import/preview`, { method: 'POST', body: fd })
      if (!res.ok) throw new Error(await errorMessage(res, i18n.language, t('receiving.import.failed')))
      setPreview(await res.json() as ImportPreview)
    } catch (e: unknown) {
      setError((e as Error).message || t('receiving.import.failed'))
    } finally {
      setBusy(null)
      if (input.current) input.current.value = ''
    }
  }

  const kept = preview ? preview.matched.filter(r => !removed.has(r.rowNumber)) : []
  const units = kept.reduce((s, r) => s + r.quantity, 0)

  async function apply() {
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
    <div className="card p-5 space-y-4" data-testid="import-panel">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 className="text-h3 text-primary">{t('receiving.import.title')}</h2>
          <p className="text-small text-muted mt-0.5">{t('receiving.import.subtitle')}</p>
        </div>
        <button type="button" onClick={onClose} aria-label={t('receiving.import.close')}
          className="text-muted hover:text-primary transition-colors p-0.5 flex-none">
          <X size={18} strokeWidth={2} />
        </button>
      </div>

      <Alert tone="warning" title={t('receiving.import.shopifyWarning')} />

      <div className="flex flex-wrap items-center gap-3">
        <input ref={input} type="file" accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
          className="sr-only" data-testid="import-file"
          onChange={e => { const f = e.target.files?.[0]; if (f) upload(f) }} />
        <Button size="sm" loading={busy === 'upload'} onClick={() => input.current?.click()}>
          {preview ? t('receiving.import.chooseAnother') : t('receiving.import.choose')}
        </Button>
        <span className="text-caption text-muted">{t('receiving.import.formats')}</span>
      </div>

      {error && <Alert tone="critical" title={error} />}

      {preview && (
        <div className="space-y-4" data-testid="import-preview">
          <p className="text-body text-primary">
            {t('receiving.import.summary', { count: kept.length, units })}
            {preview.skipped > 0 && <span className="text-muted"> · {t('receiving.import.skipped', { count: preview.skipped })}</span>}
          </p>

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
                    {r.sameAsShopify && (
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
              <p className="text-small font-semibold text-primary">
                {t('receiving.import.notAdded', { count: preview.unmatched.length })}
              </p>
              <ul className="space-y-1.5">
                {preview.unmatched.map(r => (
                  <li key={`${r.rowNumber}-${r.reason}`} className="flex flex-wrap items-baseline gap-x-2 text-small">
                    <span className="text-muted tabular-nums" dir="ltr">{t('receiving.import.row', { n: r.rowNumber })}</span>
                    <span className="text-primary"><bdi>{[r.title, r.options].filter(Boolean).join(' · ')}</bdi></span>
                    {r.value && <span className="text-muted font-mono" dir="ltr">{r.value}</span>}
                    <Badge tone="warning" label={t(`receiving.import.reason.${r.reason}`, { defaultValue: r.reason })} />
                  </li>
                ))}
              </ul>
            </div>
          )}

          <div className="flex flex-wrap gap-2">
            <Button loading={busy === 'apply'} disabled={kept.length === 0} onClick={apply}>
              {t('receiving.import.apply', { count: units })}
            </Button>
            <Button variant="secondary" onClick={onClose}>{t('common.cancel')}</Button>
          </div>
        </div>
      )}
    </div>
  )
}
