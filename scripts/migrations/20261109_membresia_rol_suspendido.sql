-- Aplicar antes de iniciar la nueva versión sobre un esquema PostgreSQL existente.
BEGIN;

CREATE TABLE IF NOT EXISTS membresia_hospital_rol_suspendido (
    membresia_id bigint NOT NULL REFERENCES membresia_hospital (id),
    rol varchar(255) NOT NULL CHECK (rol IN ('ADMIN_HOSPITAL', 'MEDICO', 'RECEPCIONISTA')),
    PRIMARY KEY (membresia_id, rol)
);

COMMIT;
