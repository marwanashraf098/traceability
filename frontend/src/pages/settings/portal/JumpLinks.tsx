import { MouseEvent } from 'react'
import { useTranslation } from 'react-i18next'

export const PORTAL_SECTIONS = ['eligibility', 'approval', 'exchanges', 'refunds', 'branding'] as const

/**
 * "On this page" — links to the tab's sections. Scrolls in the page's own scroller and moves focus to the section's
 * heading, without touching the URL (?tab=portal stays as it is).
 */
export default function JumpLinks() {
  const { t } = useTranslation()
  function jump(e: MouseEvent, id: string) {
    const target = document.getElementById(`portal-${id}`)
    if (!target) return
    e.preventDefault()
    const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
    target.scrollIntoView?.({ behavior: reduce ? 'auto' : 'smooth', block: 'start' })
    document.getElementById(`portal-${id}-title`)?.focus({ preventScroll: true })
  }
  return (
    <nav aria-label={t('settings.portal.page.jumpLabel')} className="flex flex-wrap gap-2" data-testid="portal-jump-links">
      {PORTAL_SECTIONS.map(id => (
        <a key={id} href={`#portal-${id}`} onClick={e => jump(e, id)}
          className="inline-flex items-center h-8 px-3 rounded-full border border-line bg-panel text-small text-primary/80
            hover:border-trace-blue hover:text-trace-blue-hover focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-trace-blue">
          {t(`settings.portal.sections.${id}.jump`)}
        </a>
      ))}
    </nav>
  )
}
