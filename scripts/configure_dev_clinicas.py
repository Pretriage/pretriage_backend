"""Apply verified Hospital de Clínicas metadata to the existing local hospital row."""
import argparse
import subprocess
import sys


PLACE_ID = "ChIJwwfedJTKvJURtfBmDu6LFm0"
HOSPITAL_NAME = "Hospital de Clínicas José de San Martín"
LATITUDE = -34.5984007
LONGITUDE = -58.3998358


def quote(value):
    return "'" + value.replace("'", "''") + "'"


def build_sql():
    place_id = quote(PLACE_ID)
    hospital_name = quote(HOSPITAL_NAME)
    street = quote("Av. Córdoba")
    city = quote("Buenos Aires")
    province = quote("CABA")
    return f"""
BEGIN;
SET LOCAL lock_timeout = '5s';
SELECT pg_advisory_xact_lock(735192610);

DO $$
DECLARE
    matches integer;
    hospital_row_id bigint;
BEGIN
    SELECT count(*) INTO matches FROM hospital WHERE place_id = {place_id};
    IF matches <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one hospital for the configured Place ID; found %', matches;
    END IF;
    SELECT id INTO hospital_row_id FROM hospital WHERE place_id = {place_id} FOR UPDATE;
END $$;

CREATE TEMP TABLE target_hospital ON COMMIT DROP AS
SELECT id, nombre, place_id, id_direccion
FROM hospital WHERE place_id = {place_id};
CREATE TEMP TABLE original_hospital_metadata ON COMMIT DROP AS
SELECT h.id, h.nombre, h.place_id, h.id_direccion,
       d.calle, d.altura, d.piso, d.codigo_postal, d.ciudad, d.provincia,
       d.id_coordenada, c.latitud, c.longitud
FROM target_hospital t
JOIN hospital h ON h.id = t.id
LEFT JOIN direccion d ON d.id = h.id_direccion
LEFT JOIN coordenada c ON c.id = d.id_coordenada;

-- Lock existing related rows, then reject shared records before updating metadata.
SELECT d.id FROM direccion d
JOIN target_hospital h ON h.id_direccion = d.id FOR UPDATE;
SELECT c.id FROM coordenada c
JOIN direccion d ON d.id_coordenada = c.id
JOIN target_hospital h ON h.id_direccion = d.id FOR UPDATE;
DO $$ BEGIN
    IF EXISTS (
        SELECT 1 FROM target_hospital t JOIN hospital other ON other.id_direccion = t.id_direccion
        WHERE t.id_direccion IS NOT NULL AND other.id <> t.id
    ) OR EXISTS (
        SELECT 1 FROM target_hospital t JOIN paciente p ON p.id_direccion = t.id_direccion
        WHERE t.id_direccion IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'The hospital address is shared with another hospital or patient';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM target_hospital t
        JOIN direccion own_address ON own_address.id = t.id_direccion
        JOIN direccion other_address ON other_address.id_coordenada = own_address.id_coordenada
        WHERE own_address.id_coordenada IS NOT NULL AND other_address.id <> own_address.id
    ) OR EXISTS (
        SELECT 1
        FROM target_hospital t
        JOIN direccion own_address ON own_address.id = t.id_direccion
        JOIN paciente p ON p.id_coordenada = own_address.id_coordenada
        WHERE own_address.id_coordenada IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'The hospital coordinates are shared with another address or patient';
    END IF;
END $$;

DO $$
DECLARE
    hospital_row_id bigint;
    address_row_id bigint;
    coordinate_row_id bigint;
BEGIN
    SELECT id, id_direccion INTO hospital_row_id, address_row_id FROM target_hospital;

    IF address_row_id IS NULL THEN
        INSERT INTO coordenada (latitud, longitud)
        VALUES ({LATITUDE}, {LONGITUDE}) RETURNING id INTO coordinate_row_id;
        INSERT INTO direccion (calle, altura, ciudad, provincia, id_coordenada)
        VALUES ({street}, '2351', {city}, {province}, coordinate_row_id)
        RETURNING id INTO address_row_id;
        UPDATE hospital SET nombre = {hospital_name}, id_direccion = address_row_id
        WHERE id = hospital_row_id;
    ELSE
        SELECT id_coordenada INTO coordinate_row_id
        FROM direccion WHERE id = address_row_id FOR UPDATE;
        IF coordinate_row_id IS NULL THEN
            INSERT INTO coordenada (latitud, longitud)
            VALUES ({LATITUDE}, {LONGITUDE}) RETURNING id INTO coordinate_row_id;
            UPDATE direccion SET id_coordenada = coordinate_row_id WHERE id = address_row_id;
        ELSE
            UPDATE coordenada SET latitud = {LATITUDE}, longitud = {LONGITUDE}
            WHERE id = coordinate_row_id;
        END IF;
        UPDATE direccion
        SET calle = {street}, altura = '2351', ciudad = {city}, provincia = {province}
        WHERE id = address_row_id;
        UPDATE hospital SET nombre = {hospital_name} WHERE id = hospital_row_id;
    END IF;
END $$;

SELECT 'original' AS estado, id, nombre, place_id, id_direccion, calle, altura,
       piso, codigo_postal, ciudad, provincia, id_coordenada, latitud, longitud
FROM original_hospital_metadata;
SELECT 'actualizado' AS estado, h.id, h.nombre, h.place_id, h.id_direccion,
       d.calle, d.altura, d.piso, d.codigo_postal, d.ciudad, d.provincia,
       d.id_coordenada, c.latitud, c.longitud
FROM target_hospital t
JOIN hospital h ON h.id = t.id
JOIN direccion d ON d.id = h.id_direccion
JOIN coordenada c ON c.id = d.id_coordenada;
COMMIT;
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--db-container', default='postgre')
    parser.add_argument('--db-user', default='myuser')
    parser.add_argument('--db-name', default='pretriage_db')
    parser.add_argument('--dry-run', action='store_true', help='Print SQL without accessing PostgreSQL')
    args = parser.parse_args()
    sql = build_sql()
    if args.dry_run:
        print(sql)
        return 0
    try:
        result = subprocess.run(['docker', 'exec', '-i', args.db_container, 'psql',
                                 '-X', '-U', args.db_user, '-d', args.db_name,
                                 '-v', 'ON_ERROR_STOP=1', '-f', '-'],
                                input=sql, text=True, encoding='utf-8', timeout=60)
    except (OSError, subprocess.TimeoutExpired) as error:
        print(f'No se pudo configurar el hospital: {error}', file=sys.stderr)
        return 1
    if result.returncode == 0:
        print('Metadatos verificados de Hospital de Clínicas configurados.')
    return result.returncode


if __name__ == '__main__':
    raise SystemExit(main())
