#!/usr/bin/env python3
"""
Idempotent RBAC provisioning for the ASM Keycloak realm.

Applies rbac-roles.json (the canonical role->permission model) to a RUNNING realm: creates any missing
perm:* realm roles and business roles, then ensures each business role's composite contains the perms
defined (adds missing; never removes -- extras are reported). Safe to re-run.

This is the "provisioning-as-code" companion to the asm-realm.json import seed: the seed bootstraps a
fresh realm, this reconciles an existing one after the policy changes -- no manual clicks.

Usage:
  KC_URL=http://localhost:8089 KC_REALM=asm KC_ADMIN=admin KC_ADMIN_PASSWORD=*** \
    python apply-rbac.py            # add --dry-run to preview without writing

Only depends on the Python stdlib (urllib) -- no jq, no extra packages.
"""
import json
import os
import sys
import urllib.request
import urllib.error
import urllib.parse

KC_URL = os.environ.get("KC_URL", "http://localhost:8089").rstrip("/")
KC_REALM = os.environ.get("KC_REALM", "asm")
KC_ADMIN = os.environ.get("KC_ADMIN", "admin")
KC_PW = os.environ.get("KC_ADMIN_PASSWORD")
DRY_RUN = "--dry-run" in sys.argv
DEF_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "rbac-roles.json")


def die(msg):
    print("ERROR:", msg, file=sys.stderr)
    sys.exit(1)


def req(method, path, token=None, body=None, form=None):
    url = path if path.startswith("http") else f"{KC_URL}/admin/realms/{KC_REALM}{path}"
    headers = {}
    data = None
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    elif body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    r = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(r) as resp:
            raw = resp.read().decode() or "{}"
            return resp.status, json.loads(raw) if raw.strip().startswith(("{", "[")) else raw
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def main():
    if not KC_PW:
        die("set KC_ADMIN_PASSWORD")
    with open(DEF_PATH, encoding="utf-8") as f:
        defn = json.load(f)

    print(f"-> Authenticating to {KC_URL} (realm master) as {KC_ADMIN}")
    status, tok = req("POST", f"{KC_URL}/realms/master/protocol/openid-connect/token",
                      form={"client_id": "admin-cli", "grant_type": "password",
                            "username": KC_ADMIN, "password": KC_PW})
    if status != 200 or not isinstance(tok, dict):
        die(f"auth failed (HTTP {status})")
    token = tok["access_token"]

    # 1. Ensure perm:* roles exist.
    print("-> Ensuring perm:* roles exist")
    for p in defn["perms"]:
        if DRY_RUN:
            continue
        s, _ = req("POST", "/roles", token, body={"name": p})
        if s == 201:
            print(f"   + created {p}")
        elif s not in (409,):
            print(f"   ! {p} -> HTTP {s}")

    # 2. Reconcile each business-role composite.
    for role, desired in defn["composites"].items():
        print(f"-> Reconciling composite: {role}")
        if not DRY_RUN:
            req("POST", "/roles", token, body={"name": role, "composite": True})  # idempotent
        s, current = req("GET", f"/roles/{role}/composites", token)
        current_names = {c["name"] for c in current} if isinstance(current, list) else set()
        desired_set = set(desired)
        missing = sorted(desired_set - current_names)
        extra = sorted(n for n in (current_names - desired_set) if n.startswith("perm:"))

        if missing:
            if DRY_RUN:
                print(f"   (dry-run) would add: {' '.join(missing)}")
            else:
                reps = []
                for m in missing:
                    _, rep = req("GET", f"/roles/{m}", token)
                    reps.append({"id": rep["id"], "name": rep["name"]})
                s2, _ = req("POST", f"/roles/{role}/composites", token, body=reps)
                print(f"   + added: {' '.join(missing)} (HTTP {s2})")
        else:
            print("   OK up to date")
        if extra:
            print(f"   ! extra perms present (not in policy, left untouched): {' '.join(extra)}")

    print("Done." if not DRY_RUN else "Dry-run complete (no changes written).")


if __name__ == "__main__":
    main()
