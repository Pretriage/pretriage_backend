"""Prepare or apply repeatable CABA hospital, login and dashboard demo data.

Without --apply or --resolve-places this command is an offline preview. It never
resets a database, changes existing passwords, or sends invitation emails.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import date, datetime, timedelta
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

if __package__:
    from .demo_seed_catalogs import OBRAS_SOCIALES
else:
    from demo_seed_catalogs import OBRAS_SOCIALES


ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "scripts" / "data" / "demo_hospitals_caba.json"
REQUIRED_TABLES = (
    "hospital", "direccion", "coordenada", "especialidad_medica", "hospital_especialidad_medica",
    "sector", "sala", "gestor_de_cola", "usuario_auth", "membresia_hospital",
    "membresia_hospital_rol", "medico", "recepcionista", "paciente",
    "asignacion_medico_hospital", "sesion_atencion_medica", "consulta_medica",
    "entrada_cola", "atencion_medica", "chat", "obra_social",
)


class SeedError(RuntimeError):
    """An actionable error that is safe to print without credentials."""


def load_catalog(path: Path = CATALOG) -> list[dict[str, Any]]:
    with path.open(encoding="utf-8") as stream:
        catalog = json.load(stream)
    hospitals = catalog["hospitals"]
    if not hospitals or len(hospitals) > 30:
        raise SeedError("El catálogo debe contener entre 1 y 30 hospitales")
    keys: set[str] = set()
    for item in hospitals:
        if not re.fullmatch(r"[a-z][a-z0-9-]{0,19}", item["key"]) or item["key"] in keys:
            raise SeedError("El catálogo contiene claves de hospital inválidas o repetidas")
        keys.add(item["key"])
        if not (-34.75 < float(item["latitude"]) < -34.50 and -58.56 < float(item["longitude"]) < -58.32):
            raise SeedError("Una ubicación del catálogo está fuera del área de CABA")
    return hospitals


def build_accounts(hospitals: list[dict[str, Any]], seed_key: str, email_domain: str) -> list[dict[str, Any]]:
    accounts: list[dict[str, Any]] = []

    def add(key: str, first: str, last: str, kind: str, **extra: Any) -> None:
        local = f"{seed_key}.{key}"
        if len(local) > 64:
            raise SeedError("La clave de la carga produce correos demasiado largos")
        accounts.append({"key": key, "email": f"{local}@{email_domain}",
                         "first_name": first, "last_name": last, "kind": kind, **extra})

    add("admin", "Demo", "Plataforma", "platform_admin")
    add("hospital-admin", "Demo", "Administración", "hospital_admin")
    for hospital in hospitals:
        key = hospital["key"]
        add(f"recepcion-{key}", "Demo Recepción", key, "receptionist", hospital_key=key)
        add(f"medico-{key}-clinica", "Demo Clínica", key, "doctor", hospital_key=key,
            primary_specialty="CLINICA_MEDICA")
        add(f"medico-{key}-trauma", "Demo Traumatología", key, "doctor", hospital_key=key,
            primary_specialty="TRAUMATOLOGIA")
    for number in range(1, 4):
        add(f"paciente-{number}", "Paciente Demo", str(number), "patient")
    return accounts


def load_settings(path: Path | None) -> dict[str, str]:
    values: dict[str, str] = {}
    if path is not None:
        if not path.is_file():
            raise SeedError("No se encuentra el archivo de variables indicado")
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if line.startswith("export "):
                line = line[7:]
            key, separator, value = line.partition("=")
            if not separator or not re.fullmatch(r"[A-Z][A-Z0-9_]*", key.strip()):
                continue
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
                value = value[1:-1]
            values[key.strip()] = value
    # Process variables take precedence without exporting file contents globally.
    values.update(os.environ)
    return values


def auth0_settings(values: dict[str, str], seed_key: str) -> dict[str, str]:
    domain = values.get("AUTH0_BASE_PATH") or values.get("AUTH0_ISSUER_URI", "")
    if domain:
        parsed = urlsplit(domain)
        if parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password:
            raise SeedError("AUTH0_BASE_PATH debe ser una URL HTTPS de Auth0")
        domain = f"https://{parsed.netloc}"
    result = {"domain": domain, "client_id": values.get("AUTH0_M2M_CLIENT_ID", ""),
              "client_secret": values.get("AUTH0_M2M_CLIENT_SECRET", ""),
              "connection": values.get("AUTH0_DEMO_CONNECTION", "Username-Password-Authentication"),
              "seed_key": seed_key}
    if not all(result[key] for key in ("domain", "client_id", "client_secret")):
        raise SeedError("Faltan AUTH0_BASE_PATH/AUTH0_ISSUER_URI, AUTH0_M2M_CLIENT_ID o AUTH0_M2M_CLIENT_SECRET")
    return result


def protect_directory(directory: Path) -> None:
    if directory.is_symlink():
        raise SeedError("El directorio privado no puede ser un enlace simbólico")
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    if os.name == "nt":
        # A chmod alone does not restrict Windows ACLs. Protect the directory
        # before any password is serialized so new files inherit this ACL.
        script = """$ErrorActionPreference = 'Stop'
$sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
$acl = New-Object System.Security.AccessControl.DirectorySecurity
$acl.SetOwner($sid)
$acl.SetAccessRuleProtection($true, $false)
$rule = New-Object System.Security.AccessControl.FileSystemAccessRule -ArgumentList $sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'
$acl.AddAccessRule($rule)
[System.IO.Directory]::SetAccessControl($env:PRETRIAGE_DEMO_STATE_DIR, $acl)
"""
        environment = {**os.environ, "PRETRIAGE_DEMO_STATE_DIR": str(directory.resolve())}
        result = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script],
                                env=environment, capture_output=True, text=True)
        if result.returncode:
            raise SeedError("No se pudo restringir el ACL del directorio de credenciales")
    else:
        directory.chmod(0o700)


@contextmanager
def locked_state(directory: Path):
    """Serialize local runs as well as the SQL transaction's database lock."""
    protect_directory(directory)
    lock_path = directory / "run.lock"
    descriptor = os.open(lock_path, os.O_RDWR | os.O_CREAT, 0o600)
    locked = False
    try:
        if os.fstat(descriptor).st_size == 0:
            os.write(descriptor, b"0")
        os.lseek(descriptor, 0, os.SEEK_SET)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(descriptor, msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
            locked = True
        except OSError:
            raise SeedError("Ya hay otra carga usando este estado privado") from None
        yield
    finally:
        if locked:
            os.lseek(descriptor, 0, os.SEEK_SET)
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(descriptor, msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.flock(descriptor, fcntl.LOCK_UN)
        os.close(descriptor)


def save_state(path: Path, state: dict[str, Any]) -> None:
    temporary = path.with_name("state.next.json")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(state, stream, ensure_ascii=False, indent=2)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise


def resolve_hospitals(hospitals: list[dict[str, Any]], state: dict[str, Any], api_key: str,
                      checkpoint, *, refresh: bool = False) -> list[dict[str, Any]]:
    places = state.setdefault("hospital_place_ids", {})
    resolved = []
    for hospital in hospitals:
        place_id = places.get(hospital["key"]) or hospital.get("place_id")
        if not place_id or refresh:
            if not api_key:
                raise SeedError("Falta GOOGLE_MAPS_API_KEY para resolver los Place IDs por primera vez")
            query = f"{hospital['name']}, {hospital['street']} {hospital['number']}, Ciudad Autónoma de Buenos Aires, Argentina"
            payload = {"textQuery": query, "languageCode": "es", "regionCode": "AR",
                       "pageSize": 2,
                       "locationBias": {"circle": {"center": {"latitude": hospital["latitude"],
                                                                "longitude": hospital["longitude"]}, "radius": 250.0}}}
            request = Request("https://places.googleapis.com/v1/places:searchText",
                              data=json.dumps(payload).encode("utf-8"), method="POST",
                              headers={"Content-Type": "application/json", "X-Goog-Api-Key": api_key,
                                       "X-Goog-FieldMask": "places.id"})
            try:
                with urlopen(request, timeout=20) as response:
                    result = json.load(response)
            except HTTPError as error:
                raise SeedError(f"Google Places rechazó la búsqueda de {hospital['key']} (HTTP {error.code})") from None
            except (URLError, TimeoutError, OSError, ValueError):
                raise SeedError(f"No se pudo resolver el Place ID de {hospital['key']}") from None
            matches = result.get("places", [])
            if len(matches) != 1 or not isinstance(matches[0].get("id"), str):
                raise SeedError(f"La búsqueda de {hospital['key']} es vacía o ambigua; revisar el catálogo antes de continuar")
            place_id = matches[0]["id"]
        if places.get(hospital["key"]) != place_id:
            places[hospital["key"]] = place_id
            checkpoint(state)
        resolved.append({**hospital, "place_id": place_id})
    if len({item["place_id"] for item in resolved}) != len(resolved):
        raise SeedError("Dos hospitales del catálogo resuelven al mismo Place ID")
    return resolved


class DockerDatabaseTarget:
    def __init__(self, container: str, user: str, database: str):
        self.command = ["docker", "exec", "-i", container, "psql", "-X", "-q", "-U", user,
                        "-d", database, "-v", "ON_ERROR_STOP=1", "-f", "-"]

    def execute(self, sql: str) -> str:
        try:
            result = subprocess.run(self.command, input=sql, encoding="utf-8",
                                    capture_output=True, timeout=180)
        except (OSError, subprocess.TimeoutExpired):
            raise SeedError("No se pudo acceder a PostgreSQL por Docker; comprobar contenedor, usuario y base") from None
        if result.returncode:
            # A SQL error can echo data. Keep a protected diagnostic, not stdout.
            raise SeedError("PostgreSQL rechazó la carga; la transacción se revirtió. Comprobar versión del esquema y conflictos del seed")
        return result.stdout

    def preflight(self) -> dict[str, Any]:
        names = ",".join("('" + name + "')" for name in REQUIRED_TABLES)
        sql = f"""DO $$ BEGIN
IF EXISTS (SELECT 1 FROM (VALUES {names}) t(name) WHERE to_regclass('public.'||name) IS NULL)
THEN RAISE EXCEPTION 'Run backend schema initialization before seeding'; END IF;
END $$;
SELECT current_database();
"""
        self.execute(sql)
        return {"target": "docker"}


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_mutually_exclusive_group()
    action.add_argument("--apply", action="store_true", help="Crear/reutilizar cuentas Auth0 y aplicar SQL")
    action.add_argument("--dry-run", action="store_true", help="Mostrar el plan sin red ni escrituras (predeterminado)")
    action.add_argument("--resolve-places", action="store_true", help="Resolver/cachear solo Place IDs; no Auth0 ni PostgreSQL")
    action.add_argument("--export-sql", type=Path, help="Exportar SQL usando identidades del estado privado existente")
    parser.add_argument("--target", choices=("docker", "aws"), default="docker")
    parser.add_argument("--db-container", default="postgre")
    parser.add_argument("--db-user", default="myuser")
    parser.add_argument("--db-name", default="pretriage_db")
    parser.add_argument("--profile", help="Perfil AWS para el modo aws")
    parser.add_argument("--region", default="us-east-1")
    parser.add_argument("--stack", default="pretriage-demo")
    parser.add_argument("--env-file", type=Path, help="Variables Auth0/Maps; nunca se imprimen")
    parser.add_argument("--seed-key", default="demo-caba-v1")
    parser.add_argument("--email-domain", default="demo.pretriage.com.ar")
    parser.add_argument("--days", type=int)
    parser.add_argument("--end-date", type=date.fromisoformat, help="Último día del historial, predeterminado: ayer")
    parser.add_argument("--daily-per-hospital", type=int)
    parser.add_argument("--history-patients", type=int, help="Pacientes históricos por hospital; predeterminado: 80")
    parser.add_argument("--random-seed", type=int)
    parser.add_argument("--state-dir", type=Path, help="Directorio exclusivo para estado/contraseñas (predeterminado: target/demo-seed/<seed-key>)")
    parser.add_argument("--catalog", type=Path, default=CATALOG)
    parser.add_argument("--refresh-places", action="store_true", help="Consultar Google aunque haya IDs cacheados")
    parser.add_argument("--verify-logins", action="store_true", help="Tras --apply, comprobar el acceso de todas las cuentas sin mostrar tokens")
    parser.add_argument("--backend-url", help="API para --verify-logins; por defecto el dominio AWS o localhost según --target")
    args = parser.parse_args(argv)
    args.explicit_dataset_options = {name for name in ("days", "end_date", "daily_per_hospital", "history_patients", "random_seed")
                                     if getattr(args, name) is not None}
    for name, default in (("days", 30), ("daily_per_hospital", 12), ("history_patients", 80), ("random_seed", 42)):
        if getattr(args, name) is None:
            setattr(args, name, default)
    if not re.fullmatch(r"[a-z][a-z0-9-]{0,23}", args.seed_key):
        parser.error("--seed-key debe contener 1 a 24 caracteres: letras minúsculas, números o guiones")
    if not re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\.[a-z]{2,}", args.email_domain):
        parser.error("--email-domain debe ser un dominio válido en minúsculas")
    if not 2 <= args.days <= 90 or not 2 <= args.daily_per_hospital <= 12 or not 3 <= args.history_patients <= 1000:
        parser.error("Límites: 2..90 días, referencia de 2..12 atenciones/día/hospital, 3..1000 pacientes históricos por hospital")
    if args.verify_logins and not args.apply:
        parser.error("--verify-logins requiere --apply para comprobar cuentas ya vinculadas a la base")
    if args.verify_logins:
        if __package__:
            from .demo_seed_login import validate_backend_url, LoginSmokeError
        else:
            from demo_seed_login import validate_backend_url, LoginSmokeError
        try:
            args.backend_url = validate_backend_url(args.backend_url or
                ("https://api.pretriage.com.ar" if args.target == "aws" else "http://localhost:8080"))
        except LoginSmokeError as error:
            parser.error(str(error))
    args.end_date = args.end_date or date.today() - timedelta(days=1)
    if args.end_date >= date.today():
        parser.error("El historial debe terminar ayer o antes para no generar atenciones futuras")
    if args.target == "aws" and args.region != "us-east-1":
        parser.error("Esta demo está en la Región seleccionada us-east-1")
    args.state_dir = args.state_dir or ROOT / "target" / "demo-seed" / args.seed_key
    return args


def run(args) -> int:
    state_path = args.state_dir / "state.json"
    # A retry tomorrow should use the same historical window, rather than fail
    # the SQL input hash because the first-run default was yesterday.
    if state_path.exists():
        saved = json.loads(state_path.read_text(encoding="utf-8"))
        previous = saved.get("last_applied") or saved.get("prepared_config") or {}
        for name in ("days", "end_date", "daily_per_hospital", "history_patients", "random_seed"):
            if name not in args.explicit_dataset_options and name in previous:
                setattr(args, name, date.fromisoformat(previous[name]) if name == "end_date" else previous[name])
    hospitals = load_catalog(args.catalog)
    accounts = build_accounts(hospitals, args.seed_key, args.email_domain)
    config = {"seed_key": args.seed_key, "days": args.days, "end_date": args.end_date.isoformat(),
              "daily_per_hospital": args.daily_per_hospital, "history_patients": args.history_patients,
              "random_seed": args.random_seed}
    start = args.end_date - timedelta(days=args.days - 1)
    print(f"Carga {args.seed_key}: {len(hospitals)} hospitales, 5 especialidades, {len(accounts)} cuentas con acceso")
    print(f"Catálogo: {len(OBRAS_SOCIALES)} obras sociales; se conservan nombres y bajas existentes")
    print(f"Historial ficticio: {start} a {args.end_date}, referencia {args.daily_per_hospital} consultas/día/hospital")
    print("Hospitales: " + ", ".join(item["name"] for item in hospitals))
    if not (args.apply or args.resolve_places or args.export_sql):
        print("Vista previa: no se accedió a Auth0, Google, AWS ni PostgreSQL. Usar --apply para ejecutar.")
        return 0
    settings = load_settings(args.env_file)
    auth_settings = auth0_settings(settings, args.seed_key) if args.apply else None
    with locked_state(args.state_dir):
        state = json.loads(state_path.read_text(encoding="utf-8")) if state_path.exists() else {}
        if state.get("seed_key") not in (None, args.seed_key):
            raise SeedError("El estado privado pertenece a otra carga")
        state.setdefault("seed_key", args.seed_key)
        checkpoint = lambda updated: save_state(state_path, updated)
        # Validate the destination before creating any Auth0 accounts.
        target = None
        if args.apply:
            if args.target == "aws":
                if __package__:
                    from .demo_seed_aws import AwsDatabaseTarget
                else:
                    from demo_seed_aws import AwsDatabaseTarget
                target = AwsDatabaseTarget(args.profile, args.region, args.stack)
            else:
                target = DockerDatabaseTarget(args.db_container, args.db_user, args.db_name)
            target.preflight()
        hospitals = resolve_hospitals(hospitals, state, settings.get("GOOGLE_MAPS_API_KEY", ""),
                                     checkpoint, refresh=args.refresh_places)
        if args.resolve_places:
            print(f"Place IDs resueltos/cacheados: {len(hospitals)}. Estado: {state_path}")
            return 0
        if __package__:
            from .demo_seed_data import build_sql, validate_inputs
        else:
            from demo_seed_data import build_sql, validate_inputs
        if args.apply:
            placeholders = {account["key"]: {**account, "sub": "pending|" + account["key"]} for account in accounts}
            validate_inputs(config, hospitals, placeholders)
            state["prepared_config"] = config
            checkpoint(state)
            if __package__:
                from .demo_seed_auth0 import provision_identities
            else:
                from demo_seed_auth0 import provision_identities
            identities = provision_identities(accounts, auth_settings, state, checkpoint)
        else:
            identities = {}
            for account in accounts:
                prior = state.get("accounts", {}).get(account["key"], {})
                if prior.get("email") != account["email"] or not prior.get("sub"):
                    raise SeedError("Exportar SQL requiere un estado previo con todas las identidades Auth0 resueltas")
                identities[account["key"]] = {**account, "sub": prior["sub"]}
        sql = build_sql(config, hospitals, identities)
        if args.export_sql:
            output = args.export_sql.resolve()
            if args.state_dir.resolve() not in output.parents:
                raise SeedError("Guardar el SQL exportado dentro de --state-dir para conservar el ACL privado")
            output.write_text(sql, encoding="utf-8")
            print(f"SQL exportado: {output}. No se aplicó en PostgreSQL.")
        else:
            print(target.execute(sql).strip())
            state["last_applied"] = {**config, "target": args.target, "at": datetime.now().astimezone().isoformat()}
            checkpoint(state)
            print(f"Carga completada. Credenciales y estado privado: {state_path}")
            print("Conservar ese archivo para reutilizar las cuentas después de recrear PostgreSQL.")
            if args.verify_logins:
                if __package__:
                    from .demo_seed_login import verify_logins
                else:
                    from demo_seed_login import verify_logins
                backend_url = args.backend_url or ("https://api.pretriage.com.ar" if args.target == "aws" else "http://localhost:8080")
                try:
                    count = verify_logins(identities, state, backend_url)
                except RuntimeError as error:
                    raise SeedError(f"La carga SQL ya se completó, pero falló la comprobación de acceso: {error}") from None
                print(f"Acceso comprobado: {count} cuentas; no se guardaron ni mostraron tokens.")
    return 0


def main(argv=None) -> int:
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    args = parse_args(argv)
    try:
        return run(args)
    except (SeedError, RuntimeError) as error:
        print(f"Carga interrumpida: {error}", file=sys.stderr)
        return 1
    except (OSError, ValueError, KeyError):
        print("Carga interrumpida: revisar archivos, permisos y configuración; no se imprimen datos privados", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
