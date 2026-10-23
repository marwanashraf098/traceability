import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { getTagSession, voidUnusedTagLabels, ServerMessageError, TagSession } from '../api'
import { Alert, Button, ProductThumb, Spinner, useToast } from '../components/ui'

/**
 * Flexible labeling slice 3 — Tag stock, the session SUMMARY (in the shell): per variant how many labels were
 * printed, how many pieces got tagged, and how many labels were not used. Unused labels stay valid for their
 * product (scan them in a later session); "Void unused labels" retires them instead.
 */
export default function TagStockSummary() {
  const { id } = useParams<{ id: string }>()
  const { t, i18n } = useTranslation()
  const { toast } = useToast()
  const navigate = useNavigate()
  const [session, setSession] = useState<TagSession | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [voiding, setVoiding] = useState(false)

  const readable = (e: unknown) => e instanceof ServerMessageError ? e.messageFor(i18n.language) : t('tagStock.error')

  function load() {
    if (!id) return
    getTagSession(id).then(setSession).catch(e => setError(readable(e)))
  }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(load, [id])

  async function voidUnused() {
    if (!id) return
    setVoiding(true); setError(null)
    try {
      const r = await voidUnusedTagLabels(id)
      toast({ tone: 'success', message: t('tagStock.summary.voided', { count: r.voided }) })
      load()
    } catch (e: unknown) {
      setError(readable(e))
    } finally {
      setVoiding(false)
    }
  }

  if (!session) {
    return error ? <Alert tone="critical" title={error} /> : <div className="py-16 flex justify-center"><Spinner /></div>
  }
  const unused = session.variants.reduce((s, v) => s + v.unused, 0)

  return (
    <div className="space-y-4" data-testid="tag-stock-summary">
      <div>
        <h1 className="text-h1 text-primary">{t('tagStock.summary.title')}</h1>
        <p className="text-small text-muted mt-0.5">{t('tagStock.summary.tagged', { count: session.tagged })}</p>
      </div>
      {error && <Alert tone="critical" title={error} />}

      <div className="card overflow-hidden">
        <table className="min-w-full">
          <thead>
            <tr className="border-b border-line">
              <th className="tbl-header">{t('tagStock.summary.product')}</th>
              <th className="tbl-header text-end">{t('tagStock.summary.printed')}</th>
              <th className="tbl-header text-end">{t('tagStock.summary.taggedCol')}</th>
              <th className="tbl-header text-end">{t('tagStock.summary.unused')}</th>
            </tr>
          </thead>
          <tbody>
            {session.variants.length === 0 ? (
              <tr><td colSpan={4} className="px-4 py-8 text-center text-small text-muted">{t('tagStock.summary.none')}</td></tr>
            ) : session.variants.map(v => (
              <tr key={v.variantId} className="tbl-row" data-testid={`summary-row-${v.variantId}`}>
                <td className="tbl-cell">
                  <div className="flex items-center gap-3 min-w-0">
                    <ProductThumb src={v.imageUrl} alt={v.productTitle} size={36} />
                    <div className="min-w-0">
                      <p className="text-body font-medium text-primary truncate"><bdi>{v.productTitle}</bdi></p>
                      <p className="text-small text-muted truncate"><bdi>{v.variantTitle}</bdi></p>
                    </div>
                  </div>
                </td>
                <td className="tbl-cell text-end tabular-nums">{v.printed}</td>
                <td className="tbl-cell text-end tabular-nums">{v.tagged}</td>
                <td className="tbl-cell text-end tabular-nums">{v.unused}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {unused > 0 && (
        <Alert tone="info" title={t('tagStock.summary.unusedTitle', { count: unused })}>
          {t('tagStock.summary.unusedBody')}
        </Alert>
      )}

      <div className="flex flex-wrap gap-2">
        {unused > 0 && (
          <Button variant="secondary" loading={voiding} onClick={voidUnused}>{t('tagStock.summary.void')}</Button>
        )}
        <Button onClick={() => navigate('/tag-stock')}>{t('tagStock.summary.back')}</Button>
      </div>
    </div>
  )
}
