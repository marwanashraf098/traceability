import { useTranslation } from 'react-i18next'
import { Copy, ExternalLink } from 'lucide-react'
import { Button, cn } from '../../../components/ui'
import { SwitchRow } from './ui'

export const PORTAL_HOST = 'returns.tracedtech.com'

/**
 * Returns portal — the page header (title, Live / Off, Open portal) and the first card: "Customers can start returns"
 * and the portal link. Live / Off and Open portal follow what is SAVED (what customers see now); the switch and the
 * link field are the form being edited, saved with "Save changes" as before.
 */
export default function PortalHeaderCard({
  savedLive, savedSlug, enabled, onEnabled, slug, onSlug, slugError, copied, onCopy,
}: {
  savedLive: boolean
  savedSlug: string | null
  enabled: boolean
  onEnabled: (v: boolean) => void
  slug: string
  onSlug: (v: string) => void
  slugError?: string
  copied: boolean
  onCopy: () => void
}) {
  const { t } = useTranslation()
  const typed = slug.trim().toLowerCase()
  const portalUrl = `https://${PORTAL_HOST}/${typed}`

  return (
    <header className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2.5">
            <h2 className="text-h3 text-primary">{t('settings.portal.page.title')}</h2>
            <span data-testid="portal-status"
              className={cn('inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-caption font-semibold',
                savedLive ? 'bg-success/10 text-success-text' : 'bg-elevated text-muted')}>
              <span aria-hidden="true" className={cn('w-1.5 h-1.5 rounded-full', savedLive ? 'bg-success' : 'bg-grey-200')} />
              {savedLive ? t('settings.portal.page.live') : t('settings.portal.page.off')}
            </span>
          </div>
          <p className="text-body text-muted mt-1">{t('settings.portal.page.subtitle')}</p>
        </div>
        {savedSlug && (
          <a href={`https://${PORTAL_HOST}/${savedSlug}`} target="_blank" rel="noopener noreferrer"
            className="btn rounded-xl px-3 py-1.5 text-small gap-1.5 border border-line text-primary hover:bg-elevated"
            data-testid="open-portal">
            <ExternalLink className="w-4 h-4" aria-hidden="true" />{t('settings.portal.page.openPortal')}
          </a>
        )}
      </div>

      <section className="card overflow-hidden divide-y divide-line" aria-label={t('settings.portal.page.title')}>
        <div>
          <SwitchRow
            title={t('settings.portal.enabled.title')}
            description={t('settings.portal.enabled.description')}
            checked={enabled}
            onChange={onEnabled}
            note={!enabled ? t('settings.portal.page.offNote') : undefined}
            noteTestId="portal-off-note"
          />
        </div>

        <div className="px-6 py-5 space-y-2">
          <label htmlFor="portal-slug" className="text-body font-semibold text-primary block">
            {t('settings.portal.link.label')}
          </label>
          <div className="flex flex-wrap items-start gap-2">
            <div
              dir="ltr"
              className={cn(
                'flex flex-1 min-w-[240px] max-w-xl items-stretch rounded-xl border bg-surface overflow-hidden',
                slugError ? 'border-critical' : 'border-line focus-within:border-trace-blue'
              )}
            >
              <span className="flex items-center px-3 text-body text-muted bg-elevated border-e border-line whitespace-nowrap">
                {PORTAL_HOST}/
              </span>
              <input
                id="portal-slug"
                type="text"
                autoComplete="off"
                spellCheck={false}
                aria-invalid={!!slugError}
                aria-describedby="portal-slug-help"
                className="flex-1 min-w-0 px-3 py-2 bg-transparent text-body text-primary outline-none font-mono"
                value={slug}
                onChange={e => onSlug(e.target.value)}
              />
            </div>
            <Button variant="outline" onClick={onCopy} disabled={!typed}>
              <Copy className="w-4 h-4" aria-hidden="true" />
              {copied ? t('settings.portal.link.copied') : t('settings.portal.link.copy')}
            </Button>
          </div>
          {slugError && <p className="text-small text-critical" role="alert" data-testid="error-slug">{slugError}</p>}
          {typed && (
            <p className="text-small text-primary font-mono break-all" dir="ltr" data-testid="portal-url">{portalUrl}</p>
          )}
          <p id="portal-slug-help" className="text-small text-muted">{t('settings.portal.link.help')}</p>
        </div>
      </section>
    </header>
  )
}
