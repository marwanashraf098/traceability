import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Check, Loader2, PackageCheck, TriangleAlert } from 'lucide-react'
import {
  BazarError, closeBazarTransfer, endBazarEvent, markBazarLost, scanBackOnce,
  type BazarLeftoverPiece, type BazarLeftovers, type BazarScanBackResult,
} from '../../bazar/api'
import { shortDate } from '../../bazar/format'
import { useRetryingCommand } from '../../bazar/useRetryingCommand'
import { CodeEntry, CommandStatus, ErrorCard } from './phone/ReturnParts'
import { cn } from '../../components/ui'

// Bazar POS B5 — End event (mockup page 1, step 9) and the leftovers coming back on the event's linked transfer,
// shared by the phone screen and the desktop event page. Scans go one at a time (useRetryingCommand: one request in
// flight; a weak signal retries with the SAME client_request_id — never "back" before the server says so).

const btn = 'h-[52px] w-full rounded-xl text-body-lg font-semibold flex items-center justify-center gap-2 disabled:opacity-60'
const primary = 'bg-trace-blue hover:bg-trace-blue-hover text-white'
const danger = 'bg-critical hover:bg-critical/90 text-white'
const secondary = 'border border-line bg-surface text-primary'

/** "End the event?" — what ending does, the days that still need closing (BAZAR_DAYS_NOT_CLOSED), the button. */
export function EndEventPanel({ eventId, name, onEnded, closeDaysTo }: {
  eventId: string
  name: string
  onEnded: (l: BazarLeftovers) => void
  /** Where "Close the days" goes: the close screen (phone) or the event page (desktop). */
  closeDaysTo?: string
}) {
  const { t, i18n } = useTranslation()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<BazarError | null>(null)
  const days = error?.code === 'BAZAR_DAYS_NOT_CLOSED'
    ? (error.details?.days as { business_day: string; day_number: number; status: string }[] | undefined) ?? [] : []

  async function end() {
    setBusy(true)
    setError(null)
    try {
      onEnded(await endBazarEvent(eventId))
    } catch (e) {
      setError(e instanceof BazarError ? e : new BazarError(0, { code: 'NETWORK' }))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex flex-col gap-3.5" data-testid="end-event-panel">
      <h2 className="text-h4 text-primary">{t('bazar.end.confirmTitle', { name })}</h2>
      <ul className="flex flex-col gap-2 text-body text-primary list-disc ps-5">
        <li>{t('bazar.end.what1')}</li>
        <li>{t('bazar.end.what2')}</li>
        <li>{t('bazar.end.what3')}</li>
      </ul>
      {days.length > 0 ? (
        <section role="alert" className="rounded-2xl bg-warning/10 border border-warning/30 px-4 py-3 flex flex-col gap-2"
                 data-testid="end-days-not-closed">
          <span className="font-semibold text-warning-text">{t('bazar.end.daysTitle')}</span>
          <span className="text-small text-primary">{t('bazar.end.daysBody')}</span>
          <ul className="flex flex-col gap-1">
            {days.map(d => (
              <li key={d.business_day} className="flex justify-between gap-2 text-small">
                <span className="font-semibold text-primary">
                  {t('bazar.close.dayLabel', { n: d.day_number, date: shortDate(d.business_day, i18n.language) })}
                </span>
                <span className="text-muted">{t(`bazar.close.status.${d.status}`, { amount: '' })}</span>
              </li>
            ))}
          </ul>
          {closeDaysTo && (
            <Link to={closeDaysTo} className="self-start min-h-[44px] flex items-center font-semibold text-trace-blue"
                  data-testid="end-close-days">{t('bazar.end.closeDays')}</Link>
          )}
        </section>
      ) : error && <ErrorCard error={error} />}
      <button type="button" className={cn(btn, danger)} onClick={end} disabled={busy} data-testid="end-confirm">
        {busy && <Loader2 size={16} className="animate-spin" aria-hidden />}
        {busy ? t('bazar.end.ending') : t('bazar.end.button')}
      </button>
    </div>
  )
}

/** The four counters + progress bar + the equation "Sent N = sold + back + damaged + lost". */
export function LeftoverCounts({ l }: { l: BazarLeftovers }) {
  const { t } = useTranslation()
  const c = l.counts
  const pct = (n: number) => (c.sent ? `${(n / c.sent) * 100}%` : '0%')
  const tiles: { k: 'sold' | 'back' | 'damaged' | 'lost'; n: number; tone: string; bar: string }[] = [
    { k: 'sold', n: c.sold, tone: 'text-success-text', bar: 'bg-[#16A34A]' },
    { k: 'back', n: c.back, tone: 'text-trace-blue', bar: 'bg-trace-blue' },
    { k: 'damaged', n: c.damaged, tone: 'text-warning-text', bar: 'bg-warning' },
    { k: 'lost', n: c.lost, tone: 'text-critical-text', bar: 'bg-critical' },
  ]
  return (
    <section className="flex flex-col gap-2.5" data-testid="leftover-counts">
      <div className="grid grid-cols-4 gap-2">
        {tiles.map(x => (
          <div key={x.k} className="rounded-xl border border-line bg-surface px-2 py-2.5 flex flex-col items-center"
               data-testid={`count-${x.k}`}>
            <span className={cn('text-h3 font-bold tabular-nums', x.tone)}>{x.n}</span>
            <span className="text-small text-muted">{t(`bazar.end.count.${x.k}`)}</span>
          </div>
        ))}
      </div>
      <div className="flex h-3 rounded-full overflow-hidden bg-charcoal" aria-hidden>
        {tiles.map(x => <div key={x.k} className={x.bar} style={{ width: pct(x.n) }} />)}
      </div>
      <p className="text-small text-primary tabular-nums" data-testid="leftover-equation" dir="auto">
        {t('bazar.end.equation', { sent: c.sent, sold: c.sold, back: c.back, damaged: c.damaged, lost: c.lost })}
      </p>
      {l.balanced
        ? <p className="text-small font-semibold text-success-text flex items-center gap-1.5"><Check size={16} aria-hidden />{t('bazar.end.balanced')}</p>
        : <p className="text-small font-semibold text-warning-text" data-testid="leftover-still-out">{t('bazar.end.stillOut', { count: c.outstanding })}</p>}
    </section>
  )
}

function PieceLine({ p }: { p: BazarLeftoverPiece }) {
  return (
    <span className="flex flex-col min-w-0">
      <span className="font-semibold text-primary truncate">{p.variant_title ? `${p.product_title} · ${p.variant_title}` : p.product_title}</span>
      <span className="text-small text-muted font-mono" dir="ltr">{p.piece_code ?? p.sku ?? ''}</span>
    </span>
  )
}

/**
 * The leftovers after the event ended: counters, scan back (good → back in stock; damaged → home, set aside),
 * damaged still to bring home, the pieces not scanned back (Mark lost), and Close the transfer.
 */
export function LeftoversPanel({ eventId, initial, onClosed, noCamera }: {
  eventId: string
  initial: BazarLeftovers
  onClosed: (l: BazarLeftovers) => void
  /** Desktop: the scan field only (a HID scanner), no camera. */
  noCamera?: boolean
}) {
  const { t } = useTranslation()
  const [l, setL] = useState<BazarLeftovers>(initial)
  const cmd = useRetryingCommand<BazarScanBackResult>()
  const [last, setLast] = useState<BazarScanBackResult | null>(null)
  const [picked, setPicked] = useState<Set<string>>(new Set())
  const [confirmLost, setConfirmLost] = useState(false)
  const [busy, setBusy] = useState<'lost' | 'close' | null>(null)
  const [error, setError] = useState<BazarError | null>(null)

  useEffect(() => { setL(initial) }, [initial])
  useEffect(() => {
    if (cmd.state.kind === 'done') { setLast(cmd.state.result); setL(cmd.state.result.leftovers) }
  }, [cmd.state])
  // A piece no longer out drops out of the selection.
  useEffect(() => {
    setPicked(cur => new Set([...cur].filter(id => l.outstanding.some(p => p.piece_id === id))))
  }, [l.outstanding])

  const onCode = useCallback((code: string) => {
    if (cmd.busy) return
    setLast(null)
    cmd.run((requestId, timeoutMs) => scanBackOnce(eventId, code, requestId, timeoutMs))
  }, [cmd, eventId])

  const allPicked = l.outstanding.length > 0 && picked.size === l.outstanding.length
  const pickedList = useMemo(() => l.outstanding.filter(p => picked.has(p.piece_id)).map(p => p.piece_id), [l.outstanding, picked])

  async function act(kind: 'lost' | 'close') {
    setBusy(kind)
    setError(null)
    try {
      if (kind === 'lost') {
        setL(await markBazarLost(eventId, pickedList))
        setConfirmLost(false)
      } else {
        const closed = await closeBazarTransfer(eventId)
        setL(closed)
        onClosed(closed)
      }
    } catch (e) {
      setError(e instanceof BazarError ? e : new BazarError(0, { code: 'NETWORK' }))
    } finally {
      setBusy(null)
    }
  }

  const closed = l.transfer_status === 'closed'

  return (
    <div className="flex flex-col gap-4" data-testid="leftovers-panel">
      <LeftoverCounts l={l} />

      {(!closed || l.counts.damaged_at_bazar > 0) && (
        <>
          <CodeEntry onCode={onCode} disabled={cmd.busy} hint={t('bazar.end.scanHint')} noCamera={noCamera} />
          {cmd.state.kind !== 'done' && <CommandStatus state={cmd.state} />}
          {last && (
            <section role="status" data-testid={`scan-back-${last.result}`}
              className={cn('rounded-2xl px-4 py-3 flex items-center gap-3',
                last.result === 'back_good' ? 'bg-[#16A34A] text-white' : 'bg-warning/15 text-warning-text border border-warning/30')}>
              {last.result === 'back_good' ? <PackageCheck size={22} aria-hidden /> : <TriangleAlert size={22} aria-hidden />}
              <span className="flex flex-col min-w-0">
                <span className="font-bold">{last.result === 'back_good' ? t('bazar.end.backGood') : t('bazar.end.backDamaged')}
                  {last.replayed && <span className="font-normal"> · {t('bazar.end.replayed')}</span>}</span>
                <span className="text-small truncate">
                  {last.piece.variant_title ? `${last.piece.product_title} · ${last.piece.variant_title}` : last.piece.product_title}
                </span>
              </span>
            </section>
          )}
        </>
      )}

      {l.damaged_to_bring_home.length > 0 && (
        <section className="flex flex-col gap-2" data-testid="leftovers-damaged">
          <h3 className="font-semibold text-primary">{t('bazar.end.damagedTitle')}</h3>
          <p className="text-small text-muted">{t('bazar.end.damagedBody')}</p>
          <ul className="rounded-xl border border-line bg-surface divide-y divide-line">
            {l.damaged_to_bring_home.map(p => <li key={p.piece_id} className="px-3 py-2.5"><PieceLine p={p} /></li>)}
          </ul>
        </section>
      )}

      {l.outstanding.length > 0 && !closed && (
        <section className="flex flex-col gap-2" data-testid="leftovers-out">
          <div className="flex items-center justify-between gap-2">
            <h3 className="font-semibold text-primary">{t('bazar.end.outTitle')}</h3>
            <button type="button" className="min-h-[44px] px-1 text-small font-semibold text-trace-blue"
              onClick={() => setPicked(allPicked ? new Set() : new Set(l.outstanding.map(p => p.piece_id)))}>
              {t('bazar.end.selectAll')}
            </button>
          </div>
          <p className="text-small text-muted">{t('bazar.end.outBody')}</p>
          <ul className="rounded-xl border border-line bg-surface divide-y divide-line">
            {l.outstanding.map(p => (
              <li key={p.piece_id}>
                <label className="flex items-center gap-3 px-3 py-2.5 cursor-pointer min-h-[52px]">
                  <input type="checkbox" className="w-5 h-5 accent-trace-blue" checked={picked.has(p.piece_id)}
                    onChange={e => setPicked(cur => {
                      const n = new Set(cur)
                      if (e.target.checked) n.add(p.piece_id); else n.delete(p.piece_id)
                      return n
                    })} data-testid={`out-${p.piece_id}`} />
                  <PieceLine p={p} />
                </label>
              </li>
            ))}
          </ul>
          {confirmLost ? (
            <section role="alertdialog" className="rounded-2xl border border-critical/40 bg-critical/10 px-4 py-3 flex flex-col gap-2.5"
                     data-testid="mark-lost-confirm">
              <span className="text-body text-primary">{t('bazar.end.markLostConfirm', { count: pickedList.length })}</span>
              <div className="grid grid-cols-2 gap-2">
                <button type="button" className={cn(btn, secondary, 'h-11 text-body')} onClick={() => setConfirmLost(false)}>
                  {t('bazar.end.cancel')}
                </button>
                <button type="button" className={cn(btn, danger, 'h-11 text-body')} disabled={busy === 'lost'}
                  onClick={() => act('lost')} data-testid="mark-lost-yes">
                  {busy === 'lost' && <Loader2 size={16} className="animate-spin" aria-hidden />}
                  {t('bazar.end.markLost', { count: pickedList.length })}
                </button>
              </div>
            </section>
          ) : (
            <button type="button" className={cn(btn, secondary)} disabled={pickedList.length === 0}
              onClick={() => setConfirmLost(true)} data-testid="mark-lost">
              {pickedList.length === 0 ? t('bazar.end.chooseToMark') : t('bazar.end.markLost', { count: pickedList.length })}
            </button>
          )}
        </section>
      )}

      {error && <ErrorCard error={error} />}
      {closed ? (
        <section className="rounded-2xl bg-success/10 border border-success/30 px-4 py-3 flex flex-col gap-0.5" data-testid="transfer-closed">
          <span className="font-semibold text-success-text">{t('bazar.end.closedTitle')}</span>
          <span className="text-small text-primary">{t('bazar.end.closedBody')}</span>
        </section>
      ) : (
        <>
          <button type="button" className={cn(btn, primary)} disabled={!l.balanced || busy === 'close'}
            onClick={() => act('close')} data-testid="close-transfer">
            {busy === 'close' && <Loader2 size={16} className="animate-spin" aria-hidden />}
            {busy === 'close' ? t('bazar.end.closing') : t('bazar.end.close')}
          </button>
          {!l.balanced && <p className="text-small text-muted -mt-2">{t('bazar.end.closeBlocked')}</p>}
        </>
      )}
      {l.counts.damaged_at_bazar > 0 && closed && (
        <p className="text-small text-warning-text">{t('bazar.end.damagedStillAway', { count: l.counts.damaged_at_bazar })}</p>
      )}
    </div>
  )
}
