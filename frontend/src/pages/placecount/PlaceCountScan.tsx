import { useEffect, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { ArrowRight, Check, Minus, ShieldCheck, X } from 'lucide-react'
import { useScanner, ScanMeta } from '../../hooks/useScanner'
import { usePhoneScanTarget } from '../../phone/usePhoneScanTarget'
import { PhoneScanButton } from '../../phone/PhoneScanButton'
import { ViaPhoneTag } from '../../phone/ViaPhoneTag'
import { ScanShell } from '../../components/ScanShell'
import { Alert, Button, Modal, Spinner, cn } from '../../components/ui'
import {
  cancelPlaceCount, finishPlaceCount, getPlaceCount, scanPlaceCount, PlaceCount, PlaceCountLine, PlaceCountPiece,
  PlaceCountScanResult, ServerMessageError,
} from '../../api'
import { placePath } from '../putaway/placePath'

/**
 * Warehouse places S5 — counting ONE place: full-screen, NOT Layout-wrapped (an active scan loop). The place is set
 * when the count starts; every scan only records what was found — the screen shows found here / moved here / not
 * found live — and nothing changes until Finish (one transaction, server-side). All scan mechanics are the shared
 * useScanner (+ the paired phone): one auto-focusing input, a FIFO queue so fast scans are never dropped.
 * ?from=settings|stock-take → where Back goes after Finish / Cancel.
 */

interface ScanData { result: PlaceCountScanResult | null; error?: string }

const GOOD = new Set(['FOUND_HERE', 'MOVE_HERE', 'PLACE_HERE', 'THIS_PLACE'])

const product = (p: PlaceCountPiece | null) => p
  ? [p.productTitle, p.variantTitle && p.variantTitle !== 'Default Title' ? p.variantTitle : null].filter(Boolean).join(' · ')
  : ''

/** The two lines a scan shows (last scans, the phone). */
export function describeCountScan(r: PlaceCountScanResult, t: TFunction): { title: string; detail: string } {
  const piece = r.piece?.code ?? r.code
  const here = r.count.place.code
  switch (r.outcome) {
    case 'FOUND_HERE': return { title: product(r.piece), detail: t('placeCount.scan.foundHere', { piece, place: here }) }
    case 'MOVE_HERE':  return { title: product(r.piece), detail: t('placeCount.scan.moveHere', { piece, from: r.fromPlace?.code ?? '' }) }
    case 'PLACE_HERE': return { title: product(r.piece), detail: t('placeCount.scan.placeHere', { piece }) }
    case 'OTHER_LOCATION':
      return { title: t('placeCount.scan.otherLocation', { location: r.locationName ?? '' }), detail: t('placeCount.scan.noChange', { piece }) }
    case 'NOT_PLACEABLE':
      return { title: t('placeCount.scan.notPlaceable', { status: t(`lookup.pieceStatus.${r.status}`, { defaultValue: r.status ?? '' }) }),
               detail: t('placeCount.scan.noChange', { piece }) }
    case 'UNTAGGED':   return { title: t('placeCount.scan.untagged'), detail: t('placeCount.scan.noChange', { piece }) }
    case 'DUPLICATE':  return { title: t('placeCount.scan.duplicate'), detail: piece }
    case 'THIS_PLACE': return { title: t('placeCount.scan.thisPlace', { place: here }), detail: t('placeCount.scan.scanPieces') }
    case 'COUNT_SEPARATELY':
      return { title: t(r.place?.kind === 'box' || r.place?.kind === 'shelf' ? 'placeCount.scan.countSeparately' : 'placeCount.scan.notAPiece',
                 { code: r.place?.code ?? '' }),
               detail: t('placeCount.scan.nothingRecorded') }
    default: return { title: t('placeCount.scan.unknown'), detail: t('placeCount.scan.noChange', { piece }) }
  }
}

/** Lines of one variant grouped: "Black T-shirt · M  2 / 3". */
function byVariant(pieces: PlaceCountPiece[]): { key: string; label: string; pieces: PlaceCountPiece[] }[] {
  const groups = new Map<string, { key: string; label: string; pieces: PlaceCountPiece[] }>()
  for (const p of pieces) {
    const g = groups.get(p.variantId) ?? { key: p.variantId, label: product(p), pieces: [] }
    g.pieces.push(p)
    groups.set(p.variantId, g)
  }
  return [...groups.values()].sort((a, b) => a.label.localeCompare(b.label))
}

export default function PlaceCountScan() {
  const { id } = useParams<{ id: string }>()
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const backTo = searchParams.get('from') === 'stock-take' ? '/stock-take' : '/settings?tab=places'
  const [count, setCount] = useState<PlaceCount | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [closedElsewhere, setClosedElsewhere] = useState(false)

  const readable = (e: unknown) => e instanceof ServerMessageError ? e.messageFor(i18n.language) : t('placeCount.error')

  useEffect(() => {
    if (!id) return
    getPlaceCount(id)
      .then(setCount)
      .catch(e => setLoadError(readable(e)))
      .finally(() => setLoading(false))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])

  const phone = usePhoneScanTarget({
    label: t('phone.target.placeCount', { code: count?.place.code ?? '' }),
    describe: (_code, o) => o.label,
    paused: confirming || busy,
  })

  // SAFETY-CRITICAL — the one-place count scan handler (new in S5): every scan, from the station's scanner or the
  // paired phone, goes through the shared useScanner queue (one in flight, in order, none dropped) to ONE endpoint,
  // which only RECORDS the scan; the server decides piece vs sticker and the result. Each scan gets exactly one
  // outcome and its own entry; nothing on a piece changes until Finish. Do not add a second scan path, a client-side
  // count, or apply anything per scan.
  const scanner = phone.attach(useScanner({
    focusPaused: confirming || busy,
    recentScansLimit: 8,
    onScan: phone.wrap(async (code: string, meta?: ScanMeta) => {
      if (!id) return { success: false }
      const relayEventId = typeof meta?.relayEventId === 'string' ? meta.relayEventId : undefined
      let r: PlaceCountScanResult
      try {
        r = await scanPlaceCount(id, code, relayEventId)
      } catch (e: unknown) {
        if (e instanceof ServerMessageError && e.code === 'COUNT_CLOSED') setClosedElsewhere(true)
        return { success: false, label: readable(e), data: { result: null, error: readable(e) } as ScanData }
      }
      setCount(r.count)
      const d = describeCountScan(r, t)
      return { success: GOOD.has(r.outcome), label: d.title, data: { result: r } as ScanData }
    }),
  }))

  async function finish() {
    if (!id) return
    scanner.clearQueue()
    setBusy(true)
    setActionError(null)
    try {
      setCount(await finishPlaceCount(id))
      setConfirming(false)
    } catch (e: unknown) {
      setActionError(readable(e))
    } finally {
      setBusy(false)
    }
  }

  async function cancel() {
    if (!id) return
    scanner.clearQueue()
    setBusy(true)
    try {
      await cancelPlaceCount(id)
      navigate(backTo)
    } catch (e: unknown) {
      setActionError(readable(e))
      setBusy(false)
    }
  }

  if (loading) return <div className="flex items-center justify-center h-screen bg-base"><Spinner size={32} /></div>
  if (loadError || !count) {
    return (
      <div className="flex flex-col items-center justify-center h-screen bg-base gap-4 px-6 text-center">
        <p className="text-danger text-body">{loadError ?? t('placeCount.error')}</p>
        <Button variant="secondary" onClick={() => navigate(backTo)}>{t('placeCount.back')}</Button>
      </div>
    )
  }

  // After Finish (or a count that already ended): ONE view — what the count did.
  if (count.status !== 'open') {
    const a = count.applied
    return (
      <div className="flex flex-col items-center justify-center min-h-screen bg-base px-6 py-10" data-testid="place-count-done">
        <div className="card p-6 w-full max-w-lg space-y-5">
          <div className="space-y-1">
            <h1 className="text-h2 text-primary">
              {count.status === 'finished' ? t('placeCount.done.title', { place: count.place.code }) : t('placeCount.done.cancelled')}
            </h1>
            <p className="text-body text-muted"><bdi>{placePath(count.place, t)}</bdi> · {count.locationName}</p>
          </div>
          {count.status === 'finished' && a && (
            <ul className="divide-y divide-line border border-line rounded-xl" data-testid="place-count-applied">
              <li className="flex justify-between px-4 py-3 text-body"><span>{t('placeCount.done.found')}</span><b className="tabular-nums">{count.found}</b></li>
              <li className="flex justify-between px-4 py-3 text-body"><span>{t('placeCount.done.moved')}</span><b className="tabular-nums">{a.moved}</b></li>
              <li className="flex justify-between px-4 py-3 text-body"><span>{t('placeCount.done.placed')}</span><b className="tabular-nums">{a.placed}</b></li>
              <li className="flex justify-between px-4 py-3 text-body"><span>{t('placeCount.done.cleared')}</span><b className="tabular-nums">{a.cleared}</b></li>
            </ul>
          )}
          {count.status === 'finished' && a && a.cleared > 0 && (
            <p className="text-small text-muted">{t('placeCount.done.clearedNote', { count: a.cleared })}</p>
          )}
          {a && a.skipped.length > 0 && (
            <Alert tone="warning" title={t('placeCount.done.skipped', { count: a.skipped.length })}>
              <ul className="text-small space-y-0.5">
                {a.skipped.map(s => (
                  <li key={s.pieceId}><span className="font-mono" dir="ltr">{s.code ?? s.pieceId}</span> — {t(`placeCount.skip.${s.reason}`)}</li>
                ))}
              </ul>
            </Alert>
          )}
          <Button className="w-full" size="lg" onClick={() => navigate(backTo)}>{t('placeCount.back')}</Button>
        </div>
      </div>
    )
  }

  const notFoundGroups = byVariant(count.notFoundPieces)
  const foundGroups = byVariant(count.foundLines.map(l => l.piece!).filter(Boolean))
  const expectedOf = (variantId: string) =>
    count.foundLines.filter(l => l.piece?.variantId === variantId).length
    + count.notFoundPieces.filter(p => p.variantId === variantId).length
  const placeKind = count.place.kind === 'box' ? 'box' : 'shelf'

  return (
    <div className="flex flex-col h-screen bg-base" data-testid="place-count-scan">
      <header className="bg-panel border-b border-line px-4 sm:px-6 h-16 flex-shrink-0 flex items-center justify-between gap-3">
        <div className="flex items-center gap-3 min-w-0">
          <h1 className="text-h3 text-primary whitespace-nowrap">{t('placeCount.title')}</h1>
          <span className="font-mono text-body font-semibold bg-elevated rounded-md px-2.5 py-1" dir="ltr"
            data-testid="place-count-code">{count.place.code}</span>
          <span className="text-small text-muted truncate"><bdi>{placePath(count.place, t)}</bdi></span>
        </div>
        <PhoneScanButton />
      </header>

      <div className="flex-1 min-h-0 overflow-y-auto p-4 sm:p-6 flex flex-col gap-4">
        <div className="flex items-center gap-3 rounded-xl border border-trace-blue/30 bg-trace-blue/[0.08] px-4 py-3 text-body text-primary"
          data-testid="place-count-safety">
          <ShieldCheck size={20} strokeWidth={2} className="text-trace-blue flex-shrink-0" aria-hidden />
          <span>{t('placeCount.safety')}</span>
        </div>

        {closedElsewhere && <Alert tone="warning" title={t('placeCount.closedElsewhere')} />}

        <div className="grid grid-cols-2 lg:grid-cols-4 gap-3" data-testid="place-count-counters">
          <Counter value={count.expected} label={t('placeCount.counter.expected')} testId="expected" />
          <Counter value={count.found} label={t('placeCount.counter.found')} tone="text-success" testId="found" />
          <Counter value={count.movedHere} label={t('placeCount.counter.movedHere')} tone="text-trace-blue" testId="moved" />
          <Counter value={count.notFound} label={t(`placeCount.counter.notFound.${placeKind}`)} tone="text-warning-text" testId="not-found" />
        </div>

        <div className="rounded-xl border-2 border-trace-blue bg-surface p-4 space-y-2">
          <p className="text-body-lg font-semibold text-primary">{t('placeCount.scanTitle')}</p>
          <ScanShell scanner={scanner} placeholder={t('placeCount.placeholder')} />
          {scanner.recentScans.length > 0 && (
            <ul className="divide-y divide-line" data-testid="place-count-last-scans">
              {scanner.recentScans.map(s => {
                const data = s.data as ScanData | undefined
                const r = data?.result
                const d = r ? describeCountScan(r, t) : { title: data?.error ?? t('placeCount.error'), detail: s.barcode }
                const kind = !r ? 'error' : r.outcome === 'FOUND_HERE' ? 'ok' : r.outcome === 'MOVE_HERE' || r.outcome === 'PLACE_HERE'
                  ? 'moved' : r.outcome === 'DUPLICATE' || r.outcome === 'THIS_PLACE' ? 'neutral' : 'error'
                return (
                  <li key={s.key} data-testid="place-count-scan-row" data-outcome={r?.outcome ?? 'ERROR'}
                    className="flex gap-3 py-2 items-start">
                    <span className={cn('w-7 h-7 rounded-full flex items-center justify-center flex-shrink-0',
                      kind === 'ok' ? 'bg-success/15 text-success' : kind === 'moved' ? 'bg-trace-blue/15 text-trace-blue'
                        : kind === 'neutral' ? 'bg-elevated text-secondary' : 'bg-critical/15 text-critical')}>
                      {kind === 'ok' ? <Check size={15} strokeWidth={2.5} /> : kind === 'moved' ? <ArrowRight size={15} strokeWidth={2.5} className="rtl:rotate-180" />
                        : kind === 'neutral' ? <Minus size={15} strokeWidth={2.5} /> : <X size={15} strokeWidth={2.5} />}
                    </span>
                    <span className="flex-1 min-w-0">
                      <span className={cn('block text-body font-medium', kind === 'error' ? 'text-critical-text' : 'text-primary')}><bdi>{d.title}</bdi></span>
                      <span className="block text-small text-muted"><bdi>{d.detail}</bdi></span>
                    </span>
                    {r?.viaPhone && <ViaPhoneTag />}
                  </li>
                )
              })}
            </ul>
          )}
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
          <section aria-label={t('placeCount.col.found', { count: count.found })} className="card overflow-hidden" data-testid="place-count-found">
            <p className="px-4 py-3 text-body font-semibold text-success border-b border-line">
              ✓ {t('placeCount.col.found', { count: count.found })}
            </p>
            {foundGroups.length === 0 ? <p className="px-4 py-4 text-small text-muted">{t('placeCount.col.none')}</p> : (
              <ul className="divide-y divide-line">
                {foundGroups.map(g => (
                  <li key={g.key} className="flex justify-between gap-3 px-4 py-3 text-body">
                    <span className="min-w-0"><bdi>{g.label}</bdi></span>
                    <b className="tabular-nums whitespace-nowrap" dir="ltr">{g.pieces.length} / {expectedOf(g.key)}</b>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section aria-label={t('placeCount.col.moved', { count: count.movedHere })} className="card overflow-hidden" data-testid="place-count-moved">
            <p className="px-4 py-3 text-body font-semibold text-trace-blue border-b border-line">
              {t('placeCount.col.moved', { count: count.movedHere })}
            </p>
            {count.movedLines.length === 0 ? <p className="px-4 py-4 text-small text-muted">{t('placeCount.col.none')}</p> : (
              <ul className="divide-y divide-line">
                {count.movedLines.map((l: PlaceCountLine) => (
                  <li key={l.piece?.pieceId ?? l.raw} className="px-4 py-3">
                    <p className="text-body text-primary"><bdi>{product(l.piece)}</bdi></p>
                    <p className="text-small text-muted">
                      {l.result === 'move_here'
                        ? t('placeCount.col.was', { from: l.fromPlace?.code ?? '', to: count.place.code })
                        : t('placeCount.col.hadNoPlace', { to: count.place.code })}
                      {' · '}<span className="font-mono" dir="ltr">{l.piece?.code}</span>
                    </p>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section aria-label={t(`placeCount.col.notFound.${placeKind}`, { count: count.notFound })} className="card overflow-hidden flex flex-col"
            data-testid="place-count-not-found">
            <p className="px-4 py-3 text-body font-semibold text-warning-text border-b border-line">
              {t(`placeCount.col.notFound.${placeKind}`, { count: count.notFound })}
            </p>
            {notFoundGroups.length === 0 ? <p className="px-4 py-4 text-small text-muted">{t('placeCount.col.none')}</p> : (
              <ul className="divide-y divide-line">
                {notFoundGroups.map(g => (
                  <li key={g.key} className="px-4 py-3">
                    <p className="text-body text-primary"><bdi>{g.label}</bdi>{g.pieces.length > 1 ? ` ×${g.pieces.length}` : ''}</p>
                    <p className="text-small text-muted font-mono" dir="ltr">{g.pieces.map(p => p.code).join('  ')}</p>
                  </li>
                ))}
              </ul>
            )}
            <p className="mt-auto px-4 py-3 bg-warning/[0.10] text-small text-warning-text">{t('placeCount.col.notFoundNote')}</p>
          </section>
        </div>

        {count.otherLines.length > 0 && (
          <section className="card overflow-hidden" data-testid="place-count-other">
            <p className="px-4 py-3 text-body font-semibold text-primary border-b border-line">
              {t('placeCount.col.other', { count: count.otherLines.length })}
            </p>
            <ul className="divide-y divide-line">
              {count.otherLines.map((l, i) => (
                <li key={(l.piece?.pieceId ?? l.raw) + i} className="flex justify-between gap-3 px-4 py-2.5 text-small">
                  <span className="min-w-0"><bdi>{l.piece ? product(l.piece) : l.raw}</bdi></span>
                  <span className="text-muted">{t(`placeCount.other.${l.result}`, {
                    location: l.locationName ?? '', status: t(`lookup.pieceStatus.${l.status}`, { defaultValue: l.status ?? '' }) })}</span>
                </li>
              ))}
            </ul>
          </section>
        )}
      </div>

      <footer className="bg-panel border-t border-line px-4 sm:px-6 h-[76px] flex-shrink-0 flex items-center justify-between gap-3">
        <p className="text-small text-muted">{actionError ?? t(`placeCount.footer.${placeKind}`)}</p>
        <div className="flex gap-2.5">
          <Button variant="tertiary" size="lg" disabled={busy} onClick={cancel}>{t('placeCount.cancel')}</Button>
          <Button size="lg" disabled={busy} onClick={() => setConfirming(true)}>{t('placeCount.finish')}</Button>
        </div>
      </footer>

      {confirming && (
        <Modal title={t('placeCount.confirm.title', { place: count.place.code })} onClose={() => setConfirming(false)}>
          <div className="space-y-4" data-testid="place-count-confirm">
            <ul className="text-body text-primary space-y-1.5">
              <li>{t('placeCount.confirm.moved', { count: count.movedHere })}</li>
              <li>{t('placeCount.confirm.cleared', { count: count.notFound })}</li>
            </ul>
            <p className="text-small text-muted">{t('placeCount.safety')}</p>
            {actionError && <Alert tone="critical" title={actionError} />}
            <div className="flex justify-end gap-2.5">
              <Button variant="secondary" onClick={() => setConfirming(false)}>{t('placeCount.keepScanning')}</Button>
              <Button loading={busy} onClick={finish}>{t('placeCount.finish')}</Button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  )
}

function Counter({ value, label, tone, testId }: { value: number; label: string; tone?: string; testId: string }) {
  return (
    <div className="card p-4" data-testid={`place-count-n-${testId}`}>
      <p className={cn('text-h1 font-bold tabular-nums', tone ?? 'text-primary')}>{value}</p>
      <p className="text-small text-muted">{label}</p>
    </div>
  )
}
