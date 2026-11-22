# Deploy the JobWatch backend on Oracle Cloud (Always Free)

Oracle's Always Free tier gives you a VM that never sleeps and has its own disk,
which is exactly what JobWatch needs:

- **Always on** → the 6 AM (IST) digest email actually fires.
- **Persistent disk** → the SQLite job database survives restarts and redeploys.
- **Enough RAM** → no more hung requests from a 256 MB machine.

The frontend stays on Vercel. Total cost: **₹0**.

---

## 1. Create the Oracle Cloud account

1. Sign up at <https://signup.cloud.oracle.com/>.
   - A card is required for identity verification only; Always Free resources are never charged.
   - **Home region:** pick one close to you (e.g. *India West (Mumbai)* or *India South (Hyderabad)*).
     It can't be changed later, and Always Free VMs can only be created there.
2. Wait for the "account is ready" email (usually a few minutes).

## 2. Create the VM

Console → ☰ → **Compute → Instances → Create instance**

| Setting | Value |
|---|---|
| Name | `jobwatch` |
| Image | **Canonical Ubuntu 24.04** (or 22.04) |
| Shape | **Ampere → VM.Standard.A1.Flex**, 1 OCPU, 6 GB RAM (all "Always Free-eligible") |
| Networking | Create new VCN + public subnet, **Assign a public IPv4 address: Yes** |
| SSH keys | **Generate a key pair** and download the private key (or paste your own public key) |

Click **Create**. When it shows *Running*, copy the **Public IP address**.

> **"Out of capacity" for A1?** That's common. Either retry later or in another
> availability domain, or pick shape **VM.Standard.E2.1.Micro** (AMD, 1 GB RAM, also
> Always Free). On the Micro shape, set `JAVA_HEAP=512m` in `.env` in step 5.

## 3. Open ports 80 and 443 in Oracle's network firewall

Instance page → **Subnet** link → **Security Lists** → *Default Security List* →
**Add Ingress Rules**, add two rules:

| Source CIDR | IP Protocol | Destination port |
|---|---|---|
| `0.0.0.0/0` | TCP | `80` |
| `0.0.0.0/0` | TCP | `443` |

(The VM's own firewall is opened by the setup script.)

## 4. SSH in

From PowerShell on your PC (use the key you downloaded):

```powershell
ssh -i $HOME\Downloads\ssh-key-XXXX.key ubuntu@<PUBLIC_IP>
```

If Windows complains the key is "too open", run once:
`icacls $HOME\Downloads\ssh-key-XXXX.key /inheritance:r /grant:r "$($env:USERNAME):R"`

## 5. Run the setup script (on the VM)

```bash
curl -fsSL https://raw.githubusercontent.com/subhojit26/JobScout/master/deploy/oracle/setup.sh -o setup.sh
bash setup.sh
```

The first run installs Docker, adds swap, opens the VM firewall, clones the repo and
creates `~/JobScout/deploy/oracle/.env`, then stops so you can add your secrets:

```bash
nano ~/JobScout/deploy/oracle/.env
```

Fill in `ADZUNA_APP_ID`, `ADZUNA_APP_KEY`, `JOBWATCH_EMAIL_FROM`, `SPRING_MAIL_PASSWORD`
(a Gmail **App Password**), `JOBWATCH_EMAIL_TO`, and optionally `HEALTHCHECK_URL`.
`DOMAIN` is pre-filled as `<your-ip>.sslip.io`, a free hostname that points to your VM
so Caddy can get a real HTTPS certificate. Save (Ctrl+O, Enter, Ctrl+X), then:

```bash
bash setup.sh
```

This builds the app (~5 min the first time), starts it behind HTTPS, checks
`https://<DOMAIN>/healthz`, and kicks off a first job fetch so the dashboard isn't empty.

## 6. Point the Vercel frontend at the new backend

Vercel → your frontend project → **Settings → Environment Variables**:

- `VITE_API_BASE_URL` = `https://<DOMAIN>` (e.g. `https://129-154-1-2.sslip.io`, no trailing slash)

Then **Deployments → ⋯ on the latest → Redeploy**. (The variable is baked in at build
time, so a redeploy is required.)

## 7. Turn off the old Fly app

Once the dashboard works from Vercel, stop paying for Fly:

```powershell
fly scale count 0 -a appjob
```

(or `fly apps destroy appjob` to remove it entirely).

---

## Day-to-day

All commands run on the VM in `~/JobScout/deploy/oracle`:

| Task | Command |
|---|---|
| Deploy latest code from GitHub | `bash setup.sh` |
| Live logs | `docker compose logs -f app` |
| Restart | `docker compose restart app` |
| Status | `docker compose ps` |
| Back up the database | `cp data/jobwatch.db ~/jobwatch-$(date +%F).db` |

The containers restart automatically after crashes and VM reboots, and the digest runs
every day at 06:00 Asia/Kolkata (change `JOBWATCH_CRON` / `JOBWATCH_CRON_ZONE` in `.env`
and re-run `bash setup.sh`).

**Important — idle reclaim:** on a Free Tier account Oracle may stop VMs whose CPU,
network and memory use stay under ~20% for 7 days. JobWatch is a light app, so it can hit
that. To avoid it, upgrade the account to **Pay As You Go** (Console → Billing → Upgrade).
Always Free resources stay free after upgrading; just don't create anything outside the
Always Free limits.
