"""Populate local PostgreSQL with repeatable, synthetic hospital development data."""
import argparse
import subprocess
import sys


SPECIALTIES = (
    ('CLINICA_MEDICA', 'Clínica médica'),
    ('PEDIATRIA', 'Pediatría'),
    ('CARDIOLOGIA', 'Cardiología'),
    ('TRAUMATOLOGIA', 'Traumatología'),
    ('GINECOLOGIA', 'Ginecología'),
)


def quote(value):
    return "'" + value.replace("'", "''") + "'"


def build_sql(hospital_name, place_id=None, active_doctors=False):
    name = quote(hospital_name)
    values = ',\n'.join(f'({quote(code)}, {quote(label)})' for code, label in SPECIALTIES)
    place = quote(place_id) if place_id else None
    place_match_sql = f"SELECT id FROM hospital WHERE place_id = {place}" if place else "SELECT id FROM hospital WHERE FALSE"
    identity_predicate = f"nombre = {name} OR place_id = {place}" if place else f"nombre = {name}"
    sql = f"""
BEGIN;
SELECT pg_advisory_xact_lock(735192610);
CREATE TEMP TABLE seed_name_match (id bigint) ON COMMIT DROP;
INSERT INTO seed_name_match SELECT id FROM hospital WHERE nombre = {name};
CREATE TEMP TABLE seed_place_match (id bigint) ON COMMIT DROP;
INSERT INTO seed_place_match {place_match_sql};
DO $$ BEGIN
    IF (SELECT count(*) FROM seed_name_match) > 1 THEN
        RAISE EXCEPTION 'Hospital name is ambiguous; use a unique --hospital-name';
    END IF;
    IF (SELECT count(*) FROM seed_place_match) > 1 THEN
        RAISE EXCEPTION 'Place ID is ambiguous in hospital data';
    END IF;
    IF (SELECT count(*) FROM seed_name_match) = 1
       AND (SELECT count(*) FROM seed_place_match) = 1
       AND (SELECT id FROM seed_name_match) <> (SELECT id FROM seed_place_match) THEN
        RAISE EXCEPTION 'Hospital name and Place ID refer to different hospitals';
    END IF;
END $$;
INSERT INTO hospital (nombre)
SELECT {name}
WHERE NOT EXISTS (SELECT 1 FROM seed_name_match)
  AND NOT EXISTS (SELECT 1 FROM seed_place_match);
CREATE TEMP TABLE seed_hospital ON COMMIT DROP AS
SELECT id FROM hospital WHERE {identity_predicate};
DO $$ BEGIN
    IF (SELECT count(*) FROM seed_hospital) <> 1 THEN
        RAISE EXCEPTION 'Could not resolve exactly one seed hospital';
    END IF;
END $$;
CREATE TEMP TABLE seed_specialties (codigo text, nombre text) ON COMMIT DROP;
INSERT INTO seed_specialties VALUES {values};
INSERT INTO especialidad_medica (codigo, nombre)
SELECT codigo, nombre FROM seed_specialties ON CONFLICT (codigo) DO NOTHING;
"""
    if place_id:
        sql += f"""
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM hospital h JOIN seed_hospital sh ON sh.id=h.id
               WHERE nullif(h.place_id, '') IS NOT NULL AND h.place_id <> {place})
       OR EXISTS (SELECT 1 FROM hospital h WHERE h.place_id={place}
                  AND h.id NOT IN (SELECT id FROM seed_hospital)) THEN
        RAISE EXCEPTION 'Place ID conflicts with existing hospital data';
    END IF;
END $$;
UPDATE hospital SET place_id={place} WHERE id IN (SELECT id FROM seed_hospital);
"""
    sql += """
INSERT INTO hospital_especialidad_medica (id_hospital, id_especialidad_medica)
SELECT h.id, e.id FROM seed_hospital h
CROSS JOIN especialidad_medica e JOIN seed_specialties ss ON ss.codigo=e.codigo
WHERE NOT EXISTS (SELECT 1 FROM hospital_especialidad_medica he
                  WHERE he.id_hospital=h.id AND he.id_especialidad_medica=e.id);

INSERT INTO sector (nombre, activa, id_hospital, id_especialidad)
SELECT 'DEV - ' || e.codigo, TRUE, h.id, e.id FROM seed_hospital h
CROSS JOIN especialidad_medica e JOIN seed_specialties ss ON ss.codigo=e.codigo
WHERE NOT EXISTS (SELECT 1 FROM sector s WHERE s.id_hospital=h.id
                  AND s.id_especialidad=e.id AND s.nombre='DEV - ' || e.codigo);
CREATE TEMP TABLE seed_sectors ON COMMIT DROP AS
SELECT s.id, s.id_hospital, s.id_especialidad FROM sector s
JOIN seed_hospital h ON h.id=s.id_hospital
JOIN especialidad_medica e ON e.id=s.id_especialidad
JOIN seed_specialties ss ON ss.codigo=e.codigo
WHERE s.nombre='DEV - ' || e.codigo;

INSERT INTO sala (nombre, activa, id_hospital, id_especialidad_medica, id_sector)
SELECT 'DEV - Consultorio ' || e.codigo, TRUE, s.id_hospital, e.id, s.id
FROM seed_sectors s JOIN especialidad_medica e ON e.id=s.id_especialidad
WHERE NOT EXISTS (SELECT 1 FROM sala sa WHERE sa.id_sector=s.id
                  AND sa.nombre='DEV - Consultorio ' || e.codigo);
CREATE TEMP TABLE seed_rooms ON COMMIT DROP AS
SELECT sa.id, sa.id_sector, sa.id_hospital, sa.id_especialidad_medica
FROM sala sa JOIN seed_sectors s ON s.id=sa.id_sector
JOIN especialidad_medica e ON e.id=sa.id_especialidad_medica
WHERE sa.nombre='DEV - Consultorio ' || e.codigo;

INSERT INTO gestor_de_cola (id_hospital, id_especialidad_medica, id_sector)
SELECT s.id_hospital, s.id_especialidad, s.id FROM seed_sectors s
WHERE NOT EXISTS (SELECT 1 FROM gestor_de_cola g WHERE g.id_hospital=s.id_hospital
                  AND g.id_especialidad_medica=s.id_especialidad AND g.id_sector=s.id);
"""
    if active_doctors:
        sql += """
-- Synthetic local identities; these do NOT create Auth0 accounts.
CREATE TEMP TABLE seed_doctors ON COMMIT DROP AS
SELECT r.*, e.codigo, 'dev-seed|h' || r.id_hospital || '|' || e.codigo AS auth_id
FROM seed_rooms r JOIN especialidad_medica e ON e.id=r.id_especialidad_medica;
INSERT INTO usuario_auth (id, nombre, apellido, correo_electronico,
                          numero_documento, tipo_documento, rol)
SELECT auth_id, 'Medico DEV', codigo, lower(codigo) || '@pretriage.invalid',
       'DEV-' || id_hospital || '-' || codigo, 'DNI', 1
FROM seed_doctors ON CONFLICT (id) DO NOTHING;
INSERT INTO medico (auth_id, matricula)
SELECT auth_id, 'DEV-' || id_hospital || '-' || codigo FROM seed_doctors
WHERE NOT EXISTS (SELECT 1 FROM medico m WHERE m.auth_id=seed_doctors.auth_id);
INSERT INTO asignacion_medico_hospital (id_medico, id_hospital, id_especialidad_medica)
SELECT m.id, d.id_hospital, d.id_especialidad_medica
FROM seed_doctors d JOIN medico m ON m.auth_id=d.auth_id
WHERE NOT EXISTS (SELECT 1 FROM asignacion_medico_hospital a
                  WHERE a.id_medico=m.id AND a.id_hospital=d.id_hospital
                    AND a.id_especialidad_medica=d.id_especialidad_medica);
INSERT INTO sesion_atencion_medica (id_medico, id_hospital, id_especialidad_medica,
                                   id_sector, id_sala, estado, fecha_hora_inicio)
SELECT m.id, d.id_hospital, d.id_especialidad_medica, d.id_sector, d.id,
       'ACTIVA', LOCALTIMESTAMP
FROM seed_doctors d JOIN medico m ON m.auth_id=d.auth_id
JOIN sector s ON s.id=d.id_sector JOIN sala sa ON sa.id=d.id
WHERE s.activa AND sa.activa
  AND NOT EXISTS (SELECT 1 FROM sesion_atencion_medica sm
                  WHERE sm.estado IN ('ACTIVA', 'PAUSADA')
                    AND (sm.id_medico=m.id OR sm.id_sala=d.id));
"""
    sql += """
-- Count operational capacity rather than assuming a Hospital.activo column exists.
SELECT h.id, h.nombre, h.place_id, e.codigo,
       (SELECT count(*) FROM sector s WHERE s.id_hospital=h.id
         AND s.id_especialidad=e.id AND s.activa) AS sectores_activos,
       (SELECT count(*) FROM sala sa JOIN sector s ON s.id=sa.id_sector
         WHERE sa.id_hospital=h.id AND sa.id_especialidad_medica=e.id
           AND sa.activa AND s.activa) AS salas_activas,
       (SELECT count(*) FROM sesion_atencion_medica sm
         WHERE sm.id_hospital=h.id AND sm.id_especialidad_medica=e.id
           AND sm.estado='ACTIVA') AS medicos_activos
FROM hospital h JOIN seed_hospital sh ON sh.id=h.id
JOIN hospital_especialidad_medica he ON he.id_hospital=h.id
JOIN especialidad_medica e ON e.id=he.id_especialidad_medica ORDER BY e.codigo;
COMMIT;
"""
    return sql


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--db-container', default='postgre')
    parser.add_argument('--db-user', default='myuser')
    parser.add_argument('--db-name', default='pretriage_db')
    parser.add_argument('--hospital-name', default='Hospital de Prueba - PreTriage')
    parser.add_argument('--place-id', help='Real Google Places ID; required for Google-based nearby listing')
    parser.add_argument('--with-active-doctors', action='store_true',
                        help='Create synthetic doctors with active sessions (development only)')
    parser.add_argument('--dry-run', action='store_true', help='Print SQL without accessing PostgreSQL')
    args = parser.parse_args()
    if not args.hospital_name.strip() or (args.place_id is not None and not args.place_id.strip()):
        parser.error('Hospital name and supplied Place ID must not be blank')
    sql = build_sql(args.hospital_name.strip(), args.place_id, args.with_active_doctors)
    if args.dry_run:
        print(sql)
        return 0
    try:
        result = subprocess.run(['docker', 'exec', '-i', args.db_container, 'psql',
                                 '-X', '-U', args.db_user, '-d', args.db_name,
                                 '-v', 'ON_ERROR_STOP=1', '-f', '-'],
                                input=sql, text=True, encoding='utf-8', timeout=60)
    except (OSError, subprocess.TimeoutExpired) as error:
        print(f'No se pudo ejecutar la carga: {error}', file=sys.stderr)
        return 1
    if result.returncode == 0:
        print('Carga completada. Puede repetirse sin duplicar los datos del seed.')
        if not args.place_id:
            print('No se configuro un Place ID. Para el listado cercano de Google, use --place-id con un ID real.')
    return result.returncode


if __name__ == '__main__':
    raise SystemExit(main())
