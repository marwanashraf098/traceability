import { Fragment, KeyboardEvent, useCallback, useEffect, useRef, useState } from 'react'
import type { TFunction } from 'i18next'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router-dom'
import { Repeat2, Undo2, X } from 'lucide-react'
import {
  getReturnCaseCounts, getReturnCases, CaseStage, CaseTile, CaseTone, ReturnCase, ReturnCaseCounts,
} from '../api'
import { Skeleton, cn } from '../components/ui'
import { formatMoney, shortCustomerName } from './exchangesRefunds/requestFormat'
import ExchangeRefundDrawer, { ExchangeRefundDrawerRow } from './exchangesRefunds/ExchangeRefundDrawer'
import ReturnRequestDrawer from './exchangesRefunds/ReturnRequestDrawer'

/**
 * Returns & exchanges (Step 2) — ONE row per real-world return, on the Step 1 endpoints
 * (GET /returns-exchanges + /counts). Mockups: design/returns-exchanges-page E1 (All), E2 (To do),
 * E3 (Arabic). The API decides stage, next step, tone and overdue; this page only presents them.
 *
 * Tiles (hidden at 0) filter to that tile on the To do tab with a removable chip; tabs All · To do ·
 * In progress · Done; a type select and one debounced search apply to both the list and the counts.
 * The All tab groups rows under To do / In progress / Done headers (the API sorts by stage).
 * "Show more" follows the keyset cursor.
 *
 * Row click → the right drawer by target: a request → ReturnRequestDrawer; a dashboard exchange or a
 * courier return → ExchangeRefundDrawer. Any drawer change refreshes the list and the counts.
 * The alerts' deep link ?tab=requests&request=<id>[&parcel=<id>] still opens that request's drawer.
 */

export const PAGE_SIZE = 25
const SEARCH_DEBOUNCE_MS = 300

const TILES: CaseTile[] = ['toApprove', 'replacementToChoose', 'toLinkOrder', 'refundToRecord', 'bookingProblem']
const TABS: Array<CaseStage | 'all'> = ['all', 'to_do', 'in_progress', 'done']

/** Tone → pill colours, exactly as the mockups. Type tags stay neutral. */
export const TONE_PILL: Record<CaseTone, string> = {
  action: 'bg-[#FDEBD8] text-[#8A4207]',
  problem: 'bg-[#FBE7E5] text-[#9B241D]',
  moving: 'bg-[#E7EEFF] text-[#2F4FC9]',
  done_good: 'bg-[#E6F4EC] text-[#1B6B43]',
  done_closed: 'bg-[#EEF0F3] text-[#4A5160]',
}

const GROUP_ROW: Record<CaseStage, string> = {
  to_do: 'bg-[#FBF7F2] text-[#7A4308]',
  in_progress: 'bg-[#F4F7FF] text-[#2440B8]',
  done: 'bg-[#F2F8F4] text-[#1B6B43]',
}

type DrawerTarget =
  | { kind: 'request'; id: string; parcel: string | null }
  | { kind: 'other'; row: ExchangeRefundDrawerRow }

export default function ExchangesRefunds() {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const [searchParams] = useSearchParams()

  const [tab, setTab] = useState<CaseStage | 'all'>('all')
  const [tile, setTile] = useState<CaseTile | null>(null)
  const [type, setType] = useState<'' | 'refund' | 'exchange'>('')
  const [qInput, setQInput] = useState('')
  const [q, setQ] = useState('')

  const [items, setItems] = useState<ReturnCase[]>([])
  const [cursor, setCursor] = useState<string | null>(null)
  const [counts, setCounts] = useState<ReturnCaseCounts | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadingMore, setLoadingMore] = useState(false)
  const [error, setError] = useState(false)

  // The alerts' deep link (?tab=requests&request=<id>[&parcel=<id>]) opens that request's drawer.
  const linked = searchParams.get('tab') === 'requests' ? searchParams.get('request') : null
  const [drawer, setDrawer] = useState<DrawerTarget | null>(
    linked ? { kind: 'request', id: linked, parcel: searchParams.get('parcel') } : null)

  useEffect(() => {
    const h = setTimeout(() => setQ(qInput.trim()), SEARCH_DEBOUNCE_MS)
    return () => clearTimeout(h)
  }, [qInput])

  const loadSeq = useRef(0)
  const load = useCallback(async () => {
    const seq = ++loadSeq.current
    setLoading(true)
    setError(false)
    const filter = { stage: tab, type: type || undefined, tile: tile ?? undefined, q: q || undefined }
    try {
      const [page, c] = await Promise.all([
        getReturnCases(filter, null, PAGE_SIZE),
        getReturnCaseCounts(type || undefined, q || undefined),
      ])
      if (seq !== loadSeq.current) return
      setItems(Array.isArray(page?.items) ? page.items : [])
      setCursor(page?.nextCursor ?? null)
      setCounts(c ?? null)
    } catch {
      if (seq !== loadSeq.current) return
      setError(true)
    } finally {
      if (seq === loadSeq.current) setLoading(false)
    }
  }, [tab, type, tile, q])

  useEffect(() => { load() }, [load])

  async function showMore() {
    if (!cursor || loadingMore) return
    setLoadingMore(true)
    try {
      const page = await getReturnCases(
        { stage: tab, type: type || undefined, tile: tile ?? undefined, q: q || undefined }, cursor, PAGE_SIZE)
      setItems(prev => [...prev, ...(page.items ?? [])])
      setCursor(page.nextCursor ?? null)
    } catch {
      setError(true)
    } finally {
      setLoadingMore(false)
    }
  }

  function chooseTab(next: CaseStage | 'all') {
    setTab(next)
    if (next !== 'to_do') setTile(null)
  }

  function chooseTile(next: CaseTile) {
    setTile(next)
    setTab('to_do')
  }

  function open(c: ReturnCase) {
    if (c.target.requestId && c.caseType === 'A') {
      setDrawer({ kind: 'request', id: c.target.requestId, parcel: null })
      return
    }
    setDrawer({
      kind: 'other',
      row: {
        kind: c.caseType === 'B' ? 'exchange' : 'refund',
        sourceId: (c.caseType === 'B' ? c.target.exchangeId : c.target.shipmentId) ?? c.id,
        trackingNumber: c.reference,
        orderNumber: c.orderNumber,
        customerName: c.customerName,
        legStatus: c.legStatus,
        inspectionState: c.reason?.inspectionState ?? null,
      },
    })
  }

  const stages = counts?.stages
  const stageCount = (s: CaseStage | 'all') => (stages ? stages[s] ?? 0 : 0)
  const filtered = !!(tile || type || q)

  return (
    <>
      <div className="space-y-5" data-testid="returns-exchanges-page">
        <div className="space-y-1">
          <h1 className="text-h1 text-primary">{t('returnsExchanges.title')}</h1>
          <p className="text-body text-muted">{t('returnsExchanges.subtitle')}</p>
        </div>

        <Tiles counts={counts} active={tile} onChoose={chooseTile} />

        <div className="flex flex-wrap items-end gap-4 border-b border-[#E3E6EB]">
          <nav aria-label={t('returnsExchanges.stageLabel')} className="flex gap-6 flex-grow" role="tablist">
            {TABS.map(s => {
              const active = tab === s
              return (
                <button
                  key={s}
                  type="button"
                  role="tab"
                  aria-selected={active}
                  data-testid={`tab-${s}`}
                  onClick={() => chooseTab(s)}
                  className={cn('flex items-center gap-2 pt-2.5 pb-3 text-[16px] border-b-2 -mb-px',
                    active ? 'border-[#3656E0] text-[#141821] font-semibold' : 'border-transparent text-[#5B6270]')}
                >
                  {t(`returnsExchanges.tabs.${s}`)}
                  <span
                    data-testid={`tab-count-${s}`}
                    className={cn('px-2 py-px rounded-full text-[13px]',
                      s === 'to_do' ? 'bg-[#FDEBD8] text-[#9A4A08]' : 'bg-[#EEF0F3] text-[#141821]')}
                  >
                    {stageCount(s).toLocaleString(lang)}
                  </span>
                </button>
              )
            })}
          </nav>
          <div className="flex gap-2.5 pb-2">
            <label htmlFor="rx-type" className="sr-only">{t('returnsExchanges.type.label')}</label>
            <select
              id="rx-type"
              className="h-[38px] px-2.5 rounded-lg border border-[#CDD3DC] bg-white text-[14px]"
              value={type}
              onChange={e => setType(e.target.value as '' | 'refund' | 'exchange')}
            >
              <option value="">{t('returnsExchanges.type.all')}</option>
              <option value="refund">{t('returnsExchanges.type.refund')}</option>
              <option value="exchange">{t('returnsExchanges.type.exchange')}</option>
            </select>
            <label htmlFor="rx-search" className="sr-only">{t('returnsExchanges.search.label')}</label>
            <input
              id="rx-search"
              type="search"
              dir="auto"
              className="w-[230px] h-[38px] px-3 rounded-lg border border-[#CDD3DC] bg-white text-[14px]"
              placeholder={t('returnsExchanges.search.placeholder')}
              value={qInput}
              onChange={e => setQInput(e.target.value)}
            />
          </div>
        </div>

        {tile && (
          <div className="flex items-center gap-2" data-testid="tile-chip">
            <span className="inline-flex items-center gap-1.5 ps-3 pe-1 py-1 rounded-full bg-[#FDEBD8] text-[#8A4207] text-[13px] font-semibold">
              {t(`returnsExchanges.tiles.${tile}.label`)}
              <button
                type="button"
                aria-label={t('returnsExchanges.removeFilter')}
                onClick={() => setTile(null)}
                className="w-5 h-5 rounded-full flex items-center justify-center hover:bg-[#F5D3B0]"
              >
                <X size={13} strokeWidth={2.4} />
              </button>
            </span>
          </div>
        )}

        {error ? (
          <div className="card p-10 flex flex-col items-center gap-3 text-center" data-testid="load-error">
            <p className="text-body font-semibold text-primary">{t('returnsExchanges.errorTitle')}</p>
            <button className="btn-outline" onClick={load}>{t('returnsExchanges.retry')}</button>
          </div>
        ) : (
          <section className="bg-white border border-[#E3E6EB] rounded-[14px] overflow-hidden">
            <table className="w-full text-start border-collapse table-fixed" data-testid="cases-table">
              {/* Column widths as the mockup's grid: 180 · 120 · 120 · 1fr · 250 · 84 (+ cell padding). */}
              <colgroup>
                <col className="w-[196px]" /><col className="w-[118px]" /><col className="w-[104px]" />
                <col /><col className="w-[236px]" /><col className="w-[100px]" />
              </colgroup>
              <thead>
                <tr className="text-[12px] font-semibold tracking-[0.06em] uppercase text-[#5B6270] border-b border-[#EDEFF3]">
                  {(['return', 'customer', 'order', 'items', 'next', 'updated'] as const).map(col => (
                    <th key={col} className="text-start font-semibold px-4 py-3">{t(`returnsExchanges.columns.${col}`)}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {loading ? (
                  [0, 1, 2].map(i => (
                    <tr key={i}><td colSpan={6} className="px-4 py-3"><Skeleton className="h-8 w-full" /></td></tr>
                  ))
                ) : items.length === 0 ? (
                  <tr>
                    <td colSpan={6} className="px-5 py-12 text-center text-body text-muted" data-testid="cases-empty">
                      {t(filtered ? 'returnsExchanges.empty.filtered' : `returnsExchanges.empty.${tab}`)}
                    </td>
                  </tr>
                ) : (
                  items.map((c, i) => {
                    const header = tab === 'all' && (i === 0 || items[i - 1].stage !== c.stage)
                    const shownInStage = header ? items.filter(x => x.stage === c.stage).length : 0
                    const total = stageCount(c.stage)
                    return (
                      <Fragment key={`${c.caseType}:${c.id}`}>
                        {header && (
                          <tr data-testid={`group-${c.stage}`}>
                            <td colSpan={6} className={cn('px-4 py-2.5 text-[13px] font-semibold border-b border-[#EDEFF3]', GROUP_ROW[c.stage])}>
                              {t('returnsExchanges.group', { stage: t(`returnsExchanges.tabs.${c.stage}`), count: total })}
                              {shownInStage < total && (
                                <span className="font-normal text-[#5B6270]"> {t('returnsExchanges.showing', { count: shownInStage })}</span>
                              )}
                            </td>
                          </tr>
                        )}
                        <CaseRow c={c} onOpen={() => open(c)} />
                      </Fragment>
                    )
                  })
                )}
              </tbody>
            </table>
            {!loading && cursor && (
              <div className="px-4 py-3 border-t border-[#EDEFF3] flex justify-center">
                <button type="button" className="btn-outline" disabled={loadingMore} onClick={showMore}>
                  {t('returnsExchanges.showMore')}
                </button>
              </div>
            )}
          </section>
        )}
      </div>

      <ReturnRequestDrawer
        requestId={drawer?.kind === 'request' ? drawer.id : null}
        initialParcelId={drawer?.kind === 'request' ? drawer.parcel : null}
        onClose={() => setDrawer(null)}
        onChanged={load}
      />
      <ExchangeRefundDrawer
        row={drawer?.kind === 'other' ? drawer.row : null}
        onClose={() => setDrawer(null)}
        onChanged={load}
      />
    </>
  )
}

function Tiles({ counts, active, onChoose }: {
  counts: ReturnCaseCounts | null; active: CaseTile | null; onChoose: (t: CaseTile) => void
}) {
  const { t, i18n } = useTranslation()
  const shown = TILES.filter(k => (counts?.tiles?.[k] ?? 0) > 0)
  if (shown.length === 0) return null
  return (
    <section aria-label={t('returnsExchanges.tilesLabel')} className="grid grid-cols-2 md:grid-cols-5 gap-3" data-testid="tiles">
      {shown.map(k => {
        const red = k === 'bookingProblem'
        return (
          <button
            key={k}
            type="button"
            data-testid={`tile-${k}`}
            aria-pressed={active === k}
            onClick={() => onChoose(k)}
            className={cn('px-4 py-3.5 rounded-xl border flex flex-col items-start gap-1 text-start',
              red ? 'border-[#EBC3BF] bg-[#FDF3F2]' : 'border-[#F0D5B6] bg-white',
              active === k && 'ring-2 ring-[#3656E0]')}
          >
            <span className={cn('text-[26px] font-bold leading-none', red ? 'text-[#B12D25]' : 'text-[#9A4A08]')}>
              {(counts?.tiles?.[k] ?? 0).toLocaleString(i18n.language)}
            </span>
            <span className="text-[14px] font-semibold text-[#141821]">{t(`returnsExchanges.tiles.${k}.label`)}</span>
            <span className="text-[12px] text-[#5B6270]">{t(`returnsExchanges.tiles.${k}.sub`)}</span>
          </button>
        )
      })}
    </section>
  )
}

function CaseRow({ c, onOpen }: { c: ReturnCase; onOpen: () => void }) {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const exchange = c.kind === 'exchange'
  function onKey(e: KeyboardEvent<HTMLTableRowElement>) {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onOpen() }
  }
  const overdue = c.overdueDays != null
  const reason = reasonLine(t, c)
  return (
    <tr
      tabIndex={0}
      onClick={onOpen}
      onKeyDown={onKey}
      data-testid="case-row"
      data-case-id={c.id}
      className="border-b border-[#EDEFF3] last:border-b-0 cursor-pointer hover:bg-[#FAFBFC] focus:outline-none focus-visible:bg-[#F4F7FF]"
    >
      <td className="px-4 py-3 align-middle">
        <div className="flex flex-col gap-1">
          <span className="flex items-center gap-1.5 flex-wrap">
            <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-white border border-[#D5DAE1] text-[#3A404C] text-[12px] font-semibold" data-testid="type-tag">
              {exchange ? <Repeat2 size={12} strokeWidth={2.4} aria-hidden /> : <Undo2 size={12} strokeWidth={2.4} aria-hidden />}
              {t(`returnsExchanges.kind.${c.kind}`)}
            </span>
            <bdi className="font-mono text-[14px] font-semibold text-[#141821]">{c.reference}</bdi>
          </span>
          <span className="text-[12px] text-[#5B6270]">{t(`returnsExchanges.source.${c.source}`)}</span>
        </div>
      </td>
      <td className="px-4 py-3 text-[15px] font-semibold text-[#141821]" data-testid="case-customer">
        <bdi>{c.customerName ? shortCustomerName(c.customerName) : '—'}</bdi>
      </td>
      <td className="px-4 py-3" data-testid="case-order">
        {c.orderNumber
          ? <bdi className="text-[15px] text-[#141821]">{c.orderNumber}</bdi>
          : <span className="text-[14px] text-[#5B6270] whitespace-nowrap">{t('returnsExchanges.notFound')}</span>}
      </td>
      <td className="px-4 py-3 text-[14px] text-[#2A303B]" dir="auto" data-testid="case-items">
        {lang === 'ar' ? c.itemsSummaryAr : c.itemsSummary}
      </td>
      <td className="px-4 py-3">
        <div className="flex flex-col gap-1 items-start">
          <span className={cn('px-2.5 py-1 rounded-full text-[13px] font-semibold', TONE_PILL[c.tone])}
            data-testid="case-pill" data-tone={c.tone}>
            {nextStepLabel(t, c)}
          </span>
          {reason && <span className="text-[12px] text-[#5B6270]" data-testid="case-reason"><bdi>{reason}</bdi></span>}
        </div>
      </td>
      <td className={cn('px-4 py-3 text-[13px] whitespace-nowrap', overdue ? 'text-[#B12D25] font-bold' : 'text-[#5B6270]')}
        data-testid="case-updated" data-overdue={overdue || undefined}>
        {overdue ? t('returnsExchanges.overdueAge', { count: c.overdueDays! }) : relativeTime(t, lang, c.updatedAt)}
      </td>
    </tr>
  )
}

/** The pill: the next step's label (a few carry a value — refunded amount, closed reason). */
export function nextStepLabel(t: TFunction, c: ReturnCase): string {
  const k = `returnsExchanges.next.${c.nextStep}`
  if (c.nextStep === 'refunded' && c.reason?.refundTotal && Number(c.reason.refundTotal) > 0) {
    return t(`${k}.labelAmount`, { amount: formatMoney(c.reason.refundTotal, c.reason.currency ?? 'EGP') })
  }
  if (c.nextStep === 'closed' && c.reason?.closeReason) {
    return t(`${k}.labelReason`, {
      reason: t(`exchangesRefunds.requests.closeReasonsShort.${c.reason.closeReason}`, { defaultValue: c.reason.closeReason }),
    })
  }
  if (c.nextStep === 'on_the_way') {
    return t(`${k}.label_${c.caseType === 'C' ? 'leg' : c.kind}`)
  }
  return t(`${k}.label`, { defaultValue: c.nextStep.replace(/_/g, ' ') })
}

/** The one short reason line under the pill; null when there is nothing useful to add. */
export function reasonLine(t: TFunction, c: ReturnCase): string | null {
  const k = `returnsExchanges.next.${c.nextStep}`
  const r = c.reason ?? ({} as ReturnCase['reason'])
  switch (c.nextStep) {
    case 'approve':
      return c.kind === 'exchange' ? t(`${k}.reasonExchange`) : t(`${k}.reason`, { count: r.itemsCount ?? 1 })
    case 'choose_replacement':
      return c.caseType === 'A' ? t(`${k}.reasonSoldOut`) : t(`${k}.reasonBosta`)
    case 'link_order':
      return c.status === 'needs_confirmation' ? t(`${k}.reasonSeveral`) : t(`${k}.reasonNone`)
    case 'link_request':
      return t(`${k}.reason`, { count: r.candidateCount ?? 2, refs: r.candidateReferences ?? '' })
    case 'record_refund':
      return c.overdueDays != null ? t(`${k}.reasonOverdue`) : t(`${k}.reason`)
    case 'booking_problem':
      return t(`${k}.reason_${r.bookingStatus ?? 'failed'}`, { defaultValue: t(`${k}.reason_failed`) })
    case 'inspect':
      return c.caseType === 'A' ? t(`${k}.reason`, { count: r.arrivedCount ?? 1 }) : t(`${k}.reasonLeg`)
    case 'on_the_way':
      // A request's Bosta AWB (a dashboard exchange / courier return already shows it as its reference).
      return r.trackingNumber && c.reference !== r.trackingNumber
        ? t(`${k}.reasonTracking`, { tracking: r.trackingNumber }) : null
    default: {
      const key = `${k}.reason`
      const text = t(key, { defaultValue: '' })
      return text && text !== key ? text : null
    }
  }
}

/** "Just now" · "5m ago" · "2h ago" · "Yesterday" · "3 days ago" · "21 Sep". */
export function relativeTime(t: TFunction, lang: string, iso: string, now: Date = new Date()): string {
  const d = new Date(iso)
  const mins = Math.floor((now.getTime() - d.getTime()) / 60000)
  if (mins < 1) return t('returnsExchanges.time.justNow')
  if (mins < 60) return t('returnsExchanges.time.minutes', { count: mins })
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  if (d.getTime() >= startOfToday) return t('returnsExchanges.time.hours', { count: Math.floor(mins / 60) })
  const days = Math.floor((startOfToday - new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()) / 86400000)
  if (days <= 1) return t('returnsExchanges.time.yesterday')
  if (days < 7) return t('returnsExchanges.time.days', { count: days })
  return d.toLocaleDateString(lang === 'ar' ? 'ar-EG-u-nu-latn' : 'en-GB', { day: 'numeric', month: 'short' })
}
