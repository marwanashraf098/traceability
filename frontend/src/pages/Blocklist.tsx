import { useState, useEffect, FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import {
  listBlocklist, addToBlocklist, removeFromBlocklist,
  BlocklistEntry,
} from '../api'

// ── Add modal ─────────────────────────────────────────────────────────────────

function AddModal({ onAdded, onClose }: { onAdded: () => void; onClose: () => void }) {
  const { t } = useTranslation()
  const [phone,   setPhone]   = useState('')
  const [reason,  setReason]  = useState('')
  const [loading, setLoading] = useState(false)
  const [error,   setError]   = useState('')

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      await addToBlocklist(phone.trim(), reason.trim())
      onAdded()
      onClose()
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
      <div className="bg-surface rounded-2xl shadow-xl p-6 w-full max-w-sm mx-4">
        <h2 className="text-body font-semibold mb-4">{t('blocklist.add.title')}</h2>
        <form onSubmit={handleSubmit} className="space-y-3">
          <div>
            <label className="block text-caption text-muted mb-1">{t('blocklist.add.phone')}</label>
            <input
              data-testid="bl-phone"
              className="input w-full"
              placeholder={t('blocklist.add.phonePlaceholder')}
              value={phone}
              onChange={e => setPhone(e.target.value)}
              required
            />
          </div>
          <div>
            <label className="block text-caption text-muted mb-1">{t('blocklist.add.reason')}</label>
            <input
              data-testid="bl-reason"
              className="input w-full"
              placeholder={t('blocklist.add.reasonPlaceholder')}
              value={reason}
              onChange={e => setReason(e.target.value)}
              required
            />
          </div>
          {error && <p className="text-caption text-red-500">{error}</p>}
          <div className="flex gap-2 pt-1">
            <button type="submit" className="btn-primary flex-1" disabled={loading}>
              {loading ? '…' : t('blocklist.add.submit')}
            </button>
            <button type="button" className="btn-ghost flex-1" onClick={onClose}>
              {t('blocklist.add.cancel')}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

// ── Row ───────────────────────────────────────────────────────────────────────

function BlocklistRow({
  entry,
  onRemoved,
}: {
  entry: BlocklistEntry
  onRemoved: () => void
}) {
  const { t, i18n } = useTranslation()
  const [confirming, setConfirming] = useState(false)
  const [loading,    setLoading]    = useState(false)

  async function handleRemove() {
    setLoading(true)
    try {
      await removeFromBlocklist(entry.id)
      onRemoved()
    } finally {
      setLoading(false)
      setConfirming(false)
    }
  }

  return (
    <tr className="border-b border-line last:border-0">
      <td className="py-3 px-4 font-mono text-caption">{entry.phoneCanonical}</td>
      <td className="py-3 px-4 text-caption">{entry.reason}</td>
      <td className="py-3 px-4 text-caption text-muted">
        {t(`blocklist.source.${entry.source}`, entry.source)}
      </td>
      <td className="py-3 px-4 text-caption text-muted">
        {entry.createdBy ?? '—'}
      </td>
      <td className="py-3 px-4 text-caption text-muted">
        {new Date(entry.createdAt).toLocaleDateString(i18n.language)}
      </td>
      <td className="py-3 px-4 text-end">
        {confirming ? (
          <span className="inline-flex gap-2">
            <button
              data-testid={`bl-confirm-remove-${entry.id}`}
              className="text-caption text-red-500 hover:underline"
              onClick={handleRemove}
              disabled={loading}
            >
              {t('blocklist.remove.yes')}
            </button>
            <button
              className="text-caption text-muted hover:underline"
              onClick={() => setConfirming(false)}
            >
              {t('blocklist.remove.no')}
            </button>
          </span>
        ) : (
          <button
            data-testid={`bl-remove-${entry.id}`}
            className="text-caption text-muted hover:text-red-500"
            onClick={() => setConfirming(true)}
          >
            {t('blocklist.remove.yes')}
          </button>
        )}
      </td>
    </tr>
  )
}

// ── Page ──────────────────────────────────────────────────────────────────────

export default function Blocklist() {
  const { t } = useTranslation()
  const [entries,  setEntries]  = useState<BlocklistEntry[]>([])
  const [loading,  setLoading]  = useState(true)
  const [error,    setError]    = useState('')
  const [showAdd,  setShowAdd]  = useState(false)

  async function load() {
    setLoading(true)
    setError('')
    try {
      const data = await listBlocklist()
      setEntries(data)
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() }, [])

  return (
    <div className="max-w-4xl mx-auto px-4 py-8">
      <div className="flex items-start justify-between mb-6">
        <div>
          <h1 className="text-h2 font-bold">{t('blocklist.title')}</h1>
          <p className="text-caption text-muted mt-1">{t('blocklist.subtitle')}</p>
        </div>
        <button
          data-testid="bl-add-btn"
          className="btn-primary"
          onClick={() => setShowAdd(true)}
        >
          {t('blocklist.addButton')}
        </button>
      </div>

      {error && <p className="text-caption text-red-500 mb-4">{error}</p>}

      {loading ? (
        <p className="text-caption text-muted">{t('common.loading', 'Loading…')}</p>
      ) : entries.length === 0 ? (
        <p className="text-caption text-muted">{t('blocklist.empty')}</p>
      ) : (
        <div className="bg-surface rounded-2xl border border-line overflow-hidden">
          <table className="w-full text-start">
            <thead className="bg-elevated">
              <tr>
                <th className="py-2 px-4 text-caption text-muted font-medium text-start">{t('blocklist.col.phone')}</th>
                <th className="py-2 px-4 text-caption text-muted font-medium text-start">{t('blocklist.col.reason')}</th>
                <th className="py-2 px-4 text-caption text-muted font-medium text-start">Source</th>
                <th className="py-2 px-4 text-caption text-muted font-medium text-start">{t('blocklist.col.addedBy')}</th>
                <th className="py-2 px-4 text-caption text-muted font-medium text-start">{t('blocklist.col.addedAt')}</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {entries.map(e => (
                <BlocklistRow key={e.id} entry={e} onRemoved={load} />
              ))}
            </tbody>
          </table>
        </div>
      )}

      {showAdd && <AddModal onAdded={load} onClose={() => setShowAdd(false)} />}
    </div>
  )
}
