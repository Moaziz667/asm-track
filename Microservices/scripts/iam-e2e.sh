#!/usr/bin/env bash
# End-to-end IAM/RBAC smoke against the RUNNING stack (gateway :80, Keycloak :8089).
# Exercises what unit tests can't: real tokens through the gateway, admin sync-create,
# async outbox provisioning (deactivate + driver invite), verified via Keycloak.
# Self-contained: creates a temp 'e2e-runner' direct-grant client (+audience mapper) and test
# users, then cleans everything up. Usage: bash Microservices/scripts/iam-e2e.sh
set -u

GW="http://localhost:80"
KC="http://localhost:8089"
CID="e2e-runner"
KCPW='Asm@Kc2024!Admin'
PGPW='pGk8#m2L!v9Xq4$z'
PW='Pw!123456'
TS="$(date +%s)"
PASS=0; FAIL=0

kc()    { docker exec -e PW="$KCPW" keycloak bash -c "/opt/keycloak/bin/kcadm.sh $*"; }
kcauth(){ docker exec -e PW="$KCPW" keycloak bash -c '/opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 --realm master --user admin --password "$PW"' >/dev/null 2>&1; }
appsql(){ docker exec -e PGPASSWORD="$PGPW" postgres-app psql -U app_user -d app_db -tAc "$1" >/dev/null 2>&1; }
jget()  { grep -o "\"$1\" : \"[^\"]*\"" | head -1 | sed "s/.*: \"//;s/\"//"; }   # kcadm pretty JSON
bget()  { grep -o "\"$1\":\"[^\"]*\"" | head -1 | sed "s/\"$1\":\"//;s/\"$//"; }  # compact JSON

tok()  { curl -s -X POST "$KC/realms/asm/protocol/openid-connect/token" -d "client_id=$CID" -d "grant_type=password" -d "username=$1" -d "password=$2" | bget access_token; }
mtok() { curl -s -X POST "$KC/realms/master/protocol/openid-connect/token" -d "client_id=admin-cli" -d "grant_type=password" -d "username=admin" -d "password=$KCPW" | bget access_token; }
# kcq <appUserId>: Keycloak Admin REST query for the user (reliable; avoids kcadm-output parsing).
kcq()  { curl -s -H "Authorization: Bearer $(mtok)" "$KC/admin/realms/asm/users?username=$1&exact=true"; }
code() { if [ -n "${4:-}" ]; then curl -s -o /dev/null -w "%{http_code}" -X "$1" -H "Authorization: Bearer $2" -H "Content-Type: application/json" -d "$4" "$GW$3"; else curl -s -o /dev/null -w "%{http_code}" -X "$1" -H "Authorization: Bearer $2" "$GW$3"; fi; }
body() { curl -s -X "$1" -H "Authorization: Bearer $2" -H "Content-Type: application/json" -d "$4" "$GW$3"; }

ok()    { PASS=$((PASS+1)); echo "  PASS  $1"; }
bad()   { FAIL=$((FAIL+1)); echo "  FAIL  $1 (got: $2)"; }
is403() { [ "$2" = "403" ] && ok "$1" || bad "$1 [expect 403]" "$2"; }
allow() { [ "$2" != "403" ] && [ "$2" != "401" ] && ok "$1" || bad "$1 [expect allowed]" "$2"; }

# prep_login <appUserId(=KC username)>: clear required actions + set a real password so ROPC works.
prep_login() {
  docker exec -e PW="$KCPW" keycloak bash -c '
    K=/opt/keycloak/bin/kcadm.sh
    $K config credentials --server http://localhost:8080 --realm master --user admin --password "$PW" >/dev/null 2>&1
    id=$($K get users -r asm -q username='"$1"' --fields id | grep -o "\"id\" : \"[^\"]*\"" | head -1 | sed "s/.*: \"//;s/\"//")
    [ -z "$id" ] && exit 1
    $K update users/$id -r asm -s "requiredActions=[]" -s "emailVerified=true" >/dev/null 2>&1
    $K set-password -r asm --userid $id --new-password '"'$PW'"' >/dev/null 2>&1
  ' >/dev/null 2>&1
}
# kcuser <appUserId> [field]: print matching field (default id) for the KC user, else empty.
kcuser() { kc get users -r asm -q username=$1 --fields ${2:-id} 2>/dev/null; }

echo "### Setup: e2e-runner client + audience mapper + tokens"
kcauth
kc "create clients -r asm -s clientId=$CID -s publicClient=true -s directAccessGrantsEnabled=true -s standardFlowEnabled=false -s enabled=true" >/dev/null 2>&1 || true
CIDID="$(kc get clients -r asm -q clientId=$CID --fields id 2>/dev/null | jget id)"
# Audience mapper so e2e-runner tokens carry aud=admin-web (the gateway whitelists azp/aud).
# Created from a file inside the container to avoid host->bash->kcadm JSON quoting issues.
docker exec -e PW="$KCPW" keycloak bash -c '
  K=/opt/keycloak/bin/kcadm.sh
  $K config credentials --server http://localhost:8080 --realm master --user admin --password "$PW" >/dev/null 2>&1
  CID=$($K get clients -r asm -q clientId=e2e-runner --fields id | grep -o "\"id\" : \"[^\"]*\"" | head -1 | sed "s/.*: \"//;s/\"//")
  printf "%s" "{\"name\":\"aud-admin-web\",\"protocol\":\"openid-connect\",\"protocolMapper\":\"oidc-audience-mapper\",\"config\":{\"included.client.audience\":\"admin-web\",\"access.token.claim\":\"true\",\"id.token.claim\":\"false\"}}" > /tmp/m.json
  $K create clients/$CID/protocol-mappers/models -r asm -f /tmp/m.json
' >/dev/null 2>&1 || true
ADMIN="$(tok admin@asm.com admin)"
[ -n "$ADMIN" ] && ok "mint ADMIN token (seed)" || { bad "mint ADMIN token" "empty"; echo "ABORT"; exit 1; }

echo "### B. Admin creates users (synchronous Keycloak provisioning)"
DEMAIL="e2e-disp-$TS@asm.com"; MEMAIL="e2e-mgr-$TS@asm.com"
DRESP="$(body POST "$ADMIN" /api/admin/users "{\"name\":\"E2E Disp\",\"email\":\"$DEMAIL\",\"password\":\"$PW\",\"role\":\"DISPATCHER\"}")"
DUID="$(echo "$DRESP" | bget id)"
MRESP="$(body POST "$ADMIN" /api/admin/users "{\"name\":\"E2E Manager\",\"email\":\"$MEMAIL\",\"password\":\"$PW\",\"role\":\"MANAGER\"}")"
MID="$(echo "$MRESP" | bget id)"
[ -n "$DUID" ] && ok "create dispatcher (id=$DUID)" || bad "create dispatcher" "$DRESP"
[ -n "$MID" ]  && ok "create manager (id=$MID)"     || bad "create manager" "$MRESP"
sleep 3
kcq "$MID" | grep -q '"id"' && ok "manager exists in Keycloak (sync)" || bad "manager in Keycloak" "not found"

echo "### A. RBAC matrix (live, through the gateway)"
prep_login "$DUID"; prep_login "$MID"
DISP="$(tok $DUID $PW)"; MGR="$(tok $MID $PW)"   # ROPC with KC username (= appUserId UUID)
[ -n "$DISP" ] && ok "mint DISPATCHER token" || bad "mint DISPATCHER token" "empty"
[ -n "$MGR" ]  && ok "mint MANAGER token"     || bad "mint MANAGER token" "empty"
allow "admin      GET /api/admin/users"            "$(code GET "$ADMIN" /api/admin/users)"
is403 "dispatcher GET /api/admin/users"            "$(code GET "$DISP" /api/admin/users)"
is403 "dispatcher GET /api/admin/drivers (DriverService enforces ADMIN)" "$(code GET "$DISP" /api/admin/drivers)"
is403 "dispatcher POST /api/admin/drivers"         "$(code POST "$DISP" /api/admin/drivers '{"name":"x","phone":"+216000","email":"x@x.com"}')"
allow "dispatcher GET /api/admin/companies/me"     "$(code GET "$DISP" /api/admin/companies/me)"
is403 "dispatcher GET /api/admin/reports/settings" "$(code GET "$DISP" /api/admin/reports/settings)"
allow "manager    GET /api/admin/routes"           "$(code GET "$MGR" /api/admin/routes)"
is403 "manager    GET /api/admin/users"            "$(code GET "$MGR" /api/admin/users)"
is403 "manager    GET /api/admin/companies/me"     "$(code GET "$MGR" /api/admin/companies/me)"

echo "### D. Deactivate user -> async outbox flips Keycloak enabled=false"
allow "admin PATCH deactivate manager" "$(code PATCH "$ADMIN" "/api/admin/users/$MID/status" '{"active":false}')"
DIS=""
for i in $(seq 1 12); do sleep 3; kcq "$MID" | grep -q '"enabled":false' && { DIS=y; break; }; done
[ -n "$DIS" ] && ok "Keycloak enabled=false within ~30s (outbox SET_ENABLED)" || bad "outbox deactivate" "still enabled"

echo "### C. Invite driver -> async outbox->broker->consumer creates Keycloak user"
DRVEMAIL="e2e-drv-$TS@asm.com"; DPHONE="+2169${TS: -7}"
IRESP="$(body POST "$ADMIN" /api/admin/drivers "{\"name\":\"E2E Driver\",\"phone\":\"$DPHONE\",\"email\":\"$DRVEMAIL\"}")"
DRVID="$(echo "$IRESP" | bget id)"
[ -n "$DRVID" ] && ok "invite driver (id=$DRVID)" || bad "invite driver" "$IRESP"
DF=""
if [ -n "$DRVID" ]; then for i in $(seq 1 12); do sleep 3; kcq "$DRVID" | grep -q '"id"' && { DF=y; break; }; done; fi
[ -n "$DF" ] && ok "driver provisioned in Keycloak within ~30s (outbox->broker->consumer)" || bad "driver provisioning" "not found"

echo "### Cleanup"
kcauth
[ -n "${DRVID:-}" ] && code DELETE "$ADMIN" "/api/admin/drivers/$DRVID" '{"reason":"e2e"}' >/dev/null 2>&1 && echo "  cancelled test driver"
for em in "$DEMAIL" "$MEMAIL"; do
  kid="$(kc get users -r asm -q email=$em --fields id 2>/dev/null | jget id)"
  [ -n "$kid" ] && kc "delete users/$kid -r asm" >/dev/null 2>&1
  appsql "DELETE FROM admin_users WHERE email='$em';"
done
echo "  removed test admin users"
kc "delete clients/$CIDID -r asm" >/dev/null 2>&1 && echo "  removed e2e-runner client"

echo ""
echo "=================================================="
echo "  RESULT: $PASS passed, $FAIL failed"
echo "=================================================="
[ "$FAIL" -eq 0 ]
