import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Alert, Button } from '../../components/ui'
import { api, type Line } from '../Receiving'

/**
 * S1b — Receiving a returned unit from a Return To Receive alert (?return=<key>). Shows the order it came back from
 * and, per item, how many units Shopify already restocked for that order: those are not added to Shopify again when
 * the session is finished (the warning before the manager receives them). Inside an open session it adds the item as
 * a line LINKED to the return; linked lines are listed here (never in the product grid, which merges lines).
 */
interface ReturnItem {
  variant_id: string; product_title: string; variant_title: string | null; sku: string | null
  alreadyInShopify: number
}
interface ReturnPreview { source: string; orderNumber: string | null; unitLevel: boolean; items: ReturnItem[] }

export function ReturnReceiveBanner({ returnKey, sessionId, canAdd, linkedLines, onAdded, onDismiss }: {
  returnKey: string
  sessionId?: string
  canAdd: boolean
  linkedLines: Line[]
  onAdded: () => void
  onDismiss: () => void
}) {
  const { t } = useTranslation()
  const [preview, setPreview] = useState<ReturnPreview | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState<string | null>(null)

  useEffect(() => {
    let live = true
    api<ReturnPreview>(`/receiving/return-source?key=${encodeURIComponent(returnKey)}`)
      .then(p => { if (live) setPreview(p) })
      .catch((e: unknown) => { if (live) setError((e as Error).message) })
    return () => { live = false }
  }, [returnKey, linkedLines.length])

  async function add(variantId: string) {
    if (!sessionId) return
    setBusy(variantId); setError(null)
    try {
      await api(`/receiving/sessions/${sessionId}/lines`, {
        method: 'POST', body: JSON.stringify({ variantId, quantity: 1, returnSource: returnKey }),
      })
      onAdded()
    } catch (e: unknown) { setError((e as Error).message) }
    finally { setBusy(null) }
  }

  if (error && !preview) return <Alert tone="critical" title={error} />
  if (!preview) return null
  const order = preview.orderNumber ?? ''
  const alreadyLinked = preview.unitLevel && linkedLines.some(l => l.return_source === preview.source)

  return (
    <div className="card p-4 space-y-3" data-testid="return-receive-banner">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-body font-medium text-primary">{t('receiving.returnLink.title', { order })}</p>
          <p className="text-small text-muted">
            {sessionId ? t('receiving.returnLink.sub') : t('receiving.returnLink.openSession')}
          </p>
        </div>
        <Button size="sm" variant="secondary" onClick={onDismiss}>{t('receiving.returnLink.dismiss')}</Button>
      </div>
      {error && <Alert tone="critical" title={error} />}
      <ul className="divide-y divide-line border-y border-line">
        {preview.items.map(it => (
          <li key={it.variant_id} className="py-2.5 space-y-1.5">
            <div className="flex items-center justify-between gap-3">
              <div className="min-w-0">
                <p className="text-body text-primary truncate"><bdi>{it.product_title}</bdi></p>
                <p className="text-small text-muted truncate"><bdi>{it.variant_title}</bdi>
                  {it.sku && <> · <span className="font-mono" dir="ltr">{it.sku}</span></>}</p>
              </div>
              {sessionId && canAdd && !alreadyLinked && (
                <span data-testid={`return-add-${it.variant_id}`}>
                  <Button size="sm" disabled={busy !== null} onClick={() => add(it.variant_id)}>
                    {t('receiving.returnLink.add')}
                  </Button>
                </span>
              )}
            </div>
            {it.alreadyInShopify > 0 && (
              <div data-testid="return-already-in-shopify">
                <Alert tone="warning"
                       title={t('receiving.returnLink.alreadyInShopify', { count: it.alreadyInShopify, order })} />
              </div>
            )}
          </li>
        ))}
      </ul>
      {alreadyLinked && <p className="text-small text-muted">{t('receiving.returnLink.added')}</p>}
    </div>
  )
}

/** The session's lines that bring back a return — removable, never edited in the product grid. */
export function ReturnedLines({ lines, sessionId, canEdit, onChanged }: {
  lines: Line[]; sessionId: string; canEdit: boolean; onChanged: () => void
}) {
  const { t } = useTranslation()
  const [error, setError] = useState<string | null>(null)
  if (lines.length === 0) return null
  async function remove(id: string) {
    setError(null)
    try {
      await api(`/receiving/sessions/${sessionId}/lines/${id}`, { method: 'DELETE' })
      onChanged()
    } catch (e: unknown) { setError((e as Error).message) }
  }
  return (
    <div className="card p-4 space-y-2" data-testid="returned-lines">
      <p className="text-body font-medium text-primary">{t('receiving.returnLink.linesTitle')}</p>
      {error && <Alert tone="critical" title={error} />}
      <ul className="divide-y divide-line">
        {lines.map(l => (
          <li key={l.id} className="flex items-center justify-between gap-3 py-2">
            <div className="min-w-0">
              <p className="text-body text-primary truncate"><bdi>{l.product_title}</bdi> · <bdi>{l.variant_title}</bdi></p>
              <p className="text-small text-muted">
                {t('receiving.returnLink.lineOrder', { order: l.return_order_number ?? '', count: l.quantity })}
                {(l.shopify_restocked_units ?? 0) > 0 && <> · {t('receiving.returnLink.lineSkipped',
                  { count: l.shopify_restocked_units })}</>}
              </p>
            </div>
            {canEdit && (
              <Button size="sm" variant="secondary" onClick={() => remove(l.id)}>
                {t('receiving.returnLink.remove')}
              </Button>
            )}
          </li>
        ))}
      </ul>
    </div>
  )
}
