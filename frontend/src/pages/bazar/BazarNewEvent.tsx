import { FormEvent, useEffect, useState, type ReactNode } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { HelpLink } from '../../help/HelpLink'
import {
  ArrowLeft, ArrowRight, Banknote, Check, CreditCard, Crown, History, MapPin, Smartphone, UserCog, Users, type LucideIcon,
} from 'lucide-react'
import { listLocations, type LocationRow } from '../../api'
import {
  BazarError, createBazarEvent, METHOD_ORDER, type OnlineStock, type PaymentMethod, type SellerRole,
} from '../../bazar/api'
import {
  BlockingBazarLink, OnlineStockCards, OnlineStockPhonePair, OnlineStockWholeLocation, blockingBazar,
} from './OnlineStock'
import { Button, Input, Modal, cn } from '../../components/ui'
import RangeCalendar from './r1/RangeCalendar'
import { cairoToday, dayCount, daysFrom } from './r1/overviewState'
import { DateTile, Eyebrow, rangeLabel, useIsPhone } from './r1/parts'

const ROLES: SellerRole[] = ['owner', 'manager', 'worker']

type Icon = LucideIcon
const METHOD_ICON: Record<PaymentMethod, Icon> = { cash: Banknote, instapay: Smartphone, card: CreditCard }
const ROLE_ICON: Record<SellerRole, Icon> = { owner: Crown, manager: UserCog, worker: Users }

/** A location a bazar accepts: type 'event', never the warehouse, never a Shopify location (either online stock mode — B8). */
function isEventLocation(l: LocationRow) {
  return l.type === 'event' && !l.is_fulfillment && !l.shopify_location_id
}

/** Square 6px tick box (bazar-only). */
function Tick({ on, className }: { on: boolean; className?: string }) {
  return (
    <span aria-hidden className={cn('w-[22px] h-[22px] rounded border flex items-center justify-center shrink-0 transition-colors',
      on ? 'bg-trace-blue border-trace-blue text-white' : 'bg-surface border-grey-100', className)}>
      {on && <Check size={14} strokeWidth={3} />}
    </span>
  )
}

/** A checkbox tile: icon, title (the accessible name), description, square tick. The whole tile is the label. */
function CheckTile({ icon: Ico, title, desc, checked, onChange }: {
  icon: Icon; title: string; desc: string; checked: boolean; onChange: (on: boolean) => void
}) {
  return (
    <label className={cn('relative flex flex-col gap-1 p-3 sm:p-3.5 rounded-xl border cursor-pointer select-none transition-colors min-w-0',
      'items-center text-center sm:items-start sm:text-start',
      'has-[:focus-visible]:outline has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-trace-blue',
      checked ? 'border-trace-blue border-[1.5px] bg-trace-blue/[0.04]' : 'border-line bg-surface')}>
      <input type="checkbox" className="sr-only" checked={checked} onChange={e => onChange(e.target.checked)} />
      <span className="hidden sm:flex w-full items-start justify-between">
        <span aria-hidden className="w-9 h-9 rounded-lg bg-charcoal flex items-center justify-center text-primary"><Ico size={18} aria-hidden /></span>
        <Tick on={checked} />
      </span>
      <span className={cn('text-body font-semibold sm:mt-2', checked ? 'text-trace-blue sm:text-primary' : 'text-primary')}>{title}</span>
      <span className="hidden sm:block text-small text-muted">{desc}</span>
    </label>
  )
}

function Section({ n, title, sub, children }: { n: number; title: string; sub: string; children: ReactNode }) {
  return (
    <section className="card p-4 sm:p-5 flex flex-col gap-4 min-w-0">
      <div className="flex items-start gap-3">
        <span aria-hidden dir="ltr" className="w-7 h-7 rounded bg-grey-900 text-white text-small font-bold flex items-center justify-center shrink-0">{n}</span>
        <span className="flex flex-col">
          <h2 className="text-body-lg font-semibold text-primary">{title}</h2>
          <span className="text-body text-muted">{sub}</span>
        </span>
      </div>
      {children}
    </section>
  )
}

/** The four bazar steps — display only (step 1 is this form). */
function StepsBar({ phone }: { phone: boolean }) {
  const { t } = useTranslation()
  if (phone) {
    return (
      <div className="flex flex-col gap-1.5" data-testid="bazar-steps">
        <span className="text-small text-muted">{t('bazar.r1.form.stepOf', { n: 1, total: 4 })} · {t('bazar.r1.steps.s1')}</span>
        <div aria-hidden className="grid grid-cols-4 gap-1.5">
          {[1, 2, 3, 4].map(n => <span key={n} className={cn('h-1 rounded-sm', n === 1 ? 'bg-trace-blue' : 'bg-line')} />)}
        </div>
      </div>
    )
  }
  return (
    <ol className="grid grid-cols-4 gap-3" data-testid="bazar-steps">
      {[1, 2, 3, 4].map(n => (
        <li key={n} aria-current={n === 1 ? 'step' : undefined} className="flex flex-col gap-1 min-w-0">
          <span aria-hidden className={cn('h-1 rounded-sm', n === 1 ? 'bg-trace-blue' : 'bg-line')} />
          <span className={cn('text-body mt-1.5 truncate', n === 1 ? 'font-semibold text-primary' : 'text-muted')}>
            <span dir="ltr">{n}</span> · {t(`bazar.r1.steps.s${n}`)}
          </span>
          <span className="text-small text-muted truncate">{t(`bazar.r1.steps.s${n}d`)}</span>
        </li>
      ))}
    </ol>
  )
}

/** Bazar POS — /bazar/new (R1 restyle). B8: section 5 "Online stock" — Remove from online stock (default) / Keep showing online. */
export default function BazarNewEvent() {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const navigate = useNavigate()
  const phone = useIsPhone()
  const today = cairoToday()
  const [name, setName] = useState('')
  const [where, setWhere] = useState<'new' | 'existing'>('new')
  const [locationName, setLocationName] = useState('')
  const [locationId, setLocationId] = useState('')
  const [locations, setLocations] = useState<LocationRow[]>([])
  const [startsOn, setStartsOn] = useState(today)
  const [endsOn, setEndsOn] = useState(today)
  const [methods, setMethods] = useState<PaymentMethod[]>(['cash', 'instapay', 'card'])
  const [roles, setRoles] = useState<SellerRole[]>(['owner', 'manager', 'worker'])
  const [online, setOnline] = useState<OnlineStock>('remove')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [blocking, setBlocking] = useState<{ id: string; name: string } | null>(null)
  const [whereOpen, setWhereOpen] = useState(false)

  useEffect(() => {
    listLocations().then(ls => {
      const ev = ls.filter(isEventLocation)
      setLocations(ev)
      if (ev.length > 0) setLocationId(ev[0].id)
    }).catch(() => setLocations([]))
  }, [])

  const toggle = <V,>(list: V[], v: V, on: boolean) => (on ? [...list.filter(x => x !== v), v] : list.filter(x => x !== v))

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setBlocking(null)
    if (!name.trim()) { setError(t('bazar.form.nameRequired')); return }
    if (where === 'new' && locationName.trim().length < 3) { setError(t('bazar.form.locationRequired')); return }
    if (where === 'existing' && !locationId) { setError(t('bazar.form.pickLocation')); return }
    if (methods.length === 0) { setError(t('bazar.form.methodRequired')); return }
    if (roles.length === 0) { setError(t('bazar.form.roleRequired')); return }
    setSaving(true)
    try {
      const ev = await createBazarEvent({
        name: name.trim(), startsOn, endsOn,
        locationId: where === 'existing' ? locationId : null,
        locationName: where === 'new' ? locationName.trim() : null,
        paymentMethods: METHOD_ORDER.filter(m => methods.includes(m)),
        sellerRoles: ROLES.filter(r => roles.includes(r)),
        // B8: the default (Remove from online stock) sends no mode — the server's default; Keep sends 'leave'.
        ...(online === 'leave' ? { shopifySyncMode: 'leave' as const } : {}),
      })
      navigate(`/bazar/${ev.id}`)
    } catch (err) {
      setError(err instanceof BazarError ? err.message_(i18n.language) : t('bazar.saveFailed'))
      setBlocking(blockingBazar(err))
    } finally {
      setSaving(false)
    }
  }

  const n = dayCount(startsOn, endsOn)
  const startsIn = daysFrom(today, startsOn)
  const placeLabel = where === 'new' ? locationName.trim() : (locations.find(l => l.id === locationId)?.name ?? '')
  const methodsText = METHOD_ORDER.filter(m => methods.includes(m)).map(m => t(`bazar.method.${m}`)).join(' · ')
  const startsText = startsIn > 0 ? t('bazar.r1.form.startsIn', { count: startsIn })
    : startsIn === 0 ? t('bazar.r1.form.startsToday') : t('bazar.r1.form.started', { count: -startsIn })

  const whereChooser = (
    <div className="grid grid-cols-1 sm:grid-cols-2 gap-3" role="radiogroup" aria-label={t('bazar.form.location')}>
      <label className={cn('relative flex flex-col gap-1 p-3.5 rounded-xl border cursor-pointer min-w-0',
        'has-[:focus-visible]:outline has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-trace-blue',
        where === 'new' ? 'border-trace-blue border-[1.5px] bg-trace-blue/[0.04]' : 'border-line')}>
        <input type="radio" name="bazar-where" value="new" className="sr-only" checked={where === 'new'} onChange={() => setWhere('new')} />
        <span className="flex items-start justify-between">
          <span aria-hidden className="w-9 h-9 rounded-lg bg-charcoal flex items-center justify-center text-primary"><MapPin size={18} /></span>
          <Tick on={where === 'new'} />
        </span>
        <span className="text-body font-semibold text-primary mt-2">{t('bazar.form.newLocation')}</span>
        <span className="text-small text-muted">{t('bazar.r1.form.newLocationHint')}</span>
        {where === 'new' && (
          <Input className="mt-2" aria-label={t('bazar.form.locationName')} value={locationName} maxLength={80}
                 onChange={e => setLocationName(e.target.value)} placeholder={t('bazar.form.locationPlaceholder')} />
        )}
      </label>
      <label className={cn('relative flex flex-col gap-1 p-3.5 rounded-xl border min-w-0',
        locations.length === 0 ? 'opacity-60 cursor-not-allowed' : 'cursor-pointer',
        'has-[:focus-visible]:outline has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-trace-blue',
        where === 'existing' ? 'border-trace-blue border-[1.5px] bg-trace-blue/[0.04]' : 'border-line')}>
        <input type="radio" name="bazar-where" value="existing" className="sr-only" checked={where === 'existing'}
               disabled={locations.length === 0} onChange={() => setWhere('existing')} />
        <span className="flex items-start justify-between">
          <span aria-hidden className="w-9 h-9 rounded-lg bg-charcoal flex items-center justify-center text-primary"><History size={18} /></span>
          <Tick on={where === 'existing'} />
        </span>
        <span className="text-body font-semibold text-primary mt-2">{t('bazar.form.existingLocation')}</span>
        {where === 'existing' ? (
          <select aria-label={t('bazar.form.locationName')} className="input mt-2" value={locationId}
                  onChange={e => setLocationId(e.target.value)}>
            {locations.map(l => <option key={l.id} value={l.id}>{l.name}</option>)}
          </select>
        ) : (
          <span className="text-small text-muted">
            {locations.length === 0 ? t('bazar.form.noEventLocations') : t('bazar.form.existingHint')}
          </span>
        )}
      </label>
    </div>
  )

  const nameInput = (
    <Input aria-label={t('bazar.form.name')} value={name} maxLength={80} onChange={e => setName(e.target.value)}
           placeholder={t('bazar.form.namePlaceholder')} className="h-[52px] text-[20px] font-semibold" />
  )

  const calendar = (
    <RangeCalendar start={startsOn} end={endsOn} today={today}
      onChange={(s, e) => { setStartsOn(s); setEndsOn(e) }} />
  )

  const methodTiles = (
    <div className="grid grid-cols-3 gap-2.5">
      {METHOD_ORDER.map(m => (
        <CheckTile key={m} icon={METHOD_ICON[m]} title={t(`bazar.method.${m}`)} desc={t(`bazar.r1.form.methodDesc.${m}`)}
                   checked={methods.includes(m)} onChange={on => setMethods(toggle(methods, m, on))} />
      ))}
    </div>
  )

  const roleTiles = (
    <div className="grid grid-cols-3 gap-2.5">
      {ROLES.map(r => (
        <CheckTile key={r} icon={ROLE_ICON[r]} title={t(`bazar.role.${r}`)} desc={t(`bazar.r1.form.roleDesc.${r}`)}
                   checked={roles.includes(r)} onChange={on => setRoles(toggle(roles, r, on))} />
      ))}
    </div>
  )

  const removeNote = online === 'remove'
    ? <p className="text-small text-muted" data-testid="bazar-remove-note">{t('bazar.form.removeNote')}</p>
    : <p className="text-small text-muted" data-testid="bazar-keep-note">{t('bazar.online.keepNote')}</p>

  const errorLine = error && (
    <div className="flex flex-col gap-1">
      <p role="alert" className="text-body text-critical">{error}</p>
      <BlockingBazarLink bazar={blocking} />
    </div>
  )
  const createLabel = t('bazar.r1.form.create')

  if (phone) {
    return (
      <form onSubmit={submit} noValidate className="flex flex-col gap-4 pb-24" data-testid="bazar-new">
        <div className="flex flex-col gap-1">
          <StepsBar phone />
          <h1 className="text-[26px] leading-8 font-bold tracking-[-0.02em] text-primary">{t('bazar.r1.form.title')}</h1>
          <HelpLink screen="bazarNew" className="self-start" />
        </div>
        {nameInput}
        <div className="card p-3.5 flex items-center gap-3">
          <span aria-hidden className="w-9 h-9 rounded-lg bg-charcoal flex items-center justify-center text-primary shrink-0"><MapPin size={18} /></span>
          <span className="flex flex-col min-w-0 me-auto">
            <span className="text-small text-muted">{t('bazar.r1.form.where')}</span>
            <span className="text-body font-semibold text-primary truncate">
              {placeLabel || t('bazar.r1.form.wherePick')}
              {placeLabel && where === 'new' && ` · ${t('bazar.r1.form.whereNew')}`}
            </span>
          </span>
          <button type="button" onClick={() => setWhereOpen(true)} className="text-body font-semibold text-trace-blue">
            {t('bazar.r1.form.change')}
          </button>
        </div>
        {calendar}
        <div className="card px-4 py-3 flex items-center justify-between gap-3">
          <span className="text-body font-semibold text-primary">{t('bazar.r1.form.sellingDays', { count: n })}</span>
          <span className="text-body text-muted tabular-nums">{rangeLabel(startsOn, endsOn, lang)}</span>
        </div>
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-2"><Eyebrow>{t('bazar.r1.form.payWith')}</Eyebrow></legend>
          {methodTiles}
        </fieldset>
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-2"><Eyebrow>{t('bazar.form.sellers')}</Eyebrow></legend>
          {roleTiles}
        </fieldset>
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-2"><Eyebrow>{t('bazar.online.title')}</Eyebrow></legend>
          <OnlineStockPhonePair value={online} onChange={setOnline} />
          <OnlineStockWholeLocation />
          <HelpLink screen="bazarOnlineStock" className="self-start" />
          <span className="text-small text-muted">{t(`bazar.online.${online}.desc`)}</span>
        </fieldset>
        {removeNote}
        {errorLine}
        <div className="fixed inset-x-0 bottom-0 z-20 bg-surface border-t border-line p-4">
          <button type="submit" disabled={saving}
            className="w-full h-[52px] rounded-xl bg-trace-blue hover:bg-trace-blue-hover disabled:opacity-60 text-white font-semibold text-body-lg flex items-center justify-center gap-2">
            {createLabel}<ArrowRight size={18} aria-hidden className="rtl:-scale-x-100" />
          </button>
        </div>
        {whereOpen && (
          <Modal title={t('bazar.form.location')} onClose={() => setWhereOpen(false)}>
            <div className="flex flex-col gap-4">
              {whereChooser}
              <Button onClick={() => setWhereOpen(false)}>{t('bazar.r1.form.done')}</Button>
            </div>
          </Modal>
        )}
      </form>
    )
  }

  return (
    <form onSubmit={submit} noValidate className="flex flex-col gap-5 max-w-[1120px]" data-testid="bazar-new">
      <div className="flex flex-col gap-1">
        <Link to="/bazar" className="text-small text-muted hover:text-primary self-start inline-flex items-center gap-1">
          <ArrowLeft size={14} aria-hidden className="rtl:-scale-x-100" />{t('bazar.title')}
        </Link>
        <h1 className="text-[32px] leading-10 font-bold tracking-[-0.02em] text-primary">{t('bazar.r1.form.title')}</h1>
        <HelpLink screen="bazarNew" className="self-start" />
      </div>
      <StepsBar phone={false} />

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-5 items-stretch">
        <Section n={1} title={t('bazar.r1.form.namePlace')} sub={t('bazar.r1.form.namePlaceSub')}>
          {nameInput}
          {whereChooser}
        </Section>
        <Section n={2} title={t('bazar.r1.form.when')} sub={t('bazar.r1.form.whenSub')}>
          <div className="flex flex-wrap gap-4">
            <div className="flex-[3_1_230px] min-w-0">{calendar}</div>
            <div className="flex-[2_1_150px] min-w-0 flex flex-col gap-3">
              <div className="bg-charcoal border border-line rounded-xl p-3.5 flex flex-col gap-0.5" data-testid="bazar-selling-days">
                <Eyebrow>{t('bazar.r1.form.sellingDaysTitle')}</Eyebrow>
                <span className="text-[26px] leading-8 font-bold text-primary">{t('bazar.r1.form.sellingDays', { count: n })}</span>
                <span className="text-body text-muted tabular-nums text-start">{rangeLabel(startsOn, endsOn, lang)}</span>
              </div>
              <p className="text-body text-muted">{t('bazar.r1.form.whenHelp')}</p>
            </div>
          </div>
        </Section>
        <Section n={3} title={t('bazar.r1.form.pay')} sub={t('bazar.r1.form.paySub')}>
          <fieldset>
            <legend className="sr-only">{t('bazar.form.methods')}</legend>
            {methodTiles}
          </fieldset>
        </Section>
        <Section n={4} title={t('bazar.r1.form.who')} sub={t('bazar.r1.form.whoSub')}>
          <fieldset>
            <legend className="sr-only">{t('bazar.form.sellers')}</legend>
            {roleTiles}
          </fieldset>
        </Section>
      </div>
      <Section n={5} title={t('bazar.online.title')} sub={t('bazar.online.sub')}>
        <OnlineStockCards value={online} onChange={setOnline} />
        <OnlineStockWholeLocation />
        <HelpLink screen="bazarOnlineStock" className="self-start" />
      </Section>

      {errorLine}
      <div className="bg-grey-900 text-[#F2F4F7] rounded-2xl px-4 py-3.5 flex items-center gap-4 flex-wrap" data-testid="bazar-preview">
        <span className="text-small font-bold uppercase tracking-[0.08em] text-sidebar-text">{t('bazar.r1.form.preview')}</span>
        <DateTile ymd={startsOn} lang={lang} dark size="sm" />
        <span className="flex flex-col min-w-0 flex-[1_1_200px]">
          <span className="text-body-lg font-bold truncate">{name.trim() || t('bazar.r1.form.untitled')}</span>
          <span className="text-small text-sidebar-text truncate">
            {[placeLabel, t('bazar.r1.form.dayCount', { count: n }), startsText].filter(Boolean).join(' · ')}
          </span>
        </span>
        <span className="flex items-center gap-1.5 flex-wrap">
          {methodsText && <span className="px-2.5 py-1 rounded bg-white/[0.08] text-small text-grey-100">{methodsText}</span>}
          <span className="px-2.5 py-1 rounded bg-white/[0.08] text-small text-grey-100">{t('bazar.r1.form.roleCount', { count: roles.length })}</span>
          <span className="px-2.5 py-1 rounded bg-white/[0.08] text-small text-grey-100" data-testid="bazar-preview-online">
            {t(`bazar.online.${online}.preview`)}
          </span>
        </span>
        <span className="flex items-center gap-2.5 ms-auto">
          <button type="button" onClick={() => navigate('/bazar')}
            className="h-11 px-[18px] rounded-[10px] border border-grey-700 text-[#F2F4F7] font-semibold hover:bg-white/[0.06]">
            {t('bazar.form.cancel')}
          </button>
          <button type="submit" disabled={saving}
            className="h-11 px-5 rounded-[10px] bg-trace-blue hover:bg-trace-blue-hover disabled:opacity-60 text-white font-semibold flex items-center gap-2">
            {createLabel}<ArrowRight size={16} aria-hidden className="rtl:-scale-x-100" />
          </button>
        </span>
      </div>
      {removeNote}
    </form>
  )
}
