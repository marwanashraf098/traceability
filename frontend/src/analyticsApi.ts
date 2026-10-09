// Analytics API client — one typed function per /api/v1/analytics endpoint (slices 1–10).
// Kept out of api.ts on purpose (that file is edited by every build; this one only by analytics).
// Field names mirror the backend records exactly; BigDecimal arrives as a JSON number,
// LocalDate as 'YYYY-MM-DD', Instant as an ISO string. Every endpoint is OWNER-only.

import { request } from './api'

// ── Shared ───────────────────────────────────────────────────────────────────

export type PeriodPreset = 'today' | '7d' | '30d'

/** The period every analytics endpoint takes: a preset, or an inclusive from/to (Cairo days). */
export interface PeriodParams {
  period?: PeriodPreset
  from?: string
  to?: string
}

export interface Range { from: string; to: string; tz: string }

/** Slices 5/6 wrap their answer with the previous period of the same length (null past 92 days unless compare). */
export interface Compared<T> {
  range: Range
  previousRange: Range | null
  current: T
  previous: T | null
}

type Query = Record<string, string | number | boolean | undefined | null>

export function queryString(q: Query): string {
  const parts: string[] = []
  for (const [k, v] of Object.entries(q)) {
    if (v === undefined || v === null || v === '' || v === false) continue
    parts.push(`${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
  }
  return parts.length ? `?${parts.join('&')}` : ''
}

function periodQuery(p: PeriodParams): Query {
  return p.from && p.to ? { from: p.from, to: p.to } : { period: p.period ?? '30d' }
}

function get<T>(path: string, q: Query = {}, signal?: AbortSignal): Promise<T> {
  return request<T>(`/analytics${path}${queryString(q)}`, { signal })
}

// ── s1/s2 sales ──────────────────────────────────────────────────────────────

export interface VariantSales {
  variantId: string; productId: string; productTitle: string; variantTitle: string; sku: string | null
  imageUrl: string | null; soldUnits: number; grossRevenue: number; approximateLines: number; lastSoldAt: string | null
  deliveredUnits: number; refusedUnits: number; inTransitUnits: number; wijhaUnits: number; notShippedUnits: number
  otherTerminalUnits: number; returnedUnits: number; netSoldUnits: number; returnRate: number | null
  refusalRate: number | null; deliveredRevenue: number; returnedRevenue: number; netRevenue: number
}
export interface SalesTotals {
  soldUnits: number; grossRevenue: number; orders: number; approximateLines: number; deliveredUnits: number
  refusedUnits: number; inTransitUnits: number; wijhaUnits: number; notShippedUnits: number; otherTerminalUnits: number
  returnedUnits: number; netSoldUnits: number; returnRate: number | null; refusalRate: number | null
  deliveredRevenue: number; returnedRevenue: number; netRevenue: number; deliveredOrders: number; refusedOrders: number
  wijhaOrders: number; returnsOnUndeliveredOrders: number; unverifiedNoRestockLines: number
}
export interface VariantSalesResponse { range: Range; totals: SalesTotals; variants: VariantSales[] }

export interface VariantDaily { variantId: string; totalUnits: number; units: number[] }
export interface VariantDailyResponse { range: Range; days: string[]; variants: VariantDaily[] }

export interface TopVariant { variantId: string; variantTitle: string; soldUnits: number }
export interface ProductSales {
  productId: string; title: string; imageUrl: string | null; soldUnits: number; grossRevenue: number
  variantCount: number; topVariants: TopVariant[]
}
export interface ProductSalesResponse { range: Range; sort: string; products: ProductSales[] }

export interface CitySales {
  cityId: string | null; nameEn: string | null; nameAr: string | null; orders: number; deliveredOrders: number
  refusedOrders: number; inTransitOrders: number; otherTerminalOrders: number; successRate: number | null
}
export interface CitySalesResponse { range: Range; cities: CitySales[] }

export const getSalesVariants = (p: PeriodParams, signal?: AbortSignal) =>
  get<VariantSalesResponse>('/sales/variants', periodQuery(p), signal)
export const getSalesVariantsDaily = (ids: string[], p: PeriodParams, signal?: AbortSignal) =>
  get<VariantDailyResponse>('/sales/variants/daily', { ids: ids.join(','), ...periodQuery(p) }, signal)
export const getSalesProducts = (p: PeriodParams & { sort?: 'units' | 'revenue'; limit?: number }, signal?: AbortSignal) =>
  get<ProductSalesResponse>('/sales/products', { ...periodQuery(p), sort: p.sort, limit: p.limit }, signal)
export const getSalesCities = (p: PeriodParams, signal?: AbortSignal) =>
  get<CitySalesResponse>('/sales/cities', periodQuery(p), signal)

// ── s3 money ─────────────────────────────────────────────────────────────────

export interface Stage { count: number; value: number; expected: number | null }
export interface AwaitingPayout {
  count: number; deposited: number; nextCashoutDate: string | null
  deliveredNotYetSettled: number; deliveredNotYetSettledEstimate: number | null
}
export interface InBank { shipments: number; deposited: number; payouts: number; lastTransferDate: string | null }
export interface Pipeline {
  range: Range; asOf: string; notFulfilled: Stage; inTransit: Stage; awaitingPayout: AwaitingPayout
  inYourBank: InBank; openOrderDays: number
}

export interface FeeFigure { legs: number; amount: number; estimatedCount: number }
export interface FeeComponents {
  settledLegs: number; shippingFees: number; openingPackageFees: number; collectionFees: number
  insuranceFees: number; flexShipFees: number; promotionDiscount: number; vat: number
}
export interface Fees {
  range: Range; shipping: FeeFigure; failed: FeeFigure; exchange: FeeFigure; returned: FeeFigure; total: FeeFigure
  settledComponents: FeeComponents; costPerSuccessfulDelivery: number | null; deliveredCount: number
  costPerUnsuccessfulDelivery: number | null; refusedCount: number; payoutLagDays: number | null; payoutLagShipments: number
}

export interface ExtraByAwb {
  trackingNumber: string; orderNumber: string | null; type: string; fee: number; estimated: boolean
  reason: string | null; date: string | null; city: string | null; skus: string[]
}
export interface ExtraBySku {
  variantId: string; sku: string | null; productTitle: string; variantTitle: string
  failed: number; exchanges: number; returns: number; extraFees: number
}
export interface ExtraFees {
  range: Range; groupBy: 'sku' | 'awb'; shipments: ExtraByAwb[] | null; skus: ExtraBySku[] | null
  total: number; estimatedCount: number
}

export interface StuckShipment { trackingNumber: string; orderNumber: string | null; lastStatus: string | null; days: number; cod: number | null }
export interface NotPaidShipment { trackingNumber: string; orderNumber: string | null; depositedOn: string | null; deposited: number | null; days: number }
export interface Stuck {
  asOf: string; neverPickedUp: StuckShipment[]; stuckWithBosta: StuckShipment[]; deliveredNotPaid: NotPaidShipment[]
  payoutWeekday: number | null; unresolved: number
}

export interface Payout { transactionId: string; date: string; trackedShipments: number; trackedDeposited: number; bostaBatchTotal: number | null }
export interface Payouts { range: Range; payouts: Payout[] }

export const getPipeline = (p: PeriodParams, signal?: AbortSignal) => get<Pipeline>('/money/pipeline', periodQuery(p), signal)
export const getFees = (p: PeriodParams, signal?: AbortSignal) => get<Fees>('/money/fees', periodQuery(p), signal)
export const getExtraFees = (p: PeriodParams & { groupBy: 'sku' | 'awb' }, signal?: AbortSignal) =>
  get<ExtraFees>('/money/fees/extra', { ...periodQuery(p), groupBy: p.groupBy }, signal)
export const getStuck = (signal?: AbortSignal) => get<Stuck>('/money/stuck', {}, signal)
export const getPayouts = (p: PeriodParams, signal?: AbortSignal) => get<Payouts>('/money/payouts', periodQuery(p), signal)

// ── s5 revenue / delivery / product extras ──────────────────────────────────

export interface DiscountSplit { code: number; automatic: number; other: number; total: number }
export interface Waterfall {
  gross: number; discounts: number; inTransit: number; notShipped: number; otherCarrier: number
  refused: number; otherTerminal: number; returns: number; netRealized: number
}
export interface Funnel { ordered: number; fulfilled: number; delivered: number; paidToYou: number }
export interface RevenueDay { date: string; booked: number; realized: number }
export interface RevenueSummary {
  grossSales: number; discounts: DiscountSplit; booked: number; realized: number; realizedShare: number | null
  orders: number; approximateLines: number; waterfall: Waterfall; funnel: Funnel; daily: RevenueDay[]
}
export interface BreakdownGroup {
  key: string; label: string; labelAr: string | null; booked: number; realized: number; orders: number
  deliveredOrders: number; failedOrders: number; successRate: number | null
}
export type BreakdownBy = 'channel' | 'payment' | 'governorate' | 'productType'
export interface Breakdown { by: string; groups: BreakdownGroup[] }
export interface DiscountRow {
  code: string | null; label: string | null; orders: number; booked: number; discountCost: number
  deliveredOrders: number; failedOrders: number; successRate: number | null; revenuePerCost: number | null
}
export interface Discounts { codes: DiscountRow[]; automatic: DiscountRow | null }
export interface HeatCell { weekday: number; hour: number; orders: number; avgOrders: number | null }
export interface Heatmap { cells: HeatCell[]; weekdayOccurrences: Record<string, number> }

export interface DeliveryWeek { weekStart: string; delivered: number; failed: number; successRate: number | null }
export interface SpeedBucket { bucket: string; orders: number; delivered: number; failed: number; successRate: number | null }
export interface DeliverySummary {
  successRate: number | null; delivered: number; failed: number; lostSalesValue: number
  avgHoursOrderToHanded: number | null; handedOrders: number; avgHoursHandedToDelivered: number | null
  avgHoursHandedToDeliveredCairoGiza: number | null; avgHoursHandedToDeliveredOther: number | null
  weeklyTrend: DeliveryWeek[]; fulfillmentSpeed: SpeedBucket[]
}
export interface FailureReason { reason: string; count: number; share: number | null }
export interface FailureReasons { failedLegs: number; withReason: number; coverage: number | null; reasons: FailureReason[] }

export interface AbcRow {
  variantId: string; sku: string | null; productTitle: string; variantTitle: string; realized: number
  share: number | null; cumulativeShare: number | null; abcClass: 'A' | 'B' | 'C'
}
export interface FailedProduct { productId: string; title: string; orders: number; failedOrders: number; failureRate: number | null }
export interface SizeRow {
  size: string; soldUnits: number; share: number | null; deliveredUnits: number; returnedUnits: number
  returnRate: number | null; exchangedUnits: number
}
export interface SizeCurve { sizes: SizeRow[]; unparseableUnits: number; unparseableValues: string[]; noSizeUnits: number }
export interface BoughtTogether { variantA: string; titleA: string; variantB: string; titleB: string; orders: number }
export interface ProductExtras { abc: AbcRow[]; mostFailed: FailedProduct[]; sizeCurve: SizeCurve; boughtTogether: BoughtTogether[] }

type ComparedQuery = PeriodParams & { compare?: boolean }
const cq = (p: ComparedQuery): Query => ({ ...periodQuery(p), compare: p.compare || undefined })

export const getRevenueSummary = (p: ComparedQuery, signal?: AbortSignal) =>
  get<Compared<RevenueSummary>>('/revenue/summary', cq(p), signal)
export const getRevenueBreakdown = (p: ComparedQuery & { by: BreakdownBy }, signal?: AbortSignal) =>
  get<Compared<Breakdown>>('/revenue/breakdown', { ...cq(p), by: p.by }, signal)
export const getRevenueDiscounts = (p: ComparedQuery, signal?: AbortSignal) =>
  get<Compared<Discounts>>('/revenue/discounts', cq(p), signal)
export const getRevenueHeatmap = (p: ComparedQuery, signal?: AbortSignal) =>
  get<Compared<Heatmap>>('/revenue/heatmap', cq(p), signal)
export const getDeliverySummary = (p: ComparedQuery, signal?: AbortSignal) =>
  get<Compared<DeliverySummary>>('/delivery/summary', cq(p), signal)
export const getFailureReasons = (p: ComparedQuery, signal?: AbortSignal) =>
  get<Compared<FailureReasons>>('/delivery/failure-reasons', cq(p), signal)
export const getProductExtras = (p: ComparedQuery, signal?: AbortSignal) =>
  get<Compared<ProductExtras>>('/products/extras', cq(p), signal)

// ── s4 stock ─────────────────────────────────────────────────────────────────

export interface LocationCount { locationId: string; name: string; pieces: number }
export interface AgeBucket { key: string; pieces: number; valueAtPrice: number }
export interface Valued { pieces: number; valueAtCost: number | null; costedPieces: number }
export type TrustLevel = 'high' | 'low'
export interface StockTrust {
  level: TrustLevel; packedThroughTracedPct: number | null; bostaDeliveredOrders30: number; packedThroughTraced30: number
  tracedAvailableTotal: number; shopifyAvailableTotal: number; variantsCompared: number; variantsWithMismatch: number
  mismatchUnits: number; mismatchShare: number | null; variantsWithoutShopifyFigure: number
  shopifyFigureOldest: string | null; shopifyFigureNewest: string | null; shopifySource: string
}
export interface StockSummary {
  hasPieces: boolean; range: Range; asOf: string; inWarehouse: number; byLocation: LocationCount[]
  valueAtPrice: number; valueAtCost: number | null; costedVariants: number; variantsInStock: number
  avgDaysInStock: number | null; ageBuckets: AgeBucket[]; onHold: number; damaged: Valued; lostThisPeriod: Valued
  piecesMovedFourPlus: number; trust: StockTrust; lowTrust: boolean
}
export type StockSource = 'pieces' | 'shopify'
export interface VariantStock {
  variantId: string; productTitle: string; variantTitle: string; sku: string | null; onHand: number; comingBack: number
  velocityPerDay: number | null; daysOfCover: number | null; sellThrough: number | null; avgPieceAgeDays: number | null
  lastSoldAt: string | null; soldUnits30: number; deliveredUnits30: number; returnsRate: number | null
  returnedUnits90: number; exchangedUnits90: number; topReturnReason: string | null; runningLow: boolean
  deadStock: boolean; stockValue: number | null; valueAtCost: boolean; shopifyAvailable: number | null
  stockUsed: number; stockSource: StockSource
}
export type StockSort = 'velocity' | 'daysOfCover' | 'onHand' | 'age' | 'sellThrough' | 'cash' | 'lastSold'
export type StockFilter = 'all' | 'running_low' | 'dead_stock'
export interface StockVariants {
  hasPieces: boolean; asOf: string; trustLevel: TrustLevel; stockSource: StockSource; sort: string; filter: string
  total: number; variants: VariantStock[]
}
export interface RestockItem {
  variantId: string; productTitle: string; variantTitle: string; sku: string | null; velocityPerDay: number | null
  onHand: number; comingBack: number; daysOfCover: number | null; suggestedUnits: number; costAtUnitCost: number | null
}
export interface Restock {
  hasPieces: boolean; asOf: string; trustLevel: TrustLevel; stockSource: StockSource; supplierLeadDays: number
  coverDays: number; velocityDays: number; items: RestockItem[]
}
export interface AnalyticsSettings { supplierLeadDays: number; coverDays: number; defaults: boolean }
export interface Trip {
  at: string; orderId: string | null; orderNumber: string | null; trackingNumber: string | null; cityId: string | null
  cityName: string | null; outcome: string | null; fee: number | null; feeEstimated: boolean
}
export interface PieceHistory {
  pieceId: string; barcode: string; shortCode: string | null; status: string; variantId: string; sku: string | null
  productTitle: string; variantTitle: string; receivedAt: string | null; location: string | null; trips: Trip[]
}
export interface PieceRow {
  pieceId: string; barcode: string; shortCode: string | null; status: string; receivedAt: string | null
  location: string | null; trips: number; lastTripAt: string | null
}
export interface VariantPieces { variantId: string; minTrips: number; total: number; pieces: PieceRow[] }

export const getStockSummary = (p: PeriodParams, signal?: AbortSignal) => get<StockSummary>('/stock/summary', periodQuery(p), signal)
export const getStockVariants = (q: { sort?: StockSort; filter?: StockFilter; limit?: number } = {}, signal?: AbortSignal) =>
  get<StockVariants>('/stock/variants', q, signal)
export const getRestock = (signal?: AbortSignal) => get<Restock>('/stock/restock', {}, signal)
export const getAnalyticsSettings = (signal?: AbortSignal) => get<AnalyticsSettings>('/settings', {}, signal)
export const saveAnalyticsSettings = (s: { supplierLeadDays?: number; coverDays?: number }) =>
  request<AnalyticsSettings>('/analytics/settings', { method: 'PUT', body: JSON.stringify(s) })
export const getPieceHistory = (pieceId: string, signal?: AbortSignal) =>
  get<PieceHistory>(`/pieces/${encodeURIComponent(pieceId)}/history`, {}, signal)
export const getVariantPieces = (variantId: string, q: { minTrips?: number; limit?: number } = {}, signal?: AbortSignal) =>
  get<VariantPieces>(`/variants/${encodeURIComponent(variantId)}/pieces`, q, signal)

// ── s6 customers ─────────────────────────────────────────────────────────────

export interface CustomerClassFigures {
  key: string; customers: number; orders: number; booked: number; realized: number; delivered: number
  failed: number; successRate: number | null
}
export interface CustomerSummary {
  customersWhoOrdered: number; newCustomers: number; existingCustomers: number; returningCustomers: number
  unknownCustomers: number; repeatPurchaseRate: number | null; medianDaysBetweenOrders: number | null
  ordersWithoutCustomer: number; byClass: CustomerClassFigures[]
}
export interface TopCustomer {
  customerRef: string; displayName: string | null; governorate: string | null; governorateAr: string | null
  customerType: string; orders: number; delivered: number; failed: number; successRate: number | null
  realized: number; firstOrderAt: string | null; lastOrderAt: string | null
}
export interface TopCustomers { asOf: string; totalCustomers: number; customers: TopCustomer[] }
export interface GovernorateRepeat {
  key: string; label: string; labelAr: string | null; customers: number; repeatCustomers: number; repeatRate: number | null
}
export interface CustomersByGovernorate { asOf: string; minCustomers: number; governorates: GovernorateRepeat[] }
export interface Cohort {
  month: string; customers: number; existingCustomers: number; orderedAgainPct: (number | null)[]; monthComplete: boolean[]
}
export interface Cohorts { asOf: string; cohorts: Cohort[] }
export interface WatchedCustomer {
  customerRef: string; displayName: string | null; governorate: string | null; governorateAr: string | null
  orders: number; refusedCodOrders: number; deliveredOrders: number; refusedValue: number; blocked: boolean
  suggestion: string | null; blocklistLink: string | null
}
export interface CustomerWatch { asOf: string; minRefused: number; customers: WatchedCustomer[] }

export const getCustomerSummary = (p: ComparedQuery, signal?: AbortSignal) =>
  get<Compared<CustomerSummary>>('/customers/summary', cq(p), signal)
export const getTopCustomers = (limit?: number, signal?: AbortSignal) => get<TopCustomers>('/customers/top', { limit }, signal)
export const getCustomersByGovernorate = (signal?: AbortSignal) => get<CustomersByGovernorate>('/customers/by-governorate', {}, signal)
export const getCohorts = (signal?: AbortSignal) => get<Cohorts>('/customers/cohorts', {}, signal)
export const getCustomerWatch = (signal?: AbortSignal) => get<CustomerWatch>('/customers/watch', {}, signal)

// ── s7 order finances, alerts, cash forecast ────────────────────────────────

export interface Governorate { key: string; label: string; labelAr: string | null }
export interface DeliveryStatus { key: string; label: string }
export interface OrderRow {
  orderId: string; name: string; placedAt: string; customer: string | null; governorate: Governorate | null
  items: number; total: number; paymentGroup: string | null; deliveryStatus: DeliveryStatus; financialStatus: string
  bostaFees: number | null; feesEstimated: boolean; netToYou: number | null; refundAmountUnknown: boolean
  trackingNumbers: string[]
}
export interface OrdersPage {
  range: Range; asOf: string; page: number; size: number; total: number; counts: Record<string, number>; orders: OrderRow[]
}
export interface VariantOrders { variantId: string; asOf: string; orders: OrderRow[] }
export type OrderSortKey = 'placedAt' | 'total' | 'bostaFees' | 'netToYou' | 'status'
export interface OrderFilters {
  status?: string; governorate?: string; variantId?: string; q?: string
  /** Server-side sort across all pages (default placedAt desc); the export uses the same. */
  sort?: OrderSortKey; dir?: 'asc' | 'desc'
}

export interface AlertDetail { key: string; label: string; labelAr: string | null; orders: number; successRate: number | null; failedValue: number | null }
export interface AlertSku {
  variantId: string; productTitle: string; variantTitle: string; sku: string | null; onHand: number
  daysOfCover: number | null; velocityPerDay: number | null; stockSource: StockSource
}
export type AlertKey = 'stuck_with_bosta' | 'never_picked_up' | 'delivered_not_paid' | 'low_success_governorates' | 'sells_out_soon'
export interface AnalyticsAlert {
  key: AlertKey | string; label: string; count: number; amount: number | null; link: string | null
  details: AlertDetail[] | null; skus: AlertSku[] | null
}
export interface Alerts { range: Range; asOf: string; alerts: AnalyticsAlert[] }

export interface ForecastPart { count: number; amount: number }
export interface InTransitPart { count: number; cod: number; expected: number; expectedPayoutDate: string | null }
export interface ForecastMethod {
  payoutWeekday: number; medianLagDays: number | null; lagSample: number; lagDaysUsed: number; nextPayoutDate: string | null
  awaitingDeposited: ForecastPart; awaitingNotSettled: ForecastPart; inTransit: InTransitPart
}
export interface ForecastBucket { key: string; from: string; to: string; amount: number; awaitingPayout: number; inTransit: number }
export interface CashForecast {
  asOf: string; today: string; reason: 'payout_cadence_unknown' | 'payout_lag_unknown' | null
  forecast: { buckets: ForecastBucket[]; later: number; method: ForecastMethod } | null
}

const orderQuery = (p: PeriodParams & OrderFilters & { page?: number; size?: number }): Query => ({
  ...periodQuery(p), status: p.status, governorate: p.governorate, variantId: p.variantId, q: p.q,
  sort: p.sort, dir: p.sort ? p.dir : undefined, page: p.page, size: p.size,
})
export const getAnalyticsOrders = (p: PeriodParams & OrderFilters & { page?: number; size?: number }, signal?: AbortSignal) =>
  get<OrdersPage>('/orders', orderQuery(p), signal)
/** The CSV is a file download, not JSON: the caller fetches it with the bearer token. */
export const ordersExportPath = (p: PeriodParams & OrderFilters) => `/analytics/orders/export.csv${queryString(orderQuery(p))}`
export const getVariantOrders = (variantId: string, signal?: AbortSignal) =>
  get<VariantOrders>(`/variants/${encodeURIComponent(variantId)}/orders`, {}, signal)
export const getAlerts = (p: PeriodParams, signal?: AbortSignal) => get<Alerts>('/alerts', periodQuery(p), signal)
export const getCashForecast = (signal?: AbortSignal) => get<CashForecast>('/cash-forecast', {}, signal)

// ── s10 profit + inventory sync ─────────────────────────────────────────────

export interface CostCoverage {
  variantsSold: number; variantsCosted: number; keptUnits: number; costedUnits: number
  realizedTotal: number; realizedCosted: number; costedRevenueShare: number | null
}
export interface ProfitSummary {
  range: Range; coverage: CostCoverage; cogs: number | null; grossProfit: number | null; grossMargin: number | null
  feesTotal: number; feesOnCosted: number | null; contributionProfit: number | null; contributionMargin: number | null
}
export interface TypeMargin {
  productType: string; keptUnits: number; costedUnits: number; realized: number; realizedCosted: number
  cogs: number | null; grossMargin: number | null
}
export interface ProfitByType { range: Range; coverage: CostCoverage; types: TypeMargin[] }
export interface SkuProfit {
  variantId: string; productTitle: string; variantTitle: string; sku: string | null; productType: string | null
  costed: boolean; unitCost: number | null; keptUnits: number; realized: number; cogs: number | null
  shippingFees: number; otherFees: number; netBeforeCost: number; trueNet: number | null; trueNetMargin: number | null
}
export type SkuProfitSort = 'trueNet' | 'realized' | 'margin'
export interface ProfitSkus { range: Range; coverage: CostCoverage; sort: string; total: number; skus: SkuProfit[] }

export type SyncFieldStatus = 'never' | 'ok' | 'access_denied' | 'error'
export interface InventorySyncStatus {
  tenantId: string; requestedAt: string | null; startedAt: string | null; finishedAt: string | null
  trigger: string | null; mode: string | null; variantsSeen: number; costWritten: number; costKeptManual: number
  costNonEgp: number; stockWritten: number; costStatus: SyncFieldStatus; stockStatus: SyncFieldStatus
  shopCurrency: string | null; lastError: string | null; variantsTotal: number; variantsCosted: number
  variantsStockSynced: number; nextRunAllowedAt: string | null
}

export const getProfitSummary = (p: PeriodParams, signal?: AbortSignal) => get<ProfitSummary>('/profit/summary', periodQuery(p), signal)
export const getProfitByType = (p: PeriodParams, signal?: AbortSignal) => get<ProfitByType>('/profit/by-product-type', periodQuery(p), signal)
export const getProfitSkus = (p: PeriodParams & { sort?: SkuProfitSort; limit?: number }, signal?: AbortSignal) =>
  get<ProfitSkus>('/profit/skus', { ...periodQuery(p), sort: p.sort, limit: p.limit }, signal)
export const getInventorySyncStatus = (signal?: AbortSignal) => get<InventorySyncStatus>('/inventory-sync/status', {}, signal)
export const runInventorySync = () =>
  request<{ queued: boolean; status: InventorySyncStatus }>('/analytics/inventory-sync/run', { method: 'POST' })
