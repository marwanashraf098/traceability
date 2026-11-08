"""Part D P2 — dev-store-only GraphQL helper for probes run with TRACED'S OWN app (scripts only, no app code).

Same safeguards as scripts/dev/receiving-move-s0/shopify_dev.py, plus one: the transport is ALWAYS Traced's own app
(client-credentials token from the repo's .env), never the Shopify CLI's app — idempotency keys may be stored per app,
which is the whole point of P2.

  * Refuses any shop but the dev store (DEV_SHOP), checked against .env before anything is sent.
  * Refuses to send anything until GET currentAppInstallation answers with the app handle in P2_APP_HANDLE
    (export P2_APP_HANDLE=<Traced dev app handle>), so a probe can never run under another app by mistake.
  * Mints the token IN MEMORY (never written, never printed); it is only ever sent as a header.
  * Admin API 2026-04 pinned. ONE request per call, no retry of any kind.
  * Every request and response is appended to p2-log.jsonl with the token redacted.

Usage (from the repo root, from an IP the dev app allows):
    export P2_APP_HANDLE=<handle>
    python3 -I scripts/dev/part-d-p2/<probe>.py ...
"""
import json
import os
import time
import urllib.error
import urllib.request

DEV_SHOP = "marwan-nnrumxdy.myshopify.com"
API_VERSION = "2026-04"
HERE = os.path.dirname(os.path.abspath(__file__))
LOG = os.path.join(HERE, "p2-log.jsonl")


def _env(key):
    with open(os.path.join(os.getcwd(), ".env"), encoding="utf-8") as f:
        for line in f:
            if line.startswith(key + "="):
                return line.split("=", 1)[1].strip()
    raise SystemExit(f"{key} missing from .env (run from the repo root)")


SHOP = _env("SHOPIFY_SHOP_DOMAIN")
if SHOP != DEV_SHOP:
    raise SystemExit(f"Refusing: .env shop is {SHOP!r}, P2 runs ONLY against {DEV_SHOP}")
EXPECT_APP = os.environ.get("P2_APP_HANDLE", "").strip()
if not EXPECT_APP:
    raise SystemExit("Refusing: set P2_APP_HANDLE to Traced's dev app handle (the probe checks it before any write)")

_token = None
_app_checked = False


def _mint():
    body = json.dumps({"grant_type": "client_credentials", "client_id": _env("SHOPIFY_CLIENT_ID"),
                       "client_secret": _env("SHOPIFY_CLIENT_SECRET")}).encode()
    req = urllib.request.Request(f"https://{DEV_SHOP}/admin/oauth/access_token", data=body,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.load(r)["access_token"]


def _send(label, query, variables):
    global _token
    if _token is None:
        _token = _mint()
    payload = {"query": query, "variables": variables or {}}
    req = urllib.request.Request(
        f"https://{DEV_SHOP}/admin/api/{API_VERSION}/graphql.json", data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json", "X-Shopify-Access-Token": _token})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            status, body = r.status, json.load(r)
    except urllib.error.HTTPError as e:
        status, body = e.code, {"_http_error": e.read().decode(errors="replace")[:2000]}
    except (urllib.error.URLError, TimeoutError) as e:
        # No answer: the probe must STOP — a write may or may not have landed. Never re-sent here.
        status, body = 0, {"_no_answer": repr(e)}
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(json.dumps({"t": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "label": label, "shop": DEV_SHOP,
                            "api": API_VERSION, "transport": "traced-app client credentials",
                            "headers": {"X-Shopify-Access-Token": "<redacted>"},
                            "request": payload, "status": status, "response": body}) + "\n")
    if status == 0:
        raise SystemExit(f"{label}: no answer from Shopify — STOP, check the dev store by hand before re-running")
    return status, body


WHOAMI = "query P2WhoAmI { currentAppInstallation { app { id title handle } accessScopes { handle } } }"


def _check_app():
    global _app_checked
    if _app_checked:
        return
    st, b = _send("whoami", WHOAMI, {})
    app = (((b.get("data") or {}).get("currentAppInstallation") or {}).get("app") or {})
    if app.get("handle") != EXPECT_APP:
        raise SystemExit(f"Refusing: the token belongs to app {app.get('handle')!r} ({app.get('title')!r}), "
                         f"not P2_APP_HANDLE={EXPECT_APP!r}")
    print(f"app: {app.get('title')} ({app.get('handle')})")
    _app_checked = True


def gql(label, query, variables=None):
    """One request, no retry. The app is checked first. Returns (status, parsed body)."""
    _check_app()
    return _send(label, query, variables)


def show(label, status, body):
    print(f"--- {label}: HTTP {status}")
    print(json.dumps(body, indent=1)[:3000])


# ── shared operations ─────────────────────────────────────────────────────────

LEVEL = """query P2Level($item: ID!) { inventoryItem(id: $item) { id
  inventoryLevels(first: 50, includeInactive: true) { nodes { id isActive location { id }
    quantities(names: ["available", "committed", "on_hand", "reserved"]) { name quantity } } } } }"""

ADJ = """mutation P2Adjust($input: InventoryAdjustQuantitiesInput!, $idempotencyKey: String!) {
  inventoryAdjustQuantities(input: $input) @idempotent(key: $idempotencyKey) {
    inventoryAdjustmentGroup { id createdAt changes { name delta quantityAfterChange location { id } item { id } } }
    userErrors { field message code } } }"""

ACTIVATE = """mutation P2Activate($item: ID!, $loc: ID!, $k: String!) {
  inventoryActivate(inventoryItemId: $item, locationId: $loc) @idempotent(key: $k) {
    inventoryLevel { id isActive quantities(names: ["available", "on_hand"]) { name quantity } }
    userErrors { field message } } }"""

DEACTIVATE = """mutation P2Deactivate($id: ID!) { inventoryDeactivate(inventoryLevelId: $id) { userErrors { field message } } }"""


def level(item, loc):
    """The item's level at loc INCLUDING an inactive one: {id, active, available, on_hand, ...} or None."""
    st, b = gql(f"level {loc}", LEVEL, {"item": item})
    nodes = ((((b.get("data") or {}).get("inventoryItem") or {}).get("inventoryLevels") or {}).get("nodes")) or []
    for n in nodes:
        if n["location"]["id"] == loc:
            q = {x["name"]: x["quantity"] for x in n["quantities"]}
            return {"id": n["id"], "active": n["isActive"], **q}
    return None


def adjust(label, item, loc, delta, cfq, key, reason="correction", ref=None):
    """One adjust on "available". cfq None = Shopify's explicit null opt-out. Returns (group, error codes)."""
    inp = {"reason": reason, "name": "available", "referenceDocumentUri": ref or f"traced://part-d-p2/{label}",
           "changes": [{"inventoryItemId": item, "locationId": loc, "delta": delta, "changeFromQuantity": cfq}]}
    st, b = gql(label, ADJ, {"input": inp, "idempotencyKey": key})
    show(label, st, b)
    if b.get("errors") or st != 200:
        raise SystemExit(f"{label}: HTTP {st} / top-level errors — STOP, check the dev store by hand")
    data = (b.get("data") or {}).get("inventoryAdjustQuantities") or {}
    return data.get("inventoryAdjustmentGroup"), [e.get("code") for e in (data.get("userErrors") or [])]


def activate(label, item, loc, key):
    st, b = gql(label, ACTIVATE, {"item": item, "loc": loc, "k": key})
    show(label, st, b)
    data = (b.get("data") or {}).get("inventoryActivate") or {}
    return data.get("inventoryLevel"), [e.get("message") for e in (data.get("userErrors") or [])]


def deactivate(label, level_id):
    st, b = gql(label, DEACTIVATE, {"id": level_id})
    show(label, st, b)
    return [e.get("message") for e in ((((b.get("data") or {}).get("inventoryDeactivate")) or {}).get("userErrors") or [])]
