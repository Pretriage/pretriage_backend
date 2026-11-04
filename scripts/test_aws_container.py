"""Smoke-test the AWS image with a disposable PostgreSQL, never the app DB.

Run: python scripts/test_aws_container.py --build
No AWS credentials or provider keys are used; all resources are local Docker.
"""
import argparse
import json
import secrets
import socket
import subprocess
import time
import urllib.error
import urllib.request


def docker(*args, check=True):
    return subprocess.run(["docker", *args], check=check, capture_output=True, text=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", default="pretriage-backend:aws-demo")
    parser.add_argument("--build", action="store_true")
    args = parser.parse_args()
    if args.build:
        subprocess.run(["docker", "build", "--platform", "linux/amd64", "-t", args.image, "."], check=True)

    suffix = secrets.token_hex(5)
    network, database, backend = [f"pretriage-aws-smoke-{suffix}-{kind}" for kind in ("net", "pg", "api")]
    password = secrets.token_urlsafe(24)
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    origin = "https://app.pretriage.com.ar"
    base_url = f"http://127.0.0.1:{port}"

    def request(path, headers=None, method="GET"):
        try:
            response = urllib.request.urlopen(urllib.request.Request(base_url + path, headers=headers or {}, method=method), timeout=5)
        except urllib.error.HTTPError as exc:
            response = exc
        with response:
            return response.status, dict(response.headers), response.read()

    def wait_ready():
        deadline = time.monotonic() + 150
        while time.monotonic() < deadline:
            try:
                status, _, body = request("/actuator/health/readiness")
                if status == 200 and json.loads(body).get("status") == "UP":
                    return
            except (OSError, ValueError):
                pass
            time.sleep(2)
        raise RuntimeError("The AWS container did not become ready")

    try:
        docker("network", "create", network)
        docker("run", "-d", "--name", database, "--network", network,
               "--tmpfs", "/var/lib/postgresql", "--memory", "768m",
               "-e", "POSTGRES_DB=pretriage_smoke", "-e", "POSTGRES_USER=pretriage",
               "-e", f"POSTGRES_PASSWORD={password}", "postgres:18")
        for _ in range(45):
            if docker("exec", database, "pg_isready", "-U", "pretriage", "-d", "pretriage_smoke", check=False).returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError("Disposable PostgreSQL did not start")
        env = {
            "SPRING_DATASOURCE_URL": f"jdbc:postgresql://{database}:5432/pretriage_smoke",
            "SPRING_DATASOURCE_USERNAME": "pretriage",
            "SPRING_DATASOURCE_PASSWORD": password,
            "AUTH0_ISSUER_URI": "https://example.invalid/",
            "AUTH0_BASE_PATH": "https://example.invalid/",
            "PRETRIAGE_VOZ_ORIGENES": origin,
            "PRETRIAGE_CORS_ALLOWED_ORIGINS": origin,
            "AWS_S3_REGION": "us-east-1",
            "AWS_S3_BUCKET_NAME": "pretriage-smoke-unused",
        }
        command = ["run", "-d", "--name", backend, "--network", network,
                   "--memory", "2g", "--read-only", "--tmpfs", "/tmp:size=134217728,mode=1777",
                   "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true",
                   "-p", f"127.0.0.1:{port}:8080"]
        for key, value in env.items():
            command += ["-e", f"{key}={value}"]
        docker(*command, args.image)
        wait_ready()
        assert request("/actuator/health")[0] == 200, "Health must be public"
        assert request("/actuator/env")[0] == 401, "Other actuator endpoints must be protected"
        assert request("/api/chat/1/voz")[0] == 401, "Voice handshake must require authentication"
        status, headers, _ = request("/api/login", {"Origin": origin, "Access-Control-Request-Method": "POST"}, "OPTIONS")
        assert status == 200 and headers.get("Access-Control-Allow-Origin") == origin, "Allowed CORS preflight failed"
        assert request("/api/login", {"Origin": "https://untrusted.invalid", "Access-Control-Request-Method": "POST"}, "OPTIONS")[0] == 403, "Unknown CORS origin must be denied"
        docker("exec", database, "psql", "-U", "pretriage", "-d", "pretriage_smoke", "-v", "ON_ERROR_STOP=1", "-c",
               "CREATE TABLE aws_smoke_persistence (id integer PRIMARY KEY); INSERT INTO aws_smoke_persistence VALUES (1);")
        docker("restart", "--timeout", "45", backend)
        wait_ready()
        marker = docker("exec", database, "psql", "-U", "pretriage", "-d", "pretriage_smoke", "-Atc", "SELECT count(*) FROM aws_smoke_persistence").stdout.strip()
        assert marker == "1", "Data did not survive backend restart"
        print("AWS container smoke passed: isolated PostgreSQL, readiness, endpoint protection, CORS, restart persistence.")
    except Exception:
        # These containers contain only generated smoke values, never real secrets.
        logs = docker("logs", "--tail", "80", backend, check=False)
        print(logs.stdout, logs.stderr)
        raise
    finally:
        docker("rm", "-f", backend, database, check=False)
        docker("network", "rm", network, check=False)


if __name__ == "__main__":
    main()
