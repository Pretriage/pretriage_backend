#!/usr/bin/env python3
import argparse
import base64
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

DEFAULT_MESSAGES = [
    "Tengo fiebre de 38 grados desde ayer. También me duele la garganta desde ayer. No tengo dificultad para respirar ni dolor de pecho.",
    "5 de 10.",
    "No tengo nada más que agregar.",
]

ACTIVE_CONSULTA_STATES = (
    "PENDIENTE",
    "HOSPITAL_SELECCIONADO",
    "PRETRIAGE_FINALIZADO",
    "PRETRIAGE_EN_PROCESO",
    "EN_COLA",
    "LLAMADO",
    "EN_ESPERA",
    "ATRASADO",
    "EN_ATENCION",
)

ACTIVE_ENTRADA_STATES = (
    "EN_COLA",
    "LLAMADO",
    "EN_ESPERA",
    "ATRASADO",
    "EN_ATENCION",
)

SEVERITY_BY_PRIORITY = {
    5: "RIESGO_VITAL_INMEDIATO",
    4: "MUY_URGENTE",
    3: "URGENTE",
    2: "NORMAL",
    1: "NO_URGENTE",
}
FAILURE_CONTEXT = None
MAX_TEXT_TURN_WAIT_SECONDS = 180


def load_dotenv(path: Path) -> dict:
    values = {}
    if not path.exists():
        return values
    for raw_line in path.read_text(encoding="utf-8-sig").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def env_value(dotenv: dict, key: str, default=None):
    return os.environ.get(key) or dotenv.get(key) or default


def load_messages_file(path: Path) -> list[str]:
    if not path.exists():
        raise RuntimeError(f"Messages file does not exist: {path}")
    messages = []
    for raw_line in path.read_text(encoding="utf-8-sig").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        messages.append(line)
    if not messages:
        raise RuntimeError(f"Messages file has no messages: {path}")
    return messages


def http_json(method, url, body=None, token=None, timeout=120, extra_headers=None):
    data = None
    headers = {"Accept": "application/json"}
    headers.update(extra_headers or {})
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"

    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = response.read().decode("utf-8")
            if not payload:
                return None
            return json.loads(payload)
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"HTTP {method} {url} failed: {exc.code}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"Cannot connect to {url}: {exc.reason}") from exc


def extract_access_token(login_response):
    token_value = login_response.get("token") if isinstance(login_response, dict) else None
    if not token_value:
        raise RuntimeError("Login response does not contain token")

    if isinstance(token_value, str) and token_value.count(".") == 2:
        return token_value

    if isinstance(token_value, str):
        try:
            nested = json.loads(token_value)
            if nested.get("access_token"):
                return nested["access_token"]
            if nested.get("id_token"):
                return nested["id_token"]
        except json.JSONDecodeError:
            pass

    raise RuntimeError("Could not extract access_token from /api/login response")


def decode_jwt_subject(token: str) -> str:
    try:
        payload = token.split(".")[1]
        payload += "=" * (-len(payload) % 4)
        decoded = base64.urlsafe_b64decode(payload.encode("utf-8"))
        claims = json.loads(decoded.decode("utf-8"))
        subject = claims.get("sub")
        if not subject:
            raise RuntimeError("JWT does not contain sub claim")
        return subject
    except Exception as exc:
        raise RuntimeError("Could not decode JWT subject") from exc


def sql_quote(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def mask_email(value: str) -> str:
    if not value or "@" not in value:
        return "***"
    name, domain = value.split("@", 1)
    visible = name[:2] if len(name) > 2 else name[:1]
    return f"{visible}***@{domain}"


def run_psql(sql: str, db_user: str, db_name: str, container: str, tuples_only=False):
    command = [
        "docker",
        "exec",
        "-i",
        container,
        "psql",
        "-U",
        db_user,
        "-d",
        db_name,
        "-v",
        "ON_ERROR_STOP=1",
    ]
    if tuples_only:
        command.extend(["-At"])
    command.extend(["--set", "client_encoding=UTF8"])
    completed = subprocess.run(command, input=sql, text=True, encoding="utf-8", errors="replace", capture_output=True)
    if completed.returncode != 0:
        raise RuntimeError(f"PostgreSQL command failed (exit code {completed.returncode}); inspect the local database logs")
    return completed.stdout.strip() if tuples_only else completed.stdout


def seed_database(args, subject: str, email: str):
    active_consultas = ", ".join(sql_quote(s) for s in ACTIVE_CONSULTA_STATES)
    active_entradas = ", ".join(sql_quote(s) for s in ACTIVE_ENTRADA_STATES)
    sql = f"""
BEGIN;

INSERT INTO usuario_auth (id, nombre, apellido, numero_documento, tipo_documento, correo_electronico)
VALUES ({sql_quote(subject)}, 'Paciente', 'E2E', '99999999', 'DNI', {sql_quote(email)})
ON CONFLICT (id) DO UPDATE SET correo_electronico = EXCLUDED.correo_electronico;

INSERT INTO paciente (auth_id)
SELECT {sql_quote(subject)}
WHERE NOT EXISTS (SELECT 1 FROM paciente WHERE auth_id = {sql_quote(subject)});

UPDATE entrada_cola ec
SET estado = 'CANCELADA'
FROM consulta_medica cm, paciente p
WHERE ec.id_consulta_medica = cm.id
  AND cm.id_paciente = p.id
  AND p.auth_id = {sql_quote(subject)}
  AND ec.estado IN ({active_entradas});

UPDATE consulta_medica cm
SET estado_consulta = 'CANCELADA'
FROM paciente p
WHERE cm.id_paciente = p.id
  AND p.auth_id = {sql_quote(subject)}
  AND cm.estado_consulta IN ({active_consultas});

UPDATE consulta_medica cm
SET id_chat = NULL
FROM chat c, paciente p
WHERE cm.id_chat = c.id AND c.paciente_id = p.id
  AND p.auth_id = {sql_quote(subject)};

DELETE FROM mensaje m
USING chat c, paciente p
WHERE m.chat_id = c.id
  AND c.paciente_id = p.id
  AND p.auth_id = {sql_quote(subject)};

DELETE FROM chat c
USING paciente p
WHERE c.paciente_id = p.id
  AND p.auth_id = {sql_quote(subject)};

INSERT INTO especialidad_medica (codigo, nombre)
VALUES ({sql_quote(args.specialty)}, {sql_quote(args.specialty_name)})
ON CONFLICT (codigo) DO UPDATE SET nombre = EXCLUDED.nombre;

INSERT INTO hospital (place_id, nombre)
SELECT {sql_quote(args.place_id)}, {sql_quote(args.hospital_name)}
WHERE NOT EXISTS (SELECT 1 FROM hospital WHERE place_id = {sql_quote(args.place_id)});

INSERT INTO hospital_especialidad_medica (id_hospital, id_especialidad_medica)
SELECT h.id, e.id
FROM hospital h, especialidad_medica e
WHERE h.place_id = {sql_quote(args.place_id)}
  AND e.codigo = {sql_quote(args.specialty)}
  AND NOT EXISTS (
      SELECT 1
      FROM hospital_especialidad_medica he
      WHERE he.id_hospital = h.id
        AND he.id_especialidad_medica = e.id
  );

INSERT INTO sector (nombre, activa, id_hospital, id_especialidad)
SELECT {sql_quote(args.sector_name)}, TRUE, h.id, e.id
FROM hospital h, especialidad_medica e
WHERE h.place_id = {sql_quote(args.place_id)}
  AND e.codigo = {sql_quote(args.specialty)}
  AND NOT EXISTS (
      SELECT 1 FROM sector s
      WHERE s.id_hospital = h.id AND s.id_especialidad = e.id
        AND s.nombre = {sql_quote(args.sector_name)}
  );

INSERT INTO sala (nombre, activa, id_hospital, id_especialidad_medica, id_sector)
SELECT {sql_quote(args.room_name)}, TRUE, h.id, e.id, s.id
FROM hospital h
JOIN especialidad_medica e ON e.codigo = {sql_quote(args.specialty)}
JOIN sector s ON s.id_hospital = h.id AND s.id_especialidad = e.id
WHERE h.place_id = {sql_quote(args.place_id)}
  AND s.nombre = {sql_quote(args.sector_name)}
  AND NOT EXISTS (
      SELECT 1 FROM sala sa
      WHERE sa.id_hospital = h.id AND sa.id_especialidad_medica = e.id
        AND sa.id_sector = s.id AND sa.nombre = {sql_quote(args.room_name)}
  );

COMMIT;
"""
    run_psql(sql, args.db_user, args.db_name, args.db_container)


def check_gemini(model: str, api_key: str, base_url: str, timeout=10):
    if not api_key:
        raise RuntimeError("GEMINI_API_KEY is required for the Gemini precheck")
    model = model.removeprefix("models/")
    if not model or any(char not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-" for char in model):
        raise RuntimeError("Invalid Gemini model name")
    response = http_json("GET", f"{base_url.rstrip('/')}/models/{model}", timeout=timeout,
                         extra_headers={"x-goog-api-key": api_key})
    if "generateContent" not in response.get("supportedGenerationMethods", []):
        raise RuntimeError("Selected Gemini model does not support generateContent")


def write_debug(path, payload):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def set_failure_stage(stage):
    if FAILURE_CONTEXT is not None:
        FAILURE_CONTEXT["stage"] = stage


def ensure_no_early_finalization(turn_index, min_turns, allow=False):
    if turn_index < min_turns and not allow:
        raise RuntimeError(f"Chat finalized early after {turn_index} turns; expected at least {min_turns}")


def last_bot_message(chat):
    for message in reversed(chat.get("mensajes") or []):
        if message.get("autor") == "BOT":
            return message.get("contenido")
    return None


def submit_text_turn(backend_url, chat_id, message, token, poll_interval=0.5,
                     timeout_seconds=MAX_TEXT_TURN_WAIT_SECONDS):
    """Submit once, then poll the durable ChatDTO until this turn reaches a terminal state."""
    timeout_seconds = min(timeout_seconds, MAX_TEXT_TURN_WAIT_SECONDS)
    request_id = str(uuid.uuid4())
    accepted = http_json(
        "POST", f"{backend_url}/api/chat/{chat_id}/mensajes",
        {"contenido": message, "solicitudId": request_id}, token=token, timeout=MAX_TEXT_TURN_WAIT_SECONDS,
    )
    if not isinstance(accepted, dict):
        raise RuntimeError("Text turn POST did not return a JSON object")
    # Older servers return ChatTurnResponse synchronously. Keep that path usable while
    # the E2E client migrates to the durable 202 ChatDTO contract.
    if "respuesta" in accepted:
        return {"legacyResponse": accepted, "requestId": request_id}
    if accepted.get("solicitudTurnoTextoId") != request_id:
        raise RuntimeError("Accepted ChatDTO does not match the submitted request ID")

    deadline = time.monotonic() + timeout_seconds
    current = accepted
    while True:
        state = current.get("estadoTurnoTexto")
        classification = current.get("estadoClasificacion")
        if state == "NO_DISPONIBLE" or classification in ("FALLIDA", "PARCIAL"):
            raise RuntimeError(f"Text turn failed: estadoTurnoTexto={state}, estadoClasificacion={classification}")
        if state == "COMPLETADA" and classification != "PENDIENTE":
            return {"chat": current, "requestId": request_id}
        if state not in ("PENDIENTE", "COMPLETADA"):
            raise RuntimeError(f"Unexpected text-turn state: {state}")
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise RuntimeError(f"Timed out waiting for text turn {request_id} after {timeout_seconds}s")
        if poll_interval > 0:
            time.sleep(min(poll_interval, remaining))
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise RuntimeError(f"Timed out waiting for text turn {request_id} after {timeout_seconds}s")
        current = http_json(
            "GET", f"{backend_url}/api/chat/{chat_id}", token=token,
            timeout=min(15, remaining),
        )
        if not isinstance(current, dict):
            raise RuntimeError("Chat polling did not return a JSON object")
        if current.get("solicitudTurnoTextoId") != request_id:
            raise RuntimeError("ChatDTO moved to a different text-turn request while polling")


def parse_result_json(raw):
    if not raw:
        raise RuntimeError("chat.resultado_triage_json is empty")
    try:
        value = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise RuntimeError("chat.resultado_triage_json is not valid JSON") from exc
    if not isinstance(value, dict):
        raise RuntimeError("chat.resultado_triage_json is not a JSON object")
    required = ("motivoConsulta", "sintomas", "inicio", "evolucion", "intensidadDolor",
                "signosAlarma", "antecedentesRelevantes", "medicamentos", "alergias",
                "posibilidadEmbarazo", "observaciones", "nivelPrioridad",
                "requiereAtencionInmediata", "recomendacionSeguridad")
    missing = [key for key in required if key not in value or (key != "intensidadDolor" and value[key] is None)]
    if missing:
        raise RuntimeError(f"triage JSON missing meaningful fields: {', '.join(missing)}")
    for field in ("motivoConsulta", "inicio", "evolucion", "posibilidadEmbarazo", "recomendacionSeguridad"):
        if not isinstance(value[field], str) or not value[field].strip():
            raise RuntimeError(f"triage JSON {field} must be non-empty text")
    if not isinstance(value["observaciones"], str):
        raise RuntimeError("triage JSON observaciones must be text")
    pain = value["intensidadDolor"]
    if pain is not None and (type(pain) is not int or not 0 <= pain <= 10):
        raise RuntimeError("triage JSON intensidadDolor must be null or an integer from 0 to 10")
    list_fields = ("sintomas", "signosAlarma", "antecedentesRelevantes", "medicamentos", "alergias")
    if any(not isinstance(value[field], list) or any(not isinstance(item, str) or not item.strip() for item in value[field]) for field in list_fields):
        raise RuntimeError("triage JSON list fields must be lists of strings")
    if not value["sintomas"] or all(item.strip().lower() == "no informado" for item in value["sintomas"]):
        raise RuntimeError("triage JSON must contain meaningful sintomas")
    if not isinstance(value["motivoConsulta"], str) or not value["motivoConsulta"].strip() or value["motivoConsulta"].strip().lower() == "no informado":
        raise RuntimeError("triage JSON must contain a meaningful motivoConsulta")
    if not isinstance(value["nivelPrioridad"], int) or isinstance(value["nivelPrioridad"], bool) or not 1 <= value["nivelPrioridad"] <= 5:
        raise RuntimeError("triage JSON nivelPrioridad must be an integer from 1 to 5")
    if not isinstance(value["requiereAtencionInmediata"], bool) or not isinstance(value["recomendacionSeguridad"], str) or not value["recomendacionSeguridad"].strip():
        raise RuntimeError("triage JSON has invalid final recommendation fields")
    return value


def validate_persisted_state(args, subject, chat_id, expected_symptoms, expected_alarms=(), expect_immediate_attention=False):
    # row_to_json keeps validation independent from psql's human-readable formatting.
    sql = f"""
SELECT row_to_json(x)::text
FROM (
  SELECT c.id AS chat_id, c.paciente_id AS chat_patient_id,
         c.resultado_triage_json,
         cm.id AS consulta_id, cm.id_paciente AS consulta_patient_id,
         cm.estado_consulta, cm.id_hospital, cm.id_especialidad_medica,
         cm.id_sector, cm.nivel_de_gravedad_bot,
         ec.estado AS entrada_estado, ec.prioridad,
         s.activa AS sector_activa, sa.activa AS sala_activa,
         g.id_hospital AS gestor_hospital_id, g.id_especialidad_medica AS gestor_especialidad_id,
         g.id_sector AS gestor_sector_id
  FROM chat c
  JOIN paciente p ON p.id = c.paciente_id
  JOIN consulta_medica cm ON cm.id_chat = c.id AND cm.id_paciente = p.id
  LEFT JOIN entrada_cola ec ON ec.id_consulta_medica = cm.id
  LEFT JOIN gestor_de_cola g ON g.id = ec.id_gestor_de_cola
  LEFT JOIN sector s ON s.id = cm.id_sector
  LEFT JOIN sala sa ON sa.id_hospital = cm.id_hospital
                   AND sa.id_especialidad_medica = cm.id_especialidad_medica
                   AND sa.id_sector = cm.id_sector
                   AND sa.activa = TRUE
  WHERE c.id = {int(chat_id)} AND p.auth_id = {sql_quote(subject)}
  ORDER BY cm.id DESC
  LIMIT 1
) x;
"""
    raw = run_psql(sql, args.db_user, args.db_name, args.db_container, tuples_only=True)
    if not raw:
        raise RuntimeError("No persisted consultation matched the chat and authenticated patient")
    try:
        row = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise RuntimeError("Database validation did not return JSON") from exc
    if FAILURE_CONTEXT is not None:
        FAILURE_CONTEXT["persisted"] = {key: value for key, value in row.items() if key != "resultado_triage_json"}
        FAILURE_CONTEXT["resultadoTriageRaw"] = row.get("resultado_triage_json")
    checks = {
        "chat patient matches consultation": row.get("chat_patient_id") == row.get("consulta_patient_id"),
        "consultation state is EN_COLA": row.get("estado_consulta") == "EN_COLA",
        "queue entry state is EN_COLA": row.get("entrada_estado") == "EN_COLA",
        "consultation has a sector": row.get("id_sector") is not None,
        "sector is active": row.get("sector_activa") is True,
        "active room exists": row.get("sala_activa") is True,
        "queue priority is 1..5": isinstance(row.get("prioridad"), int) and 1 <= row["prioridad"] <= 5,
        "queue manager matches consultation": (row.get("gestor_hospital_id") == row.get("id_hospital") and
            row.get("gestor_especialidad_id") == row.get("id_especialidad_medica") and
            row.get("gestor_sector_id") == row.get("id_sector")),
        "severity matches priority": row.get("nivel_de_gravedad_bot") == SEVERITY_BY_PRIORITY.get(row.get("prioridad")),
    }
    # Keep the JSON result separate so malformed JSON can produce a useful failure.
    result = parse_result_json(row.get("resultado_triage_json"))
    checks["triage priority matches queue"] = row.get("prioridad") == result["nivelPrioridad"]
    if expected_symptoms:
        terms = " ".join(str(item).lower() for item in result["sintomas"])
        checks["expected symptoms present"] = all(term.lower() in terms for term in expected_symptoms)
    if expected_alarms:
        alarm_terms = " ".join(str(item).lower() for item in result["signosAlarma"])
        checks["expected alarm signs present"] = all(term.lower() in alarm_terms for term in expected_alarms)
    if expect_immediate_attention:
        checks["immediate attention is true"] = result.get("requiereAtencionInmediata") is True
        checks["immediate attention has alarm signs"] = bool(result["signosAlarma"])
        checks["immediate attention has urgent priority"] = result["nivelPrioridad"] in (4, 5)
    origin = result.get("origenClasificacion")
    checks["classification origin is recognized"] = origin == "GEMINI"
    checks["classification origin is GEMINI"] = origin == "GEMINI"
    failed = [name for name, passed in checks.items() if not passed]
    if failed:
        raise RuntimeError("Persisted state validation failed: " + "; ".join(failed))
    return row, result


def main():
    global FAILURE_CONTEXT
    parser = argparse.ArgumentParser(description="Run a real E2E chat flow against the backend and Gemini.")
    parser.add_argument("--backend-url", default=os.environ.get("BACKEND_URL", "http://localhost:8080"))
    parser.add_argument("--env-file", default=".env")
    parser.add_argument("--db-user", default=os.environ.get("DB_USER", "myuser"))
    parser.add_argument("--db-name", default=os.environ.get("DB_NAME"), help="Isolated database name (required; pretriage_db requires --allow-destructive-seed)")
    parser.add_argument("--allow-destructive-seed", action="store_true", help="Allow cancelling/deleting the test user's data in the shared default database")
    parser.add_argument("--db-container", default=os.environ.get("DB_CONTAINER", "postgre"))
    parser.add_argument("--place-id", default=os.environ.get("E2E_HOSPITAL_PLACE_ID", "e2e-hospital-chat"))
    parser.add_argument("--hospital-name", default=os.environ.get("E2E_HOSPITAL_NAME", "Hospital E2E Chat"))
    parser.add_argument("--sector-name", default=os.environ.get("E2E_SECTOR_NAME", "Sector E2E Chat"))
    parser.add_argument("--room-name", default=os.environ.get("E2E_ROOM_NAME", "Sala E2E Chat"))
    parser.add_argument("--specialty", default=os.environ.get("E2E_ESPECIALIDAD", "CLINICA_MEDICA"))
    parser.add_argument("--specialty-name", default=os.environ.get("E2E_ESPECIALIDAD_NOMBRE", "Clínica médica"))
    parser.add_argument("--gemini-model", help="Override GEMINI_CHAT_MODEL for the availability precheck")
    parser.add_argument("--skip-gemini-check", action="store_true")
    parser.add_argument("--message", action="append", dest="messages", help="Message to send. Repeat to override defaults.")
    parser.add_argument("--messages-file", default=os.environ.get("E2E_MESSAGES_FILE"), help="Text file with one patient message per non-empty line.")
    parser.add_argument("--debug-log", default=os.environ.get("E2E_DEBUG_LOG", "target/e2e_chat_last_debug.json"))
    parser.add_argument("--expected-priority", type=int, default=os.environ.get("E2E_EXPECTED_PRIORITY"), help="Optional priority expectation for a clinically specified case")
    parser.add_argument("--expected-intensity", type=int, help="Expected reported pain intensity (0..10)")
    parser.add_argument("--expected-onset", help="Expected text within the reported symptom onset")
    parser.add_argument("--expected-symptom", action="append", default=None, help="Expected symptom term; repeat for multiple terms")
    parser.add_argument("--min-turns", type=int, default=None, help="Minimum patient turns before accepting finalization")
    parser.add_argument("--allow-early-finalization", action="store_true", help="Allow the scripted chat to finalize before all messages are sent")
    parser.add_argument("--expected-alarm", action="append", default=None, help="Expected alarm sign term; repeat for multiple terms")
    parser.add_argument("--expect-immediate-attention", action="store_true", help="Require immediate attention and GEMINI provenance")
    args = parser.parse_args()
    FAILURE_CONTEXT = {"path": args.debug_log, "stage": "argument parsing", "turnosScript": []}

    if not args.db_name:
        raise RuntimeError("An isolated database is required: pass --db-name (or DB_NAME) explicitly")
    if args.db_name == "pretriage_db" and not args.allow_destructive_seed:
        raise RuntimeError("Refusing destructive seed in default database pretriage_db; use an isolated --db-name or explicitly pass --allow-destructive-seed")
    if args.expected_priority is not None and not 1 <= args.expected_priority <= 5:
        raise RuntimeError("--expected-priority must be between 1 and 5")
    if args.expected_intensity is not None and not 0 <= args.expected_intensity <= 10:
        raise RuntimeError("--expected-intensity must be between 0 and 10")

    dotenv = load_dotenv(Path(args.env_file))
    set_failure_stage("credentials")
    email = env_value(dotenv, "AUTH0_TEST_USERNAME")
    password = env_value(dotenv, "AUTH0_TEST_PASSWORD")
    if not email or not password:
        raise RuntimeError("AUTH0_TEST_USERNAME and AUTH0_TEST_PASSWORD must exist in .env or environment")
    FAILURE_CONTEXT["usuario"] = mask_email(email)

    if args.messages:
        messages = args.messages
    elif args.messages_file:
        messages = load_messages_file(Path(args.messages_file))
    else:
        messages = DEFAULT_MESSAGES
    expected_symptoms = args.expected_symptom if args.expected_symptom is not None else ["fiebre"]
    expected_alarms = args.expected_alarm or []
    min_turns = args.min_turns if args.min_turns is not None else len(messages)
    if min_turns < 1:
        raise RuntimeError("--min-turns must be at least 1")
    backend_url = args.backend_url.rstrip("/")

    if not args.skip_gemini_check:
        set_failure_stage("gemini preflight")
        model = args.gemini_model or env_value(dotenv, "GEMINI_CHAT_MODEL", "gemini-3.8-flash")
        check_gemini(model, env_value(dotenv, "GEMINI_API_KEY"),
                     env_value(dotenv, "GEMINI_API_BASE_URL", "https://generativelanguage.googleapis.com/v1beta"))
        print(f"Gemini OK: {model}")

    set_failure_stage("login")
    login = http_json("POST", f"{backend_url}/api/login", {"email": email, "password": password})
    token = extract_access_token(login)
    subject = decode_jwt_subject(token)
    print("Login OK")

    set_failure_stage("database seed")
    seed_database(args, subject, email)
    print("Seed DB OK")

    set_failure_stage("hospital selection")
    http_json(
        "POST",
        f"{backend_url}/api/atencion/hospital",
        {"placeId": args.place_id, "codigoEspecialidad": args.specialty},
        token=token,
    )
    print("Hospital seleccionado OK")

    set_failure_stage("chat creation")
    chat = http_json("POST", f"{backend_url}/api/chat", token=token)
    chat_id = chat["id"]
    print(f"Chat creado OK: id={chat_id}")

    debug_turns = []
    debug_path = Path(args.debug_log)
    debug_base = {"chatId": None, "usuario": mask_email(email), "hospitalPlaceId": args.place_id,
                  "especialidad": args.specialty, "turnosScript": debug_turns}
    debug_base["chatId"] = chat_id
    FAILURE_CONTEXT.update(debug_base)
    FAILURE_CONTEXT["turnosScript"] = debug_turns
    final_turn = None
    try:
        set_failure_stage("chat turns")
        for index, message in enumerate(messages, start=1):
            completed_turn = submit_text_turn(backend_url, chat_id, message, token)
            legacy_turn = completed_turn.get("legacyResponse")
            chat_snapshot = completed_turn.get("chat")
            if legacy_turn is not None:
                bot_text = (legacy_turn.get("respuesta") or {}).get("contenido")
                classification = None
                final = legacy_turn.get("atencionEstimada") is not None
                attention_estimate = legacy_turn.get("atencionEstimada")
            else:
                bot_text = last_bot_message(chat_snapshot)
                classification = chat_snapshot.get("estadoClasificacion")
                final = bool(chat_snapshot.get("finalizado")) and classification == "COMPLETADA"
                attention_estimate = None
            turn_debug = {"turno": index, "paciente": message, "bot": bot_text,
                          "solicitudId": completed_turn["requestId"],
                          "estadoClasificacion": classification}
            if attention_estimate is not None:
                turn_debug["atencionEstimada"] = attention_estimate
            debug_turns.append(turn_debug)
            print(f"Turno {index} OK | bot={bot_text!r}")
            if final:
                final_turn = {"respuesta": {"contenido": bot_text}, "chat": chat_snapshot,
                              "legacyResponse": legacy_turn}
                print("Triage finalizado OK | estadoClasificacion=COMPLETADA")
                ensure_no_early_finalization(index, min_turns, args.allow_early_finalization)
                break
    except Exception:
        write_debug(debug_path, debug_base)
        raise

    current_chat = http_json("GET", f"{backend_url}/api/chat/{chat_id}", token=token)
    if current_chat.get("estadoTurnoTexto") == "NO_DISPONIBLE" or current_chat.get("estadoClasificacion") == "FALLIDA":
        write_debug(debug_path, debug_base)
        raise RuntimeError("Chat reached a failed terminal state")
    if final_turn is None and current_chat.get("finalizado") and current_chat.get("estadoClasificacion") == "COMPLETADA":
        final_bot_text = last_bot_message(current_chat)
        final_turn = {"respuesta": {"contenido": final_bot_text}, "chat": current_chat}
        print("Chat finalizado OK")
    elif final_turn is None:
        write_debug(debug_path, debug_base)
        raise RuntimeError("The scripted conversation ended but triage was not finalized")

    try:
        set_failure_stage("persisted state validation")
        persisted, triage = validate_persisted_state(
            args, subject, chat_id, expected_symptoms, expected_alarms, args.expect_immediate_attention
        )
        if args.expect_immediate_attention:
            snapshot = (final_turn or {}).get("chat") or current_chat
            final_message = last_bot_message(snapshot) or ((final_turn or {}).get("respuesta") or {}).get("contenido") or ""
            if triage["recomendacionSeguridad"].strip().casefold() not in final_message.casefold():
                raise RuntimeError("Alarm final message must include the safety recommendation")
        if args.expected_priority is not None and triage["nivelPrioridad"] != args.expected_priority:
            raise RuntimeError(f"Expected priority {args.expected_priority}, got {triage['nivelPrioridad']}")
        if args.expected_intensity is not None and triage["intensidadDolor"] != args.expected_intensity:
            raise RuntimeError(f"Expected pain intensity {args.expected_intensity}, got {triage['intensidadDolor']}")
        if args.expected_onset and args.expected_onset.casefold() not in triage["inicio"].casefold():
            raise RuntimeError("Reported onset does not match --expected-onset")
    except Exception:
        write_debug(debug_path, debug_base)
        raise
    debug_payload = dict(debug_base)
    debug_payload.update({"persisted": {key: value for key, value in persisted.items() if key != "resultado_triage_json"},
                          "resultadoTriage": triage})
    write_debug(debug_path, debug_payload)
    print(f"Debug log escrito en {debug_path}")

    print("Prioridad asignada:")
    print(json.dumps({"prioridad": triage["nivelPrioridad"], "origenClasificacion": triage.get("origenClasificacion")}, ensure_ascii=False))
    print("E2E chat OK")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        if FAILURE_CONTEXT is not None:
            failure = {key: value for key, value in FAILURE_CONTEXT.items() if key != "path"}
            failure["error"] = str(exc)
            try:
                write_debug(FAILURE_CONTEXT["path"], failure)
            except Exception:
                pass
        print(f"ERROR: {exc}", file=sys.stderr)
        sys.exit(1)







