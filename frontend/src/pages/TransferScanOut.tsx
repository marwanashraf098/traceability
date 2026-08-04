import { useEffect, useState, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useScanner } from '../hooks/useScanner'
import { ScanShell } from '../components/ScanShell'
import { Button, Spinner } from '../components/ui'
import { getTransfer, scanOutTransferPiece, TransferDetail } from '../api'

// FR-22.9 — Send-out scan screen (open state only). Same scan UX as the pick
// screen / StockTakeScan.tsx: useScanner + ScanShell own all scan mechanics,
// this component only supplies the onScan callback and renders the running
// per-variant qty_out. NOT Layout-wrapped, matching /fulfill and
// /stock-take/:id/scan.

export default function TransferScanOut() {
  const { id } = useParams<{ id: string }>()
  const { t, i18n } = useTranslation()
  const isAr = i18n.language === 'ar'
  const navigate = useNavigate()
  const [transfer, setTransfer] = useState<TransferDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)

  const load = useCallback(() => {
    if (!id) return Promise.resolve()
    return getTransfer(id)
      .then(setTransfer)
      .catch(e => setLoadError(e instanceof Error ? e.message : String(e)))
  }, [id])

  useEffect(() => {
    load().finally(() => setLoading(false))
  }, [load])

  const scanner = useScanner({
    onScan: async (barcode) => {
      if (!id) return { success: false }
      const result = await scanOutTransferPiece(id, barcode)

      if (result.success) {
        // Refetch so the per-variant table (names + qty_out) stays authoritative —
        // the scan response alone doesn't carry a variant display name for a
        // line that was just created by this very scan.
        await load()
        return { success: true, label: `${t('transfers.scanOut.progress')}: ${result.qtyOut}` }
      }

      // Rejected — render the backend's own bilingual message verbatim, never
      // re-derive text from `code` on the frontend.
      const message = isAr ? result.message_ar : result.message_en
      return { success: false, label: message ?? result.code }
    },
  })

  if (loading) {
    return (
      <div className="flex items-center justify-center h-screen bg-base">
        <Spinner size={32} />
      </div>
    )
  }

  if (loadError || !transfer || !id) {
    return (
      <div className="flex flex-col items-center justify-center h-screen bg-base gap-4 px-6 text-center">
        <p className="text-danger text-body">{loadError ?? t('transfers.scanOut.notFound')}</p>
        <Button variant="secondary" onClick={() => navigate('/transfers')}>
          {t('transfers.scanOut.back')}
        </Button>
      </div>
    )
  }

  if (transfer.status !== 'open') {
    return (
      <div className="flex flex-col items-center justify-center h-screen bg-base gap-4 px-6 text-center">
        <p className="text-danger text-body">{t('transfers.scanOut.notOpenTitle')}</p>
        <Button variant="secondary" onClick={() => navigate(`/transfers/${id}`)}>
          {t('transfers.scanOut.viewTransfer')}
        </Button>
      </div>
    )
  }

  return (
    <div className="flex flex-col h-screen bg-base" data-testid="transfer-scan-out">
      {/* Header */}
      <div className="bg-panel border-b border-line px-6 py-3 flex items-center justify-between">
        <Button variant="tertiary" size="sm" onClick={() => navigate(`/transfers/${id}`)}>
          ← {t('transfers.scanOut.back')}
        </Button>
        <div className="text-end">
          <p className="text-body font-medium text-primary">{transfer.destination_location_name}</p>
          <p className="text-caption text-muted">{t(`transfers.type.${transfer.transfer_type}`)}</p>
        </div>
      </div>

      {/* Scan input + flash overlay */}
      <div className="bg-panel border-b border-line px-6 py-4">
        <ScanShell scanner={scanner} placeholder={t('transfers.scanOut.placeholder')} />
        {scanner.recentScans[0] && (
          <div
            data-testid="last-scan-feedback"
            className={`mt-2 text-small font-medium ${scanner.recentScans[0].success ? 'text-success' : 'text-danger'}`}
          >
            {scanner.recentScans[0].success ? '✓ ' : '✗ '}
            <span className="font-mono">{scanner.recentScans[0].barcode}</span>
            {' — '}{scanner.recentScans[0].label}
          </div>
        )}
      </div>

      {/* Per-variant running send-out count */}
      <div className="flex-1 overflow-y-auto p-6 space-y-6">
        <div className="space-y-2">
          <h2 className="text-caption text-muted uppercase tracking-widest">{t('transfers.scanOut.progress')}</h2>
          {transfer.lines.length === 0 ? (
            <p className="text-small text-muted">{t('transfers.scanOut.noScansYet')}</p>
          ) : (
            <div className="card overflow-hidden">
              <table className="min-w-full">
                <tbody>
                  {transfer.lines.map(line => (
                    <tr key={line.id} className="tbl-row">
                      <td className="tbl-cell text-primary">
                        {line.product_title} · {line.variant_title}
                        {line.sku && <span className="font-mono text-caption text-muted ms-2">{line.sku}</span>}
                      </td>
                      <td className="tbl-cell text-end font-mono text-h3 text-primary">{line.qty_out}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>

        <div className="space-y-2">
          <h2 className="text-caption text-muted uppercase tracking-widest">{t('transfers.scanOut.recent')}</h2>
          {scanner.recentScans.length === 0 ? (
            <p className="text-small text-muted">{t('transfers.scanOut.noScansYet')}</p>
          ) : (
            <div className="space-y-2">
              {scanner.recentScans.map(entry => (
                <div
                  key={entry.key}
                  className="flex items-center justify-between bg-panel rounded border border-line px-3 py-2"
                >
                  <span className="font-mono text-small text-primary">{entry.barcode.slice(-10)}</span>
                  <span className={`text-caption ${entry.success ? 'text-success' : 'text-danger'}`}>
                    {entry.label}
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* Done */}
      <div className="bg-panel border-t border-line px-6 py-4">
        <Button className="w-full" onClick={() => navigate(`/transfers/${id}`)}>
          {t('transfers.scanOut.done')}
        </Button>
      </div>
    </div>
  )
}
