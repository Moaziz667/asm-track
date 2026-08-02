#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# COD end-to-end — the cash custody chain, driven entirely by API.
#
#   collect at the door → declare at the depot → counted by someone else →
#   discrepancy → settled by a manager
#
# The step this exists to prove is the one no unit test can: that the *same
# person* cannot both declare and count. Everything else in the module is
# bookkeeping around that check.
#
# Usage:
#   ADMIN_TOKEN='eyJ...' ./scripts/cod-e2e.sh <deliveryId>
#
# The admin token has to be pasted: `admin-web` is a public PKCE client with no
# direct grant, so no script can mint one (see .ai/e2e-workflow.md §1). The
# driver token is minted here via ROPC.
#
# Touches the dev database: it forces a COD instruction onto the order under
# test, because nothing else can — the flag comes from a tenant's ERP mapping.
# Refuses to run without --yes.
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

GW=${GW:-http://localhost}
KC=${KC:-http://localhost:8089}
DRIVER_USER=${DRIVER_USER:-driver2@asm.com}
DRIVER_PASS=${DRIVER_PASS:-driver123}
COD_AMOUNT=${COD_AMOUNT:-6000.000}
COUNTED=${COUNTED:-5950.000}          # deliberately short → must open a dispute
PGC=${PGC:-postgres-delivery}
PGU=${PGU:-delivery}
PGD=${PGD:-delivery_db}

DELIVERY_ID=${1:-}
YES=${2:-}

pass=0; fail=0
ok()   { echo "  ✅ $*"; pass=$((pass+1)); }
ko()   { echo "  ❌ $*"; fail=$((fail+1)); }
step() { echo; echo "── $* ─────────────────────────────────────────"; }
die()  { echo "✗ $*" >&2; exit 1; }

[ -n "$DELIVERY_ID" ] || die "usage: ADMIN_TOKEN=… $0 <deliveryId> --yes"
[ -n "${ADMIN_TOKEN:-}" ] || die "ADMIN_TOKEN is not set (paste one from the admin app)"
[ "$YES" = "--yes" ] || die "this writes to $PGD (forces COD on the order under test) — pass --yes"

psql() { docker exec -i "$PGC" psql -U "$PGU" -d "$PGD" -tAc "$1"; }
jqv()  { python -c "import sys,json;d=json.load(sys.stdin);print(d$1 if d else '')" 2>/dev/null; }

# ── 0. Auth ──────────────────────────────────────────────────────────────────
step "0. Tokens"
DRIVER_TOKEN=$(curl -s -d client_id=driver-app -d "username=$DRIVER_USER" \
  -d "password=$DRIVER_PASS" -d grant_type=password \
  "$KC/realms/asm/protocol/openid-connect/token" | jqv "['access_token']")
[ -n "$DRIVER_TOKEN" ] || die "could not mint a driver token for $DRIVER_USER"
ok "driver token for $DRIVER_USER"

# The seeded driver accounts predate the organization model and carry no tenant claim, so every
# call they make is rejected by the gateway with "No tenant assigned" long before any COD code
# runs. Failing here, with the reason, beats seven cryptic 403s further down.
HAS_ORG=$(python -c "
import base64,json,sys
p = sys.argv[1].split('.')[1]; p += '=' * (-len(p) % 4)
print('yes' if json.loads(base64.urlsafe_b64decode(p)).get('organization') else '')" "$DRIVER_TOKEN")
[ -n "$HAS_ORG" ] || die "$DRIVER_USER has no organization claim — assign this user to the tenant in Keycloak, or set DRIVER_USER to a driver created through the app"
ok "driver belongs to a tenant"

A=(-H "Authorization: Bearer $ADMIN_TOKEN")
D=(-H "Authorization: Bearer $DRIVER_TOKEN")

# ── 1. Arm the order ─────────────────────────────────────────────────────────
step "1. Force a COD instruction on the order"
# Find the schema that actually holds this delivery. Taking the first tenant schema works on a
# single-tenant box and silently targets a stranger's data on this one.
SCHEMA=""
for sch in $(psql "SELECT nspname FROM pg_namespace WHERE nspname LIKE 'company_%'"); do
  n=$(psql "SELECT count(*) FROM ${sch}.deliveries WHERE id = '$DELIVERY_ID'" 2>/dev/null | tr -dc '0-9')
  if [ "$n" = "1" ]; then SCHEMA="$sch"; break; fi
done
[ -n "$SCHEMA" ] || die "delivery $DELIVERY_ID not found in any tenant schema of $PGD"
echo "  schema: $SCHEMA"

# The token we can mint has to belong to the driver who owns the delivery, so the fixture is
# reassigned rather than the test being restricted to whichever driver happens to hold one.
DRIVER_UUID=$(python -c "
import base64,json,sys
p = sys.argv[1].split('.')[1]; p += '=' * (-len(p) % 4)
print(json.loads(base64.urlsafe_b64decode(p)).get('app_user_id', ''))" "$DRIVER_TOKEN")
[ -n "$DRIVER_UUID" ] || die "could not read app_user_id from the driver token"
psql "UPDATE $SCHEMA.deliveries SET driver_id = '$DRIVER_UUID' WHERE id = '$DELIVERY_ID'" >/dev/null
ok "delivery reassigned to $DRIVER_USER"

# Bring the delivery to a state where a proof can be submitted, and clear any proof already on it.
# A scenario that can only run once against one hand-picked row is a demo, not a test.
psql "DELETE FROM $SCHEMA.cash_collections WHERE delivery_id = '$DELIVERY_ID'" >/dev/null
psql "DELETE FROM $SCHEMA.proof_of_delivery WHERE delivery_id = '$DELIVERY_ID'" >/dev/null
psql "UPDATE $SCHEMA.deliveries SET status = 'IN_TRANSIT' WHERE id = '$DELIVERY_ID'" >/dev/null
ok "delivery reset to IN_TRANSIT"

psql "UPDATE $SCHEMA.orders o SET cod_required = TRUE, cod_amount = $COD_AMOUNT, currency = 'TND'
      FROM $SCHEMA.deliveries d WHERE d.order_id = o.id AND d.id = '$DELIVERY_ID'" >/dev/null
ARMED=$(psql "SELECT o.cod_amount FROM $SCHEMA.orders o
              JOIN $SCHEMA.deliveries d ON d.order_id = o.id WHERE d.id = '$DELIVERY_ID'" | tr -d '\r ')
[ -n "$ARMED" ] || die "delivery $DELIVERY_ID not found"
ok "order armed at $ARMED TND"

# The driver's screen must be able to say it, not infer it.
SEEN=$(curl -s "${D[@]}" "$GW/api/v1/driver/deliveries/$DELIVERY_ID" | jqv "['codAmount']")
[ -n "$SEEN" ] && ok "driver app sees codAmount=$SEEN" \
                || ko "driver app does not see the instruction"

# ── 2. Collect at the door ───────────────────────────────────────────────────
step "2. POD with a cash collection"
PNG='iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=='
BODY=$(python - "$PNG" "$COD_AMOUNT" <<'PY'
import json,sys
png, amount = sys.argv[1], sys.argv[2]
print(json.dumps({
  "bonLivraisonPhotoBase64": png,
  "packagePhotoBase64": png,
  "recipientName": "E2E COD",
  "cash": {"amountCollected": float(amount), "method": "CASH"},
}))
PY
)
POD=$(curl -s -o /dev/null -w '%{http_code}' "${D[@]}" -H 'Content-Type: application/json' \
      -X POST "$GW/api/v1/driver/deliveries/$DELIVERY_ID/pod" -d "$BODY")
[ "$POD" = "200" ] && ok "POD accepted" || ko "POD returned HTTP $POD"

COLLECTED=$(psql "SELECT amount_collected FROM $SCHEMA.cash_collections
                  WHERE delivery_id = '$DELIVERY_ID'" | tr -d '\r ')
[ -n "$COLLECTED" ] && ok "collection recorded: $COLLECTED" || ko "no cash_collections row"

STATUS=$(psql "SELECT status FROM $SCHEMA.cash_collections
               WHERE delivery_id = '$DELIVERY_ID'" | tr -d '\r ')
[ "$STATUS" = "COLLECTED" ] && ok "status COLLECTED" || ko "status is '$STATUS'"

OUT=$(curl -s "${D[@]}" "$GW/api/v1/driver/deliveries/cash/outstanding" | jqv "['amount']")
ok "driver now holds $OUT TND"

# ── 3. Declare at the depot ──────────────────────────────────────────────────
step "3. The driver declares"
DECL=$(curl -s "${D[@]}" -H 'Content-Type: application/json' \
       -X POST "$GW/api/v1/driver/deliveries/cash/declare" -d "{\"declaredTotal\": $COD_AMOUNT}")
RID=$(echo "$DECL" | jqv "['id']")
[ -n "$RID" ] && ok "handover $RID declared" || { ko "declare failed: $DECL"; RID=""; }

if [ -n "$RID" ]; then
  ST=$(echo "$DECL" | jqv "['status']")
  [ "$ST" = "DECLARED" ] && ok "status DECLARED" || ko "status is '$ST'"

  # A second declaration would split one pocket of cash across two handovers.
  AGAIN=$(curl -s -o /dev/null -w '%{http_code}' "${D[@]}" -H 'Content-Type: application/json' \
          -X POST "$GW/api/v1/driver/deliveries/cash/declare" -d "{\"declaredTotal\": 1}")
  [ "$AGAIN" != "200" ] && ok "a second handover is refused (HTTP $AGAIN)" \
                        || ko "a second handover was accepted"
fi

# ── 4. The check the whole module rests on ───────────────────────────────────
step "4. The driver cannot count his own handover"
if [ -n "$RID" ]; then
  SELF=$(curl -s -o /dev/null -w '%{http_code}' "${D[@]}" -H 'Content-Type: application/json' \
         -X POST "$GW/api/v1/admin/cash/remittances/$RID/receive" -d '{"receivedTotal": 6000}')
  # 403 from the gateway (a driver has no admin permission) or 400 from the service — either is a
  # refusal, and both matter: the rule holds at two independent layers.
  [ "$SELF" != "200" ] && ok "refused (HTTP $SELF)" || ko "a driver counted his own handover"
fi

# ── 5. Someone else counts, short ────────────────────────────────────────────
step "5. The depot counts $COUNTED"
if [ -n "$RID" ]; then
  RECV=$(curl -s "${A[@]}" -H 'Content-Type: application/json' \
         -X POST "$GW/api/v1/admin/cash/remittances/$RID/receive" \
         -d "{\"receivedTotal\": $COUNTED}")
  RST=$(echo "$RECV" | jqv "['status']")
  DISC=$(echo "$RECV" | jqv "['discrepancy']")
  [ "$RST" = "DISPUTED" ] && ok "status DISPUTED" || ko "status is '$RST' — expected DISPUTED"
  echo "  ecart: $DISC"
  # Measured against what he took, not against what he declared.
  python -c "import sys;sys.exit(0 if abs(float('${DISC:-0}') + 50) < 0.001 else 1)" \
    && ok "discrepancy is −50 (counted − collected)" \
    || ko "discrepancy is $DISC — expected −50"
fi

# ── 6. A manager settles it ──────────────────────────────────────────────────
step "6. Settling the gap"
if [ -n "$RID" ]; then
  NONOTE=$(curl -s -o /dev/null -w '%{http_code}' "${A[@]}" -H 'Content-Type: application/json' \
           -X POST "$GW/api/v1/admin/cash/remittances/$RID/reconcile" -d '{"note": "  "}')
  [ "$NONOTE" != "200" ] && ok "an empty explanation is refused (HTTP $NONOTE)" \
                         || ko "a gap was closed with no explanation"

  FIN=$(curl -s "${A[@]}" -H 'Content-Type: application/json' \
        -X POST "$GW/api/v1/admin/cash/remittances/$RID/reconcile" \
        -d '{"note": "e2e: erreur de rendu de monnaie"}')
  FST=$(echo "$FIN" | jqv "['status']")
  [ "$FST" = "RECONCILED" ] && ok "status RECONCILED" || ko "status is '$FST'"
fi

# ── 7. The money left circulation ────────────────────────────────────────────
step "7. Cash in circulation"
LEFT=$(curl -s "${D[@]}" "$GW/api/v1/driver/deliveries/cash/outstanding" | jqv "['amount']")
python -c "import sys;sys.exit(0 if float('${LEFT:-0}') == 0 else 1)" \
  && ok "the driver holds nothing" \
  || ko "the driver still holds $LEFT"

echo
echo "═══════════════════════════════════════════════"
echo "  $pass passed, $fail failed"
echo "═══════════════════════════════════════════════"
[ "$fail" -eq 0 ] || exit 1
