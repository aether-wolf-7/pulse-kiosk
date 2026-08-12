"""Thin client for the official Hevy public API (https://api.hevyapp.com/docs/).

The API is early-stage, so everything Hevy-related goes through here:
one place to absorb contract changes, timeouts and retries.
"""

import logging

import requests
from django.conf import settings

logger = logging.getLogger(__name__)

TIMEOUT = 15


class HevyError(Exception):
    def __init__(self, message, status_code=None):
        super().__init__(message)
        self.status_code = status_code


class HevyClient:
    def __init__(self, api_key: str):
        self.api_key = api_key
        self.base_url = settings.HEVY_API_BASE_URL.rstrip("/")

    def _headers(self):
        return {"api-key": self.api_key, "Content-Type": "application/json"}

    def _request(self, method: str, path: str, **kwargs):
        url = f"{self.base_url}{path}"
        try:
            resp = requests.request(method, url, headers=self._headers(), timeout=TIMEOUT, **kwargs)
        except requests.RequestException as exc:
            raise HevyError(f"Hevy API unreachable: {exc}") from exc
        if resp.status_code == 401:
            raise HevyError("API key inválida ou sem Hevy Pro", status_code=401)
        if not resp.ok:
            logger.warning("Hevy API %s %s -> %s: %s", method, path, resp.status_code, resp.text[:500])
            raise HevyError(f"Hevy API error {resp.status_code}", status_code=resp.status_code)
        try:
            return resp.json()
        except ValueError as exc:
            # A 200 with a non-JSON body means we are talking to something
            # that is not Hevy (captive portal, proxy error page). Treat it
            # as a Hevy failure instead of letting it escape as a 500.
            logger.warning("Hevy API %s %s -> non-JSON body: %s", method, path, resp.text[:200])
            raise HevyError("Resposta inesperada da API do Hevy", status_code=resp.status_code) from exc

    def validate_key(self) -> bool:
        """Cheapest call that requires a valid key."""
        self._request("GET", "/v1/workouts/count")
        return True

    def list_workouts(self, page: int = 1, page_size: int = 5):
        return self._request("GET", "/v1/workouts", params={"page": page, "pageSize": page_size})

    def create_workout(self, title: str, exercises: list, start_time: str, end_time: str):
        """exercises: [{"exercise_template_id": ..., "sets": [{"weight_kg":..., "reps":...}]}]"""
        payload = {
            "workout": {
                "title": title,
                "start_time": start_time,
                "end_time": end_time,
                "is_private": False,
                "exercises": exercises,
            }
        }
        return self._request("POST", "/v1/workouts", json=payload)
