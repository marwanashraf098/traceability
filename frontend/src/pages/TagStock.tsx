import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Barcode, Tag } from 'lucide-react'
import {
  getTagStock, startTagSession, printTagLabels, openLabelBatchPdf, openTagReprintPdf, openTagLeftoverPdf, voidLabelCodes,
  ServerMessageError, TagLeftover, TagStockOverview, TagVariantStock,
} from '../api'
import { Alert, Badge, Button, EmptyState, Input, MiniStat, Modal, ProductThumb, Spinner } from '../components/ui'

/**
 * Flexible labeling slice 3 — Tag stock, the LIST view (in the shell): the main warehouse's labeled vs untagged
 * totals and the variants that still have untagged pieces (most untagged first, searchable). Pick one, choose
 * how many labels (default and max = its untagged count), print them; the scan loop (/tag-stock/:id/scan)
 * opens for that variant. Owner / manager; main warehouse only.
 *
 * Live-test fix (2026-10-25): when the caller's open session already printed labels for the product that aren't
 * used yet, the panel defaults to "Reprint the N unused labels" (the same codes — nothing new issued), with
 * "Print new labels" as the secondary choice. Labels left over from ENDED sessions are listed under
 * "Unused labels" with Reprint and Void (the existing all-or-nothing void).
 */
export default function TagStock() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const [data, setData] = useState<TagStockOverview | null>(null)
  const [query, setQuery] = useState('')
  const [loading, setLoading] = useState(true)
  const [unavailable, setUnavailable] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [count, setCount] = useState(0)
  const [busy, setBusy] = useState<'print' | 'reprint' | 'resume' | null>(null)
  // The secondary choice: print NEW labels although this session's unused ones exist.
  const [printNew, setPrintNew] = useState(false)
  const [reload, setReload] = useState(0)

  const readable = (e: unknown) =>
    e instanceof ServerMessageError ? e.messageFor(i18n.language) : t('tagStock.error')

  useEffect(() => {
    const handle = setTimeout(() => {
      setLoading(true)
      getTagStock(query.trim() || undefined)
        .then(d => { setData(d); setUnavailable(false) })
        .catch(e => { if (e instanceof ServerMessageError && e.status === 404) setUnavailable(true); else setError(readable(e)) })
        .finally(() => setLoading(false))
    }, query ? 250 : 0)
    return () => clearTimeout(handle)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query, reload])

  function choose(v: TagVariantStock) {
    setSelected(v.variantId)
    setCount(v.untagged)
    setPrintNew(false)
    setError(null)
  }

  async function reprint(v: TagVariantStock) {
    setBusy('reprint'); setError(null)
    try {
      const session = await startTagSession()
      await openTagReprintPdf(session.id, v.variantId)
      navigate(`/tag-stock/${session.id}/scan?variant=${v.variantId}`)
    } catch (e: unknown) {
      setError(readable(e))
    } finally {
      setBusy(null)
    }
  }

  async function print(v: TagVariantStock) {
    setBusy('print'); setError(null)
    try {
      const session = await startTagSession()
      const batch = await printTagLabels(session.id, v.variantId, count, crypto.randomUUID())
      await openLabelBatchPdf(batch.id)
      navigate(`/tag-stock/${session.id}/scan?variant=${v.variantId}`)
    } catch (e: unknown) {
      setError(readable(e))
    } finally {
      setBusy(null)
    }
  }

  async function resume() {
    setBusy('resume'); setError(null)
    try {
      const session = await startTagSession()
      navigate(`/tag-stock/${session.id}/scan`)
    } catch (e: unknown) {
      setError(readable(e))
    } finally {
      setBusy(null)
    }
  }

  if (unavailable) {
    return <EmptyState icon="🏷️" message={t('tagStock.unavailable')} />
  }

  const total = data ? data.labeled + data.untagged : 0
  const pct = total > 0 && data ? Math.round((data.labeled / total) * 100) : 0

  return (
    <div className="space-y-4" data-testid="tag-stock">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-h1 text-primary">{t('tagStock.title')}</h1>
          <p className="text-small text-muted mt-0.5">{t('tagStock.subtitle')}</p>
        </div>
        <Button size="sm" variant="secondary" loading={busy === 'resume'} onClick={resume}>
          {t('tagStock.goScan')}
        </Button>
      </div>

      {data && (
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-3" data-testid="tag-stock-totals">
          <MiniStat icon={Barcode} value={data.labeled} label={t('tagStock.labeled')} />
          <MiniStat icon={Tag} value={data.untagged} label={t('tagStock.untagged')} tone={data.untagged > 0 ? 'warning' : 'neutral'} />
          <MiniStat icon={Barcode} value={`${pct}%`} label={t('tagStock.labeledShare')} />
        </div>
      )}

      {error && <Alert tone="critical" title={error} />}

      <div className="card p-4 space-y-3">
        <Input value={query} onChange={e => setQuery(e.target.value)} placeholder={t('tagStock.search')} />
        {loading && !data ? (
          <div className="py-10 flex justify-center"><Spinner /></div>
        ) : !data || data.variants.length === 0 ? (
          <p className="py-8 text-center text-small text-muted">{query ? t('tagStock.noMatch') : t('tagStock.allTagged')}</p>
        ) : (
          <ul className="divide-y divide-line" data-testid="tag-stock-variants">
            {data.variants.map(v => (
              <li key={v.variantId} className="py-3 space-y-3">
                <button type="button" onClick={() => choose(v)} data-testid={`tag-variant-${v.variantId}`}
                  aria-expanded={selected === v.variantId}
                  className="w-full flex items-center gap-3 text-start">
                  <ProductThumb src={v.imageUrl} alt={v.productTitle} size={44} />
                  <div className="min-w-0 flex-1">
                    <p className="text-body font-medium text-primary truncate"><bdi>{v.productTitle}</bdi></p>
                    <p className="text-small text-muted truncate">
                      <bdi>{v.variantTitle}</bdi>
                      {v.sku && <> · <span className="font-mono" dir="ltr">{v.sku}</span></>}
                    </p>
                  </div>
                  <div className="flex flex-col items-end gap-1 flex-none">
                    <Badge tone="warning" label={t('tagStock.untaggedCount', { count: v.untagged })} />
                    <span className="text-caption text-muted">{t('tagStock.labeledCount', { count: v.labeled })}</span>
                  </div>
                </button>
                {selected === v.variantId && (v.unusedThisSession ?? 0) > 0 && !printNew ? (
                  <div className="flex flex-wrap items-center gap-3 ps-[56px]" data-testid="tag-reprint-panel">
                    <Button loading={busy === 'reprint'} onClick={() => reprint(v)}>
                      {t('tagStock.reprintUnused', { count: v.unusedThisSession })}
                    </Button>
                    <Button variant="secondary" onClick={() => setPrintNew(true)}>{t('tagStock.printNew')}</Button>
                    <p className="text-caption text-muted basis-full">{t('tagStock.reprintHint')}</p>
                  </div>
                ) : selected === v.variantId && (
                  <div className="flex flex-wrap items-end gap-3 ps-[56px]" data-testid="tag-print-panel">
                    <label className="space-y-1.5">
                      <span className="text-small text-muted block">{t('tagStock.howMany')}</span>
                      <Input type="number" min={1} max={v.untagged} value={String(count)}
                        onChange={e => setCount(Math.max(0, Math.min(v.untagged, Math.floor(Number(e.target.value) || 0))))}
                        className="w-28" />
                    </label>
                    <Button loading={busy === 'print'} disabled={count < 1} onClick={() => print(v)}>
                      {t('tagStock.print', { count })}
                    </Button>
                    {v.unusedLabels > 0 && (
                      <p className="text-caption text-muted basis-full">{t('tagStock.unusedExisting', { count: v.unusedLabels })}</p>
                    )}
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>

      {data?.leftovers && data.leftovers.length > 0 && (
        <LeftoverLabels leftovers={data.leftovers} onChanged={() => setReload(n => n + 1)} />
      )}
    </div>
  )
}

/** Labels left unused by ended sessions — they still bind when scanned; reprint them, or void the thrown-away ones. */
function LeftoverLabels({ leftovers, onChanged }: { leftovers: TagLeftover[]; onChanged: () => void }) {
  const { t, i18n } = useTranslation()
  const [busy, setBusy] = useState<string | null>(null)
  const [confirm, setConfirm] = useState<TagLeftover | null>(null)
  const [error, setError] = useState<string | null>(null)
  const readable = (e: unknown) =>
    e instanceof ServerMessageError ? e.messageFor(i18n.language) : t('tagStock.error')

  async function reprint(l: TagLeftover) {
    setBusy(`reprint:${l.variantId}`); setError(null)
    try { await openTagLeftoverPdf(l.variantId) }
    catch (e: unknown) { setError(readable(e)) }
    finally { setBusy(null) }
  }

  async function doVoid(l: TagLeftover) {
    setBusy(`void:${l.variantId}`); setError(null)
    try {
      await voidLabelCodes(l.codeIds, 'tag_session_leftover')
      setConfirm(null)
      onChanged()
    } catch (e: unknown) {
      setError(readable(e))
    } finally {
      setBusy(null)
    }
  }

  return (
    <section className="card p-4 space-y-3" data-testid="tag-leftovers">
      <div>
        <h2 className="text-h3 text-primary">{t('tagStock.leftovers.title')}</h2>
        <p className="text-small text-muted mt-0.5">{t('tagStock.leftovers.subtitle')}</p>
      </div>
      {error && <Alert tone="critical" title={error} />}
      <ul className="divide-y divide-line">
        {leftovers.map(l => (
          <li key={l.variantId} className="py-3 flex flex-wrap items-center gap-3" data-testid={`tag-leftover-${l.variantId}`}>
            <ProductThumb src={l.imageUrl} alt={l.productTitle} size={40} />
            <div className="min-w-0 flex-1">
              <p className="text-body font-medium text-primary truncate"><bdi>{l.productTitle}</bdi></p>
              <p className="text-small text-muted truncate">
                <bdi>{l.variantTitle}</bdi>
                {l.sku && <> · <span className="font-mono" dir="ltr">{l.sku}</span></>}
              </p>
            </div>
            <Badge tone="neutral" label={t('tagStock.leftovers.count', { count: l.count })} />
            <div className="flex gap-2">
              <Button size="sm" variant="secondary" loading={busy === `reprint:${l.variantId}`} onClick={() => reprint(l)}>
                {t('tagStock.leftovers.reprint')}
              </Button>
              <Button size="sm" variant="destructive" onClick={() => setConfirm(l)}>{t('tagStock.leftovers.void')}</Button>
            </div>
          </li>
        ))}
      </ul>
      {confirm && (
        <Modal title={t('tagStock.leftovers.voidTitle', { count: confirm.count })}
          onClose={() => { if (!busy) setConfirm(null) }}>
          <div className="space-y-4">
            <p className="text-body text-muted">{t('tagStock.leftovers.voidBody')}</p>
            <div className="flex gap-2">
              <Button variant="secondary" className="flex-1" disabled={!!busy} onClick={() => setConfirm(null)}>
                {t('common.cancel')}
              </Button>
              <Button variant="destructive" className="flex-1" loading={busy === `void:${confirm.variantId}`}
                onClick={() => doVoid(confirm)}>
                {t('tagStock.leftovers.void')}
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </section>
  )
}
