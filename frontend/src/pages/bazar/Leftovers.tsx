import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Check, Loader2, PackageCheck, TriangleAlert } from 'lucide-react'
import {
  BazarError, closeBazarTransfer, endBazarEvent, markBazarLost, scanBackOnce,
  type BazarLeftoverPiece, type BazarLeftovers, type BazarScanBackResult,
} from '../../bazar/api'
import { shortDate, transferCode } from '../../bazar/format'
import { useRetryingCommand } from '../../bazar/useRetryingCommand'
import { CodeEntry, CommandStatus, ErrorCard } from './phone/ReturnParts'
import { cn } from '../../components/ui'
import { Eyebrow } from './r1/parts'
import { Chip, Legend, StackBar } from './r2/parts'

// Bazar POS B5 — End event (mockup page 1, step 9) and the leftovers coming back on the event's linked transfer,
// shared by the phone screen and the desktop event page. Scans go one at a time (useRetryingCommand: one request in
// flight; a weak signal retries with the SAME client_request_id — never "back" before the server says so).
// R2 restyle (design/bazar-v2 V2_EndEvent): same endpoints, same rules; a piece can also be marked lost from its own
// row (the existing mark-lost call with that one piece, behind a confirm).

const btn = 'h-[52px] w-full rounded-xl text-body-lg font-semibold flex items-center justify-center gap-2 disabled:opacity-60'
const primary = 'bg-trace-blue hover:bg-trace-blue-hover text-white'
const danger = 'bg-critical hover:bg-critical/90 text-white'
const secondary = 'border border-line bg-surface text-primary'
const small = 'h-9 px-3 rounded-lg text-small font-semibold whitespace-nowrap disabled:opacity-60'

const pieceName = (p: Pick<BazarLeftoverPiece, 'product_title' | 'variant_title'>) =>
  p.variant_title ? `${p.product_title} · ${p.variant_title}` : p.product_title

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
      <ol className="flex flex-col gap-2">
        {(['what1', 'what2', 'what3'] as const).map((k, i) => (
          <li key={k} className="flex items-start gap-3 text-body text-primary">
            <span aria-hidden dir="ltr" className="w-6 h-6 rounded bg-grey-900 text-white text-small font-bold flex items-center justify-center shrink-0">{i + 1}</span>
            <span>{t(`bazar.end.${k}`)}</span>
          </li>
        ))}
      </ol>
      {days.length > 0 ? (
        <section role="alert" className="rounded-xl bg-warning/10 border border-warning/30 px-4 py-3 flex flex-col gap-2"
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

/** "21 of 24 back", the bar (good / damaged / lost / not scanned), the counters and the equation. */
export function LeftoverCounts({ l }: { l: BazarLeftovers }) {
  const { t } = useTranslation()
  const c = l.counts
  const expectedBack = Math.max(c.sent - c.sold, 0)
  const scannedBack = c.back + c.damaged
  return (
    <section className="flex flex-col gap-2.5" data-testid="leftover-counts">
      <p className="flex items-baseline gap-2" data-testid="leftover-back-of">
        <span dir="ltr" className="text-[36px] leading-[40px] font-bold text-primary tabular-nums">{scannedBack}</span>
        <span className="text-body text-muted">{t('bazar.r2.end.ofBack', { count: expectedBack })}</span>
      </p>
      <StackBar total={expectedBack} parts={[
        { key: 'back', n: c.back, color: 'bg-[#16A34A]' },
        { key: 'damaged', n: c.damaged, color: 'bg-critical' },
        { key: 'lost', n: c.lost, color: 'bg-grey-500' },
      ]} />
      <div className="flex flex-wrap gap-x-4 gap-y-1">
        <Legend color="bg-[#16A34A]" label={t('bazar.r2.end.good')} value={c.back} testId="count-back" />
        <Legend color="bg-critical" label={t('bazar.end.count.damaged')} value={c.damaged} testId="count-damaged" />
        <Legend color="bg-grey-500" label={t('bazar.end.count.lost')} value={c.lost} testId="count-lost" />
        <Legend color="bg-line" label={t('bazar.r2.end.notScanned')} value={c.outstanding} testId="count-out" />
        <Legend color="bg-trace-blue" label={t('bazar.end.count.sold')} value={c.sold} testId="count-sold" />
      </div>
      <p className="text-small text-muted tabular-nums" data-testid="leftover-equation" dir="auto">
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
      <span className="font-semibold text-primary truncate">{pieceName(p)}</span>
      <span className="text-small text-muted font-mono" dir="ltr">{p.piece_code ?? p.sku ?? ''}</span>
    </span>
  )
}

/**
 * The leftovers after the event ended: scan back (good → back in stock; damaged → home, set aside), the counts,
 * damaged still to bring home, the pieces not scanned back (Mark lost — one row, or ticked together), and Close the
 * transfer (only once every piece is accounted for).
 */
export function LeftoversPanel({ eventId, initial, onClosed, noCamera, wide }: {
  eventId: string
  initial: BazarLeftovers
  onClosed: (l: BazarLeftovers) => void
  /** Desktop: the scan field only (a HID scanner), no camera. */
  noCamera?: boolean
  /** R3 — the computer's End event page: scan + counts left, not back yet + close right. Same panel. */
  wide?: boolean
}) {
  const { t } = useTranslation()
  const [l, setL] = useState<BazarLeftovers>(initial)
  const cmd = useRetryingCommand<BazarScanBackResult>()
  const [last, setLast] = useState<BazarScanBackResult | null>(null)
  const [recent, setRecent] = useState<BazarScanBackResult[]>([])
  const [picked, setPicked] = useState<Set<string>>(new Set())
  const [confirmLost, setConfirmLost] = useState(false)
  const [rowLost, setRowLost] = useState<string | null>(null)
  const [busy, setBusy] = useState<'lost' | 'close' | null>(null)
  const [error, setError] = useState<BazarError | null>(null)

  useEffect(() => { setL(initial) }, [initial])
  useEffect(() => {
    if (cmd.state.kind === 'done') {
      const r = cmd.state.result
      setLast(r)
      setL(r.leftovers)
      // The scans of this screen, newest first (display only).
      setRecent(cur => [r, ...cur.filter(x => x.piece.piece_id !== r.piece.piece_id)].slice(0, 5))
    }
  }, [cmd.state])
  // A piece no longer out drops out of the selection (and out of an open row confirm).
  useEffect(() => {
    setPicked(cur => new Set([...cur].filter(id => l.outstanding.some(p => p.piece_id === id))))
    setRowLost(cur => (cur && l.outstanding.some(p => p.piece_id === cur) ? cur : null))
  }, [l.outstanding])

  const onCode = useCallback((code: string) => {
    if (cmd.busy) return
    setLast(null)
    cmd.run((requestId, timeoutMs) => scanBackOnce(eventId, code, requestId, timeoutMs))
  }, [cmd, eventId])

  const allPicked = l.outstanding.length > 0 && picked.size === l.outstanding.length
  const pickedList = useMemo(() => l.outstanding.filter(p => picked.has(p.piece_id)).map(p => p.piece_id), [l.outstanding, picked])

  async function markLost(ids: string[]) {
    setBusy('lost')
    setError(null)
    try {
      setL(await markBazarLost(eventId, ids))
      setConfirmLost(false)
      setRowLost(null)
    } catch (e) {
      setError(e instanceof BazarError ? e : new BazarError(0, { code: 'NETWORK' }))
    } finally {
      setBusy(null)
    }
  }

  async function closeTransfer() {
    setBusy('close')
    setError(null)
    try {
      const closed = await closeBazarTransfer(eventId)
      setL(closed)
      onClosed(closed)
    } catch (e) {
      setError(e instanceof BazarError ? e : new BazarError(0, { code: 'NETWORK' }))
    } finally {
      setBusy(null)
    }
  }

  const closed = l.transfer_status === 'closed'
  const scanning = !closed || l.counts.damaged_at_bazar > 0

  return (
    <div className={wide ? 'grid gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.25fr)] items-start' : 'flex flex-col gap-4'}
         data-testid="leftovers-panel">
      <div className="flex flex-col gap-4 min-w-0">
      <section className="card p-4 flex flex-col gap-3.5">
        <div className="flex items-center justify-between gap-2 flex-wrap">
          <h3 className="text-body-lg font-semibold text-primary">{t('bazar.r2.end.scanTitle')}</h3>
          <Chip tone="neutral"><span dir="ltr" className="font-mono">{transferCode(l.transfer_id)}</span>
            {' · '}{closed ? t('bazar.end.closedTitle') : t('bazar.r2.end.comingBack')}</Chip>
        </div>
        {scanning && (
          <>
            <CodeEntry onCode={onCode} disabled={cmd.busy} hint={t('bazar.end.scanHint')} noCamera={noCamera} />
            {cmd.state.kind !== 'done' && <CommandStatus state={cmd.state} />}
            {last && (
              <section role="status" data-testid={`scan-back-${last.result}`}
                className={cn('rounded-[10px] px-3.5 py-2.5 flex items-center gap-3',
                  last.result === 'back_good' ? 'bg-[#16A34A] text-white' : 'bg-warning/15 text-warning-text border border-warning/30')}>
                {last.result === 'back_good' ? <PackageCheck size={20} aria-hidden /> : <TriangleAlert size={20} aria-hidden />}
                <span className="flex flex-col min-w-0">
                  <span className="font-bold">{last.result === 'back_good' ? t('bazar.end.backGood') : t('bazar.end.backDamaged')}
                    {last.replayed && <span className="font-normal"> · {t('bazar.end.replayed')}</span>}</span>
                  <span className="text-small truncate">{pieceName(last.piece)}</span>
                </span>
              </section>
            )}
          </>
        )}
        <LeftoverCounts l={l} />
        {recent.length > 0 && (
          <div className="flex flex-col gap-1" data-testid="leftovers-recent">
            <Eyebrow>{t('bazar.r2.end.lastScanned')}</Eyebrow>
            <ul className="divide-y divide-line">
              {recent.map(r => (
                <li key={r.piece.piece_id} className="flex items-center justify-between gap-3 py-2">
                  <span className="flex flex-col min-w-0">
                    <span className="font-semibold text-primary truncate">{pieceName(r.piece)}</span>
                    <span className="text-small text-muted font-mono" dir="ltr">{r.piece.piece_code ?? ''}</span>
                  </span>
                  {r.result === 'back_good'
                    ? <Chip tone="good">{t('bazar.r2.end.backChip')}</Chip>
                    : <Chip tone="bad">{t('bazar.end.count.damaged')}</Chip>}
                </li>
              ))}
            </ul>
          </div>
        )}
        <p className="text-small text-muted">{t('bazar.r2.end.whoScans')}</p>
      </section>

      {l.damaged_to_bring_home.length > 0 && (
        <section className="card p-4 flex flex-col gap-2" data-testid="leftovers-damaged">
          <h3 className="font-semibold text-primary">{t('bazar.end.damagedTitle')}</h3>
          <p className="text-small text-muted">{t('bazar.end.damagedBody')}</p>
          <ul className="rounded-[10px] border border-line bg-surface divide-y divide-line">
            {l.damaged_to_bring_home.map(p => <li key={p.piece_id} className="px-3 py-2.5"><PieceLine p={p} /></li>)}
          </ul>
        </section>
      )}

      </div>
      <div className="flex flex-col gap-4 min-w-0">
      {l.outstanding.length > 0 && !closed && (
        <section className="card p-4 flex flex-col gap-2.5" data-testid="leftovers-out">
          <div className="flex items-start justify-between gap-2">
            <span className="flex flex-col">
              <h3 className="text-body-lg font-semibold text-primary">{t('bazar.r2.end.notBackYet', { count: l.outstanding.length })}</h3>
              <span className="text-small text-muted">{t('bazar.end.outBody')}</span>
            </span>
            <button type="button" className="min-h-[44px] px-1 text-small font-semibold text-trace-blue shrink-0"
              onClick={() => setPicked(allPicked ? new Set() : new Set(l.outstanding.map(p => p.piece_id)))}>
              {t('bazar.end.selectAll')}
            </button>
          </div>
          <ul className="flex flex-col gap-2">
            {l.outstanding.map(p => {
              const code = p.piece_code ?? p.sku ?? pieceName(p)
              return (
                <li key={p.piece_id} className="rounded-[10px] border border-line bg-surface">
                  <div className="flex items-center gap-3 px-3 py-2 min-h-[56px]">
                    <label className="relative flex items-center gap-3 cursor-pointer min-w-0 flex-1">
                      <input type="checkbox" className="w-5 h-5 rounded accent-trace-blue shrink-0" checked={picked.has(p.piece_id)}
                        onChange={e => setPicked(cur => {
                          const n = new Set(cur)
                          if (e.target.checked) n.add(p.piece_id); else n.delete(p.piece_id)
                          return n
                        })} data-testid={`out-${p.piece_id}`} />
                      <PieceLine p={p} />
                    </label>
                    {rowLost !== p.piece_id && (
                      <button type="button" className={cn(small, 'border border-critical/40 text-critical-text hover:bg-critical/5')}
                        disabled={busy === 'lost'} onClick={() => setRowLost(p.piece_id)} data-testid={`mark-lost-row-${p.piece_id}`}
                        aria-label={`${t('bazar.r2.end.markLostRow')} — ${code}`}>
                        {t('bazar.r2.end.markLostRow')}
                      </button>
                    )}
                  </div>
                  {rowLost === p.piece_id && (
                    <div role="alertdialog" aria-label={t('bazar.r2.end.markLostRow')} data-testid="mark-lost-row-confirm"
                      className="border-t border-critical/30 bg-critical/5 px-3 py-2.5 flex flex-col gap-2 rounded-b-[10px]">
                      <span className="text-body text-primary">{t('bazar.r2.end.rowLostConfirm', { code })}</span>
                      <div className="flex gap-2 justify-end">
                        <button type="button" className={cn(small, secondary)} onClick={() => setRowLost(null)}>{t('bazar.end.cancel')}</button>
                        <button type="button" className={cn(small, danger, 'flex items-center gap-1.5')} disabled={busy === 'lost'}
                          onClick={() => markLost([p.piece_id])} data-testid="mark-lost-row-yes">
                          {busy === 'lost' && <Loader2 size={14} className="animate-spin" aria-hidden />}
                          {t('bazar.r2.end.markLostRow')}
                        </button>
                      </div>
                    </div>
                  )}
                </li>
              )
            })}
          </ul>
          {confirmLost ? (
            <section role="alertdialog" className="rounded-[10px] border border-critical/40 bg-critical/10 px-4 py-3 flex flex-col gap-2.5"
                     data-testid="mark-lost-confirm">
              <span className="text-body text-primary">{t('bazar.end.markLostConfirm', { count: pickedList.length })}</span>
              <div className="grid grid-cols-2 gap-2">
                <button type="button" className={cn(btn, secondary, 'h-11 text-body')} onClick={() => setConfirmLost(false)}>
                  {t('bazar.end.cancel')}
                </button>
                <button type="button" className={cn(btn, danger, 'h-11 text-body')} disabled={busy === 'lost'}
                  onClick={() => markLost(pickedList)} data-testid="mark-lost-yes">
                  {busy === 'lost' && <Loader2 size={16} className="animate-spin" aria-hidden />}
                  {t('bazar.end.markLost', { count: pickedList.length })}
                </button>
              </div>
            </section>
          ) : (
            <button type="button" className={cn(btn, secondary, 'h-11 text-body')} disabled={pickedList.length === 0}
              onClick={() => setConfirmLost(true)} data-testid="mark-lost">
              {pickedList.length === 0 ? t('bazar.end.chooseToMark') : t('bazar.end.markLost', { count: pickedList.length })}
            </button>
          )}
        </section>
      )}

      {error && <ErrorCard error={error} />}

      {closed ? (
        <section className="rounded-xl bg-success/10 border border-success/30 px-4 py-3 flex flex-col gap-0.5" data-testid="transfer-closed">
          <span className="font-semibold text-success-text">{t('bazar.end.closedTitle')}</span>
          <span className="text-small text-primary">{t('bazar.end.closedBody')}</span>
        </section>
      ) : (
        <section className="bg-grey-900 text-[#F2F4F7] rounded-2xl p-4 flex flex-col gap-3" data-testid="leftovers-close-box">
          <Eyebrow dark>{t('bazar.r2.end.whenClose')}</Eyebrow>
          <dl className="flex flex-col gap-2 text-body">
            {(['good', 'damaged', 'lost'] as const).map(k => (
              <div key={k} className="flex justify-between gap-3">
                <dt className="text-grey-100">{t(`bazar.r2.end.what.${k}`, { count: k === 'good' ? l.counts.back : k === 'damaged' ? l.counts.damaged : l.counts.lost })}</dt>
                <dd className="font-semibold text-end">{t(`bazar.r2.end.then.${k}`)}</dd>
              </div>
            ))}
          </dl>
          <button type="button" className={cn(btn, primary, 'disabled:bg-white/[0.08] disabled:text-sidebar-text disabled:opacity-100')}
            disabled={!l.balanced || busy === 'close'} onClick={closeTransfer} data-testid="close-transfer">
            {busy === 'close' && <Loader2 size={16} className="animate-spin" aria-hidden />}
            {busy === 'close' ? t('bazar.end.closing') : t('bazar.end.close')}
          </button>
          {!l.balanced && <p className="text-small text-sidebar-text -mt-1">{t('bazar.end.closeBlocked')}</p>}
        </section>
      )}
      {l.counts.damaged_at_bazar > 0 && closed && (
        <p className="text-small text-warning-text">{t('bazar.end.damagedStillAway', { count: l.counts.damaged_at_bazar })}</p>
      )}
      </div>
    </div>
  )
}
