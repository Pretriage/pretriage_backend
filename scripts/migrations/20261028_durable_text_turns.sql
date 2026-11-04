-- Optional explicit migration for deployments managing schema outside Hibernate.
-- Existing conversations remain idle; this migration schedules no provider work.
BEGIN;
ALTER TABLE chat ADD COLUMN IF NOT EXISTS estado_turno_texto varchar(255) DEFAULT 'SIN_SOLICITAR';
ALTER TABLE chat ADD COLUMN IF NOT EXISTS solicitud_turno_texto_id varchar(255);
ALTER TABLE chat ADD COLUMN IF NOT EXISTS intentos_turno_texto integer DEFAULT 0;
ALTER TABLE chat ADD COLUMN IF NOT EXISTS turno_texto_en_curso boolean DEFAULT false;
ALTER TABLE chat ADD COLUMN IF NOT EXISTS error_turno_texto varchar(255);
ALTER TABLE chat ADD COLUMN IF NOT EXISTS turno_texto_iniciado_en timestamp(6);
ALTER TABLE mensaje ADD COLUMN IF NOT EXISTS solicitud_id varchar(36);
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_mensaje_chat_solicitud'
                   AND conrelid = 'mensaje'::regclass) THEN
        ALTER TABLE mensaje ADD CONSTRAINT uk_mensaje_chat_solicitud UNIQUE (chat_id, solicitud_id);
    END IF;
END $$;
COMMIT;
