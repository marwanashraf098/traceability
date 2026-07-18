import { useEffect, useState, useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { request, releaseOrderHold, cancelOrder as apiCancelOrder } from '../api'
import {
  Badge, Button, EmptyState, Modal, Select, type SelectOption,
  SeverityBadge, Skeleton, useToast,
} from '../components/ui'

interface ExceptionItem {
  type: string
  severity: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW'
  subjectKey: string
  subject_type: string
  piece_id?: string
  barcode?: string
  order_id?: string
  order_number?: string
  shipment_id?: string
  tracking_number?: string
  unlinked_id?: number
  ndr_code?: number
  ndr_description?: string
  number_of_attempts?: number
  hold_reason?: string
  shipment_state?: string
  ageSeconds: number
  descriptionEn: string
  descriptionAr: string
  suggestedAction: string
  actionUrl: string
  releaseUrl?: string
  cancelUrl?: string
  diffJson?: string
}

interface ExceptionPage { total: number; page: number; size: number; items: ExceptionItem[] }

const TYPE_LABELS: Record<string, { en: string; ar: string }> = {
  lost:               { en: 'Lost',             ar: 'مفقود' },
  never_received:     { en: 'Never Received',   ar: 'لم يُستلم' },
  unmatched_delivery: { en: 'Unmatched',        ar: 'غير مرتبط' },
  blocked_customer:   { en: 'On Hold',          ar: 'معلَّق' },
  stuck_shipment:     { en: 'Stuck',            ar: 'متوقف' },
  unexpected_return:  { en: 'Unexp. Return',    ar: 'إرجاع غير متوقع' },
  delivery_limbo:     { en: 'Limbo',            ar: 'في الانتظار' },
  ndr_failed:         { en: 'NDR Failed',       ar: 'فشل التوصيل' },
  guided_unpack:      { en: 'Unpack Required',  ar: 'فك التعبئة' },
}

const ALL_TYPES      = Object.keys(TYPE_LABELS)
const ALL_SEVERITIES = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']

function formatAge(secs: number) {
  if (secs < 60)    return `${secs}s`
  if (secs < 3600)  return `${Math.floor(secs / 60)}m`
  if (secs < 86400) return `${Math.floor(secs / 3600)}h`
  return `${Math.floor(secs / 86400)}d`
}

// ── Resolve dialog ────────────────────────────────────────────────────────────

function ResolveDialog({ item, onClose, onResolved }: { item: ExceptionItem; onClose: () => void; onResolved: () => void }) {
  const { i18n } = useTranslation()
  const { toast } = useToast()
  const isAr = i18n.language === 'ar'
  const [note, setNote]       = useState('')
  const [loading, setLoading] = useState(false)

  // Resolve API call — logic untouched, toast added on success
  async function submit() {
    setLoading(true)
    try {
      await request<void>('/exceptions/resolve', {
        method: 'POST',
        body: JSON.stringify({ exceptionType: item.type, subjectKey: item.subjectKey, note }),
      })
      toast({ tone: 'success', message: isAr ? 'تم معالجة الاستثناء' : 'Exception resolved' })
      onResolved()
    } finally {
      setLoading(false)
    }
  }

  return (
    <Modal title={isAr ? 'تأكيد المعالجة' : 'Acknowledge exception'} onClose={onClose}>
      <p className="text-body text-muted mb-4">
        {isAr ? item.descriptionAr : item.descriptionEn}
      </p>
      <textarea
        className="input resize-none w-full mb-4"
        rows={3}
        placeholder={isAr ? 'ملاحظة (اختياري)' : 'Resolution note (optional)'}
        value={note}
        onChange={e => setNote(e.target.value)}
      />
      <div className="flex gap-3 justify-end">
        <Button variant="secondary" size="sm" onClick={onClose}>
          {isAr ? 'إلغاء' : 'Cancel'}
        </Button>
        <Button loading={loading} size="sm" onClick={submit}>
          {isAr ? 'تأكيد المعالجة' : 'Mark resolved'}
        </Button>
      </div>
    </Modal>
  )
}

// ── Exception row ─────────────────────────────────────────────────────────────

function ExceptionRow({ item, onAck, onAction }: { item: ExceptionItem; onAck: (item: ExceptionItem) => void; onAction?: () => void }) {
  const { t, i18n } = useTranslation()
  const isAr = i18n.language === 'ar'
  const navigate = useNavigate()
  const typeLabel = TYPE_LABELS[item.type]

  return (
    <div className="card p-4 flex items-start gap-4">
      {/*
        Severity dot — colours aligned with SEV_TONE in ui.tsx:
        CRITICAL→bg-danger, HIGH→bg-warning, MEDIUM→bg-info, LOW→bg-muted
        (was bg-brand for MEDIUM, bg-accent for LOW — neither is a DS token)
      */}
      <div className="mt-1.5 flex-shrink-0">
        <span className={`w-2.5 h-2.5 rounded-full block ${
          item.severity === 'CRITICAL' ? 'bg-danger' :
          item.severity === 'HIGH'     ? 'bg-warning' :
          item.severity === 'MEDIUM'   ? 'bg-info' : 'bg-muted'
        }`} />
      </div>

      {/* Content */}
      <div className="flex-1 min-w-0">
        <div className="flex flex-wrap items-center gap-2 mb-1.5">
          <SeverityBadge severity={item.severity} />
          <Badge
            tone="neutral"
            label={isAr ? (typeLabel?.ar ?? item.type) : (typeLabel?.en ?? item.type)}
          />
          <span className="text-caption text-muted font-mono">{formatAge(item.ageSeconds)}</span>
        </div>
        <p className="text-body text-primary leading-snug">
          {isAr ? item.descriptionAr : item.descriptionEn}
        </p>
        <div className="flex flex-wrap gap-x-4 gap-y-0.5 mt-1.5">
          {item.order_number && (
            <span className="text-small text-muted">
              {isAr ? 'طلب' : 'Order'}: <span className="font-mono text-muted">{item.order_number}</span>
            </span>
          )}
          {/* Tracking and barcode — font-mono per spec */}
          {item.tracking_number && (
            <span className="text-small text-muted font-mono">{item.tracking_number}</span>
          )}
          {item.barcode && (
            <span className="text-small text-muted font-mono">{item.barcode}</span>
          )}
          {item.ndr_description && (
            <span className="text-small text-muted">NDR: {item.ndr_description}</span>
          )}
        </div>
      </div>

      {/* Actions */}
      <div className="flex flex-col gap-2 flex-shrink-0">
        {item.type === 'blocked_customer' && item.order_id && (
          <>
            {/*
              Release/Cancel kept as raw <button> — both carry data-testid and
              Button does not spread arbitrary props.
            */}
            <button
              data-testid={`exc-release-${item.order_id}`}
              className="btn-primary btn text-small px-3 py-1.5"
              onClick={async () => {
                try { await releaseOrderHold(item.order_id!); onAction?.() } catch { /* noop */ }
              }}
            >
              {t('blocklist.exception.releaseButton', 'Release')}
            </button>
            <button
              data-testid={`exc-cancel-${item.order_id}`}
              className="btn-outline btn text-small px-3 py-1.5 text-danger border-danger/30"
              onClick={async () => {
                try { await apiCancelOrder(item.order_id!); onAction?.() } catch { /* noop */ }
              }}
            >
              {t('blocklist.exception.cancelButton', 'Cancel')}
            </button>
          </>
        )}
        {item.actionUrl && item.type !== 'blocked_customer' && (
          <Button size="sm" onClick={() => navigate(item.actionUrl)}>
            {isAr ? 'الإجراء' : 'Go →'}
          </Button>
        )}
        <Button variant="secondary" size="sm" onClick={() => onAck(item)}>
          {isAr ? 'معالجة' : 'Resolve'}
        </Button>
      </div>
    </div>
  )
}

// ── Main page ─────────────────────────────────────────────────────────────────

export default function ExceptionsPage() {
  const { t, i18n } = useTranslation()
  const isAr = i18n.language === 'ar'

  const [data,          setData]          = useState<ExceptionPage | null>(null)
  const [typeFilter,    setTypeFilter]    = useState('')
  const [sevFilter,     setSevFilter]     = useState('')
  const [page,          setPage]          = useState(0)
  const [loading,       setLoading]       = useState(false)
  const [resolvingItem, setResolvingItem] = useState<ExceptionItem | null>(null)

  // Filter state and query params — unchanged
  const load = useCallback(async () => {
    setLoading(true)
    try {
      const params = new URLSearchParams({ page: String(page), size: '50' })
      if (typeFilter) params.set('type', typeFilter)
      if (sevFilter)  params.set('severity', sevFilter)
      const result = await request<ExceptionPage>(`/exceptions?${params}`)
      setData(result)
    } finally {
      setLoading(false)
    }
  }, [page, typeFilter, sevFilter])

  useEffect(() => { load() }, [load])

  const handleResolved = () => { setResolvingItem(null); load() }

  // DS Select options — empty-string value = "All" (no filter applied)
  const severityOptions: SelectOption[] = [
    { value: '', label: isAr ? 'كل الأولويات' : 'All severities' },
    ...ALL_SEVERITIES.map(s => ({ value: s, label: s })),
  ]
  const typeOptions: SelectOption[] = [
    { value: '', label: isAr ? 'كل الأنواع' : 'All types' },
    ...ALL_TYPES.map(type => ({
      value: type,
      label: isAr ? (TYPE_LABELS[type]?.ar ?? type) : (TYPE_LABELS[type]?.en ?? type),
    })),
  ]

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-h1 text-primary">{t('exceptions.title')}</h1>
        <Button variant="secondary" size="sm" onClick={load}>
          ↻ {isAr ? 'تحديث' : 'Refresh'}
        </Button>
      </div>

      {/* Filters — Select components; filter state and query params unchanged */}
      <div className="flex flex-wrap gap-2">
        <Select
          value={sevFilter}
          onChange={value => { setSevFilter(value); setPage(0) }}
          options={severityOptions}
        />
        <Select
          value={typeFilter}
          onChange={value => { setTypeFilter(value); setPage(0) }}
          options={typeOptions}
        />
      </div>

      {loading ? (
        <div className="space-y-3">
          {Array.from({ length: 5 }).map((_, i) => (
            <Skeleton key={i} className="h-24 rounded-2xl" />
          ))}
        </div>
      ) : data?.items.length === 0 ? (
        <EmptyState message={isAr ? 'لا توجد استثناءات' : 'No exceptions — all clear'} icon="✓" />
      ) : (
        <div className="space-y-3">
          {data?.items.map((item, i) => (
            <ExceptionRow key={i} item={item} onAck={setResolvingItem} onAction={load} />
          ))}
        </div>
      )}

      {/* Pagination — unchanged logic */}
      {data && data.total > data.size && (
        <div className="flex gap-2 justify-end">
          <Button
            variant="secondary"
            size="sm"
            disabled={page === 0}
            onClick={() => setPage(p => p - 1)}
          >
            {isAr ? 'السابق' : 'Prev'}
          </Button>
          <Button
            variant="secondary"
            size="sm"
            disabled={(page + 1) * data.size >= data.total}
            onClick={() => setPage(p => p + 1)}
          >
            {isAr ? 'التالي' : 'Next'}
          </Button>
        </div>
      )}

      {resolvingItem && (
        <ResolveDialog
          item={resolvingItem}
          onClose={() => setResolvingItem(null)}
          onResolved={handleResolved}
        />
      )}
    </div>
  )
}
