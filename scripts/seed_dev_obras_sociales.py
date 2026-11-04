"""Populate the local health-insurance catalog without changing patient data."""
import argparse
import subprocess
import sys


OBRAS_SOCIALES = (
    "OSDE",
    "SWISS MEDICAL",
    "GALENO",
    "MEDIFE",
    "OMINT",
    "SANCOR SALUD",
    "ACCORD SALUD",
    "UNION PERSONAL",
    "OSECAC",
    "OSDEPYM",
    "OSPE",
    "OSUTHGRA",
    "OSPERYH",
    "IOMA",
    "PAMI",
    "DOSUBA",
)


def build_sql():
    values = ",\n".join("('" + name.replace("'", "''") + "')" for name in OBRAS_SOCIALES)
    return f"""
BEGIN;
SET LOCAL lock_timeout = '5s';
-- Serialize catalog writers while checking names; no unique-name constraint exists.
LOCK TABLE obra_social IN SHARE ROW EXCLUSIVE MODE;
CREATE TEMP TABLE seed_obras_sociales (nombre text) ON COMMIT DROP;
INSERT INTO seed_obras_sociales (nombre) VALUES {values};

INSERT INTO obra_social (nombre, virgente)
SELECT seed.nombre, TRUE FROM seed_obras_sociales seed
WHERE NOT EXISTS (
    SELECT 1 FROM obra_social existing
    WHERE upper(btrim(existing.nombre)) = upper(seed.nombre)
);

SELECT os.id, os.nombre, os.virgente FROM obra_social os
JOIN seed_obras_sociales seed ON upper(btrim(os.nombre)) = upper(seed.nombre)
ORDER BY os.nombre;
SELECT count(*) AS obras_sociales_vigentes FROM obra_social WHERE virgente;
COMMIT;
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db-container", default="postgre")
    parser.add_argument("--db-user", default="myuser")
    parser.add_argument("--db-name", default="pretriage_db")
    parser.add_argument("--dry-run", action="store_true", help="Print SQL without accessing PostgreSQL")
    args = parser.parse_args()
    sql = build_sql()
    if args.dry_run:
        print(sql)
        return 0
    try:
        result = subprocess.run(
            ["docker", "exec", "-i", args.db_container, "psql", "-X", "-U", args.db_user,
             "-d", args.db_name, "-v", "ON_ERROR_STOP=1", "-f", "-"],
            input=sql, text=True, encoding="utf-8", timeout=60,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        print(f"No se pudo cargar el catalogo: {error}", file=sys.stderr)
        return 1
    if result.returncode == 0:
        print("Catalogo cargado. Puede repetirse sin duplicar nombres ni reactivar bajas existentes.")
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
