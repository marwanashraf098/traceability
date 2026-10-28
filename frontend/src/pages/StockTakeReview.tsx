import { useCallback, useEffect, useState } from 'react'
import { useParams, useNavigate, Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { ViaPhoneTag } from '../phone/ViaPhoneTag'
import type { TFunction } from 'i18next'
import { ClipboardCheck, Smartphone, ScanLine, ArrowLeft, Search } from 'lucide-react'
import {
  Badge, Button, Card, Spinner, Alert, Modal, EmptyState, Checkbox, Input,
} from '../components/ui'
import {
  getStockTakeReconciliation, getStockTakeSession, resolveStockTake,
  attestStockTakeComplete, finalizeStockTake,
  markStockTakeSyncResolved, repushStockTakeSync,
  StockTakeRequestError, StockTakeReconciliation, StockTakeSessionDetail,
  StockTakePieceRow, StockTakeFinalizePlan, StockTakeWriteOffPiece,
} from '../api'
import { UntaggedCountsPanel } from './stocktake/UntaggedCounts'
import { ReviewScanOverlay } from './stocktake/ReviewScanOverlay'

// FR-21 Step 6.3, screen 4 + 5 — reconciliation/disposition + sync panel.
// 2026-10-27: no per-row Found it / Mark lost. A missed piece is counted by scanning it ("Found one?
// Scan it" — a full-screen overlay on the counting screen's own scan request); finalize writes off what
// is still uncounted, and its confirm lists every piece. Finalized: read-only lists + a Lookup pointer.

/** The text an error banner shows: the server's own message, else readable words for the status —
 *  never a bare "404: ". */
export function stockTakeErrorText(e: unknown, t: TFunction, lang: string): string {
  if (e instanceof StockTakeRequestError) {
    const own = e.serverMessage(lang)
    if (own) return own
    const known = [400, 403, 404, 409].includes(e.status)
    return t(known ? `stocktake.review.error.${e.status}` : 'stocktake.review.error.generic')
  }
  return t('stocktake.review.error.generic')
}

const BUCKET_ORDER = [
  'on_shelf_counted', 'on_shelf_uncounted', 'committed_to_orders',
  'with_courier_or_delivered', 'returns_bench', 'damaged',
  'previously_written_off', 'unexpected_finds',
] as const

type BucketKey = typeof BUCKET_ORDER[number]

export default function StockTakeReview() {
  const { id } = useParams<{ id: string }>()
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const errorText = (e: unknown) => stockTakeErrorText(e, t, i18n.language)
  const [scanOpen, setScanOpen] = useState(false)

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
  // Typed confirmation (StockTakeFinalizePolicy): the user types the write-off count when the
  // plan asks for it. Reset on every open, like the checkbox.
  const [typedCount, setTypedCount] = useState('')

  const load = useCallback(async () => {
    if (!id) return
    try {
      const [rec, sess] = await Promise.all([getStockTakeReconciliation(id), getStockTakeSession(id)])
      setReconciliation(rec)
      setSession(sess)
    } catch (e: unknown) {
      setError(stockTakeErrorText(e, t, i18n.language))
    } finally {
      setLoading(false)
    }
  }, [id, t, i18n.language])

  useEffect(() => { load() }, [load])

  async function handleAttest() {
    if (!id) return
    setAttesting(true)
    try {
      await attestStockTakeComplete(id)
      await load()
    } catch (e: unknown) {
      setError(errorText(e))
    } finally {
      setAttesting(false)
    }
  }

  async function handleFinalize() {
    if (!id) return
    setFinalizing(true)
    try {
      const plan = reconciliation?.finalizePlan
      if (plan?.requiresTypedConfirmation) {
        await finalizeStockTake(id, Number(typedCount))
      } else {
        await finalizeStockTake(id)
      }
      setShowFinalizeConfirm(false)
      await load()
    } catch (e: unknown) {
      setError(errorText(e))
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
  const sessionName = session.note || id.slice(0, 8)

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
        <div className={`text-end ${varianceTone(totalVariance)}`}>
          <p className="text-caption uppercase tracking-widest">{t('stocktake.review.variance')}</p>
          <p className="text-h2 font-mono" data-testid="variance-headline">{totalVariance}</p>
        </div>
      </div>

      {/* Open session: count a missed piece by scanning it, or go back to the counting screen. */}
      {isOpen && (
        <div className="flex flex-wrap gap-2" data-testid="review-open-actions">
          <Button iconStart={ScanLine} onClick={() => setScanOpen(true)}>
            {t('stocktake.review.scan.openButton')}
          </Button>
          <Button variant="secondary" iconStart={ArrowLeft} onClick={() => navigate(`/stock-take/${id}/scan`)}>
            {t('stocktake.review.backToCounting')}
          </Button>
        </div>
      )}

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
                  <td className={`tbl-cell font-semibold ${varianceTone(r.variance)}`}>{r.variance}</td>
                  <td className="tbl-cell text-muted">{r.committed}</td>
                  <td className="tbl-cell text-muted">{r.gone}</td>
                  <td className="tbl-cell text-muted">{r.damagedCount}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Flexible labeling slice 7 — untagged pieces, counted per product (switch on only). */}
      {reconciliation.untagged && (
        <UntaggedCountsPanel sessionId={id} rows={reconciliation.untagged} editable={isOpen} onSaved={load} />
      )}

      {/* Finalized: what this count did, read-only. Otherwise the disposition buckets. */}
      {isFinalized ? (
        <FinalizedLists reconciliation={reconciliation} />
      ) : (
        <div className="space-y-3">
          {BUCKET_ORDER.map(key => (
            <DispositionBucket
              key={key}
              bucketKey={key}
              rows={reconciliation.buckets[key] ?? []}
              sessionOpen={isOpen}
              onChanged={load}
              sessionId={id}
            />
          ))}
        </div>
      )}

      {/* Attest-complete / finalize */}
      {isOpen && (
        <Card className="space-y-3">
          {/* Q1b — informational, not a guard: how many counts came from a paired phone. */}
          <PhoneScanShare phone={reconciliation.phoneScanCount} total={reconciliation.scanCount} />
          {!session.completeCount ? (
            <>
              <p className="text-body text-primary font-medium">{t('stocktake.review.attestPrompt')}</p>
              <p className="text-small text-muted">{t('stocktake.review.attestWarning')}</p>
              <Button loading={attesting} onClick={handleAttest}>{t('stocktake.review.attestButton')}</Button>
            </>
          ) : (
            <>
              <p className="text-body text-success font-medium">{t('stocktake.review.attested')}</p>
              <Button variant="destructive" onClick={() => { setUnderstood(false); setTypedCount(''); setShowFinalizeConfirm(true) }}>
                {t('stocktake.review.finalizeButton')}
              </Button>
            </>
          )}
        </Card>
      )}

      {showFinalizeConfirm && (
        <FinalizeModal
          plan={reconciliation.finalizePlan}
          understood={understood}
          onUnderstood={setUnderstood}
          typedCount={typedCount}
          onTypedCount={setTypedCount}
          finalizing={finalizing}
          onCancel={() => setShowFinalizeConfirm(false)}
          onFinalize={handleFinalize}
          phoneScanCount={reconciliation.phoneScanCount}
          scanCount={reconciliation.scanCount}
        />
      )}

      {/* Sync panel */}
      {isFinalized && <SyncPanel sessionId={id} shopifySync={session.shopifySync} onChanged={load} />}

      {/* Close summary — auditable record of a finalized session */}
      {isFinalized && (
        <CloseSummaryCard session={session} totalCounted={totalCounted} totalVariance={totalVariance} />
      )}

      {scanOpen && (
        <ReviewScanOverlay
          sessionId={id}
          sessionName={sessionName}
          uncounted={reconciliation.buckets.on_shelf_uncounted ?? []}
          onScanned={load}
          onClose={() => { setScanOpen(false); load() }}
        />
      )}
    </div>
  )
}

// ── Finalized session — read-only lists ──────────────────────────────────────

function FinalizedLists({ reconciliation }: { reconciliation: StockTakeReconciliation }) {
  const { t } = useTranslation()
  const rows = BUCKET_ORDER.flatMap(k => reconciliation.buckets[k] ?? [])
  const writtenOff = rows.filter(r => r.writtenOffHere)
  const found = rows.filter(r => r.foundHere)
  const counted = [...(reconciliation.buckets.on_shelf_counted ?? []), ...(reconciliation.buckets.damaged ?? [])]
    .filter(r => !r.foundHere)

  return (
    <div className="space-y-3" data-testid="finalized-lists">
      <PieceListCard title={t('stocktake.review.finalized.counted', { count: counted.length })} rows={counted} testId="finalized-counted" />
      <PieceListCard title={t('stocktake.review.finalized.writtenOff', { count: writtenOff.length })} rows={writtenOff}
        testId="finalized-written-off" tone="danger" />
      <PieceListCard title={t('stocktake.review.finalized.found', { count: found.length })} rows={found} testId="finalized-found" />
      <div data-testid="finalized-lookup-hint"><Card className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-small text-muted">{t('stocktake.review.finalized.lookupHint')}</p>
        <Link to="/lookup" className="btn-outline btn text-small inline-flex items-center gap-1.5">
          <Search size={14} strokeWidth={2} />
          {t('stocktake.review.finalized.openLookup')}
        </Link>
      </Card></div>
    </div>
  )
}

function PieceListCard({ title, rows, testId, tone }: {
  title: string; rows: StockTakePieceRow[]; testId: string; tone?: 'danger'
}) {
  const { t } = useTranslation()
  const [expanded, setExpanded] = useState(rows.length > 0 && rows.length <= 20)
  return (
    <div data-testid={testId}><Card className="space-y-3">
      <button type="button" className="w-full flex items-center justify-between text-start" onClick={() => setExpanded(x => !x)}>
        <span className={`text-body font-semibold ${tone === 'danger' && rows.length > 0 ? 'text-danger' : 'text-primary'}`}>{title}</span>
        <span className="text-muted text-small">{expanded ? '▾' : '▸'}</span>
      </button>
      {expanded && (rows.length === 0 ? <EmptyState message={t('stocktake.review.bucketEmpty')} /> : (
        <ul className="space-y-1.5">
          {rows.map(r => <PieceLine key={r.pieceId} row={r} />)}
        </ul>
      ))}
    </Card></div>
  )
}

function PieceLine({ row }: { row: StockTakePieceRow }) {
  return (
    <li className="flex items-center gap-3 flex-wrap rounded-lg border border-line px-3 py-2 text-small">
      {row.shortCode && <span className="font-mono text-primary" dir="ltr">{row.shortCode}</span>}
      <Link to={`/lookup?q=${encodeURIComponent(row.pieceId)}`} className="text-trace-blue hover:underline">
        {row.productTitle} · {row.variantTitle}
      </Link>
      {row.sku && <span className="font-mono text-caption text-muted" dir="ltr">{row.sku}</span>}
      {row.scanDevice === 'phone' && <ViaPhoneTag />}
    </li>
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
  if (status === 'nothing_to_push') return 'syncNothingToPush'
  if (status === 'superseded_by_seed') return 'syncSuperseded'
  return 'syncNotStarted'
}

/** variance = expected − counted: positive means pieces are short. */
function varianceTone(variance: number): string {
  if (variance > 0) return 'text-danger'
  if (variance < 0) return 'text-warning'
  return 'text-success'
}

// ── Finalize modal ───────────────────────────────────────────────────────────
// Every number here is the backend's finalize plan (StockTakeReconciliationService.plan — the
// same method finalize runs), so what the modal says is exactly what finalize will do.

/** Q1b: "N of M scans came from a phone" — shown whenever N > 0; informational only. */
function PhoneScanShare({ phone, total }: { phone?: number; total?: number }) {
  const { t } = useTranslation()
  if (!phone) return null
  return (
    <p className="flex items-center gap-1.5 text-small text-info" data-testid="phone-scan-share">
      <Smartphone size={14} />
      {t('phone.phoneScans', { count: phone, total: total ?? phone })}
    </p>
  )
}

function FinalizeModal({
  plan, understood, onUnderstood, typedCount, onTypedCount, finalizing, onCancel, onFinalize,
  phoneScanCount, scanCount,
}: {
  plan: StockTakeFinalizePlan | undefined
  understood: boolean
  onUnderstood: (v: boolean) => void
  typedCount: string
  onTypedCount: (v: string) => void
  finalizing: boolean
  onCancel: () => void
  onFinalize: () => void
  phoneScanCount?: number
  scanCount?: number
}) {
  const { t } = useTranslation()
  const blocked = plan?.blockedReason ?? null
  const typedOk = !plan?.requiresTypedConfirmation || typedCount.trim() === String(plan.writeOffs)
  const tracedOnly = plan ? plan.byVariant.reduce((n, v) => n + v.damaged + v.onHold, 0) : 0
  // Slice 7: untagged write-offs are written off (and leave Shopify) like any not-found piece.
  const untaggedWriteOffs = plan?.untaggedWriteOffs ?? 0
  const untaggedRows = plan?.untagged?.filter(r => r.writeOff > 0 || r.extra > 0) ?? []

  return (
    <Modal title={t('stocktake.review.finalizeConfirmTitle')} onClose={onCancel}>
      <div className="space-y-4">
        {blocked === 'ZERO_SCANS' && (
          <Alert tone="critical" title={t('stocktake.review.finalize.zeroScans')} />
        )}
        <PhoneScanShare phone={phoneScanCount} total={scanCount} />
        {blocked === 'UNTAGGED_COUNT_MISSING' && (
          <Alert tone="warning" title={t('stocktake.review.finalize.untaggedMissing', { count: plan?.untaggedMissingCounts ?? 0 })} />
        )}
        {blocked === 'ATTESTATION_REQUIRED' && (
          <Alert tone="warning" title={t('stocktake.review.finalize.attestationRequired')} />
        )}
        {plan && !blocked && (
          <>
            {(plan.writeOffPieces?.length ?? 0) > 0 && <WriteOffPieceList pieces={plan.writeOffPieces ?? []} />}
            <p className="text-body text-primary" data-testid="finalize-summary">
              {t('stocktake.review.finalize.summary', { count: plan.writeOffs + untaggedWriteOffs, shopify: plan.shopifyDecrement })}
            </p>
            {plan.byVariant.length > 0 && (
              <div className="rounded-lg border border-line bg-elevated p-3 space-y-1.5" data-testid="finalize-variants">
                {plan.byVariant.map(v => (
                  <div key={v.variantId} className="flex items-center justify-between gap-3 text-small">
                    <span className="text-muted">{v.variantTitle}</span>
                    <span className="font-mono text-danger">
                      {t('stocktake.review.finalize.variantLine', {
                        writeOffs: v.available + v.damaged + v.onHold, shopify: v.shopifyDecrement,
                      })}
                    </span>
                  </div>
                ))}
              </div>
            )}
            {untaggedRows.length > 0 && (
              <div className="rounded-lg border border-line bg-elevated p-3 space-y-1.5" data-testid="finalize-untagged">
                {untaggedRows.map(r => (
                  <div key={r.variantId} className="flex items-center justify-between gap-3 text-small">
                    <span className="text-muted">{r.variantTitle}</span>
                    {r.writeOff > 0 ? (
                      <span className="font-mono text-danger">{t('stocktake.review.finalize.untaggedWriteOff', { count: r.writeOff })}</span>
                    ) : (
                      <span className="text-info">{t('stocktake.review.finalize.untaggedExtra', { count: r.extra })}</span>
                    )}
                  </div>
                ))}
              </div>
            )}
            {tracedOnly > 0 && (
              <p className="text-small text-muted">{t('stocktake.review.finalize.tracedOnly', { count: tracedOnly })}</p>
            )}
            {plan.damageCorrections > 0 && (
              <p className="text-small text-muted">{t('stocktake.review.finalize.damageCorrections', { count: plan.damageCorrections })}</p>
            )}
            {plan.founds > 0 && (
              <p className="text-small text-muted">
                {t('stocktake.review.finalize.founds', { count: plan.founds, shopify: plan.foundIncrements })}
              </p>
            )}
            {plan.driftSkipped > 0 && (
              <p className="text-small text-muted">{t('stocktake.review.finalize.driftSkipped', { count: plan.driftSkipped })}</p>
            )}
            {plan.requiresTypedConfirmation && (
              <label className="block space-y-1.5">
                <span className="text-small text-warning font-medium">
                  {t('stocktake.review.finalize.typedPrompt', {
                    coverage: plan.coveragePercent, minCoverage: plan.minCoveragePercent,
                    maxShare: plan.maxWriteOffPercent, count: plan.writeOffs,
                  })}
                </span>
                <Input
                  className="font-mono"
                  inputMode="numeric"
                  value={typedCount}
                  onChange={e => onTypedCount(e.target.value)}
                  aria-label={t('stocktake.review.finalize.typedLabel')}
                  data-testid="finalize-typed-count"
                />
              </label>
            )}
          </>
        )}
        {!blocked && (
          <Checkbox checked={understood} onChange={onUnderstood} label={t('stocktake.review.finalizeUnderstand')} />
        )}
        <div className="flex gap-3 justify-end">
          <Button variant="secondary" onClick={onCancel}>{t('common.cancel')}</Button>
          <Button
            variant="destructive"
            loading={finalizing}
            disabled={!!blocked || !understood || !typedOk}
            onClick={onFinalize}
          >
            {t('stocktake.review.finalizeConfirmButton')}
          </Button>
        </div>
      </div>
    </Modal>
  )
}

/** Every labeled piece finalize writes off, with its Shopify effect — above the totals. More than
 *  GROUP_OVER pieces → grouped by product / variant (codes listed per group). */
const GROUP_OVER = 10

function WriteOffPieceList({ pieces }: { pieces: StockTakeWriteOffPiece[] }) {
  const { t } = useTranslation()
  const effect = (p: StockTakeWriteOffPiece) => p.shopifyEffect < 0
    ? t('stocktake.review.writeOffList.shopifyMinus', { count: -p.shopifyEffect })
    : t('stocktake.review.writeOffList.tracedOnly')

  if (pieces.length <= GROUP_OVER) {
    return (
      <div className="space-y-1.5" data-testid="finalize-pieces">
        <p className="text-small font-semibold text-primary">{t('stocktake.review.writeOffList.title', { count: pieces.length })}</p>
        <ul className="rounded-lg border border-line bg-elevated divide-y divide-line max-h-64 overflow-y-auto">
          {pieces.map(p => (
            <li key={p.pieceId} className="flex items-center gap-3 px-3 py-2 text-small" data-testid="finalize-piece">
              <span className="font-mono text-primary" dir="ltr">{p.shortCode ?? p.pieceId.slice(-8)}</span>
              <span className="text-muted flex-1 min-w-0 truncate" dir="auto">{p.productTitle} · {p.variantTitle}</span>
              <span className={`font-mono whitespace-nowrap ${p.shopifyEffect < 0 ? 'text-danger' : 'text-muted'}`}>{effect(p)}</span>
            </li>
          ))}
        </ul>
      </div>
    )
  }

  const groups = new Map<string, StockTakeWriteOffPiece[]>()
  for (const p of pieces) groups.set(p.variantId, [...(groups.get(p.variantId) ?? []), p])
  return (
    <div className="space-y-1.5" data-testid="finalize-pieces">
      <p className="text-small font-semibold text-primary">{t('stocktake.review.writeOffList.title', { count: pieces.length })}</p>
      <div className="rounded-lg border border-line bg-elevated divide-y divide-line max-h-72 overflow-y-auto">
        {[...groups.values()].map(g => {
          const minus = g.reduce((n, p) => n - p.shopifyEffect, 0)
          return (
            <div key={g[0].variantId} className="px-3 py-2 space-y-1" data-testid="finalize-piece-group">
              <div className="flex items-center justify-between gap-3 text-small">
                <span className="text-primary font-medium min-w-0 truncate" dir="auto">
                  {g[0].productTitle} · {g[0].variantTitle} <span className="text-muted">({g.length})</span>
                </span>
                <span className={`font-mono whitespace-nowrap ${minus > 0 ? 'text-danger' : 'text-muted'}`}>
                  {minus > 0 ? t('stocktake.review.writeOffList.shopifyMinus', { count: minus }) : t('stocktake.review.writeOffList.tracedOnly')}
                </span>
              </div>
              <p className="font-mono text-caption text-muted break-words" dir="ltr">
                {g.map(p => p.shortCode ?? p.pieceId.slice(-8)).join(' · ')}
              </p>
            </div>
          )
        })}
      </div>
    </div>
  )
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
  // Written off = every write-off in Traced; pushed = what actually reached Shopify (damaged /
  // on-hold write-offs never do, and a push that hasn't applied counts 0).
  const writtenOff = session.writtenOff
    ?? (session.shopifySync?.deltas ?? []).reduce((sum, d) => sum + Math.abs(d.delta), 0)
  const pushedToShopify = session.pushedToShopify ?? 0

  return (
    <Card className="flex flex-col items-center gap-3 text-center py-8" data-testid="close-summary">
      <ClipboardCheck size={40} strokeWidth={1.75} className="text-success" />
      <h2 className="text-h2 text-primary">{t('stocktake.review.closeSummary.title')}</h2>
      <div className="flex gap-6 mt-1">
        <SummaryStat value={totalCounted} label={t('stocktake.review.closeSummary.counted')} />
        <SummaryStat value={totalVariance} label={t('stocktake.review.closeSummary.variance')} tone={totalVariance > 0 ? 'danger' : 'success'} />
        <SummaryStat value={writtenOff} label={t('stocktake.review.closeSummary.writtenOff')} tone={writtenOff > 0 ? 'danger' : 'success'} />
        <SummaryStat value={pushedToShopify} label={t('stocktake.review.closeSummary.pushedToShopify')} />
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
  bucketKey, rows, sessionOpen, onChanged, sessionId,
}: {
  bucketKey: BucketKey
  rows: StockTakePieceRow[]
  sessionOpen: boolean
  onChanged: () => void
  sessionId: string
}) {
  const { t, i18n } = useTranslation()
  const [expanded, setExpanded] = useState(true)
  const [busy, setBusy] = useState<string | null>(null)
  const [rowError, setRowError] = useState<Record<string, string>>({})

  const isFreeUncounted = bucketKey === 'on_shelf_uncounted'

  // The one row action left: Apply damaged on a scanned-damaged / recorded-available row.
  async function applyDamaged(pieceId: string) {
    if (!sessionOpen) return
    setBusy(pieceId)
    setRowError(prev => ({ ...prev, [pieceId]: '' }))
    try {
      await resolveStockTake(sessionId, [{ pieceId, action: 'mark_damaged' }])
      onChanged()
    } catch (e: unknown) {
      setRowError(prev => ({ ...prev, [pieceId]: stockTakeErrorText(e, t, i18n.language) }))
    } finally {
      setBusy(null)
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
            {isFreeUncounted && sessionOpen && (
              <p className="text-small text-muted" data-testid="uncounted-hint">{t('stocktake.review.uncountedHint')}</p>
            )}

            {rows.map(row => (
              <div key={row.pieceId} className="rounded-lg border border-line p-3 space-y-2" data-testid={`row-${row.pieceId}`}>
                <div className="flex items-center justify-between gap-3 flex-wrap">
                  <div>
                    {row.shortCode && <span className="font-mono text-small text-primary me-2" dir="ltr">{row.shortCode}</span>}
                    <Link to={`/lookup?q=${encodeURIComponent(row.pieceId)}`} className="text-trace-blue hover:underline text-body font-medium">
                      {row.productTitle} · {row.variantTitle}
                    </Link>
                    {row.sku && <span className="font-mono text-caption text-muted ms-2" dir="ltr">{row.sku}</span>}
                    {row.scanDevice === 'phone' && <span className="ms-2"><ViaPhoneTag /></span>}
                  </div>

                  <div className="flex items-center gap-2 flex-wrap">
                    {row.orderNumber && (
                      <Link to={`/orders/${row.orderId}`} className="btn-outline btn text-caption font-mono">
                        #{row.orderNumber}
                      </Link>
                    )}
                    {row.trackingNumber && (
                      <span className="font-mono text-caption text-muted" dir="ltr">{row.trackingNumber}</span>
                    )}

                    {isFreeUncounted && row.flag === 'condition_mismatch' && row.liveStatus === 'available' && sessionOpen && (
                      <Button size="sm" variant="secondary" loading={busy === row.pieceId}
                        onClick={() => applyDamaged(row.pieceId)}>
                        {t('stocktake.review.applyDamaged')}
                      </Button>
                    )}
                    {isFreeUncounted && row.flag === 'condition_mismatch' && row.liveStatus === 'damaged' && (
                      <span className="text-caption text-muted" title={t('stocktake.review.terminalTooltip')}>
                        {t('stocktake.review.terminalFlag')}
                      </span>
                    )}
                  </div>
                </div>

                {rowError[row.pieceId] && <Alert tone="critical" title={rowError[row.pieceId]} />}
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
  const { t, i18n } = useTranslation()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleMarkResolved() {
    setBusy(true); setError(null)
    try { await markStockTakeSyncResolved(sessionId); await onChanged() }
    catch (e: unknown) { setError(stockTakeErrorText(e, t, i18n.language)) }
    finally { setBusy(false) }
  }

  async function handleRepush() {
    setBusy(true); setError(null)
    try { await repushStockTakeSync(sessionId); await onChanged() }
    catch (e: unknown) { setError(stockTakeErrorText(e, t, i18n.language)) }
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

  if (shopifySync.status === 'nothing_to_push') {
    return <Alert tone="info" title={t('stocktake.sync.nothingToPush')} />
  }

  if (shopifySync.status === 'superseded_by_seed') {
    return <Alert tone="info" title={t('stocktake.sync.superseded')} />
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
