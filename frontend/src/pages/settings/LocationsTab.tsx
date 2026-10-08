import { useState, useEffect, useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import { getAccessToken } from '../../auth'

function authHeaders(): Record<string, string> {
  const t = getAccessToken()
  return t ? { Authorization: `Bearer ${t}` } : {}
}

interface Location {
  id: string
  name: string
  type: string
  is_default: boolean
  is_fulfillment?: boolean
  /** Issue 2: while stock sits here (non-main locations only). */
  shopify_sync_mode?: 'remove' | 'leave'
  shopify_location_id: string | null
  shopify_sync_status: 'unsynced' | 'pending' | 'linked' | 'error'
  shopify_sync_error: string | null
  shopify_synced_at: string | null
}

const SYNC_BADGE: Record<string, { cls: string; dot: string }> = {
  unsynced: { cls: 'badge-gray',   dot: 'bg-secondary' },
  pending:  { cls: 'badge-yellow', dot: 'bg-warning' },
  linked:   { cls: 'badge-green',  dot: 'bg-success' },
  error:    { cls: 'badge-red',    dot: 'bg-danger' },
}

function SyncBadge({ status, error }: { status: string; error?: string | null }) {
  const { t } = useTranslation()
  const { cls } = SYNC_BADGE[status] ?? SYNC_BADGE.unsynced
  return (
    <span className={`badge ${cls}`} title={error ?? ''}>
      {t(`locations.syncStatus.${status}`, status)}
    </span>
  )
}

function CreateForm({ onCreated }: { onCreated: () => void }) {
  const { t } = useTranslation()
  const [name, setName]   = useState('')
  const [busy, setBusy]   = useState(false)
  const [err,  setErr]    = useState<string | null>(null)
  const [open, setOpen]   = useState(false)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (!name.trim()) return
    setBusy(true)
    setErr(null)
    try {
      const res = await fetch('/api/v1/locations', {
        method:  'POST',
        headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        body:    JSON.stringify({ name: name.trim() }),
      })
      if (!res.ok) {
        const data = await res.json().catch(() => ({}))
        throw new Error(data.detail ?? res.statusText)
      }
      setName('')
      setOpen(false)
      onCreated()
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : t('common.error'))
    } finally {
      setBusy(false)
    }
  }

  if (!open) {
    return (
      <button onClick={() => setOpen(true)} className="btn btn-brand text-small">
        {t('locations.addLocation')}
      </button>
    )
  }

  return (
    <form onSubmit={submit} className="card p-4 space-y-3 max-w-sm">
      <h3 className="font-medium text-primary text-small">{t('locations.newTitle')}</h3>
      <input
        type="text"
        value={name}
        onChange={e => setName(e.target.value)}
        placeholder={t('locations.namePlaceholder')}
        className="input text-small w-full"
        autoFocus
        disabled={busy}
      />
      {err && <p className="text-xs text-danger">{err}</p>}
      <div className="flex gap-2">
        <button type="submit" disabled={busy || !name.trim()} className="btn btn-brand text-small">
          {busy ? t('common.loading') : t('locations.create')}
        </button>
        <button type="button" onClick={() => { setOpen(false); setErr(null) }}
          className="btn btn-ghost text-small">{t('common.cancel')}</button>
      </div>
      <p className="text-xs text-muted">{t('locations.shopifySyncNote')}</p>
    </form>
  )
}

/**
 * Issue 2 — "While stock is here": Remove from Shopify (default) / Leave Shopify unchanged. A
 * transfer snapshots this when it is sent, so a change applies to the next transfer.
 */
function SyncModeSelect({ loc, onSaved }: { loc: Location; onSaved: (mode: 'remove' | 'leave') => void }) {
  const { t } = useTranslation()
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState(false)
  if (loc.is_fulfillment) {
    return <span className="text-xs text-muted">{t('locations.syncMode.mainNa')}</span>
  }
  async function change(mode: 'remove' | 'leave') {
    setBusy(true); setErr(false)
    try {
      const res = await fetch(`/api/v1/locations/${loc.id}/shopify-sync-mode`, {
        method: 'PUT',
        headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ mode }),
      })
      if (!res.ok) throw new Error(res.statusText)
      onSaved(mode)
    } catch {
      setErr(true)
    } finally {
      setBusy(false)
    }
  }
  return (
    <div>
      <select
        className="input text-small"
        aria-label={t('locations.syncMode.label')}
        value={loc.shopify_sync_mode ?? 'remove'}
        disabled={busy}
        onChange={e => change(e.target.value as 'remove' | 'leave')}
        data-testid={`sync-mode-${loc.id}`}
      >
        <option value="remove">{t('locations.syncMode.remove')}</option>
        <option value="leave">{t('locations.syncMode.leave')}</option>
      </select>
      {err && <p className="text-xs text-danger mt-0.5">{t('common.error')}</p>}
    </div>
  )
}

export default function LocationsTab() {
  const { t } = useTranslation()
  const [locations, setLocations] = useState<Location[]>([])
  const [loading, setLoading]     = useState(false)
  const [error, setError]         = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const res = await fetch('/api/v1/locations', { headers: authHeaders() })
      if (!res.ok) throw new Error(res.statusText)
      setLocations(await res.json())
    } catch {
      setError(t('common.error'))
    } finally {
      setLoading(false)
    }
  }, [t])

  useEffect(() => { load() }, [load])

  return (
    <div className="space-y-5">
      <div className="flex items-center justify-between gap-4">
        <h2 className="text-h3 text-primary">{t('settings.tabs.locationsSubtitle')}</h2>
        <CreateForm onCreated={load} />
      </div>

      {loading && <p className="text-small text-muted">{t('common.loading')}</p>}
      {error   && <p className="text-small text-danger">{error}</p>}

      {!loading && !error && locations.length === 0 && (
        <div className="card p-8 text-center text-muted text-small">{t('locations.empty')}</div>
      )}

      {!loading && !error && locations.length > 0 && (
        <div className="card overflow-hidden">
          <table className="w-full">
            <thead>
              <tr>
                <th className="tbl-header text-start">{t('locations.col.name')}</th>
                <th className="tbl-header text-start">{t('locations.col.type')}</th>
                <th className="tbl-header text-start">{t('locations.col.shopifySync')}</th>
                <th className="tbl-header text-start">{t('locations.col.shopifyId')}</th>
                <th className="tbl-header text-start">{t('locations.col.syncedAt')}</th>
                <th className="tbl-header text-start">{t('locations.syncMode.label')}</th>
              </tr>
            </thead>
            <tbody>
              {locations.map(loc => (
                <tr key={loc.id} className="tbl-row">
                  <td className="tbl-cell">
                    <div className="font-medium text-primary">{loc.name}</div>
                    {loc.is_default && (
                      <span className="text-xs text-muted">{t('locations.default')}</span>
                    )}
                  </td>
                  <td className="tbl-cell text-muted capitalize">{loc.type}</td>
                  <td className="tbl-cell">
                    <SyncBadge status={loc.shopify_sync_status} error={loc.shopify_sync_error} />
                    {loc.shopify_sync_status === 'error' && loc.shopify_sync_error && (
                      <div className="text-xs text-danger mt-0.5 max-w-xs truncate"
                           title={loc.shopify_sync_error}>
                        {loc.shopify_sync_error}
                      </div>
                    )}
                  </td>
                  <td className="tbl-cell text-muted text-xs font-mono">
                    {loc.shopify_location_id ?? t('common.na')}
                  </td>
                  <td className="tbl-cell text-muted text-xs">
                    {loc.shopify_synced_at
                      ? new Date(loc.shopify_synced_at).toLocaleString()
                      : t('common.na')}
                  </td>
                  <td className="tbl-cell">
                    <SyncModeSelect loc={loc} onSaved={mode =>
                      setLocations(prev => prev.map(l => l.id === loc.id ? { ...l, shopify_sync_mode: mode } : l))} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="px-4 py-3 text-xs text-muted border-t border-line" data-testid="sync-mode-help">
            {t('locations.syncMode.help')}
          </p>
        </div>
      )}
    </div>
  )
}
