"""Fernet encryption for Hevy API keys at rest.

The encryption key lives in the environment (HEVY_KEY_ENCRYPTION_KEY),
never in the database and never derived from anything in the repo.

In DEBUG, a random key is generated once into backend/.dev-fernet-key
(gitignored) so local setup needs no extra step while still being
unguessable to anyone reading the source. Production has no fallback:
a missing key is a startup error, not a silently weak default.
"""

from pathlib import Path

from cryptography.fernet import Fernet, InvalidToken
from django.conf import settings
from django.core.exceptions import ImproperlyConfigured

DEV_KEY_FILENAME = ".dev-fernet-key"

__all__ = ["encrypt", "decrypt", "get_fernet", "InvalidToken"]


def _dev_key() -> bytes:
    path = Path(settings.BASE_DIR) / DEV_KEY_FILENAME
    if path.exists():
        return path.read_bytes().strip()
    key = Fernet.generate_key()
    path.write_bytes(key)
    return key


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
    """Raises InvalidToken if the stored value was encrypted under a
    different key (rotation, or a dev key carried into another env)."""
    return get_fernet().decrypt(ciphertext.encode()).decode()
