---
id: connect-bosta
topic: start
title: Connect Bosta
roles: [owner]
related: [connect-shopify, other-couriers, hand-over-parcels]
alerts: []
screens: [/settings?tab=connections]
video:
---
In **Settings › Connections**, tap **Connect Bosta**, paste a Full access API key from your Bosta account, then copy the webhook details Traced shows into Bosta's webhook settings. Only the account owner can connect Bosta.

## Get your API key

1. Sign in to the Bosta website and open **Applications** from your account menu.
2. Choose **Connect your store using an API key**. Bosta sends a one-time code to your registered phone — enter it.
3. Set the access level to **Full access**, then click **Create**.
4. Copy the key, paste it into Traced and connect. Traced checks the key with Bosta first; if it's wrong you'll see "Could not connect".

## Set up the webhook

Right after connecting, Traced shows **Webhook configuration** with a **Webhook URL**, an **Authorization key name** and an **Authorization Key**. Paste each one into Bosta's webhook settings, using the Authorization Key exactly as shown.

This is shown only once. When you've saved it, tap **Done, I've saved it**. Lost it? Tap **Regenerate webhook secret** — the old one stops working, so update Bosta straight away. Reconnecting Bosta also gives you a new one.

## What Traced does with Bosta

- Traced does not create your deliveries. Keep creating them the way you do today; Traced finds each one and links it to its Shopify order.
- It follows every delivery's status — with the courier, delivered, returned — so your pieces move with it. The webhook lets Bosta tell Traced about changes as they happen.
- It prints your Bosta waybills for packing.
- If you switch it on in the returns portal, it can book Bosta return pickups and exchanges for approved requests.

To bring in deliveries that already exist in Bosta, tap **Sync Bosta deliveries**. It's safe to run again.
