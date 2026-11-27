import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { HelpLink } from '../../help/HelpLink'
import { ChevronLeft, Download, Search, Trash2 } from 'lucide-react'
import { getRoleFromToken } from '../../api'
import {
  BazarError, deleteBazarCustomers, exportBazarCustomers, previewBazarCustomerDelete, searchBazarCustomers,
  type BazarCustomerDeleteCounts, type BazarCustomerMatch,
} from '../../bazar/api'
import { Button, Checkbox, Input, Modal } from '../../components/ui'
import { amount } from './r1/parts'

/**
 * /bazar/customers — a customer asks "what do you have on me?" or "delete my details". Find them across every bazar
 * (phone in any usual shape, or part of a name), tick one or more results (one person's phone and name-only entries
 * together), Export CSV (owner / manager) or Delete their name and phone (owner only — a preview count first; the
 * sales, amounts and items stay). Every call is a POST: a phone never goes in a URL.
 */
export default function BazarCustomerDetails() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const isOwner = getRoleFromToken() === 'owner'
  const [q, setQ] = useState('')
  const [results, setResults] = useState<{ q: string; customers: BazarCustomerMatch[]; more: boolean } | null>(null)
  const [picked, setPicked] = useState<string[]>([])
  const [busy, setBusy] = useState<'search' | 'export' | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [confirming, setConfirming] = useState(false)

  const message = (e: unknown) => e instanceof BazarError ? e.message_(i18n.language) : t('bazar.customerData.failed')

  async function run(query: string, keepNotice = false) {
    setBusy('search')
    setError(null)
    if (!keepNotice) setNotice(null)
    try {
      const r = await searchBazarCustomers(query)
      setResults({ q: query, ...r })
      setPicked([])
    } catch (e) {
      if (e instanceof BazarError && e.code === 'BAZAR_NOT_ENABLED') { navigate('/overview', { replace: true }); return }
      setError(message(e))
    } finally {
      setBusy(null)
    }
  }

  function onSearch(e: FormEvent) {
    e.preventDefault()
    if (q.trim()) void run(q.trim())
  }

  async function onExport() {
    setBusy('export')
    setError(null)
    try {
      await exportBazarCustomers(picked, 'bazar-customer-details.csv')
    } catch (e) {
      setError(message(e))
    } finally {
      setBusy(null)
    }
  }

  const toggle = (key: string, on: boolean) =>
    setPicked(p => on ? [...p.filter(k => k !== key), key] : p.filter(k => k !== key))

  return (
    <div className="flex flex-col gap-5 max-w-[960px]" data-testid="bazar-customer-details">
      <div className="flex flex-col gap-1">
        <Link to="/bazar" className="inline-flex items-center gap-1 text-small text-muted hover:text-primary w-fit">
          <ChevronLeft size={14} aria-hidden className="rtl:rotate-180" />{t('bazar.breadcrumb')}
        </Link>
        <h1 className="text-[28px] leading-9 font-bold tracking-[-0.02em] text-primary">{t('bazar.customerData.title')}</h1>
        <p className="text-body text-muted max-w-[640px]">
          {t(isOwner ? 'bazar.customerData.introOwner' : 'bazar.customerData.introManager')}
        </p>
        <HelpLink screen="bazarCustomers" className="self-start" />
      </div>

      <form onSubmit={onSearch} className="flex gap-2 items-start flex-wrap" role="search">
        <div className="flex-1 min-w-[220px] max-w-[420px]">
          <Input value={q} onChange={e => setQ(e.target.value)} iconStart={Search} maxLength={80}
            aria-label={t('bazar.customerData.searchLabel')} placeholder={t('bazar.customerData.searchPlaceholder')}
            data-testid="customer-search" />
        </div>
        <Button type="submit" loading={busy === 'search'} disabled={!q.trim()}>{t('bazar.customerData.search')}</Button>
      </form>

      {error && <p role="alert" className="text-body text-critical" data-testid="customer-error">{error}</p>}
      {notice && <p role="status" className="text-body text-success" data-testid="customer-notice">{notice}</p>}

      {results && results.customers.length === 0 && (
        <p className="text-body text-muted" data-testid="customer-none">{t('bazar.customerData.none', { q: results.q })}</p>
      )}

      {results && results.customers.length > 0 && (
        <section className="flex flex-col gap-3">
          <p className="text-small text-muted">{t('bazar.customerData.tickHint')}</p>
          <ul className="flex flex-col border border-line rounded-xl divide-y divide-line bg-surface" data-testid="customer-results">
            {results.customers.map(c => (
              <li key={c.key} className="px-4 py-3" data-testid="customer-row">
                <Checkbox checked={picked.includes(c.key)} onChange={on => toggle(c.key, on)} label={
                  <span className="flex flex-col gap-0.5">
                    <span className="font-semibold">
                      {c.name ?? t('bazar.customerData.noName')}
                      {c.phone && <span dir="ltr" className="ms-2 font-normal tabular-nums text-muted">{c.phone}</span>}
                    </span>
                    {c.names.length > 1 && (
                      <span className="text-small text-muted">{t('bazar.customerData.alsoNamed', { names: c.names.join(', ') })}</span>
                    )}
                    <span className="text-small text-muted">
                      {t('bazar.customerData.summary', {
                        count: c.sales, bazars: c.bazars, pieces: c.pieces, total: amount(c.total),
                        last: new Date(c.last_visit).toLocaleDateString(i18n.language, { dateStyle: 'medium' }),
                      })}
                    </span>
                  </span>
                } />
              </li>
            ))}
          </ul>
          {results.more && <p className="text-small text-muted">{t('bazar.customerData.more')}</p>}
          <div className="flex gap-2 flex-wrap">
            <Button variant="secondary" iconStart={Download} disabled={picked.length === 0} loading={busy === 'export'}
              onClick={onExport}>
              {t('bazar.customerData.export', { count: picked.length })}
            </Button>
            {isOwner && (
              <Button variant="danger" iconStart={Trash2} disabled={picked.length === 0} onClick={() => setConfirming(true)}>
                {t('bazar.customerData.delete', { count: picked.length })}
              </Button>
            )}
          </div>
        </section>
      )}

      {confirming && (
        <DeleteDialog keys={picked} onClose={() => setConfirming(false)} onDone={counts => {
          setConfirming(false)
          setNotice(t('bazar.customerData.deleted', { count: counts.rows, bazars: counts.bazars }))
          void run(results?.q ?? q.trim(), true)
        }} />
      )}
    </div>
  )
}

function DeleteDialog({ keys, onClose, onDone }: {
  keys: string[]
  onClose: () => void
  onDone: (c: BazarCustomerDeleteCounts) => void
}) {
  const { t, i18n } = useTranslation()
  const [preview, setPreview] = useState<BazarCustomerDeleteCounts | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState<'preview' | 'delete' | null>(null)

  async function load(changedNote = false) {
    setLoading('preview')
    try {
      setPreview(await previewBazarCustomerDelete(keys))
      setError(changedNote ? t('bazar.customerData.changed') : null)
    } catch (e) {
      setPreview(null)
      setError(e instanceof BazarError ? e.message_(i18n.language) : t('bazar.customerData.failed'))
    } finally {
      setLoading(null)
    }
  }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { void load() }, [])

  async function confirm() {
    if (!preview) return
    setLoading('delete')
    try {
      onDone(await deleteBazarCustomers(keys, preview.rows))
    } catch (e) {
      if (e instanceof BazarError && e.code === 'BAZAR_CUSTOMER_CHANGED') { await load(true); return }
      setError(e instanceof BazarError ? e.message_(i18n.language) : t('bazar.customerData.failed'))
      setLoading(null)
    }
  }

  return (
    <Modal title={t('bazar.customerData.confirmTitle')} onClose={onClose}>
      <div className="flex flex-col gap-4" data-testid="customer-delete-dialog">
        {!preview && !error && <p className="text-body text-muted">{t('bazar.customerData.checking')}</p>}
        {preview && (
          <p className="text-body text-primary" data-testid="customer-delete-count">
            {t('bazar.customerData.confirmBody', { count: preview.rows, bazars: preview.bazars, receipts: preview.receipts })}
          </p>
        )}
        {preview && <p className="text-body text-muted">{t('bazar.customerData.confirmKeep')}</p>}
        {error && <p role="alert" className="text-body text-critical">{error}</p>}
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>{t('bazar.customerData.cancel')}</Button>
          <Button variant="danger" disabled={!preview || loading === 'preview'} loading={loading === 'delete'}
            onClick={confirm}>
            {t('bazar.customerData.confirmDelete')}
          </Button>
        </div>
      </div>
    </Modal>
  )
}
