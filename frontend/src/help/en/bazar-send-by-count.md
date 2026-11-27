---
id: bazar-send-by-count
topic: bazar
title: Send pieces by count
roles: [owner, manager]
related: [bazar-get-ready, bazar-setup-new-stock, reprint-labels]
alerts: []
screens: [/bazar/:id/scan-out]
video:
---
Send by count is for pieces without labels: choose the size and how many, and Traced takes that many pieces without a label from the main warehouse, oldest first. Pieces with labels are scanned.

## Send by count
1. On the scan-out screen, under **Send by count**, press **Choose product and size**.
2. Pick the size and press **Choose**. Traced shows how many are at Main and how many online orders still need. When some of them have labels, it shows how many are without labels and how many with labels — scan those.
3. Set how many with − and +. You can send up to the number without labels.
4. Press **Send N pieces**.

## When Traced asks you to check
- **Online orders need this size:** Traced tells you how many will be left at Main. Press **Send N anyway** to send them.
- **You asked for more than there are without labels:** Traced sends the ones without labels and tells you how many others have labels — scan those.
- **Every piece of that size has a label:** nothing is sent; scan them instead. A piece counts as labelled once its label was made in Traced, even if it was never printed. If you never printed those labels, reprint them from their receiving session in **Receiving** — see [Reprint labels](/help/reprint-labels).

## Take pieces off the send
On the send-out progress list, press **Remove** on the line, choose how many and press **Remove N**. When the pieces taken off have labels, Traced lists their codes so you can put those pieces back.
