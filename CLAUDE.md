# Smart Choices — Odoo 17 Implementation

## Project Overview

**Company**: Smart Choices (Egypt)
**Platform**: Noon marketplace (Egypt)
**System**: Custom Odoo 17 Community module (`ecom_mgmt`)
**Server**: Oracle Cloud VM — `http://193.122.88.214:8069`
**SSH**: `ssh -i ~/.ssh/smart-choices-odoo.key ubuntu@193.122.88.214`
**Local**: `~/Documents/smart-choices-odoo/` (Docker on Mac M3)

---

## Business Model

4 ownership types for products:

| Owner | Commission | Notes |
|---|---|---|
| Smart Choices | N/A | Own inventory, COGS tracked |
| Ashy | SC keeps 15%, Ashy gets **85%** | Brother |
| Vantage | SC keeps 30%, Vantage gets **70%** | Partner |
| Afyouni | Cost price per unit | No commission, running balance |

**Fulfillment types**: FBN (Noon warehouse) and FBP (own warehouse)

---

## Infrastructure

```
Mac (local dev)          Oracle Cloud (production)
Docker Compose           Ubuntu 20.04, Docker
smart-choices DB         smart-choices DB
localhost:8069           193.122.88.214:8069
```

**Deploy changes:**
```bash
scp -i ~/.ssh/smart-choices-odoo.key \
  addons/ecom_mgmt/wizards/settlement_wizard.py \
  ubuntu@193.122.88.214:~/smart-choices-odoo/addons/ecom_mgmt/wizards/

ssh -i ~/.ssh/smart-choices-odoo.key ubuntu@193.122.88.214
cd ~/smart-choices-odoo && sudo docker-compose restart odoo
```

---

## Module Structure

```
addons/ecom_mgmt/
├── __manifest__.py
├── models/
│   ├── product_extension.py    # ownership_type, noon_sku, partner_cost_price
│   ├── noon_transaction.py     # NoonTransaction, NoonUnmatchedFee
│   └── settlement.py           # NoonSettlement, AfyouniSettlement, VendorExpense
├── wizards/
│   ├── noon_importer.py        # CSV import + classification
│   └── settlement_wizard.py    # Commission calculation engine
├── views/
│   ├── product_views.xml
│   ├── noon_transaction_views.xml
│   └── settlement_views.xml
└── security/ir.model.access.csv
```

---

## Settlement Wizard Logic

**File**: `wizards/settlement_wizard.py` → `_compute_commission_data()`

The wizard builds commission basis per order using these steps:

1. **Sales/Returns** — filtered by ownership type and month
2. **FBP fees** — `fbp_shipping_fee` + `shipping_credit_only`, applied **ONCE per order** (not per item)
3. **Late fees** — `fbn_late_fee` + `late_fee_adjustment` + `credit` for same-month orders
4. **Unmatched FBP fees** — manually resolved fees with `owner_override_type` set
5. **Order adjustments** — by order_nr OR by product ownership (for SKU-based adjustments)
6. **FBN late fees with SKU** — use product ownership directly
7. **Misc cross-month fees** — `late_fee_adjustment` + `credit` for orders whose sale is in a different month (lookup by order_nr across all dates)

**Commission basis formula:**
```
basis = net_proceeds + referral_fee + fbn_fulfillment_fee + shipping_credit + fbp_fee (once) + late_fees
```

**Rates:**
- Ashy: 85% of basis (SC keeps 15%)
- Vantage: 70% of basis (SC keeps 30%)

---

## Transaction Classifications

| Classification | Description |
|---|---|
| `sale` | Normal sale with SKU, net > 0 |
| `return` | order_update with net < 0 |
| `cancellation` | order with SKU, net < 0 |
| `fbp_shipping_fee` | order without SKU, fulfillment fee < 0 |
| `shipping_credit_only` | order without SKU, shipping_credit > 0, fee = 0 |
| `fbn_late_fee` | order with SKU, net = 0, fee < 0 |
| `order_adjustment` | order with SKU, net = 0, fee = 0, total < 0 |
| `late_fee_adjustment` | order_update with net = 0, fee < 0 |
| `credit` | order_update with total > 0 (Noon Credit) |
| `referral_fee_correction` | order_update with referral < 0 |

---

## Known Issues & Rules

### Duplicate Detection
- **BROKEN**: Item Nr is always empty in Noon CSVs
- Current workaround: manual check before importing
- TODO: Fix using composite key (Order Nr + SKU + Type + Total + Date)

### FBP Fee Rule
- Noon charges ONE FBP shipping fee per ORDER, not per item
- Multi-item orders: apply FBP fee to FIRST item only
- Code: `order_txs = defaultdict(list)` grouping in wizard

### Cross-Month Fees
- Late fees, credits, adjustments can appear in a different month than the original sale
- Handled by `misc_fees` block — searches by order_nr across ALL dates
- Transaction date determines which settlement month it belongs to

### Unmatched Fees
- FBP fees without a matching sale row → go to **Unmatched Fees** view
- Manually assign `owner_override_type` to resolve
- After resolution: included in that owner's settlement as standalone lines
- **Must resolve BEFORE running settlement wizard**

### Owner Override for Shared SKUs
- Some SKUs (e.g. Demolition Hammer) are shared between Ashy and Smart Choices
- Personal/owner orders must be manually excluded from settlement
- Example: NEGI50018963851 (Demolition Hammer) — owner's personal order, exclude from Ashy May settlement

---

## Monthly Workflow

### Weekly (after each Noon settlement batch)
1. Download Noon CSV from Seller Lab
2. **Ecom Management → Noon → Import CSV**
3. Preview → verify 0 unclassified
4. Confirm import
5. Go to **Unmatched Fees** → resolve all before next step
6. Classify **Pending Returns** (Sellable → FBN stock, Damaged → Damaged location)

### Monthly Settlement
1. Clear all pending returns
2. Resolve ALL unmatched fees
3. **Run Settlement Wizard** → select month → Calculate → verify numbers → Create
4. Review settlement lines — manually remove owner personal orders if any
5. Confirm settlement → vendor bill auto-created
6. Add any **Vendor Expenses** (packaging, shipping, advances) → link to settlement → Confirm
7. Pay vendor → Register Payment on vendor bill

---

## Validated Settlements

| Month | Basis (EGP) | Ashy Gets (85%) | Status |
|---|---|---|---|
| March 2026 | 26,640.90 | 22,644.77 | ✅ Confirmed |
| April 2026 | ~60,245 | ~51,208 | Needs recreation with fixed wizard |
| May 2026 | 61,316.79 | 52,119.27 | Needs recreation (exclude NEGI50018963851) |

---

## Key Database Queries

**Check Ashy basis for a month:**
```sql
SELECT 
    SUM(nt.net_proceeds + nt.referral_fee + nt.fulfillment_fee + nt.shipping_credit) as sale_basis
FROM noon_transaction nt
JOIN product_product pp ON pp.id = nt.product_id
JOIN product_template pt ON pt.id = pp.product_tmpl_id
WHERE nt.classification IN ('sale', 'return')
AND nt.transaction_date >= '2026-05-01'
AND nt.transaction_date <= '2026-05-31'
AND pt.ownership_type = 'ashy';
```

**Check unmatched fees:**
```sql
SELECT order_nr, fulfillment_fee, shipping_credit, owner_override_type, is_unmatched
FROM noon_transaction
WHERE is_unmatched = true
AND transaction_date >= '2026-05-01';
```

**Check FBP fees for Ashy orders:**
```sql
SELECT COUNT(*), SUM(fulfillment_fee + shipping_credit)
FROM noon_transaction
WHERE classification = 'fbp_shipping_fee'
AND transaction_date >= '2026-05-01'
AND transaction_date <= '2026-05-31'
AND order_nr IN (
    SELECT DISTINCT nt.order_nr FROM noon_transaction nt
    JOIN product_product pp ON pp.id = nt.product_id
    JOIN product_template pt ON pt.id = pp.product_tmpl_id
    WHERE nt.classification IN ('sale','return')
    AND pt.ownership_type = 'ashy'
);
```

---

## Pending Items

1. **Fix May settlement** — delete and recreate, exclude NEGI50018963851
2. **Fix April settlement** — delete and recreate with updated wizard
3. **Fix duplicate detection** — use composite key instead of item_nr
4. **Vendor expense → bill deduction** — fix `_compute_tax_totals()` call
5. **Enter WH/SC warehouse stock** quantities
6. **Zoho Books** setup (optional, for external P&L reporting)
7. **DuckDNS** free domain setup (optional)

---

## SKU Profit Report (Jan–May 2026)

| Metric | Amount (EGP) |
|---|---|
| Total Revenue | 283,678 |
| Total Noon Fees | -46,844 |
| Net After Fees | 236,834 |
| Owner Share (paid out) | 168,994 |
| **Your Net Profit** | **67,839** |

---

## Afyouni Running Balance

- Total owed (Jan–Apr 2026): **47,140 EGP**
- Tracked in **Afyouni Statements** (cost price per unit sold)
- No commission — pays cost price only

---

## Session ritual

At the **start** of every session: read `docs/PROGRESS.md` before anything else.

At the **end** of every session (without being asked):
1. Update `docs/PROGRESS.md` — current state, next up, any new decisions, any new gotchas.
2. Tick completed items in `docs/requirements-checklist.md`.

---

## Environment notes

**TenantContext is a ThreadLocal**: it does NOT propagate across `@Async` methods, executor-submitted tasks, parallel streams, or `CompletableFuture` chains. Any background work that reads or writes tenant data must be wrapped in `TenantContext.runAs(tenantId, ...)` so the context is explicitly set and cleared. Forgetting this causes silent zero-row results under RLS — not an exception, just missing data.

**Docker Desktop Mac M3 + Testcontainers**: `DockerDesktopMacStrategy` in `src/test/java/com/traceability/` forces docker-java API v1.41 — do not delete it; Docker Desktop rejects the default v1.24 negotiation with HTTP 400 and the fix requires overriding `test()`, `getClient()`, AND `getDockerClient()` on the strategy class.
