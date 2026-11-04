# Queue And Estimation

## Source Of Truth

`EntradaCola` is the source of truth for queue state.

Do not use `GestorDeCola.consultasEnEspera` for new estimation logic. It is legacy compatibility state.

## Queue Scope

Each queue is per:

```text
hospital + especialidad + sector
```

This is represented by `GestorDeCola` (unique per `id_hospital`,
`id_especialidad_medica` and `id_sector`).

## Sector Assignment

When a patient selects a hospital (`AtencionHospitalService.seleccionarHospital`)
or a receptionist creates an admission (`AdmisionRecepcionService.crearAdmision`),
`AsignacionSectorService.asignarSector` chooses the sector for the consultation:

- Candidates are `Sector` records of the hospital+specialty that are `activa=true`
  and have at least one `Sala.activa=true`.
- The chosen sector is the one with the fewest `EntradaCola.EN_COLA`
  (`repoEntradasCola.countByGestorDeColaSectorIdAndEstado`), ties broken by
  `Sector.nombre` ASC.
- If no candidate exists, it throws `NoSuchElementException` and the patient
  cannot enter the queue.
- The chosen sector is stored in `ConsultaMedica.sector` and the patient is added
  once to `Sector.pacientesAsignados`.

`IngresoColaService.ingresar(consulta, prioridad)` requires
`consulta.getSector() != null`; it resolves the `GestorDeCola` for
`hospital+especialidad+sector`, creating it (and validating that the sector
exists via `RepoSectores.existsById`) when missing. The overload
`ingresar(consulta, prioridad, sector)` assigns the sector and delegates.

## Queue Ordering

Estimable queue entries are ordered by:

```text
prioridad DESC,
ordenRelativo ASC,
fechaHoraIngreso ASC
```

Priority values:

```text
5 = RIESGO_VITAL_INMEDIATO
4 = MUY_URGENTE
3 = URGENTE
2 = NORMAL
1 = NO_URGENTE
```

## Entries Counted For Estimation

Only this state counts:

- `EntradaCola.EN_COLA`

These states do not count as waiting queue entries:

- `LLAMADO`
- `EN_ESPERA`
- `ATRASADO`
- `EN_ATENCION`
- `FINALIZADA`
- `CANCELADA`

## Dynamic Estimation

The estimated attention time is calculated by `EstimacionAtencionService` every time it is requested.

It is not persisted as final truth.

Formula:

```text
cola              = ordered EN_COLA entries of the patient's GestorDeCola (hospital+especialidad+sector)
pacientesAntes    = index of patient in that queue
medicosActivos    = count of SesionAtencionMedica.ACTIVA for same hospital/especialty/sector
medicosParaEstimacion = max(medicosActivos, 1)
bloquesEspera     = pacientesAntes / medicosParaEstimacion
fechaHoraAtencionEstimada = now + bloquesEspera * minutosPromedioAtencion
```

The average duration is configurable:

```properties
pretriage.estimacion.minutos-promedio-atencion=10
```

Every estimate response also identifies the assigned sector within the hospital:

- `sectorId`
- `nombreSector`

These come from `ConsultaMedica.sector` and tell the patient where to wait once they are called.

## No Active Doctors

Administrative session closure changes an `ACTIVA`/`PAUSADA` session to
`FINALIZADA` and releases its doctor/room reservation. The finalized row remains
in the database for historical references, contributes no active capacity, and
is excluded from the sector's administrative session list. That list is limited
to sessions still available to manage; it is not a history query.

If there are no active doctors:

- Estimation still uses one virtual doctor.
- Response exposes `hayMedicosActivos=false`.
- Response includes a message explaining the estimate is tentative.

Example response:

```json
{
  "fechaHoraAtencionEstimada": "2026-07-09T23:17:12",
  "hayMedicosActivos": false,
  "medicosActivos": 0,
  "medicosParaEstimacion": 1,
  "posicionEnCola": 1,
  "pacientesAntes": 0,
  "minutosPromedioAtencion": 10,
  "sectorId": 12,
  "nombreSector": "Guardia",
  "mensaje": "No hay medicos atendiendo esta especialidad en este momento. La hora es una estimacion tentativa."
}
```

## End-Of-Queue Estimate For Hospital Ranking

For the nearby-hospital ranking (`GET /api/hospitales/cercanos?ordenarPor=tiempo-atencion`) a prospective wait for a **new** patient that would join at the end of the queue is computed via `EstimacionAtencionService.calcularEsperaParaNuevaConsulta`:

```text
pacientesEnCola        = count(EntradaCola.EN_COLA for hospital+especialidad, all sectors)
medicosActivos         = count(SesionAtencionMedica.ACTIVA for hospital+especialidad)
medicosParaEstimacion  = max(medicosActivos, 1)
bloquesEspera          = pacientesEnCola / medicosParaEstimacion   // floor division, same as per-patient formula
minutosEsperaEstimados = bloquesEspera * minutosPromedioAtencion
fechaHoraAtencionEstimada = now + minutosEsperaEstimados
```

Only `EntradaCola.EN_COLA` entries are counted, never `GestorDeCola.consultasEnEspera`. A hospital is considered available for ranking only when `medicosActivos > 0` (`disponible=true`); otherwise it is excluded and an empty ranking means the frontend must display "no hay hospitales disponibles". Valid `ordenarPor` values live in `AtencionHospitalService.ORDENES_VALIDOS` (`distancia`, `tiempo-atencion`, `valoracion` y combinados como `distancia|tiempo-atencion` o `distancia|tiempo-atencion|valoracion`), el orden de los criterios es indistinto, el separador es `|`, los valores son case-sensitive y extensible; el combinado usa suma de rankings (distancia según la posición por distancia geodésica + tiempo según minutosEspera + valoración según `valoracionPromedio` de Google, con `null` tratado como 0), y los empates se rompen por `nombre` y luego por el orden por distancia geodésica.

## When Estimation Changes

The estimate can change whenever:

- A doctor starts session.
- A doctor pauses session.
- A doctor closes session.
- A patient with higher priority enters queue.
- A patient leaves queue manually.
- A delayed patient arrives.
- A patient is called or enters attention.
- A consultation is finalized or cancelled.

## Real-Time Updates

Patients can subscribe through authenticated SSE:

```http
GET /api/atencion/tiempos/suscribirse/{consultaId}
Accept: text/event-stream
```

- The authenticated patient must own the consultation.
- An initial `tiempo-estimado` event is sent when the connection opens.
- Estimates are recalculated from `EntradaCola` and `EstimacionAtencionService` every 15 seconds while connected.
- A `heartbeat` event is sent every 30 seconds.
- Multiple simultaneous emitters are supported for the same consultation.
- Connections are completed when the consultation no longer has an active `EN_COLA` estimate.
