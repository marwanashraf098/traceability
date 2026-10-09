// Analytics fixtures shaped like the three merchant types the screens must handle:
//   BROEK     — ships with Bosta, no unit costs, pieces but LOW stock trust (packs little through Traced)
//   FEMINE    — mostly shipped outside Bosta (Wijha-heavy), no pieces at all
//   HIGH_LINE — no Bosta account
// Numbers are illustrative, not production data. `analyticsFetch(fixture)` answers every
// /api/v1/analytics/* (and /connections) URL from a fixture and records the calls.

import type {
  Alerts, Breakdown, BreakdownGroup, CashForecast, Compared, CustomerSummary, CustomerWatch, DeliverySummary, Discounts,
  FailureReasons, Fees, Heatmap, InventorySyncStatus, Pipeline, ProductExtras, ProfitByType, ProfitSummary, RevenueSummary,
  StockSummary, StockVariants, VariantDailyResponse, VariantSalesResponse, VariantSales, ExtraFees, Stuck, Payouts,
  ProfitSkus, OrdersPage, OrderRow, VariantOrders, VariantPieces, PieceHistory,
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

function delivery(rate: number | null, delivered: number, failed: number, hoursToHanded = 30): DeliverySummary {
  const weeks = ['2026-08-17', '2026-08-24', '2026-08-31', '2026-09-07', '2026-09-14', '2026-09-21', '2026-09-28', '2026-10-05']
  const n = delivered + failed
  return {
    successRate: rate, delivered, failed, lostSalesValue: failed * 900, avgHoursOrderToHanded: rate == null ? null : hoursToHanded,
    handedOrders: n, avgHoursHandedToDelivered: rate == null ? null : 52, avgHoursHandedToDeliveredCairoGiza: rate == null ? null : 40,
    avgHoursHandedToDeliveredOther: rate == null ? null : 70,
    weeklyTrend: rate == null ? [] : weeks.map((w, i) => ({ weekStart: w, delivered: Math.round(delivered / 8), failed: Math.round(failed / 8),
      successRate: Math.min(0.95, rate - 0.04 + i * 0.006) })),
    fulfillmentSpeed: rate == null ? [] : [
      { bucket: 'same_day', orders: Math.round(n * 0.28), delivered: 0, failed: 0, successRate: Math.min(0.97, rate + 0.03) },
      { bucket: '1_day', orders: Math.round(n * 0.41), delivered: 0, failed: 0, successRate: rate },
      { bucket: '2_days', orders: Math.round(n * 0.19), delivered: 0, failed: 0, successRate: rate - 0.03 },
      { bucket: '3_plus_days', orders: Math.round(n * 0.12), delivered: 0, failed: 0, successRate: rate - 0.06 },
    ],
  }
}

function group(key: string, label: string, booked: number, rate: number | null, orders: number, labelAr: string | null = null): BreakdownGroup {
  const delivered = rate == null ? 0 : Math.round(orders * rate * 0.85)
  return { key, label, labelAr, booked, realized: rate == null ? 0 : Math.round(booked * rate * 0.78), orders,
    deliveredOrders: delivered, failedOrders: rate == null ? 0 : Math.round(orders * (1 - rate) * 0.8), successRate: rate }
}

function breakdowns(scale: number, hasBosta: boolean): Record<string, Compared<Breakdown>> {
  const r = (x: number) => (hasBosta ? x : null)
  const mk = (by: string, groups: BreakdownGroup[]) => compared<Breakdown>({ by, groups }, null)
  return {
    channel: mk('channel', [group('Instagram', 'Instagram', 655400 * scale, r(0.841), 560), group('Direct', 'Direct', 383600 * scale, r(0.912), 330),
      group('TikTok', 'TikTok', 303700 * scale, r(0.76), 270), group('Manual / DM', 'Manual / DM', 175800 * scale, r(0.883), 150),
      group('Unknown', 'Unknown', 80000 * scale, r(0.829), 80)]),
    payment: mk('payment', [group('COD', 'COD', 1310800 * scale, r(0.826), 1130), group('Card', 'Card', 175800 * scale, r(0.971), 160),
      group('Manual', 'Manual', 111900 * scale, r(0.984), 100)]),
    governorate: mk('governorate', [group('c-cairo', 'Cairo', 543500 * scale, r(0.896), 472, 'القاهرة'),
      group('c-giza', 'Giza', 351700 * scale, r(0.879), 306, 'الجيزة'), group('c-alex', 'Alexandria', 207800 * scale, r(0.841), 181, 'الإسكندرية'),
      group('c-dak', 'Dakahlia', 79900 * scale, r(0.77), 70, 'الدقهلية'), group('c-ast', 'Assiut', 32000 * scale, r(0.638), 28, 'أسيوط'),
      group('c-shg', 'Sohag', 24000 * scale, r(0.605), 21, 'سوهاج'), group('unknown', 'Unknown', 12000 * scale, r(0.7), 11, 'غير معروف')]),
    productType: mk('productType', [group('Tees', 'Tees', 511500 * scale, r(0.85), 600), group('Bottoms', 'Bottoms', 431600 * scale, r(0.83), 340),
      group('Hoodies', 'Hoodies', 367700 * scale, r(0.86), 250), group('uncategorised', 'uncategorised', 79900 * scale, r(0.8), 60)]),
  }
}

function discounts(scale: number): Compared<Discounts> {
  const row = (code: string | null, orders: number, booked: number, cost: number, rate: number) => ({
    code, label: code, orders, booked: booked * scale, discountCost: cost * scale, deliveredOrders: Math.round(orders * rate),
    failedOrders: Math.round(orders * (1 - rate)), successRate: rate, revenuePerCost: booked / cost })
  return compared<Discounts>({
    codes: [row('WELCOME10', 212, 243800, 24380, 0.86), row('SUMMER20', 148, 170200, 34040, 0.811), row('TIKTOK15', 96, 110400, 16560, 0.719)],
    automatic: { ...row(null, 70, 80500, 4270, 0.886), label: 'Automatic discounts' },
  }, null)
}

function heatmap(scale: number): Compared<Heatmap> {
  const cells = []
  for (let wd = 1; wd <= 7; wd++) for (let h = 0; h < 24; h++) {
    const ev = Math.exp(-Math.pow((h - 21.5) / 3.2, 2)) + 0.45 * Math.exp(-Math.pow((h - 14) / 3, 2)) + 0.05
    const dw = [0, 0.8, 0.82, 0.9, 1.15, 1.25, 1, 0.85][wd]
    const avg = Math.round(ev * dw * 22 * scale * 10) / 10
    cells.push({ weekday: wd, hour: h, orders: Math.round(avg * 4), avgOrders: avg })
  }
  return compared<Heatmap>({ cells, weekdayOccurrences: { 1: 4, 2: 4, 3: 4, 4: 5, 5: 5, 6: 4, 7: 4 } }, null)
}

function customerSummary(unknownShare: number, hasBosta: boolean): Compared<CustomerSummary> {
  const total = 1108
  const unknown = Math.round(total * unknownShare)
  const rest = total - unknown
  const cls = (key: string, customers: number, booked: number, rate: number) => ({ key, customers, orders: customers, booked,
    realized: hasBosta ? booked * rate * 0.8 : 0, delivered: 0, failed: 0, successRate: hasBosta ? rate : null })
  return compared<CustomerSummary>({
    customersWhoOrdered: total, newCustomers: Math.round(rest * 0.7), existingCustomers: Math.round(rest * 0.1), returningCustomers: Math.round(rest * 0.2),
    unknownCustomers: unknown, repeatPurchaseRate: 0.18, medianDaysBetweenOrders: 34, ordersWithoutCustomer: 3,
    byClass: [cls('new', Math.round(rest * 0.7), 900000 * (1 - unknownShare), 0.82), cls('existing', Math.round(rest * 0.1), 120000, 0.9),
      cls('returning', Math.round(rest * 0.2), 380000, 0.94), cls('unknown', unknown, 900000 * unknownShare, 0.8)],
  }, null)
}

const COST_NONE = { variantsSold: 6, variantsCosted: 0, keptUnits: 720, costedUnits: 0, realizedTotal: 1033500, realizedCosted: 0, costedRevenueShare: 0 }

function profitSummary(costed: boolean): ProfitSummary {
  const coverage = costed
    ? { variantsSold: 6, variantsCosted: 4, keptUnits: 720, costedUnits: 560, realizedTotal: 1033500, realizedCosted: 820000, costedRevenueShare: 0.79 }
    : COST_NONE
  return { range: RANGE, coverage, cogs: costed ? 311000 : null, grossProfit: costed ? 509000 : null, grossMargin: costed ? 0.62 : null,
    feesTotal: 107437, feesOnCosted: costed ? 84000 : null, contributionProfit: costed ? 425000 : null, contributionMargin: costed ? 0.518 : null }
}

function profitByType(costed: boolean): ProfitByType {
  const s = profitSummary(costed)
  return { range: RANGE, coverage: s.coverage, types: costed ? [
    { productType: 'Tees', keptUnits: 400, costedUnits: 400, realized: 338900, realizedCosted: 338900, cogs: 131500, grossMargin: 0.612 },
    { productType: 'Bottoms', keptUnits: 200, costedUnits: 160, realized: 281300, realizedCosted: 230000, cogs: 95700, grossMargin: 0.584 },
    { productType: 'Hoodies', keptUnits: 120, costedUnits: 0, realized: 236000, realizedCosted: 0, cogs: null, grossMargin: null },
  ] : [] }
}

function inventorySync(costStatus: InventorySyncStatus['costStatus']): InventorySyncStatus {
  return { tenantId: 't', requestedAt: null, startedAt: '2026-10-09T03:15:00Z', finishedAt: '2026-10-09T03:16:00Z', trigger: 'daily', mode: 'bulk',
    variantsSeen: 120, costWritten: 0, costKeptManual: 0, costNonEgp: 0, stockWritten: 120, costStatus, stockStatus: 'ok', shopCurrency: 'EGP',
    lastError: costStatus === 'access_denied' ? 'Access denied for unitCost field' : null, variantsTotal: 120, variantsCosted: 0,
    variantsStockSynced: 120, nextRunAllowedAt: null }
}

function failureReasons(failed: number): Compared<FailureReasons> {
  const withReason = Math.round(failed * 0.86)
  const r = (reason: string, share: number) => ({ reason, count: Math.round(withReason * share), share })
  return compared<FailureReasons>({ failedLegs: failed, withReason, coverage: failed ? withReason / failed : null, reasons: failed ? [
    r('Customer refused', 0.41), r('Phone unreachable / not home', 0.27), r('Wrong or incomplete address', 0.13),
    r('Postponed / rescheduled', 0.11), r('Other', 0.08)] : [] }, null)
}

function productExtras(): Compared<ProductExtras> {
  const p = (id: string, title: string, orders: number, failed: number) => ({ productId: id, title, orders, failedOrders: failed, failureRate: failed / orders })
  return compared<ProductExtras>({ abc: [
    { variantId: '00000000-0000-4000-8000-000000000001', sku: 'SKU-1', productTitle: 'Boxy Tee', variantTitle: 'Black / L', realized: 102700, share: 0.18, cumulativeShare: 0.18, abcClass: 'A' },
  ], sizeCurve: { sizes: [], unparseableUnits: 0, unparseableValues: [], noSizeUnits: 0 }, boughtTogether: [],
    mostFailed: [p('p4', 'Wide-Leg Cargo', 118, 18), p('p9', 'Denim Jacket', 41, 9), p('p3', 'Heavyweight Hoodie', 131, 17), p('p8', 'Small Tote', 12, 6)] }, null)
}

function watch(n: number): CustomerWatch {
  const rows = [['Mostafa E.', 'Assiut', 'أسيوط', 4, 3], ['Youssef M.', 'Sohag', 'سوهاج', 3, 2], ['Dina S.', 'Giza', 'الجيزة', 5, 2]] as const
  return { asOf: '2026-10-09T09:40:00Z', minRefused: 2, customers: rows.slice(0, n).map(([name, g, gAr, orders, refused], i) => ({
    customerRef: `ref-${i}`, displayName: name, governorate: g, governorateAr: gAr, orders, refusedCodOrders: refused,
    deliveredOrders: orders - refused, refusedValue: refused * 1100, blocked: i === 2, suggestion: 'Ask for prepayment', blocklistLink: '/blocklist' })) }
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

function extraFees(vs: VariantSales[], view: 'sku' | 'awb', scale: number): ExtraFees {
  const skus = vs.map((v, i) => ({ variantId: v.variantId, sku: v.sku, productTitle: v.productTitle, variantTitle: v.variantTitle,
    failed: Math.round((12 - i * 2) * scale), exchanges: Math.round((5 - i) * scale), returns: Math.round((3 - i * 0.5) * scale),
    extraFees: Math.round(((12 - i * 2) * 45 + (5 - i) * 60 + (3 - i * 0.5) * 45) * scale) })).filter(r => r.failed + r.exchanges + r.returns > 0)
  const types = ['failed', 'failed', 'exchange', 'return', 'failed'] as const
  const shipments = vs.slice(0, 5).map((v, i) => ({ trackingNumber: `71${String(3900000 + i * 1371).padStart(8, '0')}`, orderNumber: `#${4800 - i * 7}`,
    type: types[i], fee: types[i] === 'exchange' ? 60 : 45, estimated: i === 1, reason: types[i] === 'failed' ? 'Customer refused' : null,
    date: `2026-10-0${8 - i}`, city: ['Cairo', 'Sohag', 'Giza', 'Alexandria', 'Assiut'][i], skus: [v.sku ?? ''] }))
  return { range: RANGE, groupBy: view, shipments: view === 'awb' ? shipments : null, skus: view === 'sku' ? skus : null,
    total: skus.reduce((a, r) => a + r.extraFees, 0), estimatedCount: view === 'awb' ? 1 : 0 }
}

function stuck(n: number): Stuck {
  const st = (i: number, status: string, days: number, cod: number) => ({ trackingNumber: `72${String(1000000 + i * 911).padStart(8, '0')}`, orderNumber: `#${4700 + i}`, lastStatus: status, days, cod })
  return { asOf: '2026-10-09T09:40:00Z',
    stuckWithBosta: [st(1, 'In transit', 12, 1250), st(2, 'Returning to origin', 10, 1300), st(3, 'Out for delivery', 8, 2200)].slice(0, n),
    neverPickedUp: n ? [st(4, 'Booked', 9, 650)] : [],
    deliveredNotPaid: n ? [{ trackingNumber: '7139220400', orderNumber: '#4688', depositedOn: '2026-09-26', deposited: 1450, days: 13 }] : [],
    payoutWeekday: 3, unresolved: 0 }
}

function payouts(scale: number): Payouts {
  const p = (date: string, id: string, n: number, amt: number, batch: number | null) => ({ transactionId: id, date, trackedShipments: Math.round(n * scale),
    trackedDeposited: Math.round(amt * scale), bostaBatchTotal: batch == null ? null : Math.round(batch * scale) })
  return { range: RANGE, payouts: [p('2026-09-16', 'BST-P-21388', 201, 231150, 231150), p('2026-09-23', 'BST-P-21842', 188, 216900, 216900),
    p('2026-09-30', 'BST-P-22297', 196, 225800, 227950), p('2026-10-03', 'BST-P-22510', 12, 14000, 13200),
    p('2026-10-07', 'BST-P-22751', 171, 196650, null)] }
}

function profitSkus(vs: VariantSales[], costed: boolean): ProfitSkus {
  const cov = profitSummary(costed).coverage
  return { range: RANGE, coverage: cov, sort: 'trueNet', total: vs.length, skus: vs.map((v, i) => {
    const c = costed && i < 2
    const cogs = c ? v.netSoldUnits * 400 : null
    const ship = v.deliveredUnits * 46, other = v.refusedUnits * 45
    return { variantId: v.variantId, productTitle: v.productTitle, variantTitle: v.variantTitle, sku: v.sku, productType: 'Tees', costed: c,
      unitCost: c ? 400 : null, keptUnits: v.netSoldUnits, realized: v.netRevenue, cogs, shippingFees: ship, otherFees: other,
      netBeforeCost: v.netRevenue - ship - other, trueNet: c ? v.netRevenue - ship - other - cogs! : null,
      trueNetMargin: c ? (v.netRevenue - ship - other - cogs!) / v.netRevenue : null }
  }) }
}

function orderRow(i: number, fin: string, total: number, hasBosta: boolean): OrderRow {
  const net: Record<string, number | null> = { paid: total - 72, awaiting_payout: total - 72, expected: null, overdue: null, lost: -105, refunded: -105, other_carrier: null }
  const delivery: Record<string, string> = { paid: 'Delivered', awaiting_payout: 'Delivered', expected: 'In transit', overdue: 'In transit', lost: 'Refused, returning',
    refunded: 'Delivered', other_carrier: 'Other carrier' }
  return { orderId: `o-${i}`, name: `#${4871 - i}`, placedAt: `2026-10-${String(9 - (i % 9)).padStart(2, '0')}T10:00:00Z`,
    customer: ['Mariam A.', 'Omar K.', 'Nour H.', 'Youssef M.', 'Salma R.', 'Ahmed T.'][i % 6],
    governorate: { key: 'c-cairo', label: ['Cairo', 'Giza', 'Sohag'][i % 3], labelAr: ['القاهرة', 'الجيزة', 'سوهاج'][i % 3] },
    items: 1 + (i % 3), total, paymentGroup: i % 4 === 1 ? 'Card' : 'COD', deliveryStatus: { key: 'x', label: delivery[fin] },
    financialStatus: fin, bostaFees: hasBosta && fin !== 'other_carrier' ? 72 + (i % 3) * 5 : null, feesEstimated: fin === 'expected',
    netToYou: hasBosta ? net[fin] : null, refundAmountUnknown: false, trackingNumbers: [`7100${100000 + i}`] }
}

function ordersPage(hasBosta: boolean, wijha: boolean): OrdersPage {
  const fins = hasBosta ? ['paid', 'awaiting_payout', 'expected', 'overdue', 'lost', 'refunded', 'paid', 'paid', ...(wijha ? ['other_carrier', 'other_carrier'] : [])] : ['expected', 'expected', 'expected']
  const orders = fins.map((f, i) => orderRow(i, f, [1300, 1450, 2550, 1250, 1700, 950, 2100, 1850, 1100, 2950][i % 10], hasBosta))
  const counts: Record<string, number> = { other_carrier: 0, lost: 0, refunded: 0, paid: 0, overdue: 0, awaiting_payout: 0, expected: 0 }
  fins.forEach(f => { counts[f]++ })
  return { range: RANGE, asOf: '2026-10-09T09:40:00Z', page: 0, size: 50, total: orders.length, counts, orders }
}

function variantPieces(id: string, has: boolean): VariantPieces {
  return { variantId: id, minTrips: 0, total: has ? 3 : 0, pieces: has ? [
    { pieceId: 'pc-1', barcode: 'TRC-0147', shortCode: 'BXT-0147', status: 'available', receivedAt: '2026-08-14T09:00:00Z', location: 'Main', trips: 3, lastTripAt: '2026-09-21T10:00:00Z' },
    { pieceId: 'pc-2', barcode: 'TRC-0148', shortCode: 'BXT-0148', status: 'available', receivedAt: '2026-08-14T09:00:00Z', location: 'Main', trips: 5, lastTripAt: '2026-09-30T10:00:00Z' },
    { pieceId: 'pc-3', barcode: 'TRC-0149', shortCode: 'BXT-0149', status: 'delivered', receivedAt: '2026-08-14T09:00:00Z', location: null, trips: 1, lastTripAt: '2026-09-02T10:00:00Z' },
  ] : [] }
}

function pieceHistory(): PieceHistory {
  const trip = (at: string, order: string, outcome: string, city: string, fee: number) => ({ at, orderId: 'o', orderNumber: order, trackingNumber: `7032${order.slice(1)}`, cityId: null, cityName: city, outcome, fee, feeEstimated: false })
  return { pieceId: 'pc-2', barcode: 'TRC-0148', shortCode: 'BXT-0148', status: 'available', variantId: 'v', sku: 'SKU-1', productTitle: 'Boxy Tee', variantTitle: 'Black / L',
    receivedAt: '2026-08-14T09:00:00Z', location: 'Main', trips: [
      trip('2026-08-21T10:00:00Z', '#4402', 'refused', 'Giza', 105), trip('2026-09-02T10:00:00Z', '#4519', 'other_terminal', 'Sharqia', 105),
      trip('2026-09-21T10:00:00Z', '#4655', 'delivered', 'Giza', 60)] }
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
  breakdowns: Record<string, Compared<Breakdown>>
  discounts: Compared<Discounts>
  heatmap: Compared<Heatmap>
  customerSummary: Compared<CustomerSummary>
  profitSummary: ProfitSummary
  profitByType: ProfitByType
  inventorySync: InventorySyncStatus
  failureReasons: Compared<FailureReasons>
  productExtras: Compared<ProductExtras>
  watch: CustomerWatch
  variants: VariantSales[]
  stuck: Stuck
  payouts: Payouts
  profitSkus: ProfitSkus
  orders: OrdersPage
  hasPieces: boolean
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
  breakdowns: breakdowns(1, true),
  discounts: discounts(1),
  heatmap: heatmap(1),
  customerSummary: customerSummary(0.08, true),
  profitSummary: profitSummary(false),
  profitByType: profitByType(false),
  inventorySync: inventorySync('ok'),
  failureReasons: failureReasons(168),
  productExtras: productExtras(),
  watch: watch(3),
  variants: broekVariants,
  stuck: stuck(3),
  payouts: payouts(1),
  profitSkus: profitSkus(broekVariants, false),
  orders: ordersPage(true, false),
  hasPieces: true,
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
  breakdowns: breakdowns(0.25, true),
  discounts: discounts(0.25),
  heatmap: heatmap(0.25),
  customerSummary: customerSummary(0.34, true),
  profitSummary: profitSummary(true),
  profitByType: profitByType(true),
  inventorySync: inventorySync('ok'),
  failureReasons: failureReasons(33),
  productExtras: productExtras(),
  watch: watch(1),
  variants: femineVariants,
  stuck: stuck(1),
  payouts: payouts(0.12),
  profitSkus: profitSkus(femineVariants, true),
  orders: ordersPage(true, true),
  hasPieces: false,
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
  breakdowns: breakdowns(0.08, false),
  discounts: discounts(0.08),
  heatmap: heatmap(0.08),
  customerSummary: customerSummary(0.1, false),
  profitSummary: profitSummary(false),
  profitByType: profitByType(false),
  inventorySync: inventorySync('access_denied'),
  failureReasons: failureReasons(0),
  productExtras: productExtras(),
  watch: watch(0),
  variants: highLineVariants,
  stuck: stuck(0),
  payouts: { range: RANGE, payouts: [] },
  profitSkus: profitSkus(highLineVariants, false),
  orders: ordersPage(false, false),
  hasPieces: true,
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
      case '/analytics/revenue/breakdown': {
        const by = new URLSearchParams(url.split('?')[1] ?? '').get('by') ?? ''
        return f.breakdowns[by] ? ok(f.breakdowns[by]) : errResponse(400)
      }
      case '/analytics/revenue/discounts': return ok(f.discounts)
      case '/analytics/revenue/heatmap': return ok(f.heatmap)
      case '/analytics/customers/summary': return ok(f.customerSummary)
      case '/analytics/profit/summary': return ok(f.profitSummary)
      case '/analytics/profit/by-product-type': return ok(f.profitByType)
      case '/analytics/inventory-sync/status': return ok(f.inventorySync)
      case '/analytics/delivery/failure-reasons': return ok(f.failureReasons)
      case '/analytics/products/extras': return ok(f.productExtras)
      case '/analytics/customers/watch': return ok(f.watch)
      case '/analytics/money/fees/extra': {
        const view = new URLSearchParams(url.split('?')[1] ?? '').get('groupBy') === 'awb' ? 'awb' : 'sku'
        return ok(extraFees(f.variants, view, f.bostaConnected ? 1 : 0))
      }
      case '/analytics/money/stuck': return ok(f.stuck)
      case '/analytics/money/payouts': return ok(f.payouts)
      case '/analytics/profit/skus': return ok(f.profitSkus)
      case '/analytics/orders': {
        const sp = new URLSearchParams(url.split('?')[1] ?? '')
        const status = sp.get('status'), q = sp.get('q')?.toLowerCase()
        const orders = f.orders.orders.filter(o => (!status || o.financialStatus === status)
          && (!q || o.name.toLowerCase().includes(q) || (o.customer ?? '').toLowerCase().includes(q)))
        const sort = sp.get('sort'), dir = sp.get('dir') === 'asc' ? 1 : -1
        const val = (o: OrderRow): number | null => sort === 'total' ? o.total : sort === 'bostaFees' ? o.bostaFees
          : sort === 'netToYou' ? o.netToYou : sort === 'placedAt' ? Date.parse(o.placedAt) : null
        if (sort) orders.sort((a, b) => ((val(a) ?? -Infinity) - (val(b) ?? -Infinity)) * dir)
        return ok({ ...f.orders, total: orders.length, orders })
      }
      default: {
        const m = /^\/analytics\/variants\/([^/]+)\/(orders|pieces)$/.exec(path)
        if (m && m[2] === 'orders') {
          return ok({ variantId: m[1], asOf: '2026-10-09T09:40:00Z', orders: f.orders.orders.slice(0, 5) } satisfies VariantOrders)
        }
        if (m && m[2] === 'pieces') return ok(variantPieces(m[1], f.hasPieces))
        if (/^\/analytics\/pieces\/[^/]+\/history$/.test(path)) return ok(pieceHistory())
        return errResponse(404)
      }
    }
  }
}
