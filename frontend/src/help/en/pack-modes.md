---
id: pack-modes
topic: pack
title: Queue mode or waybill scan mode: which to use
roles: [owner, manager, worker]
related: [batch-waybills, set-aside, order-not-in-pick-pack, pack-other-couriers]
alerts: []
screens: [/fulfill, /settings?tab=pickpack]
video:
---
Traced packs in one of two ways: **Order queue** (pick an order from a list, scan its pieces, then scan its waybill) or **Waybill scan** (print all waybills first, then scan a waybill and its pieces and the order closes by itself). The store owner chooses in **Settings › Pick & Pack**.

## Order queue
1. Open **Pick & Pack** and pick an order from the list.
2. Scan each of its pieces.
3. Press **Complete & Pack Order**, then scan the order's waybill. This step is required.

Best when waybills are printed one by one.

## Waybill scan
1. Print the waybills for the batch with **Print waybills** — see [Print waybills for a whole batch](/help/batch-waybills).
2. Press **Start pack session** and scan a waybill. Its order opens.
3. Scan the pieces. The order closes by itself when the last piece is scanned.
4. Scan the next waybill. Press **End session** when you're done.

Best for busy days and batch printing. If a piece is missing or damaged, you can [set the order aside](/help/set-aside).

## Good to know
- Self-pickup orders have no waybill, so they always stay in the order queue. In waybill scan mode, open them from **Self-pickup orders** on the Pick & Pack page.
- The mode applies to every worker in the store. Only the owner can change it; managers can see it.
- Pack sessions that are already open finish in their current mode. The change applies to the next session.
- The first time the owner opens Pick & Pack, Traced asks **How do you want to pack?** If you skip it, Traced uses Order queue.
