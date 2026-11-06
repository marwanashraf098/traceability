import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { BostaReturnLocation, ReturnLocationsError, getReturnLocations } from '../../../api'
import { Button, cn } from '../../../components/ui'
import { RadioCards, SectionCard, SwitchRow } from './ui'

/**
 * Approval & pickup — how requests are approved (cards on the same autoApprove boolean), Bosta pickup booking and
 * where returns go back to. Booking keeps its preconditions exactly: it can be switched ON only with Bosta connected
 * and a return location SAVED (the backend enforces the same); the reason shows under it.
 */
export default function ApprovalSection({
  autoApprove, onAutoApprove, savedPickupBooking,
  bookingOn, onBooking, bookingDisabled, bookingNote, bookingError,
  savedLocationName, locationId, onLocation, locationError,
}: {
  autoApprove: boolean
  onAutoApprove: (v: boolean) => void
  /** The saved booking switch — the auto-approve description says what happens with it (as before). */
  savedPickupBooking: boolean
  bookingOn: boolean
  onBooking: (v: boolean) => void
  bookingDisabled: boolean
  bookingNote?: string
  bookingError?: string
  savedLocationName: string | null
  locationId: string | null
  onLocation: (id: string | null) => void
  locationError?: string
}) {
  const { t } = useTranslation()
  return (
    <SectionCard id="portal-approval" title={t('settings.portal.sections.approval.title')}
      description={t('settings.portal.sections.approval.description')}>
      <div className="px-6 py-5 space-y-3">
        <p id="portal-approval-mode" className="text-body font-semibold text-primary">{t('settings.portal.approval.title')}</p>
        <RadioCards labelledBy="portal-approval-mode" value={autoApprove ? 'auto' : 'manual'} testId="approval-mode"
          onChange={v => onAutoApprove(v === 'auto')}
          options={[
            { value: 'manual', title: t('settings.portal.approval.manual'), description: t('settings.portal.approval.manualDesc') },
            { value: 'auto', title: t('settings.portal.approval.auto'),
              description: t(savedPickupBooking ? 'settings.portal.approval.autoDescBooking' : 'settings.portal.approval.autoDesc') },
          ]} />
      </div>

      <SwitchRow
        title={t('settings.portal.booking.title')}
        description={t('settings.portal.booking.description')}
        checked={bookingOn}
        disabled={bookingDisabled}
        note={bookingNote}
        error={bookingError}
        onChange={onBooking}
      />

      <ReturnLocationRow savedName={savedLocationName} value={locationId} onChange={onLocation} error={locationError} />
    </SectionCard>
  )
}

/** "Returns go back to" — the tenant's Bosta pickup locations, loaded live. */
function ReturnLocationRow({
  savedName, value, onChange, error,
}: { savedName: string | null; value: string | null; onChange: (id: string | null) => void; error?: string }) {
  const { t } = useTranslation()
  const [locations, setLocations] = useState<BostaReturnLocation[] | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoadError(null)
    setLocations(null)
    try {
      setLocations(await getReturnLocations())
    } catch (e) {
      setLoadError(e instanceof ReturnLocationsError && e.code ? e.code : 'generic')
    }
  }, [])

  useEffect(() => { load() }, [load])

  // Nothing saved yet → preselect Bosta's default location.
  useEffect(() => {
    if (!locations || value) return
    const def = locations.find(l => l.isDefault)
    if (def) onChange(def.id)
  }, [locations, value, onChange])

  const label = (l: BostaReturnLocation) => {
    const name = l.cityName ? `${l.name} · ${l.cityName}` : l.name
    return l.isDefault ? t('settings.portal.returnLocation.default', { name }) : name
  }

  return (
    <div className="px-6 py-5 flex flex-wrap items-start justify-between gap-x-6 gap-y-3" data-testid="return-location-row">
      <div className="min-w-0 flex-1 basis-60">
        <label htmlFor="portal-return-location" className="text-body font-semibold text-primary block">
          {t('settings.portal.returnLocation.label')}
        </label>
        <p id="portal-return-location-help" className="text-small text-muted mt-0.5">{t('settings.portal.returnLocation.help')}</p>
      </div>
      <div className="flex-1 basis-72 max-w-md space-y-2">
        {loadError ? (
          <div className="space-y-2">
            <p className="text-small text-critical" role="alert" data-testid="return-location-error">
              {t(`settings.portal.returnLocation.errors.${loadError}`, {
                defaultValue: t('settings.portal.returnLocation.errors.generic'),
              })}
            </p>
            {savedName && (
              <p className="text-small text-muted"><bdi>{t('settings.portal.returnLocation.saved', { name: savedName })}</bdi></p>
            )}
            {loadError !== 'NO_BOSTA_ACCOUNT' && (
              <Button variant="outline" size="sm" onClick={load}>{t('settings.portal.returnLocation.retry')}</Button>
            )}
          </div>
        ) : locations === null ? (
          <p className="text-small text-muted" aria-busy="true">{t('settings.portal.returnLocation.loading')}</p>
        ) : locations.length === 0 ? (
          <p className="text-small text-muted">{t('settings.portal.returnLocation.empty')}</p>
        ) : (
          <select
            id="portal-return-location"
            className={cn('input w-full', error && 'border-critical')}
            aria-invalid={!!error}
            aria-describedby="portal-return-location-help"
            value={value ?? ''}
            onChange={e => onChange(e.target.value || null)}
          >
            {!value && <option value="" disabled>{t('settings.portal.returnLocation.label')}</option>}
            {locations.map(l => <option key={l.id} value={l.id}>{label(l)}</option>)}
          </select>
        )}
        {error && <p className="text-small text-critical" role="alert" data-testid="error-returnLocationId">{error}</p>}
      </div>
    </div>
  )
}
