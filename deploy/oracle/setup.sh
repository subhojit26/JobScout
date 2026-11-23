#!/usr/bin/env bash
# One-shot setup / update for the JobWatch backend on an Oracle Cloud Always Free
# Ubuntu VM (22.04 or 24.04, ARM or AMD). Safe to re-run: re-running pulls the latest
# code and redeploys.
#
#   curl -fsSL https://raw.githubusercontent.com/subhojit26/JobScout/master/deploy/oracle/setup.sh -o setup.sh
#   bash setup.sh
set -euo pipefail

REPO_URL="https://github.com/subhojit26/JobScout.git"
APP_DIR="$HOME/JobScout"
DEPLOY_DIR="$APP_DIR/deploy/oracle"

log() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }

# 1. Docker -------------------------------------------------------------------
if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
  log "Installing Docker and git"
  sudo apt-get update -y
  sudo apt-get install -y docker.io docker-compose-v2 git curl
  sudo systemctl enable --now docker
fi
if ! command -v sqlite3 >/dev/null 2>&1; then
  log "Installing sqlite3 (used by the backup job)"
  sudo apt-get install -y sqlite3
fi
DOCKER="docker"
if ! docker info >/dev/null 2>&1; then
  sudo usermod -aG docker "$USER" || true
  DOCKER="sudo docker"   # group change applies on next login; use sudo for this run
fi

# 2. Swap (keeps the Maven build and the JVM from being OOM-killed on small shapes) ---
if [ "$(swapon --show --noheadings | wc -l)" -eq 0 ]; then
  log "Adding 2 GB swap file"
  sudo fallocate -l 2G /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile
  sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
fi

# 3. Host firewall: Oracle's Ubuntu images REJECT everything except SSH by default ---
log "Opening ports 80 and 443 in the VM firewall"
for port in 80 443; do
  if ! sudo iptables -C INPUT -p tcp -m state --state NEW --dport "$port" -j ACCEPT 2>/dev/null; then
    sudo iptables -I INPUT 1 -p tcp -m state --state NEW --dport "$port" -j ACCEPT
  fi
done
if ! dpkg -s iptables-persistent >/dev/null 2>&1; then
  echo iptables-persistent iptables-persistent/autosave_v4 boolean true | sudo debconf-set-selections
  echo iptables-persistent iptables-persistent/autosave_v6 boolean true | sudo debconf-set-selections
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y iptables-persistent
fi
sudo netfilter-persistent save >/dev/null

# 4. Code ---------------------------------------------------------------------------
if [ -d "$APP_DIR/.git" ]; then
  log "Pulling latest code"
  git -C "$APP_DIR" pull --ff-only
else
  log "Cloning $REPO_URL"
  git clone "$REPO_URL" "$APP_DIR"
fi
cd "$DEPLOY_DIR"
mkdir -p data

# 5. Config -------------------------------------------------------------------------
if [ ! -f .env ]; then
  cp env.template .env
  chmod 600 .env
  PUBLIC_IP="$(curl -fsS https://api.ipify.org || true)"
  if [ -n "$PUBLIC_IP" ]; then
    sed -i "s|^DOMAIN=.*|DOMAIN=${PUBLIC_IP//./-}.sslip.io|" .env
  fi
  log "Created $DEPLOY_DIR/.env"
  echo "Fill in your secrets, then run this script again:"
  echo "    nano $DEPLOY_DIR/.env"
  echo "    bash $0"
  exit 0
fi
DOMAIN="$(grep -E '^DOMAIN=' .env | cut -d= -f2-)"
if [ -z "$DOMAIN" ]; then
  echo "DOMAIN is empty in .env - set it (e.g. 1-2-3-4.sslip.io for IP 1.2.3.4) and re-run." >&2
  exit 1
fi
for key in ADZUNA_APP_ID ADZUNA_APP_KEY JOBWATCH_EMAIL_FROM SPRING_MAIL_PASSWORD JOBWATCH_EMAIL_TO; do
  if [ -z "$(grep -E "^${key}=" .env | cut -d= -f2-)" ]; then
    echo "WARNING: $key is empty in .env - fetching or the 6 AM email may not work." >&2
  fi
done

# 5b. Daily database backup at 02:00 India time (20:30 UTC) -------------------------------
log "Installing daily backup job"
echo "30 20 * * * root /bin/bash $DEPLOY_DIR/backup.sh >> /var/log/jobwatch-backup.log 2>&1"   | sudo tee /etc/cron.d/jobwatch-backup >/dev/null
sudo chmod 644 /etc/cron.d/jobwatch-backup

# 6. Build and start ---------------------------------------------------------------------
log "Building and starting containers (first build takes ~5 minutes)"
$DOCKER compose up -d --build
$DOCKER image prune -f >/dev/null

# 7. Verify ---------------------------------------------------------------------------
log "Waiting for https://$DOMAIN/healthz"
for _ in $(seq 1 60); do
  if curl -fsS -m 5 "https://$DOMAIN/healthz" >/dev/null 2>&1; then
    curl -fsS "https://$DOMAIN/healthz"; echo
    log "Backend is live at https://$DOMAIN"
    echo "Next: in Vercel set VITE_API_BASE_URL=https://$DOMAIN and redeploy the frontend."
    exit 0
  fi
  sleep 5
done
echo "Backend did not become healthy in 5 minutes. Check logs with:" >&2
echo "    cd $DEPLOY_DIR && $DOCKER compose logs --tail=100" >&2
exit 1
