"""Offline tests for the EC2 backend deployment helper."""
from __future__ import annotations

import hashlib
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "deploy_backend_ec2.sh"
REGISTRY = "123456789012.dkr.ecr.us-east-1.amazonaws.com"
REPOSITORY = f"{REGISTRY}/pretriage/backend"
OLD_IMAGE = f"{REPOSITORY}:demo-previous"
NEW_IMAGE = f"{REPOSITORY}@sha256:" + ("a" * 64)
COMMIT_SHA = "b" * 40


@unittest.skipUnless(os.name == "posix" and shutil.which("bash"), "runtime tests require POSIX and Bash")
class BackendDeployScriptTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="pretriage-deploy-test-")
        self.base = Path(self.temp.name)
        self.root = self.base / "app"
        self.run_dir = self.base / "run"
        self.bin_dir = self.base / "bin"
        self.root.mkdir()
        self.run_dir.mkdir(mode=0o700)
        self.bin_dir.mkdir()
        (self.root / "compose.aws.yaml").write_text("services: {}\n", encoding="utf-8")
        self.original_env = (
            f"BACKEND_IMAGE={OLD_IMAGE}\n"
            "API_DOMAIN=api.pretriage.com.ar\n"
            "POSTGRES_PASSWORD=must-not-be-printed\n"
        )
        self.env_file = self.root / ".env.aws"
        self.env_file.write_text(self.original_env, encoding="utf-8")
        self.env_file.chmod(0o600)
        self.log = self.base / "commands.log"
        self.state = self.base / "docker-state"
        self._write_fake_commands()

    def tearDown(self) -> None:
        self.temp.cleanup()

    def _write_executable(self, name: str, content: str) -> None:
        path = self.bin_dir / name
        path.write_text(content, encoding="utf-8")
        path.chmod(0o700)

    def _write_fake_commands(self) -> None:
        self._write_executable("aws", """#!/bin/sh
if [ "$#" -eq 4 ] && [ "$1" = ecr ] && [ "$2" = get-login-password ] && [ "$3" = --region ] && [ "$4" = us-east-1 ]; then
  printf 'temporary-test-token\n'
  exit 0
fi
exit 90
""")
        self._write_executable("flock", """#!/bin/sh
exit "${FAKE_FLOCK_STATUS:-0}"
""")
        self._write_executable("docker", r"""#!/bin/sh
printf 'docker %s\n' "$*" >> "$FAKE_COMMAND_LOG"
state=$FAKE_DOCKER_STATE
case "$1" in
  login)
    [ "$2" = --username ] && [ "$3" = AWS ] && [ "$4" = --password-stdin ] || exit 90
    [ -n "$7" ] && exit 90
    [ -r /dev/stdin ] || exit 90
    cat >/dev/null
    exit 0
    ;;
  pull)
    [ "${FAKE_DOCKER_PULL_FAIL:-0}" = 1 ] && exit 1
    exit 0
    ;;
  image)
    [ "$2" = inspect ] || exit 90
    if [ "$3" = --format ]; then
      ref=$4
      image=$5
    else
      exit 90
    fi
    printf 'sha256:%s\n' "$(printf '%s' "$image" | sha256sum | cut -c1-64)"
    exit 0
    ;;
  inspect)
    [ "$2" = --format ] || exit 90
    format=$3
    [ -f "$state" ] || exit 1
    image=$(sed -n '1p' "$state")
    health=$(sed -n '2p' "$state")
    case "$format" in
      '{{.Config.Image}}') printf '%s\n' "$image" ;;
      '{{.Image}}') printf 'sha256:%s\n' "$(printf '%s' "$image" | sha256sum | cut -c1-64)" ;;
      '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}') printf '%s\n' "$health" ;;
      *) exit 90 ;;
    esac
    exit 0
    ;;
  compose)
    shift
    env_file=
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --env-file) env_file=$2; shift 2 ;;
        -f) shift 2 ;;
        *) break ;;
      esac
    done
    [ -n "$env_file" ] || exit 90
    action=$1
    shift
    case "$action" in
      up)
        [ "$*" = '-d --no-deps --force-recreate --wait --wait-timeout 180 backend' ] || exit 90
        image=$(sed -n 's/^BACKEND_IMAGE=//p' "$env_file")
        case ",${FAKE_DOCKER_UP_FAIL_IMAGES:-}," in *",$image,"*) exit 1 ;; esac
        health=healthy
        case ",${FAKE_DOCKER_UNHEALTHY_IMAGES:-}," in *",$image,"*) health=unhealthy ;; esac
        printf '%s\n%s\n' "$image" "$health" > "$state"
        exit 0
        ;;
      ps)
        [ "$*" = '-q backend' ] || exit 90
        [ -f "$state" ] || exit 0
        printf 'test-container-id\n'
        exit 0
        ;;
      exec)
        [ "$1" = -T ] && [ "$2" = backend ] && [ "$3" = curl ] || exit 90
        if [ -f "$state" ]; then health=$(sed -n '2p' "$state"); else health=unhealthy; fi
        if [ "$health" = healthy ]; then printf '{"status":"UP"}\n'; else printf '{"status":"DOWN"}\n'; fi
        exit 0
        ;;
      *) exit 90 ;;
    esac
    ;;
  *) exit 90 ;;
esac
""")
        self._write_executable("curl", """#!/bin/sh
printf 'curl %s\\n' "$*" >> "$FAKE_COMMAND_LOG"
case "$*" in
  *https://api.pretriage.com.ar/actuator/health/readiness*)
    case " $* " in *' Mozilla/5.0 '*) ;; *) exit 90 ;; esac
    [ "${FAKE_CURL_FAIL:-0}" = 1 ] && exit 1
    printf '{"status":"UP"}\\n'
    ;;
  *) exit 90 ;;
esac
""")

    def run_script(self, image: str = NEW_IMAGE, commit: str = COMMIT_SHA, **extra_env: str) -> subprocess.CompletedProcess[str]:
        env = os.environ.copy()
        env.update({
            "PATH": str(self.bin_dir) + os.pathsep + env.get("PATH", ""),
            "PRETRIAGE_DEPLOY_ROOT": str(self.root),
            "PRETRIAGE_DEPLOY_RUN_DIR": str(self.run_dir),
            "FAKE_COMMAND_LOG": str(self.log),
            "FAKE_DOCKER_STATE": str(self.state),
            "FAKE_DOCKER_PULL_FAIL": "0",
            "FAKE_DOCKER_UP_FAIL_IMAGES": "",
            "FAKE_DOCKER_UNHEALTHY_IMAGES": "",
            "FAKE_CURL_FAIL": "0",
        })
        env.update(extra_env)
        return subprocess.run(
            [shutil.which("bash") or "bash", str(SCRIPT), image, commit],
            cwd=ROOT,
            env=env,
            text=True,
            capture_output=True,
            timeout=40,
        )

    def assert_no_secrets(self, result: subprocess.CompletedProcess[str]) -> None:
        output = result.stdout + result.stderr
        self.assertNotIn("temporary-test-token", output)
        self.assertNotIn("must-not-be-printed", output)

    def test_success_updates_only_backend_image_and_checks_both_readiness_paths(self) -> None:
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        contents = self.env_file.read_text(encoding="utf-8")
        self.assertEqual(self.original_env.replace(OLD_IMAGE, NEW_IMAGE), contents)
        self.assertEqual(stat.S_IMODE(self.env_file.stat().st_mode), 0o600)
        self.assert_no_secrets(result)
        log = self.log.read_text(encoding="utf-8")
        self.assertIn(f"docker pull {NEW_IMAGE}", log)
        self.assertIn("--no-deps --force-recreate --wait --wait-timeout 180 backend", log)
        self.assertIn("exec -T backend curl", log)
        self.assertIn("Mozilla/5.0", log)
        self.assertTrue(self.state.exists())
        remaining = list(self.run_dir.iterdir())
        self.assertEqual([self.run_dir / "pretriage-backend-deploy.lock"], remaining)
        self.assertEqual(0o600, stat.S_IMODE(remaining[0].stat().st_mode))

    def test_pull_failure_does_not_change_environment(self) -> None:
        result = self.run_script(FAKE_DOCKER_PULL_FAIL="1")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("was not changed", result.stderr)
        self.assertEqual(self.original_env, self.env_file.read_text(encoding="utf-8"))
        self.assertFalse(self.state.exists())
        self.assert_no_secrets(result)

    def test_readiness_failure_restores_and_verifies_previous_backend(self) -> None:
        result = self.run_script(FAKE_DOCKER_UNHEALTHY_IMAGES=NEW_IMAGE)
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertIn("previous backend was restored and verified healthy", result.stderr)
        self.assertEqual(self.original_env, self.env_file.read_text(encoding="utf-8"))
        self.assertEqual(OLD_IMAGE, self.state.read_text(encoding="utf-8").splitlines()[0])
        self.assert_no_secrets(result)

    def test_rollback_health_failure_returns_distinct_nonzero_status(self) -> None:
        unhealthy = f"{NEW_IMAGE},{OLD_IMAGE}"
        result = self.run_script(FAKE_DOCKER_UNHEALTHY_IMAGES=unhealthy)
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("rollback health verification failed", result.stderr)
        self.assertEqual(self.original_env, self.env_file.read_text(encoding="utf-8"))
        self.assert_no_secrets(result)

    def test_invalid_arguments_and_target_preflight_do_not_modify_environment(self) -> None:
        cases = (
            ("not-an-ecr-uri", COMMIT_SHA, "IMAGE_URI"),
            (NEW_IMAGE, "bad-sha", "COMMIT_SHA"),
            (f"{REGISTRY}/other/backend@sha256:" + ("c" * 64), COMMIT_SHA, "repository"),
        )
        for image, commit, expected in cases:
            with self.subTest(expected=expected):
                result = self.run_script(image, commit)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(expected, result.stderr)
                self.assertEqual(self.original_env, self.env_file.read_text(encoding="utf-8"))

        self.env_file.unlink()
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('.env.aws file is missing', result.stderr)
        self.assert_no_secrets(result)

    def test_missing_or_duplicate_backend_image_is_rejected(self) -> None:
        self.env_file.write_text("API_DOMAIN=api.pretriage.com.ar\n", encoding="utf-8")
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("exactly one BACKEND_IMAGE", result.stderr)

        self.env_file.write_text(self.original_env + f"BACKEND_IMAGE={OLD_IMAGE}\n", encoding="utf-8")
        result = self.run_script()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("exactly one BACKEND_IMAGE", result.stderr)


if __name__ == "__main__":
    unittest.main()
