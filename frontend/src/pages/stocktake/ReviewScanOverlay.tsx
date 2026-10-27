import { useState } from 'react'
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'
import type { LucideIcon } from 'lucide-react'
import { CheckCircle2, AlertTriangle, CircleSlash, XCircle, Repeat } from 'lucide-react'
import { useScanner, ScanMeta } from '../../hooks/useScanner'
import { usePhoneScanTarget } from '../../phone/usePhoneScanTarget'
import { PhoneScanButton } from '../../phone/PhoneScanButton'
import { ViaPhoneTag } from '../../phone/ViaPhoneTag'
import { ScanShell } from '../../components/ScanShell'
import { Button } from '../../components/ui'
import {
  scanStockTakePiece, ServerMessageError,
  StockTakeCondition, StockTakeClassification, StockTakePieceRow, ScanDevice,
} from '../../api'

// "Found one? Scan it" (2026-10-27) — replaces the review page's Found it / Mark lost buttons. A
// FULL-SCREEN overlay (an active scan loop, never inside the shell — the Layout's own inputs would fight
// useScanner's click-refocus): it mounts the shared scanner + queue + key capture and registers as the
// tablet's phone-scan target ONLY while open, and every scan goes through scanStockTakePiece — the
// counting screen's own request, no second write path. Unmounting it (Done) drops the scanner and the
// phone target; the review page then reloads.

type Outcome = 'counted' | 'countedMismatch' | 'alreadyCounted' | 'notInCount' | 'unknown' | 'refused'

interface ScanData { outcome: Outcome; scanDevice?: ScanDevice }

const STYLE: Record<Outcome, { icon: LucideIcon; text: string; bg: string; border: string }> = {
  counted:         { icon: CheckCircle2,  text: 'text-success',  bg: 'bg-success/10',  border: 'border-success/30' },
  countedMismatch: { icon: AlertTriangle, text: 'text-warning',  bg: 'bg-warning/10',  border: 'border-warning/30' },
  alreadyCounted:  { icon: Repeat,        text: 'text-muted',    bg: 'bg-elevated',    border: 'border-line' },
  notInCount:      { icon: CircleSlash,   text: 'text-info',     bg: 'bg-info/10',     border: 'border-info/30' },
  unknown:         { icon: XCircle,       text: 'text-critical', bg: 'bg-critical/10', border: 'border-critical/30' },
  refused:         { icon: XCircle,       text: 'text-critical', bg: 'bg-critical/10', border: 'border-critical/30' },
}

function outcomeOf(cls: StockTakeClassification, alreadyScanned: boolean): Outcome {
  if (cls === 'unknown') return 'unknown'
  if (alreadyScanned) return 'alreadyCounted'
  if (cls === 'match') return 'counted'
  if (cls === 'condition_mismatch') return 'countedMismatch'
  return 'notInCount'   // out_of_scope / unexpected_resurfaced — recorded as an unexpected find
}

export function ReviewScanOverlay({ sessionId, sessionName, uncounted, onScanned, onClose }: {
  sessionId: string
  sessionName: string
  /** The review's still-uncounted expected pieces — re-read after every counted scan (onScanned). */
  uncounted: StockTakePieceRow[]
  onScanned: () => void
  onClose: () => void
}) {
  const { t, i18n } = useTranslation()
  const [condition, setCondition] = useState<StockTakeCondition>('good')

  const phone = usePhoneScanTarget({
    label: t('phone.target.stockTake', { name: sessionName }),
    // The overlay's own line is already a full sentence ("Counted", "Not in this count — …").
    describe: (_code, o) => o.label,
  })

  // Queued scans are dropped by useScanner when the overlay unmounts (Done).
  const scanner = phone.attach(useScanner({
    onScan: phone.wrap(async (barcode: string, meta?: ScanMeta) => {
      const relayEventId = typeof meta?.relayEventId === 'string' ? meta.relayEventId : undefined
      let result
      try {
        result = relayEventId
          ? await scanStockTakePiece(sessionId, barcode, condition, relayEventId)
          : await scanStockTakePiece(sessionId, barcode, condition)
      } catch (e: unknown) {
        // A refusal with a readable reason (untagged piece, a label not used yet) — its own words.
        if (e instanceof ServerMessageError) {
          const data: ScanData = { outcome: 'refused' }
          return { success: false, label: e.messageFor(i18n.language), data }
        }
        throw e
      }
      const outcome = outcomeOf(result.classification, result.alreadyScanned === true)
      if (outcome !== 'unknown' && outcome !== 'alreadyCounted') onScanned()
      const data: ScanData = { outcome, scanDevice: result.scanDevice }
      return {
        success: outcome === 'counted' || outcome === 'countedMismatch' || outcome === 'alreadyCounted',
        label: t(`stocktake.review.scan.outcome.${outcome}`),
        data,
      }
    }),
  }))

  const missing = uncounted.filter(r => r.flag !== 'condition_mismatch')

  return createPortal(
    <div
      className="fixed inset-0 z-[60] flex flex-col bg-base"
      role="dialog"
      aria-modal="true"
      aria-label={t('stocktake.review.scan.title')}
      data-testid="review-scan-overlay"
    >
      <div className="bg-panel border-b border-line px-4 sm:px-6 py-3 flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-h3 text-primary">{t('stocktake.review.scan.title')}</h2>
        <div className="flex items-center gap-2">
          <span className="text-small text-muted">{t('stocktake.scan.countingLabel')}</span>
          {(['good', 'damaged'] as const).map(c => (
            <button
              key={c}
              type="button"
              data-testid={`review-scan-condition-${c}`}
              onClick={() => setCondition(c)}
              className={`px-4 py-2 rounded-lg text-small font-semibold border transition-colors ${
                condition === c
                  ? (c === 'good' ? 'bg-success text-white border-success' : 'bg-danger text-white border-danger')
                  : `border-line text-muted ${c === 'good' ? 'hover:border-success' : 'hover:border-danger'}`
              }`}
            >
              {t(`stocktake.scan.${c}`)}
            </button>
          ))}
        </div>
        <div className="flex items-center gap-3">
          <PhoneScanButton />
          <Button variant="secondary" size="sm" onClick={onClose}>
            {t('stocktake.review.scan.done')}
          </Button>
        </div>
      </div>

      <div className="bg-panel border-b border-line px-4 sm:px-6 py-4 space-y-2">
        <p className="text-small text-muted">{t('stocktake.review.scan.intro')}</p>
        <ScanShell scanner={scanner} placeholder={t('stocktake.scan.placeholder')} />
      </div>

      <div className="flex-1 overflow-y-auto px-4 sm:px-6 py-4 space-y-6">
        <div className="space-y-2">
          {scanner.recentScans.map(entry => {
            const data = entry.data as ScanData | undefined
            const style = STYLE[data?.outcome ?? 'refused']
            const Icon = style.icon
            return (
              <div
                key={entry.key}
                data-testid="review-scan-feedback"
                className={`flex items-center gap-3 rounded-lg border px-3 py-2 ${style.bg} ${style.border}`}
              >
                <Icon size={16} strokeWidth={2} className={`flex-shrink-0 ${style.text}`} />
                <span className="font-mono text-small text-primary" dir="ltr">{entry.barcode}</span>
                {data?.scanDevice === 'phone' && <ViaPhoneTag />}
                <span className={`text-small font-medium ms-auto text-end ${style.text}`}>{entry.label}</span>
              </div>
            )
          })}
        </div>

        <div className="space-y-2">
          <h3 className="text-caption text-muted uppercase tracking-widest">
            {t('stocktake.review.scan.stillUncounted', { count: missing.length })}
          </h3>
          {missing.length === 0 ? (
            <p className="text-small text-success" data-testid="review-scan-all-counted">{t('stocktake.review.scan.allCounted')}</p>
          ) : (
            <ul className="space-y-1.5" data-testid="review-scan-uncounted">
              {missing.map(r => (
                <li key={r.pieceId} className="flex items-center gap-3 text-small rounded-lg border border-line px-3 py-2">
                  {r.shortCode && <span className="font-mono text-primary" dir="ltr">{r.shortCode}</span>}
                  <span className="text-muted truncate" dir="auto">{r.productTitle} · {r.variantTitle}</span>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </div>,
    document.body,
  )
}
