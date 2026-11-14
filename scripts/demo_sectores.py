"""Nombres realistas de especialidades, sectores y salas para los datos de demostración.

Los sectores se nombran como las guardias o unidades que efectivamente tienen los
hospitales de la Ciudad (por ejemplo, "Guardia de Pediatría"). Es un catálogo
compartido por la carga histórica (demo_seed_data) y por la actualización de
hospitales operativos (demo_hospitales_operativos).
"""
from __future__ import annotations

# Código -> nombre, en el mismo formato que EspecialidadesMedicasSeeder.
ESPECIALIDADES: dict[str, str] = {
    "CLINICA_MEDICA": "Clínica médica",
    "PEDIATRIA": "Pediatría",
    "CARDIOLOGIA": "Cardiología",
    "TRAUMATOLOGIA": "Traumatología",
    "GINECOLOGIA": "Ginecología",
    "CIRUGIA_GENERAL": "Cirugía general",
    "NEUROLOGIA": "Neurología",
    "OFTALMOLOGIA": "Oftalmología",
    "OTORRINOLARINGOLOGIA": "Otorrinolaringología",
    "UROLOGIA": "Urología",
    "SALUD_MENTAL": "Salud mental",
}

# Código de especialidad -> nombre del sector de atención de demanda espontánea.
NOMBRE_SECTOR: dict[str, str] = {
    "CLINICA_MEDICA": "Guardia de Adultos",
    "PEDIATRIA": "Guardia de Pediatría",
    "CARDIOLOGIA": "Unidad de Dolor Torácico",
    "TRAUMATOLOGIA": "Guardia de Traumatología",
    "GINECOLOGIA": "Guardia de Ginecología y Obstetricia",
    "CIRUGIA_GENERAL": "Guardia de Cirugía General",
    "NEUROLOGIA": "Guardia de Neurología",
    "OFTALMOLOGIA": "Guardia de Oftalmología",
    "OTORRINOLARINGOLOGIA": "Guardia de Otorrinolaringología",
    "UROLOGIA": "Guardia de Urología",
    "SALUD_MENTAL": "Guardia de Salud Mental",
}

SALAS_POR_SECTOR = 2


def nombre_sala(numero: int) -> str:
    return f"Consultorio {numero}"
