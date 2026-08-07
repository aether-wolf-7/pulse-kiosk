# Pulse Kiosk

Android kiosk app for gym tablets + backend. Students log sets/reps/load on a tablet fixed
next to each machine; the workout is pushed to their personal Hevy account via the official
Hevy API. Pilot: 3 Samsung Galaxy Tab A9+ tablets, 3 machines.

## Layout

- `backend/` — Django + DRF + PostgreSQL (SQLite in dev). Django admin is the admin panel.
  Multi-tenant (academia → machines/students/exercises). Hevy API keys encrypted at rest
  (Fernet, key outside the DB). Usage metrics logged for pilot validation.
- `android/` — Kotlin + Jetpack Compose app. Room + WorkManager offline queue (Stage 2),
  Device Owner + Lock Task Mode kiosk (Stage 3). Open in Android Studio.

## Backend quickstart

```bash
cd backend
python -m venv .venv
.venv/Scripts/pip install -r requirements.txt   # Windows (use bin/ on Linux)
.venv/Scripts/python manage.py migrate
.venv/Scripts/python manage.py seed_pilot        # pilot academia + 3 machines (prints device tokens)
.venv/Scripts/python manage.py createsuperuser
.venv/Scripts/python manage.py runserver
```

Admin at `http://localhost:8000/admin/`. API under `/api/v1/`:

| Endpoint | Auth | Purpose |
|---|---|---|
| `GET /api/v1/machine/config/` | `X-Device-Token` | Tablet boot: machine + exercises |
| `POST /api/v1/auth/login/` | `X-Device-Token` + body `{student_id, pin}` | Kiosk session |
| `POST /api/v1/auth/logout/` | `X-Session-Token` | End session |
| `POST /api/v1/hevy/link/` | `X-Session-Token` + body `{hevy_api_key}` | First-access Hevy link (validated live, stored encrypted) |
| `GET /api/v1/health/` | — | Health check |

Tests: `manage.py test` (9 tests: device auth, tenant isolation, PIN, session lifecycle, key encryption).

## Stages

1. **Stage 1 (~2 wks):** backend, admin, ID+PIN auth, Hevy key linking. ← current
2. **Stage 2 (~1 wk):** logging flow, push to Hevy on save, offline queue (Room + WorkManager).
3. **Stage 3 (~1 wk):** kiosk mode on the A9+ (Device Owner via ADB + Lock Task Mode), real-device tests.
