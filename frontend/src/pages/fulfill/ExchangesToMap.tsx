import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { Badge, Button } from '../../components/ui'
import { getExchangesToMap, getRoleFromToken } from '../../api'
import type { ExchangeToMap } from '../../api'
import { ExchangeForText } from './ShadowExchange'

// 2026-11-16 — Bosta exchanges of orders placed before the store connected (Storm "#2739:#2739-R1") whose new item
// hasn't been mapped yet. Beside the queue, never in it: nothing can be picked until a manager maps it on the
// existing mapping screen, which creates EXC-<AWB> (then a normal queue row). Display only; the server decides the set.

/** Loads GET /fulfill/exchanges-to-map; reloads whenever `reloadKey` changes. Failure = none. */
export function useExchangesToMap(reloadKey: unknown, enabled = true): ExchangeToMap[] {
  const [rows, setRows] = useState<ExchangeToMap[]>([])
  useEffect(() => {
    if (!enabled) return
    let cancelled = false
    getExchangesToMap().then(r => { if (!cancelled) setRows(r) })
    return () => { cancelled = true }
  }, [reloadKey, enabled])
  return rows
}

/** "Exchanges to map" — hidden when empty. "Map items" (owner / manager) opens the mapping screen, which comes back
 *  to Pick & Pack ready to pick the new order. */
export function ExchangesToMapSection({ rows }: { rows: ExchangeToMap[] }) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const role = getRoleFromToken()
  const canMap = role === 'owner' || role === 'manager'
  if (rows.length === 0) return null
  return (
    <section className="mt-6" data-testid="exchanges-to-map">
      <h2 className="text-h4 text-primary">{t('fulfill.toMap.title')} ({rows.length})</h2>
      <p className="text-small text-muted mt-1">{t('fulfill.toMap.hint')}</p>
      <ul className="mt-3 space-y-2">
        {rows.map(r => (
          <li key={r.exchangeId} className="card p-4 flex flex-wrap items-center gap-3"
              data-testid={`to-map-row-${r.exchangeId}`}>
            <div className="min-w-0 flex-1 space-y-1">
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-body font-semibold text-primary tabular-nums" dir="ltr">
                  {t('fulfill.toMap.awb', { tracking: r.trackingNumber })}
                </span>
                <Badge tone="info" label={t('exchange.badge')} />
                <Badge tone="warning" label={t('fulfill.toMap.badge')} />
              </div>
              <ExchangeForText exchangeFor={r.replacementFor} />
              {r.outboundDescription && (
                <p className="text-small text-muted truncate">{r.outboundDescription}</p>
              )}
            </div>
            {canMap ? (
              <Button size="sm" onClick={() => navigate(`/exchanges/${r.exchangeId}?from=fulfill`)}>
                {t('fulfill.toMap.mapItems')}
              </Button>
            ) : (
              <span className="text-caption text-muted">{t('fulfill.toMap.managerMaps')}</span>
            )}
          </li>
        ))}
      </ul>
    </section>
  )
}
