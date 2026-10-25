import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Printer, ScanLine } from 'lucide-react'
import {
  getTagStock, startTagSession, printTagMany, openTagGroupsPdf, openTagLeftoverPdf, voidLabelCodes,
  ServerMessageError, TagLeftover, TagPrintMany, TagStockOverview, TagVariantStock,
} from '../../api'
import { Alert, Badge, Button, Checkbox, Input, Modal, ProductThumb, Spinner } from '../../components/ui'

/** The most labels one print issues (LabelCodeService.MAX_CODES_PER_BATCH). */
const MAX_LABELS = 2000

/** Labels printed for one product — handed to the scan loop for its per-product progress. */
export interface PrintedProduct { variantId: string; productTitle: string; variantTitle: string; count: number }

/**
 * Inventory → Untagged (flexible labeling UI pass, 2026-10-25; replaces the Tag stock page). The main warehouse's
 * products with untagged pieces (most first, searchable): tick products, set how many labels each (default and
 * max = its untagged count), and print them in ONE PDF grouped by product (a product header before each group).
 * A product's unused labels are reprinted first; only the rest is newly issued — the bar says "includes N
 * reprints". Then "Start scanning" opens the scan loop, which binds each label to its own product. Labels left
 * over from ended sessions are listed below with Reprint and Void. Owner / manager; switch on only.
 */
export default function UntaggedTab({ onTotal }: { onTotal?: (untagged: number) => void }) {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const [data, setData] = useState<TagStockOverview | null>(null)
  const [query, setQuery] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [reload, setReload] = useState(0)
  /** Ticked products → qty (a string while typing). */
  const [picked, setPicked] = useState<Map<string, { v: TagVariantStock; qty: string }>>(new Map())
  const [busy, setBusy] = useState<'print' | 'resume' | 'open' | null>(null)
  const [printed, setPrinted] = useState<{ sessionId: string; result: TagPrintMany } | null>(null)

  const readable = (e: unknown) => e instanceof ServerMessageError ? e.messageFor(i18n.language) : t('tagStock.error')

  useEffect(() => {
    const handle = setTimeout(() => {
      setLoading(true)
      getTagStock(query.trim() || undefined)
        .then(d => { setData(d); if (!query.trim()) onTotal?.(d.untagged) })
        .catch(e => setError(readable(e)))
        .finally(() => setLoading(false))
    }, query ? 250 : 0)
    return () => clearTimeout(handle)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query, reload])

  const variants = data?.variants ?? []
  const lines = useMemo(() => [...picked.values()].map(p => ({ v: p.v, count: Number(p.qty) })), [picked])
  const valid = lines.length > 0 && lines.every(l => Number.isInteger(l.count) && l.count >= 1 && l.count <= l.v.untagged)
  const labels = lines.reduce((n, l) => n + (Number.isFinite(l.count) ? l.count : 0), 0)
  const reprints = lines.reduce((n, l) => n + Math.min(l.v.reusableLabels ?? 0, Number.isFinite(l.count) ? l.count : 0), 0)
  const allPicked = variants.length > 0 && variants.every(v => picked.has(v.variantId))

  function toggle(v: TagVariantStock, on: boolean) {
    setPicked(prev => {
      const next = new Map(prev)
      if (on) next.set(v.variantId, { v, qty: String(v.untagged) })
      else next.delete(v.variantId)
      return next
    })
  }

  function selectAll(on: boolean) {
    setPicked(prev => {
      const next = new Map(prev)
      for (const v of variants) {
        if (on) { if (!next.has(v.variantId)) next.set(v.variantId, { v, qty: String(v.untagged) }) }
        else next.delete(v.variantId)
      }
      return next
    })
  }

  function setQty(v: TagVariantStock, raw: string) {
    const digits = raw.replace(/\D/g, '')
    const n = digits === '' ? '' : String(Math.min(v.untagged, Number(digits)))
    setPicked(prev => new Map(prev).set(v.variantId, { v, qty: n }))
  }

  async function print() {
    if (!valid || busy || labels > MAX_LABELS) return
    setBusy('print'); setError(null)
    try {
      const session = await startTagSession()
      const result = await printTagMany(session.id, lines.map(l => ({ variantId: l.v.variantId, count: l.count })),
        crypto.randomUUID())
      await openTagGroupsPdf(session.id, result.groups)
      setPrinted({ sessionId: session.id, result })
      setPicked(new Map())
      setReload(n => n + 1)
    } catch (e: unknown) {
      setError(readable(e))
    } finally {
      setBusy(null)
    }
  }

  async function openAgain() {
    if (!printed) return
    setBusy('open'); setError(null)
    try { await openTagGroupsPdf(printed.sessionId, printed.result.groups) }
    catch (e: unknown) { setError(readable(e)) }
    finally { setBusy(null) }
  }

  function startScanning(sessionId: string, result?: TagPrintMany) {
    const products: PrintedProduct[] = (result?.groups ?? []).map(g => ({
      variantId: g.variantId, productTitle: g.productTitle, variantTitle: g.variantTitle, count: g.codeIds.length }))
    navigate(`/tag-stock/${sessionId}/scan`, { state: { printed: products } })
  }

  async function resume() {
    setBusy('resume'); setError(null)
    try { startScanning((await startTagSession()).id) }
    catch (e: unknown) { setError(readable(e)) }
    finally { setBusy(null) }
  }

  const total = data ? data.labeled + data.untagged : 0

  return (
    <div className="space-y-4 pb-24" data-testid="untagged-tab">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <p className="text-small text-muted max-w-2xl">
          {t('inventory.untagged.intro')}
          {data && total > 0 && <> · {t('inventory.untagged.share', { labeled: data.labeled, total })}</>}
        </p>
        <Button size="sm" variant="secondary" iconStart={ScanLine} loading={busy === 'resume'} onClick={resume}>
          {t('tagStock.goScan')}
        </Button>
      </div>

      {error && <Alert tone="critical" title={error} />}

      {printed && (
        <div className="card p-4 flex flex-wrap items-center gap-3" data-testid="untagged-printed">
          <p className="text-body text-primary flex-1 min-w-0">
            {t('inventory.untagged.printed', { count: printed.result.labels, products: printed.result.groups.length })}
          </p>
          <Button variant="secondary" iconStart={Printer} loading={busy === 'open'} onClick={openAgain}>
            {t('inventory.untagged.openAgain')}
          </Button>
          <Button iconStart={ScanLine} onClick={() => startScanning(printed.sessionId, printed.result)}>
            {t('inventory.untagged.startScanning')}
          </Button>
        </div>
      )}

      <div className="card p-4 space-y-3">
        <div className="flex flex-wrap items-center gap-3">
          <div className="flex-1 min-w-[12rem]">
            <Input value={query} onChange={e => setQuery(e.target.value)} placeholder={t('tagStock.search')} />
          </div>
          {variants.length > 0 && (
            <Checkbox checked={allPicked} onChange={selectAll} label={t('inventory.untagged.selectAll')} />
          )}
        </div>
        {loading && !data ? (
          <div className="py-10 flex justify-center"><Spinner /></div>
        ) : variants.length === 0 ? (
          <p className="py-8 text-center text-small text-muted">{query ? t('tagStock.noMatch') : t('tagStock.allTagged')}</p>
        ) : (
          <ul className="divide-y divide-line" data-testid="untagged-variants">
            {variants.map(v => {
              const p = picked.get(v.variantId)
              const reusable = v.reusableLabels ?? 0
              return (
                <li key={v.variantId} className="py-3 flex flex-wrap items-center gap-3" data-testid={`untagged-row-${v.variantId}`}>
                  <Checkbox checked={!!p} onChange={on => toggle(v, on)}
                    label={<span className="sr-only">{t('inventory.untagged.pick', { product: `${v.productTitle} · ${v.variantTitle}` })}</span>} />
                  <ProductThumb src={v.imageUrl} alt={v.productTitle} size={44} />
                  <div className="min-w-0 flex-1">
                    <p className="text-body font-medium text-primary truncate"><bdi>{v.productTitle}</bdi></p>
                    <p className="text-small text-muted truncate">
                      <bdi>{v.variantTitle}</bdi>
                      {v.sku && <> · <span className="font-mono" dir="ltr">{v.sku}</span></>}
                    </p>
                    <p className="text-caption text-muted mt-0.5">
                      {t('inventory.untagged.counts', { untagged: v.untagged, labeled: v.labeled })}
                      {reusable > 0 && <span data-testid="unused-note"> · {t('inventory.untagged.unused', { count: reusable })}</span>}
                    </p>
                  </div>
                  <label className="flex items-center gap-2">
                    <span className="text-small text-muted">{t('inventory.untagged.qty')}</span>
                    <span className="w-20">
                      <Input type="text" inputMode="numeric" className="font-mono" disabled={!p}
                        value={p ? p.qty : ''} placeholder={String(v.untagged)}
                        onChange={e => setQty(v, e.target.value)}
                        onBlur={() => { if (p && (p.qty === '' || Number(p.qty) < 1)) setQty(v, '1') }}
                        aria-label={t('inventory.untagged.qtyFor', { product: `${v.productTitle} · ${v.variantTitle}` })}
                        data-testid={`untagged-qty-${v.variantId}`} />
                    </span>
                  </label>
                </li>
              )
            })}
          </ul>
        )}
      </div>

      {data?.leftovers && data.leftovers.length > 0 && (
        <LeftoverLabels leftovers={data.leftovers} onChanged={() => setReload(n => n + 1)} />
      )}

      {lines.length > 0 && (
        <div className="sticky bottom-0 z-10 -mx-1" data-testid="untagged-bar">
          <div className="card px-4 py-3 flex flex-wrap items-center gap-3 shadow-lg">
            <div className="flex-1 min-w-0">
              <p className="text-body font-semibold text-primary">
                {t('inventory.untagged.bar', { count: labels, products: lines.length })}
              </p>
              {reprints > 0 && (
                <p className="text-caption text-muted" data-testid="untagged-reprints">
                  {t('inventory.untagged.reprints', { count: reprints })}
                </p>
              )}
              {labels > MAX_LABELS && (
                <p className="text-caption text-danger">{t('inventory.untagged.tooMany', { max: MAX_LABELS })}</p>
              )}
            </div>
            <Button iconStart={Printer} loading={busy === 'print'} disabled={!valid || labels > MAX_LABELS} onClick={print}>
              {t('inventory.untagged.print')}
            </Button>
          </div>
        </div>
      )}
    </div>
  )
}

/** Labels left unused by ended sessions — they still bind when scanned; reprint them, or void the thrown-away ones. */
export function LeftoverLabels({ leftovers, onChanged }: { leftovers: TagLeftover[]; onChanged: () => void }) {
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
