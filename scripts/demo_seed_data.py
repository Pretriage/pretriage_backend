"""Build a repeatable PostgreSQL seed for disposable PreTriage demo data.

This module deliberately returns SQL instead of connecting to a database. The
CLI that owns execution must pass Auth0 subjects and resolved hospital data to
``build_sql``; this module never creates external identities or opens a socket.
"""

from __future__ import annotations

import hashlib
import json
import random
from datetime import date, datetime, time, timedelta
from typing import Any

if __package__:
    from .demo_seed_catalogs import build_health_insurance_sql
else:
    from demo_seed_catalogs import build_health_insurance_sql


SPECIALTIES: tuple[tuple[str, str], ...] = (
    ("CLINICA_MEDICA", "Clinica medica"),
    ("PEDIATRIA", "Pediatria"),
    ("CARDIOLOGIA", "Cardiologia"),
    ("TRAUMATOLOGIA", "Traumatologia"),
    ("GINECOLOGIA", "Ginecologia"),
)

_PRIORITY_BY_SEVERITY = {
    "RIESGO_VITAL_INMEDIATO": 5,
    "MUY_URGENTE": 4,
    "URGENTE": 3,
    "NORMAL": 2,
    "NO_URGENTE": 1,
}

_ROLE_KINDS = {"platform_admin", "hospital_admin", "doctor", "receptionist", "patient"}
_DAY_LIMIT = 90


def _sql(value: Any) -> str:
    """Return a safely quoted PostgreSQL literal for the supported scalar types."""
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    if isinstance(value, (int, float)):
        if isinstance(value, float) and (value != value or value in (float("inf"), float("-inf"))):
            raise ValueError("NaN and infinite numeric values are not valid seed inputs")
        return repr(value)
    return "'" + str(value).replace("'", "''") + "'"


def _safe_key(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip() or len(value) > 128 or "\x00" in value:
        raise ValueError(f"{label} must be nonblank text of at most 128 characters")
    return value.strip()


def _stable_token(value: str, width: int = 10) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:width].upper()


def _triage_result_json(severity: str) -> str:
    """Build a complete, explicitly fictional TriageResultDTO-compatible payload."""
    result = {
        "motivoConsulta": "Caso ficticio de demostración",
        "sintomas": ["Dato sintético sin valor clínico"],
        "inicio": "Dato simulado",
        "evolucion": "Dato simulado",
        "intensidadDolor": None,
        "signosAlarma": [],
        "antecedentesRelevantes": [],
        "medicamentos": [],
        "alergias": [],
        "posibilidadEmbarazo": "Dato simulado",
        "observaciones": "Registro sintético; no representa a una persona ni un episodio real.",
        "nivelPrioridad": _PRIORITY_BY_SEVERITY[severity],
        "requiereAtencionInmediata": False,
        "recomendacionSeguridad": "Dato ficticio: no usar para decisiones clínicas.",
    }
    return json.dumps(result, ensure_ascii=False, separators=(",", ":"))


def _daily_volume(seed_key: str, random_seed: int, hospital_key: str, day: date, baseline: int) -> int:
    """Vary arrivals deterministically, with lower weekends and a safe daily cap."""
    token = _stable_token(f"{random_seed}|{seed_key}|{hospital_key}|{day.isoformat()}", 8)
    variation = int(token, 16) % 7 - 3
    weekend_adjustment = -2 if day.weekday() >= 5 else 0
    return max(2, min(12, baseline + variation + weekend_adjustment))


def expected_history_count(config: dict[str, Any], hospitals: list[dict[str, Any]]) -> int:
    """Return the exact deterministic number of synthetic queue entries."""
    seed_key = _safe_key(config.get("seed_key", "demo-caba-v1"), "config.seed_key")
    days = config.get("days", 30)
    baseline = config.get("daily_per_hospital", 12)
    random_seed = config.get("random_seed", 42)
    if not isinstance(days, int) or isinstance(days, bool) or not 1 <= days <= _DAY_LIMIT:
        raise ValueError(f"config.days must be an integer between 1 and {_DAY_LIMIT}")
    if not isinstance(baseline, int) or isinstance(baseline, bool) or not 2 <= baseline <= 12:
        raise ValueError("config.daily_per_hospital must be an integer between 2 and 12")
    if not isinstance(random_seed, int) or isinstance(random_seed, bool):
        raise ValueError("config.random_seed must be an integer")
    end_text = config.get("end_date")
    end_date = date.fromisoformat(end_text) if end_text else date.today() - timedelta(days=1)
    if end_date >= date.today():
        raise ValueError("config.end_date must be in the past so seeded sessions are historical")
    start = end_date - timedelta(days=days - 1)
    keys = [_safe_key(hospital.get("key"), "hospital.key") for hospital in hospitals]
    if not keys:
        raise ValueError("at least one hospital is required")
    if len(set(keys)) != len(keys):
        raise ValueError("hospital keys must be distinct")
    return sum(
        _daily_volume(seed_key, random_seed, key, start + timedelta(days=index), baseline)
        for key in keys
        for index in range(days)
    )


def _registry_ref(seed_key: str, entity_type: str, stable_key: str) -> str:
    return (
        "(SELECT entity_id FROM demo_seed_registry WHERE seed_key = "
        f"{_sql(seed_key)} AND entity_type = {_sql(entity_type)} "
        f"AND stable_key = {_sql(stable_key)})"
    )


def _registry_block(
    seed_key: str,
    entity_type: str,
    stable_key: str,
    table: str,
    match: str,
    insert: str,
    *,
    insert_prefix: str = "",
) -> str:
    """Ensure a row, register its generated id, and reject ambiguous/drifted keys."""
    seed = _sql(seed_key)
    etype = _sql(entity_type)
    stable = _sql(stable_key)
    return f"""DO $demo$
DECLARE
    v_id BIGINT;
    v_registered BIGINT;
    v_count BIGINT;
BEGIN
    SELECT entity_id INTO v_registered
      FROM demo_seed_registry
     WHERE seed_key = {seed} AND entity_type = {etype} AND stable_key = {stable};
    IF FOUND THEN
        SELECT COUNT(*), MIN(id) INTO v_count, v_id
          FROM {table} WHERE id = v_registered AND ({match});
        IF v_count <> 1 THEN
            RAISE EXCEPTION 'Demo seed registry drift for %.%', {etype}, {stable};
        END IF;
    ELSE
        SELECT COUNT(*), MIN(id) INTO v_count, v_id FROM {table} WHERE {match};
        IF v_count > 1 THEN
            RAISE EXCEPTION 'Ambiguous existing rows for demo seed %.%', {etype}, {stable};
        ELSIF v_count = 0 THEN
            {insert_prefix}{insert}
            RETURNING id INTO v_id;
        END IF;
        INSERT INTO demo_seed_registry(seed_key, entity_type, stable_key, entity_id)
        VALUES ({seed}, {etype}, {stable}, v_id);
    END IF;
END
$demo$;
"""


def _validate(config: dict[str, Any], hospitals: list[dict[str, Any]], identities: dict[str, dict[str, Any]]) -> tuple[dict[str, Any], list[dict[str, Any]], list[tuple[str, dict[str, Any]]]]:
    if not isinstance(config, dict) or not isinstance(hospitals, list) or not isinstance(identities, dict):
        raise TypeError("config, hospitals, and identities must be dict, list, and dict")

    seed_key = _safe_key(config.get("seed_key", "demo-caba-v1"), "config.seed_key")
    days = config.get("days", 30)
    daily = config.get("daily_per_hospital", 12)
    history_patients = config.get("history_patients", 80)
    random_seed = config.get("random_seed", 42)
    if not isinstance(days, int) or isinstance(days, bool) or not 1 <= days <= _DAY_LIMIT:
        raise ValueError(f"config.days must be an integer between 1 and {_DAY_LIMIT}")
    if not isinstance(daily, int) or isinstance(daily, bool) or not 2 <= daily <= 12:
        raise ValueError("config.daily_per_hospital must be an integer between 2 and 12")
    if not isinstance(history_patients, int) or isinstance(history_patients, bool) or history_patients < 1:
        raise ValueError("config.history_patients must be a positive integer")
    if not isinstance(random_seed, int) or isinstance(random_seed, bool):
        raise ValueError("config.random_seed must be an integer")

    end_text = config.get("end_date")
    end_date = date.fromisoformat(end_text) if end_text else date.today() - timedelta(days=1)
    if end_date >= date.today():
        raise ValueError("config.end_date must be in the past so seeded sessions are historical")
    config_out = {
        "seed_key": seed_key,
        "days": days,
        "end_date": end_date.isoformat(),
        "daily_per_hospital": daily,
        "random_seed": random_seed,
        "history_patients": history_patients,
    }

    seen_hospital_keys: set[str] = set()
    seen_place_ids: set[str] = set()
    seen_names: set[str] = set()
    hospitals_out: list[dict[str, Any]] = []
    required_hospital = {"key", "name", "place_id", "street", "number", "city", "province", "latitude", "longitude"}
    for index, hospital in enumerate(hospitals):
        if not isinstance(hospital, dict) or required_hospital - hospital.keys():
            raise ValueError(f"hospitals[{index}] is missing required normalized fields")
        item = dict(hospital)
        item["key"] = _safe_key(item["key"], f"hospitals[{index}].key")
        for field in ("name", "place_id", "street", "city", "province"):
            if not isinstance(item[field], str) or not item[field].strip():
                raise ValueError(f"hospitals[{index}].{field} must be nonblank text")
            item[field] = item[field].strip()
        if item["key"] in seen_hospital_keys or item["place_id"] in seen_place_ids or item["name"].casefold() in seen_names:
            raise ValueError("hospital keys, Place IDs, and names must be distinct in the seed input")
        seen_hospital_keys.add(item["key"])
        seen_place_ids.add(item["place_id"])
        seen_names.add(item["name"].casefold())
        if not isinstance(item["number"], int) or isinstance(item["number"], bool):
            raise ValueError(f"hospitals[{index}].number must be an integer")
        for field in ("latitude", "longitude"):
            if not isinstance(item[field], (int, float)) or isinstance(item[field], bool):
                raise ValueError(f"hospitals[{index}].{field} must be numeric")
        if not -90 <= item["latitude"] <= 90 or not -180 <= item["longitude"] <= 180:
            raise ValueError(f"hospitals[{index}] coordinates are out of range")
        if item["city"] != "CABA" or item["province"] != "Ciudad Autónoma de Buenos Aires":
            raise ValueError(f"hospitals[{index}] must be normalized to CABA, Ciudad Autónoma de Buenos Aires")
        hospitals_out.append(item)
    if not hospitals_out:
        raise ValueError("at least one hospital is required")

    seen_subs: set[str] = set()
    seen_emails: set[str] = set()
    identities_out: list[tuple[str, dict[str, Any]]] = []
    required_identity = {"sub", "email", "first_name", "last_name", "kind"}
    known_hospital_keys = seen_hospital_keys
    for account_key, identity in sorted(identities.items()):
        key = _safe_key(account_key, "identity account key")
        if not isinstance(identity, dict) or required_identity - identity.keys():
            raise ValueError(f"identity {key!r} is missing required fields")
        item = dict(identity)
        for field in ("sub", "email", "first_name", "last_name", "kind"):
            if not isinstance(item[field], str) or not item[field].strip():
                raise ValueError(f"identity {key!r}.{field} must be nonblank text")
            item[field] = item[field].strip()
        if item["kind"] not in _ROLE_KINDS:
            raise ValueError(f"identity {key!r} has unknown kind {item['kind']!r}")
        email_key = item["email"].casefold()
        if item["sub"] in seen_subs or email_key in seen_emails:
            raise ValueError("Auth0 subjects and emails must be unique in the identity input")
        seen_subs.add(item["sub"])
        seen_emails.add(email_key)
        hospital_key = item.get("hospital_key")
        if item["kind"] == "hospital_admin" and hospital_key is not None:
            raise ValueError(f"hospital_admin identity {key!r} must omit hospital_key so it is assigned to every demo hospital")
        if item["kind"] in {"doctor", "receptionist"}:
            if hospital_key not in known_hospital_keys:
                raise ValueError(f"identity {key!r} requires a known hospital_key")
        elif hospital_key is not None and hospital_key not in known_hospital_keys:
            raise ValueError(f"identity {key!r}.hospital_key is not in hospitals")
        if item["kind"] == "doctor" and item.get("primary_specialty") not in {code for code, _ in SPECIALTIES}:
            raise ValueError(f"doctor identity {key!r} requires a canonical primary_specialty")
        identities_out.append((key, item))

    counts = {kind: sum(1 for _, i in identities_out if i["kind"] == kind) for kind in _ROLE_KINDS}
    if counts["platform_admin"] != 1:
        raise ValueError("exactly one platform_admin identity is required")
    if counts["hospital_admin"] < 1:
        raise ValueError("at least one hospital_admin identity is required")
    by_hospital = {h["key"]: {kind: [] for kind in ("doctor", "receptionist")} for h in hospitals_out}
    for key, identity in identities_out:
        if identity["kind"] in {"doctor", "receptionist"}:
            by_hospital[identity["hospital_key"]][identity["kind"]].append((key, identity))
    if counts["hospital_admin"] != 1 or counts["patient"] != 3:
        raise ValueError("the demo identity set requires exactly one hospital_admin and three patient logins")
    for hkey, by_kind in by_hospital.items():
        if len(by_kind["doctor"]) != 2 or len(by_kind["receptionist"]) != 1:
            raise ValueError(f"hospital {hkey!r} requires exactly two doctors and one receptionist")
        primaries = [i["primary_specialty"] for _, i in by_kind["doctor"]]
        if "CLINICA_MEDICA" not in primaries or "TRAUMATOLOGIA" not in primaries:
            raise ValueError(f"hospital {hkey!r} requires doctors with CLINICA_MEDICA and TRAUMATOLOGIA primary specialties")
    if counts["patient"] < 1:
        raise ValueError("at least one patient login identity is required")
    return config_out, hospitals_out, identities_out


def validate_inputs(config: dict[str, Any], hospitals: list[dict[str, Any]], identities: dict[str, dict[str, Any]]) -> None:
    """Validate normalized inputs without rendering SQL or contacting services."""
    _validate(config, hospitals, identities)


def _ensure_hospital(seed_key: str, hospital: dict[str, Any]) -> str:
    """Insert/reuse a hospital without overwriting an existing address."""
    hkey = hospital["key"]
    p = {k: _sql(v) for k, v in hospital.items()}
    seed = _sql(seed_key)
    stable = _sql(hkey)
    return f"""DO $demo$
DECLARE
    v_count BIGINT;
    v_id BIGINT;
    v_registered BIGINT;
    v_place_id TEXT;
    v_address_id BIGINT;
    v_coordinate_id BIGINT;
BEGIN
    SELECT entity_id INTO v_registered FROM demo_seed_registry
     WHERE seed_key = {seed} AND entity_type = 'hospital' AND stable_key = {stable};
    IF FOUND THEN
        SELECT COUNT(*), MIN(id) INTO v_count, v_id FROM hospital
         WHERE id = v_registered AND place_id = {p['place_id']} AND nombre = {p['name']};
        IF v_count <> 1 THEN RAISE EXCEPTION 'Demo hospital registry drift for %', {stable}; END IF;
    ELSE
        SELECT COUNT(*), MIN(id) INTO v_count, v_id FROM hospital WHERE place_id = {p['place_id']};
        IF v_count > 1 THEN RAISE EXCEPTION 'Ambiguous hospital Place ID %', {p['place_id']}; END IF;
        IF EXISTS (SELECT 1 FROM hospital WHERE lower(nombre) = lower({p['name']}) AND COALESCE(place_id, '') <> {p['place_id']}) THEN
            RAISE EXCEPTION 'Hospital name % already exists with another Place ID', {p['name']};
        END IF;
        IF v_count = 0 THEN
            SELECT COUNT(*), MIN(id) INTO v_count, v_id FROM hospital WHERE lower(nombre) = lower({p['name']});
            IF v_count > 1 THEN RAISE EXCEPTION 'Ambiguous hospital name %', {p['name']}; END IF;
            IF v_count = 1 THEN
                SELECT place_id INTO v_place_id FROM hospital WHERE id = v_id;
                IF v_place_id IS NOT NULL AND btrim(v_place_id) <> '' AND v_place_id <> {p['place_id']} THEN
                    RAISE EXCEPTION 'Hospital name % is already associated with another Place ID', {p['name']};
                END IF;
                UPDATE hospital SET place_id = {p['place_id']} WHERE id = v_id AND (place_id IS NULL OR btrim(place_id) = '');
            ELSE
                INSERT INTO coordenada(latitud, longitud) VALUES ({p['latitude']}, {p['longitude']}) RETURNING id INTO v_coordinate_id;
                INSERT INTO direccion(calle, altura, ciudad, provincia, id_coordenada)
                VALUES ({p['street']}, {_sql(str(hospital['number']))}, {p['city']}, {p['province']}, v_coordinate_id)
                RETURNING id INTO v_address_id;
                INSERT INTO hospital(place_id, nombre, id_direccion)
                VALUES ({p['place_id']}, {p['name']}, v_address_id) RETURNING id INTO v_id;
            END IF;
        ELSE
            IF EXISTS (SELECT 1 FROM hospital WHERE id = v_id AND nombre <> {p['name']}) THEN
                RAISE EXCEPTION 'Place ID % is already attached to another hospital name', {p['place_id']};
            END IF;
        END IF;
        IF EXISTS (SELECT 1 FROM hospital WHERE id = v_id AND id_direccion IS NULL) THEN
            INSERT INTO coordenada(latitud, longitud) VALUES ({p['latitude']}, {p['longitude']}) RETURNING id INTO v_coordinate_id;
            INSERT INTO direccion(calle, altura, ciudad, provincia, id_coordenada)
            VALUES ({p['street']}, {_sql(str(hospital['number']))}, {p['city']}, {p['province']}, v_coordinate_id)
            RETURNING id INTO v_address_id;
            UPDATE hospital SET id_direccion = v_address_id WHERE id = v_id AND id_direccion IS NULL;
        END IF;
        INSERT INTO demo_seed_registry(seed_key, entity_type, stable_key, entity_id)
        VALUES ({seed}, 'hospital', {stable}, v_id);
    END IF;
END
$demo$;
"""


def _ensure_identity(identity: dict[str, Any], doc_number: int) -> str:
    sub = _sql(identity["sub"])
    email = _sql(identity["email"])
    first = _sql(identity["first_name"])
    last = _sql(identity["last_name"])
    # UsuarioAuth.rol has no @Enumerated annotation: JPA stores ordinal ADMIN=0, USER=1.
    role_ordinal = 0 if identity["kind"] == "platform_admin" else 1
    identity_block = f"""DO $demo$
DECLARE v_count BIGINT; v_email TEXT; v_auth_id TEXT; v_email_auth_id TEXT; v_email_count BIGINT;
BEGIN
    SELECT COUNT(*), MIN(id) INTO v_email_count, v_email_auth_id
      FROM usuario_auth WHERE lower(correo_electronico) = lower({email});
    IF v_email_count > 1 THEN RAISE EXCEPTION 'Ambiguous duplicate email in usuario_auth: %', {email}; END IF;
    IF v_email_count = 1 AND v_email_auth_id <> {sub} THEN
        RAISE EXCEPTION 'Email is already linked to a different Auth0 subject: %', {email};
    END IF;
    SELECT COUNT(*), MIN(id) INTO v_count, v_auth_id FROM usuario_auth WHERE id = {sub};
    IF v_count > 1 THEN RAISE EXCEPTION 'Duplicate Auth0 subject in usuario_auth: %', {sub}; END IF;
    IF v_count = 1 THEN
        SELECT correo_electronico INTO v_email FROM usuario_auth WHERE id = v_auth_id;
        IF lower(coalesce(v_email, '')) <> lower({email}) THEN
            RAISE EXCEPTION 'Existing Auth0 subject has a different email: %', {sub};
        END IF;
        UPDATE usuario_auth SET rol = {role_ordinal} WHERE id = v_auth_id;
    ELSE
        INSERT INTO usuario_auth(id, nombre, apellido, numero_documento, tipo_documento,
                                 correo_electronico, rol)
        VALUES ({sub}, {first}, {last}, {_sql(str(doc_number))}, 'DNI', {email}, {role_ordinal});
    END IF;
END
$demo$;
"""
    # Keep the Auth0 subject opaque in generated SQL references; never derive it from an email.
    profile_block = ""
    if identity["kind"] == "patient":
        profile_block = f"""DO $demo$
DECLARE v_count BIGINT; v_id BIGINT;
BEGIN
    SELECT COUNT(*), MIN(id) INTO v_count, v_id FROM paciente WHERE auth_id = {sub};
    IF v_count > 1 THEN RAISE EXCEPTION 'Duplicate patient profile for subject %', {sub}; END IF;
    IF v_count = 0 THEN
        INSERT INTO paciente(auth_id, nombre, apellido, numero_documento, tipo_documento,
                             origen_registro, correo_electronico)
        VALUES ({sub}, {first}, {last}, {_sql(str(doc_number))}, 'DNI', 'APLICACION', {email});
    END IF;
END
$demo$;
"""
    return identity_block + profile_block


def _care_rows(
    seed_key: str,
    config: dict[str, Any],
    hospitals: list[dict[str, Any]],
    identities: list[tuple[str, dict[str, Any]]],
) -> str:
    end_date = date.fromisoformat(config["end_date"])
    start_date = end_date - timedelta(days=config["days"] - 1)
    result: list[str] = []
    seed_short = _stable_token(seed_key, 6)
    doctor_by_hospital: dict[str, list[tuple[str, dict[str, Any]]]] = {}
    for identity_key, identity in identities:
        if identity["kind"] == "doctor":
            doctor_by_hospital.setdefault(identity["hospital_key"], []).append((identity_key, identity))
    for doctors in doctor_by_hospital.values():
        doctors.sort(key=lambda pair: pair[0])

    daily = config["daily_per_hospital"]
    patient_count = config["history_patients"]
    rng = random.Random(config["random_seed"])
    for hospital_index, hospital in enumerate(sorted(hospitals, key=lambda h: h["key"])):
        hkey = hospital["key"]
        hospital_token = f"{hospital_index + 1:03d}"
        docs = doctor_by_hospital[hkey]
        primary = {identity["primary_specialty"]: key for key, identity in docs}
        clinical_doc = primary["CLINICA_MEDICA"]
        trauma_doc = primary["TRAUMATOLOGIA"]
        ordered_docs = [(clinical_doc, next(i for k, i in docs if k == clinical_doc)),
                        (trauma_doc, next(i for k, i in docs if k == trauma_doc))]
        hospital_sequence = 0
        for day_index in range(config["days"]):
            encounter_date = start_date + timedelta(days=day_index)
            date_key = encounter_date.strftime("%Y%m%d")
            daily_count = _daily_volume(seed_key, config["random_seed"], hkey, encounter_date, daily)
            for position in range(daily_count):
                doctor_index = position % 2
                doctor_key, doctor = ordered_docs[doctor_index]
                # Each doctor handles one specialty in a separate, non-overlapping daily block.
                specialty_index = (day_index + doctor_index) % len(SPECIALTIES)
                specialty_code, _ = SPECIALTIES[specialty_index]
                hospital_sequence += 1
                patient_index = (hospital_sequence - 1) % patient_count
                document = 97000000 + hospital_index * patient_count + patient_index + 1
                patient_stable = f"{hkey}:{patient_index + 1:04d}"
                patient_ref = _registry_ref(seed_key, "history_patient", patient_stable)
                result.append(_registry_block(
                    seed_key, "history_patient", patient_stable, "paciente",
                    f"numero_documento = {_sql(str(document))} AND nombre = {_sql('Demo')} "
                    f"AND apellido = {_sql(f'Paciente {patient_index + 1:04d}')} AND origen_registro = 'RECEPCION'",
                    "INSERT INTO paciente(nombre, apellido, numero_documento, tipo_documento, origen_registro) "
                    f"VALUES ({_sql('Demo')}, {_sql(f'Paciente {patient_index + 1:04d}')}, {_sql(str(document))}, 'DNI', 'RECEPCION')",
                ))

                sector_key = f"{hkey}:{specialty_code}"
                sector_ref = _registry_ref(seed_key, "sector", sector_key)
                room_number = 1 + (day_index % 2)
                room_key = f"{sector_key}:room{room_number}"
                room_ref = _registry_ref(seed_key, "room", room_key)
                specialty_ref = _registry_ref(seed_key, "specialty", specialty_code)
                hospital_ref = _registry_ref(seed_key, "hospital", hkey)
                doctor_auth = _sql(doctor["sub"])
                doctor_profile = f"(SELECT id FROM medico WHERE auth_id = {doctor_auth})"
                queue_key = f"{sector_key}:queue"
                queue_ref = _registry_ref(seed_key, "queue", queue_key)
                session_key = f"{hkey}:{date_key}:{doctor_key}:{specialty_code}"
                session_ref = _registry_ref(seed_key, "session", session_key)
                block_start_hour = 8 if doctor_index == 0 else 13
                block_end_hour = block_start_hour + 3
                result.append(_registry_block(
                    seed_key, "session", session_key, "sesion_atencion_medica",
                    f"id_medico = {doctor_profile} AND id_hospital = {hospital_ref} "
                    f"AND id_especialidad_medica = {specialty_ref} AND id_sector = {sector_ref} AND id_sala = {room_ref} "
                    f"AND estado = 'FINALIZADA' AND fecha_hora_inicio = {_sql(encounter_date.isoformat() + f' {block_start_hour:02d}:00:00')}::timestamp "
                    f"AND fecha_hora_fin = {_sql(encounter_date.isoformat() + f' {block_end_hour:02d}:00:00')}::timestamp",
                    "INSERT INTO sesion_atencion_medica(id_medico, id_hospital, id_especialidad_medica, id_sala, id_sector, estado, fecha_hora_inicio, fecha_hora_fin) "
                    f"VALUES ({doctor_profile}, {hospital_ref}, {specialty_ref}, {room_ref}, {sector_ref}, 'FINALIZADA', "
                    f"{_sql(encounter_date.isoformat() + f' {block_start_hour:02d}:00:00')}::timestamp, "
                    f"{_sql(encounter_date.isoformat() + f' {block_end_hour:02d}:00:00')}::timestamp)",
                ))

                seq = hospital_sequence
                call_code = f"D{seed_short}-H{hospital_token}-{date_key}-{position + 1:03d}"
                consult_key = call_code
                consult_ref = _registry_ref(seed_key, "consultation", consult_key)
                is_finished = rng.random() < 0.85
                is_triaged = rng.random() < 0.65
                severity_bot = rng.choices(
                    ["RIESGO_VITAL_INMEDIATO", "MUY_URGENTE", "URGENTE", "NORMAL", "NO_URGENTE"],
                    weights=[5, 10, 25, 35, 25],
                    k=1,
                )[0]
                result_json = _triage_result_json(severity_bot)
                severity_doctor = None
                if is_finished and rng.random() < 0.8:
                    # Include reviewed disagreements as well as matches and
                    # missing reviews so dashboard comparisons are meaningful.
                    severity_doctor = severity_bot if rng.random() < 0.8 else rng.choice(
                        [code for code in ("RIESGO_VITAL_INMEDIATO", "MUY_URGENTE", "URGENTE", "NORMAL", "NO_URGENTE")
                         if code != severity_bot])
                priority = {"RIESGO_VITAL_INMEDIATO": 5, "MUY_URGENTE": 4, "URGENTE": 3, "NORMAL": 2, "NO_URGENTE": 1}[severity_bot]
                ordinal = position // 2
                minute_start = block_start_hour * 60 + ordinal * 30 + 10
                visit_start_dt = datetime.combine(encounter_date, time(minute_start // 60, minute_start % 60))
                visit_end_dt = visit_start_dt + timedelta(minutes=20)
                wait_minutes = 5 + (seq * 17) % 76
                entry_time_dt = visit_start_dt - timedelta(minutes=wait_minutes)
                consultation_created_dt = entry_time_dt - timedelta(minutes=20)
                call_time_dt = visit_start_dt - timedelta(minutes=5)
                queue_state = "FINALIZADA" if is_finished else "CANCELADA"
                consult_state = queue_state

                chat_key = f"{call_code}:chat"
                chat_ref = _registry_ref(seed_key, "chat", chat_key)
                if is_triaged:
                    chat_time_dt = (entry_time_dt - timedelta(minutes=15)).replace(microsecond=position)
                    chat_time = chat_time_dt.isoformat(sep=" ", timespec="microseconds")
                    chat_ownership = (
                        "EXISTS (SELECT 1 FROM demo_seed_registry r "
                        f"WHERE r.seed_key = {_sql(seed_key)} AND r.entity_type = 'chat' "
                        f"AND r.stable_key = {_sql(chat_key)} AND r.entity_id = chat.id)"
                    )
                    result.append(_registry_block(
                        seed_key, "chat", chat_key, "chat",
                        chat_ownership,
                        "INSERT INTO chat(fecha_hora_creacion, paciente_id, finalizado, resultado_triage_json) "
                        f"VALUES ({_sql(chat_time)}::timestamp, {patient_ref}, TRUE, "
                        f"{_sql(result_json)})",
                    ))
                else:
                    chat_ref = "NULL"

                consult_insert = (
                    "INSERT INTO consulta_medica(fecha_hora_creacion, codigo_llamado, id_hospital, id_especialidad_medica, "
                    "id_medico, id_sala, id_sector, id_paciente, nivel_de_gravedad_bot, nivel_de_gravedad_medico, "
                    "resumen_pretriage_json, estado_consulta, id_chat) VALUES ("
                    f"{_sql(consultation_created_dt.isoformat(sep=' '))}::timestamp, {_sql(call_code)}, {hospital_ref}, {specialty_ref}, "
                    f"{doctor_profile if is_finished else 'NULL'}, {room_ref if is_finished else 'NULL'}, {sector_ref}, {patient_ref}, "
                    f"{_sql(severity_bot)}, {_sql(severity_doctor)}, "
                    f"{_sql(result_json if is_triaged else None)}, "
                    f"{_sql(consult_state)}, {chat_ref})"
                )
                result.append(_registry_block(
                    seed_key, "consultation", consult_key, "consulta_medica",
                    f"codigo_llamado = {_sql(call_code)} AND id_hospital = {hospital_ref}",
                    consult_insert,
                ))

                entry_key = call_code
                result.append(_registry_block(
                    seed_key, "queue_entry", entry_key, "entrada_cola",
                    f"id_consulta_medica = {consult_ref} AND id_gestor_de_cola = {queue_ref}",
                    "INSERT INTO entrada_cola(id_gestor_de_cola, id_consulta_medica, estado, tipo_pausa, prioridad, orden_relativo, fecha_hora_ingreso, fecha_hora_llamado) "
                    f"VALUES ({queue_ref}, {consult_ref}, {_sql(queue_state)}, NULL, {priority}, {seq}, "
                    f"{_sql(entry_time_dt.isoformat(sep=' '))}::timestamp, "
                    f"{_sql(call_time_dt.isoformat(sep=' ') if is_finished else None)}::timestamp)",
                ))

                if is_finished:
                    attention_key = call_code
                    result.append(_registry_block(
                        seed_key, "attention", attention_key, "atencion_medica",
                        f"id_consulta_medica = {consult_ref}",
                        "INSERT INTO atencion_medica(id_consulta_medica, id_sesion_atencion_medica, estado, fecha_hora_inicio, fecha_hora_fin) "
                        f"VALUES ({consult_ref}, {session_ref}, 'FINALIZADA', {_sql(visit_start_dt.isoformat(sep=' '))}::timestamp, {_sql(visit_end_dt.isoformat(sep=' '))}::timestamp)",
                    ))
    return "".join(result)


def build_sql(config: dict[str, Any], hospitals: list[dict[str, Any]], identities: dict[str, dict[str, Any]]) -> str:
    """Return one transactional, repeatable SQL script for the supplied demo inputs.

    Existing matching hospitals and Auth0-backed identities are reused after
    ambiguity checks. The script only inserts or updates demo-owned rows and
    never deletes application data. Reusing ``seed_key`` requires the same
    normalized configuration, hospitals, and identity map.
    """
    config_out, hospitals_out, identities_out = _validate(config, hospitals, identities)
    seed_key = config_out["seed_key"]
    bundle = {
        "config": config_out,
        "hospitals": sorted(hospitals_out, key=lambda h: h["key"]),
        "identities": identities_out,
    }
    content_hash = hashlib.sha256(json.dumps(bundle, sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()
    lines = [
        "-- Generated by scripts/demo_seed_data.py; review inputs before execution.",
        "BEGIN;",
        "SET LOCAL lock_timeout = '15s';",
        f"SELECT pg_advisory_xact_lock(hashtext('pretriage-demo-seed'), hashtext({_sql(seed_key)}));",
        "CREATE TABLE IF NOT EXISTS demo_seed_runs (seed_key TEXT PRIMARY KEY, input_sha256 TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now());",
        "CREATE TABLE IF NOT EXISTS demo_seed_registry (seed_key TEXT NOT NULL, entity_type TEXT NOT NULL, stable_key TEXT NOT NULL, entity_id BIGINT NOT NULL, PRIMARY KEY(seed_key, entity_type, stable_key));",
        f"""DO $demo$
DECLARE v_hash TEXT;
BEGIN
    SELECT input_sha256 INTO v_hash FROM demo_seed_runs WHERE seed_key = {_sql(seed_key)} FOR UPDATE;
    IF FOUND THEN
        IF v_hash <> {_sql(content_hash)} THEN RAISE EXCEPTION 'Demo seed key % was already used with different inputs', {_sql(seed_key)}; END IF;
    ELSE
        INSERT INTO demo_seed_runs(seed_key, input_sha256) VALUES ({_sql(seed_key)}, {_sql(content_hash)});
    END IF;
END
$demo$;""",
    ]

    # Validate the canonical specialty catalog before any data changes; never choose an arbitrary duplicate.
    for code, name in SPECIALTIES:
        lines.append(f"""DO $demo$
DECLARE v_count BIGINT; v_name TEXT;
BEGIN
    SELECT COUNT(*), MIN(nombre) INTO v_count, v_name FROM especialidad_medica WHERE codigo = {_sql(code)};
    IF v_count > 1 THEN RAISE EXCEPTION 'Duplicate specialty code %', {_sql(code)}; END IF;
    IF v_count = 1 AND v_name <> {_sql(name)} THEN RAISE EXCEPTION 'Specialty code % has unexpected name %', {_sql(code)}, v_name; END IF;
END
$demo$;""")
        lines.append(_registry_block(seed_key, "specialty", code, "especialidad_medica",
            f"codigo = {_sql(code)}",
            f"INSERT INTO especialidad_medica(codigo, nombre) VALUES ({_sql(code)}, {_sql(name)})"))

    # Shared additive catalog: keep the input hash compatible with previous
    # runs so an existing demo can gain missing catalog rows on its next apply.
    lines.append(build_health_insurance_sql())

    for hospital in hospitals_out:
        lines.append(_ensure_hospital(seed_key, hospital))
        hospital_ref = _registry_ref(seed_key, "hospital", hospital["key"])
        for code, _ in SPECIALTIES:
            specialty_ref = _registry_ref(seed_key, "specialty", code)
            lines.append(f"""DO $demo$
DECLARE v_count BIGINT;
BEGIN
    SELECT COUNT(*) INTO v_count FROM hospital_especialidad_medica
     WHERE id_hospital = {hospital_ref} AND id_especialidad_medica = {specialty_ref};
    IF v_count > 1 THEN RAISE EXCEPTION 'Duplicate hospital specialty relation for hospital % and code %', {_sql(hospital['key'])}, {_sql(code)}; END IF;
    IF v_count = 0 THEN INSERT INTO hospital_especialidad_medica(id_hospital, id_especialidad_medica) VALUES ({hospital_ref}, {specialty_ref}); END IF;
END
$demo$;""")
            sector_key = f"{hospital['key']}:{code}"
            sector_name = f"DEMO {seed_key} {code}"
            lines.append(_registry_block(seed_key, "sector", sector_key, "sector",
                f"id_hospital = {hospital_ref} AND id_especialidad = {specialty_ref} AND nombre = {_sql(sector_name)}",
                "INSERT INTO sector(nombre, activa, id_hospital, id_especialidad) "
                f"VALUES ({_sql(sector_name)}, TRUE, {hospital_ref}, {specialty_ref})"))
            sector_ref = _registry_ref(seed_key, "sector", sector_key)
            for room_no in (1, 2):
                room_name = f"DEMO {seed_key} Consultorio {room_no} {code}"
                room_key = f"{sector_key}:room{room_no}"
                lines.append(_registry_block(seed_key, "room", room_key, "sala",
                    f"id_hospital = {hospital_ref} AND id_especialidad_medica = {specialty_ref} AND id_sector = {sector_ref} AND nombre = {_sql(room_name)}",
                    "INSERT INTO sala(nombre, id_sector, activa, id_hospital, id_especialidad_medica) "
                    f"VALUES ({_sql(room_name)}, {sector_ref}, TRUE, {hospital_ref}, {specialty_ref})"))
            queue_key = f"{sector_key}:queue"
            lines.append(_registry_block(seed_key, "queue", queue_key, "gestor_de_cola",
                f"id_hospital = {hospital_ref} AND id_especialidad_medica = {specialty_ref} AND id_sector = {sector_ref}",
                "INSERT INTO gestor_de_cola(id_hospital, id_especialidad_medica, id_sector) "
                f"VALUES ({hospital_ref}, {specialty_ref}, {sector_ref})"))

    platform_identity = next(i for _, i in identities_out if i["kind"] == "platform_admin")
    platform_sub = platform_identity["sub"]
    id_doc_numbers = {key: 90000000 + index for index, (key, _) in enumerate(identities_out, start=1)}
    for key, identity in identities_out:
        lines.append(_ensure_identity(identity, id_doc_numbers[key]))
        if identity["kind"] == "doctor":
            auth = _sql(identity["sub"])
            matricula = f"DEMO-{_stable_token(seed_key, 6)}-{_stable_token(key, 6)}"
            lines.append(f"""DO $demo$
DECLARE v_count BIGINT;
BEGIN
    SELECT COUNT(*) INTO v_count FROM medico WHERE auth_id = {auth};
    IF v_count > 1 THEN RAISE EXCEPTION 'Duplicate doctor profiles for subject %', {auth}; END IF;
    IF v_count = 0 THEN INSERT INTO medico(auth_id, matricula) VALUES ({auth}, {_sql(matricula)}); END IF;
END
$demo$;""")
        elif identity["kind"] == "receptionist":
            auth = _sql(identity["sub"])
            hospital_ref = _registry_ref(seed_key, "hospital", identity["hospital_key"])
            lines.append(f"""DO $demo$
DECLARE v_count BIGINT; v_id BIGINT;
BEGIN
    SELECT COUNT(*), MIN(id) INTO v_count, v_id FROM recepcionista WHERE auth_id = {auth};
    IF v_count > 1 THEN RAISE EXCEPTION 'Duplicate receptionist profiles for subject %', {auth}; END IF;
    IF v_count = 1 AND EXISTS (SELECT 1 FROM recepcionista WHERE id = v_id AND id_hospital <> {hospital_ref}) THEN
        RAISE EXCEPTION 'Receptionist subject % already belongs to a different hospital', {auth};
    ELSIF v_count = 0 THEN
        INSERT INTO recepcionista(auth_id, id_hospital) VALUES ({auth}, {hospital_ref});
    END IF;
END
$demo$;""")

    # One active membership per applicable user/hospital; append roles without removing existing roles.
    for hospital in hospitals_out:
        hospital_ref = _registry_ref(seed_key, "hospital", hospital["key"])
        for key, identity in identities_out:
            kind = identity["kind"]
            applies = (kind == "hospital_admin" and identity.get("hospital_key") is None) \
                or (kind in {"doctor", "receptionist"} and identity.get("hospital_key") == hospital["key"])
            if not applies:
                continue
            role = {"hospital_admin": "ADMIN_HOSPITAL", "doctor": "MEDICO", "receptionist": "RECEPCIONISTA"}[kind]
            sub = _sql(identity["sub"])
            lines.append(f"""DO $demo$
DECLARE v_count BIGINT; v_membership BIGINT; v_state TEXT; v_role_count BIGINT;
BEGIN
    SELECT COUNT(*), MIN(id) INTO v_count, v_membership FROM membresia_hospital
     WHERE usuario_auth_id = {sub} AND hospital_id = {hospital_ref};
    IF v_count > 1 THEN RAISE EXCEPTION 'Duplicate membership for identity % and hospital %', {_sql(key)}, {_sql(hospital['key'])}; END IF;
    IF v_count = 0 THEN
        INSERT INTO membresia_hospital(usuario_auth_id, hospital_id, estado, fecha_aceptacion, creada_por_usuario_auth_id)
        VALUES ({sub}, {hospital_ref}, 'ACTIVA', now(), {_sql(platform_sub)}) RETURNING id INTO v_membership;
    ELSE
        SELECT estado INTO v_state FROM membresia_hospital WHERE id = v_membership;
        IF v_state <> 'ACTIVA' THEN RAISE EXCEPTION 'Existing demo membership is not active for identity % and hospital %', {_sql(key)}, {_sql(hospital['key'])}; END IF;
    END IF;
    SELECT COUNT(*) INTO v_role_count FROM membresia_hospital_rol WHERE membresia_id = v_membership AND rol = {_sql(role)};
    IF v_role_count > 1 THEN RAISE EXCEPTION 'Duplicate membership role for identity % and hospital %', {_sql(key)}, {_sql(hospital['key'])}; END IF;
    IF v_role_count = 0 THEN INSERT INTO membresia_hospital_rol(membresia_id, rol) VALUES (v_membership, {_sql(role)}); END IF;
END
$demo$;""")
            if kind == "doctor":
                for code, _ in SPECIALTIES:
                    specialty_ref = _registry_ref(seed_key, "specialty", code)
                    lines.append(f"""DO $demo$
DECLARE v_count BIGINT;
BEGIN
    SELECT COUNT(*) INTO v_count FROM asignacion_medico_hospital
     WHERE id_medico = (SELECT id FROM medico WHERE auth_id = {sub})
       AND id_hospital = {hospital_ref} AND id_especialidad_medica = {specialty_ref};
    IF v_count > 1 THEN RAISE EXCEPTION 'Duplicate doctor assignment for identity % and specialty %', {_sql(key)}, {_sql(code)}; END IF;
    IF v_count = 0 THEN INSERT INTO asignacion_medico_hospital(id_medico, id_hospital, id_especialidad_medica)
        VALUES ((SELECT id FROM medico WHERE auth_id = {sub}), {hospital_ref}, {specialty_ref}); END IF;
END
$demo$;""")

    # Create one primary-specialty active session only when the doctor has no active or paused session.
    for hospital in hospitals_out:
        hkey = hospital["key"]
        hospital_ref = _registry_ref(seed_key, "hospital", hkey)
        for key, identity in identities_out:
            if identity["kind"] != "doctor" or identity["hospital_key"] != hkey:
                continue
            code = identity["primary_specialty"]
            sector_ref = _registry_ref(seed_key, "sector", f"{hkey}:{code}")
            specialty_ref = _registry_ref(seed_key, "specialty", code)
            room_ref = _registry_ref(seed_key, "room", f"{hkey}:{code}:room1")
            sub = _sql(identity["sub"])
            active_key = f"{hkey}:{key}:active-primary"
            lines.append(f"""DO $demo$
DECLARE v_count BIGINT; v_id BIGINT; v_existing_state TEXT; v_registered BIGINT;
BEGIN
    SELECT entity_id INTO v_registered FROM demo_seed_registry
     WHERE seed_key = {_sql(seed_key)} AND entity_type = 'active_session' AND stable_key = {_sql(active_key)};
    IF FOUND THEN
        SELECT COUNT(*), MIN(id) INTO v_count, v_id FROM sesion_atencion_medica
         WHERE id = v_registered AND id_medico = (SELECT id FROM medico WHERE auth_id = {sub})
           AND id_hospital = {hospital_ref} AND id_especialidad_medica = {specialty_ref} AND id_sector = {sector_ref};
        IF v_count <> 1 THEN RAISE EXCEPTION 'Active session registry drift for identity %', {_sql(key)}; END IF;
    ELSE
        SELECT COUNT(*), MIN(id), MIN(estado) INTO v_count, v_id, v_existing_state
          FROM sesion_atencion_medica WHERE id_medico = (SELECT id FROM medico WHERE auth_id = {sub})
            AND estado IN ('ACTIVA', 'PAUSADA');
        IF v_count = 0 THEN
            INSERT INTO sesion_atencion_medica(id_medico, id_hospital, id_especialidad_medica, id_sala, id_sector, estado, fecha_hora_inicio, fecha_hora_fin)
            VALUES ((SELECT id FROM medico WHERE auth_id = {sub}), {hospital_ref}, {specialty_ref}, {room_ref}, {sector_ref}, 'ACTIVA', now(), NULL)
            RETURNING id INTO v_id;
        ELSIF v_count = 1 AND v_existing_state = 'ACTIVA' AND EXISTS (
            SELECT 1 FROM sesion_atencion_medica WHERE id = v_id AND id_hospital = {hospital_ref}
              AND id_especialidad_medica = {specialty_ref} AND id_sector = {sector_ref}) THEN
            NULL;
        ELSIF v_count = 1 AND v_existing_state = 'PAUSADA' AND EXISTS (
            SELECT 1 FROM sesion_atencion_medica WHERE id = v_id AND id_hospital = {hospital_ref}
              AND id_especialidad_medica = {specialty_ref} AND id_sector = {sector_ref}) THEN
            NULL;
        ELSE
            RAISE EXCEPTION 'Doctor % already has an active or paused session elsewhere; preserve it and resolve manually', {_sql(key)};
        END IF;
        INSERT INTO demo_seed_registry(seed_key, entity_type, stable_key, entity_id)
        VALUES ({_sql(seed_key)}, 'active_session', {_sql(active_key)}, v_id);
    END IF;
END
$demo$;""")

    lines.append(_care_rows(seed_key, config_out, hospitals_out, identities_out))
    subject_list = ", ".join(_sql(identity["sub"]) for _, identity in identities_out)
    hospital_ids = f"SELECT entity_id FROM demo_seed_registry WHERE seed_key = {_sql(seed_key)} AND entity_type = 'hospital'"
    lines.extend([
        f"""SELECT 'login_accounts' AS entity_type, COUNT(*)::BIGINT AS rows FROM usuario_auth WHERE id IN ({subject_list})
UNION ALL SELECT 'health_insurance_catalog', COUNT(*)::BIGINT FROM obra_social
UNION ALL SELECT 'hospital_memberships', COUNT(*)::BIGINT FROM membresia_hospital
  WHERE usuario_auth_id IN ({subject_list}) AND hospital_id IN ({hospital_ids})
UNION ALL SELECT entity_type, COUNT(*)::BIGINT FROM demo_seed_registry
  WHERE seed_key = {_sql(seed_key)} GROUP BY entity_type
ORDER BY entity_type;""",
        "COMMIT;",
    ])
    return "\n".join(lines)
