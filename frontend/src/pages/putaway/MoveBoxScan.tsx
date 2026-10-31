import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { ArrowRight, Boxes, Check, X } from 'lucide-react'
import { useScanner, ScanMeta } from '../../hooks/useScanner'
import { usePhoneScanTarget } from '../../phone/usePhoneScanTarget'
import { PhoneScanButton } from '../../phone/PhoneScanButton'
import { ScanShell } from '../../components/ScanShell'
import { Button, cn } from '../../components/ui'
import { checkMoveBox, moveBox, BoxCheck, BoxMoveResult, ServerMessageError } from '../../api'
import { placePath } from './placePath'

/**
 * Warehouse places S5 — Put away › Move a box: full-screen (an active scan loop, like /put-away/:id/scan). Scan a
 * BOX sticker, then a shelf sticker (the box goes onto it) or a zone sticker (onto that zone's floor). The pieces in
 * the box keep their box and follow it. One auto-focusing input (the shared useScanner + the paired phone).
 */

interface Line { ok: boolean; title: string; detail: string }

export default function MoveBoxScan() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const [box, setBox] = useState<BoxCheck | null>(null)
  /** The box sticker as scanned (sent again with the target). */
  const [boxScan, setBoxScan] = useState('')
  const [last, setLast] = useState<BoxMoveResult | null>(null)

  const readable = (e: unknown) => e instanceof ServerMessageError ? e.messageFor(i18n.language) : t('boxMove.error')

  const phone = usePhoneScanTarget({ label: t('phone.target.boxMove'), describe: (_code, o) => o.label })

  // SAFETY-CRITICAL — the box-move scan handler (new in S5): every scan (station scanner or paired phone) goes through
  // the shared useScanner queue, in order, none dropped. The first scan is checked as a box; the next one is the
  // target and moves it (server-side: same location, shelf or zone only). A refused scan changes nothing and keeps
  // the chosen box. Do not add a second scan path.
  const scanner = phone.attach(useScanner({
    recentScansLimit: 8,
    onScan: phone.wrap(async (code: string, _meta?: ScanMeta) => {
      if (!box) {
        try {
          const b = await checkMoveBox(code)
          setBox(b)
          setBoxScan(code)
          setLast(null)
          const line: Line = { ok: true, title: t('boxMove.boxChosen', { code: b.box.code }), detail: t('boxMove.nowScanTarget') }
          return { success: true, label: line.title, data: line }
        } catch (e: unknown) {
          const line: Line = { ok: false, title: readable(e), detail: code }
          return { success: false, label: line.title, data: line }
        }
      }
      try {
        const r = await moveBox(boxScan, code)
        setLast(r)
        setBox(null)
        const line: Line = r.outcome === 'MOVED'
          ? { ok: true, title: t('boxMove.moved', { box: r.box.code, to: r.to.code }), detail: placePath(r.box, t) }
          : { ok: true, title: t('boxMove.alreadyThere', { box: r.box.code, to: r.to.code }), detail: placePath(r.box, t) }
        return { success: true, label: line.title, data: line }
      } catch (e: unknown) {
        const line: Line = { ok: false, title: readable(e), detail: code }
        return { success: false, label: line.title, data: line }
      }
    }),
  }))

  return (
    <div className="flex flex-col h-screen bg-base" data-testid="move-box">
      <header className="bg-panel border-b border-line px-4 sm:px-6 h-16 flex-shrink-0 flex items-center justify-between gap-3">
        <h1 className="text-h3 text-primary">{t('boxMove.title')}</h1>
        <PhoneScanButton />
      </header>

      <div className="flex-1 min-h-0 overflow-y-auto p-4 sm:p-6 flex flex-col lg:flex-row gap-5">
        <section className="lg:flex-[1.25] flex flex-col gap-4 min-w-0">
          {box ? (
            <div className="rounded-2xl bg-grey-900 text-white p-6 sm:p-7 flex flex-col gap-2.5" data-testid="move-box-chosen">
              <p className="text-caption font-semibold uppercase tracking-widest text-white/70">{t('boxMove.moving')}</p>
              <p className="font-mono text-[44px] font-semibold leading-none break-all" dir="ltr">{box.box.code}</p>
              <p className="text-body-lg text-white/80"><bdi>{placePath(box.box, t)}</bdi> · {box.locationName}</p>
              <p className="text-small text-white/70">{t('boxMove.piecesInside', { count: box.pieces })}</p>
            </div>
          ) : (
            <div className="rounded-2xl border-2 border-dashed border-line bg-surface p-7 flex items-center gap-4" data-testid="move-box-start">
              <Boxes size={28} strokeWidth={1.75} className="text-muted flex-shrink-0" />
              <div>
                <p className="text-h3 text-primary">{t('boxMove.startTitle')}</p>
                <p className="text-small text-muted">{t('boxMove.startBody')}</p>
              </div>
            </div>
          )}
          <div className="rounded-xl border-2 border-trace-blue bg-surface p-4 space-y-2">
            <p className="text-body-lg font-semibold text-primary">{box ? t('boxMove.scanTarget') : t('boxMove.scanBox')}</p>
            <p className="text-small text-muted">{box ? t('boxMove.scanTargetHint') : t('boxMove.scanBoxHint')}</p>
            <ScanShell scanner={scanner} placeholder={t('boxMove.placeholder')} />
          </div>
          {last && last.outcome === 'MOVED' && (
            <div className="card p-4 flex items-center gap-3" data-testid="move-box-done">
              <Check size={20} strokeWidth={2.5} className="text-success flex-shrink-0" />
              <p className="text-body text-primary">
                <span className="font-mono" dir="ltr">{last.box.code}</span>{' '}
                <span className="text-muted">{last.from?.code ?? ''}</span>{' '}
                <ArrowRight size={14} className="inline rtl:rotate-180" />{' '}
                <span className="font-mono" dir="ltr">{last.to.code}</span>
              </p>
            </div>
          )}
        </section>

        <section className="lg:flex-1 card flex flex-col min-w-0 lg:min-h-0 overflow-hidden" data-testid="move-box-last-scans">
          <p className="px-5 py-4 text-body font-semibold text-primary border-b border-line">{t('boxMove.lastScans')}</p>
          {scanner.recentScans.length === 0 ? (
            <p className="px-5 py-6 text-small text-muted">{t('boxMove.noScans')}</p>
          ) : (
            <ul className="flex-1 overflow-y-auto divide-y divide-line">
              {scanner.recentScans.map(s => {
                const d = s.data as Line | undefined
                const ok = d?.ok ?? s.success
                return (
                  <li key={s.key} className={cn('flex gap-3 px-5 py-3.5 items-start', !ok && 'bg-critical/[0.06]')} data-testid="move-box-scan-row">
                    <span className={cn('w-8 h-8 rounded-full flex items-center justify-center flex-shrink-0',
                      ok ? 'bg-success/15 text-success' : 'bg-critical/15 text-critical')}>
                      {ok ? <Check size={16} strokeWidth={2.5} /> : <X size={16} strokeWidth={2.5} />}
                    </span>
                    <span className="flex-1 min-w-0">
                      <span className={cn('block text-body font-semibold', ok ? 'text-primary' : 'text-critical-text')}><bdi>{d?.title ?? s.label}</bdi></span>
                      <span className="block text-small text-muted"><bdi>{d?.detail ?? s.barcode}</bdi></span>
                    </span>
                  </li>
                )
              })}
            </ul>
          )}
        </section>
      </div>

      <footer className="bg-panel border-t border-line px-4 sm:px-6 h-[76px] flex-shrink-0 flex items-center justify-between gap-3">
        {box ? <Button variant="tertiary" onClick={() => setBox(null)}>{t('boxMove.changeBox')}</Button> : <span />}
        <Button size="lg" onClick={() => navigate('/put-away')}>{t('boxMove.done')}</Button>
      </footer>
    </div>
  )
}
