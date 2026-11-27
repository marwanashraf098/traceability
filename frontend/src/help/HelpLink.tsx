import { useTranslation } from 'react-i18next'
import { CircleHelp } from 'lucide-react'
import { getRoleFromToken } from '../api'
import { cn } from '../components/ui'
import { SCREEN_ARTICLES, type ScreenHelp } from './helpRegistry'

/**
 * A screen's "Read more" link to its Help article (owners / managers — workers have no help center, so nothing
 * renders for them). Opens in a new tab so a half-filled form or a wizard step isn't left behind.
 */
export function HelpLink({ screen, className }: { screen: ScreenHelp; className?: string }) {
  const { t } = useTranslation()
  if (getRoleFromToken() === 'worker') return null
  return (
    <a href={`/help/${SCREEN_ARTICLES[screen]}`} target="_blank" rel="noopener noreferrer"
       data-testid={`help-link-${screen}`}
       className={cn('inline-flex items-center gap-1 text-small font-semibold text-trace-blue hover:text-trace-blue-hover hover:underline shrink-0', className)}>
      <CircleHelp size={15} aria-hidden />{t('helpCenter.readMore')}
    </a>
  )
}
