"""Offline CLI regression checks and the isolated PostgreSQL integration fixture."""
from __future__ import annotations

from contextlib import redirect_stdout, redirect_stderr
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

if __package__:
    from . import seed_demo
    from .demo_seed_data import build_sql, expected_history_count
else:
    import seed_demo
    from demo_seed_data import build_sql, expected_history_count


def fixture():
    hospitals = [dict(item) for item in seed_demo.load_catalog()[:2]]
    for item in hospitals:
        item["place_id"] = "demo-fixture-" + item["key"]
        item["name"] = "DEMO Fixture " + item["name"]
    config = {"seed_key": "demo-fixture-v1", "days": 30, "end_date": "2026-10-30",
              "daily_per_hospital": 6, "random_seed": 42, "history_patients": 12}
    accounts = seed_demo.build_accounts(hospitals, config["seed_key"], "demo.example.test")
    identities = {a["key"]: {**a, "sub": "demo-fixture|" + a["key"]} for a in accounts}
    return config, hospitals, identities


class DemoSeedCliTest(unittest.TestCase):
    def test_default_preview_has_no_network_database_or_file_writes(self):
        with tempfile.TemporaryDirectory(dir=seed_demo.ROOT / "target") as temporary:
            state = Path(temporary) / "must-not-be-created"
            with patch.object(seed_demo, "urlopen", side_effect=AssertionError("network called")), \
                 patch.object(seed_demo.subprocess, "run", side_effect=AssertionError("subprocess called")), \
                 redirect_stdout(io.StringIO()) as output:
                self.assertEqual(0, seed_demo.main(["--dry-run", "--state-dir", str(state)]))
            self.assertFalse(state.exists())
            self.assertIn("29 cuentas", output.getvalue())
            self.assertIn("16 obras sociales", output.getvalue())

    def test_conflicting_actions_and_future_history_are_rejected(self):
        with redirect_stderr(io.StringIO()):
            for args in (["--apply", "--resolve-places"], ["--end-date", "2999-01-01"], ["--seed-key", "../escape"],
                         ["--apply", "--verify-logins", "--backend-url", "http://example.test"]):
                with self.subTest(args=args), self.assertRaises(SystemExit):
                    seed_demo.parse_args(args)

    def test_resolved_place_ids_are_reused_without_maps_request(self):
        hospitals = seed_demo.load_catalog()
        state = {"hospital_place_ids": {h["key"]: "place-" + h["key"] for h in hospitals}}
        with patch.object(seed_demo, "urlopen", side_effect=AssertionError("network called")):
            resolved = seed_demo.resolve_hospitals(hospitals, state, "", lambda _: None)
        self.assertEqual(8, len(resolved))

    def test_maps_error_never_discloses_api_key(self):
        from urllib.error import URLError
        with patch.object(seed_demo, "urlopen", side_effect=URLError("secret-key-token")):
            with self.assertRaises(seed_demo.SeedError) as caught:
                seed_demo.resolve_hospitals(seed_demo.load_catalog()[:1], {}, "secret-key-token", lambda _: None, refresh=True)
        self.assertNotIn("secret-key-token", str(caught.exception))

    def test_sql_is_deterministic_and_histogram_varies(self):
        config, hospitals, identities = fixture()
        sql = build_sql(config, hospitals, identities)
        self.assertEqual(sql, build_sql(config, hospitals, identities))
        self.assertGreater(expected_history_count(config, hospitals), 100)
        self.assertLess(expected_history_count(config, hospitals), 600)
        self.assertIn("pg_advisory_xact_lock", sql)
        self.assertNotIn("TRUNCATE", sql.upper())
        self.assertNotIn("DELETE FROM", sql.upper())

    def test_bad_identity_set_fails_before_execution(self):
        config, hospitals, identities = fixture()
        del identities["admin"]
        with self.assertRaises(ValueError):
            build_sql(config, hospitals, identities)

    def test_private_checkpoint_replaces_atomically(self):
        with tempfile.TemporaryDirectory(dir=seed_demo.ROOT / "target") as temporary:
            path = Path(temporary) / "state.json"
            seed_demo.save_state(path, {"accounts": {"demo": {"password": "never-console"}}})
            self.assertEqual("never-console", json.loads(path.read_text())["accounts"]["demo"]["password"])
            self.assertFalse(path.with_name("state.next.json").exists())

    def test_repeated_preview_reuses_saved_history_parameters(self):
        with tempfile.TemporaryDirectory(dir=seed_demo.ROOT / "target") as temporary:
            path = Path(temporary)
            (path / "state.json").write_text(json.dumps({"last_applied": {
                "days": 14, "end_date": "2026-10-29", "daily_per_hospital": 6,
                "history_patients": 12, "random_seed": 7}}), encoding="utf-8")
            with redirect_stdout(io.StringIO()) as output:
                self.assertEqual(0, seed_demo.main(["--dry-run", "--state-dir", str(path)]))
            self.assertIn("2026-10-16 a 2026-10-29", output.getvalue())
            self.assertIn("referencia 6", output.getvalue())

    def test_invalid_catalog_is_rejected_before_auth0_creation(self):
        from contextlib import nullcontext
        import importlib
        auth_module = importlib.import_module((seed_demo.__package__ + "." if seed_demo.__package__ else "") + "demo_seed_auth0")
        with tempfile.TemporaryDirectory(dir=seed_demo.ROOT / "target") as temporary:
            path = Path(temporary)
            hospitals = seed_demo.load_catalog()
            hospitals[0]["number"] = "not-an-integer"
            catalog = path / "catalog.json"
            catalog.write_text(json.dumps({"hospitals": hospitals}), encoding="utf-8")
            settings = {"AUTH0_BASE_PATH": "https://demo.auth0.com", "AUTH0_M2M_CLIENT_ID": "demo-client",
                        "AUTH0_M2M_CLIENT_SECRET": "dummy-not-real"}
            with patch.object(seed_demo, "locked_state", return_value=nullcontext()), \
                 patch.object(seed_demo, "load_settings", return_value=settings), \
                 patch.object(seed_demo, "DockerDatabaseTarget") as target, \
                 patch.object(auth_module, "provision_identities") as provision, \
                 redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                self.assertEqual(1, seed_demo.main(["--apply", "--catalog", str(catalog), "--state-dir", str(path)]))
                provision.assert_not_called()
                target.return_value.execute.assert_not_called()


def main():
    if sys.argv[1:] == ["--fixture-sql"]:
        if hasattr(sys.stdout, "reconfigure"):
            sys.stdout.reconfigure(encoding="utf-8")
        print(build_sql(*fixture()))
        return 0
    unittest.main()


if __name__ == "__main__":
    main()
