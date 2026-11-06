import { useTranslation } from 'react-i18next'
import { cn } from '../../components/ui'

export type SettingsTabKey =
  'business' | 'connections' | 'users' | 'locations' | 'places' | 'pickpack' | 'labels' | 'couriers' | 'portal' | 'privacy'

/** The menu's groups, in the mockup's order (design/returns-portal-settings.html). Which items show is SettingsPage's. */
export const SETTINGS_GROUPS: Array<{ key: string; tabs: SettingsTabKey[] }> = [
  { key: 'store', tabs: ['business', 'locations', 'places'] },
  { key: 'operations', tabs: ['pickpack', 'labels', 'couriers'] },
  { key: 'returns', tabs: ['portal'] },
  { key: 'account', tabs: ['users', 'connections', 'privacy'] },
]

/**
 * Settings — the grouped vertical menu (replaces the row of tabs). Items are buttons with the tab's name (tests and
 * screen readers find them by it); the open one carries aria-current="page". Phones: the groups stack above the page,
 * each group's items wrapping in a row. Not sticky.
 */
export default function SettingsNav({ visible, active, onSelect }: {
  visible: SettingsTabKey[]; active: SettingsTabKey; onSelect: (key: SettingsTabKey) => void
}) {
  const { t } = useTranslation()
  return (
    <nav aria-label={t('settings.nav.label')} className="lg:w-56 shrink-0" data-testid="settings-nav">
      <h1 className="text-h3 font-bold text-primary lg:px-2.5 mb-1">{t('settings.title')}</h1>
      <div className="flex flex-col gap-3 lg:gap-0">
        {SETTINGS_GROUPS.map(g => {
          const items = g.tabs.filter(k => visible.includes(k))
          if (items.length === 0) return null
          return (
            <div key={g.key} role="group" aria-labelledby={`settings-group-${g.key}`}>
              <p id={`settings-group-${g.key}`}
                className="text-caption font-bold uppercase tracking-wider text-muted lg:px-2.5 lg:mt-4 mb-1.5">
                {t(`settings.nav.groups.${g.key}`)}
              </p>
              <ul className="flex flex-wrap gap-1.5 lg:flex-col lg:gap-0.5">
                {items.map(k => {
                  const current = k === active
                  return (
                    <li key={k}>
                      <button type="button" onClick={() => onSelect(k)} aria-current={current ? 'page' : undefined}
                        className={cn('text-body rounded-lg transition-colors text-start',
                          'px-3 py-1.5 border lg:border-0 lg:w-full lg:h-9 lg:px-2.5 lg:py-0',
                          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-trace-blue',
                          current
                            ? 'bg-trace-blue/10 text-trace-blue-hover font-semibold border-trace-blue/30'
                            : 'text-primary/80 border-line hover:bg-elevated hover:text-primary')}>
                        {t(`settings.tabs.${k}`)}
                      </button>
                    </li>
                  )
                })}
              </ul>
            </div>
          )
        })}
      </div>
    </nav>
  )
}
