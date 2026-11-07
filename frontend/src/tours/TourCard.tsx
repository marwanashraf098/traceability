import { useEffect, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { articleForTour } from '../help/helpRegistry'
import { CircleHelp, X } from 'lucide-react'
import { cn } from '../components/ui'
import { getRoleFromToken } from '../api'
import { canSeeTour, TOURS, type TourId } from './tours'
import { endTour, goToStep, shouldAutoStart, startTour, startTourIfIdle, useActiveTour } from './tourState'

/**
 * Onboarding O5 — the tour card (Step 0 rules): NON-modal, no focus trap, no autoFocus, never calls focus(), no inputs,
 * no document keydown listener, never sets a scanner's focusPaused, never a Modal. Its buttons are plain buttons whose
 * clicks bubble normally — so a scan screen's own click-to-refocus (useScanner, untouched) puts focus straight back
 * on the scan input. Docked bottom-end on desktop, a bottom sheet on a phone; logical start / end for RTL.
 *
 * The optional ring: the step's `data-tour-anchor` element, when it's on screen, gets `data-tour-highlight` (an
 * outline in index.css, pointer-events untouched) for as long as the step shows.
 *
 * Phone widths (≤ 640 px, the bottom sheet): the page's scroll container — the nearest scrolling ancestor (Layout's
 * <main>), else the document body — gets bottom padding equal to the sheet's height while the tour is open, so every
 * control (Pickups' Close, …) can be scrolled into view above the sheet. Restored when the tour closes.
 */
const PHONE = '(max-width: 640px)'

function scrollContainer(from: HTMLElement | null): HTMLElement {
  for (let el = from?.parentElement ?? null; el && el !== document.body; el = el.parentElement) {
    const oy = getComputedStyle(el).overflowY
    if (oy === 'auto' || oy === 'scroll') return el
  }
  return document.body
}

function useSheetPadding(ref: React.RefObject<HTMLElement>, showing: boolean) {
  useEffect(() => {
    const sheet = ref.current
    if (!showing || !sheet || typeof window.matchMedia !== 'function') return
    const mq = window.matchMedia(PHONE)
    const target = scrollContainer(sheet)
    const original = target.style.paddingBottom
    const apply = () => {
      target.style.paddingBottom = mq.matches ? `${Math.ceil(sheet.getBoundingClientRect().height)}px` : original
    }
    apply()
    const ro = typeof ResizeObserver === 'function' ? new ResizeObserver(apply) : null
    ro?.observe(sheet)
    mq.addEventListener?.('change', apply)
    return () => {
      ro?.disconnect()
      mq.removeEventListener?.('change', apply)
      target.style.paddingBottom = original
    }
  }, [ref, showing])
}

function useAnchorRing(anchor: string | undefined) {
  useEffect(() => {
    if (!anchor) return
    const el = document.querySelector(`[data-tour-anchor="${anchor}"]`)
    if (!el) return
    el.setAttribute('data-tour-highlight', '')
    return () => el.removeAttribute('data-tour-highlight')
  }, [anchor])
}

export function TourCard({ tourId }: { tourId: TourId }) {
  const { t } = useTranslation()
  const a = useActiveTour()
  const def = TOURS[tourId]
  const showing = a?.id === tourId
  const step = showing ? Math.min(a!.step, def.steps.length - 1) : 0
  const s = def.steps[step]
  const ref = useRef<HTMLElement>(null)
  useAnchorRing(showing ? s.anchor : undefined)
  useSheetPadding(ref, showing)
  if (!showing) return null
  const last = step === def.steps.length - 1
  const n = def.steps.length
  // Owners / managers only: workers have no help center (their "?" only replays tours).
  const role = getRoleFromToken()
  const readMore = role === 'owner' || role === 'manager' ? articleForTour(tourId) : null

  return (
    <section
      ref={ref}
      role="region"
      aria-label={t('tours.region', { tour: t(`tours.${tourId}.name`) })}
      data-testid={`tour-${tourId}`}
      data-step={step + 1}
      className={cn(
        'fixed z-dropdown bg-surface border border-line shadow-xl flex flex-col gap-3 p-5',
        'bottom-4 end-4 w-[360px] rounded-2xl',
        'max-[640px]:bottom-0 max-[640px]:end-0 max-[640px]:start-0 max-[640px]:w-auto max-[640px]:rounded-b-none max-[640px]:rounded-t-2xl',
        'animate-fadeIn motion-reduce:animate-none',
      )}
    >
      <div className="flex items-center justify-between gap-3">
        <span className="text-caption font-bold tracking-wide text-trace-blue uppercase" data-testid="tour-eyebrow">
          {t(`tours.${tourId}.name`)} · {t('tours.stepOf', { step: step + 1, total: n })}
        </span>
        <button type="button" onClick={() => endTour(true)} data-testid="tour-skip"
          className="text-small text-muted hover:text-primary inline-flex items-center gap-1">
          {t('tours.skip')} <X size={13} strokeWidth={2} aria-hidden />
        </button>
      </div>
      <div className="flex flex-col gap-1.5" aria-live="polite">
        <h2 className="text-[16px] font-bold text-primary leading-snug">{t(`${s.key}.title`)}</h2>
        <p className="text-small text-primary/80 leading-relaxed">{t(`${s.key}.body`)}</p>
        {readMore && (
          <Link to={`/help/${readMore}`} data-testid="tour-read-more"
            className="self-start text-small font-medium text-trace-blue hover:text-trace-blue-hover">
            {t('helpCenter.readMore')}
          </Link>
        )}
        {s.hasTip && (
          <p className="text-small text-warning-text bg-warning/[0.08] rounded-lg px-3 py-2 leading-relaxed" data-testid="tour-tip">
            {t(`${s.key}.tip`)}
          </p>
        )}
      </div>
      <div className="flex items-center justify-between gap-3 pt-1">
        <span className="flex gap-1.5" aria-hidden>
          {def.steps.map((_, i) => (
            <span key={i} className={cn('h-2 rounded-full', i === step ? 'w-5 bg-trace-blue' : i < step ? 'w-2 bg-trace-blue/30' : 'w-2 bg-line')} />
          ))}
        </span>
        <span className="flex gap-2">
          {step > 0 && (
            <button type="button" onClick={() => goToStep(step - 1)} data-testid="tour-back"
              className="h-9 px-3.5 rounded-lg border border-line text-small font-semibold text-primary hover:bg-elevated">
              {t('tours.back')}
            </button>
          )}
          <button type="button" onClick={() => (last ? endTour(true) : goToStep(step + 1))} data-testid="tour-next"
            className="h-9 px-4 rounded-lg bg-trace-blue text-white text-small font-semibold hover:bg-trace-blue-hover">
            {last ? t('tours.done') : t('tours.next')}
          </button>
        </span>
      </div>
    </section>
  )
}

/**
 * Put on every view of a toured page (list AND scan screen). Starts the tour by itself when shouldAutoStart says so
 * (once; never while another tour shows), and renders the card while it's this page's.
 */
export function PageTour({ tourId }: { tourId: TourId }) {
  const role = getRoleFromToken()
  const allowed = canSeeTour(tourId, role)
  useEffect(() => {
    if (!allowed) return
    let live = true
    shouldAutoStart(tourId).then(go => {
      if (live && go) startTourIfIdle(tourId)
    })
    return () => { live = false }
  }, [tourId, allowed])
  return allowed ? <TourCard tourId={tourId} /> : null
}

/**
 * The "?" in a page header: replays the page's tour from step 1 (or, on Pick & Pack, the mode chooser). A plain
 * button — it never moves focus itself, and its click bubbles to the scan screen's refocus.
 */
export function TourHelpButton({ tourId, className }: { tourId: TourId | 'pick_pack_mode'; className?: string }) {
  const { t } = useTranslation()
  const role = getRoleFromToken()
  if (tourId !== 'pick_pack_mode' && !canSeeTour(tourId, role)) return null
  return (
    <button type="button" onClick={() => startTour(tourId, true)} data-testid="tour-help"
      aria-label={t('tours.help')} title={t('tours.help')}
      className={cn('w-9 h-9 flex-shrink-0 rounded-full border border-line bg-surface text-muted hover:text-primary hover:bg-elevated inline-flex items-center justify-center', className)}>
      <CircleHelp size={17} strokeWidth={1.9} aria-hidden />
    </button>
  )
}
