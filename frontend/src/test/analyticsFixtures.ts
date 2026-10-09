// Analytics fixtures shaped like the three merchant types the screens must handle:
//   BROEK     — ships with Bosta, no unit costs, pieces but LOW stock trust (packs little through Traced)
//   FEMINE    — mostly shipped outside Bosta (Wijha-heavy), no pieces at all
//   HIGH_LINE — no Bosta account
// Numbers are illustrative, not production data. `analyticsFetch(fixture)` answers every
// /api/v1/analytics/* (and /connections) URL from a fixture and records the calls.

import type {
  Alerts, CashForecast, Compared, DeliverySummary, Fees, Pipeline, RevenueSummary, StockSummary, StockVariants,
  VariantDailyResponse, VariantSalesResponse, VariantSales,
} from '../analyticsApi'

const RANGE = { from: '2026-09-10', to: '2026-10-09', tz: 'Africa/Cairo' }
const PREV_RANGE = { from: '2026-08-11', to: '2026-09-09', tz: 'Africa/Cairo' }

function days(n: number, end = '2026-10-09'): string[] {
  const out: string[] = []
  const e = Date.parse(`${end}T00:00:00Z`)
  for (let i = n - 1; i >= 0; i--) out.push(new Date(e - i * 86_400_000).toISOString().slice(0, 10))
  return out
}

function revenueSummary(booked: number, realized: number, orders: number, approx = 0): RevenueSummary {
  const ds = days(30)
  const daily = ds.map((date, i) => {
    const b = (booked / 30) * (1 + 0.2 * Math.sin(i / 3))
    return { date, booked: Math.round(b), realized: Math.round(i > 23 ? b * 0.2 : b * (realized / booked)) }
  })
  return {
    grossSales: booked * 1.06, discounts: { code: booked * 0.04, automatic: booked * 0.02, other: 0, total: booked * 0.06 },
    booked, realized, realizedShare: realized / booked, orders, approximateLines: approx,
    waterfall: { gross: booked * 1.06, discounts: booked * 0.06, inTransit: booked * 0.12, notShipped: booked * 0.05,
      otherCarrier: 0, refused: booked * 0.1, otherTerminal: 0, returns: booked * 0.03, netRealized: realized },
    funnel: { ordered: orders, fulfilled: Math.round(orders * 0.93), delivered: Math.round(orders * 0.7), paidToYou: Math.round(orders * 0.55) },
    daily,
  }
}

function compared<T>(current: T, previous: T | null): Compared<T> {
  return { range: RANGE, previousRange: previous ? PREV_RANGE : null, current, previous }
}

function variant(i: number, title: string, v: string, units: number, price: number, wijha = 0): VariantSales {
  const delivered = Math.round(units * 0.75)
  return {
    variantId: `00000000-0000-4000-8000-${String(i).padStart(12, '0')}`, productId: `p-${i}`, productTitle: title,
    variantTitle: v, sku: `SKU-${i}`, imageUrl: null, soldUnits: units, grossRevenue: units * price, approximateLines: 0,
    lastSoldAt: '2026-10-08T10:00:00Z', deliveredUnits: delivered, refusedUnits: Math.round(units * 0.1),
    inTransitUnits: Math.round(units * 0.1), wijhaUnits: wijha, notShippedUnits: 0, otherTerminalUnits: 0, returnedUnits: 1,
    netSoldUnits: delivered - 1, returnRate: 0.02, refusalRate: 0.1, deliveredRevenue: delivered * price,
    returnedRevenue: price, netRevenue: (delivered - 1) * price,
  }
}

function sales(vs: VariantSales[], orders: number, wijhaOrders: number): VariantSalesResponse {
  const sum = (f: (v: VariantSales) => number) => vs.reduce((a, v) => a + f(v), 0)
  return {
    range: RANGE,
    totals: {
      soldUnits: sum(v => v.soldUnits), grossRevenue: sum(v => v.grossRevenue), orders, approximateLines: 0,
      deliveredUnits: sum(v => v.deliveredUnits), refusedUnits: sum(v => v.refusedUnits), inTransitUnits: sum(v => v.inTransitUnits),
      wijhaUnits: sum(v => v.wijhaUnits), notShippedUnits: 0, otherTerminalUnits: 0, returnedUnits: sum(v => v.returnedUnits),
      netSoldUnits: sum(v => v.netSoldUnits), returnRate: 0.02, refusalRate: 0.1, deliveredRevenue: sum(v => v.deliveredRevenue),
      returnedRevenue: sum(v => v.returnedRevenue), netRevenue: sum(v => v.netRevenue), deliveredOrders: Math.round(orders * 0.7),
      refusedOrders: Math.round(orders * 0.1), wijhaOrders, returnsOnUndeliveredOrders: 0, unverifiedNoRestockLines: 0,
    },
    variants: vs,
  }
}

function delivery(rate: number | null, delivered: number, failed: number): DeliverySummary {
  return {
    successRate: rate, delivered, failed, lostSalesValue: failed * 900, avgHoursOrderToHanded: 30, handedOrders: delivered + failed,
    avgHoursHandedToDelivered: 52, avgHoursHandedToDeliveredCairoGiza: 40, avgHoursHandedToDeliveredOther: 70,
    weeklyTrend: [], fulfillmentSpeed: [],
  }
}

function fees(total: number, delivered: number, estimated: number): Fees {
  const f = (legs: number, amount: number) => ({ legs, amount, estimatedCount: 0 })
  return {
    range: RANGE, shipping: f(delivered, total * 0.75), failed: f(20, total * 0.15), exchange: f(3, total * 0.05),
    returned: f(2, total * 0.05), total: { legs: delivered + 25, amount: total, estimatedCount: estimated },
    settledComponents: { settledLegs: delivered, shippingFees: total * 0.7, openingPackageFees: 0, collectionFees: total * 0.1,
      insuranceFees: 0, flexShipFees: 0, promotionDiscount: 0, vat: total * 0.14 },
    costPerSuccessfulDelivery: delivered ? (total * 0.75) / delivered : null, deliveredCount: delivered,
    costPerUnsuccessfulDelivery: 45, refusedCount: 20, payoutLagDays: 4.2, payoutLagShipments: delivered,
  }
}

function pipeline(p: Partial<Pipeline> = {}): Pipeline {
  return {
    range: RANGE, asOf: '2026-10-09T09:40:00Z',
    notFulfilled: { count: 88, value: 101200, expected: 84400 },
    inTransit: { count: 182, value: 209300, expected: 174600 },
    awaitingPayout: { count: 171, deposited: 196650, nextCashoutDate: '2026-10-14', deliveredNotYetSettled: 6, deliveredNotYetSettledEstimate: 7100 },
    inYourBank: { shipments: 781, deposited: 898150, payouts: 4, lastTransferDate: '2026-10-07' },
    openOrderDays: 30,
    ...p,
  }
}

function forecast(): CashForecast {
  return {
    asOf: '2026-10-09T09:40:00Z', today: '2026-10-09', reason: null,
    forecast: {
      buckets: [
        { key: 'next7', from: '2026-10-09', to: '2026-10-16', amount: 241300, awaitingPayout: 196650, inTransit: 44650 },
        { key: 'days8to14', from: '2026-10-17', to: '2026-10-23', amount: 118200, awaitingPayout: 0, inTransit: 118200 },
        { key: 'days15to30', from: '2026-10-24', to: '2026-11-08', amount: 11750, awaitingPayout: 0, inTransit: 11750 },
      ],
      later: 0,
      method: { payoutWeekday: 3, medianLagDays: 4.2, lagSample: 640, lagDaysUsed: 5, nextPayoutDate: '2026-10-14',
        awaitingDeposited: { count: 171, amount: 196650 }, awaitingNotSettled: { count: 6, amount: 7100 },
        inTransit: { count: 182, cod: 209300, expected: 174600, expectedPayoutDate: '2026-10-21' } },
    },
  }
}

function alerts(withSellsOut: boolean): Alerts {
  const out: Alerts['alerts'] = [
    { key: 'stuck_with_bosta', label: 'Stuck with Bosta for more than 7 days', count: 5, amount: 6850, link: '/analytics/money?view=stuck', details: null, skus: null },
    { key: 'never_picked_up', label: 'Booked, never picked up for more than 7 days', count: 0, amount: 0, link: null, details: null, skus: null },
    { key: 'delivered_not_paid', label: 'Delivered, not paid', count: 2, amount: 2150, link: null, details: null, skus: null },
    { key: 'low_success_governorates', label: 'Governorates with delivery success under 65%', count: 2, amount: null, link: null,
      details: [
        { key: 'EG-SHG', label: 'Sohag', labelAr: 'سوهاج', orders: 31, successRate: 0.605, failedValue: 14200 },
        { key: 'EG-AST', label: 'Assiut', labelAr: 'أسيوط', orders: 26, successRate: 0.638, failedValue: 10100 },
      ], skus: null },
  ]
  if (withSellsOut) {
    out.push({ key: 'sells_out_soon', label: 'Best sellers that sell out within 4 days', count: 1, amount: null, link: null, details: null,
      skus: [{ variantId: 'v', productTitle: 'Wide-Leg Cargo', variantTitle: 'Olive / 32', sku: 'WLC-32', onHand: 9, daysOfCover: 2.3, velocityPerDay: 3.9, stockSource: 'shopify' }] })
  }
  return { range: RANGE, asOf: '2026-10-09T09:40:00Z', alerts: out }
}

function stockSummary(hasPieces: boolean, lowTrust: boolean, pct: number | null): StockSummary {
  return {
    hasPieces, range: RANGE, asOf: '2026-10-09T09:40:00Z', inWarehouse: hasPieces ? 640 : 0, byLocation: [],
    valueAtPrice: hasPieces ? 512000 : 0, valueAtCost: null, costedVariants: 0, variantsInStock: hasPieces ? 41 : 0,
    avgDaysInStock: hasPieces ? 38 : null, ageBuckets: [], onHold: 0, damaged: { pieces: 0, valueAtCost: null, costedPieces: 0 },
    lostThisPeriod: { pieces: 0, valueAtCost: null, costedPieces: 0 }, piecesMovedFourPlus: 0,
    trust: { level: lowTrust ? 'low' : 'high', packedThroughTracedPct: pct, bostaDeliveredOrders30: 900, packedThroughTraced30: Math.round(900 * (pct ?? 0)),
      tracedAvailableTotal: 640, shopifyAvailableTotal: 702, variantsCompared: 41, variantsWithMismatch: 9, mismatchUnits: 62,
      mismatchShare: 0.22, variantsWithoutShopifyFigure: 0, shopifyFigureOldest: null, shopifyFigureNewest: null, shopifySource: 'variants.stock_available_shopify_traced' },
    lowTrust,
  }
}

function stockVariants(hasPieces: boolean, source: 'pieces' | 'shopify', vs: VariantSales[]): StockVariants {
  return {
    hasPieces, asOf: '2026-10-09T09:40:00Z', trustLevel: source === 'shopify' ? 'low' : 'high', stockSource: source,
    sort: 'velocity', filter: 'all', total: hasPieces ? vs.length : 0,
    variants: hasPieces ? vs.map((v, i) => ({
      variantId: v.variantId, productTitle: v.productTitle, variantTitle: v.variantTitle, sku: v.sku, onHand: 20 - i * 3,
      comingBack: 0, velocityPerDay: 3, daysOfCover: i === 0 ? 3.2 : 14 + i, sellThrough: 0.5, avgPieceAgeDays: 20,
      lastSoldAt: v.lastSoldAt, soldUnits30: v.soldUnits, deliveredUnits30: v.deliveredUnits, returnsRate: 0.02, returnedUnits90: 1,
      exchangedUnits90: 0, topReturnReason: null, runningLow: i === 0, deadStock: false, stockValue: null, valueAtCost: false,
      shopifyAvailable: 22 - i * 3, stockUsed: source === 'shopify' ? 22 - i * 3 : 20 - i * 3, stockSource: source,
    })) : [],
  }
}

function dailyFor(vs: VariantSales[]): VariantDailyResponse {
  const ds = days(30)
  return { range: RANGE, days: ds, variants: vs.map((v, k) => ({ variantId: v.variantId, totalUnits: v.soldUnits,
    units: ds.map((_, i) => Math.max(0, Math.round(v.soldUnits / 30 + Math.sin(i / 2 + k) * 2))) })) }
}

export interface AnalyticsFixture {
  name: string
  bostaConnected: boolean
  pipeline: Pipeline
  revenue: Compared<RevenueSummary>
  delivery: Compared<DeliverySummary>
  fees: Fees
  forecast: CashForecast
  alerts: Alerts
  sales: VariantSalesResponse
  daily: VariantDailyResponse
  stockSummary: StockSummary
  stockVariants: StockVariants
}

const broekVariants = [
  variant(1, 'Boxy Tee', 'Black / L', 212, 650), variant(2, 'Boxy Tee', 'Black / M', 188, 650),
  variant(3, 'Heavyweight Hoodie', 'Grey / L', 131, 1450), variant(4, 'Wide-Leg Cargo', 'Olive / 32', 118, 1250),
  variant(5, 'Linen Shirt', 'Sand / M', 86, 1100), variant(6, 'Track Pants', 'Black / M', 79, 950),
]

export const BROEK: AnalyticsFixture = {
  name: 'BROEK', bostaConnected: true,
  pipeline: pipeline(),
  revenue: compared(revenueSummary(1598500, 1033500, 1390), revenueSummary(1422000, 919300, 1286)),
  delivery: compared(delivery(0.85, 952, 168), delivery(0.826, 880, 185)),
  fees: fees(107437, 952, 41),
  forecast: forecast(),
  alerts: alerts(true),
  sales: sales(broekVariants, 1390, 12),
  daily: dailyFor(broekVariants.slice(0, 5)),
  stockSummary: stockSummary(true, true, 0.22),
  stockVariants: stockVariants(true, 'shopify', broekVariants),
}

const femineVariants = [
  variant(11, 'Satin Abaya', 'Black / M', 96, 1850, 40), variant(12, 'Pleated Skirt', 'Beige / S', 74, 890, 31),
  variant(13, 'Knit Cardigan', 'Rose / M', 51, 1200, 22),
]

export const FEMINE: AnalyticsFixture = {
  name: 'Femine', bostaConnected: true,
  pipeline: pipeline({ notFulfilled: { count: 21, value: 27400, expected: 22100 }, inTransit: { count: 34, value: 41800, expected: 33400 },
    awaitingPayout: { count: 18, deposited: 22300, nextCashoutDate: '2026-10-14', deliveredNotYetSettled: 0, deliveredNotYetSettledEstimate: 0 },
    inYourBank: { shipments: 96, deposited: 118400, payouts: 3, lastTransferDate: '2026-10-07' } }),
  revenue: compared(revenueSummary(391000, 248000, 312), revenueSummary(355000, 231000, 290)),
  delivery: compared(delivery(0.81, 141, 33), delivery(0.8, 131, 33)),
  fees: fees(16200, 141, 0),
  forecast: forecast(),
  alerts: alerts(false),
  sales: sales(femineVariants, 312, 128),
  daily: dailyFor(femineVariants),
  stockSummary: stockSummary(false, false, null),
  stockVariants: stockVariants(false, 'pieces', []),
}

const highLineVariants = [variant(21, 'Oxford Shirt', 'White / L', 44, 990), variant(22, 'Chino', 'Khaki / 32', 30, 1150)]

export const HIGH_LINE: AnalyticsFixture = {
  name: 'High line', bostaConnected: false,
  pipeline: pipeline({ notFulfilled: { count: 63, value: 71300, expected: null }, inTransit: { count: 0, value: 0, expected: null },
    awaitingPayout: { count: 0, deposited: 0, nextCashoutDate: null, deliveredNotYetSettled: 0, deliveredNotYetSettledEstimate: null },
    inYourBank: { shipments: 0, deposited: 0, payouts: 0, lastTransferDate: null } }),
  revenue: compared(revenueSummary(120400, 0, 96), null),
  delivery: compared(delivery(null, 0, 0), null),
  fees: fees(0, 0, 0),
  forecast: { asOf: '2026-10-09T09:40:00Z', today: '2026-10-09', reason: 'payout_cadence_unknown', forecast: null },
  alerts: { range: RANGE, asOf: '2026-10-09T09:40:00Z', alerts: alerts(false).alerts.map(a => ({ ...a, count: 0, details: [] })) },
  sales: sales(highLineVariants, 96, 0),
  daily: dailyFor(highLineVariants),
  stockSummary: stockSummary(true, false, 0.9),
  stockVariants: stockVariants(true, 'pieces', highLineVariants),
}

function ok(body: unknown) {
  return Promise.resolve({
    ok: true, status: 200,
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(body),
  })
}

export function errResponse(status = 500) {
  return Promise.resolve({ ok: false, status, statusText: 'Server Error', headers: { get: () => null }, json: async () => ({}) })
}

function connections(bosta: boolean) {
  return {
    shopify: { connected: true, storeId: 's', shopDomain: 'x.myshopify.com', connectionType: 'oauth', status: 'connected', importStatus: 'completed', lastSyncAt: null },
    bosta: { connected: bosta, businessName: bosta ? 'Biz' : null, pickupMode: null, awbFormat: null, awbLang: null },
    customAppAvailable: false, oauthAvailable: true,
    shopifySetup: { appUrl: '', redirectUrl: '', webhookApiVersion: '', scopes: [] },
  }
}

/** Routes the analytics + /connections URLs to the fixture; `override` can fail or replace one. */
export function analyticsFetch(
  f: AnalyticsFixture,
  calls: string[] = [],
  override?: (url: string) => Promise<unknown> | undefined,
) {
  return (url: string) => {
    calls.push(url)
    const o = override?.(url)
    if (o) return o
    const path = url.replace(/^.*\/api\/v1/, '').split('?')[0]
    switch (path) {
      case '/connections': return ok(connections(f.bostaConnected))
      case '/analytics/money/pipeline': return ok(f.pipeline)
      case '/analytics/revenue/summary': return ok(f.revenue)
      case '/analytics/delivery/summary': return ok(f.delivery)
      case '/analytics/money/fees': return ok(f.fees)
      case '/analytics/cash-forecast': return ok(f.forecast)
      case '/analytics/alerts': return ok(f.alerts)
      case '/analytics/sales/variants': return ok(f.sales)
      case '/analytics/sales/variants/daily': return ok(f.daily)
      case '/analytics/stock/summary': return ok(f.stockSummary)
      case '/analytics/stock/variants': return ok(f.stockVariants)
      default: return errResponse(404)
    }
  }
}
