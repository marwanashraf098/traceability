import { useEffect, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import type { LucideIcon } from 'lucide-react'
import { CheckCircle2, AlertTriangle, RotateCcw, CircleSlash, XCircle, Undo2 } from 'lucide-react'
import { useScanner } from '../hooks/useScanner'
import { ScanShell } from '../components/ScanShell'
import { Button, Spinner, Modal } from '../components/ui'
import {
  getStockTakeSession, scanStockTakePiece, unscanStockTakePiece, cancelStockTake,
  StockTakeCondition, StockTakeClassification, StockTakeSessionDetail,
} from '../api'

// FR-21 Step 6.3, screen 3 — blind full-screen scan. NOT Layout-wrapped, matching
// the /fulfill precedent. Uses the Step 6.2 shared scanner — no scan mechanics
// live here, only the stock-take-specific onScan callback + blind counters.
//
// Returns-pattern restyle: 5 distinct per-scan feedback states (never merged), an
// outcomes-only tally (never compared to an expected number), and an Abandon-count
// affordance mirroring Returns' own abandon link. No expected quantity, coverage %,
// progress bar, or "X of Y" anywhere on this screen — that stays exclusive to Review.

interface ScanData {
  pieceId: string | null
  classification: StockTakeClassification
}

function isCounted(cls: StockTakeClassification): boolean {
  return cls === 'match' || cls === 'condition_mismatch'
}

interface Tally { matched: number; mismatch: number; resurfaced: number; outOfScope: number; unknown: number }

const EMPTY_TALLY: Tally = { matched: 0, mismatch: 0, resurfaced: 0, outOfScope: 0, unknown: 0 }

function bucketOf(cls: StockTakeClassification): keyof Tally {
  if (cls === 'match') return 'matched'
  if (cls === 'condition_mismatch') return 'mismatch'
  if (cls === 'unexpected_resurfaced') return 'resurfaced'
  if (cls === 'out_of_scope') return 'outOfScope'
  return 'unknown'
}

const FEEDBACK_STYLE: Record<keyof Tally, { icon: LucideIcon; text: string; bg: string; border: string }> = {
  matched:    { icon: CheckCircle2, text: 'text-success',  bg: 'bg-success/10',  border: 'border-success/30' },
  mismatch:   { icon: AlertTriangle, text: 'text-warning', bg: 'bg-warning/10',  border: 'border-warning/30' },
  resurfaced: { icon: RotateCcw,    text: 'text-info',     bg: 'bg-info/10',     border: 'border-info/30' },
  outOfScope: { icon: CircleSlash,  text: 'text-muted',    bg: 'bg-elevated',    border: 'border-line' },
  unknown:    { icon: XCircle,      text: 'text-critical', bg: 'bg-critical/10', border: 'border-critical/30' },
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
  const [tally, setTally] = useState<Tally>(EMPTY_TALLY)
  const [showAbandon, setShowAbandon] = useState(false)
  const [abandoning, setAbandoning] = useState(false)
  const [abandonError, setAbandonError] = useState<string | null>(null)

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
      const bucket = bucketOf(cls)

      if (!result.alreadyScanned) {
        setTally(prev => ({ ...prev, [bucket]: prev[bucket] + 1 }))
        if (success) setCounted(c => c + 1)
      }

      const data: ScanData = { pieceId: result.pieceId, classification: cls }
      return {
        success,
        label: t(`stocktake.scan.feedback.${bucket}`),
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

  async function handleAbandon() {
    if (!id) return
    setAbandoning(true)
    setAbandonError(null)
    try {
      await cancelStockTake(id)
      navigate('/stock-take')
    } catch (e: unknown) {
      setAbandonError(e instanceof Error ? e.message : String(e))
    } finally {
      setAbandoning(false)
    }
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
      {/* Header — back + condition mode toggle + abandon */}
      <div className="bg-panel border-b border-line px-6 py-3 flex items-center justify-between gap-3">
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
        <button
          type="button"
          data-testid="abandon-link"
          onClick={() => setShowAbandon(true)}
          className="text-small font-semibold text-critical hover:text-critical/80"
        >
          {t('stocktake.scan.abandon')}
        </button>
      </div>

      {/* Scan input + flash overlay — all mechanics live in useScanner/ScanShell */}
      <div className="bg-panel border-b border-line px-6 py-4">
        <ScanShell scanner={scanner} placeholder={t('stocktake.scan.placeholder')} />
        {scanner.recentScans[0] && (() => {
          const data = scanner.recentScans[0].data as ScanData | undefined
          const bucket = data ? bucketOf(data.classification) : 'unknown'
          const style = FEEDBACK_STYLE[bucket]
          const Icon = style.icon
          return (
            <div
              data-testid="last-scan-feedback"
              className={`mt-2 flex items-center gap-2 rounded-lg border px-3 py-2 text-small font-medium ${style.bg} ${style.border} ${style.text}`}
            >
              <Icon size={16} strokeWidth={2} className="flex-shrink-0" />
              <span className="font-mono">{scanner.recentScans[0].barcode}</span>
              <span>—</span>
              <span>{scanner.recentScans[0].label}</span>
            </div>
          )
        })()}
      </div>

      {/* Blind counted + outcomes-only tally — never expected/remaining */}
      <div className="flex-1 overflow-y-auto p-6 space-y-6">
        <div className="text-center">
          <p className="text-caption text-muted uppercase tracking-widest">{t('stocktake.scan.countedLabel')}</p>
          <p className="text-6xl font-bold text-primary" data-testid="counted-display">{counted}</p>
        </div>

        <div className="flex flex-wrap justify-center gap-2">
          {(Object.keys(tally) as (keyof Tally)[]).map(bucket => {
            const style = FEEDBACK_STYLE[bucket]
            return (
              <span
                key={bucket}
                data-testid={`tally-${bucket}`}
                className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-caption font-semibold ${style.bg} ${style.border} ${style.text}`}
              >
                {t(`stocktake.scan.feedback.${bucket}`)}
                <span className="font-mono font-bold">{tally[bucket]}</span>
              </span>
            )
          })}
        </div>

        <div className="space-y-2">
          <h2 className="text-caption text-muted uppercase tracking-widest">{t('stocktake.scan.recent')}</h2>
          {scanner.recentScans.length === 0 ? (
            <p className="text-small text-muted">{t('stocktake.scan.noScansYet')}</p>
          ) : (
            <div className="space-y-2">
              {scanner.recentScans.map(entry => {
                const data = entry.data as ScanData | undefined
                const bucket = data ? bucketOf(data.classification) : 'unknown'
                const style = FEEDBACK_STYLE[bucket]
                const Icon = style.icon
                return (
                  <div
                    key={entry.key}
                    className={`flex items-center gap-3 rounded-lg border px-3 py-2 ${style.bg} ${style.border}`}
                  >
                    <Icon size={16} strokeWidth={2} className={`flex-shrink-0 ${style.text}`} />
                    <span className="font-mono text-small text-primary flex-1">{entry.barcode.slice(-10)}</span>
                    <span className={`text-caption font-semibold ${style.text}`}>
                      {entry.label}
                    </span>
                    <button
                      data-testid={`unscan-${entry.key}`}
                      onClick={() => handleUnscan(entry.key, data)}
                      className="text-muted hover:text-primary ms-1"
                      title={t('stocktake.scan.unscan')}
                    >
                      <Undo2 size={14} strokeWidth={2} />
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
        <Button variant="secondary" className="w-full" onClick={() => navigate(`/stock-take/${id}/review`)}>
          {t('stocktake.scan.review')}
        </Button>
      </div>

      {showAbandon && (
        <Modal onClose={() => setShowAbandon(false)} title={t('stocktake.scan.abandonConfirmTitle')}>
          <div className="space-y-4">
            <p className="text-body text-primary">{t('stocktake.scan.abandonConfirmBody')}</p>
            {abandonError && <p className="text-small text-danger">{abandonError}</p>}
            <div className="flex gap-3 justify-end">
              <Button variant="secondary" onClick={() => setShowAbandon(false)}>{t('common.cancel')}</Button>
              <button className="btn-danger" disabled={abandoning} onClick={handleAbandon} data-testid="confirm-abandon">
                {abandoning && <Spinner size={16} />}
                {t('stocktake.scan.abandonConfirmButton')}
              </button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  )
}
