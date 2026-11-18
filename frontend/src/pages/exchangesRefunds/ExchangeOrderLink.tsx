import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Search, Download } from 'lucide-react'
import {
  searchExchangeOrders, attachExchange, importExchangeParent,
  ExchangeOrderHit, ExchangeOrderItem, ExchangeOrderSearchResult, ExchangeImportResult,
} from '../../api'
import { Alert, Button, Input, Skeleton, useToast } from '../../components/ui'

/**
 * Exchange reference S2 (2026-11-18) — "Link to the original order" in the exchange drawer. Replaces the raw order-id
 * box: suggestions before typing (orders of the courier's receiver phone), a search by number / phone / name, an
 * "Import from Shopify" row for a pre-connect number, and a confirm step showing the order's items before anything is
 * linked. Owner / manager only — the server enforces it.
 */
type Confirm =
  | { kind: 'order'; order: ExchangeOrderHit }
  | { kind: 'import'; number: string; items: ExchangeOrderItem[] }

const DEBOUNCE_MS = 300

export default function ExchangeOrderLink({
  exchangeId,
  needsMapping,
  onLinked,
}: {
  exchangeId: string
  /** The replacement isn't mapped yet — the confirm step says it still has to be. */
  needsMapping: boolean
  onLinked: () => void
}) {
  const { t, i18n } = useTranslation()
  const { toast } = useToast()
  const [q, setQ] = useState('')
  const [result, setResult] = useState<ExchangeOrderSearchResult | null>(null)
  const [loading, setLoading] = useState(true)
  const [failed, setFailed] = useState(false)
  const [confirm, setConfirm] = useState<Confirm | null>(null)
  const [importing, setImporting] = useState(false)
  const [importMiss, setImportMiss] = useState<ExchangeImportResult | null>(null)
  const [linking, setLinking] = useState(false)
  const seq = useRef(0)

  useEffect(() => {
    const mine = ++seq.current
    setLoading(true)
    setFailed(false)
    setImportMiss(null)
    const timer = setTimeout(() => {
      searchExchangeOrders(exchangeId, q.trim())
        .then(res => { if (seq.current === mine) setResult(res) })
        .catch(() => { if (seq.current === mine) { setFailed(true); setResult(null) } })
        .finally(() => { if (seq.current === mine) setLoading(false) })
    }, q.trim() === '' ? 0 : DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [exchangeId, q])

  const formatDate = (iso: string | null) => {
    if (!iso) return null
    const d = new Date(iso)
    if (Number.isNaN(d.getTime())) return null
    return d.toLocaleDateString(i18n.language?.startsWith('ar') ? 'ar-EG' : 'en-GB',
      { day: 'numeric', month: 'short', year: 'numeric' })
  }

  const startImport = async (number: string) => {
    setImporting(true)
    setImportMiss(null)
    try {
      const res = await importExchangeParent(exchangeId, number, false)
      if (res.status === 'FOUND') setConfirm({ kind: 'import', number: res.number ?? number, items: res.items })
      else setImportMiss(res)
    } catch {
      setImportMiss({ status: 'UNAVAILABLE', reason: null, number, items: [], exchangeStatus: null })
    } finally {
      setImporting(false)
    }
  }

  const link = async () => {
    if (!confirm) return
    setLinking(true)
    const number = confirm.kind === 'order' ? confirm.order.number : `#${confirm.number}`
    try {
      if (confirm.kind === 'order') {
        await attachExchange(exchangeId, confirm.order.id)
      } else {
        const res = await importExchangeParent(exchangeId, confirm.number, true)
        if (res.status !== 'LINKED') {
          setConfirm(null)
          setImportMiss(res)
          return
        }
      }
      toast({ tone: 'success', message: t('exchangesRefunds.drawer.exchange.link.linkedToast', { number }) })
      onLinked()
    } catch {
      toast({ tone: 'error', message: t('exchangesRefunds.drawer.exchange.actionFailed') })
    } finally {
      setLinking(false)
    }
  }

  if (confirm) {
    const number = confirm.kind === 'order' ? confirm.order.number : `#${confirm.number}`
    const items = confirm.kind === 'order' ? confirm.order.items : confirm.items
    return (
      <section className="space-y-3" data-testid="exchange-link-confirm">
        <h3 className="text-caption font-semibold text-muted uppercase tracking-wider">
          {t('exchangesRefunds.drawer.exchange.link.title')}
        </h3>
        <div className="card p-3.5 space-y-3">
          <div>
            <p className="text-body font-semibold text-primary">
              {t('exchangesRefunds.drawer.exchange.link.confirmTitle')}{' '}
              <bdi dir="ltr" className="font-mono">{number}</bdi>
            </p>
            {confirm.kind === 'order' ? (
              <p className="text-small text-muted">
                {[formatDate(confirm.order.placedAt), confirm.order.customerName].filter(Boolean).join(' · ') || '—'}
              </p>
            ) : (
              <p className="text-small text-muted">{t('exchangesRefunds.drawer.exchange.link.importedNote')}</p>
            )}
          </div>
          <div>
            <p className="text-caption text-muted mb-1">{t('exchangesRefunds.drawer.exchange.link.itemsLabel')}</p>
            {items.length === 0 ? (
              <p className="text-small text-muted">—</p>
            ) : (
              <ul className="space-y-1" data-testid="exchange-link-confirm-items">
                {items.map((it, i) => (
                  <li key={i} className="flex items-baseline justify-between gap-3 text-small">
                    <span className="min-w-0 text-primary">
                      {it.productTitle ?? '—'}
                      {it.variantTitle && <span className="text-muted"> · {it.variantTitle}</span>}
                    </span>
                    <span className="flex-shrink-0 text-muted tabular-nums">×{it.quantity}</span>
                  </li>
                ))}
              </ul>
            )}
          </div>
          {needsMapping && (
            <p className="text-small text-muted">{t('exchangesRefunds.drawer.exchange.link.stillMapNote')}</p>
          )}
        </div>
        <div className="flex flex-wrap gap-2" data-testid="exchange-link-confirm-actions">
          <Button loading={linking} onClick={link}>
            {t('exchangesRefunds.drawer.exchange.link.confirmButton')}
          </Button>
          <Button variant="secondary" disabled={linking} onClick={() => setConfirm(null)}>
            {t('exchangesRefunds.drawer.exchange.link.back')}
          </Button>
        </div>
      </section>
    )
  }

  const orders = result?.orders ?? []
  const suggested = q.trim() === ''

  return (
    <section className="space-y-3" data-testid="exchange-order-link">
      <div>
        <h3 className="text-caption font-semibold text-muted uppercase tracking-wider mb-1">
          {t('exchangesRefunds.drawer.exchange.link.title')}
        </h3>
        <p className="text-small text-muted">{t('exchangesRefunds.drawer.exchange.link.hint')}</p>
      </div>
      <Input
        iconStart={Search}
        value={q}
        onChange={e => setQ(e.target.value)}
        placeholder={t('exchangesRefunds.drawer.exchange.link.searchPlaceholder')}
        aria-label={t('exchangesRefunds.drawer.exchange.link.searchPlaceholder')}
        data-testid="exchange-order-search"
      />

      {suggested && !loading && !failed && orders.length > 0 && (
        <p className="text-caption text-muted">{t('exchangesRefunds.drawer.exchange.link.suggestedLabel')}</p>
      )}

      {loading ? (
        <div className="space-y-2">
          <Skeleton className="h-16 rounded-xl" />
          <Skeleton className="h-16 rounded-xl" />
        </div>
      ) : failed ? (
        <p className="text-small text-critical">{t('exchangesRefunds.drawer.exchange.link.searchFailed')}</p>
      ) : (
        <div className="space-y-2">
          {orders.map(o => (
            <OrderRow key={o.id} order={o} date={formatDate(o.placedAt)} onLink={() => setConfirm({ kind: 'order', order: o })} />
          ))}
          {result?.importOption && (
            <div className="card p-3 space-y-2 border-dashed" data-testid="exchange-import-row">
              <p className="text-small text-primary">
                {t('exchangesRefunds.drawer.exchange.link.importPrompt')}{' '}
                <bdi dir="ltr" className="font-mono font-semibold">#{result.importOption.number}</bdi>
              </p>
              <p className="text-caption text-muted">{t('exchangesRefunds.drawer.exchange.link.importHint')}</p>
              <Button variant="secondary" size="sm" iconStart={Download} loading={importing}
                onClick={() => startImport(result.importOption!.number)}>
                {t('exchangesRefunds.drawer.exchange.link.importButton')}
              </Button>
            </div>
          )}
          {importMiss && <ImportMiss res={importMiss} />}
          {orders.length === 0 && !result?.importOption && (
            <p className="text-small text-muted" data-testid="exchange-order-empty">
              {suggested
                ? t('exchangesRefunds.drawer.exchange.link.suggestionsEmpty')
                : t('exchangesRefunds.drawer.exchange.link.noResults')}
            </p>
          )}
        </div>
      )}
    </section>
  )
}

function OrderRow({ order, date, onLink }: { order: ExchangeOrderHit; date: string | null; onLink: () => void }) {
  const { t } = useTranslation()
  const shown = order.items.slice(0, 2)
  const more = order.items.length - shown.length
  return (
    <div className="card p-3 flex items-start justify-between gap-3" data-testid={`exchange-order-${order.id}`}>
      <div className="min-w-0 space-y-0.5">
        <p className="text-small font-semibold text-primary">
          <bdi dir="ltr" className="font-mono">{order.number}</bdi>
        </p>
        <p className="text-caption text-muted truncate">
          {[date, order.customerName].filter(Boolean).join(' · ') || '—'}
        </p>
        {shown.map((it, i) => (
          <p key={i} className="text-caption text-muted truncate">
            {it.productTitle ?? '—'}{it.variantTitle ? ` · ${it.variantTitle}` : ''} ×{it.quantity}
          </p>
        ))}
        {more > 0 && (
          <p className="text-caption text-muted">{t('exchangesRefunds.drawer.exchange.link.moreItems', { count: more })}</p>
        )}
      </div>
      <Button variant="secondary" size="sm" onClick={onLink}>
        {t('exchangesRefunds.drawer.exchange.link.linkButton')}
      </Button>
    </div>
  )
}

function ImportMiss({ res }: { res: ExchangeImportResult }) {
  const { t } = useTranslation()
  const number = res.number ? `#${res.number}` : ''
  const key = res.status === 'UNAVAILABLE' ? 'importUnavailable'
    : res.status === 'NOT_PRE_CONNECT' ? 'importNotPreConnect'
    : res.reason === 'AMBIGUOUS' ? 'importAmbiguous'
    : res.reason === 'NO_CATALOG_LINES' ? 'importNoCatalog'
    : res.reason === 'CACHED_MISS' ? 'importNotFoundRecently'
    : 'importNotFound'
  return (
    <div data-testid="exchange-import-miss">
      <Alert tone={res.status === 'UNAVAILABLE' ? 'warning' : 'info'}
        title={t(`exchangesRefunds.drawer.exchange.link.${key}`, { number })} />
    </div>
  )
}
