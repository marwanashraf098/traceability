"""Part D P2, question 1e (added 2026-11-08) — what does inventoryItem.inventoryLevel(locationId:) — the shape
Traced's baseline read uses (ShopifyHttpGateway.INVENTORY_LEVELS_QUERY) — answer for an INACTIVE level: null, or the
level with isActive false? Read-only except one deactivate + one activate (fresh key) at quantity 0.

    python3 -I scripts/dev/part-d-p2/level_shape.py MAIN_LOCATION_GID ITEM_GID
"""
import os
import sys
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from traced_dev import gql, show, level, activate, deactivate

MAIN, ITEM = sys.argv[1], sys.argv[2]
SHAPE = """query P2Shape($ids: [ID!]!, $loc: ID!) { nodes(ids: $ids) { ... on InventoryItem { id
  inventoryLevel(locationId: $loc) { id isActive quantities(names: ["available", "on_hand"]) { name quantity } } } } }"""

start = level(ITEM, MAIN)
print("start", start)
if not start or not start["active"] or any(start.get(k, 0) != 0 for k in ("available", "on_hand", "committed", "reserved")):
    raise SystemExit("Refusing: ITEM must be ACTIVE at MAIN with every quantity 0")
st, b = gql("q1e_shape_active", SHAPE, {"ids": [ITEM], "loc": MAIN}); show("q1e_shape_active", st, b)
deactivate("q1e_deactivate", start["id"])
lv = level(ITEM, MAIN)
if lv is None or lv["active"]:
    raise SystemExit(f"could not deactivate ({lv}) — stopping")
st, b = gql("q1e_shape_inactive", SHAPE, {"ids": [ITEM], "loc": MAIN}); show("q1e_shape_inactive", st, b)
activate("q1e_reactivate", ITEM, MAIN, str(uuid.uuid4()))
end = level(ITEM, MAIN)
print("end", end)
if not end or not end["active"] or any(end.get(k, 0) != 0 for k in ("available", "on_hand", "committed", "reserved")):
    print("WARNING: the level did not return to its start state — fix it by hand in the dev store")
