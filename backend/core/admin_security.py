"""Brute force protection for the Django admin login.

DRF throttling only covers the API views, so the admin sat on the public
internet with unlimited password attempts. The passwords generated at deploy
are strong, but the gym owner will eventually change his to something he can
remember, and the admin holds every student's personal data and their
encrypted Hevy keys.

Keyed on client IP and deliberately simple: no new dependency, cache backed,
and it fails open if the cache is unavailable rather than locking staff out
of their own panel.
"""

import logging

from django.conf import settings
from django.core.cache import cache
from django.http import HttpResponse
from django.utils import timezone

logger = logging.getLogger(__name__)

ADMIN_LOGIN_PATH = "/admin/login/"
CACHE_PREFIX = "adminlogin"


def _client_ip(request) -> str:
    # Caddy sets X-Forwarded-For; take the first hop, which is the real client.
    forwarded = request.META.get("HTTP_X_FORWARDED_FOR", "")
    if forwarded:
        return forwarded.split(",")[0].strip()
    return request.META.get("REMOTE_ADDR", "unknown")


class AdminLoginRateLimitMiddleware:
    """Limits failed admin login attempts per IP."""

    def __init__(self, get_response):
        self.get_response = get_response
        self.limit = getattr(settings, "ADMIN_LOGIN_ATTEMPT_LIMIT", 10)
        self.window = getattr(settings, "ADMIN_LOGIN_WINDOW_SECONDS", 900)

    def __call__(self, request):
        is_login_post = request.method == "POST" and request.path == ADMIN_LOGIN_PATH
        if not is_login_post:
            return self.get_response(request)

        key = f"{CACHE_PREFIX}:{_client_ip(request)}"
        try:
            attempts = cache.get(key, 0)
        except Exception:  # cache down: never lock staff out of their own panel
            return self.get_response(request)

        if attempts >= self.limit:
            logger.warning("Admin login blocked for %s (%s attempts)", _client_ip(request), attempts)
            return HttpResponse(
                "Muitas tentativas de login. Tente novamente em alguns minutos.",
                status=429,
                content_type="text/plain; charset=utf-8",
            )

        response = self.get_response(request)

        # Django answers a successful admin login with a redirect; anything
        # else on this path means the credentials were rejected.
        if response.status_code != 302:
            try:
                cache.set(key, attempts + 1, self.window)
            except Exception:
                pass
        else:
            try:
                cache.delete(key)
            except Exception:
                pass
        return response
