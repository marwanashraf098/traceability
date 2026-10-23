import { useEffect, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { CheckCircle2, Info, XCircle } from 'lucide-react'
import { useScanner, ScanMeta } from '../hooks/useScanner'
import { usePhoneScanTarget } from '../phone/usePhoneScanTarget'
import { PhoneScanButton } from '../phone/PhoneScanButton'
import { ViaPhoneTag } from '../phone/ViaPhoneTag'
import { ScanShell } from '../components/ScanShell'
import { Alert, Button, Modal, ProductThumb, Spinner } from '../components/ui'
import {
  getTagSession, getTagStock, scanTagLabel, printTagReplacement, endTagSession, openLabelBatchPdf,
  ServerMessageError, TagScanOutcome, TagSession, TagVariantStock,
} from '../api'

/**
 * Flexible labeling slice 3 — Tag stock, the SCAN LOOP: full-screen, NOT Layout-wrapped (the /stock-take/:id/scan
 * precedent). Stick a label on an untagged piece and scan it: the server binds the code to an untagged piece of
 * the code's variant. All scan mechanics are the shared useScanner (+ the paired phone); this screen only supplies
 * onScan and shows: the session's tagged counter, the current variant's untagged left / labeled, the last scan.
 * "Change product" goes back to the list with the session kept; "Print 1 replacement label" is a plain button.
 */

type ScanKind = 'tagged' | 'info' | 'rejected'

interface ScanData { kind: ScanKind; viaPhone: boolean }

const FEEDBACK = {
  tagged:   { icon: CheckCircle2, cls: 'text-success bg-success/10 border-success/30' },
  info:     { icon: Info,         cls: 'text-info bg-info/10 border-info/30' },
  rejected: { icon: XCircle,      cls: 'text-critical bg-critical/10 border-critical/30' },
} as const

export default function TagStockScan() {
  const { id } = useParams<{ id: string }>()
  const [params] = useSearchParams()
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const [session, setSession] = useState<TagSession | null>(null)
  const [variant, setVariant] = useState<TagVariantStock | null>(null)
  const [tagged, setTagged] = useState(0)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [printing, setPrinting] = useState(false)
  const [confirmEnd, setConfirmEnd] = useState(false)
  const [ending, setEnding] = useState(false)

  const readable = (e: unknown) => e instanceof ServerMessageError ? e.messageFor(i18n.language) : t('tagStock.error')

  useEffect(() => {
    if (!id) return
    const wanted = params.get('variant')
    Promise.all([getTagSession(id), getTagStock()])
      .then(([s, overview]) => {
        if (s.endedAt) { navigate(`/tag-stock/${id}/summary`, { replace: true }); return }
        setSession(s)
        setTagged(s.tagged)
        const fromList = overview.variants.find(v => v.variantId === wanted)
        const fromSession = s.variants.find(v => v.variantId === wanted)
        if (fromList) setVariant(fromList)
        else if (fromSession) setVariant({ ...fromSession, untagged: fromSession.untaggedLeft, unusedLabels: fromSession.unused })
      })
      .catch(e => setLoadError(readable(e)))
      .finally(() => setLoading(false))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])

  const phone = usePhoneScanTarget({
    label: t('phone.target.tagStock'),
    describe: (_code, o) => o.label,
    paused: confirmEnd,
  })

  // Queued scans are dropped by useScanner when this screen unmounts (leaving it).
  const scanner = phone.attach(useScanner({
    focusPaused: confirmEnd,
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
      if (r.variant) setVariant(r.variant)
      const message = i18n.language.startsWith('ar') ? (r.messageAr ?? r.messageEn) : r.messageEn
      if (r.outcome === 'TAGGED') {
        return { success: true, label: t('tagStock.scan.tagged', { product: r.variant?.productTitle ?? '' }),
                 data: { kind: 'tagged', viaPhone: r.viaPhone } as ScanData }
      }
      if (r.outcome === 'ALREADY_TAGGED') {
        return { success: true, label: t('tagStock.scan.alreadyTagged'), data: { kind: 'info', viaPhone: r.viaPhone } as ScanData }
      }
      return { success: false, label: message ?? r.outcome, data: { kind: 'rejected', viaPhone: r.viaPhone } as ScanData }
    }),
  }))

  async function replacement() {
    if (!id || !variant) return
    setPrinting(true); setActionError(null)
    try {
      const batch = await printTagReplacement(id, variant.variantId, crypto.randomUUID())
      await openLabelBatchPdf(batch.id)
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
        <Button variant="secondary" onClick={() => navigate('/tag-stock')}>{t('tagStock.scan.backToList')}</Button>
      </div>
    )
  }

  const last = scanner.recentScans[0]
  const lastData = last?.data as ScanData | undefined
  const feedback = FEEDBACK[lastData?.kind ?? (last?.success ? 'tagged' : 'rejected')]

  return (
    <div className="flex flex-col h-screen bg-base" data-testid="tag-stock-scan">
      <div className="bg-panel border-b border-line px-4 sm:px-6 py-3 flex flex-wrap items-center justify-between gap-3">
        <Button variant="tertiary" size="sm" onClick={() => navigate('/tag-stock')}>
          ← {t('tagStock.scan.changeProduct')}
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
            <span>—</span>
            <span>{last.label}</span>
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

        {variant ? (
          <div className="card p-4 space-y-3 max-w-xl mx-auto" data-testid="tag-current-variant">
            <div className="flex items-center gap-3">
              <ProductThumb src={variant.imageUrl} alt={variant.productTitle} size={56} />
              <div className="min-w-0">
                <p className="text-body font-semibold text-primary truncate"><bdi>{variant.productTitle}</bdi></p>
                <p className="text-small text-muted truncate">
                  <bdi>{variant.variantTitle}</bdi>
                  {variant.sku && <> · <span className="font-mono" dir="ltr">{variant.sku}</span></>}
                </p>
              </div>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div className="rounded-lg border border-line p-3">
                <p className="text-caption text-muted">{t('tagStock.scan.untaggedLeft')}</p>
                <p className="text-h2 text-primary tabular-nums" data-testid="untagged-left">{variant.untagged}</p>
              </div>
              <div className="rounded-lg border border-line p-3">
                <p className="text-caption text-muted">{t('tagStock.scan.labeled')}</p>
                <p className="text-h2 text-primary tabular-nums">{variant.labeled}</p>
              </div>
            </div>
            <Button variant="secondary" size="sm" loading={printing} onClick={replacement}>
              {t('tagStock.scan.replacement')}
            </Button>
          </div>
        ) : (
          <p className="text-center text-small text-muted">{t('tagStock.scan.scanToStart')}</p>
        )}
      </div>

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
