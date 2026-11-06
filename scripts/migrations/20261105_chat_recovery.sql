-- Apply before starting the new backend over an existing PostgreSQL schema.
-- Idempotent and additive. It does not reopen or process historical conversations.
BEGIN;
ALTER TABLE chat ADD COLUMN IF NOT EXISTS cierre_parcial boolean DEFAULT false;
ALTER TABLE chat ADD COLUMN IF NOT EXISTS estado_recuperacion varchar(255) DEFAULT 'SIN_RECUPERACION';
ALTER TABLE chat ADD COLUMN IF NOT EXISTS proximo_reintento_en timestamp(6);
ALTER TABLE chat ADD COLUMN IF NOT EXISTS version_estado bigint DEFAULT 0;
ALTER TABLE chat ADD COLUMN IF NOT EXISTS fallos_internos_turno_texto integer DEFAULT 0;
ALTER TABLE chat ADD COLUMN IF NOT EXISTS fallos_internos_clasificacion integer DEFAULT 0;
ALTER TABLE chat ADD COLUMN IF NOT EXISTS fallos_disponibilidad integer DEFAULT 0;
ALTER TABLE chat ADD COLUMN IF NOT EXISTS clasificacion_iniciada_en timestamp(6);
ALTER TABLE chat ADD COLUMN IF NOT EXISTS consulta_origen_id bigint;
ALTER TABLE chat ADD COLUMN IF NOT EXISTS recuperacion_legacy_aplicada boolean DEFAULT false;
ALTER TABLE chat DROP CONSTRAINT IF EXISTS chat_estado_clasificacion_check;
ALTER TABLE chat ADD CONSTRAINT chat_estado_clasificacion_check CHECK
    (estado_clasificacion IN ('NO_SOLICITADA', 'PENDIENTE', 'COMPLETADA', 'FALLIDA', 'PARCIAL'));
CREATE INDEX IF NOT EXISTS idx_chat_recovery_due ON chat (proximo_reintento_en, id)
    WHERE estado_turno_texto IN ('PENDIENTE', 'NO_DISPONIBLE') OR recuperacion_automatica_clasificacion = true;
COMMIT;
