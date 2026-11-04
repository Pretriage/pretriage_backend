"""Mock-only tests for scripts.demo_seed_auth0; no Auth0 calls are made."""

from __future__ import annotations

import copy
import json
from urllib.parse import parse_qs, urlsplit
import unittest
from urllib.error import URLError
from urllib.request import Request
from unittest.mock import patch

from scripts import demo_seed_auth0 as seed


DOMAIN = "https://tenant.example.auth0.com"
SETTINGS = {
    "domain": DOMAIN,
    "client_id": "test-client",
    "client_secret": "test-secret",
    "connection": "Username-Password-Authentication",
    "seed_key": "demo-caba-v1",
}
ACCOUNT = {
    "key": "hospital-a-doctor-1",
    "email": "Doctor.One@Example.test",
    "first_name": "Doctor",
    "last_name": "One",
    "kind": "doctor",
    "hospital_key": "hospital-a",
    "primary_specialty": "CLINICA_MEDICA",
}


class FakeResponse:
    def __init__(self, data: object, status: int = 200):
        self.data = json.dumps(data).encode("utf-8")
        self.status = status

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def read(self):
        return self.data


def response_for_user(sub: str, *, email: str | None = None, seed_key: str = "demo-caba-v1", account_key: str = "hospital-a-doctor-1") -> dict:
    return {
        "user_id": sub,
        "email": email or "doctor.one@example.test",
        "identities": [{"connection": "Username-Password-Authentication"}],
        "app_metadata": {"pretriage_demo_seed": seed_key, "account_key": account_key},
    }


class DemoSeedAuth0Tests(unittest.TestCase):
    def setUp(self):
        self.state = {"hospital_place_ids": {"hospital-a": "place-a"}}
        self.saved = []

    def save(self, state):
        self.saved.append(copy.deepcopy(state))

    def token_response(self):
        return FakeResponse({"access_token": "opaque-token", "token_type": "Bearer"})

    def test_creates_and_persists_password_before_auth0_write(self):
        calls = []

        def fake_open(request: Request, timeout: int):
            calls.append(request)
            if request.full_url.endswith("/oauth/token"):
                return self.token_response()
            if request.full_url.startswith(DOMAIN + "/api/v2/users-by-email?"):
                self.assertEqual(parse_qs(urlsplit(request.full_url).query), {"email": ["doctor.one@example.test"]})
                return FakeResponse([])
            if request.full_url.endswith("/api/v2/users") and request.method == "POST":
                self.assertTrue(self.saved)
                checkpoint = self.saved[0]["accounts"][ACCOUNT["key"]]
                self.assertTrue(checkpoint["password"])
                self.assertIsNone(checkpoint["sub"])
                payload = json.loads(request.data.decode("utf-8"))
                self.assertEqual(payload["email"], "doctor.one@example.test")
                self.assertFalse(payload["verify_email"])
                self.assertFalse(payload["email_verified"])
                self.assertEqual(payload["app_metadata"]["pretriage_demo_seed"], SETTINGS["seed_key"])
                self.assertEqual(payload["app_metadata"]["account_key"], ACCOUNT["key"])
                return FakeResponse(response_for_user("auth0|doctor-1"), status=201)
            self.fail(f"unexpected request: {request.method} {request.full_url}")

        with patch.object(seed, "urlopen", side_effect=fake_open):
            identities = seed.provision_identities([ACCOUNT], SETTINGS, self.state, self.save, sleep=lambda _: None)

        self.assertEqual(identities[ACCOUNT["key"]]["sub"], "auth0|doctor-1")
        self.assertNotIn("password", identities[ACCOUNT["key"]])
        self.assertEqual(self.state["accounts"][ACCOUNT["key"]]["sub"], "auth0|doctor-1")
        self.assertGreaterEqual(len(self.saved), 2)
        self.assertEqual(self.saved[0]["accounts"][ACCOUNT["key"]]["sub"], None)
        self.assertEqual(self.saved[-1]["accounts"][ACCOUNT["key"]]["sub"], "auth0|doctor-1")

    def test_reuses_owned_identity_without_creating_duplicate(self):
        self.state.update({
            "version": seed.STATE_VERSION,
            "seed_key": SETTINGS["seed_key"],
            "auth0_domain": DOMAIN,
            "accounts": {ACCOUNT["key"]: {"email": "doctor.one@example.test", "password": "saved-password", "sub": "auth0|doctor-1"}},
        })
        methods = []

        def fake_open(request: Request, timeout: int):
            methods.append(request.method)
            if request.full_url.endswith("/oauth/token"):
                return self.token_response()
            if request.full_url.startswith(DOMAIN + "/api/v2/users-by-email?"):
                return FakeResponse([response_for_user("auth0|doctor-1")])
            self.fail("reuse path must not create a user")

        with patch.object(seed, "urlopen", side_effect=fake_open):
            result = seed.provision_identities([ACCOUNT], SETTINGS, self.state, self.save, sleep=lambda _: None)

        self.assertEqual(result[ACCOUNT["key"]]["sub"], "auth0|doctor-1")
        self.assertNotIn("POST", methods[1:])

    def test_refuses_unowned_existing_email_without_create(self):
        attempted_create = False

        def fake_open(request: Request, timeout: int):
            nonlocal attempted_create
            if request.full_url.endswith("/oauth/token"):
                return self.token_response()
            if request.full_url.startswith(DOMAIN + "/api/v2/users-by-email?"):
                return FakeResponse([response_for_user("auth0|someone-else", seed_key="other-seed")])
            attempted_create = True
            return FakeResponse({})

        with patch.object(seed, "urlopen", side_effect=fake_open):
            with self.assertRaisesRegex(seed.ProvisioningError, "not owned"):
                seed.provision_identities([ACCOUNT], SETTINGS, self.state, self.save, sleep=lambda _: None)
        self.assertFalse(attempted_create)

    def test_lost_password_fails_even_if_remote_user_is_owned(self):
        self.state.update({
            "version": seed.STATE_VERSION,
            "seed_key": SETTINGS["seed_key"],
            "auth0_domain": DOMAIN,
            "accounts": {ACCOUNT["key"]: {"email": "doctor.one@example.test", "sub": "auth0|doctor-1"}},
        })

        with patch.object(seed, "urlopen", return_value=self.token_response()) as mocked:
            with self.assertRaisesRegex(seed.ProvisioningError, "Saved password is missing"):
                seed.provision_identities([ACCOUNT], SETTINGS, self.state, self.save, sleep=lambda _: None)
        self.assertEqual(mocked.call_count, 1)  # token only; no identity lookup or writes

    def test_owned_remote_user_without_seed_state_is_not_returned_with_new_password(self):
        def fake_open(request: Request, timeout: int):
            if request.full_url.endswith("/oauth/token"):
                return self.token_response()
            if request.full_url.startswith(DOMAIN + "/api/v2/users-by-email?"):
                return FakeResponse([response_for_user("auth0|doctor-1")])
            self.fail("an existing owned user without password state must never be recreated")

        with patch.object(seed, "urlopen", side_effect=fake_open):
            with self.assertRaisesRegex(seed.ProvisioningError, "saved seed state is missing"):
                seed.provision_identities([ACCOUNT], SETTINGS, self.state, self.save, sleep=lambda _: None)
        self.assertNotIn(ACCOUNT["key"], self.state["accounts"])

    def test_ambiguous_create_timeout_recovers_by_owned_email_lookup(self):
        create_attempts = 0

        def fake_open(request: Request, timeout: int):
            nonlocal create_attempts
            if request.full_url.endswith("/oauth/token"):
                return self.token_response()
            if request.full_url.startswith(DOMAIN + "/api/v2/users-by-email?"):
                if create_attempts == 0:
                    return FakeResponse([])
                return FakeResponse([response_for_user("auth0|created-before-timeout")])
            if request.full_url.endswith("/api/v2/users") and request.method == "POST":
                create_attempts += 1
                raise URLError("sensitive transport detail must be hidden")
            self.fail(f"unexpected request: {request.method} {request.full_url}")

        with patch.object(seed, "MAX_RETRIES", 1), patch.object(seed, "urlopen", side_effect=fake_open):
            identities = seed.provision_identities([ACCOUNT], SETTINGS, self.state, self.save, sleep=lambda _: None)

        self.assertEqual(create_attempts, 1)
        self.assertEqual(identities[ACCOUNT["key"]]["sub"], "auth0|created-before-timeout")
        self.assertTrue(self.state["accounts"][ACCOUNT["key"]]["password"])

    def test_rate_limited_create_honors_retry_after_and_checks_before_retry(self):
        from http.client import HTTPMessage

        create_attempts = 0
        lookup_count = 0
        waits = []

        def fake_open(request: Request, timeout: int):
            nonlocal create_attempts, lookup_count
            if request.full_url.endswith("/oauth/token"):
                return self.token_response()
            if request.full_url.startswith(DOMAIN + "/api/v2/users-by-email?"):
                lookup_count += 1
                return FakeResponse([])
            if request.full_url.endswith("/api/v2/users") and request.method == "POST":
                create_attempts += 1
                if create_attempts == 1:
                    headers = HTTPMessage()
                    headers.add_header("Retry-After", "3")
                    raise seed.HTTPError(request.full_url, 429, "Too Many Requests", headers, None)
                return FakeResponse(response_for_user("auth0|doctor-1"), status=201)
            self.fail(f"unexpected request: {request.method} {request.full_url}")

        with patch.object(seed, "urlopen", side_effect=fake_open):
            result = seed.provision_identities([ACCOUNT], SETTINGS, self.state, self.save, sleep=waits.append)

        self.assertEqual(result[ACCOUNT["key"]]["sub"], "auth0|doctor-1")
        self.assertEqual(create_attempts, 2)
        self.assertEqual(lookup_count, 2)  # lookup before each POST
        self.assertIn(3.0, waits)

    def test_http_error_does_not_leak_body_or_credentials(self):
        def fake_open(request: Request, timeout: int):
            if request.full_url.endswith("/oauth/token"):
                return self.token_response()
            if request.full_url.startswith(DOMAIN + "/api/v2/users-by-email?"):
                return FakeResponse([])
            from http.client import HTTPMessage
            headers = HTTPMessage()
            raise seed.HTTPError(request.full_url, 403, "Forbidden", headers, None)

        with patch.object(seed, "urlopen", side_effect=fake_open):
            with self.assertRaises(seed.ProvisioningError) as captured:
                seed.provision_identities([ACCOUNT], SETTINGS, self.state, self.save, sleep=lambda _: None)
        message = str(captured.exception)
        self.assertIn("HTTP 403", message)
        self.assertNotIn("test-secret", message)
        self.assertNotIn("opaque-token", message)
        self.assertNotIn("doctor.one@example.test", message)

    def test_refuses_seed_or_tenant_context_change(self):
        self.state.update({"seed_key": "other-seed", "auth0_domain": DOMAIN, "accounts": {}})
        with self.assertRaisesRegex(seed.ProvisioningError, "different seed or tenant"):
            seed.provision_identities([ACCOUNT], SETTINGS, self.state, self.save)


if __name__ == "__main__":
    unittest.main()
