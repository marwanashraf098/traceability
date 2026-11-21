import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Loader2 } from 'lucide-react'
import { BazarError, findBazarSales, type BazarFoundSales, type BazarSale } from '../../../bazar/api'
import { sizeName, type BazarCatalog, type BazarCatalogProduct, type BazarCatalogSize } from '../../../bazar/catalog'
import { formatMoney, saleTime, sellErrorText } from '../../../bazar/format'
import { Input } from '../../../components/ui'
import { PickSheet } from './PickSheet'
import { Sheet } from './shared'

/**
 * Bazar POS B7c — a return / exchange without a label: pick the product + size (every size, sold out included), then
 * optionally the customer's phone or the receipt number, then Find. Never guesses: one match is shown to confirm,
 * several are listed (time, seller, price, customer — masked for a worker) and the seller picks one, none says so.
 */
export function FindSaleSheet({ eventId, catalog, onPick, onClose }: {
  eventId: string
  catalog: BazarCatalog | null
  onPick: (sale: BazarSale) => void
  onClose: () => void
}) {
  const [size, setSize] = useState<{ product: BazarCatalogProduct; size: BazarCatalogSize } | null>(null)
  if (!size) {
    return <PickSheet catalog={catalog} mode="find" onPick={(product, s) => setSize({ product, size: s })} onClose={onClose} />
  }
  return <Matches eventId={eventId} product={size.product} size={size.size} onPick={onPick}
                  onBack={() => setSize(null)} onClose={onClose} />
}

function Matches({ eventId, product, size, onPick, onBack, onClose }: {
  eventId: string
  product: BazarCatalogProduct
  size: BazarCatalogSize
  onPick: (sale: BazarSale) => void
  onBack: () => void
  onClose: () => void
}) {
  const { t, i18n } = useTranslation()
  const [phone, setPhone] = useState('')
  const [receipt, setReceipt] = useState('')
  const [result, setResult] = useState<BazarFoundSales | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const name = `${product.title} · ${sizeName(product, size)}`

  async function search() {
    setBusy(true)
    setError(null)
    try {
      setResult(await findBazarSales(eventId, size.variant_id, { phone, receipt }))
    } catch (e) {
      if (e instanceof BazarError) {
        const { title, detail } = sellErrorText(t, i18n.language, e)
        setError(detail ? `${title} — ${detail}` : title)
      } else {
        setError(t('bazar.find.failed'))
      }
    } finally { setBusy(false) }
  }

  const sales = result?.sales ?? []
  return (
    <Sheet title={t('bazar.find.title')} onClose={onClose} testId="bazar-find-sale">
      <div className="flex items-center justify-between gap-2">
        <span className="font-semibold text-primary truncate" data-testid="find-size">{name}</span>
        <button type="button" onClick={onBack} className="min-h-[40px] px-2 text-small font-semibold text-trace-blue shrink-0">
          {t('bazar.find.change')}
        </button>
      </div>
      <form className="flex flex-col gap-2" onSubmit={e => { e.preventDefault(); search() }}>
        <div className="grid grid-cols-2 gap-2">
          <Input value={phone} onChange={e => setPhone(e.target.value)} inputMode="tel" dir="ltr" autoComplete="off"
                 placeholder={t('bazar.find.phone')} aria-label={t('bazar.find.phone')} data-testid="find-phone" />
          <Input value={receipt} onChange={e => setReceipt(e.target.value)} dir="ltr" autoComplete="off"
                 placeholder={t('bazar.find.receipt')} aria-label={t('bazar.find.receipt')} data-testid="find-receipt" />
        </div>
        <button type="submit" disabled={busy} data-testid="find-go"
          className="h-11 rounded-lg bg-trace-blue text-white font-semibold flex items-center justify-center gap-2 disabled:opacity-60">
          {busy && <Loader2 size={16} className="animate-spin" aria-hidden />}{t('bazar.find.go')}
        </button>
        <span className="text-small text-muted">{t('bazar.find.filtersHint')}</span>
      </form>

      {error && <p role="alert" className="text-small text-critical">{error}</p>}
      {result && sales.length === 0 && (
        <p role="status" className="rounded-xl border border-line px-3 py-3 text-body text-primary" data-testid="find-none">
          {t('bazar.find.none', { name })}
        </p>
      )}
      {result && sales.length === 1 && (
        <section className="flex flex-col gap-2" data-testid="find-one">
          <span className="text-small font-medium text-muted">{t('bazar.find.oneTitle')}</span>
          <SaleRow sale={sales[0]} />
          <button type="button" onClick={() => onPick(sales[0])} data-testid="find-use"
            className="h-12 rounded-xl bg-[#16A34A] text-white font-bold">{t('bazar.find.useThis')}</button>
        </section>
      )}
      {result && sales.length > 1 && (
        <section className="flex flex-col gap-2" data-testid="find-several">
          <span className="text-small font-medium text-muted">{t('bazar.find.severalTitle', { count: result.count })}</span>
          <ul className="flex flex-col gap-1.5">
            {sales.map(s => (
              <li key={s.id}>
                <button type="button" onClick={() => onPick(s)} data-testid="find-row" className="w-full text-start">
                  <SaleRow sale={s} />
                </button>
              </li>
            ))}
          </ul>
          {result.count > sales.length && (
            <span className="text-small text-muted">{t('bazar.find.more', { shown: sales.length, count: result.count })}</span>
          )}
        </section>
      )}
    </Sheet>
  )
}

/** Time · seller · price · customer (masked for a worker, as the server sends it). */
function SaleRow({ sale }: { sale: BazarSale }) {
  const { t } = useTranslation()
  const customer = [sale.customer_name, sale.customer_phone].filter(Boolean).join(' · ')
  return (
    <span className="flex items-center gap-3 px-3 py-2.5 rounded-xl border border-line bg-surface min-h-[52px]">
      <span className="font-mono text-small text-muted w-11 shrink-0" dir="ltr">{saleTime(sale.sold_at)}</span>
      <span className="flex-1 min-w-0 flex flex-col">
        <span className="font-medium text-primary truncate">{sale.sale_number} · {sale.seller_name}</span>
        <span className="text-small text-muted truncate" dir="auto">{customer || t('bazar.find.noCustomer')}</span>
      </span>
      <span className="font-semibold tabular-nums shrink-0">{formatMoney(sale.net_price ?? sale.price).replace(/^EGP /, '')}</span>
    </span>
  )
}
