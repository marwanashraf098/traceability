import { useTranslation } from 'react-i18next'
import { AlertTriangle } from 'lucide-react'
import { Button } from '../../../components/ui'
import type { SaveFailure } from './usePortalSettingsDraft'

/**
 * The tab's one save: "N unsaved changes · Discard · Save changes", sticky at the bottom, shown only while something is
 * edited. The ONLY button named "Save changes" on the tab. After a partial failure it names the section that wasn't
 * saved with the backend's message; "Changes saved" is a toast, and only on full success (usePortalSettingsDraft).
 */
export default function SaveBar({ count, saving, failure, blocked, onDiscard, onSave }: {
  count: number
  saving: boolean
  failure: SaveFailure | null
  /** A field the browser can check is wrong (e.g. an empty cap) — Save does nothing until it's fixed. */
  blocked: boolean
  onDiscard: () => void
  onSave: () => void
}) {
  const { t } = useTranslation()
  if (count === 0) return null
  return (
    <div className="sticky bottom-3 z-[5]" data-testid="save-bar">
      <div role="region" aria-label={t('settings.portal.saveBar.label')}
        className="rounded-2xl border border-line bg-surface shadow-e3 px-4 py-3 sm:px-5 flex flex-col gap-2">
        {failure && (
          <p role="alert" className="flex items-start gap-2 text-small text-critical" data-testid="save-failure">
            <AlertTriangle className="w-4 h-4 mt-0.5 shrink-0" aria-hidden="true" />
            <span>
              {t('settings.portal.saveBar.failed', { section: t(`settings.portal.saveBar.sections.${failure.section}`) })}
              {' '}{failure.message}
              {failure.partial && <> {t('settings.portal.saveBar.partial')}</>}
            </span>
          </p>
        )}
        {blocked && !failure && (
          <p role="alert" className="text-small text-critical" data-testid="save-blocked">{t('settings.portal.saveBar.blocked')}</p>
        )}
        <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
          <p className="flex-1 min-w-0 text-body font-medium text-primary" data-testid="unsaved-count" aria-live="polite">
            {t('settings.portal.saveBar.unsaved', { count })}
          </p>
          <div className="flex items-center gap-2 ms-auto">
            <Button variant="outline" onClick={onDiscard} disabled={saving}>{t('settings.portal.saveBar.discard')}</Button>
            <Button onClick={onSave} loading={saving} disabled={saving}>{t('settings.portal.save')}</Button>
          </div>
        </div>
      </div>
    </div>
  )
}
