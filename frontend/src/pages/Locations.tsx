import { useState, useEffect, useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import { getAccessToken } from '../auth'

function authHeaders(): Record<string, string> {
  const t = getAccessToken()
  return t ? { Authorization: `Bearer ${t}` } : {}
}

interface Location {
  id: string
  name: string
  type: string
  is_default: boolean
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
      <button onClick={() => setOpen(true)} className="btn-primary text-sm">
        {t('locations.addLocation')}
      </button>
    )
  }

  return (
    <form onSubmit={submit} className="card p-4 space-y-3 max-w-sm">
      <h3 className="font-medium text-primary text-sm">{t('locations.newTitle')}</h3>
      <input
        type="text"
        value={name}
        onChange={e => setName(e.target.value)}
        placeholder={t('locations.namePlaceholder')}
        className="input text-sm w-full"
        autoFocus
        disabled={busy}
      />
      {err && <p className="text-xs text-danger">{err}</p>}
      <div className="flex gap-2">
        <button type="submit" disabled={busy || !name.trim()} className="btn-primary text-sm">
          {busy ? t('common.loading') : t('locations.create')}
        </button>
        <button type="button" onClick={() => { setOpen(false); setErr(null) }}
          className="btn-secondary text-sm">{t('common.cancel')}</button>
      </div>
      <p className="text-xs text-muted">{t('locations.shopifySyncNote')}</p>
    </form>
  )
}

export default function Locations() {
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
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold text-primary">{t('locations.title')}</h1>
        <CreateForm onCreated={load} />
      </div>

      {loading && <p className="text-sm text-muted">{t('common.loading')}</p>}
      {error   && <p className="text-sm text-danger">{error}</p>}

      {!loading && !error && locations.length === 0 && (
        <div className="card p-8 text-center text-muted text-sm">{t('locations.empty')}</div>
      )}

      {!loading && !error && locations.length > 0 && (
        <div className="card overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-surface border-b border-line">
              <tr>
                <th className="text-start px-4 py-2.5 text-xs font-medium text-secondary">{t('locations.col.name')}</th>
                <th className="text-start px-4 py-2.5 text-xs font-medium text-secondary">{t('locations.col.type')}</th>
                <th className="text-start px-4 py-2.5 text-xs font-medium text-secondary">{t('locations.col.shopifySync')}</th>
                <th className="text-start px-4 py-2.5 text-xs font-medium text-secondary">{t('locations.col.shopifyId')}</th>
                <th className="text-start px-4 py-2.5 text-xs font-medium text-secondary">{t('locations.col.syncedAt')}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {locations.map(loc => (
                <tr key={loc.id}>
                  <td className="px-4 py-3">
                    <div className="font-medium text-primary">{loc.name}</div>
                    {loc.is_default && (
                      <span className="text-xs text-muted">{t('locations.default')}</span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-secondary capitalize">{loc.type}</td>
                  <td className="px-4 py-3">
                    <SyncBadge status={loc.shopify_sync_status} error={loc.shopify_sync_error} />
                    {loc.shopify_sync_status === 'error' && loc.shopify_sync_error && (
                      <div className="text-xs text-danger mt-0.5 max-w-xs truncate"
                           title={loc.shopify_sync_error}>
                        {loc.shopify_sync_error}
                      </div>
                    )}
                  </td>
                  <td className="px-4 py-3 text-secondary text-xs font-mono">
                    {loc.shopify_location_id ?? t('common.na')}
                  </td>
                  <td className="px-4 py-3 text-secondary text-xs">
                    {loc.shopify_synced_at
                      ? new Date(loc.shopify_synced_at).toLocaleString()
                      : t('common.na')}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
