import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Download } from 'lucide-react'
import { CustomerDataRequest, downloadCustomerDataRequest, listCustomerDataRequests } from '../../api'
import { Badge, Button, Skeleton } from '../../components/ui'

// GDPR build A — Settings › Privacy (owner only). Shopify customer data requests: each one is
// downloadable as a JSON export for 30 days, then it expires. Traced never contacts the customer —
// the owner sends the file. No customer data is shown here beyond Shopify's customer id.

export default function PrivacyTab() {
  const { t, i18n } = useTranslation()
  const [rows, setRows] = useState<CustomerDataRequest[] | null>(null)
  const [error, setError] = useState(false)
  const [busy, setBusy] = useState<string | null>(null)
  const [rowError, setRowError] = useState<{ id: string; message: string } | null>(null)

  function load() {
    listCustomerDataRequests()
      .then(r => { setRows(r ?? []); setError(false) })
      .catch(() => setError(true))
  }

  useEffect(load, [])

  async function download(id: string) {
    setBusy(id)
    setRowError(null)
    try {
      await downloadCustomerDataRequest(id)
      load()
    } catch (e) {
      const expired = e instanceof Error && e.message === '410'
      setRowError({ id, message: t(expired ? 'settings.privacy.expiredError' : 'settings.privacy.downloadError') })
      if (expired) load()
    } finally {
      setBusy(null)
    }
  }

  const date = (iso: string) =>
    new Date(iso).toLocaleDateString(i18n.language === 'ar' ? 'ar-EG' : 'en-GB',
      { day: 'numeric', month: 'short', year: 'numeric' })

  return (
    <div className="space-y-4 max-w-3xl" data-testid="privacy-settings">
      <div>
        <h2 className="text-h3 text-primary">{t('settings.privacy.title')}</h2>
        <p className="text-small text-muted mt-1">{t('settings.privacy.subtitle')}</p>
      </div>

      {error && (
        <div role="alert" className="text-small text-danger bg-danger/10 border border-danger/25 rounded px-3 py-2">
          {t('settings.privacy.loadError')}
        </div>
      )}

      {rows === null && !error && <Skeleton className="h-32 rounded-2xl" />}

      {rows !== null && rows.length === 0 && (
        <p className="text-small text-muted rounded-xl border border-line px-4 py-6 text-center">
          {t('settings.privacy.empty')}
        </p>
      )}

      {rows !== null && rows.length > 0 && (
        <ul className="divide-y divide-line rounded-xl border border-line">
          {rows.map(r => (
            <li key={r.id} className="flex flex-wrap items-center gap-3 px-4 py-3" data-testid={`data-request-${r.id}`}>
              <div className="flex-1 min-w-[12rem]">
                <p className="text-body font-semibold text-primary">
                  {t('settings.privacy.received', { date: date(r.createdAt) })}
                </p>
                <p className="text-small text-muted">
                  {t('settings.privacy.customer', { id: r.shopifyCustomerId ?? '—' })}
                  {' · '}
                  {t('settings.privacy.orders', { count: r.ordersRequested })}
                  {' · '}
                  {r.available
                    ? t('settings.privacy.availableUntil', { date: date(r.expiresAt) })
                    : t('settings.privacy.expiredOn', { date: date(r.expiresAt) })}
                </p>
                {rowError?.id === r.id && (
                  <p role="alert" className="text-small text-danger mt-1">{rowError.message}</p>
                )}
              </div>
              {!r.available ? (
                <Badge tone="neutral" label={t('settings.privacy.expired')} />
              ) : (
                <>
                  {r.status === 'downloaded' && <Badge tone="success" label={t('settings.privacy.downloaded')} />}
                  <Button size="sm" variant={r.status === 'downloaded' ? 'secondary' : 'primary'}
                          iconStart={Download} loading={busy === r.id} disabled={busy !== null}
                          onClick={() => download(r.id)}>
                    {t('settings.privacy.download')}
                  </Button>
                </>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
