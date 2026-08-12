"""Startup checks so a misconfigured production deploy fails loudly.

Without these, a missing HEVY_KEY_ENCRYPTION_KEY only surfaces the first
time a student tries to link their Hevy account, in front of a real user.
"""

from django.conf import settings
from django.core.checks import Error, register


@register(deploy=True)
def check_production_secrets(app_configs, **kwargs):
    errors = []
    if settings.DEBUG:
        return errors

    if not settings.HEVY_KEY_ENCRYPTION_KEY:
        errors.append(
            Error(
                "HEVY_KEY_ENCRYPTION_KEY is not set.",
                hint=(
                    "Students' Hevy API keys cannot be encrypted without it. Generate one with "
                    '`python -c "from cryptography.fernet import Fernet; '
                    'print(Fernet.generate_key().decode())"` and set it in the environment.'
                ),
                id="core.E001",
            )
        )
    if not settings.SECRET_KEY or settings.SECRET_KEY == "dev-only-insecure-key":
        errors.append(
            Error(
                "SECRET_KEY is unset or still the development placeholder.",
                hint="Set a unique random SECRET_KEY in the environment.",
                id="core.E002",
            )
        )
    if not settings.ALLOWED_HOSTS or "*" in settings.ALLOWED_HOSTS:
        errors.append(
            Error(
                "ALLOWED_HOSTS must list the real hostnames in production.",
                id="core.E003",
            )
        )
    return errors
