"""Fernet encryption for Hevy API keys at rest.

The encryption key lives in the environment (HEVY_KEY_ENCRYPTION_KEY),
never in the database. In DEBUG a deterministic dev key derived from
SECRET_KEY is used so local setup needs no extra step.
"""

import base64
import hashlib

from cryptography.fernet import Fernet
from django.conf import settings
from django.core.exceptions import ImproperlyConfigured


def _dev_key() -> bytes:
    digest = hashlib.sha256(settings.SECRET_KEY.encode()).digest()
    return base64.urlsafe_b64encode(digest)


def get_fernet() -> Fernet:
    key = settings.HEVY_KEY_ENCRYPTION_KEY
    if key:
        return Fernet(key.encode() if isinstance(key, str) else key)
    if settings.DEBUG:
        return Fernet(_dev_key())
    raise ImproperlyConfigured("HEVY_KEY_ENCRYPTION_KEY is required when DEBUG=False")


def encrypt(plaintext: str) -> str:
    return get_fernet().encrypt(plaintext.encode()).decode()


def decrypt(ciphertext: str) -> str:
    return get_fernet().decrypt(ciphertext.encode()).decode()
