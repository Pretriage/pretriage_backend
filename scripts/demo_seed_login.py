"""Optional smoke verification for seeded users through the backend login API.

No work occurs on import. The caller supplies identities and the protected seed
state; credentials and tokens are never returned or included in errors.
"""

from __future__ import annotations

import base64
import json
import ipaddress
import time
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from typing import Any, Callable
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


LOGIN_TIMEOUT_SECONDS = 15
MAX_LOGIN_ATTEMPTS = 3
MAX_RETRY_DELAY_SECONDS = 8.0


class LoginSmokeError(RuntimeError):
    """Safe login verification error with no credentials, tokens, or response body."""


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        return None


def _backend_origin(value: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise LoginSmokeError("A backend URL is required for login verification")
    try:
        parsed = urlsplit(value.strip().rstrip("/"))
        port = parsed.port
    except ValueError:
        raise LoginSmokeError("Backend URL is invalid") from None
    if parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ("", "/"):
        raise LoginSmokeError("Backend URL must be an origin without credentials, path, query, or fragment")
    host = parsed.hostname
    if not host or not parsed.netloc:
        raise LoginSmokeError("Backend URL must include a host")
    local = host.casefold() == "localhost"
    try:
        local = local or ipaddress.ip_address(host).is_loopback
    except ValueError:
        pass
    if parsed.scheme != "https" and not (parsed.scheme == "http" and local):
        raise LoginSmokeError("Login verification requires HTTPS; HTTP is allowed only for localhost")
    # Keep the caller's explicit port, while canonicalizing the trailing slash.
    netloc = parsed.netloc.lower()
    if port is not None and ":" not in netloc.rsplit("@", 1)[-1]:
        netloc = f"{netloc}:{port}"
    return f"{parsed.scheme.lower()}://{netloc}"


def validate_backend_url(value: str) -> str:
    """Validate an origin before provisioning; this function performs no I/O."""
    return _backend_origin(value)


def _retry_delay(error: HTTPError, attempt: int) -> float:
    header = error.headers.get("Retry-After") if error.headers else None
    if header:
        try:
            return min(MAX_RETRY_DELAY_SECONDS, max(0.0, float(header)))
        except ValueError:
            try:
                retry_at = parsedate_to_datetime(header)
                if retry_at.tzinfo is None:
                    retry_at = retry_at.replace(tzinfo=timezone.utc)
                return min(MAX_RETRY_DELAY_SECONDS, max(0.0, (retry_at - datetime.now(timezone.utc)).total_seconds()))
            except (TypeError, ValueError, OverflowError):
                pass
    return min(MAX_RETRY_DELAY_SECONDS, 0.5 * (2 ** attempt))


def _post_login(origin: str, email: str, password: str, *, sleep: Callable[[float], None]) -> dict[str, Any]:
    payload = json.dumps({"email": email, "password": password}, separators=(",", ":")).encode("utf-8")
    request = Request(
        origin + "/api/login",
        data=payload,
        headers={"Accept": "application/json", "Content-Type": "application/json"},
        method="POST",
    )
    opener = build_opener(_NoRedirect())
    for attempt in range(MAX_LOGIN_ATTEMPTS):
        try:
            with opener.open(request, timeout=LOGIN_TIMEOUT_SECONDS) as response:
                if response.status != 200:
                    raise LoginSmokeError("Backend login returned an unexpected status")
                try:
                    data = json.loads(response.read().decode("utf-8"))
                except (UnicodeDecodeError, json.JSONDecodeError):
                    raise LoginSmokeError("Backend login returned invalid JSON") from None
                if not isinstance(data, dict):
                    raise LoginSmokeError("Backend login response has an invalid shape")
                return data
        except HTTPError as exc:
            if exc.code == 429 and attempt + 1 < MAX_LOGIN_ATTEMPTS:
                sleep(_retry_delay(exc, attempt))
                continue
            raise LoginSmokeError(f"Backend login failed (HTTP {exc.code})") from None
        except (URLError, TimeoutError, OSError):
            # Avoid repeated password attempts after an ambiguous transport failure.
            raise LoginSmokeError("Backend login could not be verified because the request failed") from None
    raise LoginSmokeError("Backend login was rate limited after bounded retries")


def _jwt_subject(token: str) -> str:
    parts = token.split(".") if isinstance(token, str) else []
    if len(parts) != 3 or not all(parts):
        raise LoginSmokeError("Backend login did not return a JWT access token")
    encoded = parts[1]
    try:
        payload = base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4))
        claims = json.loads(payload.decode("utf-8"))
    except (ValueError, UnicodeDecodeError, json.JSONDecodeError):
        raise LoginSmokeError("Backend login returned an invalid JWT payload") from None
    subject = claims.get("sub") if isinstance(claims, dict) else None
    if not isinstance(subject, str) or not subject:
        raise LoginSmokeError("Backend login token is missing its subject")
    return subject


def verify_logins(
    identities: dict[str, dict[str, Any]],
    state: dict[str, Any],
    backend_url: str,
    *,
    sleep: Callable[[float], None] = time.sleep,
) -> int:
    """Login each seeded identity and verify its returned token subject.

    JWT payload decoding is used only to compare `sub`; the signature is not
    verified here. HTTPS to the configured backend is the transport trust
    boundary, and the backend itself validates the Auth0 token.
    """
    if not isinstance(identities, dict) or not isinstance(state, dict):
        raise LoginSmokeError("Identity mapping and protected seed state are required")
    origin = _backend_origin(backend_url)
    saved_accounts = state.get("accounts")
    if not isinstance(saved_accounts, dict):
        raise LoginSmokeError("Protected seed state has no account map")

    verified = 0
    for key, identity in sorted(identities.items()):
        if not isinstance(key, str) or not isinstance(identity, dict):
            raise LoginSmokeError("Identity mapping contains an invalid account")
        saved = saved_accounts.get(key)
        if not isinstance(saved, dict):
            raise LoginSmokeError(f"Saved credentials are missing for account {key!r}")
        email = saved.get("email")
        password = saved.get("password")
        subject = identity.get("sub")
        if not all(isinstance(value, str) and value for value in (email, password, subject)):
            raise LoginSmokeError(f"Saved login data is incomplete for account {key!r}")
        if saved.get("sub") not in (None, subject):
            raise LoginSmokeError(f"Saved subject does not match the identity mapping for account {key!r}")
        identity_email = identity.get("email")
        if isinstance(identity_email, str) and identity_email.strip().casefold() != email.strip().casefold():
            raise LoginSmokeError(f"Saved email does not match the identity mapping for account {key!r}")

        response = _post_login(origin, email, password, sleep=sleep)
        token = response.get("token")
        refresh_token = response.get("refreshToken")
        expires_in = response.get("renovarTokenEn")
        if not isinstance(token, str) or not token or not isinstance(refresh_token, str) or not refresh_token:
            raise LoginSmokeError(f"Backend login response is incomplete for account {key!r}")
        if not isinstance(expires_in, int) or isinstance(expires_in, bool) or expires_in <= 0:
            raise LoginSmokeError(f"Backend login expiry is invalid for account {key!r}")
        if _jwt_subject(token) != subject:
            raise LoginSmokeError(f"Backend login returned a different subject for account {key!r}")
        verified += 1
    return verified
