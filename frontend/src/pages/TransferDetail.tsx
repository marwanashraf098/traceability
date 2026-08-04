import { useState, useEffect, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
  Badge, Button, Card, Spinner, Alert, useToast,
} from '../components/ui'
import {
  getTransfer, getRoleFromToken, beginReconcileTransfer, reprintTransferOutstanding,
  TransferCommandError, TransferDetail as TransferDetailType,
} from '../api'

// FR-22.9 — Transfer detail: header + lines table + status-gated actions
// (scan-out-more / begin-reconcile / reprint-outstanding / continue-reconcile).

function tone(status: string): 'warning' | 'success' | 'neutral' {
  if (status === 'open' || status === 'reconciling') return 'warning'
  return 'neutral'
}

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
            {transfer.destination_location_name}
            <Badge tone={tone(transfer.status)} label={t(`transfers.status.${transfer.status}`)} />
          </h1>
          <p className="text-small text-muted mt-1">
            {t(`transfers.type.${transfer.transfer_type}`)}
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

      {/* Lines */}
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

      {/* Actions */}
      {transfer.status !== 'closed' && (
        <Card className="flex flex-wrap gap-3">
          {transfer.status === 'open' && (
            <Button onClick={() => navigate(`/transfers/${id}/scan-out`)}>
              {t('transfers.detail.scanOutMore')}
            </Button>
          )}

          {canManage && transfer.status === 'open' && (
            <span title={outstanding === 0 ? t('transfers.detail.beginReconcileDisabledTooltip') : undefined}>
              <Button variant="secondary" loading={beginning} disabled={outstanding === 0} onClick={handleBeginReconcile}>
                {t('transfers.detail.beginReconcile')}
              </Button>
            </span>
          )}

          {canManage && transfer.status === 'reconciling' && (
            <Button variant="secondary" onClick={() => navigate(`/transfers/${id}/reconcile`)}>
              {t('transfers.detail.continueReconcile')}
            </Button>
          )}

          {canManage && (
            <span title={outstanding === 0 ? t('transfers.detail.reprintNoneTooltip') : undefined}>
              <Button variant="secondary" loading={reprinting} disabled={outstanding === 0} onClick={handleReprint}>
                {t('transfers.detail.reprintOutstanding')}
              </Button>
            </span>
          )}
        </Card>
      )}
    </div>
  )
}
