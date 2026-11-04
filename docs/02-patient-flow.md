# Patient Flow

## Main Flow

```mermaid
flowchart TD
    A[Patient starts attention] --> B[Selects medical specialty]
    B --> C[Gets nearby hospitals filtered by specialty]
    C --> D[Selects hospital and enters specialty queue]
    D --> E[Optionally starts AI triage chat]
    E --> F[AI assigns triage priority]
    F --> G[Queue priority updated]
    G --> H[Patient checks dynamic estimated attention time]
```

## Steps

1. Patient selects a medical specialty.
2. Backend queries registered hospitals by specialty, resolves their coordinates and metadata with Google Place Details, and filters by geodesic distance within 5 km.
3. Patient selects a hospital by `placeId` and `codigoEspecialidad`.
4. Backend creates or updates the active `ConsultaMedica` with the selected hospital and specialty.
5. Backend assigns a sector to the consultation (`AsignacionSectorService`: active sector of the hospital+specialty with the fewest `EN_COLA` entries and at least one active room). If no sector is available the selection fails.
6. The consultation enters the sector queue immediately: `EN_COLA` state and an `EntradaCola` with default priority are created, tied to the `GestorDeCola` of `hospital+especialidad+sector`.
7. Patient starts chat (optional). `POST /api/chat` always creates a new chat and closes the patient's previous open chats (see "Starting A Chat" below).
8. Bot asks clinical questions.
9. When triage finishes, `Chat.resultadoTriageJson` is stored and the `ConsultaMedica` is linked to that chat (`ConsultaMedica.chat`). Only this link marks the consultation as "pretriage realizado" for hospital metrics; selecting a hospital without the chatbot, reception admissions and chats closed without finishing leave `ConsultaMedica.chat = null`.
10. `nivelDeGravedadBot` is mapped from AI priority.
11. The existing `EntradaCola` priority is updated with the pretriage result.
12. Estimated attention time is returned dynamically.

`GET /api/atencion/hospital` (selected hospital) returns the assigned
`sectorId`, `nombreSector` and the sector's active `salas` in addition to the
hospital data. The same assigned sector (`sectorId`/`nombreSector`) is also
returned by `GET /api/paciente/consulta/estado` and in every estimated-time
response (`TiempoEstimadoAtencionResponse`), so the patient always knows which
sector to wait in.

## Starting A Chat

A patient can have several chats over time (`Chat.paciente` is `@ManyToOne`). `ChatService.iniciarChat`:

1. **Blocks on pending attention**: if any `ConsultaMedica` of the patient has a linked chat and its `EntradaCola` is not `FINALIZADA`/`CANCELADA` (e.g. `EN_COLA`, `LLAMADO`, `EN_ESPERA`, `ATRASADO`, `EN_ATENCION`), responds `400` with a patient-facing message (`AtencionPendienteException`) and changes nothing. The check is anchored on the chat↔consultation link, not on any active `EntradaCola`: the entry is created when the hospital is selected (step 6), before the chat starts, so the normal flow is never blocked.
2. Marks every previous open chat (`finalizado=false`) as `finalizado=true`, keeping at most one open chat per patient (the invariant `EsperaPacienteService.finalizarChatAbierto` relies on).
3. Creates the new chat with the bot greeting.

## Waiting And Absence Rules

Endpoints in this section are implemented in `PacienteEsperaController` / `EsperaPacienteService` (`docs/06-api-reference.md#patient-queue-state`).

If patient manually leaves the waiting queue (`POST /api/paciente/consulta/cola/pausa-manual` — `EsperaPacienteService.ausentarme`, requires `EN_COLA`):

- `EntradaCola.estado = EN_ESPERA`
- `tipoPausa = ESPERA_MANUAL`
- They do not count for estimation while waiting outside queue.
- When they return (`POST /api/paciente/consulta/cola/reincorporar`), they keep their previous relative position (keeps `ordenRelativo`).

If doctor calls patient and patient is absent:

- Doctor marks them absent manually.
- Patient becomes `EN_ESPERA` with `AUSENTE_AL_LLAMADO`.
- Patient can confirm they are delayed (`POST /api/paciente/consulta/cola/atraso/confirmar` — requires `EN_ESPERA+AUSENTE_AL_LLAMADO`).
- The one-hour waiting deadline starts when the doctor marks the called patient absent.

If patient confirms delayed (`POST /api/paciente/consulta/cola/atraso/confirmar` → `POST /api/paciente/consulta/cola/atraso/renovar`):

- State becomes `ATRASADO` (`ATRASADO_CONFIRMADO`, `fechaHoraLimiteRespuesta=now+30m`).
- They are not in queue until they mark arrival (`POST /api/paciente/consulta/cola/reincorporar`).
- Backend asks again every 30 minutes through deadline state (`sigoAsistiendo` → now `renovarConfirmacionAtraso` extends `+30m`).
- If they do not respond, they are cancelled by scheduler (`cancelarAtrasadosSinRespuesta`).

If delayed patient arrives (`POST /api/paciente/consulta/cola/reincorporar` from `ATRASADO`):

- They return to first place within their priority level (`obtenerOrdenParaPrimerLugarDePrioridad`: `min(ordenRelativo)-1` for that `prioridad`).

## Waiting Expiration

Both manual waiting (`POST /api/paciente/consulta/cola/pausa-manual`) and absence-after-call / `ATRASADO` entries are automatically cancelled after their deadline in `EN_ESPERA`/`ATRASADO` (60m for `EN_ESPERA`, 30m windows for `ATRASADO` via `EsperaPacienteService.cancelarEsperasVencidas` / `cancelarAtrasadosSinRespuesta`):

- `EntradaCola.estado = CANCELADA`
- `ConsultaMedica.estadoConsulta = CANCELADA`
- Persisted records remain as history but no longer belong to the active queue.

## Voluntary Cancellation

Patients can cancel their active hospital selection at any point of the attention flow (`POST /api/paciente/consulta/cancelar` — `EsperaPacienteService.cancelarSeleccion`, `docs/06-api-reference.md#cancel-hospital-selection`). The client requests confirmation before calling the endpoint, which is **idempotent**.

From `EN_COLA`, `EN_ESPERA` (manual or `AUSENTE_AL_LLAMADO`) or `ATRASADO`:

- `EntradaCola.estado = CANCELADA` (clears `tipoPausa`, `fechaHoraLimiteRespuesta`, `fechaHoraUltimaRepregunta`).
- `ConsultaMedica.estadoConsulta = CANCELADA` (clears `medico` and `sala`).
- The entry stops counting for estimation and is no longer offered to the doctor (`listarPacientesDisponibles`/`llamarProximo` only read `EN_COLA`; `obtenerSesionActual` only `LLAMADO`/`EN_ATENCION`), so a cancelled patient can never be called again within that selection.
- If the AI triage chat is still open, it is finalized (`Chat.finalizado = true`).

Mobile clears its saved chat ID only after confirmed cancellation and after a successful
new hospital selection (HTTP 204). The next interview starts with a new chat even for
the same hospital and specialty. Failed requests retain the cached ID; historical chats
and messages remain in the backend.

The endpoint always resolves the patient's **current** hospital selection: it operates on the most recent `EntradaCola` of the patient (`RepoEntradasCola.findFirstByConsultaMedicaPacienteIdOrderByIdDesc`). Older `EntradaCola` records from previous selections that are already `CANCELADA` or `FINALIZADA` are never considered, so a historical entry can neither shadow the active selection nor produce a spurious `409`/no-op response.

It is **not** possible to cancel a consultation in `LLAMADO`, `EN_ATENCION` (in progress) or already `FINALIZADA` (409 `ConflictoDeEstadoException`). Cancellation is terminal: the selection cannot be resumed or re-linked. Reception admissions are out of scope for this endpoint (cancelled through `POST /api/recepcion/admisiones/{admisionId}/cancelar`, `docs/09-reception-admission.md`).

## Medical Studies Management

Patients can manage their medical study files (radiology scans, lab reports, etc.) independently of the attention flow:

### Upload Study

- Patient uploads a file through `POST /api/estudios` with multipart form data.
- File is stored in AWS S3 through `GestionDeArchivosService`.
- An `EstudioClinico` record is created with metadata (type, description, file size, upload date).
- The record is linked to the authenticated patient.

### List And View Studies

- `GET /api/estudios` returns all active studies for the authenticated patient.
- `GET /api/estudios/{idEstudio}` returns metadata for a specific study.
- `GET /api/estudios/{idEstudio}/file` downloads the actual file from S3.

### Delete Study

- `DELETE /api/estudios/{idEstudio}` performs soft delete on the `EstudioClinico` record.
- The file is first deleted from S3.
- If S3 deletion fails, the database record is not modified (atomic operation).
- The entity's `activo` field is set to `false` to preserve history while removing from active views.

### Doctor Access

During attention, doctors can view patient studies through the medical history endpoints documented in the API reference.
