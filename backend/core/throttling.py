"""Rate limiting for the kiosk login endpoint.

Tablets are shared, so throttling by client IP would punish a whole gym
behind one NAT. Throttle per tablet instead: each device token gets its
own bucket, which is the granularity an attacker would have to work with.
"""

from rest_framework.throttling import SimpleRateThrottle


class DeviceLoginThrottle(SimpleRateThrottle):
    scope = "login"

    def get_cache_key(self, request, view):
        # Only throttles the login view; everything else is unthrottled.
        if getattr(view, "throttle_scope_login", False) is not True:
            return None
        token = request.headers.get("X-Device-Token", "")
        ident = token or self.get_ident(request)
        return self.cache_format % {"scope": self.scope, "ident": ident}
