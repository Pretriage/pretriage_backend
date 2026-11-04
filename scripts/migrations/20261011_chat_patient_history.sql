-- Run once on existing PostgreSQL databases after changing Chat.paciente to ManyToOne.
-- Safe to repeat. Only removes the old single-patient uniqueness; keeps FK/history.
BEGIN;
SET LOCAL lock_timeout = '5s';
DO $$
DECLARE
    old_constraint record;
    patient_column smallint;
BEGIN
    SELECT attnum INTO STRICT patient_column
    FROM pg_attribute WHERE attrelid='public.chat'::regclass
      AND attname='paciente_id' AND NOT attisdropped;
    FOR old_constraint IN
        SELECT conname FROM pg_constraint
        WHERE conrelid='public.chat'::regclass AND contype='u'
          AND conkey=ARRAY[patient_column]::smallint[]
    LOOP
        EXECUTE format('ALTER TABLE public.chat DROP CONSTRAINT %I', old_constraint.conname);
    END LOOP;
END $$;
CREATE INDEX IF NOT EXISTS idx_chat_paciente ON public.chat (paciente_id);
COMMIT;
