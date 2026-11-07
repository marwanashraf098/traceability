import { ReactNode, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button, Modal } from '../../../components/ui'

/** In-app ways out of the tab: a link to another page (main sidebar, top bar) or another Settings menu item. */
function leavingTarget(target: EventTarget | null, inside: HTMLElement | null): HTMLElement | null {
  if (!(target instanceof Element)) return null
  const navItem = target.closest<HTMLElement>('[data-testid="settings-nav"] button')
  if (navItem) return navItem.getAttribute('aria-current') === 'page' ? null : navItem
  const link = target.closest<HTMLAnchorElement>('a[href]')
  if (!link || (inside && inside.contains(link))) return null
  const href = link.getAttribute('href') ?? ''
  if (href.startsWith('#') || link.target === '_blank' || link.hasAttribute('download')) return null
  try {
    if (new URL(link.href, window.location.href).origin !== window.location.origin) return null
  } catch { return null }
  return link
}

/**
 * Redesign Pass B — while the tab has unsaved changes: an in-app link click (main sidebar, Settings menu, any other
 * same-site link outside the tab) asks "Leave without saving?" first, and closing / reloading the browser tab gets the
 * browser's own prompt (beforeunload). The browser's Back button is out of scope.
 *
 * The click is caught on the document in the capture phase, before React Router's link handler, and replayed with
 * `click()` when the merchant chooses to leave.
 */
export function useLeaveGuard(active: boolean, count: number, inside: React.RefObject<HTMLElement>): ReactNode {
  const { t } = useTranslation()
  const [pending, setPending] = useState<HTMLElement | null>(null)
  const bypass = useRef(false)

  useEffect(() => {
    if (!active) return
    function onClick(e: MouseEvent) {
      if (bypass.current || e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
      const el = leavingTarget(e.target, inside.current)
      if (!el) return
      e.preventDefault()
      e.stopPropagation()
      setPending(el)
    }
    function onBeforeUnload(e: BeforeUnloadEvent) {
      e.preventDefault()
      e.returnValue = ''
    }
    document.addEventListener('click', onClick, true)
    window.addEventListener('beforeunload', onBeforeUnload)
    return () => {
      document.removeEventListener('click', onClick, true)
      window.removeEventListener('beforeunload', onBeforeUnload)
    }
  }, [active, inside])

  if (!pending) return null
  const stay = () => setPending(null)
  const leave = () => {
    const el = pending
    setPending(null)
    bypass.current = true
    try { el.click() } finally { bypass.current = false }
  }
  return (
    <Modal onClose={stay} title={t('settings.portal.leave.title')}>
      <div role="alertdialog" aria-label={t('settings.portal.leave.title')} data-testid="leave-guard" className="space-y-5">
        <p className="text-body text-primary">{t('settings.portal.leave.body', { count })}</p>
        <div className="flex flex-wrap justify-end gap-2">
          <Button variant="outline" onClick={stay}>{t('settings.portal.leave.stay')}</Button>
          <Button variant="danger" onClick={leave}>{t('settings.portal.leave.leave')}</Button>
        </div>
      </div>
    </Modal>
  )
}
