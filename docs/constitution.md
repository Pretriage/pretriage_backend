# Constitución del Proyecto Pretriage

1. **Stack simple**: Java 21 + Spring Boot + Spring Data JPA + PostgreSQL; no se agregan frameworks ni servicios nuevos sin justificación en `docs/`.
2. **La spec manda al código**: todo cambio pasa por OpenSpec (`openspec/changes/`); código y `docs/` se actualizan en la misma tarea; nada queda obsoleto.
3. **Lógica separada de la interfaz**: las reglas de negocio viven en services/dominio; controllers y DTOs solo traducen HTTP, sin lógica.
4. **Tests obligatorios**: `./mvnw.cmd test -DskipTests` compila y los tests afectados pasan antes de dar por terminado un cambio; cola/estimación exigen sus tests focalizados.
5. **Persistencia sin atajos**: `EntradaCola` es la única fuente de verdad de la cola; prohibido persistir tiempos estimados como verdad final.
6. **Español consistente**: identificadores, mensajes, logs y documentación en español (términos técnicos en inglés permitidos); artefactos OpenSpec en español con keywords estructurales en inglés.
