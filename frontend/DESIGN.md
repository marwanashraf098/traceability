# Traced — Design System

**Product**: Traced ("nothing moves untraced")  
**Theme**: Dark-first. Every surface defaults to dark. No light-mode variant.  
**Fonts**: Inter (Latin/EN) · Cairo (Arabic/RTL, auto-applied via `[dir=rtl]`)

---

## Color tokens

All tokens are available as Tailwind utilities (`bg-{token}`, `text-{token}`, `border-{token}`).

| Token      | Hex       | Usage |
|------------|-----------|-------|
| `base`     | `#0B1220` | Deepest background — `<body>`, sidebar, full-screen scanner |
| `panel`    | `#1E293B` | Card, modal, panel background |
| `elevated` | `#253449` | Hover state, nested cards, dropdown backgrounds |
| `line`     | `#2D3F55` | Border, divider |
| `primary`  | `#F8FAFC` | Main text (near-white) |
| `muted`    | `#647488` | Secondary text, meta, labels |
| `brand`    | `#6366FF` | CTA buttons, active nav, links, brand accent |
| `accent`   | `#3882F6` | Secondary highlights, shipment status |
| `cyan`     | `#22D3EE` | Tertiary accent, courier/tracking |
| `success`  | `#22C55E` | Delivered, available, positive delta |
| `warning`  | `#F59E0B` | Pending, reserved, caution |
| `danger`   | `#EF4444` | Lost, damaged, error, negative delta |

### Opacity modifiers
Use Tailwind's `/` opacity modifier for subtle badge backgrounds on dark surfaces:
- `bg-brand/10` — brand-tinted badge background
- `bg-success/10` — success badge background
- `text-brand border border-brand/20` — badge text + border

---

## Typography scale

Defined in `tailwind.config.js` as custom font sizes:

| Class     | Size / Leading / Weight | Usage |
|-----------|------------------------|-------|
| `text-display` | 36px / 40px / 300 | Hero numbers, large stat values |
| `text-h1`      | 24px / 32px / 600 | Page titles |
| `text-h2`      | 20px / 28px / 600 | Section titles |
| `text-h3`      | 16px / 24px / 600 | Card titles |
| `text-body`    | 14px / 24px / 400 | Body copy, table cells |
| `text-small`   | 12px / 20px / 400 | Meta info, secondary labels |
| `text-caption` | 11px / 16px / 400 | Column headers (uppercase + tracking) |

**Wordmark**: `<span class="text-primary font-light text-2xl tracking-tight"><span class="text-brand">tr</span>aced</span>`

---

## Spacing & radius

Standard card pattern:
```
bg-panel rounded-xl border border-line p-5   (default card)
bg-panel rounded-lg border border-line p-4   (compact card)
bg-elevated rounded-lg border border-line    (nested / hover)
```

---

## Shadows

| Class        | Usage |
|--------------|-------|
| `shadow-card`    | Default card elevation |
| `shadow-elevated`| Modal, dropdown, tooltip |
| `shadow-brand`   | Glow around active brand elements |
| `shadow-glow`    | Focus ring for brand inputs |

---

## Status badges (piece & order)

On dark surfaces, use the `/10` opacity trick:

```tsx
// Reusable pattern:
<span className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-small
                 bg-success/10 text-success border border-success/20">
  Available
</span>
```

| Status | Color token |
|--------|-------------|
| available, delivered | `success` |
| reserved, returning | `warning` |
| packed, awaiting_pickup | `brand` |
| with_courier | `cyan` |
| lost, damaged, cancelled | `danger` |
| destroyed | `muted` |

---

## Sidebar nav

Active item: `bg-brand/10 text-primary border-s-2 border-brand`  
Inactive item: `text-muted hover:bg-elevated hover:text-primary`  
Icon size: 18×18px (SVG)

---

## Scanner screens (Fulfill / Receiving)

Full-screen, `bg-base`. Flash overlay on scan:
- Success: `bg-success/30` animated with `animate-flash`
- Error: `bg-danger/30` animated with `animate-flash`

Scan input: large, `bg-panel border-2 border-brand/40 text-primary font-mono text-lg`
On focus: `border-brand shadow-glow`

---

## RTL (Arabic / Cairo)

When `document.documentElement.dir = 'rtl'`:
- Font switches to Cairo via `[dir="rtl"]` selector in `index.css`
- Use logical CSS properties: `ps-` `pe-` `ms-` `me-` `border-s` `border-e` `rounded-s` `rounded-e`
- `start-` / `end-` for absolute positioning

Never use `left-` / `right-` / `pl-` / `pr-` in components.
