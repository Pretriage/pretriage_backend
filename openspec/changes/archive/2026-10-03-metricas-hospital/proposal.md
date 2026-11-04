# Proposal

## Why

El administrador de un hospital no tiene ninguna forma de consultar cómo funciona su hospital: no existen endpoints de métricas ni reportes, y tampoco existe una relación consultable entre una `ConsultaMedica` y el `Chat` de pretriage que permita medir adopción del chatbot (el mapping actual `ConsultaMedica.chat` apunta a `List<Mensaje>` y nunca se popula).

## What Changes

- Nuevo endpoint `GET /api/admin/hospitales/{hospitalId}/metricas?desde&hasta` con métricas del período: pacientes atendidos, espera promedio, embudo ingresó/atendidos, distribución de gravedad en arrays de orden fijo (versión bot y versión médico con bucket `SIN_REVISION`), pretriage realizado/no realizado, porcentajes precalculados y serie diaria continua (un elemento por día del rango, zero-filled) para que el frontend renderice gráficos sin lógica de recomposición.
- Autorización: solo el administrador con membresía `ADMIN_HOSPITAL` activa del hospital consultado (403 en otro hospital).
- **BREAKING (mapping)**: se reemplaza `ConsultaMedica.chat: List<Mensaje>` (mapping muerto) por `ConsultaMedica.chat: Chat` (`@OneToOne`, FK en `consulta_medica`), poblado al finalizar el pretriage con el chatbot.
- **BREAKING (relación)**: `Chat.paciente` pasa de `@OneToOne` a `@ManyToOne` — hoy un paciente solo puede crear un chat nunca en su vida (unique en `paciente_id`).
- `iniciarChat` siempre crea un chat nuevo (cerrando los chats abiertos previos del paciente) y valida que no exista un chat vinculado a una consulta cuya `EntradaCola` esté en estado distinto de `FINALIZADA`/`CANCELADA`; si existe, responde 400 con una nueva excepción y un mensaje apto para el paciente.
- La métrica "pretriage realizado" se define como `ConsultaMedica.chat IS NOT NULL` (sí = chatbot finalizado; recepción y chats abortados = no).

## Capabilities

### New Capabilities
- `hospital-metrics`: consulta de métricas periódicas del hospital por parte de su administrador (embudo de cola, espera, gravedad, adopción de pretriage).
- `pretriage-chat-link`: relación consultable `ConsultaMedica` ↔ `Chat`, vida múltiple de chats por paciente (siempre chat nuevo) y bloqueo de `iniciarChat` cuando hay una atención pendiente.

### Modified Capabilities

<!-- Ninguna: openspec/specs/ está vacío, no hay capacidades existentes. -->

## Impact

- **Entidades**: `model/consultas/ConsultaMedica.java`, `model/chat/Chat.java`.
- **Servicios**: `AtencionHospitalService` (firma de `finalizarTriageEIngresarACola`), `ChatService` (`iniciarChat`, `enviarMensaje`), nuevo `MetricasHospitalService`.
- **Nuevo**: `MetricasHospitalController`, DTOs de respuesta, queries con date-range en `RepoEntradasCola`, `RepoAtencionesMedicas`, `RepoConsultasMedicas` (hoy no existe ninguna), nueva excepción de validación de `iniciarChat` (400 en `GlobalExceptionHandler`).
- **Esquema**: `ddl-auto` genera `consulta_medica.id_chat` y libera el unique de `chat.paciente_id` (sin Flyway/Liquibase; con `update` el unique quedaría huérfano).
- **Tests**: `ChatServiceTest` (6 mocks de firma, cierre de chats abiertos previos y bloqueo por atención pendiente), `AtencionHospitalServiceTest`, nuevo `MetricasHospitalServiceTest`.
- **Docs**: `docs/06-api-reference.md` (nuevo comportamiento y 400 de `POST /api/chat`), `docs/02-patient-flow.md`, `docs/ai-agent-context.md`, regenerar `docs/generated/domain-model.{md,mmd}`.
