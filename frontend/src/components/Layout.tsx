import { NavLink, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useState, useRef, useEffect, ReactNode } from 'react'
import type { LucideIcon } from 'lucide-react'
import {
  LayoutDashboard, ShoppingBag, Warehouse, Inbox, ClipboardList, PackageCheck,
  Truck, Repeat, Undo2, AlertTriangle, Home,
  Settings, LogOut, Globe, Search, ChevronDown, Bell,
} from 'lucide-react'
import {
  getRoleFromToken, request, getMe, getExceptionsCount, getOnboardingStatus,
  type Me, type OnboardingStatus,
} from '../api'
import { clearAccessToken } from '../auth'
import { Logo } from './Logo'
import { cn, MeProvider } from './ui'
import { useStation } from './StationProvider'

// ── Nav link ──────────────────────────────────────────────────────────────────
// Icon is passed as a component reference (not pre-rendered) so it can be
// colored conditionally on the NavLink's own isActive state.

function SideNavLink({ to, icon: Icon, label }: { to: string; icon: LucideIcon; label: string }) {
  return (
    <NavLink
      to={to}
      className={({ isActive }) => cn('nav-item', isActive && 'nav-item-active')}
    >
      {({ isActive }) => (
        <>
          <Icon size={18} strokeWidth={1.75} className={isActive ? 'text-trace-blue' : ''} />
          <span>{label}</span>
        </>
      )}
    </NavLink>
  )
}

function roleInitials(role: string | null): string {
  return role ? role.slice(0, 2).toUpperCase() : '?'
}

function nameInitials(name: string): string {
  return name.trim().split(/\s+/).slice(0, 2).map(w => w[0]).join('').toUpperCase()
}

function avatarInitials(me: Me | null, role: string | null): string {
  return me?.name ? nameInitials(me.name) : roleInitials(role)
}

// ── Layout ────────────────────────────────────────────────────────────────────

export default function Layout({ children }: { children: ReactNode }) {
  const { t, i18n } = useTranslation()
  const navigate    = useNavigate()
  const [searchQ, setSearchQ]   = useState('')
  const [menuOpen, setMenuOpen] = useState(false)
  const [me, setMe]             = useState<Me | null>(null)
  const [exceptionsCount, setExceptionsCount] = useState<number | null>(null)
  const [onboarding, setOnboarding] = useState<OnboardingStatus | null>(null)
  const searchRef = useRef<HTMLInputElement>(null)
  const menuRef   = useRef<HTMLDivElement>(null)
  const role      = getRoleFromToken()
  const { stationMode, signOutWorker } = useStation()

  // A worker's control hands the station to the next worker rather than logging
  // the whole device out — the refresh cookie and stationMode stay untouched.
  // signOutWorker() clears currentWorker in-memory only; RequireAuth reacts to
  // that and re-renders <StationGate/> in place. Owner/manager (or a device not
  // in station mode) keep the real full logout, unchanged.
  const isWorkerAtStation = role === 'worker' && stationMode

  async function logout() {
    try { await request<void>('/auth/logout', { method: 'POST' }) } catch { /* ignore */ }
    clearAccessToken()
    navigate('/login')
  }

  function logoutControl() {
    setMenuOpen(false)
    if (isWorkerAtStation) {
      signOutWorker()
    } else {
      logout()
    }
  }

  function handleSearch(e: React.FormEvent) {
    e.preventDefault()
    const q = searchQ.trim()
    if (!q) return
    navigate(`/lookup?q=${encodeURIComponent(q)}`)
    setSearchQ('')
    searchRef.current?.blur()
  }

  function toggleLang() {
    const next = i18n.language === 'en' ? 'ar' : 'en'
    i18n.changeLanguage(next)
    localStorage.setItem('lang', next)
    document.documentElement.dir  = next === 'ar' ? 'rtl' : 'ltr'
    document.documentElement.lang = next
  }

  // ⌘K / Ctrl+K focuses the topbar search — visual hint chip only otherwise.
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        searchRef.current?.focus()
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [])

  // User-menu dropdown: click-outside to close.
  useEffect(() => {
    function onOutside(e: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) setMenuOpen(false)
    }
    if (menuOpen) document.addEventListener('mousedown', onOutside)
    return () => document.removeEventListener('mousedown', onOutside)
  }, [menuOpen])

  // Shell identity — decorative, non-blocking: on failure, keep the role-only
  // placeholder from the Shell pass rather than surfacing an error.
  useEffect(() => {
    getMe().then(setMe).catch(err => console.error('Failed to load /me', err))
  }, [])

  // Open-exceptions count for the notification bell — decorative, non-blocking:
  // on failure the bell renders with no badge (never NaN/stale), never throws
  // into the shell. Restricted to Owner/Manager, matching the backend endpoint's
  // access control (workers get 403 on /exceptions and /exceptions/count).
  useEffect(() => {
    if (role === 'worker') return
    getExceptionsCount()
      .then(r => setExceptionsCount(r.count))
      .catch(err => console.error('Failed to load exceptions count', err))
  }, [role])

  // Setup N/5 chip — decorative, non-blocking: on failure the chip just doesn't
  // render (never a stale/NaN count). Owner/Manager only, matching the endpoint's
  // access control (workers get 403 on /onboarding/status).
  useEffect(() => {
    if (role === 'worker') return
    getOnboardingStatus()
      .then(setOnboarding)
      .catch(err => console.error('Failed to load onboarding status', err))
  }, [role])

  return (
    <MeProvider me={me}>
    <div className="flex h-screen bg-bg overflow-hidden">

      {/* ── Sidebar ── */}
      {/* Fixed dark rail — pinned to the sidebar-* tokens, never flips with the
          content-area theme (see tailwind.config.js `sidebar` palette). */}
      <aside className="w-56 flex-shrink-0 bg-sidebar border-e border-sidebar-line flex flex-col">

        {/* Wordmark */}
        <div className="flex items-center px-[18px] py-5 border-b border-sidebar-line">
          <Logo variant="mark" size={18} className="text-sidebar-active" />
        </div>

        {/* Nav — worker gets a reduced task-scoped set (Home + the three worker
            screens); owner/manager see the full nav, unchanged. */}
        <nav className="flex-1 overflow-y-auto py-3 flex flex-col gap-0.5">
          {role === 'worker' ? (
            <>
              <SideNavLink to="/worker-home" icon={Home}          label={t('nav.home')} />
              <SideNavLink to="/fulfill"     icon={PackageCheck}  label={t('nav.fulfill')} />
              <SideNavLink to="/returns"     icon={Undo2}         label={t('nav.returns')} />
              <SideNavLink to="/pickups"     icon={Truck}         label={t('nav.pickups')} />
            </>
          ) : (
            <>
              <SideNavLink to="/overview"    icon={LayoutDashboard} label={t('nav.overview')} />
              <SideNavLink to="/orders"      icon={ShoppingBag}     label={t('nav.orders')} />
              <SideNavLink to="/inventory"   icon={Warehouse}       label={t('nav.catalog')} />
              <SideNavLink to="/receiving"   icon={Inbox}           label={t('nav.receiving')} />
              <SideNavLink to="/stock-take"  icon={ClipboardList}   label={t('nav.stocktake')} />
              <SideNavLink to="/fulfill"     icon={PackageCheck}    label={t('nav.fulfill')} />
              <SideNavLink to="/pickups"     icon={Truck}           label={t('nav.pickups')} />
              <SideNavLink to="/transfers"   icon={Repeat}          label={t('nav.transfers')} />
              <SideNavLink to="/returns"     icon={Undo2}           label={t('nav.returns')} />
              <SideNavLink to="/exceptions"  icon={AlertTriangle}   label={t('nav.exceptions')} />
              <div className="h-px bg-sidebar-line mx-[18px] my-2.5" />
              <div className="px-[18px] pt-1.5 pb-0.5 text-[11px] font-semibold tracking-wider text-sidebar-text uppercase">
                {t('nav.manager')}
              </div>
              <SideNavLink to="/settings" icon={Settings} label={t('nav.settings')} />
            </>
          )}
        </nav>

        {/* Bottom: identity — real name+role once /me resolves; role-only placeholder until then/on failure */}
        <div className="border-t border-sidebar-line px-[18px] py-[14px] flex items-center gap-2.5">
          <span className="w-[30px] h-[30px] rounded-full bg-trace-blue text-white text-[12px] font-bold flex items-center justify-center flex-shrink-0">
            {avatarInitials(me, role)}
          </span>
          {me ? (
            <span className="min-w-0 flex flex-col">
              <span className="text-small text-sidebar-active leading-tight truncate">{me.name}</span>
              <span className="text-caption text-sidebar-text leading-tight">{t(`users.roles.${me.role}`)}</span>
            </span>
          ) : role && (
            <span className="text-small text-sidebar-text capitalize">{t(`users.roles.${role}`)}</span>
          )}
        </div>
      </aside>

      {/* ── Main area ── */}
      <div className="flex-1 flex flex-col overflow-hidden min-w-0">

        {/* Top bar */}
        <header className="h-14 border-b border-line flex items-center justify-between px-[18px] gap-4 flex-shrink-0">
          <form onSubmit={handleSearch} className="flex-1 max-w-[360px]">
            <div className="relative">
              <span className="absolute start-3 top-1/2 -translate-y-1/2 text-muted pointer-events-none">
                <Search size={14} strokeWidth={2} />
              </span>
              <input
                ref={searchRef}
                type="text"
                value={searchQ}
                onChange={e => setSearchQ(e.target.value)}
                placeholder={t('nav.lookup')}
                className="input ps-9 pe-12 text-small py-1.5 w-full"
              />
              <span className="absolute end-3 top-1/2 -translate-y-1/2 text-[11px] font-mono text-muted bg-charcoal border border-line rounded-[5px] px-[5px] py-[1px] pointer-events-none">
                ⌘K
              </span>
            </div>
          </form>

          <div className="flex items-center gap-4 flex-shrink-0">
            {/* Notification bell — count = open exceptions, our single "needs attention"
                surface. No badge on the Exceptions nav item — one number, one place.
                Owner/Manager only, matching the backend endpoint's access control. */}
            {role !== 'worker' && (
              <button
                type="button"
                onClick={() => navigate('/exceptions')}
                className="relative text-muted hover:text-primary transition-colors"
              >
                <Bell size={17} strokeWidth={1.75} />
                {!!exceptionsCount && (
                  <span className="absolute -top-1 -end-1.5 bg-critical text-white text-[9px] font-bold rounded-full px-[4px] leading-[1.2] font-mono">
                    {exceptionsCount > 99 ? '99+' : exceptionsCount}
                  </span>
                )}
              </button>
            )}

            {/* User menu — avatar trigger, dropdown shows real identity + hosts lang toggle + logout */}
            <div ref={menuRef} className="relative">
              <button
                type="button"
                onClick={() => setMenuOpen(o => !o)}
                className="flex items-center gap-1.5"
              >
                <span className="w-[26px] h-[26px] rounded-full bg-trace-blue text-white text-[11px] font-bold flex items-center justify-center">
                  {avatarInitials(me, role)}
                </span>
                <ChevronDown
                  size={13}
                  strokeWidth={2}
                  className={cn('text-muted transition-transform', menuOpen && 'rotate-180')}
                />
              </button>

              {menuOpen && (
                <div className="absolute end-0 top-full mt-2 w-48 bg-surface border border-line rounded-lg shadow-e3 overflow-hidden z-dropdown py-1">
                  {me && (
                    <div className="px-3 py-2 border-b border-line min-w-0">
                      <p className="text-small text-primary truncate">{me.name}</p>
                      {me.email && <p className="text-caption text-muted truncate">{me.email}</p>}
                    </div>
                  )}
                  {onboarding && !onboarding.allDone && (
                    <button
                      type="button"
                      onClick={() => { setMenuOpen(false); navigate('/overview') }}
                      className="w-full flex items-center justify-between px-3 py-2 text-caption text-start hover:bg-black/[0.04] transition-colors border-b border-line"
                    >
                      <span className="text-muted">{t('nav.setupProgress')}</span>
                      <span className="font-bold text-trace-blue bg-trace-blue/15 rounded-full px-2 py-0.5">
                        {t('nav.setupCount', { done: onboarding.steps.filter(s => s.done).length, total: onboarding.steps.length })}
                      </span>
                    </button>
                  )}
                  <button
                    type="button"
                    onClick={() => { toggleLang(); setMenuOpen(false) }}
                    className="w-full flex items-center gap-2.5 px-3 py-2 text-body text-start text-muted hover:bg-black/[0.04] hover:text-primary transition-colors"
                  >
                    <Globe size={16} strokeWidth={1.75} />
                    {i18n.language === 'en' ? 'العربية' : 'English'}
                  </button>
                  <button
                    type="button"
                    onClick={logoutControl}
                    className="w-full flex items-center gap-2.5 px-3 py-2 text-body text-start text-danger hover:bg-danger/10 transition-colors"
                  >
                    <LogOut size={16} strokeWidth={1.75} />
                    {isWorkerAtStation ? t('nav.switchLock') : t('nav.logout')}
                  </button>
                </div>
              )}
            </div>
          </div>
        </header>

        {/* Page content */}
        <main className="flex-1 overflow-y-auto p-6">
          {children}
        </main>
      </div>
    </div>
    </MeProvider>
  )
}
