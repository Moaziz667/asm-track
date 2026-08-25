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
"""
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

def main():
    check = "--check" in sys.argv
    if not CANONICAL.exists():
        print(f"ERROR: canonical not found: {CANONICAL}", file=sys.stderr)
        return 1
    source = CANONICAL.read_text(encoding="utf-8")
    drift = 0
    for t in TARGETS:
        if check:
            if not t.exists() or t.read_text(encoding="utf-8") != source:
                print(f"DRIFT: {t.relative_to(ROOT)}")
                drift += 1
        else:
            t.parent.mkdir(parents=True, exist_ok=True)
            t.write_text(source, encoding="utf-8")
            print(f"synced -> {t.relative_to(ROOT)}")
    if check and drift:
        print(f"{drift} copy(ies) out of sync — run: python sync-rbac-policy.py", file=sys.stderr)
        return 1
    print("OK" if check else "Done.")
    return 0

if __name__ == "__main__":
    sys.exit(main())
