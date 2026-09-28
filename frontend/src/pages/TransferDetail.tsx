import { useState, useEffect, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
  Badge, Button, Card, Modal, Spinner, Alert, useToast,
} from '../components/ui'
import {
  getTransfer, getRoleFromToken, beginReconcileTransfer, closeOneWayTransfer, markTransferSent,
  cancelTransfer, reprintTransferOutstanding, TransferCommandError, TransferDetail as TransferDetailType,
} from '../api'
import { transferStatusTone, typeLabel } from './Transfers'

// FR-22.9 — Transfer detail: header + lines table + status-gated actions.
//   preparing   — Scan out more; Mark as sent (round trip / bring back); Close (move);
//                 Cancel (only while nothing was ever scanned)
//   sent        — Begin Reconcile, Reprint
//   reconciling — Continue reconcile, Reprint
//   closed / cancelled — read-only banner

export default function TransferDetail() {
  const { id } = useParams<{ id: string }>()
  const { t, i18n } = useTranslation()
  const isAr = i18n.language === 'ar'
  const navigate = useNavigate()
  const { toast } = useToast()
  const role = getRoleFromToken()
  const canManage = role === 'owner' || role === 'manager'

  const [transfer, setTransfer] = useState<TransferDetailType | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [beginning, setBeginning] = useState(false)
  const [reprinting, setReprinting] = useState(false)
  const [showCloseOneWayConfirm, setShowCloseOneWayConfirm] = useState(false)
  const [closingOneWay, setClosingOneWay] = useState(false)
  const [closeOneWayError, setCloseOneWayError] = useState<string | null>(null)
  const [markingSent, setMarkingSent] = useState(false)
  const [showCancelConfirm, setShowCancelConfirm] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const [cancelError, setCancelError] = useState<string | null>(null)

  const load = useCallback(async () => {
    if (!id) return
    try { setTransfer(await getTransfer(id)) }
    catch (e: unknown) { setError(e instanceof Error ? e.message : String(e)) }
    finally { setLoading(false) }
  }, [id])

  useEffect(() => { load() }, [load])

  async function handleBeginReconcile() {
    if (!id) return
    setBeginning(true)
    try {
      await beginReconcileTransfer(id)
      navigate(`/transfers/${id}/reconcile`)
    } catch (e: unknown) {
      const msg = e instanceof TransferCommandError ? (isAr ? e.messageAr : e.messageEn)
        : e instanceof Error ? e.message : String(e)
      toast({ tone: 'error', message: msg })
    } finally {
      setBeginning(false)
    }
  }

  function commandMessage(e: unknown): string {
    return e instanceof TransferCommandError ? (isAr ? e.messageAr : e.messageEn)
      : e instanceof Error ? e.message : String(e)
  }

  async function handleMarkSent() {
    if (!id) return
    setMarkingSent(true)
    try {
      await markTransferSent(id)
      toast({ tone: 'success', message: t('transfers.detail.markSentDone') })
      await load()
    } catch (e: unknown) {
      toast({ tone: 'error', message: commandMessage(e) })
    } finally {
      setMarkingSent(false)
    }
  }

  async function handleCancel() {
    if (!id) return
    setCancelling(true); setCancelError(null)
    try {
      await cancelTransfer(id)
      setShowCancelConfirm(false)
      await load()
    } catch (e: unknown) {
      setCancelError(commandMessage(e))
    } finally {
      setCancelling(false)
    }
  }

  async function handleCloseOneWay() {
    if (!id) return
    setClosingOneWay(true); setCloseOneWayError(null)
    try {
      await closeOneWayTransfer(id)
      setShowCloseOneWayConfirm(false)
      await load()
    } catch (e: unknown) {
      const msg = e instanceof TransferCommandError ? (isAr ? e.messageAr : e.messageEn)
        : e instanceof Error ? e.message : String(e)
      setCloseOneWayError(msg)
    } finally {
      setClosingOneWay(false)
    }
  }

  async function handleReprint() {
    if (!id) return
    setReprinting(true)
    try {
      await reprintTransferOutstanding(id)
    } catch (e: unknown) {
      const msg = e instanceof TransferCommandError ? (isAr ? e.messageAr : e.messageEn)
        : e instanceof Error ? e.message : String(e)
      toast({ tone: 'error', message: msg })
    } finally {
      setReprinting(false)
    }
  }

  if (loading) {
    return <div className="flex justify-center py-16"><Spinner size={28} /></div>
  }
  if (!transfer || !id) {
    return <Alert tone="critical" title={error ?? t('transfers.detail.notFound')} />
  }

  const outstanding = transfer.outstandingCount
  const piecesEver = transfer.piecesEverCount
  const preparing = transfer.status === 'preparing'
  const sent = transfer.status === 'sent'
  const reconciling = transfer.status === 'reconciling'
  const returning = transfer.transfer_mode === 'round_trip' || transfer.transfer_mode === 'relocate_return'

  return (
    <div className="space-y-4">
      <button onClick={() => navigate('/transfers')} className="text-small text-trace-blue hover:text-trace-blue-hover transition-colors">
        ← {t('transfers.detail.back')}
      </button>

      {error && <Alert tone="critical" title={error} />}

      {/* Header */}
      <div className="flex items-start justify-between flex-wrap gap-3">
        <div>
          <h1 className="text-h1 text-primary flex items-center gap-2">
            {transfer.transfer_mode === 'relocate_return' && transfer.source_location_name
              ? `${transfer.source_location_name} → ${transfer.destination_location_name}`
              : transfer.destination_location_name}
            <Badge tone={transferStatusTone(transfer.status)} label={t(`transfers.status.${transfer.status}`)} />
          </h1>
          <p className="text-small text-muted mt-1">
            {typeLabel(transfer, t)}
            {transfer.expected_return_at && (
              <> · {t('transfers.detail.expectedReturn')}: {new Date(transfer.expected_return_at).toLocaleDateString()}</>
            )}
          </p>
          {transfer.note && <p className="text-small text-muted mt-1">{t('transfers.detail.note')}: {transfer.note}</p>}
        </div>
        <div className="text-end">
          <p className="text-caption uppercase tracking-widest text-muted">{t('transfers.detail.outstanding')}</p>
          <p className="text-h2 font-mono text-primary" data-testid="outstanding-headline">{outstanding}</p>
        </div>
      </div>

      {transfer.status === 'closed' && (
        <Alert tone="info" title={t('transfers.detail.closedTitle')} />
      )}
      {transfer.status === 'cancelled' && (
        <Alert tone="info" title={t('transfers.detail.cancelledTitle')} />
      )}

      {/* Lines — relocate_out gets a reduced, relocation-framed table (no reconcile
          concepts: a one-way relocate has no returned/condemned/sold/lost, those
          columns stay permanently 0 and would be meaningless clutter). Round-trip
          renders the full existing table, byte-identical to before. A cancelled transfer never
          had a piece scanned, so it shows no table — the banner says it all. */}
      {transfer.status === 'cancelled' ? null : transfer.transfer_mode === 'relocate_out' ? (
        // Card (components/ui.tsx) only destructures children/className/interactive/
        // hoverable — it does not forward arbitrary props, so data-testid must live on a
        // plain element this component controls directly, not on <Card> itself.
        <div data-testid="relocate-lines-card">
          <Card className="space-y-3">
            <h2 className="text-body font-semibold text-primary">{t('transfers.detail.piecesTitle')}</h2>
            <div className="overflow-x-auto">
              <table className="min-w-full">
                <thead>
                  <tr className="border-b border-line">
                    {['variant', 'sent', 'outstanding'].map(k => (
                      <th key={k} className="tbl-header">{t(`transfers.detail.col.${k}`)}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {transfer.lines.map(line => {
                    // Relocate close is all-or-nothing at the transfer level (closeOneWay()
                    // resolves every outstanding piece across every line in one shot — there
                    // is no partial per-line close state), so transfer.status alone — already
                    // in the response — correctly derives per-line outstanding without the
                    // round-trip reconcile-counter formula (which would stay stuck at
                    // qty_out forever, since relocate never touches those counters).
                    const lineOutstanding = transfer.status === 'closed' ? 0 : line.qty_out
                    return (
                      <tr key={line.id} className="tbl-row">
                        <td className="tbl-cell text-primary">
                          {line.product_title} · {line.variant_title}
                          {line.sku && <span className="font-mono text-caption text-muted ms-2">{line.sku}</span>}
                        </td>
                        <td className="tbl-cell text-primary">{line.qty_out}</td>
                        <td className={`tbl-cell font-semibold ${lineOutstanding > 0 ? 'text-warning' : 'text-success'}`}>{lineOutstanding}</td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          </Card>
        </div>
      ) : (
        <Card className="space-y-3">
          <h2 className="text-body font-semibold text-primary">{t('transfers.detail.linesTitle')}</h2>
          <div className="overflow-x-auto">
            <table className="min-w-full">
              <thead>
                <tr className="border-b border-line">
                  {['variant', 'qtyOut', 'returnedGood', 'condemned', 'sold', 'lost', 'outstanding'].map(k => (
                    <th key={k} className="tbl-header">{t(`transfers.detail.col.${k}`)}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {transfer.lines.map(line => {
                  const lineOutstanding = line.qty_out - line.qty_returned_good - line.qty_condemned - line.qty_sold - line.qty_lost
                  return (
                    <tr key={line.id} className="tbl-row">
                      <td className="tbl-cell text-primary">
                        {line.product_title} · {line.variant_title}
                        {line.sku && <span className="font-mono text-caption text-muted ms-2">{line.sku}</span>}
                      </td>
                      <td className="tbl-cell text-primary">{line.qty_out}</td>
                      <td className="tbl-cell text-muted">{line.qty_returned_good}</td>
                      <td className="tbl-cell text-muted">{line.qty_condemned}</td>
                      <td className="tbl-cell text-muted">{line.qty_sold}</td>
                      <td className="tbl-cell text-muted">{line.qty_lost}</td>
                      <td className={`tbl-cell font-semibold ${lineOutstanding > 0 ? 'text-warning' : 'text-success'}`}>{lineOutstanding}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {/* Actions */}
      {(preparing || sent || reconciling) && (
        <div data-testid="transfer-actions">
          <Card className="flex flex-wrap gap-3">
            {preparing && (
              <Button onClick={() => navigate(`/transfers/${id}/scan-out`)}>
                {t('transfers.detail.scanOutMore')}
              </Button>
            )}

            {canManage && preparing && returning && (
              <span title={piecesEver === 0 ? t('transfers.detail.markSentDisabledTooltip') : undefined}>
                <Button variant="secondary" loading={markingSent} disabled={piecesEver === 0} onClick={handleMarkSent}>
                  {t('transfers.detail.markSent')}
                </Button>
              </span>
            )}

            {/* relocate_out: one-shot close, no reconcile stage (a move never reaches sent). */}
            {canManage && preparing && transfer.transfer_mode === 'relocate_out' && (
              <span title={outstanding === 0 ? t('transfers.detail.closeOneWayDisabledTooltip') : undefined}>
                <Button variant="destructive" disabled={outstanding === 0} onClick={() => setShowCloseOneWayConfirm(true)}>
                  {t('transfers.detail.closeOneWay')}
                </Button>
              </span>
            )}

            {canManage && preparing && piecesEver === 0 && (
              <Button variant="secondary" onClick={() => { setCancelError(null); setShowCancelConfirm(true) }}>
                {t('transfers.detail.cancelTransfer')}
              </Button>
            )}

            {canManage && sent && (
              <Button variant="secondary" loading={beginning} onClick={handleBeginReconcile}>
                {t('transfers.detail.beginReconcile')}
              </Button>
            )}

            {canManage && reconciling && (
              <Button variant="secondary" onClick={() => navigate(`/transfers/${id}/reconcile`)}>
                {t('transfers.detail.continueReconcile')}
              </Button>
            )}

            {canManage && (sent || reconciling) && (
              <span title={outstanding === 0 ? t('transfers.detail.reprintNoneTooltip') : undefined}>
                <Button variant="secondary" loading={reprinting} disabled={outstanding === 0} onClick={handleReprint}>
                  {t('transfers.detail.reprintOutstanding')}
                </Button>
              </span>
            )}
          </Card>
        </div>
      )}

      {closeOneWayError && <Alert tone="critical" title={closeOneWayError} />}

      {showCancelConfirm && (
        <Modal title={t('transfers.detail.cancelTransfer')} onClose={() => setShowCancelConfirm(false)}>
          <div className="space-y-4">
            <p className="text-body text-primary">{t('transfers.detail.cancelConfirmBody')}</p>
            {cancelError && <Alert tone="critical" title={cancelError} />}
            <div className="flex gap-3 justify-end">
              <Button variant="secondary" onClick={() => setShowCancelConfirm(false)}>{t('transfers.detail.cancelKeep')}</Button>
              <Button variant="destructive" loading={cancelling} onClick={handleCancel}>
                {t('transfers.detail.cancelTransfer')}
              </Button>
            </div>
          </div>
        </Modal>
      )}

      {showCloseOneWayConfirm && (
        <Modal title={t('transfers.detail.closeOneWayConfirmTitle')} onClose={() => setShowCloseOneWayConfirm(false)}>
          <div className="space-y-4">
            <p className="text-body text-primary">{t('transfers.detail.closeOneWayConfirmBody')}</p>
            <div className="flex gap-3 justify-end">
              <Button variant="secondary" onClick={() => setShowCloseOneWayConfirm(false)}>{t('common.cancel')}</Button>
              <Button variant="destructive" loading={closingOneWay} onClick={handleCloseOneWay}>
                {t('transfers.detail.closeOneWayConfirmButton')}
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  )
}
