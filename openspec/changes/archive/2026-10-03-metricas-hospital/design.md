# Design

## Context

Ver proposal.md - Why. Contexto técnico relevante:

- No existen endpoints de métricas ni queries con date-range en ningún repositorio; solo hay conteos por hospital/especialidad/estado (`RepoEntradasCola`) y `countBy...After` en tokens.
- `ConsultaMedica.chat` es un `@OneToMany` a `List<Mensaje>` que nunca se popula (`Mensaje` no tiene FK a consulta); nadie llama `getChat()` en el código.
- `Chat.paciente` es `@OneToOne` con unique en `paciente_id`: un paciente solo puede tener un chat jamás.
- El único momento donde coexisten la consulta con hospital asignado y el chat es la finalización del pretriage (`ChatService.enviarMensaje` → `AtencionHospitalService.finalizarTriageEIngresarACola`).
- No hay Flyway/Liquibase: el esquema lo genera `spring.jpa.hibernate.ddl-auto` (default `create-drop`, application.properties:12).
- Autorización imperativa existente: `StaffAccessService.exigirAdminHospital(subject, hospitalId)`.

## Goals / Non-Goals

**Goals:**
- Endpoint de métricas por período con semántica de embudo anclada en `EntradaCola.fechaHoraIngreso`.
- Contrato de respuesta exacto para el frontend: totales + porcentajes precalculados, distribuciones de orden fijo y serie diaria continua (gráficos sin lógica de recomposición).
- Vínculo consultable `ConsultaMedica` ↔ `Chat` poblado en la finalización del pretriage.
- `Chat.paciente` en `@ManyToOne` con vida múltiple de chats: `iniciarChat` siempre crea uno nuevo (cerrando los previos) y se bloquea cuando hay una atención pendiente (chat vinculado con `EntradaCola` no terminal).

**Non-Goals:**
- Comparación server-side de dos períodos (el cliente llama dos veces).
- Serie intradía (horaria) de la cola; la serie es diaria.
- Tablas materializadas/agregados precalculados; las queries corren on-the-fly.
- Corregir otras limitaciones de `Chat` (p. ej. que la cancelación cierra el chat sin vincular — se comporta como "no realizado" por diseño).
- Migración de datos históricos: consultas existentes quedan sin chat vinculado y cuentan como pretriage no realizado.

## Decisions

**D1. FK del vínculo en `consulta_medica.id_chat` (unidireccional desde `ConsultaMedica`).**
- Alternativa: FK en `chat.id_consulta_medica` (bidireccional). Descartada: `Chat` puede existir sin consulta (chat iniciado antes de elegir hospital) y la métrica se lee desde la consulta; poner el FK del lado consulta evita joins inversos en todos los conteos.
- `@OneToOne` con unique: dos consultas no pueden compartir chat.

**D2. Vincular solo en la finalización del pretriage.**
- Alternativa: vincular en `iniciarChat` o en `seleccionarHospital`. Técnicamente viable: la `ConsultaMedica` ya existe cuando arranca el chat (se crea en `seleccionarHospital`, antes del chat, `docs/02-patient-flow.md` pasos 4 y 7), por lo que el vínculo podría establecerse en cualquiera de esos momentos. Descartada por la métrica: "hizo pretriage" = el bot cerró el chat y lo metió en la cola con su prioridad; vincular al seleccionar hospital o al iniciar el chat contaría como realizado a quien solo eligió hospital o abandonó el chat a mitad de camino. `chat IS NOT NULL` queda equivalente a esa definición sin heurísticas sobre `resumenPretriageJson` (que también lo escribe recepción).

**D3. Firma de `finalizarTriageEIngresarACola` ampliada con `Chat`, manteniendo overloads.**
- Alternativa: resolver el chat dentro del servicio por `paciente`. Descartada: requiere nueva dependencia `RepoChat` en `AtencionHospitalService` y lógica de búsqueda; pasar el objeto ya en mano es explícito y testeable. Overloads de 2/3 args siguen funcionando con `chat = null` (uso en tests existentes).

**D4. `iniciarChat` siempre crea un chat nuevo, cierra los previos y valida atención pendiente.**
- Reutilizar el chat abierto se descartó (decisión del usuario): cada inicio de chat debe producir un chat nuevo. Para conservar el invariante de "un solo chat abierto por paciente" que asume `EsperaPacienteService.finalizarChatAbierto` (`findFirstByPacienteUsuarioAuthIdAndFinalizadoFalse`), `iniciarChat` marca como finalizados los chats abiertos previos del paciente antes de crear el nuevo.
- Validación previa: si el paciente tiene una `ConsultaMedica` con chat vinculado cuya `EntradaCola` (query `RepoEntradasCola.findByConsultaMedicaId`) esté en estado ∉ {`FINALIZADA`, `CANCELADA`}, se lanza una excepción nueva mapeada a 400 en `GlobalExceptionHandler` con un mensaje apto para el paciente (lo lee el usuario final), y no se crea el chat. La validación corre antes de cerrar los chats previos para no dejar efectos secundarios cuando falla.
- El check se ancla al vínculo chat↔consulta y no a cualquier `EntradaCola` activa: la `EntradaCola` se crea al seleccionar el hospital (`seleccionarHospital` → `ingresar`), antes de que arranque el chat (`docs/02-patient-flow.md`, pasos 6–7); bloquear por cualquier entrada activa impediría iniciar el chat en el flujo normal. Las admisiones de recepción sin chat y los chats abiertos a mitad de conversación no bloquean.
- Cerrar los chats abiertos previos asume que un cliente que aún conserva el id de un chat cerrado recibirá `ChatFinalizadoException` al enviar mensajes; el cliente debe iniciar un chat nuevo.

**D5. Embudo: todas las métricas ancladas en `EntradaCola.fechaHoraIngreso` del hospital en el rango.**
- Alternativa: anclar atendidos en `AtencionMedica.fechaHoraFin`. Descartada (decisión del usuario): permite ver drop-off real (cancelados/no atendidos) y comparar períodos de forma consistente; con anclas mixtas "atendidos" podía superar a "ingresados".

**D6. Espera promedio calculada en el servicio, no en JPQL.**
- `AVG(fechaHoraInicio - fechaHoraIngreso)` no es portable en JPQL (aritmética de fechas varía por dialecto). Se proyectan los pares de timestamps de las atenciones del embudo y se promedia en Java. Volumen por hospital/período es acotado; alternativa (query nativa) descartada por acoplamiento al dialecto.

**D7. `distribucionGravedadMedico` con bucket `SIN_REVISION`.**
- `nivelDeGravedadMedico` solo se setea al revisar la prioridad durante la atención; pacientes aún en cola o no atendidos lo tienen `null`. Bucket explícito mantiene el cierre del total con `ingresaronACola`.

**D8. Nuevo `MetricasHospitalController` + `MetricasHospitalService`.**
- Sigue el patrón "un controller por flujo" del proyecto; `HospitalConfigurationController` es configuración, no métricas. El service llama `exigirAdminHospital` como primer paso (mismo patrón que `HospitalConfigurationService`).

**D9. Parámetros de período como `LocalDate` inclusive.**
- `desde`/`hasta` como fechas (no date-times): la historia habla de "períodos" (días/meses). Se convierten a rango `[desde 00:00, hasta+1 00:00)` en el servicio. `desde > hasta` → `IllegalArgumentException` (ya mapeada a 400 en `GlobalExceptionHandler`).

**D10. `MetricasHospitalResponse` con estructura aniada y porcentajes precalculados.**
- Estructura aniada: `DistribucionNivelItem {nivel, cantidad, porcentaje}` y `SerieDiariaItem {fecha, ingresados, atendidos, esperaPromedioMinutos}`; los campos escalar son `hospitalId`, `desde`, `hasta`, `ingresaronACola`, `pacientesAtendidos`, `pacientesNoAtendidos`, `esperaPromedioMinutos`, `porcentajeAtendidos`, `pretriageRealizado`, `pretriageNoRealizado`, `porcentajePretriageRealizado`.
- Tipos: `desde`/`hasta`/`fecha` como `LocalDate` (Jackson los serializa `yyyy-MM-dd`); conteos `long`; espera y porcentajes `Double` redondeados a 1 decimal en el servicio; null cuando el denominador es 0 (espera promedio → null si no hay atendidos; porcentajes → null si `ingresaronACola = 0`).
- Ambas distribuciones SIEMPRE vienen completas: bot con los 5 valores de `NivelDeGravedad` en orden de declaración, médico con esos 5 más `SIN_REVISION` al final; el frontend itera sin manejar claves faltantes ni orden.
- Alternativas descartadas: mapa plano `{nivel: cantidad}` (el frontend debe manejar claves faltantes y orden arbitrario) y dejar los porcentajes al frontend (decisión del usuario: el backend devuelve conteos + porcentajes listos para renderizar, evita duplicar lógica de redondeo).

**D11. Serie diaria armada en Java desde los timestamps del embudo, con zero-fill.**
- La serie agrupa por el calendario de `EntradaCola.fechaHoraIngreso` (misma ancla que D5): `ingresados` = ingresos de ese día; `atendidos` = entre esos, los que finalizaron la atención. Así `sum(serieDiaria.ingresados) = ingresaronACola` y `sum(serieDiaria.atendidos) = pacientesAtendidos` cierran por construcción.
- Se reutilizan las proyecciones de timestamps de D6 (ingresos y atenciones del embudo) y se agrupa en Java, zero-filling cada día `[desde..hasta]` inclusive (un período de un año = 365 elementos, despreciable). La espera del día es el promedio de `fechaHoraInicio - fechaHoraIngreso` de los atendidos que ingresaron ese día; null si ese día no hubo atendidos.
- Alternativa descartada: `GROUP BY FUNCTION('DATE', ...)` en JPQL, por el mismo motivo que D6 (agrupación de fechas dependiente del dialecto).

## Risks / Trade-offs

- [Entorno con `ddl-auto=update`] el unique de `chat.paciente_id` queda huérfano y rompería el segundo chat → verificar el `JPA_DDL_AUTO` de cada entorno antes de desplegar; drop manual del índice si aplica.
- [Datos históricos sin vínculo] todas las consultas previas cuentan como pretriage no realizado → aceptado; la métrica es válida desde la implementación en adelante. Si se necesitara histórico, se podría backfill-eando `resumenPretriageJson` con la ausencia de `AdmisionRecepcion` (fuera de alcance).
- [Queries on-the-fly sin índices de fecha] en tablas grandes el período amplio puede ser lento → mitigación inicial: ninguna (volumen actual del proyecto es chico); si crece, agregar índices en `entrada_cola.fecha_hora_ingreso` y `atencion_medica.fecha_hora_fin`.
- [Firma de `finalizarTriageEIngresarACola` cambia] tests con mocks de 3 args fallarán → actualización mecánica de los 6 mocks en `ChatServiceTest`.
- [`POST /api/chat` puede responder 400] el cliente debe manejar el error de atención pendiente y no asumir que siempre recibe un chat → documentar en `docs/06-api-reference.md`.
- [Cerrar chats abiertos previos] un cliente que aún conserva el id de un chat abierto recibirá `ChatFinalizadoException` al enviarle mensajes → aceptado; debe iniciar un chat nuevo.
- [Embrudo y "período fresco"] pacientes que ingresaron al final del período aún no atendidos cuentan como no atendidos → es inherente a la semántica de embudo elegida; se aclara en docs.
- [Serie diaria armada en Java] traer todos los timestamps de ingresos/atenciones del período a memoria crece con el tamaño del rango → volumen actual chico; si crece, migrar a query nativa con `GROUP BY` de fecha o agregados precalculados.

## Migration Plan

Sin migración de datos. Deploy: compilar, verificar `JPA_DDL_AUTO` del entorno, regenerar diagrama de dominio. Rollback: revert del cambio (el esquema se regenera en `create-drop`; en entornos con `update` la columna `id_chat` queda inofensiva).

## Open Questions

<!-- Ninguna: embudo, población, formato de fechas, vínculo y contrato de respuesta (estructura aniada, redondeos, nullability, distribuciones de orden fijo y serie diaria continua) están decididos. -->
