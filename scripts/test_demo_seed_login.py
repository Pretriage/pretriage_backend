"""Mock-only tests for optional backend login smoke verification."""

from __future__ import annotations

import base64
import json
import unittest
from http.client import HTTPMessage
from urllib.error import HTTPError
from unittest.mock import patch

from scripts import demo_seed_login as login


def token_for(subject: str) -> str:
    header = base64.urlsafe_b64encode(b'{"alg":"RS256","typ":"JWT"}').decode().rstrip("=")
    payload = base64.urlsafe_b64encode(json.dumps({"sub": subject}).encode()).decode().rstrip("=")
    return f"{header}.{payload}.signature"


def dto(subject: str) -> dict:
    return {"token": token_for(subject), "refreshToken": "refresh-secret", "renovarTokenEn": 3600}


class FakeResponse:
    status = 200

    def __init__(self, value):
        self.body = json.dumps(value).encode()

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def read(self):
        return self.body


class FakeOpener:
    def __init__(self, responses):
        self.responses = list(responses)
        self.requests = []

    def open(self, request, timeout):
        self.requests.append((request, timeout))
        response = self.responses.pop(0)
        if isinstance(response, Exception):
            raise response
        return response


def inputs(subject="auth0|demo-1", *, email="demo@example.test", password="do-not-print-this"):
    identities = {"test-patient": {"sub": subject, "email": email, "kind": "patient"}}
    state = {"accounts": {"test-patient": {"sub": subject, "email": email, "password": password}}}
    return identities, state


class DemoSeedLoginTests(unittest.TestCase):
    def test_verifies_backend_login_dto_and_jwt_subject(self):
        identities, state = inputs()
        opener = FakeOpener([FakeResponse(dto("auth0|demo-1"))])

        with patch.object(login, "build_opener", return_value=opener):
            count = login.verify_logins(identities, state, "https://api.example.test", sleep=lambda _: None)

        self.assertEqual(count, 1)
        request, timeout = opener.requests[0]
        self.assertEqual(request.full_url, "https://api.example.test/api/login")
        self.assertEqual(request.method, "POST")
        self.assertEqual(timeout, 15)
        self.assertEqual(json.loads(request.data), {"email": "demo@example.test", "password": "do-not-print-this"})

    def test_rejects_subject_mismatch_without_exposing_token(self):
        identities, state = inputs()
        opener = FakeOpener([FakeResponse(dto("auth0|other-user"))])
        with patch.object(login, "build_opener", return_value=opener):
            with self.assertRaisesRegex(login.LoginSmokeError, "different subject") as captured:
                login.verify_logins(identities, state, "https://api.example.test")
        self.assertNotIn("signature", str(captured.exception))
        self.assertNotIn("refresh-secret", str(captured.exception))

    def test_rejects_non_https_remote_backend_before_network(self):
        identities, state = inputs()
        with patch.object(login, "build_opener", side_effect=AssertionError("network must not be created")):
            with self.assertRaisesRegex(login.LoginSmokeError, "requires HTTPS"):
                login.verify_logins(identities, state, "http://api.example.test")

    def test_allows_http_only_for_loopback_backend(self):
        identities, state = inputs()
        opener = FakeOpener([FakeResponse(dto("auth0|demo-1"))])
        with patch.object(login, "build_opener", return_value=opener):
            self.assertEqual(login.verify_logins(identities, state, "http://127.0.0.1:18080"), 1)
        self.assertEqual(opener.requests[0][0].full_url, "http://127.0.0.1:18080/api/login")

    def test_refuses_redirect_without_following_or_resending_credentials(self):
        identities, state = inputs()
        headers = HTTPMessage()
        headers.add_header("Location", "https://other.example.test/login")
        redirect = HTTPError("https://api.example.test/api/login", 302, "Found", headers, None)
        opener = FakeOpener([redirect])

        def fake_build_opener(handler):
            self.assertIsInstance(handler, login._NoRedirect)
            return opener

        with patch.object(login, "build_opener", side_effect=fake_build_opener):
            with self.assertRaisesRegex(login.LoginSmokeError, "HTTP 302"):
                login.verify_logins(identities, state, "https://api.example.test")
        self.assertEqual(len(opener.requests), 1)

    def test_retries_429_with_retry_after_header(self):
        identities, state = inputs()
        headers = HTTPMessage()
        headers.add_header("Retry-After", "2")
        rate_limited = HTTPError("https://api.example.test/api/login", 429, "Too Many Requests", headers, None)
        opener = FakeOpener([rate_limited, FakeResponse(dto("auth0|demo-1"))])
        waits = []
        with patch.object(login, "build_opener", return_value=opener):
            self.assertEqual(login.verify_logins(identities, state, "https://api.example.test", sleep=waits.append), 1)
        self.assertEqual(len(opener.requests), 2)
        self.assertEqual(waits, [2.0])

    def test_invalid_response_error_does_not_echo_backend_body_or_credentials(self):
        identities, state = inputs(password="private-password")
        opener = FakeOpener([FakeResponse({"error": "private-password refresh-secret"})])
        with patch.object(login, "build_opener", return_value=opener):
            with self.assertRaises(login.LoginSmokeError) as captured:
                login.verify_logins(identities, state, "https://api.example.test")
        self.assertNotIn("private-password", str(captured.exception))
        self.assertNotIn("refresh-secret", str(captured.exception))

    def test_missing_saved_password_fails_before_network(self):
        identities, state = inputs()
        del state["accounts"]["test-patient"]["password"]
        with patch.object(login, "build_opener", side_effect=AssertionError("network must not be created")):
            with self.assertRaisesRegex(login.LoginSmokeError, "incomplete"):
                login.verify_logins(identities, state, "https://api.example.test")

    def test_requires_complete_login_dto(self):
        identities, state = inputs()
        opener = FakeOpener([FakeResponse({"token": token_for("auth0|demo-1"), "refreshToken": "", "renovarTokenEn": 3600})])
        with patch.object(login, "build_opener", return_value=opener):
            with self.assertRaisesRegex(login.LoginSmokeError, "incomplete"):
                login.verify_logins(identities, state, "https://api.example.test")


if __name__ == "__main__":
    unittest.main()
