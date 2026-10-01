import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { CheckCircle2, OctagonAlert, PackageCheck, Undo2 } from 'lucide-react'
import { ScanShell } from '../../components/ScanShell'
import { useScanner, ScanOutcome } from '../../hooks/useScanner'
import { useStation } from '../../components/StationProvider'
import { Alert, Button, Modal, ProductThumb, Radio, cn } from '../../components/ui'
import {
  endPackSession, getMe, getPackSession, retryPackComplete, scanPackPiece, scanPackWaybill,
  setAsidePackOrder, undoPackPiece, TransferCommandError,
  PackOrderCard, PackScanResponse, PackSessionView, SetAsideReason, WaybillOutcome,
} from '../../api'

// Pick & Pack S3 — the waybill pack session (design/pick-pack-waybill-mockup/SessionWaiting,
// SessionScanning, Rejected, SetAside). Full-screen immersive — no <Layout>. Built on the shared
// useScanner + ScanShell (PickScreen and its safety-critical handlers are not touched): one scan
// input; the state machine lives in onScan — waiting for a waybill → scanning the open order's
// pieces → back to waiting when the order auto-completes or is set aside. useScanner drops a scan
// while one is in flight; keyboard scans are deliberately not queued.

type PackedFlash = { number: string | null; customer: string | null; pieces: number }
type Failed = { code: string; message: string | null }
type LastScan = { pieceId: string; name: string; barcode: string } | null

const SET_ASIDE_REASONS: SetAsideReason[] = ['piece_missing', 'damaged_piece', 'waybill_damaged', 'other']

export default function PackSessionScreen({ initial, onEnded }: {
  initial: PackSessionView
  onEnded: () => void
}) {
  const { t, i18n } = useTranslation()
  const ar = i18n.language === 'ar'
  const { currentWorker } = useStation()
  const [meName, setMeName] = useState<string | null>(null)
  const [view, setView] = useState<PackSessionView>(initial)
  const [order, setOrder] = useState<PackOrderCard | null>(initial.openOrder)
  const [rejection, setRejection] = useState<WaybillOutcome | null>(null)
  const [pieceError, setPieceError] = useState<string | null>(null)
  const [packed, setPacked] = useState<PackedFlash | null>(null)
  const [failed, setFailed] = useState<Failed | null>(null)
  const [lastScan, setLastScan] = useState<LastScan>(null)
  const [setAsideOpen, setSetAsideOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Worker shown in the header: the PIN-switched station worker, else /me.
  useEffect(() => {
    if (currentWorker) return
    getMe().then(m => setMeName(m?.name ?? null)).catch(() => {})
  }, [currentWorker])
  const worker = currentWorker?.name ?? meName ?? view.workerName ?? ''

  const refresh = useCallback(async () => {
    try { setView(await getPackSession(view.id)) } catch { /* counters refresh is best-effort */ }
  }, [view.id])

  const apiError = (e: unknown) => e instanceof TransferCommandError
    ? (ar ? e.messageAr : e.messageEn)
    : t('fulfill.waybill.session.error')

  function lineName(card: PackOrderCard | null, pieceId: string | null): string {
    const line = card?.items.find(i => i.allocatedPieces.some(p => p.piece_id === pieceId))
    if (!line) return ''
    return [line.product_title, line.variant_title !== 'Default Title' ? line.variant_title : null].filter(Boolean).join(' · ')
  }

  function applyScanResponse(r: PackScanResponse): ScanOutcome {
    if (r.status === 'completed') {
      setOrder(null)
      setLastScan(null)
      setFailed(null)
      setPacked({ number: r.packed?.orderNumber ?? null, customer: r.packed?.customerName ?? null, pieces: r.packed?.pieces ?? 0 })
      refresh()
      return { success: true }
    }
    if (r.order) setOrder(r.order)
    if (r.status === 'complete_failed') {
      setFailed({ code: r.failCode ?? 'COMPLETE_ERROR', message: r.failMessage })
      return { success: false }
    }
    if (r.status === 'rejected') {
      const code = r.scan?.code ?? 'ERROR'
      setPieceError(t(`fulfill.waybill.pieceReject.${code}`, {
        defaultValue: t(`fulfill.rejection.${code}`, { defaultValue: r.scan?.message ?? code }),
      }))
      return { success: false }
    }
    if (r.scan?.pieceId) {
      setLastScan({ pieceId: r.scan.pieceId, barcode: r.scan.barcode ?? '', name: lineName(r.order, r.scan.pieceId) })
    }
    return { success: true }
  }

  const onScan = useCallback(async (code: string): Promise<ScanOutcome> => {
    if (setAsideOpen) return { success: false }
    setError(null)
    setPieceError(null)

    if (!order) {
      setRejection(null)
      try {
        const r = await scanPackWaybill(view.id, code)
        if (r.result === 'opened' && r.order) {
          setPacked(null)
          setFailed(null)
          setLastScan(null)
          setOrder(r.order)
          return { success: true }
        }
        setRejection(r)
        refresh()
        return { success: false }
      } catch (e) {
        setError(apiError(e))
        return { success: false }
      }
    }

    try {
      return applyScanResponse(await scanPackPiece(view.id, order.id, code))
    } catch (e) {
      setError(apiError(e))
      return { success: false }
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [order, view.id, setAsideOpen, refresh, t, ar])

  const scanner = useScanner({ onScan })

  // Keep the scan input focused between scans. useScanner disables the input while a scan is in
  // flight and calls focus() in its finally — before React re-enables it — so that focus() is a
  // no-op and a hardware scanner's next scan would go nowhere. Refocus here once the scan has
  // settled (and whenever the screen state changes), instead of touching useScanner's copied
  // safety-critical code. Never while the set-aside dialog is open (no input there to steal from,
  // but its radios should keep the click).
  useEffect(() => {
    if (!scanner.scanning && !setAsideOpen) scanner.inputRef.current?.focus()
  }, [scanner.scanning, scanner.inputRef, order, rejection, failed, setAsideOpen])

  async function retry() {
    if (!order || busy) return
    setBusy(true)
    try { applyScanResponse(await retryPackComplete(view.id, order.id)) }
    catch (e) { setError(apiError(e)) }
    finally { setBusy(false) }
  }

  async function undo() {
    if (!order || !lastScan || busy) return
    setBusy(true)
    try {
      await undoPackPiece(view.id, order.id, lastScan.pieceId)
      setLastScan(null)
      const v = await getPackSession(view.id)
      setView(v)
      setOrder(v.openOrder)
    } catch (e) { setError(apiError(e)) }
    finally { setBusy(false) }
  }

  async function confirmSetAside(reason: SetAsideReason) {
    if (!order || busy) return
    setBusy(true)
    try {
      await setAsidePackOrder(view.id, order.id, reason)
      setSetAsideOpen(false)
      setOrder(null)
      setLastScan(null)
      setFailed(null)
      await refresh()
    } catch (e) { setError(apiError(e)) }
    finally { setBusy(false) }
  }

  async function end() {
    if (order || busy) return
    setBusy(true)
    try { await endPackSession(view.id); onEnded() }
    catch (e) { setError(apiError(e)); setBusy(false) }
  }

  const fmtTime = (iso: string | null) => iso
    ? new Date(iso).toLocaleTimeString(i18n.language, { hour: '2-digit', minute: '2-digit' })
    : ''

  return (
    <div className="flex flex-col h-screen bg-base" data-testid="pack-session">
      {/* Header */}
      <div className="bg-panel border-b border-line px-4 md:px-6 h-16 flex items-center gap-4 flex-shrink-0">
        <div className="min-w-0">
          <p className="text-body font-semibold text-primary leading-tight">{t('fulfill.waybill.session.title')}</p>
          <p className="text-caption text-muted">{t('fulfill.waybill.session.mode')}</p>
        </div>
        <div className="flex-1 flex flex-wrap items-center justify-center gap-2" data-testid="session-counters">
          <span className="text-small font-semibold text-success-text bg-success/[0.12] rounded-full px-3 py-1">
            {t('fulfill.waybill.session.packed', { count: view.counters.packed })}
          </span>
          <span className="text-small font-semibold text-warning-text bg-warning/[0.14] rounded-full px-3 py-1">
            {t('fulfill.waybill.session.setAside', { count: view.counters.setAside })}
          </span>
          <span className="text-small font-semibold text-muted bg-elevated rounded-full px-3 py-1">
            {t('fulfill.waybill.session.left', { count: view.counters.left })}
          </span>
        </div>
        <div className="flex items-center gap-3">
          <span className="hidden sm:inline-flex items-center gap-2 text-small text-primary">
            <span className="w-7 h-7 rounded-full bg-trace-blue/15 text-trace-blue font-semibold flex items-center justify-center">
              {worker.slice(0, 1).toUpperCase()}
            </span>
            {worker}
          </span>
          <span title={order ? t('fulfill.waybill.session.endDisabled') : undefined}>
            <Button variant="secondary" size="sm" onClick={end} disabled={!!order || busy}>
              {t('fulfill.waybill.session.end')}
            </Button>
          </span>
        </div>
      </div>

      <div className="flex-1 flex flex-col lg:flex-row min-h-0">
        {/* Main column */}
        <div className="flex-1 flex flex-col min-h-0 p-4 md:p-6 gap-4 overflow-y-auto">
          {error && <Alert tone="critical" title={error} />}

          {rejection ? (
            <RejectionCard r={rejection} fmtTime={fmtTime} onOk={() => setRejection(null)} />
          ) : order ? (
            <OrderPanel
              order={order}
              lastScan={lastScan}
              pieceError={pieceError}
              failed={failed}
              busy={busy}
              fmtTime={fmtTime}
              onUndo={undo}
              onRetry={retry}
              onSetAside={() => setSetAsideOpen(true)}
            />
          ) : (
            <div className="flex-1 flex flex-col items-center justify-center gap-5 text-center" data-testid="session-waiting">
              {packed && (
                <div className="w-full max-w-xl flex items-center gap-3 rounded-2xl border border-success/40 bg-success/[0.10] px-5 py-4 text-start"
                  data-testid="packed-flash">
                  <CheckCircle2 size={28} className="text-success flex-shrink-0" />
                  <div className="min-w-0">
                    <p className="text-body font-semibold text-primary">
                      {t('fulfill.waybill.session.packedFlash', { number: packed.number ?? '' })}
                    </p>
                    <p className="text-small text-muted">
                      {t('fulfill.waybill.session.packedFlashDetail', { customer: packed.customer ?? '', count: packed.pieces })}
                    </p>
                  </div>
                </div>
              )}
              <PackageCheck size={56} strokeWidth={1.5} className="text-trace-blue" />
              <div>
                <p className="text-h2 text-primary">{t('fulfill.waybill.session.scanWaybill')}</p>
                <p className="text-body text-muted mt-1">{t('fulfill.waybill.session.scanWaybillHint')}</p>
              </div>
            </div>
          )}

          <div className="flex-shrink-0">
            <ScanShell
              scanner={scanner}
              placeholder={order ? t('fulfill.waybill.session.piecePlaceholder') : t('fulfill.waybill.session.waybillPlaceholder')}
            />
          </div>
        </div>

        {/* Side list */}
        <aside className="lg:w-80 flex-shrink-0 border-t lg:border-t-0 lg:border-s border-line bg-panel p-4 overflow-y-auto"
          data-testid="session-recent">
          <p className="text-caption font-semibold text-muted uppercase tracking-wide mb-3">
            {t('fulfill.waybill.session.thisSession')}
          </p>
          {view.recent.length === 0 ? (
            <p className="text-small text-muted">{t('fulfill.waybill.session.nothingYet')}</p>
          ) : (
            <ul className="space-y-2">
              {view.recent.map((r, i) => (
                <li key={`${r.at}-${i}`} className="flex items-center justify-between gap-2 text-small">
                  <span className="min-w-0">
                    <span className="font-mono font-semibold text-primary">{r.orderNumber ?? '—'}</span>
                    {r.customerName && <span className="text-muted"> {r.customerName}</span>}
                  </span>
                  <span className="flex items-center gap-2 flex-shrink-0">
                    <span className={cn('text-caption font-semibold rounded-full px-2 py-0.5',
                      r.outcome === 'packed' ? 'bg-success/[0.12] text-success-text'
                        : r.outcome === 'set_aside' ? 'bg-warning/[0.14] text-warning-text'
                        : 'bg-critical/[0.12] text-critical-text')}>
                      {t(`fulfill.waybill.session.outcome.${r.outcome}`)}
                    </span>
                    <span className="text-caption text-muted font-mono">{fmtTime(r.at)}</span>
                  </span>
                </li>
              ))}
            </ul>
          )}
        </aside>
      </div>

      {setAsideOpen && order && (
        <SetAsideDialog
          order={order}
          busy={busy}
          onKeep={() => setSetAsideOpen(false)}
          onConfirm={confirmSetAside}
        />
      )}
    </div>
  )
}

function OrderPanel({ order, lastScan, pieceError, failed, busy, fmtTime, onUndo, onRetry, onSetAside }: {
  order: PackOrderCard
  lastScan: LastScan
  pieceError: string | null
  failed: Failed | null
  busy: boolean
  fmtTime: (iso: string | null) => string
  onUndo: () => void
  onRetry: () => void
  onSetAside: () => void
}) {
  const { t } = useTranslation()
  const total = order.items.reduce((n, i) => n + i.quantity, 0)
  const done = order.items.reduce((n, i) => n + Math.min(i.allocated, i.quantity), 0)
  const cod = order.payment_method === 'cod' && order.cod_amount != null

  return (
    <div className="flex flex-col lg:flex-row gap-4" data-testid="session-scanning">
      {/* Order card */}
      <div className="card p-4 lg:w-72 flex-shrink-0 space-y-2" data-testid="order-card">
        <p className="text-caption text-muted uppercase tracking-wide">{t('fulfill.waybill.session.order')}</p>
        <p className="text-h2 font-mono text-primary">{order.number}</p>
        <p className="text-body text-primary">{order.customer_name}</p>
        {order.area && <p className="text-small text-muted">{order.area}</p>}
        <div className="pt-2 space-y-1 text-small text-muted border-t border-line">
          {order.tracking_number && (
            <p className="font-mono">{t('fulfill.waybill.session.waybill', { tracking: order.tracking_number })}</p>
          )}
          <p>{t(`fulfill.waybill.session.courier.${order.courierType ?? 'delivery'}`)}</p>
          <p className={cod ? 'text-primary font-semibold' : undefined}>
            {cod ? t('fulfill.waybill.session.cod', { amount: Number(order.cod_amount).toLocaleString() })
                 : t('fulfill.waybill.session.prepaid')}
          </p>
          <p>{order.batchNo != null
            ? t('fulfill.waybill.session.batch', { no: order.batchNo, time: fmtTime(order.batchPrintedAt) })
            : t('fulfill.waybill.session.notInBatch')}</p>
        </div>
        <div className="pt-2">
          <Button variant="secondary" size="sm" className="w-full" onClick={onSetAside} disabled={busy}>
            {t('fulfill.waybill.session.setAsideButton')}
          </Button>
        </div>
      </div>

      {/* Lines */}
      <div className="flex-1 min-w-0 space-y-3">
        <div className="flex items-end justify-between gap-3">
          <div>
            <p className="text-h3 text-primary">{t('fulfill.waybill.session.scanPieces')}</p>
            <p className="text-small text-muted">{t('fulfill.waybill.session.scanPiecesHint')}</p>
          </div>
          <p className="text-h4 font-mono text-primary">{t('fulfill.waybill.session.progress', { done, total })}</p>
        </div>
        <div className="h-2 rounded-full bg-elevated overflow-hidden">
          <div className="h-full bg-trace-blue transition-all" style={{ width: `${total ? (done / total) * 100 : 0}%` }} />
        </div>

        {failed && (
          <div className="rounded-xl border border-critical/30 bg-critical/[0.10] p-4 space-y-3" data-testid="complete-failed">
            <p className="flex items-center gap-2 text-body font-semibold text-critical-text">
              <OctagonAlert size={18} /> {t('fulfill.waybill.completeFailed.title')}
            </p>
            <p className="text-small text-primary">
              {t(`fulfill.waybill.completeFailed.${failed.code}`, {
                message: failed.message ?? '', defaultValue: failed.message ?? failed.code,
              })}
            </p>
            <Button size="sm" onClick={onRetry} loading={busy}>{t('fulfill.waybill.completeFailed.retry')}</Button>
          </div>
        )}

        <div className="space-y-2.5">
          {order.items.map(item => {
            const complete = item.allocated >= item.quantity
            const left = Math.max(item.quantity - item.allocated, 0)
            return (
              <div key={item.id}
                className={cn('card p-3.5 flex items-center gap-4', complete ? 'border-success/40 bg-success/[0.06]' : '')}>
                <ProductThumb src={item.imageUrl} alt={item.product_title} size={88} cdnWidth={176} />
                <div className="flex-1 min-w-0">
                  <p className="text-body font-semibold text-primary break-words">{item.product_title}</p>
                  {item.variant_title && item.variant_title !== 'Default Title' && (
                    <p className="text-small text-muted break-words">{item.variant_title}</p>
                  )}
                  {item.sku && <p className="text-caption text-muted font-mono break-words">{item.sku}</p>}
                </div>
                <div className="flex items-center gap-2 flex-shrink-0">
                  <span className={cn('text-h4 font-mono', complete ? 'text-success-text' : 'text-primary')}>
                    {item.allocated} / {item.quantity}
                  </span>
                  {complete
                    ? <CheckCircle2 size={22} className="text-success" />
                    : <span className="text-caption font-semibold text-trace-blue bg-trace-blue/[0.10] rounded-full px-2 py-0.5">
                        {t('fulfill.waybill.session.more', { count: left })}
                      </span>}
                </div>
              </div>
            )
          })}
        </div>

        {pieceError && <Alert tone="critical" title={pieceError} />}

        {lastScan && (
          <div className="flex items-center gap-3 rounded-xl bg-trace-blue/[0.06] border border-trace-blue/20 px-4 py-2.5 text-small"
            data-testid="last-scan">
            <span className="font-semibold text-trace-blue">{t('fulfill.waybill.session.lastScan')}</span>
            <span className="text-primary min-w-0 truncate">{lastScan.name}</span>
            <span className="font-mono text-muted">{lastScan.barcode}</span>
            <Button variant="secondary" size="sm" iconStart={Undo2} className="ms-auto" onClick={onUndo} disabled={busy}>
              {t('fulfill.waybill.session.undo')}
            </Button>
          </div>
        )}
      </div>
    </div>
  )
}

function RejectionCard({ r, fmtTime, onOk }: {
  r: WaybillOutcome
  fmtTime: (iso: string | null) => string
  onOk: () => void
}) {
  const { t, i18n } = useTranslation()
  const message = i18n.language === 'ar' ? r.messageAr : r.messageEn
  return (
    <div className="flex-1 flex items-center justify-center" data-testid="session-rejected">
      <div className="w-full max-w-xl rounded-2xl border-2 border-critical/40 bg-critical/[0.06] p-6 space-y-4 text-center">
        <OctagonAlert size={44} className="text-critical mx-auto" />
        <p className="text-h2 text-critical-text" data-testid="rejected-title">
          {t(`fulfill.waybill.rejected.${r.code}`, { defaultValue: r.code ?? '' })}
        </p>
        {message && <p className="text-body text-primary">{message}</p>}
        {r.code === 'CANCELLED' && r.at && (
          <p className="text-small text-muted">{t('fulfill.waybill.rejected.cancelledAt', { time: fmtTime(r.at) })}</p>
        )}
        {r.code === 'ALREADY_PACKED' && r.at && (
          <p className="text-small text-muted">{t('fulfill.waybill.rejected.packedAt', { time: fmtTime(r.at) })}</p>
        )}
        <Button onClick={onOk} className="w-full">{t('fulfill.waybill.rejected.ok')}</Button>
      </div>
    </div>
  )
}

function SetAsideDialog({ order, busy, onKeep, onConfirm }: {
  order: PackOrderCard
  busy: boolean
  onKeep: () => void
  onConfirm: (reason: SetAsideReason) => void
}) {
  const { t } = useTranslation()
  const [reason, setReason] = useState<SetAsideReason | null>(null)
  const scanned = order.items.reduce((n, i) => n + i.allocated, 0)
  return (
    <Modal title={t('fulfill.waybill.setAside.title', { number: order.number ?? '' })} onClose={onKeep}>
      <div className="space-y-4" data-testid="set-aside-dialog">
        <p className="text-small text-muted">
          {scanned > 0 ? t('fulfill.waybill.setAside.body', { count: scanned }) : t('fulfill.waybill.setAside.bodyNone')}
        </p>
        <div className="space-y-2">
          <p className="text-small font-semibold text-primary">{t('fulfill.waybill.setAside.reason')}</p>
          {SET_ASIDE_REASONS.map(r => (
            <label key={r}
              className={cn('flex items-center gap-3 rounded-xl border p-3 cursor-pointer',
                reason === r ? 'border-trace-blue bg-trace-blue/[0.06]' : 'border-line hover:bg-elevated')}>
              <Radio value={r} checked={reason === r} onChange={v => setReason(v as SetAsideReason)} />
              <span className="text-body text-primary">{t(`fulfill.waybill.setAside.reasons.${r}`)}</span>
            </label>
          ))}
        </div>
        <div className="flex gap-2">
          <Button variant="secondary" className="flex-1" onClick={onKeep} disabled={busy}>
            {t('fulfill.waybill.setAside.keep')}
          </Button>
          <Button variant="danger" className="flex-1" disabled={!reason} loading={busy}
            onClick={() => reason && onConfirm(reason)}>
            {t('fulfill.waybill.setAside.confirm')}
          </Button>
        </div>
      </div>
    </Modal>
  )
}
