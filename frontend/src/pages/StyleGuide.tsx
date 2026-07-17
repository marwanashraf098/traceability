import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { MoreHorizontal } from 'lucide-react'
import {
  Badge, OrderBadge, SeverityBadge,
  Card, StatCard,
  Button,
  Input,
  Spinner,
  EmptyState,
  Modal,
  Select,
  Checkbox,
  Radio,
  Toggle,
  Tabs,
  SegmentedControl,
  FilterChip,
  Tooltip,
  Avatar,
  Alert,
  useToast,
  Progress,
  Skeleton, TableSkeleton,
  DataTable,
  type DataTableColumn,
  type SelectOption,
} from '../components/ui'

// ── Demo data ─────────────────────────────────────────────────────────────────

interface ShipmentRow { id: string; number: string; status: string; origin: string; destination: string; items: number; eta: string; progress: number }

const SHIPMENTS: ShipmentRow[] = [
  { id: '1', number: 'SHP-0001', status: 'with_courier',  origin: 'Cairo',   destination: 'Alex',    items: 3, eta: 'Jul 18', progress: 65 },
  { id: '2', number: 'SHP-0002', status: 'delivered',     origin: 'Giza',    destination: 'Mansoura',items: 1, eta: 'Jul 17', progress: 100 },
  { id: '3', number: 'SHP-0003', status: 'exception',     origin: 'Shubra',  destination: 'Tanta',   items: 2, eta: 'Jul 20', progress: 40 },
  { id: '4', number: 'SHP-0004', status: 'return_in_transit', origin: 'Alex', destination: 'Cairo',  items: 1, eta: 'Jul 19', progress: 20 },
]

const SELECT_OPTIONS: SelectOption[] = [
  { value: 'cairo',    label: 'Cairo' },
  { value: 'alex',     label: 'Alexandria' },
  { value: 'giza',     label: 'Giza' },
  { value: 'mansoura', label: 'Mansoura' },
]

const TABLE_COLS: DataTableColumn<ShipmentRow>[] = [
  { key: 'number',      header: 'Shipment ID',   mono: true,   render: r => r.number },
  { key: 'status',      header: 'Status',                      render: r => <Badge status={r.status} /> },
  { key: 'origin',      header: 'Origin',                      render: r => r.origin },
  { key: 'destination', header: 'Destination',                 render: r => r.destination },
  { key: 'items',       header: 'Items',          align: 'end', render: r => r.items },
  { key: 'eta',         header: 'ETA',                         render: r => r.eta },
  { key: 'progress',    header: 'Progress',                    render: r => <Progress value={r.progress} className="w-24" /> },
  {
    key: 'actions', header: '', align: 'end',
    render: () => (
      <button className="text-muted hover:text-primary p-1 transition-colors">
        <MoreHorizontal size={16} strokeWidth={2} />
      </button>
    ),
  },
]

// ── Section wrapper ───────────────────────────────────────────────────────────

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="space-y-4">
      <h2 className="text-h3 text-primary border-b border-line pb-3">{title}</h2>
      <div className="space-y-4">{children}</div>
    </section>
  )
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-wrap items-center gap-3">
      <span className="text-small text-muted w-32 flex-shrink-0">{label}</span>
      <div className="flex flex-wrap items-center gap-3">{children}</div>
    </div>
  )
}

// ── StyleGuide ────────────────────────────────────────────────────────────────

export default function StyleGuide() {
  const { i18n } = useTranslation()
  const { toast } = useToast()

  // local state for interactive components
  const [selectVal,   setSelectVal]   = useState<string | undefined>('cairo')
  const [checked,     setChecked]     = useState(false)
  const [radio,       setRadio]       = useState('a')
  const [toggled,     setToggled]     = useState(false)
  const [activeTab,   setActiveTab]   = useState('all')
  const [segment,     setSegment]     = useState('week')
  const [chips,       setChips]       = useState(['Cairo', 'In Transit'])
  const [modal,       setModal]       = useState(false)
  const [alertDismissed, setAlertDismissed] = useState(false)

  function toggleLang() {
    const next = i18n.language === 'en' ? 'ar' : 'en'
    i18n.changeLanguage(next)
    localStorage.setItem('lang', next)
    document.documentElement.dir  = next === 'ar' ? 'rtl' : 'ltr'
    document.documentElement.lang = next
  }

  return (
    <div className="min-h-screen bg-bg text-primary p-8 space-y-12">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-h1 text-primary">Design System v1.0</h1>
          <p className="text-body text-muted mt-1">Component gallery — every component, every state</p>
        </div>
        <button
          onClick={toggleLang}
          className="btn-outline px-4 py-2 text-body rounded-xl border border-line"
        >
          {i18n.language === 'en' ? 'العربية' : 'English'}
        </button>
      </div>

      {/* ── Badges ── */}
      <Section title="Badge">
        <Row label="Piece statuses">
          <Badge status="available" />
          <Badge status="with_courier" />
          <Badge status="return_in_transit" />
          <Badge status="damaged" />
          <Badge status="destroyed" />
        </Row>
        <Row label="Tone override">
          <Badge tone="success" label="Success" />
          <Badge tone="info" label="Info" />
          <Badge tone="warning" label="Warning" />
          <Badge tone="critical" label="Critical" />
          <Badge tone="neutral" label="Neutral" />
        </Row>
        <Row label="Order status">
          <OrderBadge status="new" />
          <OrderBadge status="confirmed" />
          <OrderBadge status="with_courier" />
          <OrderBadge status="delivered" />
          <OrderBadge status="returning" />
          <OrderBadge status="cancelled" />
        </Row>
        <Row label="Severity">
          <SeverityBadge severity="CRITICAL" />
          <SeverityBadge severity="HIGH" />
          <SeverityBadge severity="MEDIUM" />
          <SeverityBadge severity="LOW" />
        </Row>
      </Section>

      {/* ── Button ── */}
      <Section title="Button">
        <Row label="primary">
          <Button variant="primary">Default</Button>
          <Button variant="primary" loading>Loading</Button>
          <Button variant="primary" disabled>Disabled</Button>
        </Row>
        <Row label="secondary">
          <Button variant="secondary">Default</Button>
          <Button variant="secondary" disabled>Disabled</Button>
        </Row>
        <Row label="tertiary">
          <Button variant="tertiary">Default</Button>
          <Button variant="tertiary" disabled>Disabled</Button>
        </Row>
        <Row label="destructive">
          <Button variant="destructive">Delete</Button>
          <Button variant="destructive" disabled>Disabled</Button>
        </Row>
        <Row label="sizes">
          <Button size="sm">Small</Button>
          <Button size="md">Medium</Button>
          <Button size="lg">Large</Button>
        </Row>
      </Section>

      {/* ── Input ── */}
      <Section title="Input">
        <div className="max-w-sm space-y-3">
          <Input placeholder="Default input" />
          <Input placeholder="Filled" defaultValue="marwan@example.com" />
          <Input placeholder="With error" invalid error="This field is required" />
          <Input placeholder="Disabled" disabled />
          <Input placeholder="Scan variant" variant="scan" />
        </div>
      </Section>

      {/* ── Select ── */}
      <Section title="Select">
        <div className="max-w-xs space-y-3">
          <Select
            value={selectVal}
            onChange={setSelectVal}
            options={SELECT_OPTIONS}
            placeholder="Choose a city…"
          />
          <Select
            value={undefined}
            onChange={() => {}}
            options={SELECT_OPTIONS}
            placeholder="With create"
            allowCreate
            onCreate={label => alert(`Create: ${label}`)}
          />
          <Select
            value={undefined}
            onChange={() => {}}
            options={SELECT_OPTIONS}
            placeholder="Disabled"
            disabled
          />
        </div>
      </Section>

      {/* ── Checkbox ── */}
      <Section title="Checkbox">
        <Row label="states">
          <Checkbox checked={false} onChange={() => {}} label="Unchecked" />
          <Checkbox checked={checked} onChange={setChecked} label="Checked" />
          <Checkbox checked={false} onChange={() => {}} label="Indeterminate" indeterminate />
          <Checkbox checked={false} onChange={() => {}} label="Disabled" disabled />
        </Row>
      </Section>

      {/* ── Radio ── */}
      <Section title="Radio">
        <Row label="states">
          <Radio value="a" checked={radio === 'a'} onChange={setRadio} label="Option A" />
          <Radio value="b" checked={radio === 'b'} onChange={setRadio} label="Option B" />
          <Radio value="c" checked={radio === 'c'} onChange={setRadio} label="Option C" disabled />
        </Row>
      </Section>

      {/* ── Toggle ── */}
      <Section title="Toggle">
        <Row label="md size">
          <Toggle checked={false} onChange={() => {}} label="Off" />
          <Toggle checked={toggled} onChange={setToggled} label="On" />
          <Toggle checked={false} onChange={() => {}} label="Disabled" disabled />
        </Row>
        <Row label="sm size">
          <Toggle checked={false} onChange={() => {}} size="sm" label="Off" />
          <Toggle checked={true} onChange={() => {}} size="sm" label="On" />
        </Row>
      </Section>

      {/* ── Tabs ── */}
      <Section title="Tabs">
        <Tabs
          tabs={[
            { key: 'all',        label: 'All' },
            { key: 'in_transit', label: 'In Transit' },
            { key: 'delivered',  label: 'Delivered' },
            { key: 'exceptions', label: 'Exceptions' },
            { key: 'returns',    label: 'Returns' },
          ]}
          activeKey={activeTab}
          onChange={setActiveTab}
        />
        <p className="text-small text-muted">Active tab: {activeTab}</p>
      </Section>

      {/* ── SegmentedControl ── */}
      <Section title="SegmentedControl">
        <SegmentedControl
          options={[
            { value: 'week',   label: 'This week' },
            { value: '7days',  label: 'Last 7 days' },
            { value: 'month',  label: 'This month' },
            { value: 'custom', label: 'Custom' },
          ]}
          value={segment}
          onChange={setSegment}
        />
      </Section>

      {/* ── FilterChip ── */}
      <Section title="FilterChip">
        <Row label="chips">
          {chips.map(c => (
            <FilterChip key={c} label={c} onRemove={() => setChips(prev => prev.filter(x => x !== c))} />
          ))}
          <FilterChip dateRange={{ start: 'Jul 1', end: 'Jul 17' }} onRemove={() => {}} />
        </Row>
      </Section>

      {/* ── Tooltip ── */}
      <Section title="Tooltip">
        <Row label="hover me">
          <Tooltip content="Keyboard shortcut: ⌘K">
            <Button variant="secondary">Hover for tooltip</Button>
          </Tooltip>
          <Tooltip title="Scan barcode" content="Point the scanner at the barcode on the package">
            <Button variant="secondary">With title</Button>
          </Tooltip>
        </Row>
      </Section>

      {/* ── Avatar ── */}
      <Section title="Avatar">
        <Row label="sizes">
          <Avatar name="Marawan Ashraf" role="Admin" size="md" />
          <Avatar name="Ahmed Hassan" size="sm" />
        </Row>
      </Section>

      {/* ── Alert ── */}
      <Section title="Alert">
        <div className="max-w-lg space-y-3">
          <Alert tone="success" title="Settlement confirmed" />
          <Alert tone="info"    title="Shopify sync in progress" dismissible onDismiss={() => {}} />
          <Alert tone="warning" title="3 unmatched fees need resolution">
            Resolve them before running the settlement wizard.
          </Alert>
          {!alertDismissed && (
            <Alert tone="critical" title="RLS policy error detected" dismissible onDismiss={() => setAlertDismissed(true)}>
              Check that SET LOCAL GUC is properly set for this tenant.
            </Alert>
          )}
        </div>
      </Section>

      {/* ── Toast ── */}
      <Section title="Toast">
        <Row label="fire one">
          <Button variant="primary"     onClick={() => toast({ tone: 'success', message: 'Settlement created successfully' })}>Success</Button>
          <Button variant="secondary"   onClick={() => toast({ tone: 'info',    message: 'Syncing Shopify inventory…' })}>Info</Button>
          <Button variant="secondary"   onClick={() => toast({ tone: 'warning', message: '3 unmatched fees remain' })}>Warning</Button>
          <Button variant="destructive" onClick={() => toast({ tone: 'error',   message: 'Failed to create shipment', action: { label: 'Retry', onClick: () => {} } })}>Error + action</Button>
        </Row>
      </Section>

      {/* ── Progress ── */}
      <Section title="Progress">
        <div className="max-w-xs space-y-4">
          <div>
            <p className="text-small text-muted mb-2">0%</p>
            <Progress value={0} />
          </div>
          <div>
            <p className="text-small text-muted mb-2">65%</p>
            <Progress value={65} />
          </div>
          <div>
            <p className="text-small text-muted mb-2">100%</p>
            <Progress value={100} />
          </div>
          <div>
            <p className="text-small text-muted mb-2">Indeterminate</p>
            <Progress />
          </div>
        </div>
      </Section>

      {/* ── Skeleton ── */}
      <Section title="Skeleton">
        <Row label="blocks">
          <Skeleton className="h-4 w-32" />
          <Skeleton className="h-4 w-24" />
          <Skeleton className="h-8 w-8 rounded-full" />
        </Row>
        <div className="max-w-2xl">
          <p className="text-small text-muted mb-2">TableSkeleton (5 rows, 4 cols)</p>
          <TableSkeleton rows={5} cols={4} />
        </div>
      </Section>

      {/* ── Card / StatCard ── */}
      <Section title="Card / StatCard">
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 max-w-2xl">
          <StatCard label="Total orders"  value={1284}  delta={12}  deltaLabel="this week" />
          <StatCard label="In transit"    value={342}   delta={-8}  deltaLabel="vs last week" />
          <StatCard label="Revenue (EGP)" value="67,839" accent />
        </div>
        <div className="max-w-sm">
          <Card interactive>
            <p className="text-body text-primary font-medium">Interactive card</p>
            <p className="text-small text-muted mt-1">Hover to see shadow + border transition.</p>
          </Card>
        </div>
      </Section>

      {/* ── Modal ── */}
      <Section title="Modal">
        <Button variant="secondary" onClick={() => setModal(true)}>Open modal</Button>
        {modal && (
          <Modal title="Confirm action" onClose={() => setModal(false)}>
            <p className="text-body text-muted mb-6">Are you sure you want to delete this settlement? This cannot be undone.</p>
            <div className="flex justify-end gap-3">
              <Button variant="secondary" onClick={() => setModal(false)}>Cancel</Button>
              <Button variant="destructive" onClick={() => setModal(false)}>Delete</Button>
            </div>
          </Modal>
        )}
      </Section>

      {/* ── Spinner / EmptyState ── */}
      <Section title="Spinner / EmptyState">
        <Row label="Spinner">
          <Spinner size={16} />
          <Spinner size={24} />
          <Spinner size={32} />
        </Row>
        <div className="bg-panel rounded-2xl border border-line max-w-sm">
          <EmptyState message="No shipments found" action={{ label: 'Clear filters', onClick: () => {} }} />
        </div>
      </Section>

      {/* ── DataTable ── */}
      <Section title="DataTable">
        <div className="bg-panel rounded-2xl border border-line overflow-hidden">
          <DataTable columns={TABLE_COLS} rows={SHIPMENTS} />
        </div>
        <div className="bg-panel rounded-2xl border border-line overflow-hidden mt-4">
          <p className="text-small text-muted px-4 pt-3">Loading state</p>
          <DataTable columns={TABLE_COLS} rows={[]} loading />
        </div>
        <div className="bg-panel rounded-2xl border border-line overflow-hidden mt-4">
          <DataTable columns={TABLE_COLS} rows={[]} emptyMessage="No shipments found" />
        </div>
      </Section>
    </div>
  )
}
