import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Check, MessageCircle } from 'lucide-react'
import Layout from '../../components/Layout'
import { useStation } from '../../components/StationProvider'
import { Alert, Button, Input, ProductThumb, Spinner, cn } from '../../components/ui'
import { ScanShell } from '../../components/ScanShell'
import { useScanner } from '../../hooks/useScanner'
import { usePhoneScanTarget } from '../../phone/usePhoneScanTarget'
import { PhoneScanButton } from '../../phone/PhoneScanButton'
import {
  authFetch, getCatalog, getLabelRuns, getReceivingSuggestion, getRoleFromToken, patchOnboardingPrefs,
  type CatalogProduct, type LabelRunSummary,
} from '../../api'
import { getAccessToken } from '../../auth'
import { loadCapabilities } from '../../capabilities'
import { useOnboardingProgress } from '../../onboardingProgress'
import Receiving, { api, RunPicker, SessionView, type Line, type SessionDetail } from '../Receiving'
import ProductSelectionGrid from './ProductSelectionGrid'
import ReceivingModeCards, { type ReceivingMode } from './ReceivingModeCards'
import { clearGuidedSession, guideHidden, readGuidedSession, setGuideHidden, writeGuidedSession } from './guidedState'
import { GateFrame, HELP_WHATSAPP_URL } from '../onboarding/SetupLayout'

// Onboarding O4 — the guided FIRST receiving session (design/onboarding/Traced_onboarding_Flow-5-Receive-Start,
// Flow-6-Session, Flow-7-Done). Full-screen (no Layout): Flow-5 → the REAL receiving session view (the same
// endpoints and SessionView as Receiving: POST sessions → lines → finalize → labels) with a guide panel beside it →
// Flow-7, once. Order that actually works (Step 0 #4): add product → close session → print labels → stick → check
// by scanning — the check is READ-ONLY against GET /receiving/sessions/{id}/pieces (nothing is recorded).
//
// Who: owner / manager, never station mode, only while progress' first_receiving isn't done (or to resume the
// guided session of this tab, guidedState). Anyone else gets the normal Receiving page.

/** Step 0 #5: "Where is this stock now? · Move from my Shopify location" would write to a non-Traced Shopify
 *  location (breaks the Location-target invariant) — not designed, so hidden. Nothing renders it while false. */
export const MOVE_FROM_SHOPIFY_LOCATION = false

const PACES = [
  { key: 'relaxed', perDay: 50 },
  { key: 'steady', perDay: 150 },
  { key: 'sprint', perDay: 300 },
] as const

function authHeaders() {
  const t = getAccessToken()
  return { 'Content-Type': 'application/json', ...(t ? { Authorization: `Bearer ${t}` } : {}) }
}

function Loading() {
  return (
    <div className="min-h-screen bg-bg flex items-center justify-center" data-testid="guided-loading">
      <Spinner />
    </div>
  )
}

// ── The /receiving route: guided or the normal page ──────────────────────────

export function ReceivingRoute() {
  const [params] = useSearchParams()
  const { stationMode } = useStation()
  const role = getRoleFromToken()
  const stored = readGuidedSession()
  const guidedParam = params.get('guided') === '1'
  const sessionParam = params.get('session')
  const resumable = !!stored && (sessionParam === stored.id || (guidedParam && !sessionParam))
  const asked = guidedParam || resumable
  const allowed = asked && !stationMode && (role === 'owner' || role === 'manager')
  const progress = useOnboardingProgress(allowed)
  // Decided once: finishing the session marks first_receiving done, and the flow must still reach Flow-7.
  const decision = useRef<'guided' | 'normal' | null>(null)
  if (decision.current === null) {
    if (!allowed) decision.current = 'normal'
    else if (progress !== undefined) {
      const first = progress?.steps.find(s => s.key === 'first_receiving')
      decision.current = resumable || (first !== undefined && !first.done) ? 'guided' : 'normal'
    }
  }
  if (decision.current === null) return <Loading />
  if (decision.current === 'guided') return <GuidedReceiving resumeId={resumable ? stored!.id : null} />
  if (guidedParam) return <Navigate to="/receiving" replace />
  return <Layout><Receiving /></Layout>
}

// ── The guided flow ───────────────────────────────────────────────────────────

type Stage = { name: 'setup' } | { name: 'session'; id: string } | { name: 'done'; session: SessionDetail }

export default function GuidedReceiving({ resumeId }: { resumeId: string | null }) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [stage, setStage] = useState<Stage>(resumeId ? { name: 'session', id: resumeId } : { name: 'setup' })
  const skip = () => { clearGuidedSession(); navigate('/overview') }

  return (
    <GateFrame width="wide" onExit={skip} exitLabel={t('guidedReceiving.skip')}
      stepper={<span className="text-body text-muted" data-testid="guided-header">{t('guidedReceiving.headerTitle')}</span>}>
      {stage.name === 'setup' && <SetupStep onStarted={id => setStage({ name: 'session', id })} />}
      {stage.name === 'session' && (
        <SessionStep id={stage.id} onLost={() => { clearGuidedSession(); setStage({ name: 'setup' }) }}
          onFinished={session => setStage({ name: 'done', session })} />
      )}
      {stage.name === 'done' && <DoneStep session={stage.session} />}
    </GateFrame>
  )
}

// ── Flow-5: set up ────────────────────────────────────────────────────────────

interface Suggested { variantId: string; shopifyQuantity: number; mainOnHand: number | null; product: CatalogProduct; variantTitle: string }

function Section({ n, title, children, testId }: { n: number; title: string; children: ReactNode; testId?: string }) {
  return (
    <fieldset className="m-0 bg-surface border border-line rounded-2xl p-[22px] max-[640px]:p-4 flex flex-col gap-3 min-w-0" data-testid={testId}>
      <legend className="px-1.5 text-[13px] font-bold text-trace-blue uppercase tracking-wide">{n} · {title}</legend>
      {children}
    </fieldset>
  )
}

function SetupStep({ onStarted }: { onStarted: (id: string) => void }) {
  const { t } = useTranslation()
  const [flexible, setFlexible] = useState<boolean | null>(null)
  const [runs, setRuns] = useState<LabelRunSummary[] | null>(null)
  const [mainId, setMainId] = useState<string | null | undefined>(undefined)
  const [mode, setMode] = useState<ReceivingMode>('print_stick')
  const [runId, setRunId] = useState('')
  const [sug, setSug] = useState<Suggested | null | undefined>(undefined)
  const [picking, setPicking] = useState(false)
  const [qty, setQty] = useState('10')
  const [picked, setPicked] = useState<Map<string, number>>(() => new Map())
  const [starting, setStarting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let live = true
    loadCapabilities().then(c => {
      if (!live) return
      setFlexible(c.flexibleLabeling)
      if (!c.flexibleLabeling) { setRuns([]); return }
      getLabelRuns()
        .then(rs => { if (!live) return; const open = rs.filter(r => r.status === 'open'); setRuns(open); if (open.length === 1) setRunId(open[0].id) })
        .catch(() => { if (live) setRuns([]) })
    })
    api<{ id: string; is_fulfillment?: boolean }[]>('/locations')
      .then(ls => { if (live) setMainId(ls.find(l => l.is_fulfillment)?.id ?? null) })
      .catch(() => { if (live) setMainId(null) })
    // The suggestion: one read-only server call; on empty / failure the picker simply shows.
    getReceivingSuggestion()
      .then(async r => {
        const s = r?.suggestion
        if (!s) return null
        const cat = await getCatalog({ variantIds: [s.variantId] })
        const product = cat.products.find(p => p.variants.some(v => v.id === s.variantId))
        const variant = product?.variants.find(v => v.id === s.variantId)
        return product && variant
          ? { variantId: s.variantId, shopifyQuantity: s.shopifyQuantity, mainOnHand: s.mainOnHand ?? null, product, variantTitle: variant.title }
          : null
      })
      .then(s => { if (live) setSug(s) })
      .catch(() => { if (live) setSug(null) })
    return () => { live = false }
  }, [])

  const modes: ReceivingMode[] = flexible === false ? ['print_stick'] : ['count_only', 'print_stick', 'factory']
  const factory = mode === 'factory'
  const showPicker = !factory && (picking || sug === null)
  const qtyNum = Math.max(0, Math.floor(Number(qty) || 0))
  const lines: { variantId: string; quantity: number }[] = factory ? []
    : showPicker ? [...picked.entries()].map(([variantId, quantity]) => ({ variantId, quantity }))
    : sug ? [{ variantId: sug.variantId, quantity: qtyNum }] : []
  const units = lines.reduce((s, l) => s + l.quantity, 0)
  const mainBefore = !showPicker && sug ? sug.mainOnHand : null
  const canStart = !!mainId && !starting && (factory ? !!runId && runs !== null && runs.length > 0 : units > 0)

  async function start() {
    if (!mainId) return
    setStarting(true); setError(null)
    try {
      // The same calls the normal Receiving page makes: POST session, then one POST per line.
      const res = await api<{ sessionId: string }>('/receiving/sessions', {
        method: 'POST',
        body: JSON.stringify({
          locationId: mainId, reference: null, supplierName: null, note: null,
          ...(flexible ? { mode } : {}),
          ...(factory ? { labelRunId: runId } : {}),
        }),
      })
      for (const l of lines) {
        await api<{ lineId: string }>(`/receiving/sessions/${res.sessionId}/lines`, {
          method: 'POST', body: JSON.stringify({ variantId: l.variantId, quantity: l.quantity }),
        })
      }
      writeGuidedSession({ id: res.sessionId, mode, mainBefore })
      onStarted(res.sessionId)
    } catch {
      setError(t('guidedReceiving.startError'))
      setStarting(false)
    }
  }

  const pickerLines: Line[] = useMemo(() => [...picked.entries()].map(([variantId, quantity]) => ({
    id: variantId, variant_id: variantId, variant_title: '', sku: null, product_title: '', quantity, piece_count: 0,
  })), [picked])

  if (flexible === null || mainId === undefined) return <div className="flex justify-center py-16"><Spinner /></div>

  const n0 = MOVE_FROM_SHOPIFY_LOCATION ? 2 : 1
  return (
    <div className="flex flex-wrap items-start gap-7" data-testid="guided-setup">
      <section className="flex-[999_1_560px] min-w-0 flex flex-col gap-[18px]">
        <h1 className="text-[32px] max-[640px]:text-[26px] leading-tight font-bold text-primary tracking-tight">{t('guidedReceiving.setupTitle')}</h1>
        {error && <Alert tone="critical" title={error} />}
        {mainId === null && <Alert tone="warning" title={t('guidedReceiving.noMain')} />}

        <Section n={n0} title={t('guidedReceiving.markTitle')} testId="guided-mode">
          <ReceivingModeCards modes={modes} selected={mode} onSelect={setMode}
            factoryAvailable={runs === null ? null : runs.length > 0} className="lg:grid-cols-3 sm:grid-cols-1" />
        </Section>

        {factory ? (
          <Section n={n0 + 1} title={t('guidedReceiving.runTitle')} testId="guided-run">
            <RunPicker runs={runs} runId={runId} onChange={setRunId} />
          </Section>
        ) : (
          <Section n={n0 + 1} title={t('guidedReceiving.productTitle')} testId="guided-product">
            {sug === undefined ? (
              <div className="flex justify-center py-4"><Spinner /></div>
            ) : showPicker ? (
              <div className="space-y-3" data-testid="guided-picker">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <p className="text-small text-muted">{t('guidedReceiving.pickerHint')}</p>
                  {sug && (
                    <button type="button" onClick={() => setPicking(false)} className="text-small font-medium text-trace-blue hover:text-trace-blue-hover">
                      {t('guidedReceiving.useSuggestion')}
                    </button>
                  )}
                </div>
                <ProductSelectionGrid
                  lines={pickerLines}
                  onCommit={(variantId, quantity) => setPicked(prev => {
                    const next = new Map(prev)
                    if (quantity > 0) next.set(variantId, quantity); else next.delete(variantId)
                    return next
                  })}
                  onRefresh={() => {}}
                  onFinalizeClick={() => { void start() }}
                  finalizing={starting}
                  finalizeLabel={u => t('guidedReceiving.startWith', { count: u })}
                />
              </div>
            ) : sug && (
              <div className="flex flex-col gap-4" data-testid="guided-suggestion">
                <div className="flex flex-wrap gap-3.5 items-center justify-between">
                  <div className="flex gap-3.5 items-center min-w-0">
                    <ProductThumb src={sug.product.imageUrl} alt={sug.product.title} size={56} />
                    <div className="flex flex-col gap-0.5 min-w-0">
                      <span className="text-[15px] font-semibold text-primary truncate">
                        <bdi>{sug.product.title}</bdi> · <bdi>{sug.variantTitle}</bdi>
                      </span>
                      <span className="text-small text-muted" data-testid="guided-suggestion-why">
                        {t('guidedReceiving.suggestionWhy', { count: sug.shopifyQuantity })}
                      </span>
                    </div>
                  </div>
                  <button type="button" onClick={() => setPicking(true)} data-testid="guided-choose-another"
                    className="text-body font-medium text-trace-blue hover:text-trace-blue-hover px-1 py-2">
                    {t('guidedReceiving.chooseAnother')}
                  </button>
                </div>
                <label className="flex flex-wrap items-center gap-3 text-body font-semibold text-primary">
                  {t('guidedReceiving.qtyLabel')}
                  <span className="w-[90px]">
                    <Input type="number" min={1} value={qty} onChange={e => setQty(e.target.value)} data-testid="guided-qty" />
                  </span>
                  <span className="font-normal text-muted">{t('guidedReceiving.qtyHint')}</span>
                </label>
              </div>
            )}
          </Section>
        )}

        {!showPicker && (
          <div className="flex justify-end">
            <Button size="lg" onClick={() => { void start() }} disabled={!canStart} loading={starting}>
              {t('guidedReceiving.start')}
            </Button>
          </div>
        )}
      </section>

      <aside className="flex-[1_1_300px] max-w-[360px] max-[860px]:max-w-none flex flex-col gap-3.5">
        <div className="bg-surface border border-line rounded-2xl p-5 flex flex-col gap-3" data-testid="guided-shopify-panel">
          <span className="text-[15px] font-semibold text-primary">{t('guidedReceiving.shopifyTitle')}</span>
          <ShopifyChange before={mainBefore} added={factory ? null : units} />
          <p className="text-small font-medium text-warning-text leading-relaxed" data-testid="guided-warning">{t('guidedReceiving.warning')}</p>
          <p className="text-small text-muted leading-relaxed">{factory ? t('guidedReceiving.factoryShopify') : t('guidedReceiving.explain')}</p>
        </div>
        {mode !== 'count_only' && (
          <div className="bg-surface border border-line rounded-2xl p-5 flex flex-col gap-2 text-body text-primary/80">
            <span className="text-[15px] font-semibold text-primary">{t('guidedReceiving.needTitle')}</span>
            <span>{t('guidedReceiving.needPrinter')}</span>
            <span>{t('guidedReceiving.needScanner')}</span>
          </div>
        )}
      </aside>
    </div>
  )
}

/** "Traced Main 0 → 10": before → after when Shopify's count there is known, else "+N". Traced Main only. */
function ShopifyChange({ before, added }: { before: number | null; added: number | null }) {
  const { t } = useTranslation()
  return (
    <div className="flex justify-between gap-3 px-3 py-2.5 bg-success/[0.08] rounded-lg text-body" data-testid="guided-shopify-change">
      <span>{t('guidedReceiving.tracedMain')}</span>
      {added !== null && (
        <b dir="ltr" className="tabular-nums">{before !== null ? `${before} → ${before + added}` : `+${added}`}</b>
      )}
    </div>
  )
}

// ── Flow-6: the session + guide ───────────────────────────────────────────────

function SessionStep({ id, onLost, onFinished }: { id: string; onLost: () => void; onFinished: (s: SessionDetail) => void }) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [session, setSession] = useState<SessionDetail | null>(null)
  const [checked, setChecked] = useState({ done: 0, total: 0 })
  const [isPrinted, setPrinted] = useState(false)
  const [hidden, setHidden] = useState(() => guideHidden(id))
  // A label PDF that opened in the guided first session proves the printer: the Get started "Test label" step is done
  // (once per page; fire-and-forget — a failed write only leaves the step open).
  const printed = useRef(false)
  const labelPrinted = () => {
    setPrinted(true)
    if (printed.current) return
    printed.current = true
    patchOnboardingPrefs({ testLabelPrinted: true }).catch(() => { printed.current = false })
  }
  const stored = readGuidedSession()
  const mode: ReceivingMode = session?.mode ?? stored?.mode ?? 'print_stick'

  async function load() {
    try { setSession(await api<SessionDetail>(`/receiving/sessions/${id}`)) }
    catch { onLost() }
  }
  useEffect(() => { void load() /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [id])

  const finalized = session?.status === 'finalized'
  // Low and High end at close: Flow-7 follows the finalize. Medium goes on to print / stick / check.
  useEffect(() => {
    if (session && finalized && mode !== 'print_stick') onFinished(session)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [finalized, mode])

  if (!session) return <div className="flex justify-center py-16"><Spinner /></div>

  const toggle = (h: boolean) => { setHidden(h); setGuideHidden(id, h) }
  const checking = finalized && mode === 'print_stick'
  return (
    <div className="flex flex-wrap items-start gap-6" data-testid="guided-session">
      <div className="flex-[999_1_560px] min-w-0 space-y-4">
        {checking && <LabelCheck sessionId={id} onProgress={setChecked} />}
        <SessionView session={session} flexibleLabeling={!!session.mode} onRefresh={() => { void load() }} onPrinted={labelPrinted}
          onBack={() => { clearGuidedSession(); navigate('/receiving') }} />
      </div>
      {hidden ? (
        <button type="button" onClick={() => toggle(false)} data-testid="guided-show-guide"
          className="text-small font-medium text-trace-blue hover:text-trace-blue-hover">
          {t('guidedReceiving.showGuide')}
        </button>
      ) : (
        <GuidePanel mode={mode} session={session} checked={checked} printed={isPrinted}
          onPrinted={labelPrinted} onHide={() => toggle(true)} onDone={() => onFinished(session)} />
      )}
    </div>
  )
}

interface GuideStep { key: string; label: string; done: boolean; hint?: string; action?: ReactNode }

/** The guide beside the session: steps in the order that works, tips. No inputs, never moves focus. */
function GuidePanel({ mode, session, checked, printed, onPrinted, onHide, onDone }: {
  mode: ReceivingMode; session: SessionDetail; checked: { done: number; total: number }; printed: boolean
  onPrinted: () => void; onHide: () => void; onDone: () => void
}) {
  const { t } = useTranslation()
  const [printError, setPrintError] = useState(false)
  const finalized = session.status === 'finalized'
  const units = session.lines.reduce((s, l) => s + l.quantity, 0)
  const hasLines = session.lines.length > 0 || finalized

  async function print() {
    setPrintError(false)
    try {
      // The same call as the session's own "Print labels".
      const res = await authFetch(`/receiving/sessions/${session.id}/labels`, { headers: authHeaders() })
      if (!res.ok) throw new Error(String(res.status))
      window.open(URL.createObjectURL(await res.blob()), '_blank')
      onPrinted()
    } catch { setPrintError(true) }
  }

  const steps: GuideStep[] = mode === 'count_only' ? [
    { key: 'addProduct', label: t('guidedReceiving.step.addProduct'), done: hasLines },
    { key: 'enterCount', label: t('guidedReceiving.step.enterCount'), done: units > 0 || finalized },
    { key: 'close', label: t('guidedReceiving.step.close'), done: finalized, hint: t('guidedReceiving.step.closeHintLow') },
  ] : mode === 'factory' ? [
    { key: 'scanFactory', label: t('guidedReceiving.step.scanFactory'), done: (session.factory?.staged ?? 0) > 0 || finalized,
      hint: t('guidedReceiving.step.scanFactoryHint') },
    { key: 'finishFactory', label: t('guidedReceiving.step.finishFactory'), done: finalized, hint: t('guidedReceiving.step.finishFactoryHint') },
  ] : [
    { key: 'addProduct', label: t('guidedReceiving.step.addProduct'), done: hasLines },
    { key: 'close', label: t('guidedReceiving.step.close'), done: finalized, hint: t('guidedReceiving.step.closeHint') },
    { key: 'print', label: t('guidedReceiving.step.print'), done: printed || checked.done > 0,
      action: finalized ? (
        <Button size="sm" variant={printed ? 'secondary' : 'primary'} onClick={() => { void print() }}>
          {t('guidedReceiving.step.printButton', { count: session.piece_count })}
        </Button>
      ) : undefined },
    { key: 'stick', label: t('guidedReceiving.step.stick'), done: checked.done > 0 },
    { key: 'check', label: t('guidedReceiving.step.check'), done: finalized && checked.total > 0 && checked.done >= checked.total,
      hint: t('guidedReceiving.step.checkHint') },
  ]
  const current = steps.findIndex(s => !s.done)

  return (
    <aside className="flex-[1_1_300px] max-w-[340px] max-[860px]:max-w-none bg-surface border border-line rounded-2xl p-5 flex flex-col gap-4"
      data-testid="guide-panel" data-mode={mode}>
      <div className="flex items-center justify-between gap-2">
        <span className="text-[15px] font-semibold text-primary">{t('guidedReceiving.guideTitle')}</span>
        <button type="button" onClick={onHide} className="text-small text-muted hover:text-primary" data-testid="guided-hide-guide">
          {t('guidedReceiving.hideGuide')}
        </button>
      </div>
      <ol className="flex flex-col gap-3">
        {steps.map((s, i) => (
          <li key={s.key} data-testid={`guide-step-${s.key}`} data-done={s.done} data-current={i === current}
            className="flex gap-3">
            <span className={cn('w-6 h-6 flex-shrink-0 rounded-full flex items-center justify-center text-caption font-bold',
              s.done ? 'bg-success text-white' : i === current ? 'bg-trace-blue text-white' : 'bg-line text-muted')} aria-hidden>
              {s.done ? <Check size={13} strokeWidth={3} /> : i + 1}
            </span>
            <span className="flex flex-col gap-1.5 min-w-0">
              <span className={cn('text-body', i === current ? 'font-semibold text-primary' : s.done ? 'text-muted' : 'text-primary/80')}>
                {s.label}{s.done && <span className="sr-only"> ✓</span>}
              </span>
              {i === current && s.hint && <span className="text-small text-muted">{s.hint}</span>}
              {s.action}
            </span>
          </li>
        ))}
      </ol>
      {printError && <p role="alert" className="text-small text-critical-text">{t('guidedReceiving.printFailed')}</p>}
      {mode === 'print_stick' && finalized && (
        <Button variant={current === -1 ? 'primary' : 'secondary'} onClick={onDone} className="w-full">
          {t('guidedReceiving.done')}
        </Button>
      )}
      <div className="border-t border-line pt-3.5 flex flex-col gap-2.5 text-small text-muted">
        {mode !== 'count_only' && <p>{t('guidedReceiving.tipKeyboard')}</p>}
        {mode !== 'count_only' && (
          <div className="flex flex-wrap items-center gap-2" data-testid="guided-phone-tip">
            <span>{t('guidedReceiving.tipPhone')}</span>
            <PhoneScanButton />
          </div>
        )}
        <a href={HELP_WHATSAPP_URL} target="_blank" rel="noopener noreferrer" data-testid="guided-chat"
          className="inline-flex items-center gap-1.5 font-medium text-trace-blue hover:text-trace-blue-hover">
          <MessageCircle size={14} strokeWidth={2} /> {t('guidedReceiving.chat')}
        </a>
      </div>
    </aside>
  )
}

interface SessionPiece { id: string; barcode: string | null; short_code: string | null }

const norm = (code: string) => code.replace(/\s+/g, '').toUpperCase()

/**
 * The label check: READ ONLY. Each scan is compared with this session's own pieces (GET …/pieces, loaded once);
 * nothing is sent anywhere. The only auto-focusing input on the view (useScanner + ScanShell, unchanged; the
 * paired phone through the existing usePhoneScanTarget).
 */
function LabelCheck({ sessionId, onProgress }: { sessionId: string; onProgress: (p: { done: number; total: number }) => void }) {
  const { t } = useTranslation()
  const [pieces, setPieces] = useState<SessionPiece[] | null>(null)
  const [loadFailed, setLoadFailed] = useState(false)
  const [checked, setChecked] = useState<Set<string>>(() => new Set())
  const [last, setLast] = useState<string | null>(null)
  const piecesRef = useRef<SessionPiece[] | null>(null)
  piecesRef.current = pieces
  const checkedRef = useRef(checked)
  checkedRef.current = checked

  useEffect(() => {
    let live = true
    api<SessionPiece[]>(`/receiving/sessions/${sessionId}/pieces`)
      .then(p => { if (live) setPieces(p) })
      .catch(() => { if (live) setLoadFailed(true) })
    return () => { live = false }
  }, [sessionId])

  useEffect(() => { onProgress({ done: checked.size, total: pieces?.length ?? 0 }) }, [checked, pieces, onProgress])

  const phone = usePhoneScanTarget({ label: t('guidedReceiving.check.title'), describe: (_c, o) => o.label })
  const scanner = phone.attach(useScanner({
    onScan: phone.wrap(async (code: string) => {
      const c = norm(code)
      const hit = (piecesRef.current ?? []).find(p => (p.short_code && norm(p.short_code) === c) || (p.barcode && norm(p.barcode) === c))
      if (!hit) return { success: false, label: t('guidedReceiving.check.notHere') }
      setLast(hit.short_code ?? hit.barcode ?? c)
      if (checkedRef.current.has(hit.id)) return { success: true, label: t('guidedReceiving.check.again') }
      setChecked(prev => new Set(prev).add(hit.id))
      return { success: true, label: t('guidedReceiving.check.ok') }
    }),
  }))

  const total = pieces?.length ?? 0
  return (
    <section className="card p-5 space-y-3" data-testid="label-check">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <div>
          <h2 className="text-h3 text-primary">{t('guidedReceiving.check.title')}</h2>
          <p className="text-small text-muted">{t('guidedReceiving.check.body')}</p>
        </div>
        <span className="text-h2 font-bold text-primary tabular-nums" dir="ltr" data-testid="label-check-counter" aria-live="polite">
          {t('guidedReceiving.check.counter', { done: checked.size, total })}
        </span>
      </div>
      {loadFailed && <Alert tone="warning" title={t('guidedReceiving.check.loadFailed')} />}
      <ScanShell scanner={scanner} placeholder={t('guidedReceiving.check.placeholder')} />
      <div className="flex flex-wrap items-center justify-between gap-2 text-small">
        {last ? (
          <span className="text-muted" data-testid="label-check-last">
            {t('guidedReceiving.check.last', { code: '' })}<span className="font-mono text-primary" dir="ltr">{last}</span>
            {scanner.recentScans[0] && <> · {scanner.recentScans[0].label}</>}
          </span>
        ) : scanner.recentScans[0] && !scanner.recentScans[0].success ? (
          <span className="text-critical-text" data-testid="label-check-last">{scanner.recentScans[0].label}</span>
        ) : <span />}
        {total > 0 && checked.size >= total && (
          <span className="font-semibold text-success-text" data-testid="label-check-all">{t('guidedReceiving.check.allChecked')}</span>
        )}
      </div>
    </section>
  )
}

// ── Flow-7: done ──────────────────────────────────────────────────────────────

function DoneStep({ session }: { session: SessionDetail }) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const stored = useMemo(() => readGuidedSession(), [])
  const mode: ReceivingMode = session.mode ?? stored?.mode ?? 'print_stick'
  const low = mode === 'count_only'
  const count = session.piece_count
  const [pace, setPace] = useState<number | null>(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState(false)
  // Flow-7 shows once: leaving it ends the guided session of this tab.
  const leave = () => { clearGuidedSession(); navigate('/overview') }

  async function save() {
    if (pace === null) return
    setSaving(true); setError(false)
    try {
      await patchOnboardingPrefs({ labelingPace: pace })
      leave()
    } catch { setError(true); setSaving(false) }
  }

  return (
    <div className="max-w-[640px] mx-auto flex flex-col gap-5" data-testid="guided-done">
      <div className="flex flex-col items-center text-center gap-3">
        <span className="w-14 h-14 rounded-full bg-success/15 text-success-text flex items-center justify-center" aria-hidden>
          <Check size={28} strokeWidth={3} />
        </span>
        <h1 className="text-[28px] max-[640px]:text-[24px] leading-tight font-bold text-primary">{t('guidedReceiving.finished.title', { count })}</h1>
        <p className="text-body text-muted max-w-[480px]">{t(low ? 'guidedReceiving.finished.subLow' : 'guidedReceiving.finished.sub')}</p>
      </div>

      <div className="bg-surface border border-line rounded-2xl p-5 flex flex-col gap-3" data-testid="guided-done-shopify">
        <span className="text-[15px] font-semibold text-primary">{t('guidedReceiving.finished.changedTitle')}</span>
        <ShopifyChange before={stored?.id === session.id ? stored.mainBefore : null} added={count} />
      </div>

      {!low && (
        <div className="bg-surface border border-line rounded-2xl p-5 flex flex-wrap items-center justify-between gap-3" data-testid="guided-done-lookup">
          <div className="flex flex-col gap-1 min-w-0">
            <span className="text-[15px] font-semibold text-primary">{t('guidedReceiving.finished.lookupTitle')}</span>
            <span className="text-small text-muted">{t('guidedReceiving.finished.lookupBody')}</span>
          </div>
          <Link to="/lookup" onClick={() => clearGuidedSession()}
            className="h-10 inline-flex items-center px-4 rounded-lg border border-line text-body font-semibold text-primary hover:bg-black/[0.03]">
            {t('guidedReceiving.finished.openLookup')}
          </Link>
        </div>
      )}

      <div className="bg-surface border border-line rounded-2xl p-5 flex flex-col gap-3" data-testid="guided-pace">
        <div className="flex flex-col gap-1">
          <span className="text-[15px] font-semibold text-primary">{t('guidedReceiving.finished.paceTitle')}</span>
          <span className="text-small text-muted">{t('guidedReceiving.finished.paceBody')}</span>
        </div>
        <div className="grid grid-cols-3 max-[480px]:grid-cols-1 gap-2.5">
          {PACES.map(p => (
            <button key={p.key} type="button" aria-pressed={pace === p.perDay} onClick={() => setPace(p.perDay)}
              data-testid={`guided-pace-${p.key}`}
              className={cn('rounded-xl border p-3.5 flex flex-col gap-1 text-start transition-colors',
                pace === p.perDay ? 'border-trace-blue bg-trace-blue/[0.06]' : 'border-line hover:border-trace-blue')}>
              <span className="text-body font-semibold text-primary">{t(`guidedReceiving.finished.${p.key}`)}</span>
              <span className="text-small text-muted">{t('guidedReceiving.finished.perDay', { count: p.perDay })}</span>
            </button>
          ))}
        </div>
        {error && <p role="alert" className="text-small text-critical-text">{t('guidedReceiving.finished.saveFailed')}</p>}
      </div>

      <div className="flex flex-wrap justify-end gap-3">
        <Button variant="secondary" onClick={leave}>{t('guidedReceiving.finished.notNow')}</Button>
        <Button onClick={() => { void save() }} disabled={pace === null} loading={saving}>{t('guidedReceiving.finished.setGoal')}</Button>
      </div>
    </div>
  )
}
