import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Info } from 'lucide-react'
import { getLabelSettings, updateLabelSettings, ReceivingDefaultMode } from '../../api'
import { Radio, Skeleton, cn } from '../../components/ui'

// Flexible labeling slice 8 — Settings › Labels (switched-on tenants only; owner + manager edit). The receiving mode
// the "How do you want to receive this delivery?" step pre-selects. Flexible labeling itself is NOT switched here —
// that stays an admin-only setting.

export default function LabelsTab() {
  const { t } = useTranslation()
  const [saved, setSaved] = useState<ReceivingDefaultMode | null>(null)
  const [mode, setMode] = useState<ReceivingDefaultMode>('print_stick')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [result, setResult] = useState<'saved' | 'error' | null>(null)

  useEffect(() => {
    let cancelled = false
    getLabelSettings()
      .then(s => {
        if (cancelled) return
        setSaved(s.receivingDefaultMode)
        setMode(s.receivingDefaultMode)
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
      await updateLabelSettings({ receivingDefaultMode: mode })
      setSaved(mode)
      setResult('saved')
    } catch {
      setResult('error')
    } finally {
      setSaving(false)
    }
  }

  if (loading) return <Skeleton className="h-64 rounded-2xl" />

  const option = (value: ReceivingDefaultMode) => (
    <label
      className={cn('flex items-start gap-3 rounded-xl border p-4 cursor-pointer transition-colors',
        mode === value ? 'border-trace-blue bg-trace-blue/[0.06]' : 'border-line hover:bg-elevated')}
      data-testid={`labels-mode-${value}`}
    >
      <span className="pt-0.5">
        <Radio value={value} checked={mode === value} onChange={v => setMode(v as ReceivingDefaultMode)} />
      </span>
      <span className="flex-1 min-w-0">
        <span className="block text-body font-semibold text-primary">{t(`receiving.mode.${value}.title`)}</span>
        <span className="block text-small text-muted mt-0.5">{t(`receiving.mode.${value}.body`)}</span>
      </span>
    </label>
  )

  return (
    <div className="space-y-6 max-w-2xl" data-testid="labels-settings">
      <div>
        <h2 className="text-h3 text-primary">{t('settings.labels.title')}</h2>
        <p className="text-small text-muted mt-1">{t('settings.labels.subtitle')}</p>
      </div>

      <div className="space-y-2">
        <p className="text-small font-semibold text-primary">{t('settings.labels.defaultMode')}</p>
        {option('print_stick')}
        {option('count_only')}
      </div>

      <p className="flex items-start gap-2 text-small text-muted">
        <Info size={14} strokeWidth={1.75} className="flex-shrink-0 mt-0.5" />
        {t('settings.labels.note')}
      </p>

      <div className="flex items-center justify-end gap-3 pt-4 border-t border-line">
        {result === 'saved' && <span role="status" className="text-small text-success">{t('settings.saved')}</span>}
        {result === 'error' && <span role="alert" className="text-small text-danger">{t('settings.labels.error')}</span>}
        <button type="button" className="btn btn-outline" disabled={saving || mode === saved}
          onClick={() => saved && setMode(saved)}>
          {t('common.cancel')}
        </button>
        <button type="button" className="btn btn-brand" disabled={saving || mode === saved} onClick={save}
          data-testid="labels-save">
          {saving ? t('settings.saving') : t('settings.save')}
        </button>
      </div>
    </div>
  )
}
