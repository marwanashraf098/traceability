import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { request } from '../../api'

/**
 * Review mode S7 — click-to-scan chips for tenants with the scanHelpers capability (the public demo
 * and a simulated-courier review tenant; the CALLER renders this only when useCapabilities() says so,
 * and GET /scan-helpers answers 404 to anyone else anyway). Each chip's Scan button calls the
 * screen's own scan handler — the exact path a real scanner takes; there is no second scan path.
 * Candidates are refetched whenever refreshKey changes (a scanned code stops being a candidate).
 * Any failure renders nothing.
 */
export type ScanHelperContext = 'pieces' | 'waybills' | 'pickup' | 'returns' | 'lookup'

interface Candidate { code: string; label: string | null }

export default function ScanHelperChips({
  context,
  variantId,
  refreshKey = 0,
  disabled = false,
  onScan,
  showNoStock = false,
}: {
  context: ScanHelperContext
  variantId?: string
  refreshKey?: number | string
  disabled?: boolean
  onScan: (code: string) => void
  /** pieces only: when the variant has no available piece, say so and link to Receiving. */
  showNoStock?: boolean
}) {
  const { t } = useTranslation()
  const [items, setItems] = useState<Candidate[] | null>(null)

  useEffect(() => {
    let cancelled = false
    const qs = context === 'pieces' && variantId ? `?variantId=${encodeURIComponent(variantId)}` : ''
    request<{ items?: Candidate[] } | null>(`/scan-helpers/${context}${qs}`)
      .then(r => { if (!cancelled) setItems(Array.isArray(r?.items) ? r!.items! : []) })
      .catch(() => { if (!cancelled) setItems(null) })
    return () => { cancelled = true }
  }, [context, variantId, refreshKey])

  if (items === null) return null
  if (items.length === 0) {
    if (!(showNoStock && context === 'pieces')) return null
    return (
      <div className="mt-2.5 pt-2.5 border-t border-dashed border-line" data-testid="scan-helper-no-stock">
        <p className="text-caption text-muted">
          {t('scanHelpers.noStock')}{' '}
          <Link to="/receiving" className="text-brand font-semibold hover:underline">{t('scanHelpers.noStockLink')}</Link>
        </p>
      </div>
    )
  }
  return (
    <div className="mt-2.5 pt-2.5 border-t border-dashed border-line" data-testid={`scan-helper-${context}`}>
      <p className="text-caption text-muted mb-1.5">{t(`scanHelpers.hint.${context}`)}</p>
      <div className="flex flex-wrap gap-1.5">
        {items.map(c => (
          <div key={c.code} className="flex items-center gap-1.5 bg-elevated border border-line rounded-full ps-2.5 pe-1 py-0.5">
            {c.label && context !== 'pieces' && (
              <span className="text-caption text-muted" dir="ltr">
                {context === 'lookup' ? t(`scanHelpers.status.${c.label}`, { defaultValue: c.label }) : c.label}
              </span>
            )}
            <span className="text-caption font-mono text-primary" dir="ltr">{c.code.slice(-10)}</span>
            <button
              type="button"
              disabled={disabled}
              onClick={() => onScan(c.code)}
              className="text-caption font-semibold text-white bg-brand hover:bg-brand-hover rounded-full px-2.5 py-0.5
                         disabled:opacity-[0.35] disabled:cursor-not-allowed transition-colors"
              aria-label={t('scanHelpers.scanAria', { code: c.code })}
            >
              {t('scanHelpers.scan')}
            </button>
          </div>
        ))}
      </div>
    </div>
  )
}
