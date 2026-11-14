"""Actualiza los hospitales de demostración para dejarlos listos para usar.

Hace, en una sola transacción y sin borrar filas:

1. Agrega las especialidades nuevas del catálogo (si faltan).
2. Da de alta los hospitales nuevos de CABA y, para cada hospital del catálogo,
   las especialidades que atiende, con su sector, dos consultorios y su cola.
3. Renombra todos los sectores con nombres reales de guardia según la
   especialidad (por ejemplo, "Guardia de Pediatría") y los consultorios de la
   carga histórica ("Consultorio 1").
4. Cierra todas las atenciones abiertas: cancela consultas y entradas de cola
   activas, finaliza las atenciones en curso y cancela las admisiones de
   recepción sin terminar.
5. Deja un médico de guardia ficticio activo en cada sector que no tenga
   ninguno, para que el hospital figure disponible.
6. Ingresa pacientes ficticios a la cola de cada sector de todos los hospitales.

Sin --apply ni --export-sql solo muestra el plan. Ejemplos:

    python scripts/demo_hospitales_operativos.py
    python scripts/demo_hospitales_operativos.py --export-sql target/demo-operativo.sql
    python scripts/demo_hospitales_operativos.py --apply --target docker
    python scripts/demo_hospitales_operativos.py --apply --target aws --profile pretriage --env-file .env.demo

Con GOOGLE_MAPS_API_KEY en --env-file se resuelven los Place IDs de los
hospitales nuevos; sin Place ID el hospital se crea igual, pero los pacientes no
lo ven en el listado de la app hasta volver a correr el script con la clave.
"""
from __future__ import annotations

import argparse
from datetime import date
import hashlib
import json
from pathlib import Path
import re
import sys
from typing import Any

if __package__:
    from .demo_sectores import ESPECIALIDADES, NOMBRE_SECTOR, SALAS_POR_SECTOR, nombre_sala
else:
    from demo_sectores import ESPECIALIDADES, NOMBRE_SECTOR, SALAS_POR_SECTOR, nombre_sala

ROOT = Path(__file__).resolve().parents[1]
CATALOGO = ROOT / "scripts" / "data" / "demo_hospitales_operativos.json"

# Sector creado por el seed opt-in de métricas: se identifica por su nombre.
SECTORES_SIN_RENOMBRAR = ("Sector metricas demo",)

PRIORIDAD = {"RIESGO_VITAL_INMEDIATO": 5, "MUY_URGENTE": 4, "URGENTE": 3, "NORMAL": 2, "NO_URGENTE": 1}
ESTADOS_ABIERTOS_COLA = ("EN_COLA", "EN_REVISION", "LLAMADO", "EN_ESPERA", "ATRASADO", "EN_ATENCION")

# Casos ficticios por especialidad: (nivel, motivo, síntomas, inicio, evolución, dolor, edad, género).
# Género "F" restringe el nombre del paciente; None admite cualquiera.
CASOS: dict[str, list[tuple]] = {
    "CLINICA_MEDICA": [
        ("URGENTE", "Fiebre alta y escalofríos", ["Fiebre de 39 °C", "Escalofríos", "Dolor muscular"], "Hace 2 días", "Empeoró", 4, 46, None),
        ("NORMAL", "Dolor de garganta al tragar", ["Dolor de garganta", "Fiebre de 38 °C"], "Hace 2 días", "Se mantiene igual", 4, 31, None),
        ("NO_URGENTE", "Tos y congestión nasal", ["Tos seca", "Congestión nasal"], "Hace 4 días", "Se mantiene igual", None, 27, None),
        ("MUY_URGENTE", "Falta de aire de inicio reciente", ["Dificultad para respirar", "Cansancio al hablar"], "Hace 3 horas", "Empeoró", None, 67, None),
    ],
    "PEDIATRIA": [
        ("NORMAL", "Fiebre y dolor de oído", ["Fiebre de 38,3 °C", "Dolor de oído derecho"], "Desde anoche", "Se mantiene igual", 5, 4, None),
        ("URGENTE", "Vómitos y diarrea", ["Vómitos (4 veces)", "Diarrea", "Decaimiento"], "Hace 12 horas", "Empeoró", 3, 2, None),
        ("NO_URGENTE", "Erupción en la piel", ["Ronchas en brazos y piernas", "Picazón"], "Hace 1 día", "Se mantiene igual", None, 7, None),
        ("MUY_URGENTE", "Dificultad para respirar con silbidos", ["Silbidos al respirar", "Respiración rápida"], "Hace 2 horas", "Empeoró", None, 6, None),
    ],
    "CARDIOLOGIA": [
        ("MUY_URGENTE", "Dolor de pecho opresivo", ["Dolor opresivo en el pecho", "Sudoración"], "Hace 1 hora", "Empeoró", 8, 62, None),
        ("URGENTE", "Palpitaciones", ["Palpitaciones", "Mareo leve"], "Hace 3 horas", "Se mantiene igual", None, 54, None),
        ("NORMAL", "Presión arterial alta en control", ["Presión 160/95 en casa", "Dolor de cabeza leve"], "Hoy a la mañana", "Se mantiene igual", 3, 58, None),
    ],
    "TRAUMATOLOGIA": [
        ("URGENTE", "Caída con dolor de muñeca", ["Dolor e hinchazón en la muñeca izquierda", "No puede mover la mano"], "Hace 2 horas", "Se mantiene igual", 7, 38, None),
        ("NORMAL", "Torcedura de tobillo", ["Dolor e hinchazón en el tobillo derecho", "Puede apoyar el pie"], "Hace 5 horas", "Se mantiene igual", 5, 24, None),
        ("NO_URGENTE", "Dolor de espalda baja", ["Dolor lumbar al agacharse"], "Hace 3 días", "Mejoró", 4, 42, None),
    ],
    "GINECOLOGIA": [
        ("URGENTE", "Dolor abdominal bajo en embarazo", ["Dolor en la parte baja del abdomen", "Embarazo de 20 semanas"], "Hace 4 horas", "Empeoró", 6, 29, "F"),
        ("NORMAL", "Ardor al orinar", ["Ardor al orinar", "Ganas frecuentes de orinar"], "Hace 2 días", "Se mantiene igual", 3, 34, "F"),
        ("NO_URGENTE", "Control por flujo vaginal", ["Flujo con picazón"], "Hace 5 días", "Se mantiene igual", None, 26, "F"),
    ],
    "CIRUGIA_GENERAL": [
        ("MUY_URGENTE", "Dolor abdominal en fosa ilíaca derecha", ["Dolor abdominal inferior derecho", "Fiebre de 38,5 °C", "Vómitos"], "Hace 8 horas", "Empeoró", 8, 22, None),
        ("URGENTE", "Herida cortante en la mano", ["Corte profundo en la palma", "Sangrado controlado con presión"], "Hace 1 hora", "Se mantiene igual", 6, 35, None),
        ("NORMAL", "Bulto doloroso en la ingle", ["Bulto en la ingle derecha que molesta al hacer fuerza"], "Hace 2 semanas", "Se mantiene igual", 3, 51, None),
    ],
    "NEUROLOGIA": [
        ("URGENTE", "Dolor de cabeza intenso", ["Dolor de cabeza intenso", "Molestia con la luz"], "Hace 6 horas", "Empeoró", 8, 36, None),
        ("NORMAL", "Mareos al levantarse", ["Mareos", "Sensación de inestabilidad"], "Hace 2 días", "Se mantiene igual", None, 59, None),
        ("NO_URGENTE", "Hormigueo en la mano", ["Hormigueo en los dedos de la mano derecha"], "Hace 1 semana", "Se mantiene igual", None, 44, None),
    ],
    "OFTALMOLOGIA": [
        ("URGENTE", "Cuerpo extraño en el ojo", ["Ardor y lagrimeo en el ojo izquierdo", "Sensación de arenilla"], "Hace 2 horas", "Se mantiene igual", 5, 33, None),
        ("NORMAL", "Ojo rojo con secreción", ["Ojo rojo", "Lagañas", "Picazón"], "Hace 2 días", "Se mantiene igual", 2, 19, None),
    ],
    "OTORRINOLARINGOLOGIA": [
        ("NORMAL", "Dolor de oído", ["Dolor de oído izquierdo", "Sensación de oído tapado"], "Hace 2 días", "Se mantiene igual", 5, 28, None),
        ("URGENTE", "Sangrado nasal que no cede", ["Sangrado por la nariz", "Toma anticoagulantes"], "Hace 40 minutos", "Se mantiene igual", None, 71, None),
    ],
    "UROLOGIA": [
        ("URGENTE", "Dolor lumbar tipo cólico", ["Dolor intenso en la zona lumbar derecha", "Náuseas"], "Hace 3 horas", "Empeoró", 9, 45, None),
        ("NORMAL", "Dificultad para orinar", ["Chorro débil", "Ganas frecuentes de orinar de noche"], "Hace 1 semana", "Empeoró", None, 66, None),
    ],
    "SALUD_MENTAL": [
        ("URGENTE", "Crisis de angustia", ["Angustia intensa", "Falta de aire", "Llanto"], "Hace 1 hora", "Se mantiene igual", None, 32, None),
        ("NORMAL", "Insomnio y ansiedad", ["No puede dormir", "Ansiedad"], "Hace 2 semanas", "Empeoró", None, 41, None),
    ],
}

NOMBRES = [
    ("Lucía", "Fernández", "F"), ("Martín", "Gómez", "M"), ("Sofía", "Rodríguez", "F"), ("Juan", "Pérez", "M"),
    ("Valentina", "López", "F"), ("Santiago", "Díaz", "M"), ("Camila", "Martínez", "F"), ("Mateo", "Romero", "M"),
    ("Julieta", "Sosa", "F"), ("Tomás", "Álvarez", "M"), ("Martina", "Torres", "F"), ("Benjamín", "Ruiz", "M"),
    ("Agustina", "Ramírez", "F"), ("Nicolás", "Flores", "M"), ("Florencia", "Acosta", "F"), ("Facundo", "Benítez", "M"),
    ("Carolina", "Medina", "F"), ("Diego", "Herrera", "M"), ("Paula", "Aguirre", "F"), ("Federico", "Giménez", "M"),
    ("Milagros", "Molina", "F"), ("Ignacio", "Castro", "M"), ("Rocío", "Ortiz", "F"), ("Gonzalo", "Silva", "M"),
    ("Antonella", "Luna", "F"), ("Joaquín", "Juárez", "M"), ("Micaela", "Cabrera", "F"), ("Lautaro", "Ríos", "M"),
    ("Daniela", "Morales", "F"), ("Ezequiel", "Vega", "M"), ("Mariana", "Ponce", "F"), ("Pablo", "Navarro", "M"),
]


class OperativoError(RuntimeError):
    """Error accionable que se puede mostrar sin datos sensibles."""


def _sql(value: Any) -> str:
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    if isinstance(value, (int, float)):
        return repr(value)
    return "'" + str(value).replace("'", "''") + "'"


def cargar_catalogo(path: Path = CATALOGO) -> dict[str, Any]:
    with path.open(encoding="utf-8") as stream:
        catalogo = json.load(stream)
    claves: set[str] = set()
    for hospital in catalogo["hospitales_existentes"] + catalogo["hospitales_nuevos"]:
        if not re.fullmatch(r"[a-z][a-z0-9-]{0,19}", hospital["key"]) or hospital["key"] in claves:
            raise OperativoError(f"Clave de hospital inválida o repetida: {hospital['key']!r}")
        claves.add(hospital["key"])
        desconocidas = set(hospital["especialidades"]) - set(ESPECIALIDADES)
        if desconocidas or not hospital["especialidades"]:
            raise OperativoError(f"Especialidades inválidas en {hospital['key']}: {sorted(desconocidas)}")
    for hospital in catalogo["hospitales_existentes"]:
        if not hospital.get("place_id"):
            raise OperativoError(f"El hospital existente {hospital['key']} necesita su Place ID")
    for hospital in catalogo["hospitales_nuevos"]:
        if not (-34.75 < float(hospital["latitude"]) < -34.50 and -58.56 < float(hospital["longitude"]) < -58.32):
            raise OperativoError(f"La ubicación de {hospital['key']} está fuera de CABA")
    return catalogo


def _hospital_ref(hospital: dict[str, Any]) -> str:
    """Busca el hospital por Place ID y, si no lo tiene, por nombre."""
    por_nombre = f"(SELECT MIN(id) FROM hospital WHERE lower(nombre) = lower({_sql(hospital['name'])}))"
    if hospital.get("place_id"):
        return f"COALESCE((SELECT MIN(id) FROM hospital WHERE place_id = {_sql(hospital['place_id'])}), {por_nombre})"
    return por_nombre


def _alta_hospital(hospital: dict[str, Any]) -> str:
    place = _sql(hospital.get("place_id"))
    nombre = _sql(hospital["name"])
    return f"""DO $op$
DECLARE v_count BIGINT; v_id BIGINT; v_coordenada BIGINT; v_direccion BIGINT;
BEGIN
    SELECT COUNT(*), MIN(id) INTO v_count, v_id FROM hospital
     WHERE ({place} IS NOT NULL AND place_id = {place}) OR lower(nombre) = lower({nombre});
    IF v_count > 1 THEN RAISE EXCEPTION 'Hospital ambiguo: %', {nombre}; END IF;
    IF v_count = 0 THEN
        INSERT INTO coordenada(latitud, longitud) VALUES ({_sql(hospital['latitude'])}, {_sql(hospital['longitude'])})
        RETURNING id INTO v_coordenada;
        INSERT INTO direccion(calle, altura, ciudad, provincia, id_coordenada)
        VALUES ({_sql(hospital['street'])}, {_sql(str(hospital['number']))}, {_sql(hospital['city'])},
                {_sql(hospital['province'])}, v_coordenada)
        RETURNING id INTO v_direccion;
        INSERT INTO hospital(place_id, nombre, id_direccion) VALUES ({place}, {nombre}, v_direccion);
    ELSIF {place} IS NOT NULL THEN
        UPDATE hospital SET place_id = {place} WHERE id = v_id AND (place_id IS NULL OR btrim(place_id) = '');
    END IF;
END
$op$;"""


def _especialidad_en_hospital(hospital: dict[str, Any], codigo: str) -> str:
    """Relación hospital-especialidad, sector, consultorios y cola, sin duplicar lo existente."""
    salas = "\n".join(
        f"""        INSERT INTO sala(nombre, id_sector, activa, id_hospital, id_especialidad_medica)
        VALUES ({_sql(nombre_sala(numero))}, v_sector, TRUE, v_hospital, v_especialidad);"""
        for numero in range(1, SALAS_POR_SECTOR + 1))
    return f"""DO $op$
DECLARE v_hospital BIGINT; v_especialidad BIGINT; v_sector BIGINT;
BEGIN
    v_hospital := {_hospital_ref(hospital)};
    IF v_hospital IS NULL THEN
        RAISE NOTICE 'Hospital % no encontrado; se omite %', {_sql(hospital['name'])}, {_sql(codigo)};
        RETURN;
    END IF;
    SELECT id INTO v_especialidad FROM especialidad_medica WHERE codigo = {_sql(codigo)};
    IF NOT EXISTS (SELECT 1 FROM hospital_especialidad_medica
                    WHERE id_hospital = v_hospital AND id_especialidad_medica = v_especialidad) THEN
        INSERT INTO hospital_especialidad_medica(id_hospital, id_especialidad_medica) VALUES (v_hospital, v_especialidad);
    END IF;
    SELECT MIN(id) INTO v_sector FROM sector
     WHERE id_hospital = v_hospital AND id_especialidad = v_especialidad AND NOT COALESCE(archivado, FALSE);
    IF v_sector IS NULL THEN
        INSERT INTO sector(nombre, activa, archivado, id_hospital, id_especialidad)
        VALUES ({_sql(NOMBRE_SECTOR[codigo])}, TRUE, FALSE, v_hospital, v_especialidad)
        RETURNING id INTO v_sector;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM sala WHERE id_sector = v_sector) THEN
{salas}
    END IF;
    IF NOT EXISTS (SELECT 1 FROM gestor_de_cola WHERE id_hospital = v_hospital
                    AND id_especialidad_medica = v_especialidad AND id_sector = v_sector) THEN
        INSERT INTO gestor_de_cola(id_hospital, id_especialidad_medica, id_sector) VALUES (v_hospital, v_especialidad, v_sector);
    END IF;
END
$op$;"""


def _renombrar_sectores() -> str:
    casos = "\n".join(f"            WHEN {_sql(codigo)} THEN {_sql(nombre)}" for codigo, nombre in NOMBRE_SECTOR.items())
    excluidos = ", ".join(_sql(nombre) for nombre in SECTORES_SIN_RENOMBRAR)
    return f"""-- Nombres reales de guardia; si un hospital tiene varios sectores de una especialidad, se numeran.
UPDATE sector s
   SET nombre = CASE WHEN x.cantidad > 1 THEN x.base || ' ' || x.orden ELSE x.base END
  FROM (
    SELECT s2.id,
           CASE e.codigo
{casos}
           END AS base,
           row_number() OVER (PARTITION BY s2.id_hospital, s2.id_especialidad ORDER BY s2.id) AS orden,
           count(*) OVER (PARTITION BY s2.id_hospital, s2.id_especialidad) AS cantidad
      FROM sector s2
      JOIN especialidad_medica e ON e.id = s2.id_especialidad
     WHERE NOT COALESCE(s2.archivado, FALSE) AND s2.nombre NOT IN ({excluidos})
  ) x
 WHERE s.id = x.id AND x.base IS NOT NULL
   AND s.nombre IS DISTINCT FROM (CASE WHEN x.cantidad > 1 THEN x.base || ' ' || x.orden ELSE x.base END);
-- Consultorios de la carga histórica: "DEMO <clave> Consultorio N <ESPECIALIDAD>" -> "Consultorio N".
UPDATE sala SET nombre = 'Consultorio ' || substring(nombre FROM 'Consultorio ([0-9]+)')
 WHERE nombre LIKE 'DEMO %Consultorio %' AND substring(nombre FROM 'Consultorio ([0-9]+)') IS NOT NULL;"""


def _colas_faltantes() -> str:
    return """-- Cada sector activo necesita su cola para recibir pacientes.
INSERT INTO gestor_de_cola(id_hospital, id_especialidad_medica, id_sector)
SELECT s.id_hospital, s.id_especialidad, s.id FROM sector s
 WHERE s.activa AND NOT COALESCE(s.archivado, FALSE)
   AND NOT EXISTS (SELECT 1 FROM gestor_de_cola g WHERE g.id_sector = s.id);"""


def _cerrar_atenciones_abiertas() -> str:
    abiertos = ", ".join(_sql(estado) for estado in ESTADOS_ABIERTOS_COLA)
    return f"""-- Cierre de todas las atenciones abiertas (no se borran filas).
UPDATE atencion_medica SET estado = 'FINALIZADA', fecha_hora_fin = COALESCE(fecha_hora_fin, now())
 WHERE estado = 'EN_CURSO';
UPDATE entrada_cola SET estado = CASE WHEN estado = 'EN_ATENCION' THEN 'FINALIZADA' ELSE 'CANCELADA' END
 WHERE estado IN ({abiertos});
UPDATE consulta_medica SET estado_consulta = CASE WHEN estado_consulta = 'EN_ATENCION' THEN 'FINALIZADA' ELSE 'CANCELADA' END
 WHERE estado_consulta NOT IN ('FINALIZADA', 'CANCELADA');
UPDATE admision_recepcion SET estado = 'CANCELADA', fecha_hora_cancelacion = COALESCE(fecha_hora_cancelacion, now())
 WHERE estado IN ('INICIADA', 'FORMULARIO_COMPLETO');"""


def _medicos_de_guardia() -> str:
    return """-- Un médico de guardia ficticio activo en cada sector sin médicos, para que el hospital figure disponible.
DO $op$
DECLARE r RECORD; v_medico BIGINT; v_sala BIGINT;
BEGIN
    FOR r IN
        SELECT s.id, s.id_hospital, s.id_especialidad FROM sector s
         WHERE s.activa AND NOT COALESCE(s.archivado, FALSE)
           AND NOT EXISTS (SELECT 1 FROM sesion_atencion_medica m WHERE m.id_sector = s.id AND m.estado = 'ACTIVA')
         ORDER BY s.id
    LOOP
        SELECT MIN(id) INTO v_sala FROM sala WHERE id_sector = r.id AND activa;
        CONTINUE WHEN v_sala IS NULL;
        SELECT MIN(id) INTO v_medico FROM medico WHERE matricula = 'GUARDIA-DEMO-' || r.id;
        IF v_medico IS NULL THEN
            INSERT INTO medico(matricula) VALUES ('GUARDIA-DEMO-' || r.id) RETURNING id INTO v_medico;
        END IF;
        CONTINUE WHEN EXISTS (SELECT 1 FROM sesion_atencion_medica WHERE id_medico = v_medico AND estado IN ('ACTIVA', 'PAUSADA'));
        INSERT INTO sesion_atencion_medica(id_medico, id_hospital, id_especialidad_medica, id_sala, id_sector, estado, fecha_hora_inicio)
        VALUES (v_medico, r.id_hospital, r.id_especialidad, v_sala, r.id, 'ACTIVA', now());
    END LOOP;
END
$op$;"""


def _resumen(caso: tuple) -> str:
    nivel, motivo, sintomas, inicio, evolucion, dolor, _edad, genero = caso
    resumen = {
        "motivoConsulta": motivo,
        "sintomas": sintomas,
        "inicio": inicio,
        "evolucion": evolucion,
        "intensidadDolor": dolor,
        "signosAlarma": [],
        "antecedentesRelevantes": [],
        "medicamentos": [],
        "alergias": [],
        "posibilidadEmbarazo": "Sí" if "Embarazo" in " ".join(sintomas) else ("No" if genero == "F" else "No aplica"),
        "observaciones": "Paciente ficticio de demostración.",
        "nivelPrioridad": PRIORIDAD[nivel],
        "requiereAtencionInmediata": nivel in ("RIESGO_VITAL_INMEDIATO", "MUY_URGENTE"),
        "recomendacionSeguridad": "Si empeora, avisar de inmediato al personal de guardia.",
    }
    return json.dumps(resumen, ensure_ascii=False, separators=(",", ":"))


def _ingresar_pacientes(pacientes_por_sector: int, semilla: str) -> str:
    filas_casos = []
    for codigo, casos in CASOS.items():
        for indice, caso in enumerate(casos):
            nivel, _motivo, _sintomas, _inicio, _evolucion, _dolor, edad, genero = caso
            filas_casos.append(f"({_sql(codigo)}, {indice}, {_sql(nivel)}, {PRIORIDAD[nivel]}, {edad}, "
                               f"{_sql(genero)}, {_sql(_resumen(caso))})")
    filas_nombres = [f"({indice}, {_sql(nombre)}, {_sql(apellido)}, {_sql(genero)})"
                     for indice, (nombre, apellido, genero) in enumerate(NOMBRES)]
    # Documentos ficticios altos y distintos en cada corrida; se saltean los ya usados.
    base_documento = 96_000_000 + int(hashlib.sha256(semilla.encode()).hexdigest(), 16) % 3_000_000
    return f"""-- Pacientes ficticios en la cola de cada sector de todos los hospitales.
CREATE TEMP TABLE demo_op_casos(codigo TEXT, indice INT, nivel TEXT, prioridad INT, edad INT, genero TEXT, resumen TEXT);
INSERT INTO demo_op_casos VALUES
{(","+chr(10)).join(filas_casos)};
CREATE TEMP TABLE demo_op_nombres(indice INT, nombre TEXT, apellido TEXT, genero TEXT);
INSERT INTO demo_op_nombres VALUES
{(","+chr(10)).join(filas_nombres)};
DO $op$
DECLARE
    r RECORD; c RECORD; n RECORD;
    v_total INT := 0; v_casos INT; v_documento BIGINT := {base_documento};
    v_paciente BIGINT; v_consulta BIGINT; v_orden BIGINT; v_ingreso TIMESTAMP;
BEGIN
    FOR r IN
        SELECT s.id AS sector, s.id_hospital, s.id_especialidad, e.codigo, g.id AS gestor
          FROM sector s
          JOIN especialidad_medica e ON e.id = s.id_especialidad
          JOIN gestor_de_cola g ON g.id_sector = s.id AND g.id_hospital = s.id_hospital
                               AND g.id_especialidad_medica = s.id_especialidad
         WHERE s.activa AND NOT COALESCE(s.archivado, FALSE)
         ORDER BY s.id_hospital, s.id
    LOOP
        SELECT COUNT(*) INTO v_casos FROM demo_op_casos WHERE codigo = r.codigo;
        CONTINUE WHEN v_casos = 0;
        FOR i IN 1..{pacientes_por_sector} LOOP
            v_total := v_total + 1;
            SELECT * INTO c FROM demo_op_casos WHERE codigo = r.codigo
             ORDER BY indice OFFSET ((r.sector + i) % v_casos) LIMIT 1;
            SELECT * INTO n FROM demo_op_nombres WHERE c.genero IS NULL OR genero = c.genero
             ORDER BY indice OFFSET (v_total % (SELECT COUNT(*) FROM demo_op_nombres
                                                 WHERE c.genero IS NULL OR genero = c.genero)) LIMIT 1;
            WHILE EXISTS (SELECT 1 FROM paciente WHERE numero_documento = v_documento::TEXT) LOOP
                v_documento := v_documento + 1;
            END LOOP;
            INSERT INTO paciente(nombre, apellido, numero_documento, tipo_documento, origen_registro,
                                 genero_biologico, fecha_nacimiento)
            VALUES (n.nombre, n.apellido, v_documento::TEXT, 'DNI', 'RECEPCION',
                    CASE n.genero WHEN 'F' THEN 1 ELSE 0 END,
                    (current_date - make_interval(years => c.edad, days => (v_total * 37) % 300))::DATE)
            RETURNING id INTO v_paciente;
            v_documento := v_documento + 1;
            v_ingreso := now() - make_interval(mins => ({pacientes_por_sector} - i + 1) * 9 + (v_total % 7));
            INSERT INTO consulta_medica(fecha_hora_creacion, id_hospital, id_especialidad_medica, id_sector,
                                        id_paciente, nivel_de_gravedad_bot, resumen_pretriage_json,
                                        estado_consulta, id_gestor_de_cola)
            VALUES (v_ingreso, r.id_hospital, r.id_especialidad, r.sector, v_paciente, c.nivel, c.resumen,
                    'EN_COLA', r.gestor)
            RETURNING id INTO v_consulta;
            SELECT COALESCE(MAX(orden_relativo), 0) + 1 INTO v_orden FROM entrada_cola WHERE id_gestor_de_cola = r.gestor;
            INSERT INTO entrada_cola(id_gestor_de_cola, id_consulta_medica, estado, prioridad, orden_relativo, fecha_hora_ingreso)
            VALUES (r.gestor, v_consulta, 'EN_COLA', c.prioridad, v_orden, v_ingreso);
        END LOOP;
    END LOOP;
    RAISE NOTICE 'Pacientes ingresados a las colas: %', v_total;
END
$op$;"""


def build_sql(catalogo: dict[str, Any], *, pacientes_por_sector: int = 3, medicos_de_guardia: bool = True,
              semilla: str | None = None) -> str:
    if not 0 <= pacientes_por_sector <= 10:
        raise OperativoError("Los pacientes por sector deben estar entre 0 y 10")
    semilla = semilla or date.today().isoformat()
    especialidades = ",\n".join(f"    ({_sql(codigo)}, {_sql(nombre)})" for codigo, nombre in ESPECIALIDADES.items())
    partes = [
        "-- Generado por scripts/demo_hospitales_operativos.py",
        "BEGIN;",
        "SET LOCAL lock_timeout = '15s';",
        "SELECT pg_advisory_xact_lock(hashtext('pretriage-demo-operativo'));",
        f"""INSERT INTO especialidad_medica(codigo, nombre)
SELECT v.codigo, v.nombre FROM (VALUES
{especialidades}
) AS v(codigo, nombre)
WHERE NOT EXISTS (SELECT 1 FROM especialidad_medica e WHERE e.codigo = v.codigo);""",
    ]
    partes.extend(_alta_hospital(hospital) for hospital in catalogo["hospitales_nuevos"])
    for hospital in catalogo["hospitales_existentes"] + catalogo["hospitales_nuevos"]:
        partes.extend(_especialidad_en_hospital(hospital, codigo) for codigo in hospital["especialidades"])
    partes.append(_renombrar_sectores())
    partes.append(_colas_faltantes())
    partes.append(_cerrar_atenciones_abiertas())
    if medicos_de_guardia:
        partes.append(_medicos_de_guardia())
    if pacientes_por_sector:
        partes.append(_ingresar_pacientes(pacientes_por_sector, semilla))
    partes.append("COMMIT;")
    return "\n".join(partes) + "\n"


def resolver_place_ids(catalogo: dict[str, Any], api_key: str) -> list[str]:
    """Completa los Place IDs faltantes de los hospitales nuevos; devuelve los que no se pudieron resolver."""
    if __package__:
        from .seed_demo import SeedError, resolve_hospitals
    else:
        from seed_demo import SeedError, resolve_hospitals
    sin_resolver = []
    for hospital in catalogo["hospitales_nuevos"]:
        if hospital.get("place_id"):
            continue
        try:
            hospital["place_id"] = resolve_hospitals([hospital], {}, api_key, lambda _state: None)[0]["place_id"]
        except SeedError as error:
            print(f"  {hospital['name']}: {error}")
            sin_resolver.append(hospital["name"])
    return sin_resolver


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    accion = parser.add_mutually_exclusive_group()
    accion.add_argument("--apply", action="store_true", help="Aplicar los cambios en la base")
    accion.add_argument("--export-sql", type=Path, help="Guardar el SQL generado sin ejecutarlo")
    parser.add_argument("--target", choices=("docker", "aws"), default="docker")
    parser.add_argument("--db-container", default="postgre")
    parser.add_argument("--db-user", default="myuser")
    parser.add_argument("--db-name", default="pretriage_db")
    parser.add_argument("--profile", help="Perfil AWS para --target aws")
    parser.add_argument("--region", default="us-east-1")
    parser.add_argument("--stack", default="pretriage-demo")
    parser.add_argument("--env-file", type=Path, help="Archivo con GOOGLE_MAPS_API_KEY para resolver Place IDs")
    parser.add_argument("--pacientes-por-sector", type=int, default=3)
    parser.add_argument("--sin-medicos-guardia", action="store_true",
                        help="No crear médicos de guardia ficticios en sectores sin médicos activos")
    parser.add_argument("--catalogo", type=Path, default=CATALOGO)
    return parser.parse_args(argv)


def run(args) -> int:
    catalogo = cargar_catalogo(args.catalogo)
    nuevos = catalogo["hospitales_nuevos"]
    total_especialidades = sum(len(h["especialidades"]) for h in catalogo["hospitales_existentes"] + nuevos)
    print(f"Especialidades del catálogo: {len(ESPECIALIDADES)} ({', '.join(ESPECIALIDADES.values())})")
    print(f"Hospitales nuevos: {len(nuevos)}: " + ", ".join(h["name"] for h in nuevos))
    print(f"Especialidades a asegurar en hospitales: {total_especialidades}")
    print(f"Se cierran todas las atenciones abiertas y se ingresan {args.pacientes_por_sector} pacientes por sector.")
    if not (args.apply or args.export_sql):
        print("Vista previa: no se accedió a Google, AWS ni PostgreSQL. Usar --apply o --export-sql.")
        return 0
    if args.env_file:
        if __package__:
            from .seed_demo import load_settings
        else:
            from seed_demo import load_settings
        api_key = load_settings(args.env_file).get("GOOGLE_MAPS_API_KEY", "")
        if api_key:
            pendientes = resolver_place_ids(catalogo, api_key)
            if pendientes:
                print("Sin Place ID (no aparecerán en la app hasta resolverlo): " + ", ".join(pendientes))
    sin_place = [h["name"] for h in nuevos if not h.get("place_id")]
    if sin_place:
        print(f"Aviso: {len(sin_place)} hospitales nuevos quedan sin Place ID y no se listan en la app.")
    sql = build_sql(catalogo, pacientes_por_sector=args.pacientes_por_sector,
                    medicos_de_guardia=not args.sin_medicos_guardia)
    if args.export_sql:
        args.export_sql.parent.mkdir(parents=True, exist_ok=True)
        args.export_sql.write_text(sql, encoding="utf-8")
        print(f"SQL exportado en {args.export_sql}")
        return 0
    if args.target == "aws":
        if __package__:
            from .demo_seed_aws import AwsDatabaseTarget
        else:
            from demo_seed_aws import AwsDatabaseTarget
        target = AwsDatabaseTarget(args.profile, args.region, args.stack)
    else:
        if __package__:
            from .seed_demo import DockerDatabaseTarget
        else:
            from seed_demo import DockerDatabaseTarget
        target = DockerDatabaseTarget(args.db_container, args.db_user, args.db_name)
    target.preflight()
    target.execute(sql)
    print("Listo: hospitales, sectores, especialidades y colas actualizados.")
    return 0


def main(argv=None) -> int:
    try:
        return run(parse_args(argv))
    except Exception as error:  # noqa: BLE001 - mensaje accionable para el operador
        print(f"Error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
