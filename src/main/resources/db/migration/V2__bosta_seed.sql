-- ============================================================
-- V2 — Seed: Bosta state mapping (§8.3) + NDR codes (§8.4)
-- ============================================================

-- ---- Bosta state → internal state mapping (§8.3) -----------
-- PK is (state_code, applies_to_order_type).
-- Code 41 has two rows: its piece effect differs by delivery type.

INSERT INTO bosta_state_mappings
    (state_code, applies_to_order_type, bosta_state,
     internal_shipment_state, piece_status_after, notes)
VALUES
-- Pre-transit
(10,  'ALL',  'Pickup requested',
      'created',      NULL,
      'Pickup scheduled; pieces already packed/awaiting_pickup'),
(20,  'ALL',  'Route assigned',
      'created',      'awaiting_pickup',
      NULL),
-- Picked up / in motion
(21,  'ALL',  'Picked up from business',
      'with_courier', 'with_courier',
      'FR-10.3 trigger: order→with_courier; custody event handed_to_courier'),
(24,  'ALL',  'Received at warehouse',
      'with_courier', NULL,
      NULL),
(30,  'ALL',  'In transit between hubs',
      'with_courier', NULL,
      NULL),
-- Code 41 is type-dependent (SEND = out for delivery; RTO = out for return)
(41,  'SEND', 'Out for delivery',
      'with_courier', NULL,
      NULL),
(41,  'RTO',  'Out for return',
      'returning',    'return_in_transit',
      NULL),
-- Terminal delivery
(45,  'ALL',  'Delivered',
      'delivered',    'delivered',
      'Terminal. isConfirmedDelivery flag stored on shipment.'),
(46,  'ALL',  'Returned to business',
      'returned',     'return_pending_inspection',
      'Starts FR-12.4 never-received detection clock.'),
-- Exception / problem states
(47,  'ALL',  'Exception',
      'exception',    NULL,
      'exceptionCode→NDR table; piece unchanged until manager resolves'),
(48,  'ALL',  'Terminated',
      'terminated',   NULL,
      'Requires manager resolution'),
(49,  'ALL',  'Canceled',
      'cancelled',    NULL,
      'Runs guided unpack flow if pieces are still packed'),
(100, 'ALL',  'Lost',
      'lost',         'lost',
      'Terminal; raises exception entry'),
(101, 'ALL',  'Damaged',
      'exception',    NULL,
      'Manager resolves to damaged'),
(102, 'ALL',  'Investigation',
      'exception',    NULL,
      NULL),
(103, 'ALL',  'Awaiting your action (return failed 3×)',
      'exception',    NULL,
      'Inventory in limbo at Bosta hub; FR-15.3 state-103 detector'),
(105, 'ALL',  'On hold',
      'with_courier', NULL,
      'Piece unchanged; exception flag raised'),
-- CRP / Exchange / Cash-collection / Archived — mapped defensively
(22,  'ALL',  'CRP out for pickup',
      'with_courier', NULL,
      'Customer return pickup; no piece flow in MVP'),
(23,  'ALL',  'CRP received',
      'with_courier', NULL,
      'Log as unlinked exception'),
(25,  'ALL',  'Cash collection',
      'with_courier', NULL,
      NULL),
(40,  'ALL',  'Exchange',
      'with_courier', NULL,
      'Log as unlinked exception in MVP'),
(60,  'ALL',  'Fulfillment',
      'with_courier', NULL,
      NULL),
(104, 'ALL',  'Archived',
      'cancelled',    NULL,
      NULL);

-- ---- NDR exception codes (§8.4) ----------------------------

INSERT INTO ndr_codes (code, category, description, severity)
VALUES
-- Forward (delivery attempt failures)
(1,  'forward', 'Customer not at address',        'normal'),
(2,  'forward', 'Changed address',                'normal'),
(3,  'forward', 'Postponed by customer',          'normal'),
(4,  'forward', 'Customer wants to open parcel',  'normal'),
(5,  'forward', 'Data modification needed',       'normal'),
(6,  'forward', 'Sender cancelled',               'normal'),
(7,  'forward', 'Customer not answering',         'normal'),
(8,  'forward', 'Refused by customer',            'normal'),
(12, 'forward', 'Outside delivery coverage',      'normal'),
(13, 'forward', 'Data modification needed',       'normal'),
(14, 'forward', 'Data modification needed',       'normal'),
-- Return (RTO attempt codes)
(20, 'return',  'Retry scheduled',                'normal'),
(21, 'return',  'Postponed',                      'normal'),
(22, 'return',  'Postponed',                      'normal'),
(23, 'return',  'Data update required',           'normal'),
(24, 'return',  'Business refused return',        'normal'),
(25, 'return',  'Data update required',           'normal'),
-- Critical: courier-side evidence of loss/tamper (FR-15.3, surfaced with
-- never-received report)
(26, 'return',  'Order damaged',                  'critical'),
(27, 'return',  'Empty order — missing items',    'critical'),
(28, 'return',  'Incomplete order',               'critical'),
(29, 'return',  'Parcel does not belong',         'critical'),
(30, 'return',  'Opened when it should not be',   'critical');
