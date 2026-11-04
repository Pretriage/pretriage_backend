"""Safely provision explicitly opted-in demo identities in Auth0.

This module has no command-line entry point and performs no work when imported.
The caller owns durable, access-controlled state persistence and must provide a
``save_state`` callback. Passwords for newly generated users are checkpointed
through that callback before any Auth0 user-creation request is sent.
"""

from __future__ import annotations

import json
import secrets
import string
import time
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from typing import Any, Callable
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, urlopen


STATE_VERSION = 1
DEFAULT_CONNECTION = "Username-Password-Authentication"
DEFAULT_TIMEOUT_SECONDS = 15
MAX_RETRIES = 4
RETRY_MAX_SECONDS = 8.0


class ProvisioningError(RuntimeError):
    """Safe-to-display error; never contains Auth0 response bodies or secrets."""

    def __init__(self, message: str, *, retry_after: float | None = None):
        super().__init__(message)
        self.retry_after = retry_after


def _validate_domain(value: Any) -> str:
    if not isinstance(value, str):
        raise ProvisioningError("Auth0 domain must be an HTTPS origin")
    parsed = urlsplit(value.strip().rstrip("/"))
    if parsed.scheme != "https" or not parsed.netloc or parsed.path or parsed.query or parsed.fragment:
        raise ProvisioningError("Auth0 domain must be an HTTPS origin without a path")
    if parsed.username or parsed.password:
        raise ProvisioningError("Auth0 domain must not contain user information")
    return f"https://{parsed.netloc.lower()}"


def _normalize_email(value: Any) -> str:
    if not isinstance(value, str):
        raise ProvisioningError("Each account requires a valid email")
    email = value.strip().lower()
    if not email or "@" not in email or any(ch.isspace() for ch in email):
        raise ProvisioningError("Each account requires a valid email")
    return email


def _validate_accounts(accounts: list[dict[str, Any]]) -> list[dict[str, Any]]:
    if not isinstance(accounts, list):
        raise ProvisioningError("accounts must be a list")
    normalized: list[dict[str, Any]] = []
    seen_keys: set[str] = set()
    seen_emails: set[str] = set()
    for account in accounts:
        if not isinstance(account, dict):
            raise ProvisioningError("Each account must be an object")
        key = account.get("key")
        if not isinstance(key, str) or not key.strip():
            raise ProvisioningError("Each account requires a stable key")
        key = key.strip()
        email = _normalize_email(account.get("email"))
        if key in seen_keys:
            raise ProvisioningError("Duplicate account key in request")
        if email in seen_emails:
            raise ProvisioningError("Duplicate account email in request")
        first_name = account.get("first_name")
        last_name = account.get("last_name")
        kind = account.get("kind")
        if not all(isinstance(x, str) and x.strip() for x in (first_name, last_name, kind)):
            raise ProvisioningError("Each account requires first_name, last_name, and kind")
        if "hospital_key" in account and account["hospital_key"] is not None and not isinstance(account["hospital_key"], str):
            raise ProvisioningError("hospital_key must be a string when provided")
        if "primary_specialty" in account and account["primary_specialty"] is not None and not isinstance(account["primary_specialty"], str):
            raise ProvisioningError("primary_specialty must be a string when provided")
        normalized.append({
            "key": key,
            "email": email,
            "first_name": first_name.strip(),
            "last_name": last_name.strip(),
            "kind": kind.strip(),
            "hospital_key": account.get("hospital_key"),
            "primary_specialty": account.get("primary_specialty"),
        })
        seen_keys.add(key)
        seen_emails.add(email)
    return normalized


def _new_password() -> str:
    """Generate a password with mixed character classes for common tenant rules."""
    alphabet = string.ascii_letters + string.digits + "!@#$%_-"
    required = [
        secrets.choice(string.ascii_lowercase),
        secrets.choice(string.ascii_uppercase),
        secrets.choice(string.digits),
        secrets.choice("!@#$%_-"),
    ]
    required.extend(secrets.choice(alphabet) for _ in range(36))
    secrets.SystemRandom().shuffle(required)
    return "".join(required)


def _save_checkpoint(save_state: Callable[[dict[str, Any]], None], state: dict[str, Any]) -> None:
    try:
        save_state(state)
    except Exception:
        # A caller's serialization exception can accidentally include object
        # representations; never propagate that text because state has secrets.
        raise ProvisioningError("Could not persist the protected Auth0 seed state") from None


def _decode_json(data: bytes, *, action: str, status: int | None = None) -> Any:
    try:
        return json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        suffix = f" (HTTP {status})" if status is not None else ""
        raise ProvisioningError(f"Auth0 returned an invalid response for {action}{suffix}") from None


def _retry_delay(error: HTTPError, attempt: int) -> float:
    header = error.headers.get("Retry-After") if error.headers else None
    if header:
        try:
            return min(RETRY_MAX_SECONDS, max(0.0, float(header)))
        except ValueError:
            try:
                retry_at = parsedate_to_datetime(header)
                if retry_at.tzinfo is None:
                    retry_at = retry_at.replace(tzinfo=timezone.utc)
                return min(RETRY_MAX_SECONDS, max(0.0, (retry_at - datetime.now(timezone.utc)).total_seconds()))
            except (TypeError, ValueError, OverflowError):
                pass
    return min(RETRY_MAX_SECONDS, 0.5 * (2 ** attempt))


def _http_json(
    method: str,
    url: str,
    *,
    headers: dict[str, str] | None = None,
    payload: dict[str, Any] | None = None,
    action: str,
    timeout: int = DEFAULT_TIMEOUT_SECONDS,
    sleep: Callable[[float], None] = time.sleep,
    max_attempts: int = MAX_RETRIES,
) -> Any:
    body = None if payload is None else json.dumps(payload, separators=(",", ":")).encode("utf-8")
    request_headers = {"Accept": "application/json", **(headers or {})}
    if body is not None:
        request_headers["Content-Type"] = "application/json"

    for attempt in range(max_attempts):
        request = Request(url, data=body, headers=request_headers, method=method)
        try:
            with urlopen(request, timeout=timeout) as response:
                return _decode_json(response.read(), action=action, status=response.status)
        except HTTPError as exc:
            if exc.code == 429 and attempt + 1 < max_attempts:
                sleep(_retry_delay(exc, attempt))
                continue
            # Never include Auth0's body, request URL, or exception text.
            raise ProvisioningError(
                f"Auth0 {action} failed (HTTP {exc.code})",
                retry_after=_retry_delay(exc, attempt) if exc.code == 429 else None,
            ) from None
        except (TimeoutError, URLError, ConnectionError, OSError):
            if attempt + 1 < max_attempts:
                sleep(min(RETRY_MAX_SECONDS, 0.5 * (2 ** attempt)))
                continue
            raise ProvisioningError(f"Auth0 {action} failed after network retries") from None
    raise ProvisioningError(f"Auth0 {action} failed after retries")


def _get_token(settings: dict[str, Any], domain: str, *, sleep: Callable[[float], None]) -> str:
    client_id = settings.get("client_id")
    client_secret = settings.get("client_secret")
    if not isinstance(client_id, str) or not client_id or not isinstance(client_secret, str) or not client_secret:
        raise ProvisioningError("Auth0 Management API client credentials are required")
    token_response = _http_json(
        "POST",
        f"{domain}/oauth/token",
        payload={
            "grant_type": "client_credentials",
            "client_id": client_id,
            "client_secret": client_secret,
            "audience": f"{domain}/api/v2/",
            "scope": "read:users create:users",
        },
        action="Management API token request",
        sleep=sleep,
    )
    token = token_response.get("access_token") if isinstance(token_response, dict) else None
    if not isinstance(token, str) or not token:
        raise ProvisioningError("Auth0 token response did not include an access token")
    return token


def _users_by_email(email: str, *, domain: str, token: str, sleep: Callable[[float], None]) -> list[dict[str, Any]]:
    query = urlencode({"email": email})
    result = _http_json(
        "GET",
        f"{domain}/api/v2/users-by-email?{query}",
        headers={"Authorization": f"Bearer {token}"},
        action="user lookup",
        sleep=sleep,
    )
    if not isinstance(result, list) or any(not isinstance(user, dict) for user in result):
        raise ProvisioningError("Auth0 user lookup returned an invalid response")
    return result


def _assert_owned(user: dict[str, Any], *, seed_key: str, account_key: str) -> str:
    metadata = user.get("app_metadata")
    if not isinstance(metadata, dict) or metadata.get("pretriage_demo_seed") != seed_key or metadata.get("account_key") != account_key:
        raise ProvisioningError("An Auth0 account with this email exists but is not owned by this demo seed")
    user_id = user.get("user_id")
    if not isinstance(user_id, str) or not user_id:
        raise ProvisioningError("Owned Auth0 account is missing its user identifier")
    return user_id


def _lookup_owned(
    account: dict[str, Any],
    *,
    domain: str,
    token: str,
    connection: str,
    seed_key: str,
    sleep: Callable[[float], None],
) -> dict[str, Any] | None:
    users = [
        user for user in _users_by_email(account["email"], domain=domain, token=token, sleep=sleep)
        if _normalize_email(user.get("email")) == account["email"]
    ]
    if len(users) > 1:
        raise ProvisioningError("Multiple Auth0 accounts match this email")
    if not users:
        return None
    identities = users[0].get("identities")
    if not isinstance(identities, list) or not any(
        isinstance(identity, dict) and identity.get("connection") == connection
        for identity in identities
    ):
        raise ProvisioningError("An Auth0 account with this email uses a different connection")
    _assert_owned(users[0], seed_key=seed_key, account_key=account["key"])
    return users[0]


def _create_user(
    account: dict[str, Any],
    *,
    domain: str,
    token: str,
    connection: str,
    seed_key: str,
    password: str,
    sleep: Callable[[float], None],
) -> dict[str, Any]:
    payload = {
        "connection": connection,
        "email": account["email"],
        "password": password,
        "given_name": account["first_name"],
        "family_name": account["last_name"],
        "name": f"{account['first_name']} {account['last_name']}",
        "email_verified": False,
        "verify_email": False,
        "app_metadata": {
            "pretriage_demo_seed": seed_key,
            "account_key": account["key"],
            "kind": account["kind"],
            **({"hospital_key": account["hospital_key"]} if account.get("hospital_key") is not None else {}),
        },
    }
    if account.get("primary_specialty"):
        payload["app_metadata"]["primary_specialty"] = account["primary_specialty"]
    return _http_json(
        "POST",
        f"{domain}/api/v2/users",
        headers={"Authorization": f"Bearer {token}"},
        payload=payload,
        action="user creation",
        sleep=sleep,
        # The outer reconciler must look up the email after every ambiguous
        # create failure before attempting another POST.
        max_attempts=1,
    )


def provision_identities(
    accounts: list[dict[str, Any]],
    settings: dict[str, Any],
    state: dict[str, Any],
    save_state: Callable[[dict[str, Any]], None],
    *,
    sleep: Callable[[float], None] = time.sleep,
) -> dict[str, dict[str, Any]]:
    """Provision/reuse identities owned by one demo seed.

    ``state`` is the caller's durable, access-controlled checkpoint. Passwords
    live only in that state; they are intentionally omitted from the returned
    identity mapping and from all errors/log messages.
    """
    if not isinstance(settings, dict) or not isinstance(state, dict) or not callable(save_state):
        raise ProvisioningError("settings, state, and a state persistence callback are required")
    normalized = _validate_accounts(accounts)
    domain = _validate_domain(settings.get("domain"))
    seed_key = settings.get("seed_key")
    if not isinstance(seed_key, str) or not seed_key.strip():
        raise ProvisioningError("A stable seed_key is required")
    seed_key = seed_key.strip()
    connection = settings.get("connection", DEFAULT_CONNECTION)
    if not isinstance(connection, str) or not connection.strip():
        raise ProvisioningError("Auth0 database connection is required")
    connection = connection.strip()

    if state.get("version") not in (None, STATE_VERSION):
        raise ProvisioningError("Unsupported Auth0 seed state version")
    if state.get("seed_key") not in (None, seed_key) or state.get("auth0_domain") not in (None, domain):
        raise ProvisioningError("Auth0 seed state belongs to a different seed or tenant")
    stored_accounts = state.get("accounts", {})
    if not isinstance(stored_accounts, dict):
        raise ProvisioningError("Auth0 seed state has an invalid accounts map")
    state.setdefault("version", STATE_VERSION)
    state.setdefault("seed_key", seed_key)
    state.setdefault("auth0_domain", domain)
    state.setdefault("accounts", stored_accounts)
    state.setdefault("hospital_place_ids", {})
    if not isinstance(state["hospital_place_ids"], dict):
        raise ProvisioningError("Auth0 seed state has an invalid hospital place IDs map")

    token = _get_token(settings, domain, sleep=sleep)
    identities: dict[str, dict[str, Any]] = {}

    for account in normalized:
        key = account["key"]
        prior = stored_accounts.get(key)
        if prior is not None and not isinstance(prior, dict):
            raise ProvisioningError(f"Stored state for account key {key!r} is invalid")
        if isinstance(prior, dict):
            if _normalize_email(prior.get("email")) != account["email"]:
                raise ProvisioningError(f"Email changed for existing seed account {key!r}")
            password = prior.get("password")
            if not isinstance(password, str) or not password:
                raise ProvisioningError(f"Saved password is missing for account {key!r}; refusing to return an unusable login")
            prior_sub = prior.get("sub")
        else:
            password = None
            prior_sub = None

        existing = _lookup_owned(
            account,
            domain=domain,
            token=token,
            connection=connection,
            seed_key=seed_key,
            sleep=sleep,
        )
        if existing is None:
            if prior is None:
                password = _new_password()
                # Persist the credential before any API write so a timeout or
                # process crash cannot create an account whose password is lost.
                stored_accounts[key] = {
                    "email": account["email"],
                    "password": password,
                    "sub": None,
                    "kind": account["kind"],
                }
                _save_checkpoint(save_state, state)
            # Checkpointed state is already durable. If POST times out, reconcile
            # by looking the email up before any retry, avoiding duplicate creates.
            create_error: ProvisioningError | None = None
            created: dict[str, Any] | None = None
            for attempt in range(MAX_RETRIES):
                try:
                    created = _create_user(
                        account,
                        domain=domain,
                        token=token,
                        connection=connection,
                        seed_key=seed_key,
                        password=password,
                        sleep=sleep,
                    )
                    break
                except ProvisioningError as exc:
                    create_error = exc
                    # A network error, 429, 5xx, or conflict may have occurred
                    # after Auth0 accepted the request. Reconcile before retry.
                    existing = _lookup_owned(
                        account,
                        domain=domain,
                        token=token,
                        connection=connection,
                        seed_key=seed_key,
                        sleep=sleep,
                    )
                    if existing is not None:
                        break
                    if "HTTP 4" in str(exc) and "HTTP 429" not in str(exc) and "HTTP 409" not in str(exc):
                        raise
                    if "HTTP 409" in str(exc):
                        raise
                    if attempt + 1 < MAX_RETRIES:
                        sleep(exc.retry_after if exc.retry_after is not None else min(RETRY_MAX_SECONDS, 0.5 * (2 ** attempt)))
            if existing is not None:
                user_id = _assert_owned(existing, seed_key=seed_key, account_key=key)
            elif created is not None:
                try:
                    user_id = _assert_owned(created, seed_key=seed_key, account_key=key)
                except ProvisioningError:
                    raise ProvisioningError(f"Auth0 created account {key!r} without expected ownership metadata") from None
            else:
                if create_error is not None:
                    raise create_error
                raise ProvisioningError(f"Auth0 user creation failed for account {key!r}")
        else:
            if prior is None:
                raise ProvisioningError(
                    f"An owned Auth0 account exists for {key!r}, but saved seed state is missing; refusing to return an unusable login"
                )
            user_id = _assert_owned(existing, seed_key=seed_key, account_key=key)

        if prior_sub and prior_sub != user_id:
            raise ProvisioningError(f"Auth0 user identifier changed for account {key!r}")
        stored_accounts[key].update({"email": account["email"], "sub": user_id, "kind": account["kind"]})
        _save_checkpoint(save_state, state)
        identity = {k: account[k] for k in ("email", "first_name", "last_name", "kind", "hospital_key", "primary_specialty") if account.get(k) is not None}
        identity["sub"] = user_id
        identities[key] = identity

    return identities
