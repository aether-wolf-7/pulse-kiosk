"""
Django settings — Pulse Kiosk backend.

Env-driven and fail-closed: everything defaults to production-safe values,
so forgetting a variable on a deploy degrades into a startup error, never
into a silently insecure server. Local dev opts in via backend/.env
(DEBUG=True); see .env.example.
"""

import sys
from pathlib import Path

import environ

BASE_DIR = Path(__file__).resolve().parent.parent

env = environ.Env(
    DEBUG=(bool, False),
    ALLOWED_HOSTS=(list, []),
)
environ.Env.read_env(BASE_DIR / ".env")

# No call-site default: django-environ only honours the scheme default above
# when the call site passes none, so DEBUG is False unless explicitly set.
DEBUG = env("DEBUG")
SECRET_KEY = env("SECRET_KEY", default="dev-only-insecure-key" if DEBUG else "")
# In dev the backend is reached from the emulator (10.0.2.2) and from real
# tablets over the gym LAN, so any host goes. Production must set the list.
ALLOWED_HOSTS = env("ALLOWED_HOSTS") or (["*"] if DEBUG else [])

# Fernet key for Hevy API keys at rest. Never stored in the DB.
# Generate with: python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
HEVY_KEY_ENCRYPTION_KEY = env("HEVY_KEY_ENCRYPTION_KEY", default="")

HEVY_API_BASE_URL = env("HEVY_API_BASE_URL", default="https://api.hevyapp.com")

INSTALLED_APPS = [
    "django.contrib.admin",
    "django.contrib.auth",
    "django.contrib.contenttypes",
    "django.contrib.sessions",
    "django.contrib.messages",
    "django.contrib.staticfiles",
    "rest_framework",
    "core",
]

MIDDLEWARE = [
    "django.middleware.security.SecurityMiddleware",
    "django.contrib.sessions.middleware.SessionMiddleware",
    "django.middleware.common.CommonMiddleware",
    "django.middleware.csrf.CsrfViewMiddleware",
    "django.contrib.auth.middleware.AuthenticationMiddleware",
    "django.contrib.messages.middleware.MessageMiddleware",
    "django.middleware.clickjacking.XFrameOptionsMiddleware",
]

ROOT_URLCONF = "config.urls"

TEMPLATES = [
    {
        "BACKEND": "django.template.backends.django.DjangoTemplates",
        "DIRS": [],
        "APP_DIRS": True,
        "OPTIONS": {
            "context_processors": [
                "django.template.context_processors.request",
                "django.contrib.auth.context_processors.auth",
                "django.contrib.messages.context_processors.messages",
            ],
        },
    },
]

WSGI_APPLICATION = "config.wsgi.application"

DATABASES = {
    "default": env.db("DATABASE_URL", default=f"sqlite:///{BASE_DIR / 'db.sqlite3'}"),
}

AUTH_PASSWORD_VALIDATORS = [
    {"NAME": "django.contrib.auth.password_validation.UserAttributeSimilarityValidator"},
    {"NAME": "django.contrib.auth.password_validation.MinimumLengthValidator"},
    {"NAME": "django.contrib.auth.password_validation.CommonPasswordValidator"},
    {"NAME": "django.contrib.auth.password_validation.NumericPasswordValidator"},
]

REST_FRAMEWORK = {
    "DEFAULT_RENDERER_CLASSES": ["rest_framework.renderers.JSONRenderer"],
    "DEFAULT_AUTHENTICATION_CLASSES": [],
    "DEFAULT_PERMISSION_CLASSES": ["rest_framework.permissions.AllowAny"],
    "UNAUTHENTICATED_USER": None,
    "DEFAULT_THROTTLE_CLASSES": ["core.throttling.DeviceLoginThrottle"],
    "DEFAULT_THROTTLE_RATES": {"login": "20/min"},
}

# A student is locked out after this many failed PIN attempts within the
# window; 4-digit PINs are only safe if guessing is expensive.
LOGIN_FAILURE_LIMIT = env.int("LOGIN_FAILURE_LIMIT", default=10)
LOGIN_FAILURE_WINDOW_MINUTES = env.int("LOGIN_FAILURE_WINDOW_MINUTES", default=15)

LANGUAGE_CODE = "pt-br"
TIME_ZONE = "America/Sao_Paulo"
USE_I18N = True
USE_TZ = True

STATIC_URL = "static/"
STATIC_ROOT = BASE_DIR / "staticfiles"

DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"

if "test" in sys.argv:
    # PIN hashing is deliberately slow, which makes the suite slow and, worse,
    # timing-sensitive: a loaded machine once stretched a run past the login
    # throttle's one-minute window and failed a test that is not about timing.
    # Production keeps the real hashers.
    PASSWORD_HASHERS = ["django.contrib.auth.hashers.MD5PasswordHasher"]
