# ASM Track — VPS Deploy Steps

VPS: **`192.168.10.76`** · Admin → `:8090` · API/driver → `:80` · Odoo → `:8070`

Two gitignored things are NOT in the repo and must be copied manually:
`Microservices/.env` and `Microservices/db-seed/` (the data dumps).

---

## 1. Replace the code on the VPS
```bash
rm -rf ~/PFE                      # safe: docker volumes are NOT inside this folder
git clone <your-remote> ~/PFE
```

## 2. Copy .env + data dumps  (run on your LAPTOP)
```powershell
scp "C:\Users\M S I\OneDrive\Documents\PFE\Microservices\.env"      USER@192.168.10.76:~/PFE/Microservices/.env
scp -r "C:\Users\M S I\OneDrive\Documents\PFE\Microservices\db-seed" USER@192.168.10.76:~/PFE/Microservices/
```

## 3. Adjust .env for the VPS  (on the VPS)
Edit `~/PFE/Microservices/.env`:
```dotenv
MINIO_PUBLIC_URL=http://192.168.10.76:9000
ODOO_URL=http://odoo-2:8069/jsonrpc
ODOO_DB=odoo2
# keep ODOO_UID / ODOO_PASSWORD the same as your local .env (matches the restored Odoo data)
```

## 4. Open the firewall  (once)
```bash
sudo ufw allow 80/tcp     # API gateway (driver app + admin proxy)
sudo ufw allow 8090/tcp   # admin dashboard
sudo ufw allow 8070/tcp   # Odoo (optional, only to open Odoo UI)
sudo ufw allow 9000/tcp   # MinIO (POD file links)
```

## 5. Deploy + seed — one command
```bash
cd ~/PFE/Microservices
chmod +x deploy.sh        # first time only
./deploy.sh --seed        # wipes volumes, restores all dumps, builds + starts everything
```
First build is slow (Gradle). When it finishes, open **http://192.168.10.76:8090**.

> Later redeploys (after a `git pull`, no data change): just `./deploy.sh` (no `--seed`).

## 6. Driver app APK  (on the VPS)
```bash
cd ~/PFE/Apps/driverApp
flutter build apk --release        # already targets 192.168.10.76
# → build/app/outputs/flutter-apk/app-release.apk
```
Copy to the phone, install, open. The **phone must be on the company network** (able to
reach `192.168.10.76`). Quick test: open `http://192.168.10.76:8090` in the phone browser.

---

## Verify
```bash
docker compose ps                  # all Up / healthy
docker compose logs -f delivery-service
```
- Admin dashboard loads at `:8090`, you can log in (seeded users).
- Driver app connects and shows the seeded deliveries.
- Realtime banners work (handoff / dispatch).

## Notes & gotchas
- **`./deploy.sh --seed` wipes ALL docker volumes** — only use it for the first deploy
  (or when you intentionally want to reset to the seeded data).
- `.env` must match the dumped data (OAuth client secrets + password hashes live in the
  DBs) — that's why you copy your **local** `.env`, not a freshly generated one.
- Odoo runs as a separate compose (`docker-compose.odoo2.yml`) pinned to project `pfe`;
  `deploy.sh` brings it up and restores `odoo2.dump` + filestore automatically in `--seed`.
- The integration talks to Odoo over JSON-RPC, so Odoo's stored `web.base.url` is irrelevant.
- Docker named volumes survive `rm -rf ~/PFE`; only `docker compose down -v` deletes data.
