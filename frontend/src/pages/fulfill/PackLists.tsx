import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { ChevronRight, Printer } from 'lucide-react'
import {
  getOrdersPickPlaces, getPrintBatchesToday, getPrintedNotPacked, getRoleFromToken, PrintJobRunningError, startPrintJob,
  TransferCommandError, NotPackedRow, PrintBatchToday,
} from '../../api'
import { Alert, Button, Modal, Skeleton, cn } from '../../components/ui'
import { useCapabilities } from '../../capabilities'
import { PrintJobPanel } from './PrintJobPanel'
import { orderPlaceSummary } from './placeSplit'

// Pick & Pack S4 — the two lists on the waybill-mode page (design/pick-pack-waybill-mockup/
// Main.html): "Print batches today" (with Reprint) and "Printed but not packed". Each loads its
// own data; `refreshKey` reloads both after a print. Counts and statuses come from the server
// (PackListRules), never derived here.

const time = (iso: string, lang: string) =>
  new Date(iso).toLocaleTimeString(lang, { hour: '2-digit', minute: '2-digit' })

function setAsideReasonKey(r: string | null) {
  return r && ['piece_missing', 'damaged_piece', 'waybill_damaged', 'other', 'deleted_in_bosta'].includes(r) ? r : 'other'
}

// ── Print batches today ─────────────────────────────────────────────────────

export function PrintBatchesToday({ refreshKey }: { refreshKey: number }) {
  const { t, i18n } = useTranslation()
  const [rows, setRows] = useState<PrintBatchToday[] | null>(null)
  const [reprinting, setReprinting] = useState<string | null>(null)
  /** The reprint job being followed (merged-PDF slice C: a reprint is a background print job). */
  const [reprintJob, setReprintJob] = useState<{ jobId: string; batchNo: number; alreadyRunning: boolean } | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    getPrintBatchesToday()
      .then(r => { if (!cancelled) setRows(Array.isArray(r) ? r : []) })
      .catch(() => { if (!cancelled) setRows([]) })
    return () => { cancelled = true }
  }, [refreshKey])

  async function reprint(b: PrintBatchToday) {
    if (reprinting) return
    setReprinting(b.batchId)
    setError(null)
    try {
      const jobId = await startPrintJob({ kind: 'reprint', batchId: b.batchId })
      setReprintJob({ jobId, batchNo: b.batchNo, alreadyRunning: false })
    } catch (e) {
      if (e instanceof PrintJobRunningError) {
        setReprintJob({ jobId: e.jobId, batchNo: b.batchNo, alreadyRunning: true })
        return
      }
      setError(e instanceof TransferCommandError
        ? (i18n.language === 'ar' ? e.messageAr : e.messageEn)
        : t('fulfill.printBatch.error'))
    } finally {
      setReprinting(null)
    }
  }

  return (
    <section className="card p-5 space-y-3" data-testid="batches-today">
      <div className="flex items-center justify-between">
        <h2 className="text-h3 text-primary">{t('fulfill.waybill.lists.batchesTitle')}</h2>
        {rows && rows.length > 0 && (
          <span className="text-small text-muted">{t('fulfill.waybill.lists.batchesCount', { count: rows.length })}</span>
        )}
      </div>
      {error && <Alert tone="critical" title={error} />}
      {rows === null ? (
        <Skeleton className="h-24 rounded-xl" />
      ) : rows.length === 0 ? (
        <p className="text-small text-muted py-6 text-center" data-testid="batches-empty">
          {t('fulfill.waybill.lists.batchesEmpty')}
        </p>
      ) : (
        <ul className="divide-y divide-line">
          {rows.map(b => {
            const total = Math.max(b.waybillCount, b.packed + b.setAside + b.cancelled + b.waiting)
            const pct = (n: number) => `${total ? (n / total) * 100 : 0}%`
            return (
              <li key={b.batchId} className="py-3 flex flex-wrap items-center gap-x-6 gap-y-2" data-testid="batch-row">
                <div className="min-w-[8rem]">
                  <p className="text-body font-semibold text-primary font-mono">#{b.batchNo}</p>
                  <p className="text-small text-muted">{t('fulfill.waybill.lists.today', { time: time(b.printedAt, i18n.language) })}</p>
                </div>
                <div className="min-w-[7rem] text-small">
                  <p className="text-muted">{t('fulfill.waybill.lists.printedBy')}</p>
                  <p className="text-primary">{b.printedByName ?? '—'}</p>
                </div>
                <div className="min-w-[5rem] text-small">
                  <p className="text-muted">{t('fulfill.waybill.lists.waybills')}</p>
                  <p className="text-primary font-mono">{b.waybillCount}</p>
                </div>
                <div className="flex-1 min-w-[14rem] space-y-1">
                  <div className="h-2 rounded-full bg-elevated overflow-hidden flex" aria-hidden>
                    <span className="h-full bg-success" style={{ width: pct(b.packed) }} />
                    <span className="h-full bg-warning" style={{ width: pct(b.setAside) }} />
                    <span className="h-full bg-critical" style={{ width: pct(b.cancelled) }} />
                  </div>
                  <p className="text-small text-muted" data-testid="batch-progress">
                    {[
                      t('fulfill.waybill.lists.progressPacked', { count: b.packed }),
                      b.setAside > 0 && t('fulfill.waybill.lists.progressSetAside', { count: b.setAside }),
                      b.cancelled > 0 && t('fulfill.waybill.lists.progressCancelled', { count: b.cancelled }),
                      b.waiting > 0 ? t('fulfill.waybill.lists.progressWaiting', { count: b.waiting })
                        : t('fulfill.waybill.lists.progressNoneLeft'),
                    ].filter(Boolean).join(' · ')}
                  </p>
                </div>
                <Button variant="secondary" size="sm" iconStart={Printer} loading={reprinting === b.batchId}
                  onClick={() => reprint(b)}>
                  {t('fulfill.waybill.lists.reprint')}
                </Button>
              </li>
            )
          })}
        </ul>
      )}

      {reprintJob && (
        <Modal
          title={reprintJob.alreadyRunning ? t('fulfill.printBatch.title')
            : t('fulfill.waybill.lists.reprintTitle', { no: reprintJob.batchNo })}
          onClose={() => setReprintJob(null)}
        >
          <div data-testid="reprint-result">
            <PrintJobPanel
              key={reprintJob.jobId}
              jobId={reprintJob.jobId}
              alreadyRunning={reprintJob.alreadyRunning}
              actions={() => (
                <Button variant="secondary" className="w-full" onClick={() => setReprintJob(null)}>
                  {t('fulfill.printBatch.done')}
                </Button>
              )}
            />
          </div>
        </Modal>
      )}
    </section>
  )
}

// ── Printed but not packed ──────────────────────────────────────────────────

const STATUS_TONE: Record<NotPackedRow['status'], string> = {
  cancelled: 'bg-critical/[0.12] text-critical-text',
  packing:   'bg-trace-blue/[0.12] text-trace-blue',
  set_aside: 'bg-warning/[0.14] text-warning-text',
  waiting:   'bg-elevated text-muted',
}

export function PrintedNotPacked({ refreshKey }: { refreshKey: number }) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const canOpenException = ['owner', 'manager'].includes(getRoleFromToken() ?? '')
  const [rows, setRows] = useState<NotPackedRow[] | null>(null)

  useEffect(() => {
    let cancelled = false
    getPrintedNotPacked()
      .then(r => { if (!cancelled) setRows(Array.isArray(r) ? r : []) })
      .catch(() => { if (!cancelled) setRows([]) })
    return () => { cancelled = true }
  }, [refreshKey])

  // Warehouse places S3: each order's places, one request after the list loads; the list is then sorted by place
  // (walking order, like the gather list). Places off or the request fails → today's list, unchanged.
  const { places: placesOn } = useCapabilities()
  const [places, setPlaces] = useState<{ rows: NotPackedRow[]; byOrder: Map<string, ReturnType<typeof orderPlaceSummary>> } | null>(null)
  useEffect(() => {
    if (!placesOn || !rows || rows.length === 0) return
    let cancelled = false
    getOrdersPickPlaces([...new Set(rows.map(r => r.orderId))])
      .then(r => {
        if (cancelled || !r || !Array.isArray(r.orders)) return
        setPlaces({ rows, byOrder: new Map(r.orders.map(o => [o.orderId, orderPlaceSummary(o.lines)])) })
      })
      .catch(() => { /* today's list */ })
    return () => { cancelled = true }
  }, [placesOn, rows])
  const placed = places && places.rows === rows ? places.byOrder : null
  const shown = rows && placed
    ? rows.map((r, i) => ({ r, i, key: placed.get(r.orderId)?.key ?? null }))
        .sort((a, b) => (a.key == null || b.key == null)
          ? ((a.key == null ? 1 : 0) - (b.key == null ? 1 : 0)) || a.i - b.i
          : (a.key < b.key ? -1 : a.key > b.key ? 1 : a.i - b.i))
        .map(x => x.r)
    : rows

  function chip(r: NotPackedRow) {
    switch (r.status) {
      case 'cancelled': return t('fulfill.waybill.lists.statusCancelled')
      case 'packing':   return t('fulfill.waybill.lists.statusPacking', { name: r.packerName ?? '' })
      case 'set_aside': return t('fulfill.waybill.lists.statusSetAside', {
        reason: t(`fulfill.waybill.setAside.reasons.${setAsideReasonKey(r.setAsideReason)}`) })
      default:          return t('fulfill.waybill.lists.statusWaiting')
    }
  }

  return (
    <section className="card p-5 space-y-3" data-testid="printed-not-packed">
      <div className="flex items-center justify-between">
        <h2 className="text-h3 text-primary">{t('fulfill.waybill.lists.notPackedTitle')}</h2>
        {rows && rows.length > 0 && (
          <span className="text-small text-muted">{t('fulfill.waybill.lists.notPackedCount', { count: rows.length })}</span>
        )}
      </div>
      {rows === null ? (
        <Skeleton className="h-24 rounded-xl" />
      ) : rows.length === 0 ? (
        <p className="text-small text-muted py-6 text-center" data-testid="not-packed-empty">
          {t('fulfill.waybill.lists.notPackedEmpty')}
        </p>
      ) : (
        <ul className="divide-y divide-line">
          {(shown ?? rows).map(r => {
            const opens = canOpenException && !!r.exceptionType && !!r.subjectKey
            const where = placed?.get(r.orderId)
            const content = (
              <>
                <span className="min-w-0 flex-1">
                  <span className="block text-body text-primary">
                    <span className="font-mono font-semibold">{r.orderNumber ?? '—'}</span>
                    {r.customerName && <span> · {r.customerName}</span>}
                  </span>
                  <span className="block text-small text-muted">
                    <span className="font-mono">{r.trackingNumber}</span>
                    {' · '}{t('fulfill.waybill.lists.batchNo', { no: r.batchNo })}
                  </span>
                </span>
                {where && (
                  <span className="w-40 flex-shrink-0 text-small" data-testid="not-packed-places">
                    <span className="block text-caption text-muted">{t('fulfill.places.colPlace')}</span>
                    <span className="block text-primary break-words">
                      {where.codes.length > 0 && <bdi className="font-mono">{where.codes.join(' · ')}</bdi>}
                      {where.codes.length > 0 && where.noPlace && ' · '}
                      {where.noPlace && <span className="text-muted">{t('fulfill.places.noPlace')}</span>}
                      {where.codes.length === 0 && !where.noPlace && '—'}
                    </span>
                  </span>
                )}
                {(() => {
                  const status = (
                    <span className={cn('text-caption font-semibold rounded-full px-2.5 py-1 flex-shrink-0', STATUS_TONE[r.status])}
                      data-testid={`status-${r.status}`}>
                      {chip(r)}
                    </span>
                  )
                  // With the place column, the status sits in a fixed slot so the column lines up row to row.
                  return where ? <span className="w-64 flex-shrink-0 flex justify-end">{status}</span> : status
                })()}
                {opens && <ChevronRight size={16} className="text-muted flex-shrink-0 rtl:rotate-180" />}
              </>
            )
            return (
              <li key={r.shipmentId} data-testid="not-packed-row">
                {opens ? (
                  <button type="button"
                    className="w-full py-3 flex items-center gap-3 text-start hover:bg-elevated rounded-lg px-1 -mx-1 transition-colors"
                    onClick={() => navigate(`/exceptions?type=${r.exceptionType}&key=${encodeURIComponent(r.subjectKey!)}`)}>
                    {content}
                  </button>
                ) : (
                  <div className="py-3 flex items-center gap-3">{content}</div>
                )}
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
