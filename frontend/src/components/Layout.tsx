import { NavLink, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useState, useRef, useEffect, ReactNode } from 'react'
import type { LucideIcon } from 'lucide-react'
import {
  LayoutDashboard, ShoppingBag, List, Inbox, ClipboardList, PackageCheck,
  Truck, Repeat, Undo2, AlertTriangle, Plug, MapPin, Store, UserPlus,
  Users, Settings, LogOut, Globe, Search, ChevronDown,
} from 'lucide-react'
import { getRoleFromToken, request } from '../api'
import { clearAccessToken } from '../auth'
import { Logo } from './Logo'
import { cn } from './ui'

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

// ── Layout ────────────────────────────────────────────────────────────────────

export default function Layout({ children }: { children: ReactNode }) {
  const { t, i18n } = useTranslation()
  const navigate    = useNavigate()
  const [searchQ, setSearchQ]   = useState('')
  const [menuOpen, setMenuOpen] = useState(false)
  const searchRef = useRef<HTMLInputElement>(null)
  const menuRef   = useRef<HTMLDivElement>(null)
  const role      = getRoleFromToken()

  async function logout() {
    try { await request<void>('/auth/logout', { method: 'POST' }) } catch { /* ignore */ }
    clearAccessToken()
    navigate('/login')
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

  return (
    <div className="flex h-screen bg-bg overflow-hidden">

      {/* ── Sidebar ── */}
      <aside className="w-56 flex-shrink-0 bg-panel border-e border-line flex flex-col">

        {/* Wordmark */}
        <div className="flex items-center px-[18px] py-5 border-b border-line">
          <Logo variant="mark" size={18} />
        </div>

        {/* Nav */}
        <nav className="flex-1 overflow-y-auto py-3 flex flex-col gap-0.5">
          <SideNavLink to="/overview"    icon={LayoutDashboard} label={t('nav.overview')} />
          <SideNavLink to="/orders"      icon={ShoppingBag}     label={t('nav.orders')} />
          <SideNavLink to="/catalog"     icon={List}            label={t('nav.catalog')} />
          <SideNavLink to="/receiving"   icon={Inbox}           label={t('nav.receiving')} />
          <SideNavLink to="/stock-take"  icon={ClipboardList}   label={t('nav.stocktake')} />
          <SideNavLink to="/fulfill"     icon={PackageCheck}    label={t('nav.fulfill')} />
          <SideNavLink to="/pickups"     icon={Truck}           label={t('nav.pickups')} />
          <SideNavLink to="/transfers"   icon={Repeat}          label={t('nav.transfers')} />
          <SideNavLink to="/returns"     icon={Undo2}           label={t('nav.returns')} />
          <SideNavLink to="/exceptions"  icon={AlertTriangle}   label={t('nav.exceptions')} />
          <SideNavLink to="/connections" icon={Plug}            label={t('nav.connections')} />
          {role !== 'worker' && (
            <>
              <div className="h-px bg-line mx-[18px] my-2.5" />
              <div className="px-[18px] pt-1.5 pb-0.5 text-[11px] font-semibold tracking-wider text-muted uppercase">
                {t('nav.manager')}
              </div>
              <SideNavLink to="/locations"         icon={MapPin}   label={t('nav.locations')} />
              <SideNavLink to="/shopify-inventory"  icon={Store}    label={t('nav.shopifyInventory')} />
              <SideNavLink to="/onboarding"         icon={UserPlus} label={t('nav.onboarding')} />
              <SideNavLink to="/users"              icon={Users}    label={t('nav.users')} />
              <SideNavLink to="/settings"           icon={Settings} label={t('nav.settings')} />
            </>
          )}
        </nav>

        {/* Bottom: identity — avatar (role-based initials) + role, no name/email available client-side */}
        <div className="border-t border-line px-[18px] py-[14px] flex items-center gap-2.5">
          <span className="w-[30px] h-[30px] rounded-full bg-trace-blue text-white text-[12px] font-bold flex items-center justify-center flex-shrink-0">
            {roleInitials(role)}
          </span>
          {role && (
            <span className="text-small text-muted capitalize">{t(`users.roles.${role}`)}</span>
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

          {/* User menu — avatar trigger, dropdown hosts lang toggle + logout */}
          <div ref={menuRef} className="relative flex-shrink-0">
            <button
              type="button"
              onClick={() => setMenuOpen(o => !o)}
              className="flex items-center gap-1.5"
            >
              <span className="w-[26px] h-[26px] rounded-full bg-trace-blue text-white text-[11px] font-bold flex items-center justify-center">
                {roleInitials(role)}
              </span>
              <ChevronDown
                size={13}
                strokeWidth={2}
                className={cn('text-muted transition-transform', menuOpen && 'rotate-180')}
              />
            </button>

            {menuOpen && (
              <div className="absolute end-0 top-full mt-2 w-48 bg-surface border border-line rounded-lg shadow-e3 overflow-hidden z-dropdown py-1">
                <button
                  type="button"
                  onClick={() => { toggleLang(); setMenuOpen(false) }}
                  className="w-full flex items-center gap-2.5 px-3 py-2 text-body text-start text-muted hover:bg-white/5 hover:text-primary transition-colors"
                >
                  <Globe size={16} strokeWidth={1.75} />
                  {i18n.language === 'en' ? 'العربية' : 'English'}
                </button>
                <button
                  type="button"
                  onClick={() => { setMenuOpen(false); logout() }}
                  className="w-full flex items-center gap-2.5 px-3 py-2 text-body text-start text-danger hover:bg-danger/10 transition-colors"
                >
                  <LogOut size={16} strokeWidth={1.75} />
                  {t('nav.logout')}
                </button>
              </div>
            )}
          </div>
        </header>

        {/* Page content */}
        <main className="flex-1 overflow-y-auto p-6">
          {children}
        </main>
      </div>
    </div>
  )
}
