# Privacy Policy

**Version 1.0 — Effective 1 July 2026**

> ⚠️ **Template — not legal advice.** Fill every `[PLACEHOLDER]` and have a qualified lawyer review this before publishing, especially the processor / sub-processor sections. Keep the `Version` and `Effective` lines accurate — the signup consent record stores the version a user accepted.

This Privacy Policy explains how **Traced** ("Traced", "we", "us") handles information in connection with the Traced platform (the "Service"). Traced gives businesses piece-level traceability for their physical inventory and shipments.

## 1. Who we are

Traced, a company established in Egypt, located at North Investors Area, Cairo, Egypt. For any privacy question, contact us at **hello@tracedtech.com**.

## 2. Our two roles

- **As a controller**, we decide how we handle data about the merchant who signs up for Traced — your account and business details.
- **As a processor**, we handle data about your end customers (the people you ship to) **on your behalf and on your instructions**. For that data, you are the controller and Traced acts as your processor. Your own agreement with your customers and your own privacy notice govern that relationship; this policy describes what we do as your processor.

## 3. Data we handle

**Merchant account data (we are controller):** business name, owner name, email address, hashed password, account settings (label size, language, timezone, pickup address), and subscription/billing details.

**Connected operational data (we are processor for you):** when you connect Shopify and Bosta, we ingest order, product, shipment, and delivery data needed to trace your inventory. This includes your **end customers'** names, phone numbers, delivery addresses, cash-on-delivery amounts, tracking/airway-bill numbers, and delivery status. We also store an immutable, per-piece chain-of-custody record (which piece moved, when, and which of your users handled it).

**Technical data (we are controller):** sign-in and security logs, IP addresses, and error/diagnostic telemetry. Error telemetry is processed through Sentry with personal data scrubbed before transmission.

## 4. How we use data

We use data only to provide and secure the Service: tracking item location and state, generating and printing labels, linking shipments, handling returns, surfacing exceptions and analytics, authenticating users, preventing abuse, and meeting legal obligations. We do **not** sell personal data, and we do **not** use your end customers' data for our own purposes.

## 5. Legal bases (where GDPR applies)

We rely on: performance of our contract with you (to provide the Service); our legitimate interests (securing the Service, preventing fraud); and your documented instructions where we act as your processor. You are responsible for having a lawful basis and appropriate notice for the end-customer data you route into Traced.

## 6. Where data is processed and our sub-processors

The Service is hosted in the European Union. We rely on the following sub-processors:

| Sub-processor | Purpose | Location |
|---|---|---|
| Hetzner | Application hosting | Germany (EU) |
| Supabase | Managed PostgreSQL database | Ireland (EU) |
| Cloudflare | Edge / content delivery / DDoS protection | Global edge |
| Sentry | Error monitoring (PII-scrubbed) | Egypt |
| Shopify | Source of order/product data (your connected store) | per Shopify |
| Bosta | Courier integration (delivery data) | Egypt |

Data may be transferred between Egypt and the EU to operate the Service. Where personal data is transferred out of the EEA, we rely on an appropriate transfer mechanism such as the European Commission's Standard Contractual Clauses or an adequacy decision.

## 7. Retention

We retain account and operational data for as long as your account is active and for a reasonable period afterward to meet legal, accounting, and security obligations, after which it is deleted or anonymized. End-customer personal data is erased on a valid redaction request (see Section 9). Per-piece custody events are designed to be tamper-evident and do not contain end-customer personal data.

## 8. Security

We protect data with encryption in transit (TLS) and encryption of sensitive fields at rest (AES-256-GCM), including encryption of our backups. We keep test and production data separate, enforce strict per-tenant data isolation (row-level security), grant database and staff access on a least-privilege, need-to-know basis, and maintain access controls and audit logging. We maintain an incident response process and will notify you of incidents affecting your data as required by law. No system is perfectly secure, but we work to protect your data.

## 9. Your rights and your customers' rights

Depending on your location, you may have rights to access, correct, export, delete, or restrict processing of your personal data, and to object or complain to a supervisory authority. To exercise these, contact **hello@tracedtech.com**.

For **end-customer** requests, because we act as your processor, requests are handled through you as the controller. We support Shopify's data-request, customer-redact, and shop-redact webhooks: a valid redaction erases the relevant end-customer personal data from orders and stored raw payloads, while the tamper-evident custody log (which holds no end-customer personal data) is preserved.

## 10. Cookies

We use only the cookies and local mechanisms necessary to sign you in and keep your session secure. We do not use advertising cookies.

## 11. Children

The Service is for businesses and is not directed to children. We do not knowingly collect data from children.

## 12. Changes

We may update this policy. We will change the version and effective date above, and where changes are material we will notify account owners. Continued use after an update means you accept the revised policy.

## 13. Shopify-connected stores

When you connect a Shopify store, the following applies:

- **What we access through Shopify's APIs:** order and product data, and — only where you grant access and Shopify approves it — protected customer data (customer name, phone, address, and email). We request only the minimum customer data fields needed to provide traceability, consistent with Shopify's data-minimization requirements, and we use that data solely for the purposes described in this policy (purpose limitation). We do not use it to build profiles, for advertising, or for any unrelated purpose.
- **What we collect directly from you:** your account and business details (see Section 3).
- **Automated logs:** we generate operational and security logs relating to your use of the Service (see Section 3).
- **Consent and opt-out:** as the controller of your customers' data, you are responsible for obtaining any consent required in your customers' jurisdictions and for honoring their opt-out and data-sharing decisions; we apply those decisions when you communicate them to us. We honor Shopify's `customers/data_request`, `customers/redact`, and `shop/redact` webhooks — a valid redaction erases the relevant customer personal data from orders and stored raw payloads.
- **Shopify's own role:** Shopify processes personal data under its own privacy policy (https://www.shopify.com/legal/privacy) and Data Processing Addendum. Your agreement with Shopify governs Shopify's processing.

## 14. Contact

Traced — North Investors Area, Cairo, Egypt — **hello@tracedtech.com**
