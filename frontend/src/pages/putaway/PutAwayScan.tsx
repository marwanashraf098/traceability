import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { ArrowRight, Check, Layers, X } from 'lucide-react'
import { useScanner, ScanMeta } from '../../hooks/useScanner'
import { usePhoneScanTarget } from '../../phone/usePhoneScanTarget'
import { PhoneScanButton } from '../../phone/PhoneScanButton'
import { ViaPhoneTag } from '../../phone/ViaPhoneTag'
import { ScanShell } from '../../components/ScanShell'
import { Alert, Button, Spinner, cn } from '../../components/ui'
import {
  closePutAway, getPutAwaySession, scanPutAway, PutAwayCloseSummary, PutAwayScanResult, PutAwaySession,
  ServerMessageError,
} from '../../api'
import { placePath } from './placePath'
import { resetPutAwayWaitingCache } from './usePutAwayWaiting'

/**
 * Warehouse places S2 — Put away, the SCAN LOOP: full-screen, NOT Layout-wrapped (the /tag-stock/:id/scan
 * precedent). Scan a shelf or box sticker — it becomes the place being filled (kept server-side) — then pieces;
 * another place sticker switches place. All scan mechanics are the shared useScanner (+ the paired phone): one
 * auto-focusing input, a FIFO queue so fast scans are never dropped; this screen only supplies onScan and shows the
 * current place, the last scans and the session totals. Finish closes the session and shows what was put where.
 */

interface ScanData { result: PutAwayScanResult | null; error?: string }

const SUCCESS = new Set(['PLACE_SET', 'PLACED', 'MOVED', 'ALREADY_HERE'])

/** The two lines a scan shows (last-scans list, the phone). */
export function describeScan(r: PutAwayScanResult, t: TFunction): { title: string; detail: string } {
  const product = r.piece ? [r.piece.productTitle, r.piece.variantTitle && r.piece.variantTitle !== 'Default Title' ? r.piece.variantTitle : null]
    .filter(Boolean).join(' · ') : ''
  const piece = r.piece?.code ?? r.code
  const place = r.place?.code ?? ''
  const here = r.session.current?.place.code ?? ''
  switch (r.outcome) {
    case 'PLACE_SET':
      return {
        title: t(`putAway.scan.switched.${r.place?.kind === 'box' ? 'box' : 'shelf'}`, { code: place }),
        detail: r.fromPlace ? t('putAway.scan.was', { code: r.fromPlace.code }) : placePath(r.place!, t),
      }
    case 'PLACED':        return { title: product, detail: t('putAway.scan.addedTo', { piece, place: here }) }
    case 'MOVED':         return { title: product, detail: t('putAway.scan.movedFrom', { piece, from: r.fromPlace?.code ?? '' }) }
    case 'ALREADY_HERE':  return { title: product, detail: t('putAway.scan.alreadyHere', { piece, place: here }) }
    case 'SCAN_PLACE_FIRST':
      return { title: t('putAway.scan.placeFirst'), detail: t('putAway.scan.notPlaced', { piece }) }
    case 'SCAN_SHELF_OR_BOX':
      return { title: t(`putAway.scan.notHolding.${r.place?.kind === 'rack' ? 'rack' : 'zone'}`, { code: place }),
               detail: t('putAway.scan.scanShelfOrBox') }
    case 'PLACE_OTHER_LOCATION':
      return { title: t('putAway.scan.placeElsewhere', { code: place, location: r.locationName ?? '' }),
               detail: t('putAway.scan.scanPlaceHere', { location: r.session.locationName }) }
    case 'PLACE_ARCHIVED':
      return { title: t('putAway.scan.archived', { code: place }), detail: t('putAway.scan.scanShelfOrBox') }
    case 'NOT_AT_THIS_LOCATION':
      return { title: t('putAway.scan.notHere'),
               detail: r.locationName ? t('putAway.scan.pieceIsAt', { piece, location: r.locationName })
                 : t('putAway.scan.pieceNowhere', { piece }) }
    case 'CANNOT_PLACE':
      return { title: t('putAway.scan.cannotPlace'),
               detail: t('putAway.scan.pieceIs', { piece, status: t(`lookup.pieceStatus.${r.status}`, { defaultValue: r.status ?? '' }) }) }
    case 'UNTAGGED':
      return { title: t('putAway.scan.untagged'), detail: t('putAway.scan.tagFirst') }
    default:
      return { title: t('putAway.scan.unknown'), detail: t('putAway.scan.unknownBody') }
  }
}

export default function PutAwayScan() {
  const { id } = useParams<{ id: string }>()
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const [session, setSession] = useState<PutAwaySession | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [finishing, setFinishing] = useState(false)
  const [finishError, setFinishError] = useState<string | null>(null)
  const [done, setDone] = useState<PutAwayCloseSummary | null>(null)
  /** Closed while this screen was open (a manager, or 12 h idle): the scans stop being accepted. */
  const [closedElsewhere, setClosedElsewhere] = useState(false)

  const readable = (e: unknown) => e instanceof ServerMessageError ? e.messageFor(i18n.language) : t('putAway.error')

  useEffect(() => {
    if (!id) return
    getPutAwaySession(id)
      .then(s => { if (s.status === 'closed') navigate('/put-away', { replace: true }); else setSession(s) })
      .catch(e => setLoadError(readable(e)))
      .finally(() => setLoading(false))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])

  const phone = usePhoneScanTarget({
    label: t('phone.target.putAway'),
    describe: (_code, o) => o.label,
    paused: finishing,
  })

  // SAFETY-CRITICAL — the put-away scan handler (new in S2): every scan, from the station's scanner or the paired
  // phone, goes through the shared useScanner queue (one in flight, in order, none dropped) to ONE endpoint; the
  // server decides place sticker vs piece and keeps the current place. Each scan gets exactly one outcome and its
  // own entry; a refused scan changes nothing. Do not add a second scan path or a client-side place state.
  const scanner = phone.attach(useScanner({
    focusPaused: finishing,
    recentScansLimit: 12,
    onScan: phone.wrap(async (code: string, meta?: ScanMeta) => {
      if (!id) return { success: false }
      const relayEventId = typeof meta?.relayEventId === 'string' ? meta.relayEventId : undefined
      let r: PutAwayScanResult
      try {
        r = await scanPutAway(id, code, relayEventId)
      } catch (e: unknown) {
        if (e instanceof ServerMessageError && e.code === 'SESSION_CLOSED') setClosedElsewhere(true)
        return { success: false, label: readable(e), data: { result: null, error: readable(e) } as ScanData }
      }
      setSession(r.session)
      const d = describeScan(r, t)
      return { success: SUCCESS.has(r.outcome), label: d.title, data: { result: r } as ScanData }
    }),
  }))

  async function finish() {
    if (!id) return
    scanner.clearQueue()
    setFinishing(true)
    setFinishError(null)
    try {
      const summary = await closePutAway(id)
      resetPutAwayWaitingCache()
      setDone(summary)
    } catch (e: unknown) {
      setFinishError(readable(e))
    } finally {
      setFinishing(false)
    }
  }

  if (loading) {
    return <div className="flex items-center justify-center h-screen bg-base"><Spinner size={32} /></div>
  }
  if (loadError || !session) {
    return (
      <div className="flex flex-col items-center justify-center h-screen bg-base gap-4 px-6 text-center">
        <p className="text-danger text-body">{loadError ?? t('putAway.error')}</p>
        <Button variant="secondary" onClick={() => navigate('/put-away')}>{t('putAway.scan.back')}</Button>
      </div>
    )
  }

  // After Finish: ONE view — what this session put where.
  if (done) {
    return (
      <div className="flex flex-col items-center justify-center min-h-screen bg-base px-6 py-10" data-testid="put-away-done">
        <div className="card p-6 w-full max-w-lg space-y-5">
          <div className="space-y-1">
            <h1 className="text-h2 text-primary">{t('putAway.done.title')}</h1>
            <p className="text-body text-muted">
              {t('putAway.done.body', { count: done.session.placed, places: done.session.placesUsed })}
            </p>
          </div>
          {done.places.length > 0 && (
            <ul className="divide-y divide-line border border-line rounded-xl">
              {done.places.map(p => (
                <li key={p.place.id} className="flex items-center justify-between gap-3 px-4 py-3">
                  <span className="min-w-0">
                    <span className="block font-mono text-body font-semibold text-primary" dir="ltr">{p.place.code}</span>
                    <span className="block text-small text-muted truncate">{placePath(p.place, t)}</span>
                  </span>
                  <span className="text-body text-primary tabular-nums">{t('putAway.done.pieces', { count: p.pieces })}</span>
                </li>
              ))}
            </ul>
          )}
          {done.session.stillWaiting > 0 && (
            <Alert tone="warning" title={t('putAway.done.stillWaiting', { count: done.session.stillWaiting })} />
          )}
          <Button className="w-full" size="lg" onClick={() => navigate('/put-away')}>{t('putAway.done.back')}</Button>
        </div>
      </div>
    )
  }

  const current = session.current

  return (
    <div className="flex flex-col h-screen bg-base" data-testid="put-away-scan">
      <header className="bg-panel border-b border-line px-4 sm:px-6 h-16 flex-shrink-0 flex items-center justify-between gap-3">
        <div className="flex items-baseline gap-3 min-w-0">
          <h1 className="text-h3 text-primary">{t('putAway.title')}</h1>
          <span className="text-small text-muted truncate">{session.locationName}</span>
        </div>
        <PhoneScanButton />
      </header>

      {closedElsewhere && (
        <div className="px-4 sm:px-6 pt-4" data-testid="put-away-closed-elsewhere">
          <Alert tone="warning" title={t('putAway.scan.closedElsewhere')}>
            <Button variant="secondary" size="sm" onClick={() => navigate('/put-away')}>{t('putAway.scan.back')}</Button>
          </Alert>
        </div>
      )}

      <div className="flex-1 min-h-0 overflow-y-auto lg:overflow-hidden p-4 sm:p-6 flex flex-col lg:flex-row gap-5">
        <section aria-label={t('putAway.scan.currentPlace')} className="lg:flex-[1.25] flex flex-col gap-4 min-w-0">
          {current ? (
            <div className="rounded-2xl bg-grey-900 text-white p-6 sm:p-7 flex flex-col gap-2.5" data-testid="put-away-current">
              <p className="text-caption font-semibold uppercase tracking-widest text-white/70">
                {t(`putAway.scan.puttingInto.${current.place.kind === 'box' ? 'box' : 'shelf'}`)}
              </p>
              <p className="font-mono text-[44px] sm:text-[54px] font-semibold leading-none break-all" dir="ltr"
                data-testid="put-away-current-code">{current.place.code}</p>
              <p className="text-body-lg text-white/80">{placePath(current.place, t)}</p>
              <div className="flex flex-wrap gap-2.5 mt-2">
                <span className="rounded-lg bg-white/10 px-4 py-2.5">
                  <span className="text-h3 font-bold tabular-nums" data-testid="put-away-here-now">{current.putHereNow}</span>{' '}
                  <span className="text-small text-white/70">{t('putAway.scan.putHereNow')}</span>
                </span>
                <span className="rounded-lg bg-white/10 px-4 py-2.5">
                  <span className="text-h3 font-bold tabular-nums">{current.onThisPlace}</span>{' '}
                  <span className="text-small text-white/70">
                    {t(`putAway.scan.onThis.${current.place.kind === 'box' ? 'box' : 'shelf'}`)}
                  </span>
                </span>
              </div>
            </div>
          ) : (
            <div className="rounded-2xl border-2 border-dashed border-line bg-surface p-7 flex items-center gap-4"
              data-testid="put-away-no-place">
              <Layers size={28} strokeWidth={1.75} className="text-muted flex-shrink-0" />
              <div>
                <p className="text-h3 text-primary">{t('putAway.scan.startTitle')}</p>
                <p className="text-small text-muted">{t('putAway.scan.startBody')}</p>
              </div>
            </div>
          )}

          <div className="rounded-xl border-2 border-trace-blue bg-surface p-4 space-y-2">
            <p className="text-body-lg font-semibold text-primary">
              {current ? t('putAway.scan.scanPiece') : t('putAway.scan.scanPlace')}
            </p>
            <p className="text-small text-muted">{current ? t('putAway.scan.scanPieceHint') : t('putAway.scan.scanPlaceHint')}</p>
            <ScanShell scanner={scanner} placeholder={t('putAway.scan.placeholder')} />
          </div>

          <div className="card p-4 space-y-2" data-testid="put-away-totals">
            <p className="text-caption font-semibold uppercase tracking-wider text-muted">{t('putAway.scan.thisSession')}</p>
            <div className="flex flex-wrap gap-x-6 gap-y-1 text-body text-primary">
              <span><span className="text-h3 font-bold tabular-nums" data-testid="put-away-placed">{session.placed}</span> {t('putAway.scan.placed')}</span>
              <span><span className="text-h3 font-bold tabular-nums">{session.placesUsed}</span> {t('putAway.scan.placesUsed')}</span>
              <span><span className="text-h3 font-bold tabular-nums text-warning-text" data-testid="put-away-still-waiting">{session.stillWaiting}</span> {t('putAway.scan.stillWaiting')}</span>
            </div>
          </div>
        </section>

        <section aria-label={t('putAway.scan.lastScans')}
          className="lg:flex-1 card flex flex-col min-w-0 lg:min-h-0 overflow-hidden" data-testid="put-away-last-scans">
          <p className="px-5 py-4 text-body font-semibold text-primary border-b border-line">{t('putAway.scan.lastScans')}</p>
          {scanner.recentScans.length === 0 ? (
            <p className="px-5 py-6 text-small text-muted">{t('putAway.scan.noScans')}</p>
          ) : (
            <ul className="flex-1 overflow-y-auto divide-y divide-line">
              {scanner.recentScans.map(s => {
                const data = s.data as ScanData | undefined
                const r = data?.result
                const d = r ? describeScan(r, t) : { title: data?.error ?? t('putAway.error'), detail: s.barcode }
                const kind = !r ? 'error' : r.outcome === 'PLACE_SET' ? 'switch' : r.outcome === 'MOVED' ? 'moved'
                  : SUCCESS.has(r.outcome) ? 'ok' : 'error'
                return (
                  <li key={s.key} data-testid="put-away-scan-row" data-outcome={r?.outcome ?? 'ERROR'}
                    className={cn('flex gap-3 px-5 py-3.5 items-start',
                      kind === 'error' && 'bg-critical/[0.06]', kind === 'moved' && 'bg-trace-blue/[0.05]')}>
                    <span className={cn('w-8 h-8 rounded-full flex items-center justify-center flex-shrink-0',
                      kind === 'ok' ? 'bg-success/15 text-success' : kind === 'moved' ? 'bg-trace-blue/15 text-trace-blue'
                        : kind === 'switch' ? 'bg-elevated text-secondary' : 'bg-critical/15 text-critical')}>
                      {kind === 'ok' ? <Check size={16} strokeWidth={2.5} /> : kind === 'moved' ? <ArrowRight size={16} strokeWidth={2.5} className="rtl:rotate-180" />
                        : kind === 'switch' ? <Layers size={16} strokeWidth={2} /> : <X size={16} strokeWidth={2.5} />}
                    </span>
                    <span className="flex-1 min-w-0">
                      <span className={cn('block text-body font-semibold', kind === 'error' ? 'text-critical-text' : 'text-primary')}>
                        <bdi>{d.title}</bdi>
                      </span>
                      <span className={cn('block text-small', kind === 'moved' ? 'text-trace-blue' : 'text-muted')}>
                        <bdi>{d.detail}</bdi>
                      </span>
                    </span>
                    {r?.viaPhone && <ViaPhoneTag />}
                  </li>
                )
              })}
            </ul>
          )}
        </section>
      </div>

      <footer className="bg-panel border-t border-line px-4 sm:px-6 h-[76px] flex-shrink-0 flex items-center justify-between gap-3">
        <p className="text-small text-muted">{finishError ?? t('putAway.scan.footer')}</p>
        <Button size="lg" loading={finishing} onClick={finish}>{t('putAway.scan.finish')}</Button>
      </footer>
    </div>
  )
}
