-- Corrige tildes omitidas en el catálogo global sin modificar códigos, IDs ni relaciones.
-- Idempotente: solo actualiza los nombres antiguos conocidos.
UPDATE especialidad_medica
SET nombre = CASE codigo
    WHEN 'CLINICA_MEDICA' THEN 'Clínica médica'
    WHEN 'PEDIATRIA' THEN 'Pediatría'
    WHEN 'CARDIOLOGIA' THEN 'Cardiología'
    WHEN 'TRAUMATOLOGIA' THEN 'Traumatología'
    WHEN 'GINECOLOGIA' THEN 'Ginecología'
END
WHERE (codigo = 'CLINICA_MEDICA' AND nombre = 'Clinica medica')
   OR (codigo = 'PEDIATRIA' AND nombre = 'Pediatria')
   OR (codigo = 'CARDIOLOGIA' AND nombre = 'Cardiologia')
   OR (codigo = 'TRAUMATOLOGIA' AND nombre = 'Traumatologia')
   OR (codigo = 'GINECOLOGIA' AND nombre = 'Ginecologia');
