# Doctor Flow

## Main Flow

```mermaid
flowchart TD
    A[Doctor logs in] --> B[Sees assigned hospitals and specialties]
    B --> C[Chooses specialty, sector and room in the selected hospital]
    C --> D[Starts active session]
    D --> E[Calls next patient from queue]
    E --> F{Patient responds?}
    F -->|Yes| G[Patient enters attention]
    F -->|No| H[Doctor marks absent]
    G --> I[Doctor reviews preliminary priority]
    I --> K[Doctor finishes attention]
    H --> J[Patient waiting/delayed flow]
```

## Session Rules

- A doctor is already assigned to hospitals and specialties by the admin domain.
- A doctor starts a session by choosing hospital, specialty, sector, and room.
- A doctor cannot change specialty inside a session.
- To change specialty, close the current session and start another.
- A session belongs to exactly one sector; the room must belong to that sector and
  the sector to the same hospital+specialty, or the startup is rejected.
- A room can be used by one doctor at a time.
- A doctor can have one active or paused session at a time.
- After a reload or new login, the active or paused session and any `LLAMADO` or
  `EN_ATENCION` consultation are recovered from the backend.
- A paused session continues reserving the doctor and room, although it does not count as estimation capacity.
- Hospital configuration cannot deactivate that room or sector while the session
  is active or paused. A room must also be active to appear in the doctor selector.
- A doctor cannot pause or close a session while a consultation is LLAMADO or EN_ATENCION.
- A doctor cannot call another patient while a previous patient is still LLAMADO or EN_ATENCION.

## Session States

- `ACTIVA`: doctor is attending and counts for estimation.
- `PAUSADA`: doctor is temporarily absent and does not count for estimation.
- `FINALIZADA`: session closed.

## Administrative Session Closure

Hospital administrators can list `ACTIVA` and `PAUSADA` sessions under each
sector in the web configuration panel and explicitly confirm **Finalizar sesión**.
This also works for inactive sectors and legacy sessions whose sector is inferred
from their room. Only an administrator authorized for that hospital can do this.
The list excludes `FINALIZADA`: closing a session removes it from the next list
response. The persisted session remains available for internal historical
references; this administrative endpoint does not provide a finalized-session
history view.

The close operation changes either state to `FINALIZADA` and returns the updated
session. It records the end time and an audit event, and releases the doctor and
room reservation. Pausing does not release
that reservation. Finalization preserves the session and clinical/queue history;
it does not cancel or move queued patients. Repeating a successful close preserves
the original end time and does not add another audit event.

Closure returns `409` while the doctor has a queue entry in `LLAMADO` or
`EN_ATENCION`, or the session has an `AtencionMedica.EN_CURSO`. That consultation
must be resolved through the doctor flow first. Administrative closure and doctor
operations acquire the same session row lock, then recheck its state, so a
concurrent patient call cannot continue using a session the administrator closed.

After closure, a sector with history must be deactivated rather than physically
deleted. Deactivation still requires resolving all pending consultations.

## Calling Patients

When doctor calls next patient:

- Backend selects next `EntradaCola.EN_COLA` for the session hospital/specialty/sector.
- Ordering is by priority descending and relative order ascending.
- Entry becomes `LLAMADO`.
- Consultation also becomes `LLAMADO`.

If patient appears:

- Entry becomes `EN_ATENCION`.
- Consultation becomes `EN_ATENCION`.
- Doctor, room, and session context are assigned.
- An `AtencionMedica.EN_CURSO` historical record is created and linked to the consultation and session.

If patient does not respond:

- Doctor marks absent manually.
- Entry becomes `EN_ESPERA`.
- `tipoPausa = AUSENTE_AL_LLAMADO`.
- Patient decides whether they are delayed or returning.

## Completing Attention

When attention finishes:

- The doctor must first confirm the preliminary priority or correct it.
- Confirmation is a one-click action. A correction requires a different priority; its short reason is optional.
- Each genuine review change creates an append-only `RevisionPrioridadConsulta` record with doctor, previous and new priority, decision, optional reason, and timestamp.
- Repeating the exact same review is idempotent and does not duplicate history.
- The current effective value is also stored in `ConsultaMedica.nivelDeGravedadMedico` for direct reads. Reviewing it does not reorder an entry that is already `EN_ATENCION`.
- Priority can be changed again while attention remains open, but never after finalization.
- `EntradaCola` and `ConsultaMedica` become `FINALIZADA`.
- The linked `AtencionMedica` becomes `FINALIZADA` and stores its end time.

The authorized clinical summary and review state are available at
`GET /api/medico/sesiones/{sesionId}/consultas/{consultaId}/pretriaje`.
The same response carries `estadoClasificacion`, `cierreParcial` and the literal
`mensajesConversacion`. If classification closed as `PARCIAL` without a valid AI
summary, the web shows the transcript in this consultation view and labels the
queue's initial priority provisional. The doctor still reviews priority through
the existing workflow; no second consultation or queue entry is created.
`PUT /api/medico/sesiones/{sesionId}/consultas/{consultaId}/revision-prioridad`
accepts `CONFIRMAR`, or `CORREGIR` with a required different `prioridad` and an
optional `motivo` of at most 500 characters. Finalization returns `409` while
the review is pending.

## Queue Visibility And History

- `GET /api/medico/asignaciones` returns the doctor's hospital and specialty assignments.
- `GET /api/hospitales/{hospitalId}/sectores?codigoEspecialidad={codigoEspecialidad}` returns the active sectors of a hospital for the given specialty.
- `GET /api/hospitales/{hospitalId}/sectores/{sectorId}/salas?codigoEspecialidad={codigoEspecialidad}` returns the active rooms of a sector for the given specialty.
- Web loads sectors after choosing a specialty, then loads rooms after choosing a
  sector. Changing either parent clears the dependent selections; starting a
  session sends `hospitalId`, `codigoEspecialidad`, `sectorId`, and `salaId`.
- `GET /api/medico/sesiones/actual` returns the authenticated doctor's active or
  paused session and their currently called or in-attention consultation, if present.
- `GET /api/medico/sesiones/{sesionId}/pacientes-disponibles?dni={dni}` lists ordered `EN_COLA` patients for the session hospital and specialty, including the preliminary priority, patient name and surname, document (`numeroDocumento`/`tipoDocumento`) and `estadoConsulta` authorized for the attending doctor. A room is not present until the patient is called. `dni` is optional exact-match filter on `Paciente.numeroDocumento` (trimmed) scoped to the session queue, executed at DB level; blank/missing returns full queue, non-matching returns `[]`.
- `GET /api/medico/atenciones` returns the authenticated doctor's historical attention records.

## Clinical History Access

When attending patients, doctors can access their medical records:

- `GET /api/medico/pacientes/{pacienteId}/historial-clinico` retrieves all medical records (PDFs, images) for a specific patient.
- `GET /api/medico/pacientes/{pacienteId}/historial-clinico/{estudioId}/archivo` downloads or previews a specific medical record file.
- `GET /api/medico/pacientes/{pacienteId}/historial-clinico/{estudioId}/reporte` retrieves structured information about a specific medical record.
- `GET /api/medico/pacientes/{pacienteId}/ultimos-reportes?limite=5` gets up to `limite` most recent active medical records ordered by `fechaSubida` DESC (`activo=true`; `limite` defaults to `5`; values `< 1` return `400`) for quick triage reference.
