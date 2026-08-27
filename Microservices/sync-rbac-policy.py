#!/usr/bin/env python3
"""
Sync the canonical RBAC policy into every service.

The gateway owns the single source of truth:
    ApiGateway/src/main/resources/rbac-policy.json
This copies it verbatim into each other service's resources so all layers evaluate identical rules
(bundled locally = no runtime fetch, no single point of failure). Run after editing the policy; CI can
run it with --check to fail the build if any copy has drifted.

Usage:
    python sync-rbac-policy.py          # copy canonical -> all services
    python sync-rbac-policy.py --check  # verify copies match (no write); exit 1 on drift

--check prints one line per service rather than a bare OK. A green job that says nothing is a job
nobody reads, and the day it turns red the log has to name the service that drifted.
"""
import hashlib
import json
import sys
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent
CANONICAL = ROOT / "ApiGateway" / "src" / "main" / "resources" / "rbac-policy.json"
TARGETS = [
    ROOT / "DeliveryMicroservice" / "src" / "main" / "resources" / "rbac-policy.json",
    ROOT / "DriverService" / "src" / "main" / "resources" / "rbac-policy.json",
    ROOT / "AppBackend" / "src" / "main" / "resources" / "rbac-policy.json",
    ROOT / "AssistantService" / "src" / "main" / "resources" / "rbac-policy.json",
]


def digest(text):
    """First twelve hex characters — comparable by eye in a log, short enough to fit on a line."""
    return hashlib.md5(text.encode("utf-8")).hexdigest()[:12]


def counts(text):
    """Rules and distinct permissions, so the summary says what was checked, not only that it passed."""
    try:
        rules = json.loads(text).get("rules", [])
    except json.JSONDecodeError:
        return None
    perms = set()

    def walk(node):
        # A rule names its permission either directly (perm) or as a set of alternatives (anyPerm).
        if isinstance(node, dict):
            if isinstance(node.get("perm"), str):
                perms.add(node["perm"])
            for alternative in node.get("anyPerm", []) or []:
                if isinstance(alternative, str):
                    perms.add(alternative)
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(rules)
    return len(rules), len(perms)


def service_of(path):
    """DeliveryMicroservice/src/main/resources/rbac-policy.json -> DeliveryMicroservice"""
    return path.relative_to(ROOT).parts[0]


def main():
    check = "--check" in sys.argv
    if not CANONICAL.exists():
        print(f"ERROR: canonical not found: {CANONICAL}", file=sys.stderr)
        return 1

    source = CANONICAL.read_text(encoding="utf-8")
    reference = digest(source)
    width = max(len(service_of(p)) for p in [CANONICAL] + TARGETS)

    # The canonical file is listed too: the log then shows the five services the claim is about,
    # instead of four comparisons against a fifth file that never appears.
    if check:
        print(f"{service_of(CANONICAL):<{width}}  md5 {reference}  reference")

    drift = 0
    for target in TARGETS:
        name = service_of(target)
        if not check:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(source, encoding="utf-8")
            print(f"synced -> {target.relative_to(ROOT)}")
            continue

        if not target.exists():
            print(f"{name:<{width}}  {'absent':<16}  DRIFT")
            drift += 1
            continue

        found = digest(target.read_text(encoding="utf-8"))
        print(f"{name:<{width}}  md5 {found}  {'OK' if found == reference else 'DRIFT'}")
        if found != reference:
            drift += 1

    if check and drift:
        print(f"\n{drift} copy(ies) out of sync - run: python sync-rbac-policy.py", file=sys.stderr)
        return 1

    if check:
        found = counts(source)
        # ASCII only: CI consoles are not always UTF-8, and a mangled dash in a log looks like a bug.
        detail = f" - {found[0]} rules, {found[1]} distinct permissions" if found else ""
        print(f"\n{len(TARGETS) + 1} identical copies{detail}")
    else:
        print("Done.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
