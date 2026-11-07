---
id: missed-scans
topic: trouble
title: Some scans are missed
roles: [owner, manager, worker]
related: [test-scanner, scanner-wrong-letters, phone-wont-pair]
alerts: []
screens: [/fulfill, /returns, /stock-take]
video:
---
Traced doesn't drop fast scans: each one waits its turn and is handled in order, with its own beep. A scan usually goes missing because it never reached the scan box, or because too many were waiting.

## How scanning works

- Each scan joins a line. Traced handles one at a time, in the order you scanned, and gives each its own beep and flash — a high beep when it's accepted, a low one when it isn't.
- While scans are waiting, the screen shows how many, for example **3 scans waiting**.
- Up to 20 scans can wait. One more is refused with an error beep and **Too many scans waiting — slow down**. Wait for the line to clear, then scan that item again.

## Check these in order

1. **The scan box didn't have the cursor.** If you clicked into another box (a note or a quantity), the scan was typed there. Click anywhere on the screen outside other boxes — the cursor goes back to the scan box — and scan again.
2. **A window was open.** While a dialog is open, scans don't go to the screen behind it. Finish or close it first.
3. **You left the screen.** Scans still waiting when you leave a screen are dropped. Wait until nothing is waiting before you move on.
4. **No beep at all.** The scanner may not send Enter at the end of each scan — see [Test your barcode scanner](/help/test-scanner). A low beep with a code that looks wrong is a keyboard setting — see [Scanner types wrong letters](/help/scanner-wrong-letters).
5. **Phone scans.** If the phone shows **Not applied — scan again**, the scan didn't reach the screen (for example, the screen closed first). Scan it again. More in [Phone scanner won't pair](/help/phone-wont-pair).

## Make sure

Each scan that was handled shows on the screen — in its list of recent scans, or as the item packed, counted or received. If an item doesn't show as scanned, scan it again.
