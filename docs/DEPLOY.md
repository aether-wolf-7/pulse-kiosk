# Deploying the Pulse Kiosk backend

Target: the client's VPS at `169.58.168.14`, which runs Docker and Portainer.
Public hostname: `kiosk.pulsefitness.com.br` (DNS already points at the VPS).

## What is already on that server

Checked from outside before touching anything:

| Port | State | Note |
|---|---|---|
| 80 | free | Caddy will take it (needed for the Let's Encrypt challenge) |
| 443 | free | Caddy will take it (the tablets connect here) |
| 8080 | **in use** | the client's existing service, left alone |
| 9443 | in use | Portainer |

Confirm this again on the server before deploying:

```bash
docker ps --format 'table {{.Names}}\t{{.Ports}}'
ss -tlnp | grep -E ':(80|443)\s'
```

If anything is listening on 80 or 443, **stop and re-plan**: taking those
ports would knock the client's own service offline.

## Why the stack looks like this

- **Only Caddy publishes ports.** Postgres and Django are on a private compose
  network with no host binding, so the database cannot be reached from the
  internet regardless of the VPS firewall.
- **Caddy terminates TLS** and renews the certificate automatically. This is
  not optional: the release build of the app refuses plain HTTP, and every
  student's Hevy API key travels over this connection.
- **Everything is named `pulsekiosk-*`** so it is obvious in Portainer which
  containers are ours.
- **The container refuses to start on an unsafe config.** `entrypoint.sh` runs
  `manage.py check --deploy` first, which fails on a missing encryption key, a
  placeholder `SECRET_KEY`, or wildcard hosts.

## Deploy

1. Copy `deploy/` and `backend/` to the server, e.g. `/opt/pulsekiosk`.

2. Create `deploy/.env` from `.env.example` and generate the secrets **on the
   server**:

   ```bash
   python3 -c "import secrets; print(secrets.token_urlsafe(64))"   # SECRET_KEY
   openssl rand -base64 32 | tr '+/' '-_'                          # HEVY_KEY_ENCRYPTION_KEY
   openssl rand -base64 24                                         # POSTGRES_PASSWORD
   ```

   > **Back up `HEVY_KEY_ENCRYPTION_KEY` somewhere other than this VPS.**
   > Lose it and no stored Hevy key can be decrypted again: every student has
   > to link their account from scratch. Rotating it has the same effect.

3. Build and start:

   ```bash
   cd /opt/pulsekiosk/deploy
   docker compose up -d --build
   docker compose logs -f backend
   ```

   The first Caddy start takes a few seconds while it obtains the certificate.

4. Create the admin user:

   ```bash
   docker compose exec backend python manage.py createsuperuser
   ```

5. Seed the pilot gym and machines (prints the device token for each tablet):

   ```bash
   docker compose exec backend python manage.py seed_pilot
   ```

6. Set the gym's maintenance code in the admin, under Academias. Without it,
   the tablets have no way out of kiosk mode.

## Verify

```bash
curl -sS https://kiosk.pulsefitness.com.br/api/v1/health/     # {"status":"ok",...}
curl -sS -o /dev/null -w '%{http_code}\n' https://kiosk.pulsefitness.com.br/api/v1/machine/config/   # 401 without a token
curl -sSI http://kiosk.pulsefitness.com.br/ | head -1          # 308 redirect to HTTPS
```

Then check the certificate is real (not Caddy's internal fallback):

```bash
echo | openssl s_client -connect kiosk.pulsefitness.com.br:443 -servername kiosk.pulsefitness.com.br 2>/dev/null | openssl x509 -noout -issuer -dates
```

And confirm the client's service is still up on 8080.

## Point the tablets at it

Set the release build's API base URL to
`https://kiosk.pulsefitness.com.br/api/v1/`, rebuild, and provision the
tablets per [PROVISIONING.md](PROVISIONING.md).

## Operations

```bash
docker compose logs -f backend                    # application logs
docker compose exec backend python manage.py retry_hevy_pushes   # force a retry now
docker compose exec db pg_dump -U pulsekiosk pulsekiosk > backup.sql
docker compose pull && docker compose up -d --build              # update
```

The `pulsekiosk-retry` container already retries failed Hevy pushes every five
minutes, so a Hevy outage recovers on its own.

### Backups

Two things must be backed up, and they are useless without each other:

1. the Postgres volume (`pulsekiosk_db_data`), and
2. `HEVY_KEY_ENCRYPTION_KEY` from `deploy/.env`.

A database dump alone cannot decrypt a single Hevy key.

## The APK download page

`https://kiosk.pulsefitness.com.br/baixar/` serves the signed release APK plus
short install instructions in Portuguese, so the client can test on any Android
device without a cable.

Caddy serves it from the `apk_data` volume; the API keeps the rest of the site.
To publish a new build, upload it into the running Caddy container:

```bash
docker cp app-release.apk pulsekiosk-caddy:/srv/app/pulse-kiosk.apk
```

Two things bit during setup and are worth remembering:

- **Do not use `{$DOMAIN}` inside an inline compose `config`.** Compose
  interpolates `$DOMAIN` before Caddy sees it, leaving literal braces, and
  Caddy then rejects `{kiosk.pulsefitness.com.br}` as a site address and
  crash-loops. Write the hostname literally.
- Installed by tapping, the APK is a **normal app, not a kiosk**. Device Owner
  only happens through the ADB step in PROVISIONING.md. That is deliberate: the
  client can try it on a personal phone without locking the phone down.
