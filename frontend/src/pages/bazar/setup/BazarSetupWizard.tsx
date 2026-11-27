import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { Link, Navigate, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { HelpLink } from '../../../help/HelpLink'
import { ArrowLeft, ArrowRight, Check, Loader2, Minus, Plus, Printer, Search } from 'lucide-react'
import {
  BazarError, createBazarEvent, getBazarEvent, getBazarSetup, getBazarSetupProducts, markBazarSent, METHOD_ORDER,
  openBazarSetupLabels, receiveBazarSetup, sendBazarSetupBatch, setBazarOnlineStock, startBazarEvent,
  type BazarEventDetail, type BazarSetupProduct, type BazarSetupStatus, type OnlineStock, type PaymentMethod,
  type SellerRole,
} from '../../../bazar/api'
import { resetCapabilitiesCache } from '../../../capabilities'
import { Button, Modal, cn } from '../../../components/ui'
import { OnlineStockCards, OnlineStockPhonePair, OnlineStockTag } from '../OnlineStock'
import { PricesCard, priceTotals, usePriceData } from '../r3/PricesCard'
import RangeCalendar from '../r1/RangeCalendar'
import { cairoToday, dayCount } from '../r1/overviewState'
import { Eyebrow, amount, rangeLabel, useIsPhone } from '../r1/parts'
import { btnPrimary, btnSecondary } from '../r3/parts'
import { LowerTheseList } from './LowerThese'

/**
 * Bazar B9b — the guided bazar setup, "Set up from new stock" (wizard entry 2026-11-26: any owner of a bazar store with
 * Shopify connected, from the Bazar page) (design/bazar-v2 V2_New1–3 and the
 * phone versions). /bazar/setup, owner only, and only while GET /bazar-setup says it's on (else → /bazar):
 *   1 Your bazar   — the same create call as /bazar/new (+ B8's online stock pair, Remove by default)
 *   2 Pick products — the Traced catalog, a stepper per size, "N in your other locations" (Shopify total − Traced Main)
 *   3 Barcodes      — labels or no barcodes (you can mix, per product) → received into Traced Main (one request)
 *   → "Lower these at the location you took them from" (list, CSV, Done)
 *   4 Prices        — B9c: the batch's pieces go on the bazar's send (POST /bazar-setup/send-batch — exactly the
 *                     batch's pieces, the same send as a scan), then Get ready's PricesCard (bulk % / round / custom)
 *   5 Ready         — B9c: the online stock choice (Remove / Keep, changeable until sent), Mark as sent (Get ready's
 *                     confirm — the existing send), then Start → the bazar is live.
 * "Continue to your bazar" (the header link) leaves the wizard at any step; everything is on Get ready too. The bazar
 * being set up and the receive's request id are remembered in this browser so a reload resumes at Pick; once received,
 * the server's status decides (not lowered → the list; lowered, draft: send preparing → Prices, sent → Ready).
 */

type Step = 'bazar' | 'pick' | 'barcodes' | 'lower' | 'prices' | 'ready' | 'done'
const STORE_KEY = 'traced.bazarSetup.v1'
interface Saved { eventId?: string; requestId?: string }

function readSaved(): Saved {
  try { const v = JSON.parse(localStorage.getItem(STORE_KEY) ?? '{}'); return v && typeof v === 'object' ? v : {} } catch { return {} }
}
function writeSaved(s: Saved) {
  try { localStorage.setItem(STORE_KEY, JSON.stringify(s)) } catch { /* private window: no resume, still works */ }
}
function clearSaved() {
  try { localStorage.removeItem(STORE_KEY) } catch { /* ignore */ }
}
function newRequestId() {
  const c = globalThis.crypto as Crypto | undefined
  return c?.randomUUID ? c.randomUUID() : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`
}

export default function BazarSetupWizard() {
  const { t } = useTranslation()
  const [status, setStatus] = useState<BazarSetupStatus | null | undefined>(undefined)
  const [step, setStep] = useState<Step | null>(null)
  const [eventId, setEventId] = useState<string | null>(null)
  const [eventName, setEventName] = useState<string>('')

  useEffect(() => {
    let cancelled = false
    ;(async () => {
      const s = await getBazarSetup().catch(() => null)
      if (cancelled) return
      setStatus(s)
      if (!s || !s.canSetUp) return
      if (s.batch && !s.batch.lowered) {
        setEventId(s.batch.eventId); setEventName(s.batch.eventName ?? ''); setStep('lower'); return
      }
      // B9c: a lowered batch whose bazar is still being set up resumes at Prices (send preparing) or Ready (sent).
      if (s.batch && s.batch.lowered && s.batch.eventStatus === 'draft'
          && (s.batch.transferStatus === 'preparing' || s.batch.transferStatus === 'sent')) {
        setEventId(s.batch.eventId); setEventName(s.batch.eventName ?? '')
        setStep(s.batch.transferStatus === 'sent' ? 'ready' : 'prices'); return
      }
      const saved = readSaved()
      if (saved.eventId) {
        const ev = await getBazarEvent(saved.eventId).catch(() => null)
        if (cancelled) return
        if (ev && ev.status === 'draft') { setEventId(ev.id); setEventName(ev.name); setStep('pick'); return }
        clearSaved()
      }
      setStep('bazar')
    })()
    return () => { cancelled = true }
  }, [])

  if (status === undefined || (status && status.canSetUp && step === null)) {
    return <div className="min-h-screen flex items-center justify-center"><Loader2 className="animate-spin text-muted" aria-label={t('bazar.wizard.loading')} /></div>
  }
  if (!status || !status.canSetUp) return <Navigate to="/bazar" replace />
  // Wizard entry — Traced Main is linked but Shopify isn't counting there yet and the store already has pieces: a
  // receive here would start the count through receiving (the seed could never run after it). Nothing to resume
  // without a batch, so the wizard stops here.
  if (status.blocked === 'main_not_counting' && !status.batch) return <MainNotCounting />

  return (
    <Frame step={step!} laterTo={eventId && step !== 'done' ? `/bazar/${encodeURIComponent(eventId)}` : null}>
      {step === 'bazar' && (
        <YourBazar created={eventId ? eventName : null}
          onNext={(id, name) => {
            if (id) { setEventId(id); setEventName(name); writeSaved({ ...readSaved(), eventId: id }) }
            setStep('pick')
          }} />
      )}
      {(step === 'pick' || step === 'barcodes') && eventId && (
        <PickAndReceive eventId={eventId} step={step} setStep={setStep}
          onReceived={s => { setStatus(s); writeSaved({ eventId: eventId ?? undefined }); setStep('lower') }} />
      )}
      {step === 'lower' && (
        <LowerStep status={status} onDone={s => { setStatus(s); clearSaved(); setStep('prices') }} />
      )}
      {step === 'prices' && eventId && (
        <PricesStep eventId={eventId} leftAtMain={status.batch?.leftAtMain ?? 0} onNext={() => setStep('ready')} />
      )}
      {step === 'ready' && eventId && <ReadyStep eventId={eventId} onBack={() => setStep('prices')} onStarted={() => setStep('done')} />}
      {step === 'done' && <DoneStep eventId={eventId} />}
    </Frame>
  )
}

function MainNotCounting() {
  const { t } = useTranslation()
  return (
    <div className="min-h-screen bg-charcoal flex items-center justify-center p-4" data-testid="bazar-setup-main-not-counting">
      <div className="card p-6 max-w-[520px] flex flex-col gap-3">
        <h1 className="text-h3 font-semibold text-primary">{t('bazar.newStock.blockTitle')}</h1>
        <p className="text-body text-muted">{t('bazar.newStock.blockBody')}</p>
        <div className="flex flex-wrap gap-2.5 pt-1">
          <Link to="/settings?tab=connections" className={btnPrimary}>{t('bazar.newStock.blockSettings')}</Link>
          <Link to="/bazar" className={btnSecondary}>{t('bazar.newStock.blockBack')}</Link>
        </div>
      </div>
    </div>
  )
}

// ── Frame: the top bar with the five steps ───────────────────────────────────

const STEP_INDEX: Record<Step, number> = { bazar: 1, pick: 2, barcodes: 3, lower: 3, prices: 4, ready: 5, done: 6 }

function Frame({ step, laterTo, children }: { step: Step; laterTo: string | null; children: ReactNode }) {
  const { t } = useTranslation()
  const phone = useIsPhone()
  const current = STEP_INDEX[step]
  const names = [1, 2, 3, 4, 5].map(n => t(`bazar.wizard.steps.s${n}`))
  return (
    <div className="min-h-screen bg-charcoal" data-testid="bazar-setup-wizard">
      <header className="bg-surface border-b border-line">
        {phone ? (
          <div className="px-4 pt-3 pb-2 flex flex-col gap-2">
            <div className="flex items-center justify-between">
              <span className="text-[20px] font-bold text-primary">traced<span className="text-trace-blue">.</span></span>
              <span className="text-small text-muted" data-testid="bazar-setup-step">
                {current <= 5 ? <>{t('bazar.wizard.stepOf', { n: current, total: 5 })} · {names[current - 1]}</> : names[4]}
              </span>
            </div>
            <span className="flex items-center justify-between gap-3">
              {laterTo ? <LaterLink to={laterTo} /> : <span />}
              <HelpLink screen="bazarSetup" />
            </span>
            <div aria-hidden className="grid grid-cols-5 gap-1.5">
              {[1, 2, 3, 4, 5].map(n => (
                <span key={n} className={cn('h-1 rounded-sm', n < current ? 'bg-success' : n === current ? 'bg-trace-blue' : 'bg-line')} />
              ))}
            </div>
          </div>
        ) : (
          <div className="max-w-[1120px] mx-auto px-6 h-16 flex items-center gap-6">
            <span className="flex items-center gap-2">
              <span className="text-[20px] font-bold text-primary">traced<span className="text-trace-blue">.</span></span>
              <span className="px-2 py-0.5 rounded bg-charcoal text-small font-semibold text-muted">{t('bazar.title')}</span>
            </span>
            <ol className="flex items-center gap-2 mx-auto" data-testid="bazar-setup-step">
              {names.map((name, i) => {
                const n = i + 1
                return (
                  <li key={n} aria-current={n === current ? 'step' : undefined} className="flex items-center gap-2">
                    {n > 1 && <span aria-hidden className="w-6 h-px bg-line" />}
                    <span aria-hidden dir="ltr" className={cn('w-6 h-6 rounded flex items-center justify-center text-small font-semibold',
                      n < current ? 'bg-success text-white' : n === current ? 'bg-trace-blue text-white' : 'border border-line text-muted')}>
                      {n < current ? <Check size={14} strokeWidth={3} /> : n}
                    </span>
                    <span className={cn('text-body', n === current ? 'font-semibold text-primary' : n < current ? 'text-primary' : 'text-muted')}>{name}</span>
                  </li>
                )
              })}
            </ol>
            <HelpLink screen="bazarSetup" />
            {laterTo && <LaterLink to={laterTo} />}
          </div>
        )}
      </header>
      <main className="max-w-[1120px] mx-auto px-4 sm:px-6 py-6 sm:py-8">{children}</main>
    </div>
  )
}

/** "Continue to your bazar" — leaves the wizard at any step (everything is on Get ready too). */
function LaterLink({ to }: { to: string }) {
  const { t } = useTranslation()
  return (
    <Link to={to} className="text-body font-semibold text-trace-blue hover:underline shrink-0 self-start sm:self-auto"
          data-testid="bazar-setup-later">{t('bazar.wizard.later')}</Link>
  )
}

function Footer({ left, children }: { left: ReactNode; children: ReactNode }) {
  const phone = useIsPhone()
  if (phone) {
    return (
      <div className="fixed inset-x-0 bottom-0 z-20 bg-surface border-t border-line p-4 flex gap-2.5">{children}</div>
    )
  }
  return (
    <div className="card mt-5 px-4 py-3.5 flex items-center gap-3 flex-wrap">
      <span className="text-body text-muted me-auto">{left}</span>
      {children}
    </div>
  )
}

// ── 1 Your bazar ─────────────────────────────────────────────────────────────

function YourBazar({ created, onNext }: { created: string | null; onNext: (id: string, name: string) => void }) {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const phone = useIsPhone()
  const today = cairoToday()
  const [name, setName] = useState('')
  const [where, setWhere] = useState('')
  const [startsOn, setStartsOn] = useState(today)
  const [endsOn, setEndsOn] = useState(today)
  const [methods, setMethods] = useState<PaymentMethod[]>(['cash', 'instapay', 'card'])
  const [team, setTeam] = useState(true)
  const [online, setOnline] = useState<OnlineStock>('remove')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const n = dayCount(startsOn, endsOn)

  async function next() {
    setError(null)
    if (!name.trim()) { setError(t('bazar.form.nameRequired')); return }
    if (where.trim().length < 3) { setError(t('bazar.form.locationRequired')); return }
    if (methods.length === 0) { setError(t('bazar.form.methodRequired')); return }
    setSaving(true)
    try {
      const roles: SellerRole[] = team ? ['owner', 'manager', 'worker'] : ['owner']
      const ev = await createBazarEvent({
        name: name.trim(), startsOn, endsOn, locationId: null, locationName: where.trim(),
        paymentMethods: METHOD_ORDER.filter(m => methods.includes(m)), sellerRoles: roles,
        ...(online === 'leave' ? { shopifySyncMode: 'leave' as const } : {}),
      })
      onNext(ev.id, ev.name)
    } catch (e) {
      setError(e instanceof BazarError ? e.message_(lang) : t('bazar.saveFailed'))
    } finally {
      setSaving(false)
    }
  }

  const nextLabel = <>{t('bazar.wizard.bazar.next')}<ArrowRight size={16} aria-hidden className="rtl:-scale-x-100" /></>

  if (created !== null) {
    // Back from step 2: the bazar exists already — it's changed on Get ready, never created twice.
    return (
      <div className="flex flex-col gap-4" data-testid="bazar-setup-bazar-created">
        <div className="card p-5 flex flex-col gap-1">
          <Eyebrow>{t('bazar.wizard.bazar.title')}</Eyebrow>
          <span className="text-h4 font-bold text-primary">{created}</span>
          <span className="text-body text-muted">{t('bazar.wizard.bazar.changeLater')}</span>
        </div>
        <Footer left={t('bazar.wizard.stepOf', { n: 1, total: 5 })}>
          <button type="button" className={cn(btnPrimary, phone && 'w-full h-[52px]')} onClick={() => onNext('', created)}
                  data-testid="bazar-setup-next">{nextLabel}</button>
        </Footer>
      </div>
    )
  }

  const chip = (on: boolean) => cn('h-11 rounded-[10px] border text-body font-semibold',
    on ? 'border-trace-blue border-[1.5px] bg-trace-blue/[0.04] text-trace-blue' : 'border-line bg-surface text-primary')

  return (
    <div className={cn('flex flex-col', phone && 'pb-24')} data-testid="bazar-setup-bazar">
      <div className="grid gap-5 lg:grid-cols-[minmax(0,0.75fr)_minmax(0,1fr)] items-stretch">
        {!phone && (
          <div className="rounded-2xl bg-grey-900 text-[#F2F4F7] p-7 flex flex-col gap-5">
            <Eyebrow dark>{t('bazar.wizard.bazar.welcome')}</Eyebrow>
            <h1 className="text-[34px] leading-[1.15] font-bold tracking-tight">{t('bazar.wizard.bazar.hero')}</h1>
            <ol className="flex flex-col gap-4">
              {[1, 2, 3, 4, 5].map(i => (
                <li key={i} className="flex gap-3">
                  <span aria-hidden dir="ltr" className={cn('w-6 h-6 rounded flex items-center justify-center text-small font-semibold shrink-0',
                    i === 1 ? 'bg-trace-blue text-white' : 'bg-white/[0.08] text-[#F2F4F7]')}>{i}</span>
                  <span className="flex flex-col">
                    <span className="font-semibold">{t(`bazar.wizard.bazar.list.s${i}`)}</span>
                    <span className="text-small text-sidebar-text">{t(`bazar.wizard.bazar.list.s${i}d`)}</span>
                  </span>
                </li>
              ))}
            </ol>
            <p className="text-small text-sidebar-text border-t border-white/10 pt-4 mt-auto">{t('bazar.wizard.bazar.nothingChanges')}</p>
          </div>
        )}
        <div className="card p-5 sm:p-6 flex flex-col gap-4 min-w-0">
          {phone && <p className="text-small text-muted" data-testid="bazar-setup-new-stock-note">{t('bazar.wizard.bazar.nothingChanges')}</p>}
          <div className="flex flex-col">
            <h2 className="text-h4 font-bold text-primary">{t('bazar.wizard.bazar.title')}</h2>
            <span className="text-body text-muted">{t('bazar.wizard.bazar.changeLater')}</span>
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            <label className="flex flex-col gap-1.5">
              <span className="text-body font-semibold text-primary">{t('bazar.wizard.bazar.name')}</span>
              <input className="input" value={name} maxLength={80} onChange={e => setName(e.target.value)}
                     placeholder={t('bazar.form.namePlaceholder')} data-testid="bazar-setup-name" />
            </label>
            <label className="flex flex-col gap-1.5">
              <span className="text-body font-semibold text-primary">{t('bazar.wizard.bazar.where')}</span>
              <input className="input" value={where} maxLength={80} onChange={e => setWhere(e.target.value)}
                     placeholder={t('bazar.form.locationPlaceholder')} data-testid="bazar-setup-where" />
            </label>
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <RangeCalendar start={startsOn} end={endsOn} today={today} onChange={(s, e) => { setStartsOn(s); setEndsOn(e) }} />
            <div className="flex flex-col gap-3 min-w-0">
              <div className="bg-charcoal border border-line rounded-xl p-3.5 flex flex-col gap-0.5">
                <Eyebrow>{t('bazar.r1.form.sellingDaysTitle')}</Eyebrow>
                <span className="text-[26px] leading-8 font-bold text-primary">{t('bazar.r1.form.sellingDays', { count: n })}</span>
                <span className="text-body text-muted tabular-nums text-start">{rangeLabel(startsOn, endsOn, lang)}</span>
              </div>
              <fieldset className="flex flex-col gap-2">
                <legend className="mb-2"><Eyebrow>{t('bazar.r1.form.payWith')}</Eyebrow></legend>
                <div className="grid grid-cols-3 gap-2">
                  {METHOD_ORDER.map(m => (
                    <button key={m} type="button" aria-pressed={methods.includes(m)} className={chip(methods.includes(m))}
                      onClick={() => setMethods(methods.includes(m) ? methods.filter(x => x !== m) : [...methods, m])}>
                      {t(`bazar.method.${m}`)}
                    </button>
                  ))}
                </div>
              </fieldset>
              <fieldset className="flex flex-col gap-2">
                <legend className="mb-2"><Eyebrow>{t('bazar.wizard.bazar.who')}</Eyebrow></legend>
                <div className="grid grid-cols-2 gap-2" role="radiogroup" aria-label={t('bazar.wizard.bazar.who')}>
                  <button type="button" role="radio" aria-checked={!team} className={chip(!team)} onClick={() => setTeam(false)}
                          data-testid="bazar-setup-just-me">{t('bazar.wizard.bazar.justMe')}</button>
                  <button type="button" role="radio" aria-checked={team} className={chip(team)} onClick={() => setTeam(true)}
                          data-testid="bazar-setup-team">{t('bazar.wizard.bazar.team')}</button>
                </div>
              </fieldset>
              <fieldset className="flex flex-col gap-2">
                <legend className="mb-2"><Eyebrow>{t('bazar.online.title')}</Eyebrow></legend>
                <OnlineStockPhonePair value={online} onChange={setOnline} />
                <span className="text-small text-muted">{t(`bazar.online.${online}.desc`)}</span>
              </fieldset>
            </div>
          </div>
          {error && <p role="alert" className="text-body text-critical">{error}</p>}
        </div>
      </div>
      <Footer left={t('bazar.wizard.stepOf', { n: 1, total: 5 })}>
        <button type="button" className={cn(btnPrimary, phone && 'w-full h-[52px]')} onClick={next} disabled={saving}
                data-testid="bazar-setup-next">
          {saving && <Loader2 size={16} className="animate-spin" aria-hidden />}{nextLabel}
        </button>
      </Footer>
    </div>
  )
}

// ── 2 Pick products + 3 Barcodes → receive ───────────────────────────────────

function PickAndReceive({ eventId, step, setStep, onReceived }: {
  eventId: string; step: 'pick' | 'barcodes'; setStep: (s: Step) => void; onReceived: (s: BazarSetupStatus) => void
}) {
  const { t, i18n } = useTranslation()
  const [products, setProducts] = useState<BazarSetupProduct[] | null>(null)
  const [stockKnown, setStockKnown] = useState(false)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [qty, setQty] = useState<Record<string, number>>({})
  const [labelsOn, setLabelsOn] = useState(true)
  const [noneOn, setNoneOn] = useState(false)
  const [perProduct, setPerProduct] = useState<Record<string, boolean>>({})   // product → labels (when mixing)

  useEffect(() => {
    getBazarSetupProducts()
      .then(r => { setProducts(r.products); setStockKnown(r.stockKnown) })
      .catch(e => { setProducts([]); setLoadError(e instanceof BazarError ? e.message_(i18n.language) : t('bazar.loadFailed')) })
  }, [i18n.language, t])

  const picked = useMemo(() => (products ?? [])
    .map(p => ({ p, n: p.variants.reduce((s, v) => s + (qty[v.id] ?? 0), 0) }))
    .filter(x => x.n > 0), [products, qty])
  const pieces = picked.reduce((s, x) => s + x.n, 0)

  const labelsFor = (productId: string) => labelsOn && noneOn ? (perProduct[productId] ?? true) : labelsOn

  if (products === null) return <Loader2 className="animate-spin text-muted" aria-label={t('bazar.wizard.loading')} />
  if (step === 'pick') {
    return (
      <PickStep products={products} stockKnown={stockKnown} loadError={loadError} qty={qty} setQty={setQty}
        picked={picked} pieces={pieces} onBack={() => setStep('bazar')} onNext={() => setStep('barcodes')} />
    )
  }
  return (
    <BarcodesStep eventId={eventId} picked={picked} pieces={pieces} qty={qty}
      labelsOn={labelsOn} setLabelsOn={setLabelsOn} noneOn={noneOn} setNoneOn={setNoneOn}
      labelsFor={labelsFor} setPerProduct={(id, on) => setPerProduct({ ...perProduct, [id]: on })}
      onBack={() => setStep('pick')} onReceived={onReceived} />
  )
}

function Stepper({ value, onChange, label }: { value: number; onChange: (n: number) => void; label: string }) {
  const btn = 'w-8 h-full flex items-center justify-center text-muted hover:text-primary disabled:opacity-40'
  return (
    <span className={cn('inline-flex items-center h-9 rounded-lg border', value > 0 ? 'border-trace-blue' : 'border-line')}>
      <button type="button" className={btn} aria-label={`${label} −`} disabled={value <= 0} onClick={() => onChange(value - 1)}>
        <Minus size={14} aria-hidden />
      </button>
      <input dir="ltr" inputMode="numeric" aria-label={label} value={value}
        className={cn('w-10 text-center bg-transparent text-body font-semibold tabular-nums outline-none', value > 0 ? 'text-trace-blue' : 'text-muted')}
        onChange={e => { const n = parseInt(e.target.value.replace(/\D/g, '') || '0', 10); onChange(Math.min(9999, n)) }} />
      <button type="button" className={btn} aria-label={`${label} +`} disabled={value >= 9999} onClick={() => onChange(value + 1)}>
        <Plus size={14} aria-hidden />
      </button>
    </span>
  )
}

function PickStep({ products, stockKnown, loadError, qty, setQty, picked, pieces, onBack, onNext }: {
  products: BazarSetupProduct[]; stockKnown: boolean; loadError: string | null
  qty: Record<string, number>; setQty: (q: Record<string, number>) => void
  picked: { p: BazarSetupProduct; n: number }[]; pieces: number; onBack: () => void; onNext: () => void
}) {
  const { t } = useTranslation()
  const phone = useIsPhone()
  const [query, setQuery] = useState('')
  const [filter, setFilter] = useState<'all' | 'selected'>('all')
  const q = query.trim().toLowerCase()
  const shown = products.filter(p => {
    if (filter === 'selected' && !p.variants.some(v => (qty[v.id] ?? 0) > 0)) return false
    if (!q) return true
    return p.title.toLowerCase().includes(q) || p.variants.some(v => (v.sku ?? '').toLowerCase().includes(q) || (v.title ?? '').toLowerCase().includes(q))
  })
  const worth = picked.reduce((s, x) => s + x.p.variants.reduce((a, v) => a + (qty[v.id] ?? 0) * Number(v.price ?? 0), 0), 0)
  const size = (title: string | null) => title ?? t('bazar.wizard.oneSize')
  // Wizard entry — picked sizes that already have available pieces at Traced Main (warn, never block).
  const atMainPicked = products.reduce((s, p) => s + p.variants.reduce(
    (a, v) => a + ((qty[v.id] ?? 0) > 0 ? (v.atMain ?? 0) : 0), 0), 0)
  const other = (n: number | null) => (stockKnown || n !== null) && n !== null ? t('bazar.wizard.pick.other', { count: n }) : null

  function toggle(p: BazarSetupProduct, on: boolean) {
    const next = { ...qty }
    for (const v of p.variants) next[v.id] = on ? Math.max(1, next[v.id] ?? 0) : 0
    setQty(next)
  }

  return (
    <div className={cn('flex flex-col', phone && 'pb-24')} data-testid="bazar-setup-pick">
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_280px] items-start">
        <div className="card p-4 sm:p-5 flex flex-col gap-4 min-w-0">
          <div className="flex flex-col">
            <h1 className="text-h4 font-bold text-primary">{t('bazar.wizard.pick.title')}</h1>
            <span className="text-body text-muted">{t('bazar.wizard.pick.sub')}</span>
          </div>
          <div className="flex gap-2 flex-wrap">
            <label className="flex-1 min-w-[200px] relative">
              <Search size={16} aria-hidden className="absolute start-3 top-1/2 -translate-y-1/2 text-muted" />
              <input className="input ps-9 w-full" value={query} onChange={e => setQuery(e.target.value)}
                     placeholder={t('bazar.wizard.pick.search', { count: products.length })} aria-label={t('bazar.wizard.pick.searchLabel')}
                     data-testid="bazar-setup-search" />
            </label>
            {!phone && (['all', 'selected'] as const).map(f => (
              <button key={f} type="button" aria-pressed={filter === f} onClick={() => setFilter(f)} data-testid={`bazar-setup-filter-${f}`}
                className={cn('h-11 px-3.5 rounded-[10px] border text-body font-semibold',
                  filter === f ? 'bg-grey-900 border-grey-900 text-white' : 'border-line bg-surface text-primary')}>
                {f === 'all' ? t('bazar.wizard.pick.all') : t('bazar.wizard.pick.selected', { count: picked.length })}
              </button>
            ))}
          </div>
          {loadError && <p role="alert" className="text-body text-critical">{loadError}</p>}
          {products.length === 0 && !loadError && <p className="text-body text-muted">{t('bazar.wizard.pick.empty')}</p>}
          <ul className="flex flex-col gap-2.5">
            {shown.map(p => {
              const n = p.variants.reduce((s, v) => s + (qty[v.id] ?? 0), 0)
              const on = n > 0
              const totalOther = p.variants.every(v => v.otherLocations === null) ? null
                : p.variants.reduce((s, v) => s + (v.otherLocations ?? 0), 0)
              return (
                <li key={p.id} data-testid="bazar-setup-product"
                    className={cn('rounded-xl border p-3 flex gap-3 flex-wrap items-center', on ? 'border-trace-blue bg-trace-blue/[0.03]' : 'border-line')}>
                  <input type="checkbox" className="w-5 h-5 accent-trace-blue" checked={on} aria-label={p.title}
                         onChange={e => toggle(p, e.target.checked)} />
                  {p.imageUrl
                    ? <img src={p.imageUrl} alt="" className="w-12 h-12 rounded-lg object-cover bg-charcoal" />
                    : <span aria-hidden className="w-12 h-12 rounded-lg bg-charcoal" />}
                  <span className="flex flex-col min-w-[120px] flex-[1_1_140px]">
                    <span className="text-body font-semibold text-primary">{p.title}</span>
                    {p.variants[0]?.price != null && <span className="text-small text-muted">{amount(p.variants[0].price)} {t('bazar.wizard.egp')}</span>}
                  </span>
                  {on ? (
                    <span className="flex flex-wrap gap-3 items-end flex-[2_1_260px]">
                      {p.variants.map(v => (
                        <span key={v.id} className="flex flex-col gap-1">
                          <span className="text-small text-muted">
                            {size(v.title)}{other(v.otherLocations) ? ` · ${other(v.otherLocations)}` : ''}
                            {(v.atMain ?? 0) > 0 ? ` · ${t('bazar.newStock.atMain', { count: v.atMain })}` : ''}
                          </span>
                          <Stepper value={qty[v.id] ?? 0} label={`${p.title} ${size(v.title)}`}
                                   onChange={x => setQty({ ...qty, [v.id]: x })} />
                        </span>
                      ))}
                    </span>
                  ) : (
                    <span className="text-body text-muted flex-[2_1_200px]">{totalOther !== null ? other(totalOther) : ''}</span>
                  )}
                  {on && <span dir="ltr" className="text-body font-semibold text-primary tabular-nums ms-auto">{t('bazar.wizard.pick.pcs', { count: n })}</span>}
                </li>
              )
            })}
          </ul>
          {atMainPicked > 0 && (
            <p role="status" data-testid="bazar-setup-at-main-warning"
               className="rounded-xl border border-warning/40 bg-warning/10 p-3 text-body text-primary">
              {t('bazar.newStock.atMainWarning', { count: atMainPicked })}
            </p>
          )}
        </div>
        {!phone && (
          <aside className="flex flex-col gap-4 lg:sticky lg:top-6">
            <div className="rounded-2xl bg-grey-900 text-[#F2F4F7] p-5 flex flex-col gap-3" data-testid="bazar-setup-taking">
              <Eyebrow dark>{t('bazar.wizard.pick.taking')}</Eyebrow>
              <span className="flex items-baseline gap-2">
                <span dir="ltr" className="text-[40px] leading-none font-bold">{pieces}</span>
                <span className="text-sidebar-text">{t('bazar.wizard.pick.piecesProducts', { count: picked.length })}</span>
              </span>
              <ul className="flex flex-col gap-1.5 text-body">
                {picked.map(x => (
                  <li key={x.p.id} className="flex justify-between gap-2"><span className="truncate text-sidebar-text">{x.p.title}</span>
                    <span dir="ltr" className="font-semibold tabular-nums">{x.n}</span></li>
                ))}
              </ul>
              {pieces > 0 && <span className="text-small text-sidebar-text border-t border-white/10 pt-3">
                {t('bazar.wizard.pick.worth', { amount: amount(worth) })}</span>}
            </div>
            <div className="card p-4 flex flex-col gap-1.5" data-testid="bazar-setup-shopify-note">
              <span className="text-body font-semibold text-primary">{t('bazar.wizard.pick.noteTitle')}</span>
              <span className="text-body text-muted">{t('bazar.wizard.pick.note')}</span>
            </div>
          </aside>
        )}
      </div>
      <Footer left={t('bazar.wizard.pick.selectedPieces', { count: pieces })}>
        {!phone && (
          <button type="button" className={btnSecondary} onClick={onBack} data-testid="bazar-setup-back">
            <ArrowLeft size={16} aria-hidden className="rtl:-scale-x-100" />{t('bazar.wizard.back')}
          </button>
        )}
        <button type="button" className={cn(btnPrimary, phone && 'w-full h-[52px]')} disabled={pieces === 0} onClick={onNext}
                data-testid="bazar-setup-next">
          {phone ? t('bazar.wizard.pick.nextPhone', { count: pieces }) : t('bazar.wizard.pick.next')}
          {!phone && <ArrowRight size={16} aria-hidden className="rtl:-scale-x-100" />}
        </button>
      </Footer>
    </div>
  )
}

function BarcodesStep({ eventId, picked, pieces, qty, labelsOn, setLabelsOn, noneOn, setNoneOn, labelsFor, setPerProduct, onBack, onReceived }: {
  eventId: string; picked: { p: BazarSetupProduct; n: number }[]; pieces: number; qty: Record<string, number>
  labelsOn: boolean; setLabelsOn: (on: boolean) => void; noneOn: boolean; setNoneOn: (on: boolean) => void
  labelsFor: (productId: string) => boolean; setPerProduct: (id: string, on: boolean) => void
  onBack: () => void; onReceived: (s: BazarSetupStatus) => void
}) {
  const { t, i18n } = useTranslation()
  const phone = useIsPhone()
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function receive() {
    setSaving(true); setError(null)
    const saved = readSaved()
    const requestId = saved.requestId ?? newRequestId()
    writeSaved({ ...saved, eventId, requestId })
    try {
      const lines = picked.flatMap(x => x.p.variants.filter(v => (qty[v.id] ?? 0) > 0)
        .map(v => ({ variantId: v.id, quantity: qty[v.id], labels: labelsFor(x.p.id) })))
      onReceived(await receiveBazarSetup(eventId, requestId, lines))
    } catch (e) {
      setError(e instanceof BazarError ? e.message_(i18n.language) : t('bazar.saveFailed'))
    } finally {
      setSaving(false)
    }
  }

  const card = (kind: 'labels' | 'none', on: boolean, set: (on: boolean) => void) => (
    <label data-testid={`bazar-setup-mode-${kind}`}
      className={cn('relative flex flex-col gap-2 p-5 rounded-2xl border cursor-pointer',
        'has-[:focus-visible]:outline has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-trace-blue',
        on ? 'border-trace-blue border-[1.5px]' : 'border-line bg-surface')}>
      <input type="checkbox" className="sr-only" checked={on} onChange={e => set(e.target.checked)} />
      <span className="flex items-start justify-between gap-3">
        <span className="text-body-lg font-bold text-primary">{t(`bazar.wizard.barcodes.${kind}.title`)}</span>
        <span aria-hidden className={cn('w-[22px] h-[22px] rounded border flex items-center justify-center shrink-0',
          on ? 'bg-trace-blue border-trace-blue text-white' : 'bg-surface border-grey-100')}>{on && <Check size={14} strokeWidth={3} />}</span>
      </span>
      <span className="text-body text-muted">{t(`bazar.wizard.barcodes.${kind}.desc`)}</span>
      <ul className="flex flex-col gap-1 text-body text-primary">
        {(['p1', 'p2', 'm1'] as const).map(k => (
          <li key={k} className="flex gap-2"><span aria-hidden className={k.startsWith('p') ? 'text-success-text' : 'text-warning-text'}>{k.startsWith('p') ? '+' : '−'}</span>
            {t(`bazar.wizard.barcodes.${kind}.${k}`)}</li>
        ))}
      </ul>
      {kind === 'labels' && <span className="text-small text-muted">{t('bazar.wizard.barcodes.labelSize')}</span>}
    </label>
  )

  return (
    <div className={cn('flex flex-col gap-5', phone && 'pb-24')} data-testid="bazar-setup-barcodes">
      <div className="flex flex-col">
        <h1 className="text-h3 font-bold text-primary">{t('bazar.wizard.barcodes.title')}</h1>
        <span className="text-body text-muted">{t('bazar.wizard.barcodes.sub')}</span>
      </div>
      <div className="grid gap-4 sm:grid-cols-2">
        {card('labels', labelsOn, setLabelsOn)}
        {card('none', noneOn, setNoneOn)}
      </div>
      {labelsOn && noneOn && (
        <div className="card p-4 flex flex-col gap-2" data-testid="bazar-setup-mix">
          <span className="text-body font-semibold text-primary">{t('bazar.wizard.barcodes.mixTitle')}</span>
          {picked.map(x => (
            <div key={x.p.id} className="flex items-center gap-3 flex-wrap" data-testid="bazar-setup-mix-row">
              <span className="text-body text-primary me-auto">{x.p.title} · {t('bazar.wizard.pick.pcs', { count: x.n })}</span>
              <span className="inline-flex rounded-lg border border-line overflow-hidden" role="radiogroup" aria-label={x.p.title}>
                {([true, false] as const).map(l => (
                  <button key={String(l)} type="button" role="radio" aria-checked={labelsFor(x.p.id) === l}
                    onClick={() => setPerProduct(x.p.id, l)}
                    className={cn('px-3 h-9 text-body font-semibold', labelsFor(x.p.id) === l ? 'bg-trace-blue text-white' : 'bg-surface text-primary')}>
                    {l ? t('bazar.wizard.barcodes.mixLabels') : t('bazar.wizard.barcodes.mixNone')}
                  </button>
                ))}
              </span>
            </div>
          ))}
        </div>
      )}
      {error && <p role="alert" className="text-body text-critical">{error}</p>}
      <Footer left={t('bazar.wizard.barcodes.footer')}>
        {!phone && (
          <button type="button" className={btnSecondary} onClick={onBack} data-testid="bazar-setup-back">
            <ArrowLeft size={16} aria-hidden className="rtl:-scale-x-100" />{t('bazar.wizard.back')}
          </button>
        )}
        <button type="button" className={cn(btnPrimary, phone && 'w-full h-[52px]')} onClick={receive}
                disabled={saving || (!labelsOn && !noneOn)} data-testid="bazar-setup-receive">
          {saving && <Loader2 size={16} className="animate-spin" aria-hidden />}
          {t('bazar.wizard.barcodes.receive', { count: pieces })}
        </button>
      </Footer>
    </div>
  )
}

// ── Lower these → Done → Continue ────────────────────────────────────────────

function LowerStep({ status, onDone }: {
  status: BazarSetupStatus; onDone: (s: BazarSetupStatus) => void
}) {
  const { t } = useTranslation()
  const b = status.batch
  const [printError, setPrintError] = useState<string | null>(null)
  return (
    <div className="flex flex-col gap-5 max-w-[760px]" data-testid="bazar-setup-lower">
      <div className="flex flex-col gap-1">
        <h1 className="text-h3 font-bold text-primary">{t('bazar.wizard.lower.title')}</h1>
        <p className="text-body text-muted">{t('bazar.wizard.lower.body')}</p>
      </div>
      {b?.labelledReceiptId && (
        <div className="card p-4 flex items-center gap-3 flex-wrap">
          <span className="text-body text-primary me-auto">{t('bazar.wizard.lower.labelsReady')}</span>
          <button type="button" className={btnSecondary} data-testid="bazar-setup-print"
            onClick={() => { setPrintError(null); openBazarSetupLabels(b.labelledReceiptId!).catch(() => setPrintError(t('bazar.wizard.lower.printFailed'))) }}>
            <Printer size={16} aria-hidden />{t('bazar.wizard.lower.print')}
          </button>
          {printError && <p role="alert" className="text-body text-critical w-full">{printError}</p>}
        </div>
      )}
      <div className="card p-4 sm:p-5">
        <LowerTheseList onDone={onDone} />
      </div>
    </div>
  )
}

// ── 4 Prices (B9c) ───────────────────────────────────────────────────────────

function PricesStep({ eventId, leftAtMain, onNext }: { eventId: string; leftAtMain: number; onNext: () => void }) {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const phone = useIsPhone()
  const [ev, setEv] = useState<BazarEventDetail | null>(null)
  const [sending, setSending] = useState(leftAtMain > 0)
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)
  // One request id per chunk of the batch: a retry after a lost answer re-sends the SAME id (nothing sent twice).
  const [requestId, setRequestId] = useState(newRequestId)

  useEffect(() => {
    let cancelled = false
    ;(async () => {
      setError(null)
      try {
        if (leftAtMain > 0 || attempt > 0) {
          setSending(true)
          let id = requestId
          for (let i = 0; i < 50; i++) {
            const r = await sendBazarSetupBatch(id)
            if (cancelled) return
            if (r.left <= 0 || r.sent === 0) break
            id = newRequestId()
            setRequestId(id)
          }
        }
        const next = await getBazarEvent(eventId)
        if (!cancelled) { setEv(next); setSending(false) }
      } catch (e) {
        if (cancelled) return
        setSending(false)
        setError(e instanceof BazarError ? e.message_(lang) : t('bazar.saveFailed'))
      }
    })()
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [eventId, attempt])

  if (error) {
    return (
      <div className="card p-5 flex flex-col gap-3 max-w-[640px]" data-testid="bazar-setup-send-error">
        <p role="alert" className="text-body text-critical">{error}</p>
        <button type="button" className={cn(btnPrimary, 'self-start')} onClick={() => setAttempt(a => a + 1)}
                data-testid="bazar-setup-send-retry">{t('bazar.wizard.prices.retry')}</button>
      </div>
    )
  }
  if (sending || !ev) {
    return (
      <div className="flex items-center gap-3 text-body text-muted" data-testid="bazar-setup-sending">
        <Loader2 className="animate-spin" aria-hidden />{t('bazar.wizard.prices.sending')}
      </div>
    )
  }
  return <PricesBody ev={ev} phone={phone} onNext={onNext} />
}

function PricesBody({ ev, phone, onNext }: { ev: BazarEventDetail; phone: boolean; onNext: () => void }) {
  const { t } = useTranslation()
  const prices = usePriceData(ev)
  const totals = priceTotals(prices)
  return (
    <div className={cn('flex flex-col gap-5', phone && 'pb-24')} data-testid="bazar-setup-prices">
      <div className="flex flex-col gap-1">
        <h1 className="text-h3 font-bold text-primary">{t('bazar.wizard.prices.title')}</h1>
        <p className="text-body text-muted">{t('bazar.wizard.prices.sub', { count: ev.counts.sent })}</p>
      </div>
      <PricesCard ev={ev} data={prices} />
      <Footer left={prices.rows && totals.missing > 0
        ? t('bazar.r1.overview.sub.missing', { count: totals.missing })
        : t('bazar.wizard.stepOf', { n: 4, total: 5 })}>
        <button type="button" className={cn(btnPrimary, phone && 'w-full h-[52px]')} onClick={onNext}
                data-testid="bazar-setup-next">
          {t('bazar.wizard.prices.next')}<ArrowRight size={16} aria-hidden className="rtl:-scale-x-100" />
        </button>
      </Footer>
    </div>
  )
}

// ── 5 Ready (B9c) — Get ready's send and start, same calls and confirms ──────

function ReadyStep({ eventId, onBack, onStarted }: { eventId: string; onBack: () => void; onStarted: () => void }) {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const phone = useIsPhone()
  const [ev, setEv] = useState<BazarEventDetail | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [confirmSend, setConfirmSend] = useState(false)
  const [busy, setBusy] = useState<'send' | 'start' | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [changing, setChanging] = useState<OnlineStock | null>(null)
  const [savingOnline, setSavingOnline] = useState(false)
  const [onlineError, setOnlineError] = useState<string | null>(null)

  const load = useCallback(() => getBazarEvent(eventId).then(setEv, e => {
    setLoadError(e instanceof BazarError ? e.message_(lang) : t('bazar.loadFailed'))
  }), [eventId, lang, t])
  useEffect(() => { load() }, [load])

  if (loadError) return <p role="alert" className="text-body text-critical">{loadError}</p>
  if (!ev) return <Loader2 className="animate-spin text-muted" aria-label={t('bazar.wizard.loading')} />

  const sent = ev.transfer_status === 'sent'
  const preparing = ev.transfer_status === 'preparing'
  const pieces = ev.counts.sent
  const online: OnlineStock = ev.online_stock === 'leave' ? 'leave' : 'remove'

  async function act(kind: 'send' | 'start') {
    setBusy(kind)
    setError(null)
    try {
      if (kind === 'send') {
        setEv(await markBazarSent(eventId))
      } else {
        await startBazarEvent(eventId)
        resetCapabilitiesCache()   // GET /me's bazarLive changes
        onStarted()
      }
    } catch (e) {
      setError(e instanceof BazarError ? e.message_(lang) : t('bazar.saveFailed'))
      load()
    } finally {
      setBusy(null)
      setConfirmSend(false)
    }
  }

  async function saveOnline() {
    if (!changing) return
    setSavingOnline(true)
    setOnlineError(null)
    try {
      setEv(await setBazarOnlineStock(eventId, changing))
      setChanging(null)
    } catch (e) {
      setOnlineError(e instanceof BazarError ? e.message_(lang) : t('bazar.saveFailed'))
    } finally {
      setSavingOnline(false)
    }
  }

  return (
    <div className={cn('flex flex-col gap-5 max-w-[760px]', phone && 'pb-24')} data-testid="bazar-setup-ready">
      <div className="flex flex-col gap-1">
        <h1 className="text-h3 font-bold text-primary">{t('bazar.wizard.ready.title')}</h1>
        <p className="text-body text-muted">{sent ? t('bazar.start.ready') : t('bazar.r3.setup.sendHint')}</p>
      </div>
      <div className="card p-4 sm:p-5 flex flex-col gap-4">
        <div className="flex items-center gap-3">
          <span className="flex flex-col me-auto min-w-0">
            <span className="text-h4 font-bold text-primary" data-testid="bazar-setup-ready-pieces">
              {t('bazar.r3.setup.ready', { count: pieces })}
            </span>
            <span className="text-small text-muted">{ev.name}</span>
          </span>
          <span className={cn('text-small font-semibold', sent ? 'text-success-text' : 'text-muted')}>
            {sent ? t('bazar.transfer.sent') : t('bazar.transfer.preparing')}
          </span>
        </div>
        <div className="flex items-center gap-3 rounded-[10px] border border-line px-3 py-2.5" data-testid="bazar-online-row">
          <span className="flex flex-col min-w-0 me-auto">
            <span className="text-small text-muted">{t('bazar.online.row')}</span>
            <span className="text-body font-semibold text-primary">{t(`bazar.online.${online}.title`)}</span>
            <span className="text-small text-muted">
              {ev.online_stock_locked ? t('bazar.online.locked') : t(`bazar.online.${online}.desc`)}
            </span>
          </span>
          <OnlineStockTag mode={online} />
          {!ev.online_stock_locked && (
            <button type="button" className="text-body font-semibold text-trace-blue" data-testid="bazar-online-change"
                    onClick={() => { setOnlineError(null); setChanging(online) }}>
              {t('bazar.online.change')}
            </button>
          )}
        </div>
        {error && <p role="alert" className="text-body text-critical">{error}</p>}
      </div>
      <Footer left={t('bazar.wizard.stepOf', { n: 5, total: 5 })}>
        {preparing && !phone && (
          <button type="button" className={btnSecondary} onClick={onBack} data-testid="bazar-setup-back">
            <ArrowLeft size={16} aria-hidden className="rtl:-scale-x-100" />{t('bazar.wizard.back')}
          </button>
        )}
        {preparing && (
          <button type="button" className={cn(btnPrimary, phone && 'w-full h-[52px]')} onClick={() => setConfirmSend(true)}
                  disabled={pieces === 0 || busy === 'send'} data-testid="bazar-mark-sent">
            {busy === 'send' && <Loader2 size={16} className="animate-spin" aria-hidden />}
            {t('bazar.r3.setup.markSent', { count: pieces })}
          </button>
        )}
        {sent && (
          <button type="button" className={cn(btnPrimary, phone && 'w-full h-[52px]')} onClick={() => act('start')}
                  disabled={busy === 'start'} data-testid="bazar-start">
            {busy === 'start' && <Loader2 size={16} className="animate-spin" aria-hidden />}
            {t('bazar.start.button')}
          </button>
        )}
      </Footer>

      {changing && (
        <Modal title={t('bazar.online.dialogTitle')} onClose={() => setChanging(null)}>
          <div className="flex flex-col gap-4" data-testid="bazar-online-dialog">
            <OnlineStockCards value={changing} onChange={setChanging} name="bazar-online-stock-change" />
            {onlineError && <p role="alert" className="text-body text-critical">{onlineError}</p>}
            <div className="flex justify-end gap-2">
              <Button variant="secondary" onClick={() => setChanging(null)}>{t('bazar.form.cancel')}</Button>
              <Button onClick={saveOnline} loading={savingOnline}>{t('bazar.online.save')}</Button>
            </div>
          </div>
        </Modal>
      )}

      {confirmSend && (
        <Modal title={t('bazar.transfer.confirmTitle')} onClose={() => setConfirmSend(false)}>
          <p className="text-body text-primary" data-testid="bazar-send-confirm-body">
            {t(online === 'leave' ? 'bazar.transfer.confirmBodyKeep' : 'bazar.transfer.confirmBody', { count: pieces })}
          </p>
          <div className="flex justify-end gap-2 mt-5">
            <Button variant="secondary" onClick={() => setConfirmSend(false)}>{t('bazar.form.cancel')}</Button>
            <Button onClick={() => act('send')} loading={busy === 'send'}>{t('bazar.transfer.markSent')}</Button>
          </div>
        </Modal>
      )}
    </div>
  )
}

function DoneStep({ eventId }: { eventId: string | null }) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  return (
    <div className="card p-6 flex flex-col gap-3 max-w-[640px]" data-testid="bazar-setup-done">
      <span aria-hidden className="w-10 h-10 rounded-full bg-success/10 text-success-text flex items-center justify-center"><Check size={20} /></span>
      <h1 className="text-h3 font-bold text-primary">{t('bazar.wizard.done.title')}</h1>
      <p className="text-body text-muted">{t('bazar.wizard.done.body')}</p>
      <button type="button" className={cn(btnPrimary, 'self-start')} data-testid="bazar-setup-continue"
              onClick={() => navigate(eventId ? `/bazar/${encodeURIComponent(eventId)}` : '/bazar')}>
        {t('bazar.wizard.done.continue')}<ArrowRight size={16} aria-hidden className="rtl:-scale-x-100" />
      </button>
    </div>
  )
}
