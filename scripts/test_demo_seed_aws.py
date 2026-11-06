"""Mock-only tests for the opt-in AWS demo database executor."""

from __future__ import annotations

import gzip
import unittest
from unittest.mock import patch

from scripts.demo_seed_aws import AwsDatabaseTarget, DemoSeedAwsError, _build_preflight_sql, _required_table_names
from scripts.seed_demo import REQUIRED_TABLES


STACK = {
    "Stacks": [{
        "StackStatus": "CREATE_COMPLETE",
        "Outputs": [
            {"OutputKey": "InstanceId", "OutputValue": "i-demo123"},
            {"OutputKey": "ClinicalFilesBucketName", "OutputValue": "demo-clinical-bucket"},
        ],
    }]
}


class FakeCloudFormation:
    def __init__(self, response=STACK):
        self.response = response
        self.calls = []

    def describe_stacks(self, **kwargs):
        self.calls.append(kwargs)
        return self.response


class FakeEc2:
    def __init__(self, state="running"):
        self.state = state
        self.calls = []

    def describe_instances(self, **kwargs):
        self.calls.append(kwargs)
        return {"Reservations": [{"Instances": [{"InstanceId": "i-demo123", "State": {"Name": self.state}}]}]}


class FakeSsm:
    def __init__(self, *, preflight_ok=True, send_error=None, command_status="Success", invocation_statuses=None):
        self.preflight_ok = preflight_ok
        self.send_error = send_error
        self.command_status = command_status
        self.invocation_statuses = list(invocation_statuses or [])
        self.send_calls = []
        self.invocation_calls = []
        self.cancel_calls = []
        self._commands = {}
        self._next_id = 0

    def describe_instance_information(self, **kwargs):
        return {"InstanceInformationList": [{"InstanceId": "i-demo123", "PingStatus": "Online"}]}

    def send_command(self, **kwargs):
        self.send_calls.append(kwargs)
        command = kwargs["Parameters"]["commands"][0]
        if self.send_error and "DEMO_DB_PREFLIGHT_OK" not in command:
            raise self.send_error
        self._next_id += 1
        command_id = f"command-{self._next_id}"
        self._commands[command_id] = command
        return {"Command": {"CommandId": command_id}}

    def get_command_invocation(self, **kwargs):
        self.invocation_calls.append(kwargs)
        command = self._commands[kwargs["CommandId"]]
        if "DEMO_DB_PREFLIGHT_OK" in command:
            if not self.preflight_ok:
                return {"Status": "Success", "ResponseCode": 0, "StandardOutputContent": "DEMO_DB_PREFLIGHT_OK|DEMO_DB_TABLES_MISSING\n"}
            return {"Status": "Success", "ResponseCode": 0, "StandardOutputContent": "DEMO_DB_PREFLIGHT_OK|DEMO_DB_TABLES_OK\n"}
        if self.invocation_statuses:
            status = self.invocation_statuses.pop(0)
            return {"Status": status, "ResponseCode": 0 if status == "Success" else -1,
                    "StandardOutputContent": "DEMO_SQL_SEED_OK\n" if status == "Success" else ""}
        return {"Status": self.command_status, "ResponseCode": 0 if self.command_status == "Success" else -1,
                "StandardOutputContent": "DEMO_SQL_SEED_OK\n" if self.command_status == "Success" else ""}

    def cancel_command(self, **kwargs):
        self.cancel_calls.append(kwargs)
        return {}


class FakeS3:
    def __init__(self, version_id="seed-version-1"):
        self.version_id = version_id
        self.put_calls = []
        self.delete_calls = []

    def put_object(self, **kwargs):
        self.put_calls.append(kwargs)
        return {"VersionId": self.version_id}

    def delete_object(self, **kwargs):
        self.delete_calls.append(kwargs)
        return {}


class FakeSession:
    def __init__(self, clients):
        self.clients = clients
        self.calls = []

    def client(self, service, **kwargs):
        self.calls.append((service, kwargs))
        return self.clients[service]


def make_target(*, instance_state="running", preflight_ok=True, command_status="Success", invocation_statuses=None, send_error=None):
    cf = FakeCloudFormation()
    ec2 = FakeEc2(instance_state)
    ssm = FakeSsm(preflight_ok=preflight_ok, command_status=command_status,
                  invocation_statuses=invocation_statuses, send_error=send_error)
    s3 = FakeS3()
    session = FakeSession({"cloudformation": cf, "ec2": ec2, "ssm": ssm, "s3": s3})
    target = AwsDatabaseTarget("demo-profile", session_factory=lambda **_: session, sleep=lambda _: None)
    return target, session, cf, ec2, ssm, s3


class DemoSeedAwsTests(unittest.TestCase):
    def test_preflight_checks_existing_stack_instance_ssm_paths_and_tables(self):
        target, session, cf, ec2, ssm, s3 = make_target()
        result = target.preflight()

        self.assertEqual(result["instance_id"], "i-demo123")
        self.assertEqual(result["clinical_files_bucket"], "demo-clinical-bucket")
        self.assertTrue(result["database_ready"])
        self.assertEqual(set(result["tables_ready"]), set(REQUIRED_TABLES) | {"coordenada"})
        self.assertEqual(cf.calls, [{"StackName": "pretriage-demo"}])
        self.assertEqual(ec2.calls[0]["InstanceIds"], ["i-demo123"])
        self.assertEqual(len(ssm.send_calls), 1)
        command = ssm.send_calls[0]["Parameters"]["commands"][0]
        self.assertIn("/opt/pretriage/.env.aws", command)
        self.assertIn("/opt/pretriage/compose.aws.yaml", command)
        self.assertIn("psql -X -q", command)
        self.assertNotIn("POSTGRES_PASSWORD", command)
        self.assertEqual(s3.put_calls, [])
        self.assertEqual(s3.delete_calls, [])
        self.assertEqual(dict((service, kw["region_name"]) for service, kw in session.calls), {"cloudformation": "us-east-1", "ec2": "us-east-1", "ssm": "us-east-1"})

    def test_preflight_sql_checks_full_seed_contract_without_boolean_text_assumptions(self):
        names = _required_table_names()
        sql = _build_preflight_sql()
        self.assertIn("obra_social", names)
        self.assertEqual(set(names), set(REQUIRED_TABLES) | {"coordenada"})
        self.assertIn(f"COUNT(*) = {len(names)}", sql)
        self.assertIn("to_regclass", sql)
        self.assertIn("DEMO_DB_TABLES_OK", sql)
        self.assertIn("DEMO_DB_TABLES_MISSING", sql)
        self.assertNotIn("::text", sql)
        for table in names:
            self.assertIn(f"public.{table}", sql)

    def test_refuses_nonrunning_instance_without_running_ssm(self):
        target, _, _, _, ssm, _ = make_target(instance_state="stopped")
        with self.assertRaisesRegex(DemoSeedAwsError, "must already be running"):
            target.preflight()
        self.assertEqual(ssm.send_calls, [])

    def test_preflight_table_failure_stops_before_upload(self):
        target, _, _, _, _, s3 = make_target(preflight_ok=False)
        with self.assertRaisesRegex(DemoSeedAwsError, "table checks failed"):
            target.execute("INSERT INTO hospital(nombre) VALUES ('demo');")
        self.assertEqual(s3.put_calls, [])

    def test_execute_uploads_gzip_sse_runs_exact_version_and_cleans_only_that_version(self):
        target, _, _, _, ssm, s3 = make_target()
        sql = "INSERT INTO hospital(nombre) VALUES ('Demo Hospital');"
        result = target.execute(sql)

        upload = s3.put_calls[0]
        self.assertEqual(upload["Bucket"], "demo-clinical-bucket")
        self.assertTrue(upload["Key"].startswith("_deployment/demo-seed/"))
        self.assertTrue(upload["Key"].endswith(".sql.gz"))
        self.assertEqual(upload["ServerSideEncryption"], "AES256")
        self.assertEqual(gzip.decompress(upload["Body"]).decode(), sql)
        remote = ssm.send_calls[1]["Parameters"]["commands"][0]
        self.assertIn("aws s3api get-object --bucket", remote)
        self.assertNotIn("--version-id", remote)
        self.assertIn("sha256sum -c -", remote)
        self.assertIn("docker compose --env-file /opt/pretriage/.env.aws", remote)
        self.assertIn("exec -T postgres", remote)
        self.assertIn("psql -X -q", remote)
        self.assertIn("trap 'rm -f --", remote)
        self.assertNotIn("POSTGRES_PASSWORD", remote)
        self.assertEqual(len(s3.delete_calls), 1)
        self.assertEqual(s3.delete_calls[0], {"Bucket": "demo-clinical-bucket", "Key": upload["Key"], "VersionId": "seed-version-1"})
        self.assertIn("completed", result)

    def test_destructive_sql_is_rejected_before_aws_clients_are_created(self):
        target = AwsDatabaseTarget("demo-profile", session_factory=lambda **_: self.fail("must not create AWS session"))
        for sql in ("DELETE FROM hospital;", "/* seed */ DROP TABLE hospital;", "INSERT INTO x(v) VALUES ('x'); TRUNCATE x;"):
            with self.subTest(sql=sql), self.assertRaises(DemoSeedAwsError):
                target.execute(sql)

    def test_complete_seed_with_shared_catalog_passes_execution_safeguards(self):
        # The previous standalone catalog used ON COMMIT DROP, which is not
        # permitted by the AWS seed executor. Validate the complete payload.
        from scripts.demo_seed_data import build_sql
        from scripts.test_demo_seed import fixture

        sql = build_sql(*fixture())
        target, _, _, _, _, s3 = make_target()
        target.execute(sql)
        self.assertEqual(sql, gzip.decompress(s3.put_calls[0]["Body"]).decode())

    def test_terminal_ssm_failure_cleans_only_exact_uploaded_version(self):
        target, _, _, _, _, s3 = make_target(command_status="Failed")
        with self.assertRaisesRegex(DemoSeedAwsError, "Seed command failed"):
            target.execute("INSERT INTO hospital(nombre) VALUES ('Demo');")
        self.assertEqual(len(s3.delete_calls), 1)
        self.assertEqual(s3.delete_calls[0]["VersionId"], "seed-version-1")

    def test_unknown_ssm_submission_retains_artifact_and_reports_exact_version(self):
        target, _, _, _, _, s3 = make_target(send_error=TimeoutError("private transport detail"))
        with self.assertRaisesRegex(DemoSeedAwsError, "artifact retained at s3://demo-clinical-bucket/_deployment/demo-seed/.+version seed-version-1") as exc:
            target.execute("INSERT INTO hospital(nombre) VALUES ('Demo');")
        self.assertNotIn("private transport detail", str(exc.exception))
        self.assertEqual(s3.delete_calls, [])

    def test_timeout_cancels_then_cleans_if_command_reaches_terminal_state(self):
        target, _, _, _, _, s3 = make_target()
        preflight_success = {"Status": "Success", "ResponseCode": 0, "StandardOutputContent": "DEMO_DB_PREFLIGHT_OK|DEMO_DB_TABLES_OK"}
        with patch.object(target, "_wait_for_terminal", side_effect=[preflight_success, None, {"Status": "Cancelled", "ResponseCode": -1}]):
            with self.assertRaisesRegex(DemoSeedAwsError, r"Seed command failed \(Cancelled\)"):
                target.execute("INSERT INTO hospital(nombre) VALUES ('Demo');")
        self.assertEqual(len(s3.delete_calls), 1)

    def test_timeout_with_unknown_terminal_state_retains_exact_version(self):
        target, _, _, _, _, s3 = make_target()
        preflight_success = {"Status": "Success", "ResponseCode": 0, "StandardOutputContent": "DEMO_DB_PREFLIGHT_OK|DEMO_DB_TABLES_OK"}
        with patch.object(target, "_wait_for_terminal", side_effect=[preflight_success, None, None]):
            with self.assertRaisesRegex(DemoSeedAwsError, "artifact retained at s3://demo-clinical-bucket/.+version seed-version-1"):
                target.execute("INSERT INTO hospital(nombre) VALUES ('Demo');")
        self.assertEqual(s3.delete_calls, [])


if __name__ == "__main__":
    unittest.main()
