import { useEffect, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useScanner } from '../hooks/useScanner'
import { ScanShell } from '../components/ScanShell'
import { Button, Spinner } from '../components/ui'
import {
  getStockTakeSession, scanStockTakePiece, unscanStockTakePiece,
  StockTakeCondition, StockTakeClassification, StockTakeSessionDetail,
} from '../api'

// FR-21 Step 6.3, screen 3 — blind full-screen scan. NOT Layout-wrapped, matching
// the /fulfill precedent. Uses the Step 6.2 shared scanner — no scan mechanics
// live here, only the stock-take-specific onScan callback + blind counters.

interface ScanData {
  pieceId: string | null
  classification: StockTakeClassification
}

const UNEXPECTED: StockTakeClassification[] = ['unexpected_resurfaced', 'out_of_scope']

function isCounted(cls: StockTakeClassification): boolean {
  return cls === 'match' || cls === 'condition_mismatch'
}

interface Tally { matched: number; mismatch: number; unexpected: number; unknown: number }

function bucketOf(cls: StockTakeClassification): keyof Tally {
  if (cls === 'match') return 'matched'
  if (cls === 'condition_mismatch') return 'mismatch'
  if (UNEXPECTED.includes(cls)) return 'unexpected'
  return 'unknown'
}

export default function StockTakeScan() {
  const { id } = useParams<{ id: string }>()
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [session, setSession] = useState<StockTakeSessionDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [condition, setCondition] = useState<StockTakeCondition>('good')
  const [counted, setCounted] = useState(0)
  const [tally, setTally] = useState<Tally>({ matched: 0, mismatch: 0, unexpected: 0, unknown: 0 })

  useEffect(() => {
    if (!id) return
    getStockTakeSession(id)
      .then(setSession)
      .catch(e => setLoadError(e instanceof Error ? e.message : String(e)))
      .finally(() => setLoading(false))
  }, [id])

  const scanner = useScanner({
    onScan: async (barcode) => {
      if (!id) return { success: false }
      const result = await scanStockTakePiece(id, barcode, condition)
      const cls = result.classification
      const success = isCounted(cls)

      if (!result.alreadyScanned) {
        setTally(prev => ({ ...prev, [bucketOf(cls)]: prev[bucketOf(cls)] + 1 }))
        if (success) setCounted(c => c + 1)
      }

      const data: ScanData = { pieceId: result.pieceId, classification: cls }
      return {
        success,
        label: success ? t('stocktake.scan.counted') : t('stocktake.scan.flagged'),
        data,
      }
    },
  })

  async function handleUnscan(key: string, data: ScanData | undefined) {
    if (!id || !data?.pieceId) { scanner.removeRecentScan(key); return }
    await unscanStockTakePiece(id, data.pieceId)
    scanner.removeRecentScan(key)
    const bucket = bucketOf(data.classification)
    setTally(prev => ({ ...prev, [bucket]: Math.max(0, prev[bucket] - 1) }))
    if (isCounted(data.classification)) setCounted(c => Math.max(0, c - 1))
  }

  if (loading) {
    return (
      <div className="flex items-center justify-center h-screen bg-base">
        <Spinner size={32} />
      </div>
    )
  }
  if (loadError || !session || !id) {
    return (
      <div className="flex flex-col items-center justify-center h-screen bg-base gap-4 px-6 text-center">
        <p className="text-danger text-body">{loadError ?? t('stocktake.scan.notFound')}</p>
        <Button variant="secondary" onClick={() => navigate('/stock-take')}>
          {t('stocktake.scan.backToList')}
        </Button>
      </div>
    )
  }

  return (
    <div className="flex flex-col h-screen bg-base" data-testid="stocktake-scan">
      {/* Header — back + condition mode toggle */}
      <div className="bg-panel border-b border-line px-6 py-3 flex items-center justify-between">
        <Button variant="tertiary" size="sm" onClick={() => navigate('/stock-take')}>
          ← {t('stocktake.scan.back')}
        </Button>
        <div className="flex items-center gap-2">
          <span className="text-small text-muted">{t('stocktake.scan.countingLabel')}</span>
          <button
            type="button"
            data-testid="condition-good-btn"
            onClick={() => setCondition('good')}
            className={`px-4 py-2 rounded-lg text-small font-semibold border transition-colors ${
              condition === 'good'
                ? 'bg-success text-white border-success'
                : 'border-line text-muted hover:border-success'
            }`}
          >
            {t('stocktake.scan.good')}
          </button>
          <button
            type="button"
            data-testid="condition-damaged-btn"
            onClick={() => setCondition('damaged')}
            className={`px-4 py-2 rounded-lg text-small font-semibold border transition-colors ${
              condition === 'damaged'
                ? 'bg-danger text-white border-danger'
                : 'border-line text-muted hover:border-danger'
            }`}
          >
            {t('stocktake.scan.damaged')}
          </button>
        </div>
      </div>

      {/* Scan input + flash overlay — all mechanics live in useScanner/ScanShell */}
      <div className="bg-panel border-b border-line px-6 py-4">
        <ScanShell scanner={scanner} placeholder={t('stocktake.scan.placeholder')} />
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

      {/* Blind counted + live tally — never expected/remaining */}
      <div className="flex-1 overflow-y-auto p-6 space-y-6">
        <div className="text-center">
          <p className="text-caption text-muted uppercase tracking-widest">{t('stocktake.scan.countedLabel')}</p>
          <p className="text-6xl font-bold text-primary" data-testid="counted-display">{counted}</p>
        </div>

        <div className="flex flex-wrap justify-center gap-x-4 gap-y-1 text-small text-muted">
          <span data-testid="tally-matched">{t('stocktake.scan.tally.matched')}: {tally.matched}</span>
          <span data-testid="tally-mismatch">{t('stocktake.scan.tally.mismatch')}: {tally.mismatch}</span>
          <span data-testid="tally-unexpected">{t('stocktake.scan.tally.unexpected')}: {tally.unexpected}</span>
          <span data-testid="tally-unknown">{t('stocktake.scan.tally.unknown')}: {tally.unknown}</span>
        </div>

        <div className="space-y-2">
          <h2 className="text-caption text-muted uppercase tracking-widest">{t('stocktake.scan.recent')}</h2>
          {scanner.recentScans.length === 0 ? (
            <p className="text-small text-muted">{t('stocktake.scan.noScansYet')}</p>
          ) : (
            <div className="space-y-2">
              {scanner.recentScans.map(entry => {
                const data = entry.data as ScanData | undefined
                return (
                  <div
                    key={entry.key}
                    className="flex items-center justify-between bg-panel rounded border border-line px-3 py-2"
                  >
                    <span className="font-mono text-small text-primary">{entry.barcode.slice(-10)}</span>
                    <span className={`text-caption ${entry.success ? 'text-success' : 'text-danger'}`}>
                      {entry.label}
                    </span>
                    <button
                      data-testid={`unscan-${entry.key}`}
                      onClick={() => handleUnscan(entry.key, data)}
                      className="text-danger hover:text-danger/80 text-small ms-1"
                      title={t('stocktake.scan.unscan')}
                    >
                      {t('stocktake.scan.unscan')}
                    </button>
                  </div>
                )
              })}
            </div>
          )}
        </div>
      </div>

      {/* Review — hands off to the manager screen */}
      <div className="bg-panel border-t border-line px-6 py-4">
        <Button className="w-full" onClick={() => navigate(`/stock-take/${id}/review`)}>
          {t('stocktake.scan.review')}
        </Button>
      </div>
    </div>
  )
}
