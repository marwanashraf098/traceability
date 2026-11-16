import { FormEvent, useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Info } from 'lucide-react'
import { listLocations, type LocationRow } from '../../api'
import {
  BazarError, createBazarEvent, METHOD_ORDER, type PaymentMethod, type SellerRole,
} from '../../bazar/api'
import { Button, Checkbox, Input, Radio, cn } from '../../components/ui'

const ROLES: SellerRole[] = ['owner', 'manager', 'worker']

function cairoToday(): string {
  return new Date().toLocaleDateString('en-CA', { timeZone: 'Africa/Cairo' })
}

/** A location B1 accepts for a bazar: type 'event', never the warehouse, never a Shopify location, remove mode. */
function isEventLocation(l: LocationRow & { shopify_sync_mode?: string }) {
  return l.type === 'event' && !l.is_fulfillment && !l.shopify_location_id && l.shopify_sync_mode !== 'leave'
}

/** Bazar POS B6 — /bazar/new (mockup step 1). No "leave online stock" option: B1 runs every bazar in remove mode. */
export default function BazarNewEvent() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [where, setWhere] = useState<'new' | 'existing'>('new')
  const [locationName, setLocationName] = useState('')
  const [locationId, setLocationId] = useState('')
  const [locations, setLocations] = useState<LocationRow[]>([])
  const [startsOn, setStartsOn] = useState(cairoToday())
  const [endsOn, setEndsOn] = useState(cairoToday())
  const [methods, setMethods] = useState<PaymentMethod[]>(['cash', 'instapay', 'card'])
  const [roles, setRoles] = useState<SellerRole[]>(['owner', 'manager', 'worker'])
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    listLocations().then(ls => {
      const ev = ls.filter(isEventLocation)
      setLocations(ev)
      if (ev.length > 0) setLocationId(ev[0].id)
    }).catch(() => setLocations([]))
  }, [])

  const toggle = <T,>(list: T[], v: T, on: boolean) => (on ? [...list.filter(x => x !== v), v] : list.filter(x => x !== v))

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
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
      })
      navigate(`/bazar/${ev.id}`)
    } catch (err) {
      setError(err instanceof BazarError ? err.message_(i18n.language) : t('bazar.saveFailed'))
    } finally {
      setSaving(false)
    }
  }

  const label = 'text-small font-medium text-muted'
  const choice = (on: boolean) => cn('flex flex-col gap-2 p-3.5 rounded-xl border',
    on ? 'border-trace-blue bg-trace-blue/[0.04] border-[1.5px]' : 'border-line')

  return (
    <div className="flex flex-col gap-5 max-w-[1080px]" data-testid="bazar-new">
      <div className="flex flex-col gap-1">
        <Link to="/bazar" className="text-small text-muted hover:text-primary self-start">{t('bazar.breadcrumb')}</Link>
        <h1 className="text-h2 text-primary">{t('bazar.form.title')}</h1>
      </div>
      <div className="flex flex-wrap gap-5 items-start">
        <form onSubmit={submit} noValidate className="card p-5 flex-[999_1_520px] min-w-0 flex flex-col gap-5">
          <label className="flex flex-col gap-1.5">
            <span className={label}>{t('bazar.form.name')}</span>
            <Input value={name} maxLength={80} onChange={e => setName(e.target.value)} placeholder={t('bazar.form.namePlaceholder')} />
          </label>

          <fieldset className="flex flex-col gap-1.5">
            <legend className={cn(label, 'mb-1.5')}>{t('bazar.form.location')}</legend>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div className={choice(where === 'new')}>
                <Radio value="new" checked={where === 'new'} onChange={() => setWhere('new')} label={t('bazar.form.newLocation')} />
                {where === 'new' && (
                  <Input aria-label={t('bazar.form.locationName')} value={locationName} maxLength={80}
                         onChange={e => setLocationName(e.target.value)} placeholder={t('bazar.form.locationPlaceholder')} />
                )}
              </div>
              <div className={choice(where === 'existing')}>
                <Radio value="existing" checked={where === 'existing'} disabled={locations.length === 0}
                       onChange={() => setWhere('existing')} label={t('bazar.form.existingLocation')} />
                {where === 'existing' ? (
                  <select aria-label={t('bazar.form.locationName')} className="input" value={locationId}
                          onChange={e => setLocationId(e.target.value)}>
                    {locations.map(l => <option key={l.id} value={l.id}>{l.name}</option>)}
                  </select>
                ) : (
                  <span className="text-small text-muted">
                    {locations.length === 0 ? t('bazar.form.noEventLocations') : t('bazar.form.existingHint')}
                  </span>
                )}
              </div>
            </div>
          </fieldset>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <label className="flex flex-col gap-1.5">
              <span className={label}>{t('bazar.form.firstDay')}</span>
              <Input type="date" value={startsOn} onChange={e => { setStartsOn(e.target.value); if (endsOn < e.target.value) setEndsOn(e.target.value) }} />
            </label>
            <label className="flex flex-col gap-1.5">
              <span className={label}>{t('bazar.form.lastDay')}</span>
              <Input type="date" value={endsOn} min={startsOn} onChange={e => setEndsOn(e.target.value)} />
            </label>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-5">
            <fieldset>
              <legend className={cn(label, 'mb-2')}>{t('bazar.form.methods')}</legend>
              <div className="flex flex-wrap gap-x-5 gap-y-2">
                {METHOD_ORDER.map(m => (
                  <Checkbox key={m} checked={methods.includes(m)} label={t(`bazar.method.${m}`)}
                            onChange={on => setMethods(toggle(methods, m, on))} />
                ))}
              </div>
            </fieldset>
            <fieldset>
              <legend className={cn(label, 'mb-2')}>{t('bazar.form.sellers')}</legend>
              <div className="flex flex-wrap gap-x-5 gap-y-2">
                {ROLES.map(r => (
                  <Checkbox key={r} checked={roles.includes(r)} label={t(`bazar.role.${r}`)}
                            onChange={on => setRoles(toggle(roles, r, on))} />
                ))}
              </div>
            </fieldset>
          </div>

          <p className="flex items-start gap-2 text-small text-muted bg-charcoal border border-line rounded-xl px-3 py-2.5" data-testid="bazar-remove-note">
            <Info size={16} className="mt-px shrink-0" aria-hidden />{t('bazar.form.removeNote')}
          </p>

          {error && <p role="alert" className="text-body text-critical">{error}</p>}
          <div className="flex justify-end gap-2.5 border-t border-line pt-4">
            <Button variant="secondary" onClick={() => navigate('/bazar')}>{t('bazar.form.cancel')}</Button>
            <Button type="submit" loading={saving}>{t('bazar.form.create')}</Button>
          </div>
        </form>

        <aside className="card p-5 flex-[1_1_280px] min-w-0 flex flex-col gap-3">
          <h2 className="text-body-lg font-semibold text-primary">{t('bazar.how.title')}</h2>
          <ol className="list-decimal ps-5 flex flex-col gap-2.5 text-body text-primary">
            <li>{t('bazar.how.s1')}</li>
            <li>{t('bazar.how.s2')}</li>
            <li>{t('bazar.how.s3')}</li>
            <li>{t('bazar.how.s4')}</li>
          </ol>
          <p className="text-small text-muted bg-charcoal border border-line rounded-xl px-3 py-2.5">{t('bazar.how.soldNote')}</p>
        </aside>
      </div>
    </div>
  )
}
