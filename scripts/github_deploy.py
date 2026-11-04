"""Deploy an already-published backend digest to the existing EC2 demo via SSM.

Uses AWS CLI credentials from GitHub OIDC. Never starts EC2 or runs database seeds.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import time

REGION = "us-east-1"
ROOT = Path(__file__).resolve().parents[1]
IMAGE_PATTERN = re.compile(r"[0-9]{12}\.dkr\.ecr\.us-east-1\.amazonaws\.com/[a-z0-9][a-z0-9._/-]*@sha256:[0-9a-f]{64}")


class DeployError(RuntimeError):
    pass


class AwsCliError(DeployError):
    def __init__(self, operation: str, stderr: str):
        match = re.search(r"\(([A-Za-z0-9]+)\)", stderr)
        self.code = match.group(1) if match else "AwsCliError"
        super().__init__(f"AWS {operation}: {self.code}")


def aws_json(arguments: list[str]) -> dict:
    result = subprocess.run(
        ["aws", *arguments, "--region", REGION, "--output", "json", "--no-cli-pager"],
        capture_output=True, text=True, encoding="utf-8", timeout=60,
    )
    if result.returncode:
        raise AwsCliError(" ".join(arguments[:2]), result.stderr)
    return json.loads(result.stdout)


def validate_inputs(instance_id: str, image: str, commit: str) -> None:
    if not re.fullmatch(r"i-[0-9a-f]{8,17}", instance_id):
        raise DeployError("Invalid EC2 instance ID")
    if not IMAGE_PATTERN.fullmatch(image):
        raise DeployError("Expected a backend ECR image pinned by sha256 digest in us-east-1")
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise DeployError("Expected a full commit SHA")


def require_online_instance(instance_id: str) -> None:
    response = aws_json(["ec2", "describe-instances", "--instance-ids", instance_id])
    instances = [item for reservation in response.get("Reservations", [])
                 for item in reservation.get("Instances", [])]
    if len(instances) != 1 or instances[0].get("InstanceId") != instance_id:
        raise DeployError("Expected exactly the configured demo instance")
    state = instances[0].get("State", {}).get("Name")
    if state != "running":
        raise DeployError(
            f"EC2 is {state}; image published but NOT deployed. Start the demo manually and rerun this workflow on produccion."
        )
    response = aws_json(["ssm", "describe-instance-information", "--filters",
                         json.dumps([{"Key": "InstanceIds", "Values": [instance_id]}])])
    records = response.get("InstanceInformationList", [])
    if (len(records) != 1 or records[0].get("InstanceId") != instance_id
            or records[0].get("PingStatus") != "Online"):
        raise DeployError("EC2 is not Online in SSM; image published but NOT deployed")


def command_parameters(script: bytes, image: str, commit: str) -> dict:
    content = script.replace(b"\r\n", b"\n")
    payload = base64.b64encode(content).decode("ascii")
    checksum = hashlib.sha256(content).hexdigest()
    wrapper = "\n".join([
        "set -eu", "umask 077",
        "bundle=$(mktemp -d /run/pretriage-ci.XXXXXX)",
        "trap 'rm -rf -- \"$bundle\"' EXIT",
        f"printf '%s' '{payload}' | base64 -d > \"$bundle/deploy.sh\"",
        f"printf '%s  %s\\n' '{checksum}' \"$bundle/deploy.sh\" | sha256sum -c - >/dev/null",
        f"bash \"$bundle/deploy.sh\" {shlex.quote(image)} {shlex.quote(commit)}",
    ])
    return {"commands": [wrapper], "executionTimeout": ["900"]}


def safe_command_output(value: str) -> str:
    value = re.sub(r"eyJ[A-Za-z0-9_.-]+", "<redacted-token>", value)
    value = re.sub(r"(?:AKIA|ASIA)[0-9A-Z]{16}", "<redacted-access-key>", value)
    return "\n".join(value.splitlines()[-35:])


def wait_for_command(command_id: str, instance_id: str, timeout: int = 960) -> dict:
    deadline = time.monotonic() + timeout
    last_status = None
    while time.monotonic() < deadline:
        try:
            result = aws_json(["ssm", "get-command-invocation", "--command-id", command_id,
                               "--instance-id", instance_id])
        except AwsCliError as error:
            if error.code != "InvocationDoesNotExist":
                raise
            time.sleep(5)
            continue
        status = result.get("Status")
        if status != last_status:
            print(f"SSM status: {status}", flush=True)
            last_status = status
        if status not in {"Pending", "InProgress", "Delayed", "Cancelling"}:
            for field in ("StandardOutputContent", "StandardErrorContent"):
                output = safe_command_output(result.get(field, ""))
                if output:
                    print(output, flush=True)
            if status != "Success" or result.get("ResponseCode") != 0:
                raise DeployError(f"Deployment did not succeed: SSM {status}, response {result.get('ResponseCode')}")
            return result
        time.sleep(5)
    raise DeployError(f"SSM command {command_id} is not confirmed complete; inspect it before retrying")


def deploy(instance_id: str, image: str, commit: str) -> str:
    validate_inputs(instance_id, image, commit)
    require_online_instance(instance_id)
    script = (ROOT / "scripts/deploy_backend_ec2.sh").read_bytes()
    with tempfile.TemporaryDirectory(prefix="pretriage-ssm-") as directory:
        path = Path(directory) / "parameters.json"
        path.write_text(json.dumps(command_parameters(script, image, commit)), encoding="utf-8")
        response = aws_json(["ssm", "send-command", "--document-name", "AWS-RunShellScript",
                             "--instance-ids", instance_id, "--timeout-seconds", "60",
                             "--comment", f"Pretriage produccion {commit}",
                             "--parameters", f"file://{path}"])
    command_id = response["Command"]["CommandId"]
    print(f"Deployment command: {command_id}", flush=True)
    wait_for_command(command_id, instance_id)
    print(f"Deployed commit {commit} using {image}", flush=True)
    return command_id


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance-id", required=True)
    parser.add_argument("--image", required=True)
    parser.add_argument("--commit", required=True)
    args = parser.parse_args()
    try:
        deploy(args.instance_id, args.image, args.commit)
    except (DeployError, OSError, ValueError, subprocess.TimeoutExpired) as error:
        print(f"Deployment failed: {error}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
