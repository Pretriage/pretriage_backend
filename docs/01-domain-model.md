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
- `chat` (optional link to the `Chat` from digital pretriage)

Relevant states in `EstadoConsulta`:

- `PENDIENTE`
- `HOSPITAL_SELECCIONADO`
- `PRETRIAGE_EN_PROCESO`
- `PRETRIAGE_FINALIZADO`
- `EN_COLA`
- `EN_REVISION` (assigned to a doctor and room before the call)
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
- `estadoClasificacion` (`NO_SOLICITADA`, `PENDIENTE`, `COMPLETADA`, `FALLIDA`, `PARCIAL`)
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
Digital pretriage links the chat through `ConsultaMedica.chat` (`@OneToOne`, unique FK
`consulta_medica.id_chat`, unidirectional from the consultation). A
[partial closure](34-automatic-chat-recovery.md) can also link the chat while leaving
`resultadoTriageJson` null. Reception admission has its own record in `AdmisionRecepcion`.

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

`ConsultaMedica.sintomasBot` is a unidirectional one-to-many association to `Sintoma`
through `id_consulta_medica`. Each `Sintoma` stores a symptom name identified during triage.

## Hospitals And Specialties

`EspecialidadMedica` stores a code and display name for each specialty.

`Hospital` has many specialties. A patient can select only hospitals that support the chosen specialty.

`Sector` groups rooms within a hospital for one specialty. A specialty can have multiple
sectors in the same hospital. Sector names are trimmed and unique among
non-archived sectors in a hospital, ignoring case.
`hospital` and `especialidad` are `ManyToOne`. `salas` maps the inverse side of `Sala.sector`.
`pacientesAsignados` is a unidirectional `OneToMany` through `id_sector_asignado`.

Under `/api/admin/hospitales/{hospitalId}/configuracion`, `ADMIN_HOSPITAL` can create a sector
with `POST /sectores` and update or delete it with `PUT` or `DELETE /sectores/{sectorId}`.
The specialty must be enabled for the hospital. Updates and deletions return `409` when the
sector or its rooms have unfinished consultations. Deactivation also checks for `ACTIVA` or
`PAUSADA` sessions and deactivates the sector's rooms. Deletion removes an unused
sector, its rooms and empty queue manager. For a sector with history, deletion
sets `archivado=true` and `activa=false`, deactivates its rooms and preserves the
historical relationships. Both return `204`. Configuration omits archived
sectors and their rooms. Active assignment and admission exclude them.

`AsignacionSectorService` selects the sector for patient entry. `SesionAtencionMedica` records
the validated sector and room selected by the doctor at the start of the session.

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
- `EN_REVISION` (reserved and excluded from queue estimates)
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

## Declared Symptom Discrepancy

`DiscrepanciaSintomas` records at most one intentional false declaration per
`AtencionMedica`. It identifies the patient by DNI and records the reporting
hospital, doctor, and timestamp. Annulment preserves the record and adds the
administrator and timestamp.

`SancionDigitalPaciente` records the result of each recalculation by DNI. One
current report gives six calendar months, two give one calendar year, and
three or more give an explicit permanent sanction. Temporary terms run from
the latest current report in the Buenos Aires time zone. Clinical priorities
and active consultations continue through their regular flows.

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

## Diagrams

The [PlantUML class diagram](35-class-diagram.puml) groups the Java domain model into an overview and four detailed views. The [diagram guide](35-class-diagram.md) describes the views and shows how to render them.

A Mermaid ER diagram can be generated from the JPA entity classes:

```powershell
python scripts\generate_domain_diagram.py
```

Generated files:

- `docs/generated/domain-model.md`
- `docs/generated/domain-model.mmd`
