import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { CheckCircle2, XCircle } from 'lucide-react'
import { useScanner, ScanMeta } from '../../hooks/useScanner'
import { usePhoneScanTarget } from '../../phone/usePhoneScanTarget'
import { PhoneScanButton } from '../../phone/PhoneScanButton'
import { ViaPhoneTag } from '../../phone/ViaPhoneTag'
import { ScanShell } from '../../components/ScanShell'
import { Alert, Badge, Button, Modal, Progress, Radio, Spinner, cn } from '../../components/ui'
import {
  getReceivingSession, scanFactoryPiece, undoFactoryScan, printFactoryExtraLabel, openFactoryExtraLabelPdf,
  ServerMessageError, FactoryProgress, FactoryScanOutcome,
} from '../../api'

/**
 * Flexible labeling slice 6b — factory receiving, the SCAN LOOP: full-screen, NOT Layout-wrapped (the Tag stock /
 * stock-take precedent). The pieces arrived wearing the run's factory labels: scan each one — the label names the
 * product, so there is nothing to pick. Only scanned pieces enter stock, when the session is finished (on the session
 * screen). All scan mechanics are the shared useScanner (+ the paired phone); this screen supplies onScan and shows the
 * last scan, per product received + scanned / expected, "Undo last scan" and "Piece without a label".
 */

interface ScanData { ok: boolean; viaPhone: boolean; overage: boolean }

export default function FactoryReceivingScan() {
  const { id } = useParams<{ id: string }>()
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const [progress, setProgress] = useState<FactoryProgress | null>(null)
  const [reference, setReference] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [undoing, setUndoing] = useState(false)
  const [askExtra, setAskExtra] = useState(false)
  const [extraFor, setExtraFor] = useState<string | null>(null)
  const [printing, setPrinting] = useState(false)

  const back = `/receiving?session=${id}`
  const readable = (e: unknown) => e instanceof ServerMessageError ? e.messageFor(i18n.language) : t('receiving.factory.error')

  useEffect(() => {
    if (!id) return
    getReceivingSession(id)
      .then(s => {
        if (s.mode !== 'factory' || s.status !== 'open' || !s.factory) { navigate(back, { replace: true }); return }
        setProgress(s.factory)
        setReference(s.reference)
      })
      .catch(e => setLoadError(readable(e)))
      .finally(() => setLoading(false))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])

  const runName = progress ? (progress.reference ? `${progress.factoryName} · ${progress.reference}` : progress.factoryName) : ''

  const phone = usePhoneScanTarget({
    label: t('phone.target.factoryReceiving', { ref: reference ?? runName }),
    describe: (_code, o) => o.label,
    paused: askExtra,
  })

  // Queued scans are dropped by useScanner when this screen unmounts (leaving it).
  const scanner = phone.attach(useScanner({
    focusPaused: askExtra,
    onScan: phone.wrap(async (code: string, meta?: ScanMeta) => {
      if (!id) return { success: false }
      const relayEventId = typeof meta?.relayEventId === 'string' ? meta.relayEventId : undefined
      let r: FactoryScanOutcome
      try {
        r = await scanFactoryPiece(id, code, relayEventId)
      } catch (e: unknown) {
        return { success: false, label: readable(e), data: { ok: false, viaPhone: false, overage: false } as ScanData }
      }
      setProgress(r.progress)
      setNotice(null)
      const product = r.line ? `${r.line.productTitle} / ${r.line.variantTitle}` : ''
      if (r.outcome === 'STAGED') {
        return { success: true, label: product, data: { ok: true, viaPhone: r.viaPhone, overage: r.overage } as ScanData }
      }
      const message = (i18n.language.startsWith('ar') ? (r.messageAr ?? r.messageEn) : r.messageEn) ?? r.outcome
      return { success: false, label: message, data: { ok: false, viaPhone: r.viaPhone, overage: false } as ScanData }
    }),
  }))

  async function undo() {
    if (!id) return
    setUndoing(true); setActionError(null)
    try {
      const r = await undoFactoryScan(id)
      setProgress(r.progress)
      setNotice(t('receiving.factory.undone', { code: r.code }))
    } catch (e: unknown) {
      setActionError(readable(e))
    } finally {
      setUndoing(false)
    }
  }

  async function printExtra() {
    if (!id || !extraFor) return
    setPrinting(true); setActionError(null)
    try {
      const label = await printFactoryExtraLabel(id, extraFor, crypto.randomUUID())
      await openFactoryExtraLabelPdf(id, label.codeId)
      setAskExtra(false)
      setNotice(t(label.overage ? 'receiving.factory.extraPrintedOverage' : 'receiving.factory.extraPrinted', { code: label.code }))
    } catch (e: unknown) {
      setActionError(readable(e))
    } finally {
      setPrinting(false)
    }
  }

  if (loading) {
    return <div className="flex items-center justify-center h-screen bg-base"><Spinner size={32} /></div>
  }
  if (loadError || !progress) {
    return (
      <div className="flex flex-col items-center justify-center h-screen bg-base gap-4 px-6 text-center">
        <p className="text-danger text-body">{loadError ?? t('receiving.factory.error')}</p>
        <Button variant="secondary" onClick={() => navigate(back)}>{t('receiving.factory.backToSession')}</Button>
      </div>
    )
  }

  const last = scanner.recentScans[0]
  const lastData = last?.data as ScanData | undefined
  const ok = lastData?.ok ?? last?.success ?? false
  const Icon = ok ? CheckCircle2 : XCircle

  return (
    <div className="flex flex-col h-screen bg-base" data-testid="factory-scan">
      <div className="bg-panel border-b border-line px-4 sm:px-6 py-3 flex flex-wrap items-center justify-between gap-3">
        <Button variant="tertiary" size="sm" onClick={() => navigate(back)}>
          ← {t('receiving.factory.backToSession')}
        </Button>
        <p className="text-small text-muted truncate"><bdi>{runName}</bdi></p>
        <div className="flex items-center gap-3">
          <PhoneScanButton />
          <Button size="sm" onClick={() => navigate(back)}>{t('receiving.factory.doneScanning')}</Button>
        </div>
      </div>

      <div className="bg-panel border-b border-line px-4 sm:px-6 py-4">
        <ScanShell scanner={scanner} placeholder={t('receiving.factory.placeholder')} />
        {last && (
          <div data-testid="factory-last-scan"
            className={cn('mt-2 flex flex-wrap items-center gap-2 rounded-lg border px-3 py-2 text-small font-medium',
              ok ? 'text-success bg-success/10 border-success/30' : 'text-critical bg-critical/10 border-critical/30')}>
            <Icon size={16} strokeWidth={2} className="flex-shrink-0" />
            <span className="font-mono" dir="ltr">{last.barcode}</span>
            <span aria-hidden className="rtl:rotate-180">→</span>
            <span><bdi>{last.label}</bdi></span>
            {lastData?.overage && <Badge tone="warning" label={t('receiving.factory.overage')} />}
            {lastData?.viaPhone && <ViaPhoneTag />}
          </div>
        )}
      </div>

      <div className="flex-1 overflow-y-auto p-4 sm:p-6 space-y-6">
        {actionError && <Alert tone="critical" title={actionError} />}
        {notice && <Alert tone="info" title={notice} />}

        <div className="text-center">
          <p className="text-caption text-muted uppercase tracking-widest">{t('receiving.factory.scannedLabel')}</p>
          <p className="text-6xl font-bold text-primary tabular-nums" data-testid="factory-staged">{progress.staged}</p>
        </div>

        <div className="card p-4 space-y-3 max-w-xl mx-auto" data-testid="factory-progress">
          <p className="text-small font-semibold text-primary">{t('receiving.factory.progress')}</p>
          <FactoryLines progress={progress} />
          <div className="flex flex-wrap gap-2 pt-1">
            <Button variant="secondary" size="sm" loading={undoing} disabled={progress.staged === 0} onClick={undo}>
              {t('receiving.factory.undo')}
            </Button>
            <Button variant="secondary" size="sm" onClick={() => {
              setExtraFor(progress.lines.length === 1 ? progress.lines[0].variantId : null); setAskExtra(true) }}>
              {t('receiving.factory.extra')}
            </Button>
          </div>
        </div>
      </div>

      {askExtra && (
        <Modal title={t('receiving.factory.extraTitle')} onClose={() => { if (!printing) setAskExtra(false) }}>
          <div className="space-y-4" data-testid="factory-extra-picker">
            <p className="text-small text-muted">{t('receiving.factory.extraBody')}</p>
            <div className="space-y-2 max-h-80 overflow-y-auto">
              {progress.lines.map(l => (
                <label key={l.variantId} className={cn('flex items-center gap-3 rounded-xl border p-3 cursor-pointer',
                  extraFor === l.variantId ? 'border-trace-blue bg-trace-blue/[0.06]' : 'border-line hover:bg-elevated')}>
                  <Radio value={l.variantId} checked={extraFor === l.variantId} onChange={v => setExtraFor(v)} />
                  <span className="text-body text-primary"><bdi>{l.productTitle} / {l.variantTitle}</bdi></span>
                </label>
              ))}
            </div>
            <div className="flex gap-2">
              <Button variant="secondary" className="flex-1" disabled={printing} onClick={() => setAskExtra(false)}>
                {t('common.cancel')}
              </Button>
              <Button className="flex-1" loading={printing} disabled={!extraFor} onClick={printExtra}>
                {t('receiving.factory.extraPrint')}
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  )
}

/** Per product: received (earlier sessions) + scanned here, of expected; "+N extra" past expected. */
export function FactoryLines({ progress }: { progress: FactoryProgress }) {
  const { t } = useTranslation()
  return (
    <ul className="space-y-3">
      {progress.lines.map(l => {
        const have = l.received + l.staged
        return (
          <li key={l.lineId} className="space-y-1" data-testid={`factory-line-${l.variantId}`}>
            <div className="flex items-center justify-between gap-3 text-small">
              <span className="text-primary truncate"><bdi>{l.productTitle} / {l.variantTitle}</bdi></span>
              <span className="flex items-center gap-2 flex-shrink-0">
                {have > l.expected && <Badge tone="warning" label={t('receiving.factory.extraCount', { count: have - l.expected })} />}
                <span className="font-mono text-muted tabular-nums" dir="ltr">{have} / {l.expected}</span>
              </span>
            </div>
            <Progress value={l.expected > 0 ? Math.min(100, Math.round(100 * have / l.expected)) : 0} />
            {l.received > 0 && (
              <p className="text-caption text-muted">{t('receiving.factory.lineSplit', { received: l.received, staged: l.staged })}</p>
            )}
          </li>
        )
      })}
    </ul>
  )
}
