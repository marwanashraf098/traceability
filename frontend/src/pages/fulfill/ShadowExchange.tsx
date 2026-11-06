import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Clock } from 'lucide-react'
import { Badge } from '../../components/ui'
import { getWaitingForRelease } from '../../api'
import type { ShadowExchangeFor, WaitingForReleaseOrder } from '../../api'

// S3c — Shopify-native exchange "shadow" orders (#1010-X1) in Pick & Pack. A shadow says which order it
// replaces (exchangeFor), how many units the warehouse is short for it (shortBy), and — while Shopify hasn't
// released it for fulfilment — that it can't be packed yet (heldForRelease / the "Waiting for release" list).
// Display only: every rule is the server's (scan / complete / the waybill resolver refuse HELD_FOR_RELEASE).

/** "Exchange for #1010" — plain text (Pick & Pack never links out to an order). */
export function ExchangeForText({ exchangeFor, className = '' }: { exchangeFor?: ShadowExchangeFor | null; className?: string }) {
  const { t } = useTranslation()
  if (!exchangeFor) return null
  return (
    <span className={`text-caption text-muted whitespace-nowrap ${className}`} data-testid="shadow-exchange-for">
      {t('fulfill.shadow.exchangeFor', { number: exchangeFor.number ?? '' })}
    </span>
  )
}

/** "Short by N" — only when the warehouse is short for this exchange. */
export function ShortByChip({ shortBy }: { shortBy?: number | null }) {
  const { t } = useTranslation()
  if (!shortBy || shortBy <= 0) return null
  return (
    <span
      className="inline-flex items-center whitespace-nowrap bg-warning/[0.14] border border-warning/[0.30] text-warning-text text-caption font-semibold px-2 py-0.5 rounded-full"
      data-testid="shadow-short-by"
    >
      {t('fulfill.shadow.shortBy', { count: shortBy })}
    </span>
  )
}

/** The pick screen / waybill card notice while Shopify hasn't released the exchange. */
export function HeldForReleaseNotice({ held }: { held?: boolean }) {
  const { t } = useTranslation()
  if (!held) return null
  return (
    <div className="rounded-xl border border-warning/[0.30] bg-warning/[0.14] p-3.5 flex gap-3" data-testid="shadow-held-notice">
      <Clock size={18} strokeWidth={2} className="text-warning-text flex-shrink-0 mt-0.5" />
      <div className="min-w-0">
        <p className="text-body font-semibold text-warning-text">{t('fulfill.shadow.heldTitle')}</p>
        <p className="text-small text-primary mt-0.5">{t('fulfill.shadow.heldBody')}</p>
      </div>
    </div>
  )
}

function lineName(i: WaitingForReleaseOrder['items'][number]): string {
  return [i.product_title, i.variant_title && i.variant_title !== 'Default Title' ? i.variant_title : null]
    .filter(Boolean).join(' · ')
}

/** Loads GET /fulfill/waiting-for-release; reloads whenever `reloadKey` changes. Failure = none. */
export function useWaitingForRelease(reloadKey: unknown, enabled = true): WaitingForReleaseOrder[] {
  const [rows, setRows] = useState<WaitingForReleaseOrder[]>([])
  useEffect(() => {
    if (!enabled) return
    let cancelled = false
    getWaitingForRelease().then(r => { if (!cancelled) setRows(r) })
    return () => { cancelled = true }
  }, [reloadKey, enabled])
  return rows
}

/** "Waiting for release" — beside the queue, never in it; rows don't open the pick screen. Hidden when empty. */
export function WaitingForReleaseSection({ rows }: { rows: WaitingForReleaseOrder[] }) {
  const { t } = useTranslation()
  if (rows.length === 0) return null
  return (
    <section className="mt-8" data-testid="waiting-for-release">
      <h2 className="text-caption text-muted font-semibold uppercase tracking-wide mb-1">
        {t('fulfill.shadow.waitingTitle')} ({rows.length})
      </h2>
      <p className="text-small text-muted mb-3">{t('fulfill.shadow.waitingHint')}</p>
      <div className="space-y-2">
        {rows.map(o => (
          <div key={o.id} className="card p-3 md:py-2.5 flex flex-col gap-2" data-testid={`waiting-row-${o.id}`}>
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-mono text-small font-semibold text-primary">{o.number ?? o.id.slice(-8)}</span>
              <Badge tone="info" label={t('exchange.badge')} />
              <ExchangeForText exchangeFor={o.exchangeFor} />
              <span className="flex-1" />
              <ShortByChip shortBy={o.shortBy} />
              <span className="inline-flex items-center gap-1.5 text-caption font-semibold text-warning-text whitespace-nowrap">
                <Clock size={12} strokeWidth={2} />
                {t('fulfill.shadow.waitingStatus')}
              </span>
            </div>
            {o.items.length > 0 && (
              <ul className="space-y-0.5">
                {o.items.map((i, n) => (
                  <li key={`${i.variant_id}-${n}`} className="text-small text-primary flex gap-2">
                    <span className="font-mono text-muted">{i.quantity}×</span>
                    <span className="break-words min-w-0">{lineName(i)}</span>
                  </li>
                ))}
              </ul>
            )}
          </div>
        ))}
      </div>
    </section>
  )
}
