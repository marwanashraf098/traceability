---
id: hand-over-parcels
topic: pickup
title: Hand parcels to the courier
roles: [owner, manager, worker]
related: [cancelled-waybills-pickup, pack-modes, pack-other-couriers, tracked-vs-not-tracked]
alerts: []
screens: [/pickups]
video:
---
Hand parcels to the courier in a pickup session: open **Pickups**, start a new session, scan each parcel's waybill, then close the session to confirm the handover. Closing marks every scanned parcel as with the courier, and it can't be undone.

1. Open **Pickups** and press **New session**.
2. Choose the **Pickup date** and **Time slot**, add notes if you like, and press **Start scanning**.
3. Scan the waybill on each parcel as the courier takes it. A good scan shows **✓ Accepted**. Scanned the wrong one? Press **Remove** next to it.
4. When the courier has everything, press **Close session**, check the number of packages and press **Confirm handover**. The scanned parcels and their pieces change to with the courier straight away.
5. After closing, press **Print / Download manifest** for the handover list: every package, the total, and a space for the courier's signature.

## Scans Traced refuses
- **Already scanned in this session.**
- **In another active session** — remove it from that session first.
- **Shipment not in packed state** — pack the order first.
- **Return-leg shipment** — a return isn't handed over here.
- **Already with the courier** — Bosta already has this parcel; nothing to do.
- A waybill cancelled or deleted in Bosta — don't hand it over. See [Cancelled or deleted waybills at pickup](/help/cancelled-waybills-pickup).

## One courier per session
If your store also ships with other couriers, each session is for one courier: the first parcel you scan sets it. A parcel for another courier is refused with "This parcel goes with … — keep it for that courier's pickup". Start a separate session for each courier.

## Good to know
- You can't close a session with nothing scanned.
- If closing fails, check your connection and try again — nothing was handed over.
- A Bosta order the courier takes before it was packed in Pick & Pack shows as [Not traced](/help/tracked-vs-not-tracked).
