# AI Agent Context

This file gives future AI agents enough context to work on the project without rediscovering the domain from scratch.

## Chat language policy

Patient-facing messages/audio do not announce preclassification or a priority calculation.
Use a brief acknowledgement for the medical team; retain urgent-care warnings. Switching
to Live with only the initial greeting uses `Te escucho` instead of repeating that greeting;
the first bot transcription is suppressed in events and persistence when the greeting
already exists. Subsequent patient/bot turns are unaffected. Mobile labels describe
registration of answers and attention, while internal clinical results remain unchanged.

Gemini REST output defaults to 8192 tokens, configurable with
`GEMINI_MAX_OUTPUT_TOKENS` (1-65536). Non-`STOP` responses are rejected and logged
with an allowlisted finish reason plus numeric usage counters only. Do not accept
partial JSON or bypass safety stops. The generic historical error does not prove
token exhaustion; the chat 17 equivalent completed successfully at both limits.

`LenguajeClinicoChat` shares spelling and ambiguity instructions between text, corrective
questions, Live interviews and final voice classification. Preserve raw messages;
normalize only unambiguous wording in structured results. Unknown/ambiguous terms require
neutral clarification; unresolved expressions remain literal quotes in observations,
not invented clinical facts. Never guess medication names/doses or diagnoses. Regional
`moratón` is canonicalized to `moretón` in result motive/symptom/alarm text only; other
clinical fields and priority are unchanged. Semantic checking is model guidance, not a
validated medical dictionary. See [clinical language normalization](05-ai-triage.md#clinical-language-normalization).

## Core Intent

The system manages the first medical attention workflow:

1. Patient chooses medical specialty.
2. Patient chooses hospital that supports that specialty, is assigned to a sector, and enters the hospital/specialty/sector queue with default priority.
3. Optional AI triage collects symptoms and assigns priority, updating the queue priority.
4. Doctors start sessions in rooms and call patients.
5. Estimated attention time is recalculated dynamically.

## High Risk Areas

- Queue state and estimation.
- Doctor session concurrency.
- Patient absence and delayed return rules.
- AI triage priority mapping.
- Auth0 token handling in local E2E scripts.
- Auth refresh with rotation (`AuthService.renovarTokenUsuario`, `RefreshTokenRequest`, `RefreshTokenInvalidoException` -> `401`).

## Auth

- `AuthController` (`/api/register`, `/api/login`, `/api/renovar`): `renovar` is public (`SpringSecurityConfig:34`), validates `@NotBlank refreshToken`, delegates to `AuthService.renovarTokenUsuario` (`grant_type=refresh_token`).
- `AuthService`: `obtenerTokenParaLogearUsuario` requests `offline_access` to obtain rotating `refresh_token`; `renovarTokenUsuario` proxies to Auth0 `/oauth/token` and maps `AuthIdTokenResponse` -> `LoginResponseDTO` (`token`, `refreshToken`, `renovarTokenEn`). Rotation invalidates previous refresh; `invalid_grant`/`invalid_request` maps to `RefreshTokenInvalidoException` (`GlobalExceptionHandler` `401`). Stateless, no DB persistence for refresh.
- Invariants: frontend must replace stored `refreshToken` on each renovar; on `401` force re-login. Keep `LoginResponseDTO.renovarTokenEn` from `expires_in`.
- Password reset: `CambioContraseniaController` (`POST /api/auth/cambio-contrasenia/solicitar-token` `SolicitarTokenCambioContraseniaRequest`, `GET /api/auth/cambio-contrasenia/validar?token=`, `POST /api/auth/cambio-contrasenia` `CambiarContraseniaRequest`) all public (`SpringSecurityConfig`). `CambioContraseniaService` owns `obtenerTokenCambioContraseña` (always `200` generic for privacy, `existsByCorreoElectronicoIgnoreCase` + rate limit `countByUsuarioIdAndFechaHoraCreacionAfter` vs `pretriage.cambio-contrasenia.max-solicitudes-por-hora/ventana-horas`, invalidate prior `PENDIENTE` -> `INVALIDADO`, `TokenService.generarToken` SecureRandom 32B base64url, `PasswordResetEmailPort` smtp/local), `validarTokenCambioContrasenia` (`expiro()` -> `EXPIRO`), `cambiarContraseña` (`PATCH /api/v2/users/{auth0Id}` with M2M `client_credentials` via shared `AuthService.guardarContraseniaEnAuth0`. Entities: `CambioContraseniaToken` extends `RegistroCambioContrasenia` (`@MappedSuperclass`: `id`, `@ManyToOne UsuarioAuth`, `fechaHoraCreacion`; token adds `token unique plain`, `fechaHoraExpiracion`, `EstadoCambioContrasenia PENDIENTE/CAMBIADO/EXPIRO/INVALIDADO`) in `UsuarioAuth.cambiosDeContrasenia`. Exceptions: `TokenCambioContraseniaInvalidoException` `400`, `LimiteSolicitudesCambioContraseniaException` `429`, `NoSePudoCambiarContraseniaException` `400`.
- Authenticated password change: `POST /api/auth/cambio-contrasenia/actual` (NOT public; `@AuthenticationPrincipal Jwt`, subject = `UsuarioAuth.id`). The controller marks the request with `@Valid`: strength rules are declared with standard Bean Validation annotations on `CambiarContraseniaAutenticadaRequest` (`@NotBlank` on both fields + one `@Pattern` per rule on the new password: 8-32 code points, upper `\p{Lu}`, lower `\p{Ll}`, digit-or-special where special = not letter/digit/space); a strength failure responds `400` from the validation layer BEFORE the service runs and does NOT count as a failed attempt. `CambioContraseniaAutenticadoService.cambiarContrasenia(auth0Id, CambiarContraseniaAutenticadaRequest)` flow: resolve `UsuarioAuth` by subject (missing -> `UsuarioLocalNoExisteException` `500` generic + WARN about Auth0/local divergence) -> check `BloqueoCambioContrasenia` (`estaBloqueado(now)` -> `CambioContraseniaBloqueadoException` `423` with remaining minutes; expired lock resets counter to 0) -> reject equal new/current -> verify current via `AuthService.obtenerTokenParaLogearUsuario` (password-realm, token discarded) -> save via `AuthService.guardarContraseniaEnAuth0`. Every attempt that reaches the change logic and does not succeed increments `intentosFallidos` (persisted in `bloqueo_cambio_contrasenia`, one row per user, `RepoBloqueoCambioContrasenia.findByUsuarioId`); at `pretriage.cambio-contrasenia-autenticado.max-intentos` (3) sets `bloqueadoHasta = now + horas-bloqueo` (`pretriage.cambio-contrasenia-autenticado.horas-bloqueo`, 24 = 1 day). Success resets the counter. No `@Transactional` on the method so failed attempts survive the thrown exception. The lock affects only this endpoint (not login/other endpoints nor the token flow).

## Optional Integrations

- Amazon S3 is disabled by default through
  `pretriage.storage.s3.enabled=false`.
- Local startup must not require AWS credentials.
- Set `PRETRIAGE_STORAGE_S3_ENABLED=true` together with `AWS_S3_REGION`,
  `AWS_ACCESS_KEY_ID`, and `AWS_SECRET_ACCESS_KEY` only when clinical-study file
  downloads are required.

## Source Files By Concern

### AI Chat

- `ChatService`
- `TriageIaClient` (provider-native chat schema, nullable unreported pain and
  unfinished priority, explicit output validation)
- `ChatBotController`
- `Chat`
- `Mensaje`
- `TriageResultDTO`

- Voice: `SesionVozChat`, `GeminiLiveCliente`, `GeminiLiveProperties`
  (`services/voz`), `ChatVozWebSocketHandler`, `ChatVozHandshakeInterceptor`,
  `WebSocketConfig`; `ChatService.finalizarEntrevistaVoz` /
  `registrarTurnosVoz`. Gemini Live conducts the voice interview with a prompt
  sharing `LenguajeClinicoChat` interview rules and closes it via `finalizar_entrevista`
  (`ResumenEntrevistaVoz`); priority is never assigned by the Live interview model: Gemini REST
  classifies the summary + transcription after the Gemini session ends, with the
  validated output contract and no local replacement on provider failure.
  `ValidadorCierreEntrevistaVoz` gates closure in both the session and the service
  using saved plus pending patient turns. Never trust an empty summary or use it
  to invent sufficient interview evidence. The classification prompt gives literal patient statements precedence over
  conflicting summary fields. Classify only after normal Gemini transport
  closure; incomplete closure preserves the transcript for continuation.

Successful new chat responses expose `origenRespuesta=GEMINI`; final
`Chat.resultadoTriageJson` also stores `origenClasificacion`. Intermediate text
turns request only `mensaje`, with at most one remote correction for an incompatible
intermediate response or repeated question. Gemini is instructed to ask about one datum per turn,
and `LenguajeClinicoChat.REGLAS_PREGUNTA_TEXTO` excludes closure instructions from that task.
Question-mark count and punctuation do not invalidate the response or trigger a
format correction; preserve Gemini's returned message. Gemini supplies questions
and the backend applies shared `ProgresoEntrevista`: objectives per reported symptom
and datum, one open invitation, and terminal data states (answered, unknown, declined
or attempts exhausted). Independent budgets are 24 total patient turns, three
attempts per datum and four replies without progress; budget closure is partial.
Each turn
covers one concept; onset/duration and impact are limited to reported symptoms, pain
may use 0–10 or daily impact, and temperature is requested only when measured.
Unknown or unmeasured values stay unknown without repetition, and facts are never
carried from one symptom to another. «No tengo otros síntomas» stops the search for
new symptoms but permits bounded quantification of those already reported; «no quiero
seguir» ends the interview. Do not repeat the open invitation. A clear alarm closes
immediately, and unreported alarm signs remain unknown. See
[interview policy](17-simple-chat-questions.md).
See [resolved-data closure](21-cierre-por-datos-resueltos.md) for resource accounting
and `seguimientoEntrevista` stored-result metadata. Excluded messages count locally
for resource protection but are not resent to Gemini or accepted as clinical facts.
A terminal `CIERRE_SIN_DATOS` response has no classification or queue update;
clients synchronize the closed chat and must not infer COMPLETADA from closure.
Complete interviews request final classification and reject a provider response
that keeps asking questions. Chat never generates local questions or classification.
Final model JSON/schema errors can request one bounded correction. Current recovery
uses [the durable policy](34-automatic-chat-recovery.md): internal generation or
validation failures use up to two workflow executions per phase; typed transient API
outages use persisted backoff until recovery or consultation invalidation. Historical
details remain in [Gemini REST retries](19-gemini-retries.md). Do not automatically
resend mobile POST messages or replay Live audio. Unfinished text-turn failures remove
the undelivered patient message; accepted closure preserves answers and attempts
classification from the available facts. If no valid result is obtained, the chat
closes as `PARCIAL` with no AI priority. New work does not end in `FALLIDA`; the
legacy `POST /api/chat/{id}/clasificacion` retains handling for historical `FALLIDA`
chats only. Both modalities share final-result validation. See
[server-owned closure](23-server-owned-voice-closure.md) and the current recovery
contract.
Reception form fallback remains active in `AdmisionRecepcionService`. Preserve
valid final AI classifications instead of overwriting them with local rules.
Immediate-attention final messages must include the result's safety recommendation.
The real chat E2E must verify final origin, structured content and queue priority;
an `EN_COLA` state alone does not establish successful AI classification.

Chat lifecycle and consultation link:

- `Chat.paciente` is `@ManyToOne`: a patient can have many chats over time.
- `POST /api/chat` (`ChatService.iniciarChat`) always creates a new chat. First it
  rejects with `400 AtencionPendienteException` (patient-facing message) when a
  `ConsultaMedica` of the patient has a linked chat and its `EntradaCola`
  (`findByConsultaMedicaId`) is not `FINALIZADA`/`CANCELADA`; then it closes the
  patient's previous open chats (`RepoChat.findAllByPacienteUsuarioAuthIdAndFinalizadoFalse`),
  keeping at most one open chat. The validation runs before closing, so a rejection
  has no side effects.
- `ConsultaMedica.chat` (`@OneToOne`, FK `consulta_medica.id_chat`, unique) links the
  chatbot interview to the existing selected-hospital consultation after either a
  valid completed classification or a partial closure. A partial closure reuses the
  consultation and queue entry; it does not create a second one. "Pretriage realizado" counts a `COMPLETADA`
  classification or a legacy null state with a non-null AI result; partial, failed,
  pending, reception-only and unlinked consultations are "no realizado". Bot severity
  metrics include `SIN_CLASIFICACION_IA` for arrivals without a valid AI classification,
  so a provisional `NORMAL` priority is not reported as an AI result.

### Hospital Metrics

- `MetricasHospitalController` — `GET /api/admin/hospitales/{hospitalId}/metricas?desde&hasta`
  (`LocalDate`, inclusive). Contract and chart mapping: `docs/06-api-reference.md#hospital-metrics-hospital-admin`.
- `MetricasHospitalService` — `StaffAccessService.exigirAdminHospital` first (`403` otherwise),
  `desde > hasta` → `400`, range `[desde 00:00, hasta+1 00:00)`.
- Queries: `RepoEntradasCola.findFechasHoraIngresoByHospitalEnRango`,
  `RepoAtencionesMedicas.findAtencionesDelEmbudo` (`AtencionEmbudoProjection`),
  `RepoConsultasMedicas.contarIngresadosPorNivelBot/Medico` (`ConteoPorNivelProjection`, `null` nivel = `SIN_REVISION`)
  and `contarIngresadosCon/SinPretriage`.
- DTOs: `MetricasHospitalResponse`, `DistribucionNivelItem`, `SerieDiariaItem` (`controllers/dtos/metricas`).
- Metrics demo data is opt-in: `pretriage.seed.metricas-demo.enabled=true` also
  requires an explicit `pretriage.seed.metricas-demo.hospital-place-id`. Never
  enable this synthetic-data runner in the application's normal datasource.
  Configuration and prerequisites: `docs/07-local-development.md`.

Contract rules: everything is anchored on `EntradaCola.fechaHoraIngreso` of the hospital in range;
`pacientesAtendidos` = those with `AtencionMedica.FINALIZADA`; wait = `fechaHoraInicio - fechaHoraIngreso`
averaged in Java (no dialect-specific JPQL date math); percentages and waits rounded to 1 decimal
and `null` when the denominator is 0; `distribucionGravedadBot` has the 5 `NivelDeGravedad`
values in declaration order plus `SIN_CLASIFICACION_IA`, and `distribucionGravedadMedico` the same 5 + `SIN_REVISION` last;
`serieDiaria` has one zero-filled item per day `desde..hasta`. Invariants:
`pacientesNoAtendidos = ingresaronACola - pacientesAtendidos`,
`pretriageRealizado + pretriageNoRealizado = ingresaronACola`, each distribution sums to
`ingresaronACola`, `sum(serieDiaria.ingresados) = ingresaronACola`,
`sum(serieDiaria.atendidos) = pacientesAtendidos`.

### Hospital And Specialty

- `AtencionHospitalService` (incl. `buscarHospitalesCercanos` with `ordenarPor` and availability filter)
- `HospitalController`
- `Hospital`
- `EspecialidadMedica`
- `Sector` (`hospital`+`especialidad` grouping, `activa` default `true`, multiple per specialty, `HospitalConfigurationService.crearSector`/`actualizarSector`/`eliminarSector` + `RepoSectores/RepoSalas/RepoConsultasMedicas/RepoSesionesAtencionMedica` checks `existsBySalaIdIn...`, `SectorHospitalResponse`/`GuardarSectorRequest`/`ActualizarSectorRequest`, listed in `GET /configuracion`, `PUT`/`DELETE` blocked if `Sala` has non-terminal `ConsultaMedica` or `ACTIVA/PAUSADA` session)
- `AsignacionSectorService` (`asignarSector` picks the active sector with the fewest `EntradaCola.EN_COLA`, ties by name ASC, requires an active room)
- `RepoSectores` (incl. `findByHospitalIdAndEspecialidadIdAndActivaTrueOrderByNombreAsc` for assignment and `findByHospitalIdAndEspecialidadCodigoAndActivaTrueOrderByNombreAsc` for the doctor flow)
- `RepoHospitales`
- `RepoEspecialidadesMedicas`
- `RepoSectores`
- `HospitalConfigurationController`/`HospitalConfigurationService` (`GET /configuracion` returns `especialidades`+`salas`+`sectores`, `POST /sectores`, `PUT /sectores/{sectorId}`, `DELETE /sectores/{sectorId}` → `204`; rooms are sector-scoped: `POST/PUT /sectores/{sectorId}/salas[/{salaId}]`, `PATCH /sectores/{sectorId}/salas/{salaId}/estado`, hospital-scoped specialty `DELETE /especialidades/{especialidadId}` (all active rooms block, including legacy rooms without a sector; no cascades), uniqueness/activa-checks via `RepoSalas.existsByHospitalIdAndSectorIdAndNombreIgnoreCase`/`existsByHospitalIdAndNombreIgnoreCaseAndSectorIdAndIdNot`/`existsByHospitalIdAndEspecialidadIdAndActivaTrue`, `SalaHospitalResponse` exposes `sectorId`/`sectorNombre`)

### Queue And Estimation

- `EstimacionAtencionService` (`calcularPara` per-patient + `calcularEsperaParaNuevaConsulta` for hospital ranking)
- `EntradaCola`
- `EstadoEntradaCola`
- `GestorDeCola` (unique per hospital+especialidad+sector)
- `RepoEntradasCola` (`countByGestorDeColaHospitalIdAndGestorDeColaEspecialidadIdAndEstado` and `countByGestorDeColaSectorIdAndEstado`)
- `RepoGestoresDeColas` (`findByHospitalIdAndEspecialidadIdAndSectorId`)
- `TiempoEstimadoAtencionResponse` + `EsperaNuevaConsultaCalculo`
 - `HospitalCercanoDTO` enriched with `pacientesEnCola`, `minutosEsperaEstimados`, `fechaHoraAtencionEstimada`, `disponible`

### Doctor Attention

- `AtencionMedicoService` (sessions are per hospital/specialty/sector/room; `iniciarSesion` uses `sectorId`, `llamarProximo` reads the sector gestor)
- `MedicoController` (`GET /api/hospitales/{hospitalId}/sectores?codigoEspecialidad=`, `GET /api/hospitales/{hospitalId}/sectores/{sectorId}/salas?codigoEspecialidad=`, `POST /api/medico/sesiones` with `sectorId`)
- `SesionAtencionMedica`
- `EstadoSesionMedica`
- `Sala`
- `AsignacionMedicoHospital`
- `RepoSesionesAtencionMedica`
- `SesionesMedicasAdminService`: sector listing includes only `ACTIVA`/`PAUSADA`, including inactive sectors and legacy room-sector associations. Closing returns a `FINALIZADA` DTO and preserves the database row, but subsequent lists exclude it; this is not a finalized-session history endpoint.
- `AtencionMedica`
- `EstadoAtencionMedica`
- `RepoAtencionesMedicas`
- `TiempoEstimadoNotifier`

### Patient Waiting State

- `EsperaPacienteService` (`ausentarme`→`pausa-manual`, `estoyAtrasado`→`atraso/confirmar`, `sigoAsistiendo`→`atraso/renovar`, `llegue`→`reincorporar`, `cancelarSeleccion`→`cancelar`)
- `PacienteEsperaController` (`@RequestMapping /api/paciente/consulta` + `GET /estado` unchanged + `POST /cola/pausa-manual`, `POST /cola/atraso/confirmar`, `POST /cola/atraso/renovar`, `POST /cola/reincorporar`, `POST /cancelar`)
- `EstadoConsultaPacienteDTO` (includes `sectorId`/`nombreSector` of the assigned sector)
- `TipoPausaCola`
- `RepoChat` (`findFirstByPacienteUsuarioAuthIdAndFinalizadoFalse` to finalize the open triage chat on cancellation)

## Invariants

- `EntradaCola` is the queue source of truth.
- A queue is scoped by hospital, specialty and sector (`GestorDeCola` unique triple; `AsignacionSectorService` assigns the sector on hospital selection and on reception admission).
- `IngresoColaService.ingresar(consulta, prioridad)` requires `consulta.getSector() != null`; the overload `ingresar(consulta, prioridad, sector)` assigns it.
- Hospital selection enters the consultation into the queue directly; the AI triage is optional and only updates the queue priority.
- Estimated attention time is dynamic and should be recalculated on every request.
- Only `EntradaCola.EN_COLA` counts for waiting estimation (both per-patient and per-hospital ranking; never `GestorDeCola.consultasEnEspera`). Per-patient estimation is scoped to the patient's sector (its gestor queue and the hospital+especialidad+sector `ACTIVA` sessions); hospital ranking estimation stays hospital+especialidad-wide.
- Doctor sessions count for capacity only when `EstadoSesionMedica.ACTIVA`.
- Nearby hospitals ranking shows only hospitals with `medicosActivos > 0` (`disponible=true`); an empty result means "no hay hospitales disponibles". Ranking wait uses end-of-queue formula `pacientesEnCola / max(medicosActivos,1) * minutosPromedioAtencion`. `ordenarPor` valid values live in `ORDENES_VALIDOS` (`distancia`, `tiempo-atencion`, `valoracion`, combinados con `|` como `distancia|tiempo-atencion|valoracion`), orden indistinto y case-sensitive; el combinado usa suma de rankings (distancia geodésica + tiempo + valoración, `null` = 0) con desempate por `nombre`. Cada hospital expone `valoracionPromedio` (1.0–5.0 o `null` si Google no tiene rating) y `cantidadValoraciones` (o `null`); el mensaje "sin hospitales valorados" lo muestra el frontend cuando todos los `valoracionPromedio` son `null`.
- Paused sessions do not count as active capacity.
- Zero active doctors still yields an estimate using one virtual doctor, but response must indicate no active doctors.
- A room cannot have two active or paused sessions at the same time.
- A doctor cannot have two active or paused sessions at the same time.
- A patient delayed after absence is not reinserted into queue until marking arrival.
- A paused session reserves doctor and room but does not count as active capacity.
- A session cannot be paused or closed while its doctor has a called or in-attention consultation.
- A doctor cannot call another patient while one is called or in attention.
- `AtencionMedica` is created on presence confirmation and finalized with the consultation.
- `EN_ESPERA` entries are cancelled after one hour measured from `fechaHoraSalidaTemporal`.
- A patient can cancel the active hospital selection at any time (`POST /api/paciente/consulta/cancelar`) while its `EntradaCola` is `EN_COLA`, `LLAMADO`, `EN_ESPERA` or `ATRASADO`; cancellation is terminal and idempotent (`CANCELADA` → no-op success).
- The cancellation endpoint deterministically resolves the patient's **most recent** `EntradaCola` (`RepoEntradasCola.findFirstByConsultaMedicaPacienteIdOrderByIdDesc`); historical `CANCELADA`/`FINALIZADA` entries from previous selections never shadow the active one.
- Cancellation marks `EntradaCola.CANCELADA` + `ConsultaMedica.CANCELADA` (clears `medico`/`sala`), excludes the entry from estimation, prevents the doctor from calling or re-seeing the patient, and finalizes an open AI triage chat.
- `EN_ATENCION` (consultation in progress) and `FINALIZADA` cannot be cancelled (`409 ConflictoDeEstadoException`).
- Reception admissions are not cancelled by the patient endpoint; they use `AdmisionRecepcionService.cancelar`.
- SSE subscriptions validate that the authenticated patient owns the consultation.

## Estimation Contract

`TiempoEstimadoAtencionResponse` keeps the original field:

- `fechaHoraAtencionEstimada`

It also exposes operational metadata:

- `hayMedicosActivos`
- `medicosActivos`
- `medicosParaEstimacion`
- `posicionEnCola`
- `pacientesAntes`
- `minutosPromedioAtencion`
- `mensaje`
- `sectorId` / `nombreSector` (assigned sector from `ConsultaMedica.sector`)

Do not remove `fechaHoraAtencionEstimada` because existing clients may depend on it.

## Verification Strategy

For queue or estimation changes:

```powershell
.\mvnw.cmd "-Dtest=AtencionHospitalServiceTest,EstimacionAtencionServiceTest" test
```

For chat lifecycle or hospital metrics changes:

```powershell
.\mvnw.cmd "-Dtest=ChatServiceTest,AtencionHospitalServiceTest,GlobalExceptionHandlerTest,MetricasHospitalServiceTest,MetricasHospitalControllerTest" test
```

`RepoMetricasHospitalTest` (date-range queries) needs Docker Desktop like the rest of the repository tests.

For chat behavior changes, run real E2E:

```powershell
python scripts\e2e_chat.py --backend-url http://localhost:18080 --db-name pretriage_chat_e2e --messages-file scripts\chat_case_example.txt --debug-log target\debug_case.json
```

For full confidence:

```powershell
python scripts/test_backend_isolated.py
```

Full tests need Docker Desktop access.

## Common Mistakes To Avoid

- Do not calculate estimated time from `GestorDeCola.consultasEnEspera`.
- Do not persist estimated attention time as final truth.
- Do not count paused doctors as active capacity.
- Do not count delayed or manually waiting patients as in queue.
- Do not add admin module assumptions yet; admin is planned but not in scope.
- Do not expose raw `.env` values in logs or docs.
- Text messages use `TextoTurnoService`: accept once with a stable UUID, return
  `202 ChatDTO`, then poll GET. Durable `estadoTurnoTexto` is independent of
  `estadoClasificacion`. Never replay POST to recover a lost response. A pending
  or unavailable text turn blocks text and voice; automatic recovery is server-owned.
  See `docs/24-durable-text-turns.md` before changing this lifecycle.

## Documentation Maintenance

Documentation updates are required in the same change as code updates. When changing entities, states, flows, endpoints, DTOs, configuration, or verification commands:

1. Update the relevant narrative files under `docs/`.
2. Regenerate the domain diagram with `python scripts/generate_domain_diagram.py` after JPA entity changes.
3. Verify API paths and state transitions against controllers and services.
4. Do not consider the task complete while documentation is stale.
## Reception-Assisted Admission

- Reception admission uses `AdmisionRecepcion`, `SesionRecepcion`, `TriageFormularioService`, and `IngresoColaService`.
- `AdmisionRecepcionService.crearAdmision` assigns the consultation's sector via `AsignacionSectorService.asignarSector` before saving.
- It does not create or use `Chat`.
- DNI is required; patients without Auth0 are valid domain patients.
- Receptionists may be assigned to multiple hospitals but can have only one active reception session.
- Reception cannot set or edit priority.
- Both digital and reception flows must enter the queue through `IngresoColaService`.
- Keep `docs/09-reception-admission.md` synchronized with backend contracts.

Nearby patient hospital candidates come only from registered DB hospitals matching the selected specialty. `GooglePlacesService.buscarHospitalesRegistrados` uses Place Details for those IDs, geodesic radius 5 km and distance sorting. Never let global Nearby Search or its 20-result limit determine the registered candidate set. Active capacity and queue ranking rules remain unchanged.
