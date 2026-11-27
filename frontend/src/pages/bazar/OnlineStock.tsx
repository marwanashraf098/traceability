import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Check, CloudOff, Globe } from 'lucide-react'
import { BazarError, getBazarOnlineStockOptions, type OnlineStock } from '../../bazar/api'
import { cn } from '../../components/ui'
import { Tag } from './r1/parts'

/**
 * Bazar B8 — online stock per bazar. 'remove' (default): pieces come off the online stock while they're at the bazar,
 * leftovers go back when it ends. 'leave': Shopify isn't touched when pieces go to the bazar; pieces sold there still
 * come off the online stock (Main reconcile). The server decides and locks it (BazarOnlineStock); these only show it.
 */

const ORDER: OnlineStock[] = ['remove', 'leave']

/**
 * U1d — Keep showing online is offered only while Main reconcile writes for the store (the server's keep_available:
 * the bazar detail's, else GET /bazar-events/online-stock). Unknown (an older server, a failed read) = available — the
 * server refuses a Keep it can't honour (409 BAZAR_KEEP_UNAVAILABLE) either way.
 */
export function useKeepAvailable(fromDetail?: boolean): boolean {
  const [fetched, setFetched] = useState<boolean | null>(null)
  useEffect(() => {
    if (fromDetail !== undefined) return
    let live = true
    getBazarOnlineStockOptions()
      .then(o => { if (live && typeof o?.keep_available === 'boolean') setFetched(o.keep_available) })
      .catch(() => {})
    return () => { live = false }
  }, [fromDetail])
  return fromDetail ?? fetched ?? true
}

/** U1d — said under the choice while Keep isn't available. */
function KeepUnavailableNote() {
  const { t } = useTranslation()
  return <p className="text-small text-muted col-span-full" data-testid="bazar-keep-unavailable">{t('bazar.u1d.keepUnavailable')}</p>
}

/** The two cards (New bazar form section 5, and Get ready's Change dialog). A radio group. */
export function OnlineStockCards({ value, onChange, name = 'bazar-online-stock', keepAvailable = true }: {
  value: OnlineStock; onChange: (m: OnlineStock) => void; name?: string; keepAvailable?: boolean
}) {
  const { t } = useTranslation()
  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 gap-3" role="radiogroup" aria-label={t('bazar.online.title')}
         data-testid="bazar-online-stock">
      {ORDER.map(m => {
        const on = value === m
        const off = m === 'leave' && !keepAvailable
        const Ico = m === 'remove' ? CloudOff : Globe
        return (
          <label key={m} data-testid={`online-stock-${m}`} aria-disabled={off || undefined}
            className={cn('relative flex flex-col gap-1 p-3.5 rounded-xl border min-w-0',
              off ? 'cursor-not-allowed opacity-50' : 'cursor-pointer',
              'has-[:focus-visible]:outline has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-trace-blue',
              on ? 'border-trace-blue border-[1.5px] bg-trace-blue/[0.04]' : 'border-line bg-surface')}>
            <input type="radio" name={name} value={m} className="sr-only" checked={on} disabled={off}
                   onChange={() => { if (!off) onChange(m) }} />
            <span className="flex items-start justify-between">
              <span aria-hidden className="w-9 h-9 rounded-lg bg-charcoal flex items-center justify-center text-primary"><Ico size={18} /></span>
              <span aria-hidden className={cn('w-[22px] h-[22px] rounded border flex items-center justify-center shrink-0',
                on ? 'bg-trace-blue border-trace-blue text-white' : 'bg-surface border-grey-100')}>
                {on && <Check size={14} strokeWidth={3} />}
              </span>
            </span>
            <span className="text-body font-semibold text-primary mt-2 flex items-center gap-2 flex-wrap">
              {t(`bazar.online.${m}.title`)}
              {m === 'remove' && <span className="px-1.5 py-0.5 rounded bg-charcoal text-caption font-semibold text-muted">{t('bazar.online.default')}</span>}
            </span>
            <span className="text-small text-muted">{t(`bazar.online.${m}.desc`)}</span>
          </label>
        )
      })}
      {!keepAvailable && <KeepUnavailableNote />}
    </div>
  )
}

/** The phone pair: "Remove online · Default" / "Keep online". A radio group of two buttons. */
export function OnlineStockPhonePair({ value, onChange, keepAvailable = true }: {
  value: OnlineStock; onChange: (m: OnlineStock) => void; keepAvailable?: boolean
}) {
  const { t } = useTranslation()
  return (
    <div className="grid grid-cols-2 gap-2.5" role="radiogroup" aria-label={t('bazar.online.title')} data-testid="bazar-online-stock">
      {ORDER.map(m => {
        const off = m === 'leave' && !keepAvailable
        return (
          <button key={m} type="button" role="radio" aria-checked={value === m} disabled={off}
            onClick={() => { if (!off) onChange(m) }}
            data-testid={`online-stock-${m}`}
            className={cn('h-12 px-3 rounded-xl border text-body font-semibold', off && 'opacity-50 cursor-not-allowed',
              value === m ? 'border-trace-blue border-[1.5px] bg-trace-blue/[0.04] text-trace-blue' : 'border-line bg-surface text-primary')}>
            {t(`bazar.online.${m}.phone`)}
          </button>
        )
      })}
      {!keepAvailable && <KeepUnavailableNote />}
    </div>
  )
}

/** Small square tag: "Off online" / "Shown online". */
export function OnlineStockTag({ mode, dark }: { mode: OnlineStock | null | undefined; dark?: boolean }) {
  const { t } = useTranslation()
  if (mode !== 'remove' && mode !== 'leave') return null
  return (
    <Tag tone={mode === 'leave' ? 'info' : 'neutral'} dark={dark} testId="bazar-online-tag">
      {t(`bazar.online.${mode}.tag`)}
    </Tag>
  )
}

/** U1b — the choice belongs to the bazar's location: every bazar there shares it (said under the cards / the row). */
export function OnlineStockWholeLocation({ location }: { location?: string | null }) {
  const { t } = useTranslation()
  return (
    <p className="text-small text-muted" data-testid="bazar-online-whole-location">
      {location ? t('bazar.u1b.online.wholeLocationNamed', { location }) : t('bazar.u1b.online.wholeLocation')}
    </p>
  )
}

/** U1b — the bazar in the way of an online-stock change (BAZAR_ONLINE_STOCK_CONFLICT details), as a link; else null. */
export function blockingBazar(e: unknown): { id: string; name: string } | null {
  if (!(e instanceof BazarError) || e.code !== 'BAZAR_ONLINE_STOCK_CONFLICT' || !e.details) return null
  const id = e.details.blocking_event_id, name = e.details.blocking_event_name
  return typeof id === 'string' && typeof name === 'string' ? { id, name } : null
}

export function BlockingBazarLink({ bazar }: { bazar: { id: string; name: string } | null }) {
  const { t } = useTranslation()
  if (!bazar) return null
  return (
    <Link to={`/bazar/${bazar.id}`} className="text-body font-semibold text-trace-blue underline" data-testid="bazar-blocking-link">
      {t('bazar.u1b.online.openBlocking', { name: bazar.name })}
    </Link>
  )
}
