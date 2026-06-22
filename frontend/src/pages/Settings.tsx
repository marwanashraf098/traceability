import { useState, useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { getTenantSettings, updateTenantSettings, getRoleFromToken, TenantSettings } from '../api'

// ── Segmented control ─────────────────────────────────────────────────────────

function SegmentedControl<T extends string>({
  value,
  options,
  onChange,
  disabled,
}: {
  value: T
  options: { value: T; label: string }[]
  onChange: (v: T) => void
  disabled?: boolean
}) {
  return (
    <div className="inline-flex rounded-lg border border-line overflow-hidden">
      {options.map(opt => (
        <button
          key={opt.value}
          type="button"
          disabled={disabled}
          onClick={() => onChange(opt.value)}
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

// ── Settings page ─────────────────────────────────────────────────────────────

export default function Settings() {
  const { t } = useTranslation()
  const role = getRoleFromToken()
  const isOwner = role === 'owner'

  const [loading,  setLoading]  = useState(true)
  const [saving,   setSaving]   = useState(false)
  const [error,    setError]    = useState('')
  const [saved,    setSaved]    = useState(false)

  const [name,            setName]            = useState('')
  const [pickupAddress,   setPickupAddress]   = useState('')
  const [labelSize,       setLabelSize]       = useState<'40x25' | '50x25'>('40x25')
  const [defaultLanguage, setDefaultLanguage] = useState<'ar' | 'en'>('ar')
  const [timezone,        setTimezone]        = useState('Africa/Cairo')

  useEffect(() => {
    async function load() {
      try {
        const s = await getTenantSettings()
        setName(s.name ?? '')
        setPickupAddress(s.pickupAddress ?? '')
        setLabelSize(s.labelSize)
        setDefaultLanguage(s.defaultLanguage)
        setTimezone(s.timezone)
      } catch {
        setError(t('common.error'))
      } finally {
        setLoading(false)
      }
    }
    load()
  }, [t])

  async function handleSave(e: React.FormEvent) {
    e.preventDefault()
    setSaving(true)
    setError('')
    setSaved(false)
    try {
      const payload: Partial<TenantSettings> = {}
      if (name.trim())          payload.name            = name.trim()
      if (pickupAddress.trim()) payload.pickupAddress   = pickupAddress.trim()
      payload.labelSize       = labelSize
      payload.defaultLanguage = defaultLanguage
      if (timezone.trim())      payload.timezone        = timezone.trim()

      await updateTenantSettings(payload)
      setSaved(true)
      setTimeout(() => setSaved(false), 4000)
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : ''
      if (msg.includes('400')) {
        setError(t('settings.errors.generic'))
      } else {
        setError(t('settings.errors.generic'))
      }
    } finally {
      setSaving(false)
    }
  }

  const labelOptions: { value: '40x25' | '50x25'; label: string }[] = [
    { value: '40x25', label: '40×25 mm' },
    { value: '50x25', label: '50×25 mm' },
  ]

  const langOptions: { value: 'ar' | 'en'; label: string }[] = [
    { value: 'ar', label: t('settings.roles.ar') },
    { value: 'en', label: t('settings.roles.en') },
  ]

  return (
    <div className="max-w-xl mx-auto space-y-6">
      <div>
        <h1 className="text-h1 text-primary">{t('settings.title')}</h1>
        <p className="text-small text-muted mt-1">{t('settings.subtitle')}</p>
      </div>

      {loading ? (
        <div className="flex items-center justify-center py-16">
          <svg className="animate-spin w-6 h-6 text-brand" fill="none" viewBox="0 0 24 24">
            <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/>
            <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"/>
          </svg>
        </div>
      ) : (
        <div className="card p-6 space-y-6">
          {!isOwner && (
            <div className="text-small text-muted bg-elevated border border-line rounded px-3 py-2">
              {t('settings.ownerOnly')}
            </div>
          )}

          {error && (
            <div role="alert" className="text-small text-danger bg-danger/10 border border-danger/25 rounded px-3 py-2">
              {error}
            </div>
          )}

          {saved && (
            <div role="status" className="text-small text-success bg-success/10 border border-success/25 rounded px-3 py-2">
              {t('settings.saved')}
            </div>
          )}

          <form onSubmit={handleSave} className="space-y-5">
            {/* Business name */}
            <div>
              <label className="block text-small text-muted mb-1.5" htmlFor="bizName">
                {t('settings.businessName')}
              </label>
              <input
                id="bizName"
                type="text"
                value={name}
                onChange={e => setName(e.target.value)}
                className="input"
                disabled={!isOwner || saving}
              />
            </div>

            {/* Pickup address */}
            <div>
              <label className="block text-small text-muted mb-1.5" htmlFor="pickupAddr">
                {t('settings.pickupAddress')}
              </label>
              <input
                id="pickupAddr"
                type="text"
                value={pickupAddress}
                onChange={e => setPickupAddress(e.target.value)}
                className="input"
                disabled={!isOwner || saving}
              />
              <p className="text-caption text-muted mt-1">{t('settings.pickupAddressHint')}</p>
            </div>

            {/* Label size */}
            <div>
              <span className="block text-small text-muted mb-2">{t('settings.labelSize')}</span>
              <SegmentedControl
                value={labelSize}
                options={labelOptions}
                onChange={setLabelSize}
                disabled={!isOwner || saving}
              />
            </div>

            {/* Default language */}
            <div>
              <span className="block text-small text-muted mb-2">{t('settings.defaultLanguage')}</span>
              <SegmentedControl
                value={defaultLanguage}
                options={langOptions}
                onChange={setDefaultLanguage}
                disabled={!isOwner || saving}
              />
              <p className="text-caption text-muted mt-1.5">{t('settings.langNote')}</p>
            </div>

            {/* Timezone */}
            <div>
              <label className="block text-small text-muted mb-1.5" htmlFor="timezone">
                {t('settings.timezone')}
              </label>
              <input
                id="timezone"
                type="text"
                value={timezone}
                onChange={e => setTimezone(e.target.value)}
                className="input"
                disabled={!isOwner || saving}
                placeholder="Africa/Cairo"
                dir="ltr"
              />
              <p className="text-caption text-muted mt-1">{t('settings.timezoneHint')}</p>
            </div>

            {isOwner && (
              <button
                type="submit"
                disabled={saving}
                className="btn btn-brand"
              >
                {saving ? t('settings.saving') : t('settings.save')}
              </button>
            )}
          </form>
        </div>
      )}
    </div>
  )
}
