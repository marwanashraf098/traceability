import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Eye, Info } from 'lucide-react'
import { getTenantSettings, updateTenantSettings, PickPackMode } from '../../api'
import { Radio, Skeleton, cn } from '../../components/ui'

// Pick & Pack S3 — Settings › Pick & Pack (design/pick-pack-waybill-mockup/Settings.html).
// Owners edit; managers see it read-only (PUT /tenant/settings is owner-only server-side);
// workers never reach Settings.

export default function PickPackTab({ isOwner }: { isOwner: boolean }) {
  const { t } = useTranslation()
  const [saved, setSaved] = useState<PickPackMode | null>(null)
  const [mode, setMode] = useState<PickPackMode>('order_queue')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [result, setResult] = useState<'saved' | 'error' | null>(null)

  useEffect(() => {
    let cancelled = false
    getTenantSettings()
      .then(s => {
        if (cancelled) return
        const m = s?.pickPackMode ?? 'order_queue'
        setSaved(m)
        setMode(m)
      })
      .catch(() => { if (!cancelled) setResult('error') })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [])

  async function save() {
    if (saving || mode === saved) return
    setSaving(true)
    setResult(null)
    try {
      await updateTenantSettings({ pickPackMode: mode })
      setSaved(mode)
      setResult('saved')
    } catch {
      setResult('error')
    } finally {
      setSaving(false)
    }
  }

  if (loading) return <Skeleton className="h-64 rounded-2xl" />

  const option = (value: PickPackMode, title: string, line1: string, line2: string) => (
    <label
      className={cn(
        'flex items-start gap-3 rounded-xl border p-4 transition-colors',
        isOwner ? 'cursor-pointer' : 'cursor-default',
        mode === value ? 'border-trace-blue bg-trace-blue/[0.06]' : 'border-line',
        isOwner && mode !== value && 'hover:bg-elevated'
      )}
      data-testid={`pickpack-mode-${value}`}
    >
      <span className="pt-0.5">
        <Radio value={value} checked={mode === value} disabled={!isOwner} onChange={v => setMode(v as PickPackMode)} />
      </span>
      <span className="flex-1 min-w-0">
        <span className="block text-body font-semibold text-primary">{title}</span>
        <span className="block text-small text-muted mt-0.5">{line1}</span>
        <span className="block text-small text-muted">{line2}</span>
      </span>
    </label>
  )

  return (
    <div className="space-y-6 max-w-2xl" data-testid="pickpack-settings">
      {!isOwner && (
        <div className="flex items-center gap-2 text-small text-muted bg-elevated border border-line rounded px-3 py-2">
          <Eye size={14} strokeWidth={1.75} className="flex-shrink-0" />
          {t('settings.readOnlyBanner')}
        </div>
      )}

      <div>
        <div className="flex items-center gap-2">
          <h2 className="text-h3 text-primary">{t('settings.pickPack.title')}</h2>
          <span className="text-caption font-semibold text-muted bg-elevated border border-line rounded-full px-2 py-0.5">
            {t('settings.pickPack.ownersOnly')}
          </span>
        </div>
        <p className="text-small text-muted mt-1">{t('settings.pickPack.subtitle')}</p>
      </div>

      <div className="space-y-2">
        {option('order_queue', t('settings.pickPack.queueTitle'), t('settings.pickPack.queueLine1'), t('settings.pickPack.queueLine2'))}
        {option('waybill_scan', t('settings.pickPack.waybillTitle'), t('settings.pickPack.waybillLine1'), t('settings.pickPack.waybillLine2'))}
      </div>

      <div className="space-y-2">
        <p className="flex items-start gap-2 text-small text-muted">
          <Info size={14} strokeWidth={1.75} className="flex-shrink-0 mt-0.5" />
          {t('settings.pickPack.selfPickupNote')}
        </p>
        <p className="flex items-start gap-2 text-small text-muted">
          <Info size={14} strokeWidth={1.75} className="flex-shrink-0 mt-0.5" />
          {t('settings.pickPack.sessionsNote')}
        </p>
      </div>

      {isOwner && (
        <div className="flex items-center justify-end gap-3 pt-4 border-t border-line">
          {result === 'saved' && <span role="status" className="text-small text-success">{t('settings.saved')}</span>}
          {result === 'error' && <span role="alert" className="text-small text-danger">{t('settings.pickPack.error')}</span>}
          <button
            type="button"
            className="btn btn-outline"
            disabled={saving || mode === saved}
            onClick={() => saved && setMode(saved)}
          >
            {t('common.cancel')}
          </button>
          <button
            type="button"
            className="btn btn-brand"
            disabled={saving || mode === saved}
            onClick={save}
            data-testid="pickpack-save"
          >
            {saving ? t('settings.saving') : t('settings.save')}
          </button>
        </div>
      )}
    </div>
  )
}
