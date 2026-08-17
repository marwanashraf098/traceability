import { useCallback, useEffect, useState } from 'react'
import { useParams, useNavigate, Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Lock, ClipboardCheck } from 'lucide-react'
import {
  Badge, Button, Card, Spinner, Alert, Modal, EmptyState, Checkbox,
} from '../components/ui'
import {
  getStockTakeReconciliation, getStockTakeSession, resolveStockTake,
  attestStockTakeComplete, finalizeStockTake, releasePieceForAdjust,
  markStockTakeSyncResolved, repushStockTakeSync,
  StockTakeCommittedError, StockTakeReconciliation, StockTakeSessionDetail,
  StockTakePieceRow, PieceCommittedError,
} from '../api'

// FR-21 Step 6.3, screen 4 + 5 — reconciliation/disposition + sync panel.

const BUCKET_ORDER = [
  'on_shelf_counted', 'on_shelf_uncounted', 'committed_to_orders',
  'with_courier_or_delivered', 'returns_bench', 'damaged',
  'previously_written_off', 'unexpected_finds',
] as const

type BucketKey = typeof BUCKET_ORDER[number]

export default function StockTakeReview() {
  const { id } = useParams<{ id: string }>()
  const { t } = useTranslation()
  const navigate = useNavigate()

  const [reconciliation, setReconciliation] = useState<StockTakeReconciliation | null>(null)
  const [session, setSession] = useState<StockTakeSessionDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [attesting, setAttesting] = useState(false)
  const [showFinalizeConfirm, setShowFinalizeConfirm] = useState(false)
  const [finalizing, setFinalizing] = useState(false)
  // Gates the Finalize button inside the modal — unticked -> disabled. This is the one
  // action in the product that removes real sellable stock; the friction must gate, not
  // decorate. Reset on every open/close so a stale "understood" never survives a re-open.
  const [understood, setUnderstood] = useState(false)

  const load = useCallback(async () => {
    if (!id) return
    try {
      const [rec, sess] = await Promise.all([getStockTakeReconciliation(id), getStockTakeSession(id)])
      setReconciliation(rec)
      setSession(sess)
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [id])

  useEffect(() => { load() }, [load])

  async function handleAttest() {
    if (!id) return
    setAttesting(true)
    try {
      await attestStockTakeComplete(id)
      await load()
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setAttesting(false)
    }
  }

  async function handleFinalize() {
    if (!id) return
    setFinalizing(true)
    try {
      await finalizeStockTake(id)
      setShowFinalizeConfirm(false)
      await load()
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setFinalizing(false)
    }
  }

  if (loading) {
    return <div className="flex justify-center py-16"><Spinner size={28} /></div>
  }
  if (!reconciliation || !session || !id) {
    return <Alert tone="critical" title={error ?? t('stocktake.review.notFound')} />
  }

  const rollup = reconciliation.variantRollup
  const totalExpected = rollup.reduce((s, r) => s + r.expectedOnShelf, 0)
  const totalCounted = rollup.reduce((s, r) => s + r.counted, 0)
  const totalVariance = rollup.reduce((s, r) => s + r.variance, 0)
  const isOpen = session.status === 'open'
  const isFinalized = session.status === 'finalized'

  return (
    <div className="space-y-4">
      <button onClick={() => navigate('/stock-take')} className="text-small text-trace-blue hover:text-trace-blue-hover transition-colors">
        ← {t('stocktake.review.back')}
      </button>

      {error && <Alert tone="critical" title={error} />}

      {/* Header */}
      <div className="flex items-start justify-between flex-wrap gap-3">
        <div>
          <h1 className="text-h1 text-primary flex items-center gap-2">
            {t('stocktake.review.title')}
            <Badge tone={isFinalized ? 'success' : isOpen ? 'warning' : 'neutral'} label={t(`stocktake.status.${session.status}`)} />
          </h1>
          <p className="text-small text-muted mt-1">
            {t('stocktake.review.coverage', { percent: reconciliation.coveragePercent })}
            {' · '}
            {t('stocktake.review.countedOfExpected', { counted: totalCounted, expected: totalExpected })}
          </p>
        </div>
        <div className={`text-end ${totalVariance < 0 ? 'text-danger' : 'text-success'}`}>
          <p className="text-caption uppercase tracking-widest">{t('stocktake.review.variance')}</p>
          <p className="text-h2 font-mono" data-testid="variance-headline">{totalVariance}</p>
        </div>
      </div>

      {/* Per-variant rollup */}
      {rollup.length > 0 && (
        <div className="card overflow-hidden">
          <table className="min-w-full">
            <thead>
              <tr className="border-b border-line">
                {['stocktake.review.col.variant', 'stocktake.review.col.totalKnown', 'stocktake.review.col.expectedOnShelf',
                  'stocktake.review.col.counted', 'stocktake.review.col.variance', 'stocktake.review.col.committed',
                  'stocktake.review.col.gone', 'stocktake.review.col.damaged'].map(k => (
                  <th key={k} className="tbl-header">{t(k)}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rollup.map(r => (
                <tr key={r.variantId} className="tbl-row">
                  <td className="tbl-cell">
                    <div className="text-body font-medium text-primary">{r.variantTitle}</div>
                    {r.sku && <div className="text-caption text-muted font-mono">{r.sku}</div>}
                  </td>
                  <td className="tbl-cell text-primary">{r.totalKnown}</td>
                  <td className="tbl-cell text-primary">{r.expectedOnShelf}</td>
                  <td className="tbl-cell text-primary">{r.counted}</td>
                  <td className={`tbl-cell font-semibold ${r.variance < 0 ? 'text-danger' : 'text-success'}`}>{r.variance}</td>
                  <td className="tbl-cell text-muted">{r.committed}</td>
                  <td className="tbl-cell text-muted">{r.gone}</td>
                  <td className="tbl-cell text-muted">{r.damagedCount}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Disposition buckets */}
      <div className="space-y-3">
        {BUCKET_ORDER.map(key => (
          <DispositionBucket
            key={key}
            bucketKey={key}
            rows={reconciliation.buckets[key] ?? []}
            completeCount={session.completeCount}
            sessionOpen={isOpen}
            onChanged={load}
            sessionId={id}
          />
        ))}
      </div>

      {/* Attest-complete / finalize */}
      {isOpen && (
        <Card className="space-y-3">
          {!session.completeCount ? (
            <>
              <p className="text-body text-primary font-medium">{t('stocktake.review.attestPrompt')}</p>
              <p className="text-small text-muted">{t('stocktake.review.attestWarning')}</p>
              <Button loading={attesting} onClick={handleAttest}>{t('stocktake.review.attestButton')}</Button>
            </>
          ) : (
            <>
              <p className="text-body text-success font-medium">{t('stocktake.review.attested')}</p>
              <Button variant="destructive" onClick={() => { setUnderstood(false); setShowFinalizeConfirm(true) }}>
                {t('stocktake.review.finalizeButton')}
              </Button>
            </>
          )}
        </Card>
      )}

      {showFinalizeConfirm && (() => {
        const negativeRollup = rollup.filter(r => r.variance < 0)
        return (
          <Modal title={t('stocktake.review.finalizeConfirmTitle')} onClose={() => setShowFinalizeConfirm(false)}>
            <div className="space-y-4">
              <p className="text-body text-primary">
                {t('stocktake.review.finalizeConfirmBody', {
                  count: reconciliation.buckets.on_shelf_uncounted?.length ?? 0,
                  variants: negativeRollup.map(r => r.variantTitle).join(', ') || t('common.na'),
                })}
              </p>
              {negativeRollup.length > 0 && (
                <div className="rounded-lg border border-line bg-elevated p-3 space-y-1.5">
                  {negativeRollup.map(r => (
                    <div key={r.variantId} className="flex items-center justify-between text-small">
                      <span className="text-muted">{r.variantTitle}</span>
                      <span className="font-mono font-semibold text-danger">{r.variance}</span>
                    </div>
                  ))}
                </div>
              )}
              <Checkbox
                checked={understood}
                onChange={setUnderstood}
                label={t('stocktake.review.finalizeUnderstand')}
              />
              <div className="flex gap-3 justify-end">
                <Button variant="secondary" onClick={() => setShowFinalizeConfirm(false)}>{t('common.cancel')}</Button>
                <Button
                  variant="destructive"
                  loading={finalizing}
                  disabled={!understood}
                  onClick={handleFinalize}
                >
                  {t('stocktake.review.finalizeConfirmButton')}
                </Button>
              </div>
            </div>
          </Modal>
        )
      })()}

      {/* Sync panel */}
      {isFinalized && <SyncPanel sessionId={id} shopifySync={session.shopifySync} onChanged={load} />}

      {/* Close summary — auditable record of a finalized session */}
      {isFinalized && (
        <CloseSummaryCard session={session} totalCounted={totalCounted} totalVariance={totalVariance} />
      )}
    </div>
  )
}

// ── Close summary ─────────────────────────────────────────────────────────────
// Every field here is already on the wire — session detail (openedBy/finalizedBy/
// finalizedAt) and shopifySync.deltas (per-variant write-off counts, populated at
// finalize) — no new data, purely a client-side render of what finalizeStockTake()
// and getStockTakeSession() already return.

function syncStatusKey(status: string | undefined): string {
  if (status === 'pending') return 'syncPending'
  if (status === 'pushed') return 'syncPushed'
  if (status === 'failed') return 'syncFailed'
  if (status === 'failed_ambiguous') return 'syncAmbiguous'
  return 'syncNotStarted'
}

function formatDuration(openedAt: string, finalizedAt: string): string {
  const ms = new Date(finalizedAt).getTime() - new Date(openedAt).getTime()
  const totalMinutes = Math.max(0, Math.round(ms / 60000))
  const hours = Math.floor(totalMinutes / 60)
  const minutes = totalMinutes % 60
  if (hours === 0) return `${minutes}m`
  return `${hours}h ${minutes}m`
}

function CloseSummaryCard({ session, totalCounted, totalVariance }: {
  session: StockTakeSessionDetail
  totalCounted: number
  totalVariance: number
}) {
  const { t } = useTranslation()
  const writtenOff = (session.shopifySync?.deltas ?? []).reduce((sum, d) => sum + Math.abs(d.delta), 0)

  return (
    <Card className="flex flex-col items-center gap-3 text-center py-8" data-testid="close-summary">
      <ClipboardCheck size={40} strokeWidth={1.75} className="text-success" />
      <h2 className="text-h2 text-primary">{t('stocktake.review.closeSummary.title')}</h2>
      <div className="flex gap-6 mt-1">
        <SummaryStat value={totalCounted} label={t('stocktake.review.closeSummary.counted')} />
        <SummaryStat value={totalVariance} label={t('stocktake.review.closeSummary.variance')} tone={totalVariance < 0 ? 'danger' : 'success'} />
        <SummaryStat value={writtenOff} label={t('stocktake.review.closeSummary.writtenOff')} tone={writtenOff > 0 ? 'danger' : 'success'} />
      </div>
      <div className="w-full max-w-xs border-t border-line mt-2 pt-3 flex flex-col gap-1.5 text-start">
        <div className="flex justify-between text-small">
          <span className="text-muted">{t('stocktake.review.closeSummary.attestedBy')}</span>
          <span className="text-primary">{session.openedByName ?? t('common.na')}</span>
        </div>
        {session.finalizedByName && session.finalizedAt && (
          <>
            <div className="flex justify-between text-small">
              <span className="text-muted">{t('stocktake.review.closeSummary.finalizedBy')}</span>
              <span className="text-primary">{session.finalizedByName}</span>
            </div>
            <div className="flex justify-between text-small">
              <span className="text-muted">{t('stocktake.review.closeSummary.duration')}</span>
              <span className="text-primary font-mono">{formatDuration(session.openedAt, session.finalizedAt)}</span>
            </div>
          </>
        )}
        <div className="flex justify-between text-small">
          <span className="text-muted">{t('stocktake.review.closeSummary.shopifySync')}</span>
          <span className="text-primary">{t(`stocktake.review.closeSummary.${syncStatusKey(session.shopifySync?.status)}`)}</span>
        </div>
      </div>
    </Card>
  )
}

function SummaryStat({ value, label, tone = 'primary' }: { value: number; label: string; tone?: 'primary' | 'success' | 'danger' }) {
  const toneClass = tone === 'success' ? 'text-success' : tone === 'danger' ? 'text-danger' : 'text-primary'
  return (
    <div className="text-center">
      <p className={`text-h3 font-mono font-bold ${toneClass}`}>{value}</p>
      <p className="text-caption text-muted">{label}</p>
    </div>
  )
}

// ── Disposition bucket ───────────────────────────────────────────────────────

function DispositionBucket({
  bucketKey, rows, completeCount, sessionOpen, onChanged, sessionId,
}: {
  bucketKey: BucketKey
  rows: StockTakePieceRow[]
  completeCount: boolean
  sessionOpen: boolean
  onChanged: () => void
  sessionId: string
}) {
  const { t } = useTranslation()
  const [expanded, setExpanded] = useState(true)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [busy, setBusy] = useState<string | null>(null)
  const [committedErrors, setCommittedErrors] = useState<Record<string, PieceCommittedError>>({})
  const [releasing, setReleasing] = useState<string | null>(null)
  const [rowError, setRowError] = useState<Record<string, string>>({})

  const isFreeUncounted = bucketKey === 'on_shelf_uncounted'
  const isCommitted = bucketKey === 'committed_to_orders'

  function toggleSelect(pieceId: string) {
    setSelected(prev => {
      const next = new Set(prev)
      if (next.has(pieceId)) next.delete(pieceId); else next.add(pieceId)
      return next
    })
  }

  async function runResolve(pieceId: string, action: 'found' | 'lost' | 'mark_damaged') {
    if (!sessionOpen) return
    setBusy(pieceId)
    setRowError(prev => ({ ...prev, [pieceId]: '' }))
    try {
      await resolveStockTake(sessionId, [{ pieceId, action }])
      onChanged()
    } catch (e: unknown) {
      if (e instanceof StockTakeCommittedError) {
        setCommittedErrors(prev => ({ ...prev, [pieceId]: e.body }))
      } else {
        setRowError(prev => ({ ...prev, [pieceId]: e instanceof Error ? e.message : String(e) }))
      }
    } finally {
      setBusy(null)
    }
  }

  async function handleBulkFound() {
    if (selected.size === 0) return
    setBusy('__bulk__')
    try {
      await resolveStockTake(sessionId, Array.from(selected).map(pieceId => ({ pieceId, action: 'found' as const })))
      setSelected(new Set())
      onChanged()
    } catch (e: unknown) {
      setRowError(prev => ({ ...prev, __bulk__: e instanceof Error ? e.message : String(e) }))
    } finally {
      setBusy(null)
    }
  }

  async function handleRelease(pieceId: string) {
    setReleasing(pieceId)
    try {
      await releasePieceForAdjust(pieceId)
      setCommittedErrors(prev => { const next = { ...prev }; delete next[pieceId]; return next })
    } catch (e: unknown) {
      setRowError(prev => ({ ...prev, [pieceId]: e instanceof Error ? e.message : String(e) }))
    } finally {
      setReleasing(null)
    }
  }

  return (
    <Card className="space-y-3">
      <button
        type="button"
        className="w-full flex items-center justify-between text-start"
        onClick={() => setExpanded(x => !x)}
      >
        <span className="text-body font-semibold text-primary flex items-center gap-2">
          {t(`stocktake.review.bucket.${bucketKey}`)}
          <Badge tone="neutral" label={String(rows.length)} />
        </span>
        <span className="text-muted text-small">{expanded ? '▾' : '▸'}</span>
      </button>

      {expanded && (
        rows.length === 0 ? (
          <EmptyState message={t('stocktake.review.bucketEmpty')} />
        ) : (
          <div className="space-y-2">
            {isFreeUncounted && selected.size > 0 && (
              <div className="flex items-center justify-between bg-elevated rounded-lg px-3 py-2">
                <span className="text-small text-muted">{t('stocktake.review.selectedCount', { count: selected.size })}</span>
                <Button size="sm" loading={busy === '__bulk__'} onClick={handleBulkFound}>
                  {t('stocktake.review.foundSelected')}
                </Button>
              </div>
            )}
            {rowError.__bulk__ && <Alert tone="critical" title={rowError.__bulk__} />}

            {rows.map(row => (
              <div key={row.pieceId} className="rounded-lg border border-line p-3 space-y-2">
                <div className="flex items-center justify-between gap-3 flex-wrap">
                  <div className="flex items-center gap-2">
                    {isFreeUncounted && (
                      <input
                        type="checkbox"
                        checked={selected.has(row.pieceId)}
                        onChange={() => toggleSelect(row.pieceId)}
                        aria-label={t('stocktake.review.selectRow')}
                      />
                    )}
                    <div>
                      <Link to={`/lookup?q=${encodeURIComponent(row.pieceId)}`} className="text-trace-blue hover:underline text-body font-medium">
                        {row.productTitle} · {row.variantTitle}
                      </Link>
                      {row.sku && <span className="font-mono text-caption text-muted ms-2">{row.sku}</span>}
                    </div>
                  </div>

                  <div className="flex items-center gap-2 flex-wrap">
                    {row.orderNumber && (
                      <Link to={`/orders/${row.orderId}`} className="btn-outline btn text-caption font-mono">
                        #{row.orderNumber}
                      </Link>
                    )}
                    {row.trackingNumber && (
                      <span className="font-mono text-caption text-muted">{row.trackingNumber}</span>
                    )}

                    {isFreeUncounted && (
                      <>
                        <Button size="sm" variant="secondary" loading={busy === row.pieceId}
                          onClick={() => runResolve(row.pieceId, 'found')}>
                          {t('stocktake.review.foundIt')}
                        </Button>
                        <span title={!completeCount ? t('stocktake.review.markLostGatedTooltip') : undefined}>
                          <Button size="sm" variant="destructive" loading={busy === row.pieceId}
                            disabled={!completeCount}
                            iconStart={!completeCount ? Lock : undefined}
                            onClick={() => runResolve(row.pieceId, 'lost')}>
                            {t('stocktake.review.markLost')}
                          </Button>
                        </span>
                        {row.flag === 'condition_mismatch' && row.liveStatus === 'available' && (
                          <Button size="sm" variant="secondary" loading={busy === row.pieceId}
                            onClick={() => runResolve(row.pieceId, 'mark_damaged')}>
                            {t('stocktake.review.applyDamaged')}
                          </Button>
                        )}
                        {row.flag === 'condition_mismatch' && row.liveStatus === 'damaged' && (
                          <span className="text-caption text-muted" title={t('stocktake.review.terminalTooltip')}>
                            {t('stocktake.review.terminalFlag')}
                          </span>
                        )}
                      </>
                    )}

                    {isCommitted && (
                      <Button size="sm" variant="destructive" loading={busy === row.pieceId}
                        onClick={() => runResolve(row.pieceId, 'lost')}>
                        {t('stocktake.review.markLost')}
                      </Button>
                    )}
                  </div>
                </div>

                {rowError[row.pieceId] && <Alert tone="critical" title={rowError[row.pieceId]} />}

                {/* Reused from Lookup.tsx's AdjustPanel — inline warning card, NOT a Modal */}
                {committedErrors[row.pieceId] && (
                  <div className="rounded-lg border border-warning/30 bg-warning/5 p-4 space-y-3">
                    <p className="text-body font-semibold text-warning">{t('adjust.committedTitle')}</p>
                    <p className="text-small text-muted">
                      {t('adjust.committedBody', {
                        status: row.liveStatus,
                        orderNumber: committedErrors[row.pieceId].orderNumber,
                      })}
                    </p>
                    <div className="flex gap-2">
                      <button
                        data-testid={`release-btn-${row.pieceId}`}
                        onClick={() => handleRelease(row.pieceId)}
                        disabled={releasing === row.pieceId}
                        className="btn-brand text-small"
                      >
                        {releasing === row.pieceId ? <Spinner size={14} /> : t('adjust.releaseBtn')}
                      </button>
                      <Link
                        to={`/orders/${committedErrors[row.pieceId].orderId}`}
                        className="btn-outline text-small font-mono"
                      >
                        #{committedErrors[row.pieceId].orderNumber}
                      </Link>
                    </div>
                  </div>
                )}
              </div>
            ))}
          </div>
        )
      )}
    </Card>
  )
}

// ── Sync panel ────────────────────────────────────────────────────────────────

function SyncPanel({ sessionId, shopifySync, onChanged }: {
  sessionId: string
  shopifySync: StockTakeSessionDetail['shopifySync']
  onChanged: () => void
}) {
  const { t } = useTranslation()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleMarkResolved() {
    setBusy(true); setError(null)
    try { await markStockTakeSyncResolved(sessionId); await onChanged() }
    catch (e: unknown) { setError(e instanceof Error ? e.message : String(e)) }
    finally { setBusy(false) }
  }

  async function handleRepush() {
    setBusy(true); setError(null)
    try { await repushStockTakeSync(sessionId); await onChanged() }
    catch (e: unknown) { setError(e instanceof Error ? e.message : String(e)) }
    finally { setBusy(false) }
  }

  if (!shopifySync) {
    return <Alert tone="info" title={t('stocktake.sync.notStarted')} />
  }

  if (shopifySync.status === 'pending') {
    return (
      <Card className="flex items-center gap-3">
        <Spinner size={18} />
        <span className="text-body text-primary">{t('stocktake.sync.pending')}</span>
      </Card>
    )
  }

  if (shopifySync.status === 'pushed') {
    return (
      <Card className="space-y-2 border-success/40 bg-success/5">
        <p className="text-body font-medium text-success">{t('stocktake.sync.pushed')}</p>
        <ul className="text-small text-muted space-y-1">
          {shopifySync.deltas.map(d => (
            <li key={d.variantId}>{d.variantTitle} ({d.sku}): {d.delta}</li>
          ))}
        </ul>
        <p className="text-caption text-muted font-mono">{shopifySync.referenceDocumentUri}</p>
      </Card>
    )
  }

  if (shopifySync.status === 'failed') {
    return <Alert tone="warning" title={t('stocktake.sync.failed')} />
  }

  // failed_ambiguous
  return (
    <Card className="space-y-3 border-danger/40 bg-danger/5">
      <p className="text-body font-semibold text-danger">{t('stocktake.sync.ambiguousTitle')}</p>
      <p className="text-small text-muted">
        {t('stocktake.sync.ambiguousBody', {
          variants: shopifySync.deltas.map(d => d.variantTitle).join(', ') || t('common.na'),
          ref: shopifySync.referenceDocumentUri,
        })}
      </p>
      {error && <Alert tone="critical" title={error} />}
      <div className="flex gap-2">
        <Button size="sm" loading={busy} onClick={handleMarkResolved}>
          {t('stocktake.sync.markResolved')}
        </Button>
        <Button size="sm" variant="destructive" loading={busy} onClick={handleRepush}>
          {t('stocktake.sync.repush')}
        </Button>
      </div>
    </Card>
  )
}
