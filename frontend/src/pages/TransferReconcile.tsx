import { useState, useEffect, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useScanner } from '../hooks/useScanner'
import { ScanShell } from '../components/ScanShell'
import {
  Alert, Badge, Button, Card, Input, Modal, Spinner,
} from '../components/ui'
import {
  getTransfer, getRoleFromToken, scanBackTransferPiece, classifyTransferShortfall,
  closeTransferSession, TransferCommandError, TransferCondition,
  TransferDetail as TransferDetailType, TransferLine,
} from '../api'

// FR-22.9 — Reconcile screen (MANAGER/OWNER only): scan-back (good/condemned)
// + per-line quantity-based classify, with a live running balance. Close is
// disabled until every line balances (outstandingCount === 0, the same
// authoritative check closeTransfer() itself makes server-side).

interface ShortfallInputs { sold: string; lost: string; condemnedNotReturned: string }

function emptyInputs(): ShortfallInputs {
  return { sold: '', lost: '', condemnedNotReturned: '' }
}

function lineOutstanding(line: TransferLine): number {
  return line.qty_out - line.qty_returned_good - line.qty_condemned - line.qty_sold - line.qty_lost
}

export default function TransferReconcile() {
  const { id } = useParams<{ id: string }>()
  const { t, i18n } = useTranslation()
  const isAr = i18n.language === 'ar'
  const navigate = useNavigate()
  const role = getRoleFromToken()
  const canManage = role === 'owner' || role === 'manager'

  const [transfer, setTransfer] = useState<TransferDetailType | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [condition, setCondition] = useState<TransferCondition>('good')
  const [shortfallInputs, setShortfallInputs] = useState<Record<string, ShortfallInputs>>({})
  const [classifyBusy, setClassifyBusy] = useState<string | null>(null)
  const [classifyErrors, setClassifyErrors] = useState<Record<string, string>>({})
  const [showCloseConfirm, setShowCloseConfirm] = useState(false)
  const [closing, setClosing] = useState(false)
  const [closeError, setCloseError] = useState<string | null>(null)

  const load = useCallback(async () => {
    if (!id) return
    try { setTransfer(await getTransfer(id)) }
    catch (e: unknown) { setError(e instanceof Error ? e.message : String(e)) }
  }, [id])

  useEffect(() => { load().finally(() => setLoading(false)) }, [load])

  const scanner = useScanner({
    onScan: async (barcode) => {
      if (!id) return { success: false }
      const result = await scanBackTransferPiece(id, barcode, condition)
      if (result.success) {
        await load()
        return { success: true, label: t(`transfers.reconcile.${condition}`) }
      }
      const message = isAr ? result.message_ar : result.message_en
      return { success: false, label: message ?? result.code }
    },
  })

  function setLineInput(lineId: string, field: keyof ShortfallInputs, value: string) {
    setShortfallInputs(prev => ({
      ...prev,
      [lineId]: { ...(prev[lineId] ?? emptyInputs()), [field]: value },
    }))
  }

  async function handleClassify(line: TransferLine) {
    if (!id) return
    const inputs = shortfallInputs[line.id] ?? emptyInputs()
    const counts = {
      sold: parseInt(inputs.sold, 10) || 0,
      lost: parseInt(inputs.lost, 10) || 0,
      condemnedNotReturned: parseInt(inputs.condemnedNotReturned, 10) || 0,
    }
    setClassifyBusy(line.id)
    setClassifyErrors(prev => ({ ...prev, [line.id]: '' }))
    try {
      await classifyTransferShortfall(id, line.id, counts)
      setShortfallInputs(prev => ({ ...prev, [line.id]: emptyInputs() }))
      await load()
    } catch (e: unknown) {
      const msg = e instanceof TransferCommandError ? (isAr ? e.messageAr : e.messageEn)
        : e instanceof Error ? e.message : String(e)
      setClassifyErrors(prev => ({ ...prev, [line.id]: msg }))
    } finally {
      setClassifyBusy(null)
    }
  }

  async function handleClose() {
    if (!id) return
    setClosing(true); setCloseError(null)
    try {
      await closeTransferSession(id)
      setShowCloseConfirm(false)
      navigate(`/transfers/${id}`)
    } catch (e: unknown) {
      const msg = e instanceof TransferCommandError ? (isAr ? e.messageAr : e.messageEn)
        : e instanceof Error ? e.message : String(e)
      setCloseError(msg)
    } finally {
      setClosing(false)
    }
  }

  if (loading) {
    return <div className="flex justify-center py-16"><Spinner size={28} /></div>
  }
  if (!transfer || !id) {
    return <Alert tone="critical" title={error ?? t('transfers.reconcile.notFound')} />
  }
  if (!canManage) {
    return <Alert tone="critical" title={t('transfers.reconcile.accessDenied')} />
  }
  if (transfer.status === 'open') {
    return (
      <div className="space-y-4">
        <Alert tone="warning" title={t('transfers.reconcile.notReconcilingTitle')} />
        <Button variant="secondary" onClick={() => navigate(`/transfers/${id}`)}>
          {t('transfers.reconcile.goToDetail')}
        </Button>
      </div>
    )
  }
  if (transfer.status === 'closed') {
    return (
      <div className="space-y-4">
        <Alert tone="info" title={t('transfers.reconcile.closedRedirect')} />
        <Button variant="secondary" onClick={() => navigate(`/transfers/${id}`)}>
          {t('transfers.reconcile.goToDetail')}
        </Button>
      </div>
    )
  }

  const allBalanced = transfer.outstandingCount === 0

  return (
    <div className="space-y-4">
      <button onClick={() => navigate(`/transfers/${id}`)} className="text-small text-trace-blue hover:text-trace-blue-hover transition-colors">
        ← {t('transfers.reconcile.back')}
      </button>

      <div className="flex items-center justify-between flex-wrap gap-3">
        <h1 className="text-h1 text-primary flex items-center gap-2">
          {t('transfers.reconcile.title')}
          <Badge tone="warning" label={transfer.destination_location_name} />
        </h1>
        <div className="text-end">
          <p className="text-caption uppercase tracking-widest text-muted">{t('transfers.detail.outstanding')}</p>
          <p className={`text-h2 font-mono ${allBalanced ? 'text-success' : 'text-warning'}`} data-testid="reconcile-outstanding">
            {transfer.outstandingCount}
          </p>
        </div>
      </div>

      {/* Scan back */}
      <Card className="space-y-3">
        <div className="flex items-center justify-between flex-wrap gap-2">
          <h2 className="text-body font-semibold text-primary">{t('transfers.reconcile.scanBackTitle')}</h2>
          <div className="flex items-center gap-2">
            <span className="text-small text-muted">{t('transfers.reconcile.conditionLabel')}</span>
            <button
              type="button"
              data-testid="condition-good-btn"
              onClick={() => setCondition('good')}
              className={`px-4 py-2 rounded-lg text-small font-semibold border transition-colors ${
                condition === 'good' ? 'bg-success text-white border-success' : 'border-line text-muted hover:border-success'
              }`}
            >
              {t('transfers.reconcile.good')}
            </button>
            <button
              type="button"
              data-testid="condition-condemned-btn"
              onClick={() => setCondition('condemned')}
              className={`px-4 py-2 rounded-lg text-small font-semibold border transition-colors ${
                condition === 'condemned' ? 'bg-danger text-white border-danger' : 'border-line text-muted hover:border-danger'
              }`}
            >
              {t('transfers.reconcile.condemned')}
            </button>
          </div>
        </div>

        <ScanShell scanner={scanner} placeholder={t('transfers.reconcile.placeholder')} />
        {scanner.recentScans[0] && (
          <div
            data-testid="last-scan-feedback"
            className={`text-small font-medium ${scanner.recentScans[0].success ? 'text-success' : 'text-danger'}`}
          >
            {scanner.recentScans[0].success ? '✓ ' : '✗ '}
            <span className="font-mono">{scanner.recentScans[0].barcode}</span>
            {' — '}{scanner.recentScans[0].label}
          </div>
        )}

        {scanner.recentScans.length > 0 && (
          <div className="space-y-1.5">
            <h3 className="text-caption text-muted uppercase tracking-widest">{t('transfers.reconcile.recent')}</h3>
            {scanner.recentScans.slice(0, 5).map(entry => (
              <div key={entry.key} className="flex items-center justify-between bg-elevated rounded border border-line px-3 py-1.5">
                <span className="font-mono text-small text-primary">{entry.barcode.slice(-10)}</span>
                <span className={`text-caption ${entry.success ? 'text-success' : 'text-danger'}`}>{entry.label}</span>
              </div>
            ))}
          </div>
        )}
      </Card>

      {/* Per-line classify + live balance */}
      <Card className="space-y-3">
        <h2 className="text-body font-semibold text-primary">{t('transfers.reconcile.linesTitle')}</h2>
        <div className="overflow-x-auto">
          <table className="min-w-full">
            <thead>
              <tr className="border-b border-line">
                {['variant', 'qtyOut', 'accounted', 'outstanding', 'sold', 'lost', 'condemnedNotReturned'].map(k => (
                  <th key={k} className="tbl-header">{t(`transfers.reconcile.col.${k}`)}</th>
                ))}
                <th className="tbl-header" />
              </tr>
            </thead>
            <tbody>
              {transfer.lines.map(line => {
                const outstanding = lineOutstanding(line)
                const accounted = line.qty_out - outstanding
                const inputs = shortfallInputs[line.id] ?? emptyInputs()
                const requested = (parseInt(inputs.sold, 10) || 0)
                  + (parseInt(inputs.lost, 10) || 0)
                  + (parseInt(inputs.condemnedNotReturned, 10) || 0)
                const canSubmit = requested > 0 && requested <= outstanding
                return (
                  <tr key={line.id} className="tbl-row align-top">
                    <td className="tbl-cell text-primary">
                      {line.product_title} · {line.variant_title}
                      {line.sku && <span className="font-mono text-caption text-muted ms-2">{line.sku}</span>}
                    </td>
                    <td className="tbl-cell text-primary">{line.qty_out}</td>
                    <td className="tbl-cell text-muted">{accounted}</td>
                    <td className="tbl-cell">
                      {outstanding === 0
                        ? <Badge tone="success" label={t('transfers.reconcile.balanced')} />
                        : <span className="font-semibold text-warning">{outstanding}</span>}
                    </td>
                    {(['sold', 'lost', 'condemnedNotReturned'] as const).map(field => (
                      <td key={field} className="tbl-cell">
                        <Input
                          data-testid={`shortfall-${field}-${line.id}`}
                          type="number"
                          min={0}
                          disabled={outstanding === 0}
                          value={inputs[field]}
                          onChange={e => setLineInput(line.id, field, e.target.value)}
                          className="w-16"
                        />
                      </td>
                    ))}
                    <td className="tbl-cell">
                      <Button
                        size="sm"
                        variant="secondary"
                        disabled={!canSubmit}
                        loading={classifyBusy === line.id}
                        onClick={() => handleClassify(line)}
                      >
                        {t('transfers.reconcile.classifySubmit')}
                      </Button>
                      {classifyErrors[line.id] && (
                        <p className="text-caption text-danger mt-1 max-w-[16rem]">{classifyErrors[line.id]}</p>
                      )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      </Card>

      {/* Close */}
      <Card className="flex items-center justify-between flex-wrap gap-3">
        <div>
          {!allBalanced && <p className="text-small text-muted">{t('transfers.reconcile.closeDisabledTooltip')}</p>}
          {closeError && <Alert tone="critical" title={closeError} />}
        </div>
        <Button variant="destructive" disabled={!allBalanced} onClick={() => setShowCloseConfirm(true)}>
          {t('transfers.reconcile.closeButton')}
        </Button>
      </Card>

      {showCloseConfirm && (
        <Modal title={t('transfers.reconcile.closeConfirmTitle')} onClose={() => setShowCloseConfirm(false)}>
          <div className="space-y-4">
            <p className="text-body text-primary">{t('transfers.reconcile.closeConfirmBody')}</p>
            <div className="flex gap-3 justify-end">
              <Button variant="secondary" onClick={() => setShowCloseConfirm(false)}>{t('common.cancel')}</Button>
              <Button variant="destructive" loading={closing} onClick={handleClose}>
                {t('transfers.reconcile.closeConfirmButton')}
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  )
}
