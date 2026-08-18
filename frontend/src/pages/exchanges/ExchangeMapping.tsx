import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { CheckCircle2 } from 'lucide-react'
import { getExchanges, mapExchange, ExchangeSummary, CatalogProduct, CatalogVariant } from '../../api'
import { Alert, Badge, Button, ProductThumb, Skeleton } from '../../components/ui'
import ExchangeVariantPicker from './ExchangeVariantPicker'

interface SelectedVariant {
  variant: CatalogVariant
  product: CatalogProduct
}

/** Trims TRAILING whitespace only — raw courier text is shown verbatim otherwise, never parsed. */
function trimTrailing(s: string | null): string {
  return (s ?? '').replace(/\s+$/, '')
}

export default function ExchangeMapping() {
  const { t } = useTranslation()
  const { id } = useParams<{ id: string }>()

  const [exchange, setExchange] = useState<ExchangeSummary | null>(null)
  const [loading, setLoading]   = useState(true)
  const [loadError, setLoadError] = useState('')

  const [outbound, setOutbound] = useState<SelectedVariant | null>(null)
  const [inbound, setInbound]   = useState<SelectedVariant | null>(null)
  const [pickerLeg, setPickerLeg] = useState<'outbound' | 'inbound' | null>(null)

  const [confirming, setConfirming] = useState(false)
  const [confirmError, setConfirmError] = useState('')
  const [mappedOrderNumber, setMappedOrderNumber] = useState<string | null>(null)

  useEffect(() => {
    if (!id) return
    getExchanges()
      .then(rows => {
        const found = rows.find(r => r.id === id)
        if (!found) { setLoadError(t('exchange.mapping.notFound')); return }
        setExchange(found)
      })
      .catch(() => setLoadError(t('common.error')))
      .finally(() => setLoading(false))
  }, [id, t])

  async function handleConfirm() {
    if (!id || !outbound || !inbound) return
    setConfirming(true); setConfirmError('')
    try {
      const result = await mapExchange(id, outbound.variant.id, inbound.variant.id)
      setMappedOrderNumber(`EXC-${exchange?.tracking_number ?? ''}` || result.orderId)
    } catch (e: unknown) {
      setConfirmError((e as Error).message ?? t('common.error'))
    } finally {
      setConfirming(false)
    }
  }

  if (loading) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-8 w-64 rounded-xl" />
        <Skeleton className="h-48 rounded-2xl" />
        <Skeleton className="h-48 rounded-2xl" />
      </div>
    )
  }

  if (loadError || !exchange) {
    return <Alert tone="critical" title={loadError || t('exchange.mapping.notFound')} />
  }

  if (mappedOrderNumber) {
    return (
      <div className="card p-8 text-center space-y-4" data-testid="exchange-mapped-success">
        <CheckCircle2 className="mx-auto text-success" size={40} />
        <h2 className="text-h3 text-primary">{t('exchange.mapping.successTitle')}</h2>
        <p className="text-body text-muted">
          {t('exchange.mapping.successBody', { order: mappedOrderNumber })}
        </p>
        <Link to="/exceptions" className="text-trace-blue hover:underline text-small">
          {t('exchange.mapping.backToExceptions')}
        </Link>
      </div>
    )
  }

  return (
    <div className="space-y-4 max-w-3xl">
      <div>
        <h1 className="text-h2 text-primary">{t('exchange.mapping.title', { tracking: exchange.tracking_number })}</h1>
        {(exchange.customer_name || exchange.customer_phone) && (
          <p className="text-small text-muted mt-1">
            {[exchange.customer_name, exchange.customer_phone].filter(Boolean).join(' · ')}
          </p>
        )}
      </div>

      {confirmError && <Alert tone="critical" title={confirmError} />}

      <LegCard
        legLabel={t('exchange.mapping.outboundLabel')}
        legHint={t('exchange.mapping.outboundHint')}
        description={trimTrailing(exchange.outbound_description)}
        descriptionAr={null}
        selected={outbound}
        onSelectClick={() => setPickerLeg('outbound')}
        testId="exchange-leg-outbound"
      />

      <LegCard
        legLabel={t('exchange.mapping.inboundLabel')}
        legHint={t('exchange.mapping.inboundHint')}
        description={trimTrailing(exchange.inbound_description)}
        descriptionAr={trimTrailing(exchange.inbound_description_ar)}
        selected={inbound}
        onSelectClick={() => setPickerLeg('inbound')}
        testId="exchange-leg-inbound"
      />

      <div className="flex justify-end pt-2">
        <Button
          disabled={!outbound || !inbound}
          loading={confirming}
          onClick={handleConfirm}
        >
          {t('exchange.mapping.confirmButton')}
        </Button>
      </div>

      {pickerLeg && (
        <ExchangeVariantPicker
          onClose={() => setPickerLeg(null)}
          onSelect={(variant, product) => {
            if (pickerLeg === 'outbound') setOutbound({ variant, product })
            else setInbound({ variant, product })
            setPickerLeg(null)
          }}
        />
      )}
    </div>
  )
}

function LegCard({
  legLabel, legHint, description, descriptionAr, selected, onSelectClick, testId,
}: {
  legLabel: string
  legHint: string
  description: string
  descriptionAr: string | null
  selected: SelectedVariant | null
  onSelectClick: () => void
  testId: string
}) {
  const { t } = useTranslation()
  return (
    <div className="card p-5 space-y-3" data-testid={testId}>
      <div className="flex items-center gap-2">
        <Badge tone="neutral" label={legLabel} />
        <span className="text-small text-muted">{legHint}</span>
      </div>

      <div className="space-y-1">
        {description && <p className="text-body text-primary">{description}</p>}
        {descriptionAr && <p className="text-body text-primary" dir="rtl">{descriptionAr}</p>}
        <p className="text-caption text-muted italic">{t('exchange.mapping.rawDescriptionNote')}</p>
      </div>

      <div className="flex items-center gap-3 pt-1">
        {selected ? (
          <>
            <ProductThumb src={selected.product.imageUrl} alt={selected.product.title} size={40} cdnWidth={80} />
            <div className="flex-1 min-w-0">
              <div className="text-small font-semibold text-primary truncate">{selected.product.title}</div>
              <div className="text-caption text-muted truncate">{selected.variant.title}</div>
            </div>
            <button
              type="button"
              onClick={onSelectClick}
              className="text-caption text-trace-blue hover:underline flex-shrink-0"
            >
              {t('exchange.mapping.changeVariant')}
            </button>
          </>
        ) : (
          <Button variant="secondary" onClick={onSelectClick}>
            {t('exchange.mapping.selectVariant')}
          </Button>
        )}
      </div>
    </div>
  )
}
