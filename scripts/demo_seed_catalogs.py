"""Shared, additive catalogs for the integral and local demo seed commands."""

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


def build_health_insurance_sql() -> str:
    """Fill missing names inside the caller's transaction, preserving existing rows.

    No unique constraint exists on names. Take the same table lock in both
    commands before checking normalized names, including inactive matches.
    A CTE avoids temporary-table DROP statements in the guarded AWS executor.
    """
    values = ",\n".join("('" + name.replace("'", "''") + "')" for name in OBRAS_SOCIALES)
    return f"""
LOCK TABLE obra_social IN SHARE ROW EXCLUSIVE MODE;
WITH seed_obras_sociales(nombre) AS (VALUES {values})
INSERT INTO obra_social (nombre, virgente)
SELECT seed.nombre, TRUE FROM seed_obras_sociales seed
WHERE NOT EXISTS (
    SELECT 1 FROM obra_social existing
    WHERE upper(btrim(existing.nombre)) = upper(seed.nombre)
);
"""
