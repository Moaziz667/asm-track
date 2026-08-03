#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Export the OpenAPI contract of every service to docs/openapi/.
#
# The cahier des charges (§10, §13) promises a published API contract. The specs
# already exist — springdoc serves them live on all five services and the gateway
# aggregates them — but a contract that only exists on a running machine is not a
# deliverable: nobody can read it in a review, diff it across a release, or hand
# it to an integrator who has no access to the stack.
#
# This writes them to files. Run it against a stack that is up:
#
#   ./ops/openapi/export.sh                 # against http://localhost
#   GW=https://asm.example.com  ./ops/openapi/export.sh
#
# The files are committed. They drift the moment a controller changes, so re-run
# this whenever the API does — the diff in the pull request is the point.
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

GW=${GW:-http://localhost}
OUT=${OUT:-docs/openapi}

# The gateway proxies each service's /v3/api-docs under its own name (see its
# application.yml). Fetching through the gateway rather than per-service ports is
# what an integrator can actually reach.
# The three the gateway exposes publicly. erp-adapter is deliberately absent: it is an internal
# service, unreachable from outside, and publishing a contract for a door nobody can open only
# invites someone to look for it. The gateway itself is an aggregator with no routes of its own —
# exporting it produced a spec with zero paths.
SERVICES="delivery driver app-backend"

mkdir -p "$OUT"
fail=0

fetch() {
  local name="$1" url="$2" file="$OUT/$1.json"
  local code
  code=$(curl -s -m 30 -o "$file.tmp" -w '%{http_code}' "$url")
  if [ "$code" != "200" ]; then
    echo "  [KO] $name - HTTP $code"
    rm -f "$file.tmp"; fail=1; return
  fi
  # A gateway that answers 200 with an HTML error page would otherwise be
  # committed as an API contract.
  if ! python -c "import json,sys; json.load(open(sys.argv[1]))" "$file.tmp" 2>/dev/null; then
    echo "  [KO] $name - reponse non JSON"
    rm -f "$file.tmp"; fail=1; return
  fi
  python -c "
import json,sys
spec = json.load(open(sys.argv[1]))
# Sorted keys and a stable indent: without them every export reshuffles the file
# and the diff says nothing about what actually changed in the API.
json.dump(spec, open(sys.argv[2], 'w'), indent=2, ensure_ascii=False, sort_keys=True)
print('  [ok] %s - %d paths' % (sys.argv[3], len(spec.get('paths', {}))))
" "$file.tmp" "$file" "$name"
  rm -f "$file.tmp"
}

echo "Export OpenAPI depuis $GW"
for s in $SERVICES; do
  fetch "$s" "$GW/v3/api-docs/$s"
done

echo
[ "$fail" -eq 0 ] && echo "→ $OUT" || { echo "→ incomplet"; exit 1; }
