#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

readonly REGION="us-east-1"
readonly PUBLIC_HEALTH_URL="https://api.pretriage.com.ar/actuator/health/readiness"
readonly BROWSER_USER_AGENT="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

die() {
    printf 'Deploy failed: %s\n' "$1" >&2
    exit "${2:-1}"
}

if [[ $# -ne 2 ]]; then
    die 'usage: deploy_backend_ec2.sh IMAGE_URI COMMIT_SHA'
fi

IMAGE_URI=$1
COMMIT_SHA=$2
if [[ ! $COMMIT_SHA =~ ^[[:xdigit:]]{40}$ ]]; then
    die 'COMMIT_SHA must be a 40-character hexadecimal commit ID'
fi

image_pattern='^([0-9]{12}\.dkr\.ecr\.us-east-1\.amazonaws\.com)/((([a-z0-9]+([._-][a-z0-9]+)*)(/[a-z0-9]+([._-][a-z0-9]+)*)*))@sha256:([a-fA-F0-9]{64})$'
if [[ ! $IMAGE_URI =~ $image_pattern ]]; then
    die 'IMAGE_URI must be an us-east-1 ECR repository digest (IMAGE@sha256:<64 hex>)'
fi
readonly NEW_REGISTRY=${BASH_REMATCH[1]}
readonly NEW_REPOSITORY=${BASH_REMATCH[2]}

ROOT=${PRETRIAGE_DEPLOY_ROOT:-/opt/pretriage}
RUN_BASE=${PRETRIAGE_DEPLOY_RUN_DIR:-/run}
readonly ENV_FILE="$ROOT/.env.aws"
readonly COMPOSE_FILE="$ROOT/compose.aws.yaml"

if [[ ! -d $ROOT || ! -f $ENV_FILE || -L $ENV_FILE ]]; then
    die 'deployment root or regular .env.aws file is missing'
fi
if [[ ! -f $COMPOSE_FILE || -L $COMPOSE_FILE ]]; then
    die 'compose.aws.yaml is missing or is a symbolic link'
fi
if [[ ! -d $RUN_BASE ]]; then
    die 'temporary run directory is missing'
fi
for required_command in aws docker curl flock mktemp; do
    command -v "$required_command" >/dev/null 2>&1 || die "required command is unavailable: $required_command"
done

WORK_DIR=$(mktemp -d "${RUN_BASE%/}/pretriage-deploy.XXXXXX") || die 'could not create a private temporary directory under the run directory'
chmod 700 "$WORK_DIR"
readonly DOCKER_CONFIG_DIR="$WORK_DIR/docker-config"
readonly TOKEN_FILE="$WORK_DIR/ecr-token"
readonly ENV_BACKUP="$WORK_DIR/env.aws.backup"
mkdir -m 700 "$DOCKER_CONFIG_DIR"
export DOCKER_CONFIG="$DOCKER_CONFIG_DIR"

rollback_needed=0
rollback_started=0
temporary_env=

LOCK_FD=
readonly LOCK_FILE="$RUN_BASE/pretriage-backend-deploy.lock"
exec {LOCK_FD}>"$LOCK_FILE"
chmod 600 "$LOCK_FILE"

cleanup() {
    local status=$?
    trap - EXIT
    if (( rollback_needed )) && (( ! rollback_started )); then
        if ! rollback_backend 'unexpected script exit'; then
            status=2
        elif (( status == 0 )); then
            status=1
        fi
    fi
    if [[ -n ${temporary_env:-} ]]; then
        rm -f -- "$temporary_env"
    fi
    rm -rf -- "$WORK_DIR"
    exit "$status"
}

on_signal() {
    local signal=$1
    local status=$2
    trap - INT TERM
    if (( rollback_needed )) && ! rollback_backend "received $signal"; then
        status=2
    fi
    exit "$status"
}

trap cleanup EXIT
trap 'on_signal INT 130' INT
trap 'on_signal TERM 143' TERM
flock -n "$LOCK_FD" || die 'another backend deployment is already running'

old_line=
old_image=
old_image_id=
backend_image_count=0
while IFS= read -r line || [[ -n $line ]]; do
    if [[ $line == BACKEND_IMAGE=* ]]; then
        ((backend_image_count += 1))
        old_line=$line
    fi
done < "$ENV_FILE"
if (( backend_image_count != 1 )); then
    die '.env.aws must contain exactly one BACKEND_IMAGE entry'
fi
old_image=${old_line#BACKEND_IMAGE=}
# Compose accepts quoted dotenv values (the initial AWS setup uses single
# quotes). Unwrap one matching pair without evaluating shell expressions.
old_image=${old_image%$'\r'}
case "$old_image" in
    \"*\") old_image=${old_image:1:-1} ;;
    \'*\') old_image=${old_image:1:-1} ;;
esac
old_image_pattern='^([0-9]{12}\.dkr\.ecr\.us-east-1\.amazonaws\.com)/((([a-z0-9]+([._-][a-z0-9]+)*)(/[a-z0-9]+([._-][a-z0-9]+)*)*))(:[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}|@sha256:[a-fA-F0-9]{64})$'
if [[ ! $old_image =~ $old_image_pattern ]]; then
    die '.env.aws BACKEND_IMAGE must be an image URI in the existing us-east-1 ECR repository'
fi
old_registry=${BASH_REMATCH[1]}
old_repository=${BASH_REMATCH[2]}
if [[ $old_registry != "$NEW_REGISTRY" || $old_repository != "$NEW_REPOSITORY" ]]; then
    die 'IMAGE_URI must use the registry and repository already configured by BACKEND_IMAGE'
fi

compose_up_backend() {
    docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" \
        up -d --no-deps --force-recreate --wait --wait-timeout 180 backend >/dev/null 2>&1
}

container_id() {
    docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps -q backend 2>/dev/null
}

verify_backend_image() {
    local expected_image=$1
    local expected_id=$2
    local id actual_image actual_id health_status
    id=$(container_id) || return 1
    [[ -n $id ]] || return 1
    actual_image=$(docker inspect --format '{{.Config.Image}}' "$id" 2>/dev/null) || return 1
    actual_id=$(docker inspect --format '{{.Image}}' "$id" 2>/dev/null) || return 1
    health_status=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$id" 2>/dev/null) || return 1
    [[ $actual_image == "$expected_image" && $actual_id == "$expected_id" && $health_status == healthy ]]
}

readiness_body_is_up() {
    local body=$1
    printf '%s' "$body" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"'
}

verify_internal_readiness() {
    local body
    body=$(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" \
        exec -T backend curl --fail --silent --show-error \
        --connect-timeout 5 --max-time 10 \
        http://localhost:8080/actuator/health/readiness 2>/dev/null) || return 1
    readiness_body_is_up "$body"
}

verify_public_readiness() {
    local attempt body
    for attempt in 1 2 3 4 5 6; do
        if body=$(curl --fail --silent --show-error --connect-timeout 5 --max-time 15 \
            --user-agent "$BROWSER_USER_AGENT" "$PUBLIC_HEALTH_URL" 2>/dev/null) \
            && readiness_body_is_up "$body"; then
            return 0
        fi
        sleep 5
    done
    return 1
}

rollback_backend() {
    local reason=$1
    rollback_started=1
    rollback_needed=0

    temporary_env=$(mktemp "$ROOT/.env.aws.rollback.XXXXXX") || {
        printf 'Deploy failed: %s; rollback failed because the original environment could not be staged\n' "$reason" >&2
        return 1
    }
    if ! cp -- "$ENV_BACKUP" "$temporary_env" || ! chmod 600 "$temporary_env" \
        || ! mv -f -- "$temporary_env" "$ENV_FILE"; then
        rm -f -- "$temporary_env"
        temporary_env=
        printf 'Deploy failed: %s; rollback failed while restoring .env.aws\n' "$reason" >&2
        return 1
    fi
    temporary_env=

    if ! compose_up_backend; then
        printf 'Deploy failed: %s; previous configuration was restored, but backend recreation failed\n' "$reason" >&2
        return 1
    fi
    if [[ ! $old_image_id =~ ^sha256:[[:xdigit:]]{64}$ ]] \
        || ! verify_backend_image "$old_image" "$old_image_id" \
        || ! verify_internal_readiness \
        || ! verify_public_readiness; then
        printf 'Deploy failed: %s; previous configuration was restored, but rollback health verification failed\n' "$reason" >&2
        return 1
    fi
    printf 'Deploy failed: %s; previous backend was restored and verified healthy\n' "$reason" >&2
    return 0
}

fail_with_rollback() {
    local reason=$1
    if rollback_backend "$reason"; then
        exit 1
    fi
    exit 2
}

# A local copy of the configured image is required so a failed update can roll back
# without depending on a second registry pull.
old_image_id=$(docker image inspect --format '{{.Id}}' "$old_image" 2>/dev/null) \
    || die 'the currently configured backend image is not available locally for rollback'
if [[ ! $old_image_id =~ ^sha256:[[:xdigit:]]{64}$ ]]; then
    die 'the currently configured backend image is not available locally for rollback'
fi

if ! aws ecr get-login-password --region "$REGION" >"$TOKEN_FILE" 2>/dev/null; then
    die 'could not obtain a temporary ECR authorization token'
fi
chmod 600 "$TOKEN_FILE"
if ! docker login --username AWS --password-stdin "$NEW_REGISTRY" <"$TOKEN_FILE" >/dev/null 2>&1; then
    die 'Docker could not authenticate to the configured ECR registry'
fi
rm -f -- "$TOKEN_FILE"

if ! docker pull "$IMAGE_URI" >/dev/null 2>&1; then
    die 'could not pull the requested backend image; .env.aws was not changed'
fi
new_image_id=$(docker image inspect --format '{{.Id}}' "$IMAGE_URI" 2>/dev/null) \
    || die 'pulled image could not be inspected; .env.aws was not changed'
if [[ ! $new_image_id =~ ^sha256:[[:xdigit:]]{64}$ ]]; then
    die 'pulled image returned an invalid local image ID; .env.aws was not changed'
fi

cp -- "$ENV_FILE" "$ENV_BACKUP" || die 'could not prepare the private rollback copy'
chmod 600 "$ENV_BACKUP"
temporary_env=$(mktemp "$ROOT/.env.aws.next.XXXXXX") || die 'could not create an atomic environment-file update'
updated_count=0
while IFS= read -r line || [[ -n $line ]]; do
    if [[ $line == BACKEND_IMAGE=* ]]; then
        ((updated_count += 1))
        printf 'BACKEND_IMAGE=%s\n' "$IMAGE_URI" >>"$temporary_env"
    else
        printf '%s\n' "$line" >>"$temporary_env"
    fi
done < "$ENV_FILE"
if (( updated_count != 1 )); then
    die '.env.aws changed during deployment preflight; refusing to update it'
fi
chmod 600 "$temporary_env"
rollback_needed=1
if ! mv -f -- "$temporary_env" "$ENV_FILE"; then
    temporary_env=
    if ! rollback_backend 'atomic environment-file update failed'; then
        die 'environment-file update failed and rollback could not be verified' 2
    fi
    die 'atomic environment-file update failed; previous backend was restored'
fi
temporary_env=

if ! compose_up_backend; then
    fail_with_rollback 'backend recreation or Compose health wait failed'
fi
if ! verify_backend_image "$IMAGE_URI" "$new_image_id"; then
    fail_with_rollback 'running backend does not use the requested image digest or is unhealthy'
fi
if ! verify_internal_readiness; then
    fail_with_rollback 'backend readiness check inside the container failed'
fi
if ! verify_public_readiness; then
    fail_with_rollback 'public HTTPS readiness check failed'
fi

rollback_needed=0
printf 'Deploy succeeded: commit %s is running from the requested ECR image digest and both readiness checks passed.\n' "$COMMIT_SHA"
