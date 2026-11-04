-- Optional explicit migration for deployments managing schema outside Hibernate.
-- Legacy chats are not enrolled in automatic processing by this migration.
BEGIN;
ALTER TABLE chat ADD COLUMN IF NOT EXISTS intentos_clasificacion_automaticos integer DEFAULT 0;
ALTER TABLE chat ADD COLUMN IF NOT EXISTS recuperacion_automatica_clasificacion boolean DEFAULT false;
ALTER TABLE chat ADD COLUMN IF NOT EXISTS clasificacion_automatica_en_curso boolean DEFAULT false;
ALTER TABLE chat ADD COLUMN IF NOT EXISTS clasificacion_automatica_voz boolean DEFAULT false;
ALTER TABLE chat ALTER COLUMN intentos_clasificacion_automaticos SET DEFAULT 0;
ALTER TABLE chat ALTER COLUMN recuperacion_automatica_clasificacion SET DEFAULT false;
ALTER TABLE chat ALTER COLUMN clasificacion_automatica_en_curso SET DEFAULT false;
ALTER TABLE chat ALTER COLUMN clasificacion_automatica_voz SET DEFAULT false;
COMMIT;
