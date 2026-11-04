"""Run an explicit demo SQL seed against the existing on-demand EC2 database.

The module does not create AWS resources or start/stop the instance. boto3 is
loaded only when a target is used, and all operator-supplied AWS credentials
come from the standard boto3 credential chain or the named profile.
"""

from __future__ import annotations

import gzip
import hashlib
import re
import shlex
import time
import uuid
from typing import Any, Callable


TERMINAL_SSM_STATUSES = {
    "Success",
    "Cancelled",
    "Failed",
    "TimedOut",
    "Undeliverable",
    "Terminated",
    "DeliveryTimedOut",
    "ExecutionTimedOut",
}
DESTRUCTIVE_SQL = re.compile(r"\b(?:DELETE|TRUNCATE|DROP|ALTER)\b", re.IGNORECASE)


class DemoSeedAwsError(RuntimeError):
    """Safe operational error without SDK response bodies or remote output."""


def _service_error_code(exc: Exception) -> str | None:
    response = getattr(exc, "response", None)
    if isinstance(response, dict):
        error = response.get("Error")
        if isinstance(error, dict):
            code = error.get("Code")
            return str(code) if code is not None else None
    return None


def _check_sql_is_seed_only(sql: str) -> None:
    if not isinstance(sql, str) or not sql.strip():
        raise DemoSeedAwsError("SQL seed must be a non-empty string")
    # Do not let this executor become a path for destructive schema/data resets.
    # Comments and quoted string literals are removed before checking SQL words.
    scrubbed = re.sub(r"/\*.*?\*/|--[^\r\n]*", " ", sql, flags=re.DOTALL)
    scrubbed = re.sub(r"'(?:''|[^'])*'|\"(?:\"\"|[^\"])*\"", "''", scrubbed)
    if DESTRUCTIVE_SQL.search(scrubbed):
        raise DemoSeedAwsError("Demo seed SQL cannot contain DELETE, TRUNCATE, DROP, or ALTER")


def _required_table_names() -> tuple[str, ...]:
    """Use the CLI's complete schema contract plus tables referenced by SQL."""
    try:
        from .seed_demo import REQUIRED_TABLES
    except ImportError:
        # `python scripts/seed_demo.py` imports this module as a top-level module.
        from seed_demo import REQUIRED_TABLES
    return tuple(dict.fromkeys((*REQUIRED_TABLES, "coordenada")))


def _build_preflight_sql(table_names: tuple[str, ...] | None = None) -> str:
    names = table_names or _required_table_names()
    if not names or any(not re.fullmatch(r"[a-z][a-z0-9_]*", name) for name in names):
        raise DemoSeedAwsError("Invalid required-table preflight contract")
    rows = ", ".join(f"(\\$\\$public.{name}\\$\\$)" for name in names)
    return (
        f"SELECT CASE WHEN COUNT(*) = {len(names)} "
        "THEN \\$\\$DEMO_DB_TABLES_OK\\$\\$ ELSE \\$\\$DEMO_DB_TABLES_MISSING\\$\\$ END "
        f"FROM (VALUES {rows}) AS required(table_name) "
        "WHERE to_regclass(table_name) IS NOT NULL"
    )


class AwsDatabaseTarget:
    """Preflight and execute SQL against the existing demo EC2 PostgreSQL host."""

    def __init__(
        self,
        profile: str | None = None,
        region: str = "us-east-1",
        stack_name: str = "pretriage-demo",
        *,
        session_factory: Callable[..., Any] | None = None,
        sleep: Callable[[float], None] = time.sleep,
        monotonic: Callable[[], float] = time.monotonic,
    ):
        self.profile = profile
        self.region = region
        self.stack_name = stack_name
        self._session_factory = session_factory
        self._sleep = sleep
        self._monotonic = monotonic
        self._session: Any | None = None
        self._clients: dict[str, Any] = {}

    def _get_session(self) -> Any:
        if self._session is None:
            factory = self._session_factory
            if factory is None:
                try:
                    import boto3  # optional until AWS target is actually used
                except ImportError:
                    raise DemoSeedAwsError("boto3 is required to use the AWS demo database target") from None
                factory = boto3.Session
            kwargs: dict[str, Any] = {"region_name": self.region}
            if self.profile:
                kwargs["profile_name"] = self.profile
            self._session = factory(**kwargs)
        return self._session

    def _client(self, service: str) -> Any:
        if service not in self._clients:
            try:
                from botocore.config import Config
                config = Config(
                    connect_timeout=5,
                    read_timeout=20,
                    retries={"total_max_attempts": 3, "mode": "standard"},
                )
            except ImportError:
                config = None
            kwargs: dict[str, Any] = {"region_name": self.region}
            if config is not None:
                kwargs["config"] = config
            self._clients[service] = self._get_session().client(service, **kwargs)
        return self._clients[service]

    def _stack_outputs(self) -> dict[str, str]:
        try:
            response = self._client("cloudformation").describe_stacks(StackName=self.stack_name)
        except Exception as exc:
            code = _service_error_code(exc)
            if code:
                raise DemoSeedAwsError(f"CloudFormation stack lookup failed ({code})") from None
            raise DemoSeedAwsError("CloudFormation stack lookup failed") from None
        stacks = response.get("Stacks", []) if isinstance(response, dict) else []
        if len(stacks) != 1:
            raise DemoSeedAwsError("Expected one existing CloudFormation stack")
        status = stacks[0].get("StackStatus")
        if status not in {"CREATE_COMPLETE", "UPDATE_COMPLETE", "UPDATE_ROLLBACK_COMPLETE"}:
            raise DemoSeedAwsError(f"CloudFormation stack is not ready ({status or 'unknown status'})")
        outputs = {
            item.get("OutputKey"): item.get("OutputValue")
            for item in stacks[0].get("Outputs", [])
            if isinstance(item, dict) and isinstance(item.get("OutputKey"), str)
        }
        required = ("InstanceId", "ClinicalFilesBucketName")
        if any(not outputs.get(key) for key in required):
            raise DemoSeedAwsError("CloudFormation stack is missing required demo outputs")
        return {key: str(outputs[key]) for key in required}

    def _instance_state(self, instance_id: str) -> str:
        try:
            response = self._client("ec2").describe_instances(InstanceIds=[instance_id])
        except Exception as exc:
            code = _service_error_code(exc)
            if code:
                raise DemoSeedAwsError(f"EC2 instance check failed ({code})") from None
            raise DemoSeedAwsError("EC2 instance check failed") from None
        for reservation in response.get("Reservations", []):
            for instance in reservation.get("Instances", []):
                if instance.get("InstanceId") == instance_id:
                    return str(instance.get("State", {}).get("Name", "unknown"))
        raise DemoSeedAwsError("CloudFormation instance output does not resolve to an EC2 instance")

    def _ssm_is_online(self, instance_id: str) -> bool:
        try:
            response = self._client("ssm").describe_instance_information(
                Filters=[{"Key": "InstanceIds", "Values": [instance_id]}]
            )
        except Exception as exc:
            code = _service_error_code(exc)
            if code:
                raise DemoSeedAwsError(f"SSM managed-instance check failed ({code})") from None
            raise DemoSeedAwsError("SSM managed-instance check failed") from None
        return any(
            item.get("InstanceId") == instance_id and item.get("PingStatus") == "Online"
            for item in response.get("InstanceInformationList", [])
        )

    def _send_script(self, instance_id: str, command: str, *, timeout_seconds: int) -> str:
        try:
            response = self._client("ssm").send_command(
                InstanceIds=[instance_id],
                DocumentName="AWS-RunShellScript",
                Parameters={"commands": [command], "executionTimeout": [str(timeout_seconds)]},
                TimeoutSeconds=timeout_seconds,
                Comment="Pretriage demo seed preflight or explicit SQL seed",
            )
            command_id = response["Command"]["CommandId"]
            if not isinstance(command_id, str) or not command_id:
                raise DemoSeedAwsError("SSM did not return a command identifier")
            return command_id
        except DemoSeedAwsError:
            raise
        except Exception as exc:
            code = _service_error_code(exc)
            if code:
                raise DemoSeedAwsError(f"SSM command submission failed ({code})") from None
            # The request may have reached SSM even if its response was lost.
            raise DemoSeedAwsError("SSM command submission state is unknown") from None

    def _wait_for_terminal(self, command_id: str, instance_id: str, *, timeout_seconds: int) -> dict[str, Any] | None:
        deadline = self._monotonic() + timeout_seconds
        while self._monotonic() < deadline:
            try:
                invocation = self._client("ssm").get_command_invocation(
                    CommandId=command_id,
                    InstanceId=instance_id,
                )
            except Exception as exc:
                if _service_error_code(exc) == "InvocationDoesNotExist":
                    self._sleep(2)
                    continue
                code = _service_error_code(exc)
                if code:
                    raise DemoSeedAwsError(f"SSM command status check failed ({code})") from None
                raise DemoSeedAwsError("SSM command status check failed") from None
            status = invocation.get("Status")
            if status in TERMINAL_SSM_STATUSES:
                return invocation
            self._sleep(2)
        return None

    def _run_script(self, instance_id: str, command: str, *, timeout_seconds: int) -> dict[str, Any]:
        command_id = self._send_script(instance_id, command, timeout_seconds=min(timeout_seconds, 3600))
        invocation = self._wait_for_terminal(command_id, instance_id, timeout_seconds=timeout_seconds)
        if invocation is None:
            raise DemoSeedAwsError("SSM command did not reach a terminal state before the deadline")
        if invocation.get("Status") != "Success" or invocation.get("ResponseCode") != 0:
            status = invocation.get("Status", "unknown status")
            raise DemoSeedAwsError(f"SSM command failed ({status})")
        return invocation

    def preflight(self) -> dict[str, Any]:
        """Read stack/instance state and confirm DB readiness without mutation."""
        outputs = self._stack_outputs()
        instance_id = outputs["InstanceId"]
        state = self._instance_state(instance_id)
        if state != "running":
            raise DemoSeedAwsError(f"Demo instance must already be running (current state: {state})")
        if not self._ssm_is_online(instance_id):
            raise DemoSeedAwsError("Demo instance is not online in Systems Manager")

        check_db = (
            "set -eu\n"
            "test -d /opt/pretriage\n"
            "test -s /opt/pretriage/.env.aws\n"
            "test -s /opt/pretriage/compose.aws.yaml\n"
            "result=$(docker compose --env-file /opt/pretriage/.env.aws "
            "-f /opt/pretriage/compose.aws.yaml exec -T postgres sh -c "
            f"'psql -X -q -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\" -v ON_ERROR_STOP=1 -Atqc \"{_build_preflight_sql()}\"')\n"
            "test \"$result\" = 'DEMO_DB_TABLES_OK'\n"
            "printf 'DEMO_DB_PREFLIGHT_OK|%s\\n' \"$result\""
        )
        invocation = self._run_script(instance_id, check_db, timeout_seconds=180)
        stdout = invocation.get("StandardOutputContent", "")
        if "DEMO_DB_PREFLIGHT_OK|DEMO_DB_TABLES_OK" not in stdout:
            raise DemoSeedAwsError("Database readiness or required table checks failed")
        tables = _required_table_names()
        return {
            "stack_name": self.stack_name,
            "region": self.region,
            "instance_id": instance_id,
            "instance_state": state,
            "ssm_status": "Online",
            "clinical_files_bucket": outputs["ClinicalFilesBucketName"],
            "database_ready": True,
            "tables_ready": {table: True for table in tables},
        }

    @staticmethod
    def _remote_seed_script(bucket: str, key: str, expected_sha256: str) -> str:
        q_bucket = shlex.quote(bucket)
        q_key = shlex.quote(key)
        q_sha256 = shlex.quote(expected_sha256)
        body = "\n".join([
            "tmp_dir=$(mktemp -d /tmp/pretriage-demo-seed.XXXXXX)",
            "trap 'rm -f -- \"$tmp_dir/seed.sql.gz\" \"$tmp_dir/seed.sql\"; rmdir -- \"$tmp_dir\"' EXIT",
            # Each run writes a fresh UUID key once. Reading the current object
            # avoids requiring s3:GetObjectVersion on the EC2 instance role;
            # the SHA-256 check proves it is the uploaded content.
            f"aws s3api get-object --bucket {q_bucket} --key {q_key} \"$tmp_dir/seed.sql.gz\" >/dev/null",
            f"printf '%s  %s\\n' {q_sha256} \"$tmp_dir/seed.sql.gz\" | sha256sum -c -",
            "gzip -dc -- \"$tmp_dir/seed.sql.gz\" > \"$tmp_dir/seed.sql\"",
            "docker compose --env-file /opt/pretriage/.env.aws -f /opt/pretriage/compose.aws.yaml exec -T postgres sh -c 'psql -X -q -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\" -v ON_ERROR_STOP=1 -f -' < \"$tmp_dir/seed.sql\"",
            "printf 'DEMO_SQL_SEED_OK\\n'",
        ])
        # SSM's shell can vary by document/platform; invoke Bash explicitly so
        # `pipefail` and the cleanup trap have defined behavior.
        return "bash -euo pipefail <<'PRETRIAGE_DEMO_SEED_SCRIPT'\n" + body + "\nPRETRIAGE_DEMO_SEED_SCRIPT"

    def _delete_exact_s3_version(self, bucket: str, key: str, version_id: str) -> None:
        try:
            self._client("s3").delete_object(Bucket=bucket, Key=key, VersionId=version_id)
        except Exception as exc:
            code = _service_error_code(exc)
            if code:
                raise DemoSeedAwsError(f"Could not remove exact seed artifact s3://{bucket}/{key} version {version_id} ({code})") from None
            raise DemoSeedAwsError(f"Could not remove exact seed artifact s3://{bucket}/{key} version {version_id}") from None

    def execute(self, sql: str) -> str:
        """Upload, run, then remove one exact compressed SQL artifact version."""
        _check_sql_is_seed_only(sql)
        context = self.preflight()
        bucket = context["clinical_files_bucket"]
        instance_id = context["instance_id"]
        key = f"_deployment/demo-seed/{uuid.uuid4()}.sql.gz"
        compressed = gzip.compress(sql.encode("utf-8"), mtime=0)
        compressed_sha256 = hashlib.sha256(compressed).hexdigest()
        try:
            uploaded = self._client("s3").put_object(
                Bucket=bucket,
                Key=key,
                Body=compressed,
                ContentType="application/gzip",
                ServerSideEncryption="AES256",
            )
        except Exception as exc:
            code = _service_error_code(exc)
            if code:
                raise DemoSeedAwsError(f"Could not upload the compressed seed artifact ({code})") from None
            raise DemoSeedAwsError("Could not upload the compressed seed artifact") from None
        version_id = uploaded.get("VersionId") if isinstance(uploaded, dict) else None
        if not isinstance(version_id, str) or not version_id:
            raise DemoSeedAwsError(f"S3 did not return a version ID for seed artifact {key}; artifact retained")

        artifact_ref = f"s3://{bucket}/{key} version {version_id}"
        try:
            command = self._remote_seed_script(bucket, key, compressed_sha256)
            command_id = self._send_script(instance_id, command, timeout_seconds=600)
        except DemoSeedAwsError as exc:
            raise DemoSeedAwsError(f"{exc}; artifact retained at {artifact_ref}") from None

        command_terminal = False
        try:
            invocation = self._wait_for_terminal(command_id, instance_id, timeout_seconds=600)
            if invocation is None:
                # Cancel and confirm the exact SSM command before removing its
                # source artifact. If command state remains uncertain, retain it.
                try:
                    self._client("ssm").cancel_command(CommandId=command_id)
                except Exception as exc:
                    code = _service_error_code(exc)
                    suffix = f" ({code})" if code else ""
                    raise DemoSeedAwsError(
                        f"Seed command timed out; cancel failed{suffix}"
                    ) from None
                invocation = self._wait_for_terminal(command_id, instance_id, timeout_seconds=60)
                if invocation is None:
                    raise DemoSeedAwsError("Seed command state remains unknown")
            command_terminal = True
            if invocation.get("Status") != "Success" or invocation.get("ResponseCode") != 0:
                status = invocation.get("Status", "unknown status")
                raise DemoSeedAwsError(
                    f"Seed command failed ({status}); check SSM and permissions for instance-role s3:GetObject, operator s3:PutObject, and operator s3:DeleteObjectVersion"
                )
            if "DEMO_SQL_SEED_OK" not in invocation.get("StandardOutputContent", ""):
                raise DemoSeedAwsError("Seed command completed without its success marker")
        except DemoSeedAwsError as exc:
            if command_terminal:
                self._delete_exact_s3_version(bucket, key, version_id)
                raise
            raise DemoSeedAwsError(f"{exc}; artifact retained at {artifact_ref}") from None

        self._delete_exact_s3_version(bucket, key, version_id)
        return f"SQL seed completed on {instance_id}; temporary artifact version removed."
