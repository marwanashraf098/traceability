import type { ReactNode } from 'react'
import type { TFunction } from 'i18next'
import { useTranslation } from 'react-i18next'
import { Repeat2, Undo2 } from 'lucide-react'
import type { ShopifyInTraced, ShopifyReturnLine, ShopifyReturnStateCode, ShopifyReturnView } from '../api'
import { ProductThumb, cn } from './ui'

/**
 * Shopify-native returns S2 — READ-ONLY display of a return made in Shopify (a returns app or Shopify's self-serve
 * returns). The order drawer shows one card per return; the Returns page reuses the chips and labels. Traced takes no
 * action on these: no buttons, no booking, nothing kept aside (S3+).
 */

/** Shopify state → chip colours (the mockup's tones). */
const STATE_PILL: Record<ShopifyReturnStateCode, string> = {
  requested: 'bg-[#E0E7FF] text-[#1E3A8A]',
  open: 'bg-[#E0E7FF] text-[#1E3A8A]',
  released: 'bg-[#E0E7FF] text-[#1E3A8A]',
  on_hold: 'bg-[#FEF3C7] text-[#78350F]',
  shipped: 'bg-[#DCFCE7] text-[#14532D]',
  closed: 'bg-[#DCFCE7] text-[#14532D]',
  declined: 'bg-[#EEF0F3] text-[#4A5160]',
  cancelled: 'bg-[#EEF0F3] text-[#4A5160]',
}

/** "On hold · Awaiting payment" — the state, plus the first hold reason when held. */
export function shopifyStateLabel(t: TFunction, state: ShopifyReturnStateCode, holdReasons: string[]): string {
  const label = t(`shopifyReturns.state.${state}`)
  if (state === 'on_hold' && holdReasons.length > 0) {
    const reason = t(`shopifyReturns.holdReason.${holdReasons[0]}`, { defaultValue: humanize(holdReasons[0]) })
    return t('shopifyReturns.onHoldWith', { reason })
  }
  return label
}

export function ShopifyStateChip({ state, holdReasons }: { state: ShopifyReturnStateCode; holdReasons: string[] }) {
  const { t } = useTranslation()
  return (
    <span data-testid="shopify-state" data-state={state}
      className={cn('inline-flex px-2.5 py-1 rounded-full text-[13px] font-semibold', STATE_PILL[state])}>
      {shopifyStateLabel(t, state, holdReasons)}
    </span>
  )
}

export function FromShopifyChip() {
  const { t } = useTranslation()
  return (
    <span data-testid="source-chip" data-source="shopify"
      className="inline-flex px-2 py-0.5 rounded-md bg-[#F4F4F5] text-[#3F3F46] text-[12px] font-semibold">
      {t('shopifyReturns.fromShopify')}
    </span>
  )
}

export function DuplicateChip() {
  const { t } = useTranslation()
  return (
    <span data-testid="duplicate-chip"
      className="inline-flex px-2 py-0.5 rounded-md bg-[#FBE7E5] text-[#9B241D] text-[12px] font-semibold">
      {t('shopifyReturns.duplicate')}
    </span>
  )
}

/** Shopify's return reason handle (e.g. "size-too-large", or an old enum "SIZE_TOO_LARGE") → words. */
export function reasonLabel(t: TFunction, reason: string | null | undefined): string | null {
  if (!reason) return null
  const key = reason.toLowerCase().replace(/_/g, '-')
  return t(`shopifyReturns.reasons.${key}`, { defaultValue: humanize(reason) })
}

/** What Traced already knows of the returned item(s). */
export function inTracedLabel(t: TFunction, inTraced: ShopifyInTraced): string {
  return t(`shopifyReturns.inTraced.${inTraced.state}`, {
    received: inTraced.received, units: inTraced.units, sellable: inTraced.sellable, damaged: inTraced.damaged,
  })
}

export function lineLabel(t: TFunction, l: ShopifyReturnLine): string {
  if (!l.productTitle) return t('shopifyReturns.unknownItem')
  const base = l.variantTitle ? `${l.productTitle} · ${l.variantTitle}` : l.productTitle
  return l.quantity > 1 ? `${base} × ${l.quantity}` : base
}

function humanize(s: string): string {
  const words = s.replace(/[-_]+/g, ' ').trim().toLowerCase()
  return words.charAt(0).toUpperCase() + words.slice(1)
}

function LineRow({ line, children }: { line: ShopifyReturnLine; children?: ReactNode }) {
  const { t } = useTranslation()
  return (
    <li className="flex gap-3 items-start">
      <ProductThumb src={line.imageUrl} alt={line.productTitle ?? ''} size={40} />
      <div className="min-w-0 flex-1 space-y-0.5">
        <p className="text-small font-semibold text-primary" dir="auto">
          {line.productTitle ?? t('shopifyReturns.unknownItem')}
        </p>
        {(line.variantTitle || line.sku) && (
          <p className="text-caption text-muted" dir="auto">
            {[line.variantTitle, line.sku].filter(Boolean).join(' · ')}
          </p>
        )}
        {children}
      </div>
      <span className="text-small text-muted flex-shrink-0">×{line.quantity}</span>
    </li>
  )
}

/** The order drawer's card for one Shopify return. */
export function ShopifyReturnCard({ ret }: { ret: ShopifyReturnView }) {
  const { t, i18n } = useTranslation()
  const exchange = ret.kind === 'exchange'
  const Icon = exchange ? Repeat2 : Undo2
  const date = ret.requestedAt
    ? new Date(ret.requestedAt).toLocaleDateString(i18n.language === 'ar' ? 'ar-EG-u-nu-latn' : 'en-GB', { day: 'numeric', month: 'short' })
    : null
  return (
    <section data-testid="shopify-return-card" data-return-id={ret.id}
      aria-label={t(exchange ? 'shopifyReturns.card.titleExchange' : 'shopifyReturns.card.titleReturn', { name: ret.name ?? '' })}
      className="border border-[#C7D2FE] rounded-2xl p-4 space-y-4 bg-surface">
      <div className="space-y-2">
        <div className="flex items-center gap-2 flex-wrap">
          <Icon size={18} strokeWidth={2} className="text-trace-blue flex-shrink-0" aria-hidden />
          <h3 className="text-body font-semibold text-primary">
            {t(exchange ? 'shopifyReturns.card.exchange' : 'shopifyReturns.card.return')}{' '}
            <bdi className="font-mono">{ret.name}</bdi>
          </h3>
          <FromShopifyChip />
          {ret.duplicate && <DuplicateChip />}
        </div>
        <div className="flex items-center gap-2 flex-wrap">
          <ShopifyStateChip state={ret.state} holdReasons={ret.holdReasons} />
          {date && <span className="text-caption text-muted">{t('shopifyReturns.card.requestedAt', { date })}</span>}
        </div>
        {ret.duplicate && ret.duplicateOf.length > 0 && (
          <p className="text-caption text-muted" data-testid="duplicate-of">
            {t('shopifyReturns.card.duplicateOf', { refs: ret.duplicateOf.join(', ') })}
          </p>
        )}
      </div>

      {exchange && (
        <div className="space-y-2" data-testid="going-out">
          <p className="text-caption font-bold text-muted uppercase tracking-wider">{t('shopifyReturns.card.goingOut')}</p>
          <ul className="space-y-3">
            {ret.goingOut.map((l, i) => <LineRow key={i} line={l} />)}
          </ul>
          {ret.keptAside && (
            <p className={`text-caption font-bold ${ret.keptAside.state === 'short' ? 'text-danger' : 'text-muted'}`}
               data-testid="kept-aside">
              {ret.keptAside.state === 'short'
                ? t('shopifyReturns.card.keptAsideShort', { number: ret.keptAside.number, count: ret.keptAside.shortBy })
                : ret.keptAside.state === 'ended'
                  ? t('shopifyReturns.card.keptAsideEnded', { number: ret.keptAside.number })
                  : t('shopifyReturns.card.keptAside', { number: ret.keptAside.number })}
            </p>
          )}
        </div>
      )}

      <div className="space-y-2" data-testid="coming-back">
        <p className="text-caption font-bold text-muted uppercase tracking-wider">{t('shopifyReturns.card.comingBack')}</p>
        <ul className="space-y-3">
          {ret.comingBack.map((l, i) => {
            const reason = reasonLabel(t, l.reason)
            return (
              <LineRow key={i} line={l}>
                {reason && <p className="text-caption text-primary" data-testid="return-reason">{t('shopifyReturns.card.reason', { reason })}</p>}
                {l.reasonNote && <p className="text-caption text-muted" dir="auto" data-testid="return-note">“{l.reasonNote}”</p>}
                {l.customerNote && <p className="text-caption text-muted" dir="auto" data-testid="customer-note">“{l.customerNote}”</p>}
              </LineRow>
            )
          })}
        </ul>
        <p className="text-caption font-medium text-primary bg-elevated rounded-lg px-3 py-2" data-testid="in-traced">
          {t('shopifyReturns.inTracedLabel')}: {inTracedLabel(t, ret.inTraced)}
        </p>
        {ret.restockedInShopify > 0 && (
          <p className="text-caption text-muted" data-testid="restocked-in-shopify">
            {t('shopifyReturns.card.restocked', { count: ret.restockedInShopify })}
          </p>
        )}
      </div>
      <p className="text-caption text-muted">{t('shopifyReturns.card.readOnly')}</p>
    </section>
  )
}
