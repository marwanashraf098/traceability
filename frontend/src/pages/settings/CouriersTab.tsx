import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Eye, Info, ScanLine } from 'lucide-react'
import {
  getCourierSettings, updateCourierSettings, getCatchUpPreview, testWaybill,
  CourierSettings, CatchUpPreview, TestWaybillResult,
} from '../../api'
import { Skeleton, Toggle, cn } from '../../components/ui'

// Non-Bosta couriers G2 — Settings › Couriers.
// "I ship with other couriers" + default carrier: owner edits (PUT is owner-only server-side, audit-logged);
// managers see them read-only. "Test a waybill": owner + manager, read-only on the server — nothing is stored.

export default function CouriersTab({ isOwner }: { isOwner: boolean }) {
  const { t } = useTranslation()
  const [saved, setSaved] = useState<CourierSettings | null>(null)
  const [enabled, setEnabled] = useState(false)
  const [carrier, setCarrier] = useState('')
  const [preview, setPreview] = useState<CatchUpPreview | null>(null)
  // Switch-on catch-up: off by default ("start from now"); opt in to the last N days (1–7, default 3).
  const [includeRecent, setIncludeRecent] = useState(false)
  const [days, setDays] = useState(3)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [result, setResult] = useState<'saved' | 'error' | null>(null)

  useEffect(() => {
    let cancelled = false
    getCourierSettings()
      .then(s => {
        if (cancelled || !s) return
        setSaved(s)
        setEnabled(s.enabled)
        setCarrier(s.defaultCarrier ?? '')
      })
      .catch(() => { if (!cancelled) setResult('error') })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [])

  const switchingOn = !!saved && !saved.enabled && enabled
  const dirty = !!saved && (enabled !== saved.enabled || carrier.trim() !== (saved.defaultCarrier ?? ''))

  // The dry-run count for the chosen window, shown before the owner saves.
  useEffect(() => {
    if (!switchingOn || !includeRecent) { setPreview(null); return }
    let cancelled = false
    getCatchUpPreview(days).then(p => { if (!cancelled) setPreview(p) }).catch(() => { if (!cancelled) setPreview(null) })
    return () => { cancelled = true }
  }, [switchingOn, includeRecent, days])

  async function save() {
    if (saving || !dirty) return
    setSaving(true)
    setResult(null)
    try {
      const s = await updateCourierSettings({ enabled, defaultCarrier: carrier.trim() || null,
        catchUpDays: switchingOn && includeRecent ? days : null })
      setSaved(s)
      setEnabled(s.enabled)
      setCarrier(s.defaultCarrier ?? '')
      setIncludeRecent(false)
      setResult('saved')
    } catch {
      setResult('error')
    } finally {
      setSaving(false)
    }
  }

  if (loading) return <Skeleton className="h-64 rounded-2xl" />

  return (
    <div className="space-y-8 max-w-2xl" data-testid="couriers-settings">
      {!isOwner && (
        <div className="flex items-center gap-2 text-small text-muted bg-elevated border border-line rounded px-3 py-2">
          <Eye size={14} strokeWidth={1.75} className="flex-shrink-0" />
          {t('settings.readOnlyBanner')}
        </div>
      )}

      <section className="space-y-4">
        <div>
          <div className="flex items-center gap-2 flex-wrap">
            <h2 className="text-h3 text-primary">{t('settings.couriers.title')}</h2>
            <span className="text-caption font-semibold text-muted bg-elevated border border-line rounded-full px-2 py-0.5">
              {t('settings.couriers.ownersOnly')}
            </span>
          </div>
          <p className="text-small text-muted mt-1">{t('settings.couriers.subtitle')}</p>
        </div>

        <div className="flex items-start justify-between gap-4 rounded-xl border border-line p-4">
          <div className="min-w-0">
            <p className="text-body font-semibold text-primary">{t('settings.couriers.switchTitle')}</p>
            <p className="text-small text-muted mt-0.5">{t('settings.couriers.switchLine')}</p>
          </div>
          <span data-testid="couriers-switch">
            <Toggle checked={enabled} onChange={setEnabled} disabled={!isOwner}
                    ariaLabel={t('settings.couriers.switchTitle')} />
          </span>
        </div>

        {switchingOn && isOwner && (
          <div className="rounded-xl border border-line p-4 space-y-2" data-testid="couriers-catchup">
            <p className="text-small text-primary">{t('settings.couriers.fromNow')}</p>
            <label className="flex flex-wrap items-center gap-2 text-small text-primary">
              <input type="checkbox" checked={includeRecent} onChange={e => setIncludeRecent(e.target.checked)}
                     data-testid="couriers-include-recent" />
              <span>{t('settings.couriers.includeRecentBefore')}</span>
              <select className="input w-auto py-1" value={days} disabled={!includeRecent}
                      onChange={e => setDays(Number(e.target.value))} aria-label={t('settings.couriers.daysLabel')}
                      data-testid="couriers-days">
                {[1, 2, 3, 4, 5, 6, 7].map(n => <option key={n} value={n}>{n}</option>)}
              </select>
              <span>{t('settings.couriers.includeRecentAfter')}</span>
            </label>
            {includeRecent && preview && preview.days === days && (
              <p className="flex items-start gap-2 text-small text-muted" data-testid="couriers-catchup-count">
                <Info size={14} strokeWidth={1.75} className="flex-shrink-0 mt-0.5" />
                {t('settings.couriers.catchUp', { count: preview.wouldCreate, days })}
              </p>
            )}
          </div>
        )}
        {saved?.enabled && !enabled && (
          <p className="flex items-start gap-2 text-small text-muted" data-testid="couriers-off-note">
            <Info size={14} strokeWidth={1.75} className="flex-shrink-0 mt-0.5" />
            {t('settings.couriers.offNote')}
          </p>
        )}

        <div className="space-y-1.5">
          <label htmlFor="couriers-default-carrier" className="block text-small font-medium text-primary">
            {t('settings.couriers.defaultCarrier')}
          </label>
          <input
            id="couriers-default-carrier"
            className="input w-full sm:max-w-xs"
            list="couriers-carrier-suggestions"
            value={carrier}
            maxLength={60}
            disabled={!isOwner}
            onChange={e => setCarrier(e.target.value)}
            placeholder={t('settings.couriers.defaultCarrierPlaceholder')}
          />
          <datalist id="couriers-carrier-suggestions">
            {(saved?.suggestions ?? []).map(s => <option key={s} value={s} />)}
          </datalist>
          <p className="text-caption text-muted">{t('settings.couriers.defaultCarrierHint')}</p>
        </div>

        {saved?.bostaConnected && (
          <p className="flex items-start gap-2 text-small text-muted">
            <Info size={14} strokeWidth={1.75} className="flex-shrink-0 mt-0.5" />
            {t('settings.couriers.bostaNote')}
          </p>
        )}

        {isOwner && (
          <div className="flex flex-wrap items-center justify-end gap-3 pt-4 border-t border-line">
            {result === 'saved' && <span role="status" className="text-small text-success">{t('settings.saved')}</span>}
            {result === 'error' && <span role="alert" className="text-small text-danger">{t('settings.couriers.error')}</span>}
            <button type="button" className="btn btn-outline" disabled={saving || !dirty}
                    onClick={() => { if (saved) { setEnabled(saved.enabled); setCarrier(saved.defaultCarrier ?? '') } }}>
              {t('common.cancel')}
            </button>
            <button type="button" className="btn btn-brand" disabled={saving || !dirty} onClick={save}
                    data-testid="couriers-save">
              {saving ? t('settings.saving') : t('settings.save')}
            </button>
          </div>
        )}
      </section>

      <TestAWaybill />
    </div>
  )
}

/** "Test a waybill" — scan with a hardware scanner (or type), see what Traced would do with it. Read-only. */
function TestAWaybill() {
  const { t, i18n } = useTranslation()
  const ar = i18n.language?.startsWith('ar')
  const inputRef = useRef<HTMLInputElement>(null)
  const [value, setValue] = useState('')
  const [busy, setBusy] = useState(false)
  const [res, setRes] = useState<TestWaybillResult | null>(null)
  const [failed, setFailed] = useState(false)

  async function run() {
    const code = value.trim()
    if (!code || busy) return
    setBusy(true)
    setFailed(false)
    try {
      setRes(await testWaybill(code))
    } catch {
      setRes(null)
      setFailed(true)
    } finally {
      setBusy(false)
      setValue('')
      inputRef.current?.focus()
    }
  }

  const good = res?.verdict === 'OPENS_ORDER'

  return (
    <section className="space-y-4 pt-6 border-t border-line" data-testid="test-waybill">
      <div>
        <h2 className="text-h3 text-primary">{t('settings.couriers.test.title')}</h2>
        <p className="text-small text-muted mt-1">{t('settings.couriers.test.subtitle')}</p>
      </div>

      <form className="flex flex-col sm:flex-row gap-2" onSubmit={e => { e.preventDefault(); run() }}>
        <div className="relative flex-1 min-w-0">
          <ScanLine size={16} strokeWidth={2} className="absolute start-3 top-1/2 -translate-y-1/2 text-muted pointer-events-none" />
          <input
            ref={inputRef}
            dir="ltr"
            className="input w-full ps-9 font-mono"
            value={value}
            onChange={e => setValue(e.target.value)}
            placeholder={t('settings.couriers.test.placeholder')}
            aria-label={t('settings.couriers.test.placeholder')}
            autoComplete="off"
            data-testid="test-waybill-input"
          />
        </div>
        <button type="submit" className="btn btn-brand" disabled={busy || !value.trim()}>
          {busy ? t('common.loading') : t('settings.couriers.test.run')}
        </button>
      </form>

      {failed && <p role="alert" className="text-small text-danger">{t('settings.couriers.test.error')}</p>}

      {res && (
        <div className="rounded-xl border border-line overflow-hidden" data-testid="test-waybill-result">
          <p className={cn('px-4 py-3 text-body font-semibold', good ? 'bg-success/10 text-success' : 'bg-warning/10 text-primary')}
             data-testid="test-waybill-verdict" data-verdict={res.verdict}>
            {ar ? res.verdictAr : res.verdictEn}
          </p>
          <dl className="grid grid-cols-1 sm:grid-cols-[max-content_1fr] gap-x-4 gap-y-2 px-4 py-3 text-small">
            <dt className="text-muted">{t('settings.couriers.test.raw')}</dt>
            <dd className="font-mono break-all text-primary" dir="ltr">{res.raw}</dd>
            <dt className="text-muted">{t('settings.couriers.test.bostaKey')}</dt>
            <dd className="font-mono break-all text-primary" dir="ltr">{res.bostaKey ?? '—'}</dd>
            <dt className="text-muted">{t('settings.couriers.test.genericKey')}</dt>
            <dd className="font-mono break-all text-primary" dir="ltr">{res.genericKey ?? '—'}</dd>
            <dt className="text-muted">{t('settings.couriers.test.format')}</dt>
            <dd className="text-primary">{t('settings.couriers.test.formatUnknown')}</dd>
            <dt className="text-muted">{t('settings.couriers.test.waybillMode')}</dt>
            <dd className="text-primary">
              {res.waybillModeCode === 'OPEN' ? t('settings.couriers.test.waybillModeOpen')
                : (ar ? res.waybillModeAr : res.waybillModeEn) ?? res.waybillModeCode}
            </dd>
          </dl>
          {(res.shipments.length > 0 || res.fulfillments.length > 0) && (
            <div className="border-t border-line px-4 py-3 space-y-2 text-small">
              {res.shipments.map((s, i) => (
                <p key={'s' + i} className="text-primary">
                  {t('settings.couriers.test.shipmentRow', { number: s.orderNumber, carrier: s.carrier ?? '—', state: s.state })}
                </p>
              ))}
              {res.fulfillments.map((f, i) => (
                <p key={'f' + i} className="text-muted">
                  {t('settings.couriers.test.fulfillmentRow', { number: f.orderNumber, carrier: f.carrier ?? '—', tracking: f.trackingNumber })}
                </p>
              ))}
            </div>
          )}
        </div>
      )}

      <p className="flex items-start gap-2 text-small text-muted" data-testid="test-waybill-camera-hint">
        <Info size={14} strokeWidth={1.75} className="flex-shrink-0 mt-0.5" />
        {t('settings.couriers.test.cameraHint')}
      </p>
    </section>
  )
}
