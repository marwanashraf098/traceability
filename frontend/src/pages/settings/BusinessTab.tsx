import { useState, useEffect } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Eye } from 'lucide-react'
import { getTenantSettings, updateTenantSettings, getConnections, bostaUpdateSettings, TenantSettings } from '../../api'

// ── Segmented control ─────────────────────────────────────────────────────────

function SegmentedControl<T extends string>({
  value,
  options,
  onChange,
  disabled,
  testId,
}: {
  value: T
  options: { value: T; label: string }[]
  onChange: (v: T) => void
  disabled?: boolean
  testId?: string
}) {
  return (
    <div className="inline-flex rounded-lg border border-line overflow-hidden">
      {options.map(opt => (
        <button
          key={opt.value}
          type="button"
          disabled={disabled}
          onClick={() => onChange(opt.value)}
          data-testid={testId ? `${testId}-${opt.value}` : undefined}
          className={[
            'px-4 py-1.5 text-small font-medium transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-brand/60',
            value === opt.value
              ? 'bg-brand text-white'
              : 'bg-panel text-muted hover:text-primary disabled:hover:text-muted',
          ].join(' ')}
        >
          {opt.label}
        </button>
      ))}
    </div>
  )
}

// ── Settings-row layout primitives ────────────────────────────────────────────
// Appearance only — label+hint on the left (~1/3), control on the right (~2/3),
// row fills the section's full width. The control itself gets a capped max-width
// (see className="input max-w-md" below) so a lone text input doesn't stretch
// edge-to-edge on a wide screen. Row order is plain DOM order (label div first,
// control div second) so it mirrors automatically under dir="rtl" — no ps-/pe-
// direction hacks needed here, same as the rest of the app's flex layouts.

function SettingsSection({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="card p-6">
      <h2 className="text-h3 text-primary mb-4">{title}</h2>
      <div className="divide-y divide-line">{children}</div>
    </div>
  )
}

function SettingsRow({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col sm:flex-row sm:items-start gap-2 sm:gap-6 py-4 first:pt-0 last:pb-0">
      <div className="sm:w-1/3 flex-shrink-0">
        <div className="text-small font-medium text-primary">{label}</div>
        {hint && <p className="text-caption text-muted mt-0.5">{hint}</p>}
      </div>
      <div className="sm:w-2/3 min-w-0">{children}</div>
    </div>
  )
}

// ── Tenant field snapshot — the diff baseline for Save fan-out ────────────────

interface TenantFields {
  name: string
  pickupAddress: string
  labelSize: '40x25' | '50x25'
  defaultLanguage: 'ar' | 'en'
  timezone: string
}

interface AwbFields {
  awbFormat: 'A4' | 'A6'
  awbLang: 'ar' | 'en'
}

// ── Business tab ───────────────────────────────────────────────────────────────
//
// Save fans out into up to two independent calls — changed tenant fields to
// PUT /tenant/settings, changed AWB fields (format + language) to PUT
// /bosta/settings — each fired only if its own section actually changed, and
// awb_lang/awbFormat are NEVER included in the tenant payload. Promise.allSettled
// so one section failing never hides or blocks the other section's success;
// each section shows its own Saved/error state.

export default function BusinessTab({ isOwner }: { isOwner: boolean }) {
  const { t } = useTranslation()

  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')

  const [tenantSnapshot, setTenantSnapshot] = useState<TenantFields | null>(null)
  const [awbSnapshot,    setAwbSnapshot]    = useState<AwbFields | null>(null)

  const [name,            setName]            = useState('')
  const [pickupAddress,   setPickupAddress]   = useState('')
  const [labelSize,       setLabelSize]       = useState<'40x25' | '50x25'>('40x25')
  const [defaultLanguage, setDefaultLanguage] = useState<'ar' | 'en'>('ar')
  const [timezone,        setTimezone]        = useState('Africa/Cairo')
  const [consentSettings, setConsentSettings] = useState<Pick<TenantSettings, 'consentPrivacyVersion' | 'consentTermsVersion' | 'consentAcceptedAt'> | null>(null)

  // Bosta AWB settings — stored in courier_accounts, loaded separately
  const [bostaConnected, setBostaConnected] = useState(false)
  const [awbFormat,      setAwbFormat]      = useState<'A4' | 'A6'>('A4')
  const [awbLang,        setAwbLang]        = useState<'ar' | 'en'>('ar')

  const [saving,        setSaving]        = useState(false)
  const [tenantResult,  setTenantResult]  = useState<'idle' | 'saved' | 'error'>('idle')
  const [awbResult,     setAwbResult]     = useState<'idle' | 'saved' | 'error'>('idle')

  useEffect(() => {
    async function load() {
      try {
        const [s, conn] = await Promise.all([getTenantSettings(), getConnections()])
        const tenantFields: TenantFields = {
          name: s.name ?? '',
          pickupAddress: s.pickupAddress ?? '',
          labelSize: s.labelSize,
          defaultLanguage: s.defaultLanguage,
          timezone: s.timezone,
        }
        setName(tenantFields.name)
        setPickupAddress(tenantFields.pickupAddress)
        setLabelSize(tenantFields.labelSize)
        setDefaultLanguage(tenantFields.defaultLanguage)
        setTimezone(tenantFields.timezone)
        setTenantSnapshot(tenantFields)
        setConsentSettings({
          consentPrivacyVersion: s.consentPrivacyVersion,
          consentTermsVersion:   s.consentTermsVersion,
          consentAcceptedAt:     s.consentAcceptedAt,
        })

        setBostaConnected(conn.bosta.connected)
        const awbFields: AwbFields = {
          awbFormat: conn.bosta.awbFormat ?? 'A4',
          awbLang:   conn.bosta.awbLang === 'en' ? 'en' : 'ar',
        }
        setAwbFormat(awbFields.awbFormat)
        setAwbLang(awbFields.awbLang)
        setAwbSnapshot(awbFields)
      } catch {
        setLoadError(t('common.error'))
      } finally {
        setLoading(false)
      }
    }
    load()
  }, [t])

  async function handleSave(e: React.FormEvent) {
    e.preventDefault()
    if (!tenantSnapshot || !awbSnapshot) return

    const tenantPayload: Partial<TenantSettings> = {}
    if (name.trim() !== tenantSnapshot.name)                     tenantPayload.name            = name.trim()
    if (pickupAddress.trim() !== tenantSnapshot.pickupAddress)   tenantPayload.pickupAddress   = pickupAddress.trim()
    if (labelSize !== tenantSnapshot.labelSize)                  tenantPayload.labelSize       = labelSize
    if (defaultLanguage !== tenantSnapshot.defaultLanguage)      tenantPayload.defaultLanguage = defaultLanguage
    if (timezone.trim() !== tenantSnapshot.timezone)             tenantPayload.timezone        = timezone.trim()

    // awb_lang / awbFormat are NEVER part of the tenant payload above — they go
    // to PUT /bosta/settings only, and only if the bosta section actually changed.
    const awbPayload: { awbFormat?: 'A4' | 'A6'; awbLang?: string } = {}
    if (bostaConnected && awbFormat !== awbSnapshot.awbFormat) awbPayload.awbFormat = awbFormat
    if (bostaConnected && awbLang   !== awbSnapshot.awbLang)   awbPayload.awbLang   = awbLang

    const tenantChanged = Object.keys(tenantPayload).length > 0
    const awbChanged    = Object.keys(awbPayload).length > 0
    if (!tenantChanged && !awbChanged) return

    setSaving(true)
    setTenantResult('idle')
    setAwbResult('idle')

    const [tenantOutcome, awbOutcome] = await Promise.allSettled([
      tenantChanged ? updateTenantSettings(tenantPayload) : Promise.resolve(undefined),
      awbChanged    ? bostaUpdateSettings(awbPayload)      : Promise.resolve(undefined),
    ])

    if (tenantChanged) {
      if (tenantOutcome.status === 'fulfilled') {
        setTenantSnapshot({
          name: name.trim(),
          pickupAddress: pickupAddress.trim(),
          labelSize,
          defaultLanguage,
          timezone: timezone.trim(),
        })
        setTenantResult('saved')
      } else {
        setTenantResult('error')
      }
    }
    if (awbChanged) {
      if (awbOutcome.status === 'fulfilled') {
        setAwbSnapshot({ ...awbSnapshot, ...awbPayload } as AwbFields)
        setAwbResult('saved')
      } else {
        setAwbResult('error')
      }
    }
    setSaving(false)
  }

  const hasChanges = !!tenantSnapshot && !!awbSnapshot && (
    name.trim() !== tenantSnapshot.name ||
    pickupAddress.trim() !== tenantSnapshot.pickupAddress ||
    labelSize !== tenantSnapshot.labelSize ||
    defaultLanguage !== tenantSnapshot.defaultLanguage ||
    timezone.trim() !== tenantSnapshot.timezone ||
    (bostaConnected && awbFormat !== awbSnapshot.awbFormat) ||
    (bostaConnected && awbLang !== awbSnapshot.awbLang)
  )

  const labelOptions: { value: '40x25' | '50x25'; label: string }[] = [
    { value: '40x25', label: '40×25 mm' },
    { value: '50x25', label: '50×25 mm' },
  ]

  const langOptions: { value: 'ar' | 'en'; label: string }[] = [
    { value: 'ar', label: t('settings.roles.ar') },
    { value: 'en', label: t('settings.roles.en') },
  ]

  const awbFormatOptions: { value: 'A4' | 'A6'; label: string }[] = [
    { value: 'A4', label: 'A4' },
    { value: 'A6', label: 'A6' },
  ]

  const awbLangOptions: { value: 'ar' | 'en'; label: string }[] = [
    { value: 'ar', label: t('settings.roles.ar') },
    { value: 'en', label: t('settings.roles.en') },
  ]

  const fieldsDisabled = !isOwner || saving

  return (
    <div className="space-y-6">
      {loading ? (
        <div className="flex items-center justify-center py-16">
          <svg className="animate-spin w-6 h-6 text-brand" fill="none" viewBox="0 0 24 24">
            <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/>
            <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"/>
          </svg>
        </div>
      ) : (
        <form onSubmit={handleSave} className="space-y-6">
          {!isOwner && (
            <div className="flex items-center gap-2 text-small text-muted bg-elevated border border-line rounded px-3 py-2">
              <Eye size={14} strokeWidth={1.75} className="flex-shrink-0" />
              {t('settings.readOnlyBanner')}
            </div>
          )}

          {loadError && (
            <div role="alert" className="text-small text-danger bg-danger/10 border border-danger/25 rounded px-3 py-2">
              {loadError}
            </div>
          )}

          <SettingsSection title={t('settings.sections.businessDetails')}>
            <SettingsRow label={t('settings.businessName')}>
              <label className="sr-only" htmlFor="bizName">{t('settings.businessName')}</label>
              <input
                id="bizName"
                type="text"
                value={name}
                onChange={e => setName(e.target.value)}
                className="input max-w-md"
                disabled={fieldsDisabled}
              />
            </SettingsRow>
            <SettingsRow label={t('settings.pickupAddress')} hint={t('settings.pickupAddressHint')}>
              <label className="sr-only" htmlFor="pickupAddr">{t('settings.pickupAddress')}</label>
              <input
                id="pickupAddr"
                type="text"
                value={pickupAddress}
                onChange={e => setPickupAddress(e.target.value)}
                className="input max-w-md"
                disabled={fieldsDisabled}
              />
            </SettingsRow>
          </SettingsSection>

          <SettingsSection title={t('settings.sections.labelsPrinting')}>
            <SettingsRow label={t('settings.labelSize')}>
              <SegmentedControl
                value={labelSize}
                options={labelOptions}
                onChange={setLabelSize}
                disabled={fieldsDisabled}
                testId="labelSize"
              />
            </SettingsRow>

            <SettingsRow label={t('settings.awbFormat')}>
              <SegmentedControl
                value={awbFormat}
                options={awbFormatOptions}
                onChange={setAwbFormat}
                disabled={fieldsDisabled || !bostaConnected}
                testId="awbFormat"
              />
              <p className="text-caption text-muted mt-1.5">
                {awbFormat === 'A6'
                  ? t('settings.awbFormatA6')
                  : t('settings.awbFormatA4')}
              </p>
              {!bostaConnected && (
                <p className="text-caption text-muted mt-0.5 opacity-60">{t('settings.awbFormatNotConnected')}</p>
              )}
            </SettingsRow>

            <SettingsRow label={t('settings.awbLang')} hint={t('settings.awbLangHint')}>
              <SegmentedControl
                value={awbLang}
                options={awbLangOptions}
                onChange={setAwbLang}
                disabled={fieldsDisabled || !bostaConnected}
                testId="awbLang"
              />
              {awbResult === 'saved' && (
                <p role="status" data-testid="awb-result-saved" className="text-caption text-success mt-1.5">{t('settings.saved')}</p>
              )}
              {awbResult === 'error' && (
                <p role="alert" data-testid="awb-result-error" className="text-caption text-danger mt-1.5">{t('settings.errors.awbSection')}</p>
              )}
            </SettingsRow>
          </SettingsSection>

          <SettingsSection title={t('settings.sections.localization')}>
            <SettingsRow label={t('settings.defaultLanguage')} hint={t('settings.langNote')}>
              <SegmentedControl
                value={defaultLanguage}
                options={langOptions}
                onChange={setDefaultLanguage}
                disabled={fieldsDisabled}
                testId="defaultLanguage"
              />
            </SettingsRow>

            <SettingsRow label={t('settings.timezone')} hint={t('settings.timezoneHint')}>
              <label className="sr-only" htmlFor="timezone">{t('settings.timezone')}</label>
              <input
                id="timezone"
                type="text"
                value={timezone}
                onChange={e => setTimezone(e.target.value)}
                className="input max-w-md"
                disabled={fieldsDisabled}
                placeholder="Africa/Cairo"
                dir="ltr"
              />
            </SettingsRow>
          </SettingsSection>

          {isOwner && (
            <div className="flex items-center justify-end gap-3 pt-4 border-t border-line">
              {tenantResult === 'saved' && (
                <span role="status" data-testid="business-result-saved" className="text-small text-success">{t('settings.saved')}</span>
              )}
              {tenantResult === 'error' && (
                <span role="alert" data-testid="business-result-error" className="text-small text-danger">{t('settings.errors.businessSection')}</span>
              )}
              <button
                type="submit"
                disabled={saving || !hasChanges}
                data-testid="business-save"
                className="btn btn-brand"
              >
                {saving ? t('settings.saving') : t('settings.save')}
              </button>
            </div>
          )}
        </form>
      )}

      {/* Legal agreement — read-only */}
      <div className="card p-6 space-y-4">
        <h2 className="text-h3 text-primary">{t('settings.consent.title')}</h2>
        {!consentSettings?.consentAcceptedAt ? (
          <p className="text-small text-muted">{t('settings.consent.none')}</p>
        ) : (
          <dl className="space-y-3">
            <div className="flex flex-col sm:flex-row sm:items-center gap-1">
              <dt className="text-small text-muted sm:w-48 shrink-0">{t('settings.consent.privacyVersion')}</dt>
              <dd className="text-small text-primary font-medium flex items-center gap-2">
                {consentSettings.consentPrivacyVersion}
                <Link to="/privacy" target="_blank" rel="noopener noreferrer"
                  className="text-brand hover:text-brand-hover text-caption underline underline-offset-2 transition-colors">
                  {t('common.view')}
                </Link>
              </dd>
            </div>
            <div className="flex flex-col sm:flex-row sm:items-center gap-1">
              <dt className="text-small text-muted sm:w-48 shrink-0">{t('settings.consent.termsVersion')}</dt>
              <dd className="text-small text-primary font-medium flex items-center gap-2">
                {consentSettings.consentTermsVersion}
                <Link to="/terms" target="_blank" rel="noopener noreferrer"
                  className="text-brand hover:text-brand-hover text-caption underline underline-offset-2 transition-colors">
                  {t('common.view')}
                </Link>
              </dd>
            </div>
            <div className="flex flex-col sm:flex-row sm:items-center gap-1">
              <dt className="text-small text-muted sm:w-48 shrink-0">{t('settings.consent.acceptedAt')}</dt>
              <dd className="text-small text-primary">
                {new Date(consentSettings.consentAcceptedAt!).toLocaleString()}
              </dd>
            </div>
          </dl>
        )}
      </div>
    </div>
  )
}
