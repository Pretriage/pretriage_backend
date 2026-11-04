# Pretriage Backend Overview

Pretriage backend manages the first medical attention flow before a patient is seen by a doctor. The system combines patient self-service, AI-assisted triage, hospital and specialty selection, hospital queues, doctor sessions, and estimated attention time.

## Scope

The current focus is first attention only. The system does not model referral, diagnosis, treatment follow-up, or post-consultation derivation.

## Main Actors

- Patient: selects specialty and hospital, completes AI triage, enters queue, checks state and estimated attention time.
- Doctor: starts an attention session at an assigned hospital/specialty/sector/room, calls patients, marks absences, pauses or closes the session.
- Hospital admin: manages scoped staff memberships and invitations, and hospital configuration (enabled specialties, sectors and rooms). Assignment administration remains incremental.
- AI triage bot: collects symptoms and produces structured triage output used to assign priority.

## Core Modules

- Authentication: Auth0 login/register integration with refresh-token rotation via `POST /api/renovar` (`AuthController.renovar`, `AuthService.renovarTokenUsuario`, `RefreshTokenRequest`/`LoginResponseDTO`, `RefreshTokenInvalidoException` -> `401`; `offline_access` scope; public endpoint in `SpringSecurityConfig`). Password reset is supported via token flow (`CambioContraseniaService`, `CambioContraseniaToken`, `TokenService`, `PasswordResetEmailPort`; public `POST /api/auth/cambio-contrasenia/solicitar-token`, `GET /api/auth/cambio-contrasenia/validar?token=`, `POST /api/auth/cambio-contrasenia`; generic `200` response for privacy, configurable expiry/limit, `INVALIDADO` for superseded tokens, Auth0 `PATCH /api/v2/users/{id}` + best-effort grant revocation). Logged-in users can also rotate their password via authenticated change (`POST /api/auth/cambio-contrasenia/actual`, NOT public; `CambioContraseniaAutenticadoService`, `BloqueoCambioContrasenia`; strength rules 8-32 code points + upper + lower + digit/special declared with standard Bean Validation annotations on `CambiarContraseniaAutenticadaRequest` (`@NotBlank` + `@Pattern` per rule) and enforced with `@Valid` in the controller, so strength failures return `400` without counting as a failed attempt; current password verified against Auth0 via `password-realm`, shared M2M save `AuthService.guardarContraseniaEnAuth0`; failed attempts reaching the change logic are persisted per user and after `pretriage.cambio-contrasenia-autenticado.max-intentos` (3) the endpoint is locked for `pretriage.cambio-contrasenia-autenticado.horas-bloqueo` (24 = 1 day) with `423 Locked` including remaining minutes; JWT subject without local `UsuarioAuth` -> generic `500` + WARN log; the lock affects only this endpoint).
- Hospital selection: filters hospitals by selected specialty and distance; can order/filter by estimated attention time and shows only hospitals available for attention (with active doctors), displaying the estimated wait alongside each hospital.
- Medical specialties and sectors: `EspecialidadMedica` and `Sector` (`Sector` groups rooms per hospital+specialty, multiple sectors per specialty, `POST/PUT/DELETE /api/admin/hospitales/{hospitalId}/configuracion/sectores` with `activa`, `PUT`/`DELETE` blocked if salas have patients, `DELETE` also blocked if `ACTIVA/PAUSADA` sessions, included in `GET /configuracion`). Rooms are created/updated/activated under `.../configuracion/sectores/{sectorId}/salas` and disabling a specialty is hospital-scoped (blocked by any active room of that specialty in the hospital, including rooms without sector) via `DELETE .../especialidades/{especialidadId}`.
- AI triage chat: creates a chat, stores patient and bot messages, stores structured triage JSON. An optional speech-to-speech interview (`WS /api/chat/{id}/voz`, Gemini Live) collects the symptoms by voice and hands the summary to Gemini REST for the preclassification.
- Queue management: uses `EntradaCola` as queue state per hospital/specialty/sector; each consultation is assigned a sector (`AsignacionSectorService`) before entering its `GestorDeCola`.
- Doctor attention: uses SesionAtencionMedica, rooms, assignments, and historical AtencionMedica records. The hospital-admin sector-session list exposes only `ACTIVA`/`PAUSADA`; finalized sessions remain in the database but are not returned by that list.
- Reception admission: supports DNI-based in-person registration, structured form triage, and entry into the same dynamic queue without using chat.
- Estimated attention time: recalculated dynamically using queue state and active doctor sessions.
- Real-time estimation: authenticated SSE subscriptions support periodic updates, heartbeat, and multiple connections per consultation.
- Medical studies management: patients can upload, list, download, and delete medical study files (PDFs, images) stored in AWS S3. Doctors can access patient studies during attention through clinical history endpoints.
- Health insurance credentials: patients and receptionists can load, list, update, and delete credentials. Load and update validate the health insurance at runtime selecting the validator for the requested obra social (`FabricaValidadoresCredencialesObraSocial`). Only a demo mock (`OSDE`) exists; real integrations with the health insurance companies are out of scope and can be plugged in by implementing `ValidadorCredencialObraSocial`.

## Current Technical Stack

- Java 21
- Spring Boot
- Spring Data JPA
- PostgreSQL through Docker Compose
- Spring Security OAuth2 Resource Server
- Gemini REST structured output (`gemini-3.5-flash-lite`, configurable)
- Spring WebSocket + Gemini Live (`gemini-3.8-live`) for the optional voice chat

The [voice interaction guide](14-chat-voz-gemini-live.md) explains the mobile,
backend and Gemini Live responsibilities and the complete message sequence.
The [interview policy](17-simple-chat-questions.md) defines symptom quantification
and when text and voice stop asking questions. [Gemini REST retries](19-gemini-retries.md) documents
transient failure recovery, configuration and shared time limits.
The [chat hardening plan and implementation](20-chat-hardening.md) describes scope
redirection, clarification of incoherent input, patient evidence checks, voice
closure controls and reproducible adversarial cases.
