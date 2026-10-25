import { useEffect, useState } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { CheckCircle2, Info, XCircle } from 'lucide-react'
import { useScanner, ScanMeta } from '../hooks/useScanner'
import { usePhoneScanTarget } from '../phone/usePhoneScanTarget'
import { PhoneScanButton } from '../phone/PhoneScanButton'
import { ViaPhoneTag } from '../phone/ViaPhoneTag'
import { ScanShell } from '../components/ScanShell'
import { Alert, Button, Modal, Progress, Radio, Spinner, cn } from '../components/ui'
import {
  getTagSession, scanTagLabel, printTagReplacement, endTagSession, openLabelBatchPdf,
  ServerMessageError, TagScanOutcome, TagSession,
} from '../api'
import type { PrintedProduct } from './inventory/UntaggedTab'

/**
 * Flexible labeling — Tag stock, the SCAN LOOP: full-screen, NOT Layout-wrapped (the /stock-take/:id/scan
 * precedent). Stick a label on an untagged piece and scan it: the server binds the code to an untagged piece of
 * the CODE'S OWN product (named codes), so there is no product to pick (UI pass 2026-10-25 — "Change product" and
 * the per-product start are gone). All scan mechanics are the shared useScanner (+ the paired phone); this screen
 * only supplies onScan and shows the session counter, the last scan ("P000123 → Hydrogen / Default") and, per
 * product printed, tagged of printed. "Print 1 replacement label" asks which product.
 */

type ScanKind = 'tagged' | 'info' | 'rejected'

interface ScanData { kind: ScanKind; viaPhone: boolean }

const FEEDBACK = {
  tagged:   { icon: CheckCircle2, cls: 'text-success bg-success/10 border-success/30' },
  info:     { icon: Info,         cls: 'text-info bg-info/10 border-info/30' },
  rejected: { icon: XCircle,      cls: 'text-critical bg-critical/10 border-critical/30' },
} as const

const BACK = '/inventory?tab=untagged'

export default function TagStockScan() {
  const { id } = useParams<{ id: string }>()
  const location = useLocation()
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  /** What the print that opened this loop printed (from Inventory → Untagged); else the session's own counts. */
  const printedFromPrint = (location.state as { printed?: PrintedProduct[] } | null)?.printed ?? []
  const [session, setSession] = useState<TagSession | null>(null)
  const [tagged, setTagged] = useState(0)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [printing, setPrinting] = useState(false)
  const [askReplacement, setAskReplacement] = useState(false)
  const [replacementFor, setReplacementFor] = useState<string | null>(null)
  const [confirmEnd, setConfirmEnd] = useState(false)
  const [ending, setEnding] = useState(false)

  const readable = (e: unknown) => e instanceof ServerMessageError ? e.messageFor(i18n.language) : t('tagStock.error')

  useEffect(() => {
    if (!id) return
    getTagSession(id)
      .then(s => {
        if (s.endedAt) { navigate(`/tag-stock/${id}/summary`, { replace: true }); return }
        setSession(s)
        setTagged(s.tagged)
      })
      .catch(e => setLoadError(readable(e)))
      .finally(() => setLoading(false))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])

  const phone = usePhoneScanTarget({
    label: t('phone.target.tagStock'),
    describe: (_code, o) => o.label,
    paused: confirmEnd || askReplacement,
  })

  // Queued scans are dropped by useScanner when this screen unmounts (leaving it).
  const scanner = phone.attach(useScanner({
    focusPaused: confirmEnd || askReplacement,
    onScan: phone.wrap(async (code: string, meta?: ScanMeta) => {
      if (!id) return { success: false }
      const relayEventId = typeof meta?.relayEventId === 'string' ? meta.relayEventId : undefined
      let r: TagScanOutcome
      try {
        r = await scanTagLabel(id, code, relayEventId)
      } catch (e: unknown) {
        return { success: false, label: readable(e), data: { kind: 'rejected', viaPhone: false } as ScanData }
      }
      setTagged(r.taggedThisSession)
      const message = i18n.language.startsWith('ar') ? (r.messageAr ?? r.messageEn) : r.messageEn
      const product = r.variant ? `${r.variant.productTitle} / ${r.variant.variantTitle}` : ''
      if (r.outcome === 'TAGGED') {
        getTagSession(id).then(setSession).catch(() => { /* progress refreshes on the next scan */ })
        return { success: true, label: product, data: { kind: 'tagged', viaPhone: r.viaPhone } as ScanData }
      }
      if (r.outcome === 'ALREADY_TAGGED') {
        return { success: true, label: `${product} · ${t('tagStock.scan.alreadyTagged')}`,
                 data: { kind: 'info', viaPhone: r.viaPhone } as ScanData }
      }
      return { success: false, label: message ?? r.outcome, data: { kind: 'rejected', viaPhone: r.viaPhone } as ScanData }
    }),
  }))

  // Per product printed: tagged (this session) of printed (that print, else this session's own batches).
  const progress = (() => {
    const fromSession = new Map((session?.variants ?? []).map(v => [v.variantId, v]))
    const rows = printedFromPrint.length > 0
      ? printedFromPrint.map(p => ({ ...p, tagged: fromSession.get(p.variantId)?.tagged ?? 0 }))
      : (session?.variants ?? []).filter(v => v.printed > 0).map(v => ({
          variantId: v.variantId, productTitle: v.productTitle, variantTitle: v.variantTitle, count: v.printed, tagged: v.tagged }))
    return rows
  })()

  async function replacement() {
    if (!id || !replacementFor) return
    setPrinting(true); setActionError(null)
    try {
      const batch = await printTagReplacement(id, replacementFor, crypto.randomUUID())
      await openLabelBatchPdf(batch.id)
      setAskReplacement(false)
    } catch (e: unknown) {
      setActionError(readable(e))
    } finally {
      setPrinting(false)
    }
  }

  async function end() {
    if (!id) return
    setEnding(true); setActionError(null)
    try {
      await endTagSession(id)
      navigate(`/tag-stock/${id}/summary`)
    } catch (e: unknown) {
      setActionError(readable(e))
      setEnding(false)
    }
  }

  if (loading) {
    return <div className="flex items-center justify-center h-screen bg-base"><Spinner size={32} /></div>
  }
  if (loadError || !session) {
    return (
      <div className="flex flex-col items-center justify-center h-screen bg-base gap-4 px-6 text-center">
        <p className="text-danger text-body">{loadError ?? t('tagStock.error')}</p>
        <Button variant="secondary" onClick={() => navigate(BACK)}>{t('tagStock.scan.backToList')}</Button>
      </div>
    )
  }

  const last = scanner.recentScans[0]
  const lastData = last?.data as ScanData | undefined
  const feedback = FEEDBACK[lastData?.kind ?? (last?.success ? 'tagged' : 'rejected')]

  return (
    <div className="flex flex-col h-screen bg-base" data-testid="tag-stock-scan">
      <div className="bg-panel border-b border-line px-4 sm:px-6 py-3 flex flex-wrap items-center justify-between gap-3">
        <Button variant="tertiary" size="sm" onClick={() => navigate(BACK)}>
          ← {t('tagStock.scan.backToList')}
        </Button>
        <div className="flex items-center gap-3">
          <PhoneScanButton />
          <Button variant="secondary" size="sm" onClick={() => setConfirmEnd(true)}>{t('tagStock.scan.end')}</Button>
        </div>
      </div>

      <div className="bg-panel border-b border-line px-4 sm:px-6 py-4">
        <ScanShell scanner={scanner} placeholder={t('tagStock.scan.placeholder')} />
        {last && (
          <div data-testid="tag-last-scan"
            className={`mt-2 flex flex-wrap items-center gap-2 rounded-lg border px-3 py-2 text-small font-medium ${feedback.cls}`}>
            <feedback.icon size={16} strokeWidth={2} className="flex-shrink-0" />
            <span className="font-mono" dir="ltr">{last.barcode}</span>
            <span aria-hidden className="rtl:rotate-180">→</span>
            <span><bdi>{last.label}</bdi></span>
            {lastData?.viaPhone && <ViaPhoneTag />}
          </div>
        )}
      </div>

      <div className="flex-1 overflow-y-auto p-4 sm:p-6 space-y-6">
        {actionError && <Alert tone="critical" title={actionError} />}

        <div className="text-center">
          <p className="text-caption text-muted uppercase tracking-widest">{t('tagStock.scan.taggedLabel')}</p>
          <p className="text-6xl font-bold text-primary tabular-nums" data-testid="tagged-count">{tagged}</p>
        </div>

        {progress.length > 0 ? (
          <div className="card p-4 space-y-3 max-w-xl mx-auto" data-testid="tag-progress">
            <p className="text-small font-semibold text-primary">{t('tagStock.scan.progress')}</p>
            <ul className="space-y-3">
              {progress.map(p => (
                <li key={p.variantId} className="space-y-1" data-testid={`tag-progress-${p.variantId}`}>
                  <div className="flex items-center justify-between gap-3 text-small">
                    <span className="text-primary truncate"><bdi>{p.productTitle} / {p.variantTitle}</bdi></span>
                    <span className="font-mono text-muted tabular-nums" dir="ltr">{Math.min(p.tagged, p.count)} / {p.count}</span>
                  </div>
                  <Progress value={p.count > 0 ? Math.min(100, Math.round(100 * p.tagged / p.count)) : 0} />
                </li>
              ))}
            </ul>
            <Button variant="secondary" size="sm" onClick={() => {
              setReplacementFor(progress.length === 1 ? progress[0].variantId : null); setAskReplacement(true) }}>
              {t('tagStock.scan.replacement')}
            </Button>
          </div>
        ) : (
          <p className="text-center text-small text-muted">{t('tagStock.scan.scanToStart')}</p>
        )}
      </div>

      {askReplacement && (
        <Modal title={t('tagStock.scan.replacementTitle')} onClose={() => { if (!printing) setAskReplacement(false) }}>
          <div className="space-y-4" data-testid="tag-replacement-picker">
            <p className="text-small text-muted">{t('tagStock.scan.replacementWhich')}</p>
            <div className="space-y-2">
              {progress.map(p => (
                <label key={p.variantId} className={cn('flex items-center gap-3 rounded-xl border p-3 cursor-pointer',
                  replacementFor === p.variantId ? 'border-trace-blue bg-trace-blue/[0.06]' : 'border-line hover:bg-elevated')}>
                  <Radio value={p.variantId} checked={replacementFor === p.variantId} onChange={v => setReplacementFor(v)} />
                  <span className="text-body text-primary"><bdi>{p.productTitle} / {p.variantTitle}</bdi></span>
                </label>
              ))}
            </div>
            <div className="flex gap-2">
              <Button variant="secondary" className="flex-1" disabled={printing} onClick={() => setAskReplacement(false)}>
                {t('common.cancel')}
              </Button>
              <Button className="flex-1" loading={printing} disabled={!replacementFor} onClick={replacement}>
                {t('tagStock.scan.replacementPrint')}
              </Button>
            </div>
          </div>
        </Modal>
      )}

      {confirmEnd && (
        <Modal title={t('tagStock.scan.endTitle')} onClose={() => { if (!ending) setConfirmEnd(false) }}>
          <div className="space-y-4">
            <p className="text-body text-muted">{t('tagStock.scan.endBody')}</p>
            <div className="flex gap-2">
              <Button variant="secondary" className="flex-1" disabled={ending} onClick={() => setConfirmEnd(false)}>
                {t('common.cancel')}
              </Button>
              <Button className="flex-1" loading={ending} onClick={end}>{t('tagStock.scan.end')}</Button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  )
}
