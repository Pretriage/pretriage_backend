# Domain Model

## Patient And Consultation

`Paciente` represents either an authenticated patient profile or a reception-created patient.
`OrigenRegistroPaciente` (`APLICACION` | `RECEPCION`) records the registration source.
Reception-created patients store their identity fields directly plus `telefono`, optional
`correoElectronico`, and a structured one-to-one `Direccion`. `UsuarioAuth` is optional for
reception-created patients.

`ConsultaMedica` represents the current first-attention workflow. Important fields:

- `paciente`
- `hospital`
- `especialidad`
- `estadoConsulta`
- `nivelDeGravedadBot`
- `medico`
- `sala`
- `sector` (assigned automatically when the patient enters the queue)
- `chat` (pretriage `Chat` linked when the bot finishes the triage; `null` = pretriage not done)

Relevant states in `EstadoConsulta`:

- `PENDIENTE`
- `HOSPITAL_SELECCIONADO`
- `PRETRIAGE_EN_PROCESO`
- `PRETRIAGE_FINALIZADO`
- `EN_COLA`
- `LLAMADO`
- `EN_ESPERA`
- `ATRASADO`
- `EN_ATENCION`
- `FINALIZADA`
- `CANCELADA`

## AI Triage Chat

`Chat` represents one AI pre-triage conversation. Important fields:

- `paciente` (`@ManyToOne`: a patient can have many chats over time; `POST /api/chat` always creates a new one and closes the previous open ones)
- `mensajes`
- `fechaHoraCreacion`
- `finalizado`
- `resultadoTriageJson`
- `estadoClasificacion` (`NO_SOLICITADA`, `PENDIENTE`, `COMPLETADA`, `FALLIDA`)
- `estadoTurnoTexto` (`SIN_SOLICITAR`, `PENDIENTE`, `COMPLETADA`, `NO_DISPONIBLE`),
  `solicitudTurnoTextoId`, `intentosTurnoTexto`: durable state of the latest accepted
  text message, independent of final clinical classification.
- `turnoTextoEnCurso`, `turnoTextoIniciadoEn`, `errorTurnoTexto`: internal worker claim,
  lease timestamp and safe failure category. Legacy null state reads as `SIN_SOLICITAR`.
- `intentosClasificacionAutomaticos`, `recuperacionAutomaticaClasificacion`,
  `clasificacionAutomaticaEnCurso`, `clasificacionAutomaticaVoz`: internal durable
  recovery budget, ownership, claim and modality; legacy nulls read as 0/false.

Messages are loaded by `fechaHoraEnvio ASC, id ASC`. For the current voice closure
and final-classification recovery lifecycle, see
[server-owned closure](23-server-owned-voice-closure.md). New automatic jobs persist
`PENDIENTE` before provider work; the patient does not trigger their retries.

The structured triage result is stored as JSON in `resultadoTriageJson` and maps
to `ConsultaMedica.nivelDeGravedadBot` when the triage finishes.
When the bot finishes the triage, the patient's `ConsultaMedica` is linked to the
chat through `ConsultaMedica.chat` (`@OneToOne`, FK `consulta_medica.id_chat`, unique;
unidirectional from the consultation). A consultation without linked chat means the
chatbot pretriage was not done (reception admission, hospital selection without chat,
or a chat closed without finishing).

`Mensaje` is one chat message. Important fields:

- `contenido`
- `autor`
- `pacienteAutor`
- `fechaHoraEnvio`
- `solicitudId`: nullable UUID for patient text submissions. The unique
  `(chat_id, solicitud_id)` constraint and stored content make delayed duplicate
  requests idempotent, including after subsequent turns. Voice and legacy messages
  have no request key. See [durable text turns](24-durable-text-turns.md).

`autor` values in `AutorMensaje`:

- `BOT`
- `PACIENTE`

`ConsultaMedica.sintomasBot` is a many-to-many reference to `Sintoma`, the
catalog of symptom names identified during triage.

## Hospitals And Specialties

`EspecialidadMedica` stores a code and display name for each specialty.

`Hospital` has many specialties. A patient can select only hospitals that support the chosen specialty.

`Sector` groups rooms within a hospital for a single specialty. Important fields: `nombre` (unique per hospital, case-insensitive, `@Size(max=100)` trimmed), `activa` (`boolean` default `true`, toggled via `PUT`), `hospital` (`ManyToOne`), `especialidad` (`ManyToOne`), `salas` (`OneToMany(mappedBy=sector)`), `pacientesAsignados` (`OneToMany(mappedBy=sector)`). A specialty can have multiple sectors in the same hospital (e.g., “Clínica Médica – Ala Norte/Sur”). Sectors are managed by `ADMIN_HOSPITAL` via `HospitalConfigurationService` (`POST /sectores` `crearSector`, `PUT /sectores/{sectorId}` `actualizarSector` with `nombre`+`especialidadId`+`activa`, `DELETE /sectores/{sectorId}` `eliminarSector` → `204`) and listed in `GET /configuracion` (`ConfiguracionHospitalResponse.sectores`, `SectorHospitalResponse` with `activa`). Creation requires the specialty to be enabled for the hospital and audits `SECTOR_CREADO`; update audits `SECTOR_ACTUALIZADO` and delete audits `SECTOR_ELIMINADO` and desvincula empty `Sala`s (`sala.setSector(null)`). `PUT` is blocked with `409` if any `Sala` of the sector has `ConsultaMedica` with `estadoConsulta NOT IN (FINALIZADA,CANCELADA)` (`RepoConsultasMedicas.existsBySalaIdInAndEstadoConsultaNotIn`); `DELETE` additionally blocks if any `Sala` has `SesionAtencionMedica` `ACTIVA`/`PAUSADA` (`RepoSesionesAtencionMedica.existsBySalaIdInAndEstadoIn`). Consultation/session assignment is performed by `AsignacionSectorService` and stored in `GestorDeCola(sector)` and `ConsultaMedica.sector`.

`Sala` belongs to a hospital, a specialty and a sector (`Sala.sector` `ManyToOne`, assigned at creation). Rooms are managed under `.../configuracion/sectores/{sectorId}/salas` (`POST` `crearSala`, `PUT /salas/{salaId}` `actualizarSala`, `PATCH /salas/{salaId}/estado` `actualizarEstadoSala`); the room's specialty must match the sector's specialty and its name is unique per `hospitalId + sectorId` case-insensitive (`RepoSalas.existsByHospitalIdAndSectorIdAndNombreIgnoreCase` / `existsByHospitalIdAndNombreIgnoreCaseAndSectorIdAndIdNot`). Disabling a specialty is hospital-scoped (`DELETE .../especialidades/{especialidadId}`) and blocked with `409` if the specialty has active rooms anywhere in that hospital, including rooms without a sector (`existsByHospitalIdAndEspecialidadIdAndActivaTrue`); only the hospital-specialty association is removed. A room can be used by one doctor session at a time. `SalaHospitalResponse` exposes `sectorId` and `sectorNombre`.

## Health Insurance Credentials

`ObraSocial` is the health insurance catalog. Important fields:

- `nombre`
- `virgente`

`virgente = false` is the logical delete state used by the admin CRUD.

`Credencial` stores one patient's health insurance credential. Important fields:

- `numeroAfiliado`
- `plan`
- `fechaVencimiento`
- `obraSocial`
- `paciente`

Load and update validate the credential at runtime through
`FabricaValidadoresCredencialesObraSocial`, which resolves the validator for the
requested obra social (`ValidadorCredencialObraSocial`). Only the demo mock
(`OSDE`) exists; real integrations are out of scope.

## Addresses And Coordinates

`Direccion` stores a structured address. Important fields:

- `calle`
- `altura`
- `piso`
- `codigoPostal`
- `ciudad`
- `provincia`
- `coordenada`

`Coordenada` stores a latitude/longitude pair. `Hospital` and `Paciente` own a
one-to-one `Direccion`; `Paciente` also keeps a `coordenadaActual`.

## Queue

`GestorDeCola` is the queue container per hospital, specialty and sector (unique on `id_hospital`, `id_especialidad_medica` and `id_sector`).

`EntradaCola` is the source of truth for queue state. Important fields:

- `gestorDeCola`
- `consultaMedica`
- `estado`
- `tipoPausa`
- `prioridad`
- `ordenRelativo`
- `fechaHoraIngreso`
- `fechaHoraLlamado`
- `fechaHoraSalidaTemporal`
- `fechaHoraUltimaRepregunta`
- `fechaHoraLimiteRespuesta`

Relevant states in `EstadoEntradaCola`:

- `EN_COLA`
- `LLAMADO`
- `EN_ESPERA`
- `ATRASADO`
- `EN_ATENCION`
- `FINALIZADA`
- `CANCELADA`

`tipoPausa` values in `TipoPausaCola`:

- `AUSENTE_AL_LLAMADO`
- `ESPERA_MANUAL`
- `ATRASADO_CONFIRMADO`

## Doctor Attention

`Medico` is authenticated through `UsuarioAuth`.

`AsignacionMedicoHospital` associates a doctor with hospital and specialty.

`SesionAtencionMedica` represents the current doctor work session. Important fields:

- `medico`
- `hospital`
- `especialidad`
- `sector`
- `sala`
- `estado`
- `fechaHoraInicio`
- `fechaHoraPausa`
- `fechaHoraFin`

Session states:

- `ACTIVA`: counts as active doctor capacity.
- `PAUSADA`: does not count as active capacity.
- `FINALIZADA`: closed session.

## Historical Attention

`AtencionMedica` is the historical record of one effective patient attention. It is created when a called patient confirms presence and enters `EN_ATENCION`, and finalized together with the consultation. Important fields:

- `consultaMedica`
- `sesionAtencionMedica`
- `estado`
- `fechaHoraInicio`
- `fechaHoraFin`

One `SesionAtencionMedica` can contain many attention records. One `ConsultaMedica` has at most one `AtencionMedica` in the current first-attention flow. Doctor, hospital, specialty, and room historical context is obtained from the linked session.

Attention states:

- `EN_CURSO`
- `FINALIZADA`

## Priority Review

`RevisionPrioridadConsulta` is the append-only record of a priority review made
by a doctor during attention. Important fields:

- `consultaMedica`
- `medico`
- `decision`
- `prioridadAnterior`
- `prioridadNueva`
- `motivo`
- `fechaHora`

`decision` values in `DecisionRevisionPrioridad`:

- `CONFIRMAR`
- `CORREGIR`

The review state (`PENDIENTE`, `CONFIRMADA`, `CORREGIDA`) is derived from the
history, not persisted. The current effective value is stored in
`ConsultaMedica.nivelDeGravedadMedico`.

## Reception Admission

`SesionRecepcion` is the receptionist's working session at one assigned
hospital. Important fields:

- `recepcionista`
- `hospital`
- `estado`
- `fechaHoraInicio`
- `fechaHoraFin`

Session states in `EstadoSesionRecepcion`:

- `ACTIVA`
- `FINALIZADA`

`AdmisionRecepcion` stores one reception-assisted admission. It does not create
or use `Chat`. Important fields:

- `consultaMedica`
- `sesionRecepcion`
- `estado`
- `coberturaConfirmada`, `credencialCoberturaId`, and `nombreObraSocialCobertura` record the
  admission's coverage choice. The ID and provider are saved as values so later changes to
  the patient's credentials do not rewrite the admission.
- `formularioJson`
- `resultadoTriageJson`
- `fechaHoraInicio`
- `fechaHoraFinalizacion`
- `fechaHoraCancelacion`

Admission states in `EstadoAdmisionRecepcion`:

- `INICIADA`
- `FORMULARIO_COMPLETO`
- `FINALIZADA`
- `CANCELADA`

## Staff Identity, Memberships And Invitations

`UsuarioAuth` is the local global identity linked to the Auth0 subject. It
stores name, document, email, and a platform-level `RolSistema` (`ADMIN` or
`USER`). `Medico`, `Recepcionista`, and `Paciente` profiles own a one-to-one
`UsuarioAuth`; patient profiles make it optional.

`CambioContraseniaToken` stores a password-reset token. Both password-change records extend the `RegistroCambioContrasenia` `@MappedSuperclass` (`id`, `usuario` (`UsuarioAuth`), `fechaHoraCreacion`; not an entity, each subclass keeps its own table so `cambio_contrasenia_token` is unchanged). `CambioContraseniaToken` adds:

- `token` (opaque, `unique`, plain for email warning)
- `fechaHoraExpiracion`
- `estado`

States in `EstadoCambioContrasenia`:

- `PENDIENTE`
- `CAMBIADO`
- `EXPIRO`
- `INVALIDADO` (superseded by a newer `PENDIENTE` for the same user; not counted for validation)

`expiro()` checks `LocalDateTime.now().isAfter(fechaHoraExpiracion)`. Token lifetime and rate limit (`pretriage.cambio-contrasenia.expiracion-minutos`, `max-solicitudes-por-hora`, `ventana-horas`) are configurable via `application.properties`. `UsuarioAuth.cambiosDeContrasenia` is `@OneToMany(cascade=ALL)` mapped to the inherited `usuario` field.

`BloqueoCambioContrasenia` (also extends `RegistroCambioContrasenia`, table `bloqueo_cambio_contrasenia`, unique `id_usuario`) stores the current lockout state of the authenticated password change: one row per user with:

- `intentosFallidos` (consecutive failed attempts; any non-success counts)
- `bloqueadoHasta` (nullable `LocalDateTime`; while in the future the endpoint answers `423`)

`estaBloqueado(now)`/`minutosRestantes(now)` drive the `423` response. Reaching `pretriage.cambio-contrasenia-autenticado.max-intentos` (3) sets `bloqueadoHasta = now + horas-bloqueo` (24 = 1 day); an expired lock resets the counter to 0 and a success clears it. The lock only affects `POST /api/auth/cambio-contrasenia/actual` (`CambioContraseniaAutenticadoService`).

`MembresiaHospital` links one global identity to one hospital. Important fields:

- `usuario`
- `hospital`
- `estado`
- `roles`
- `fechaCreacion`
- `fechaAceptacion`
- `fechaSuspension`
- `creadaPor`

The pair `(usuario, hospital)` is unique. States in `EstadoMembresiaHospital`:

- `INVITADA`
- `ACTIVA`
- `SUSPENDIDA`
- `REVOCADA`

Roles in `RolMembresiaHospital`:

- `ADMIN_HOSPITAL`
- `MEDICO`
- `RECEPCIONISTA`

`InvitacionHospital` is a single-use invitation to join a hospital. Important
fields:

- `hospital`
- `emailNormalizado`
- `estado`
- `rolesSolicitados`
- `especialidadIds`
- `tokenHash`
- `matricula`, `tipoMatricula`, `jurisdiccionMatricula`
- `venceEn`
- `fechaCreacion`
- `fechaAceptacion`
- `emailEnviado`, `ultimoIntentoEnvio`, `cantidadIntentosEnvio`
- `invitadaPor`
- `aceptadaPor`

Only the SHA-256 hash of the invitation secret is stored. States in
`EstadoInvitacionHospital`:

- `PENDIENTE`
- `ACEPTADA`
- `EXPIRADA`
- `REVOCADA`

`CredencialProfesional` stores a doctor's professional registration. Important
fields:

- `medico`
- `numero`
- `tipo`
- `jurisdiccion`
- `estado`

The tuple `(numero, tipo, jurisdiccion)` is unique. `tipo` values in
`TipoMatriculaProfesional`: `NACIONAL` | `PROVINCIAL`. `estado` values in
`EstadoCredencialProfesional`: `PENDIENTE_VERIFICACION` | `VERIFICADA` |
`SUSPENDIDA` | `VENCIDA`. National credentials are normalized to the `NACION`
jurisdiction.

`AsignacionMedicoHospital` associates a doctor with hospital and specialty. The
tuple `(medico, hospital, especialidad)` is unique.

`AuditoriaHospital` records privileged hospital changes. Important fields:

- `hospital`
- `actor`
- `fecha`
- `accion`
- `objetivo`
- `resultado`

## Medical Studies And File Management

`EstudioClinico` represents a medical study file (PDF, image, etc.) uploaded by a patient. Important fields:

- `paciente`
- `nombreArchivo`
- `tipoArchivo`
- `extensionArchivo`
- `descripcion`
- `fechaSubida`
- `tamanoArchivo`
- `rutaArchivo`
- `activo`

Files are stored in AWS S3 through `GestionDeArchivosService`. The entity uses soft delete (`activo = false`) to preserve history while removing from active views. Patients can upload, list, download, and delete their own studies. Doctors can view patient studies during attention.

## Generated Diagram

A Mermaid ER diagram can be generated from the JPA entity classes:

```powershell
python scripts\generate_domain_diagram.py
```

Generated files:

- `docs/generated/domain-model.md`
- `docs/generated/domain-model.mmd`
