# Hospital Selection And Arrival

## Flow Overview

The hospital selection flow allows patients to choose a hospital and get arrival time estimates. The backend provides route options but does not store the user's choice or enforce the estimated arrival time for queue entry.

## API Sequence

### 1. Get Nearby Hospitals

```http
GET /api/hospitales/cercanos?latitud={lat}&longitud={lon}&codigoEspecialidad={codigoEspecialidad}&transporte={transporte}&ordenarPor={ordenarPor}
```

- Starts from hospitals registered in the app, filtered by selected specialty. Google Place Details supplies metadata and coordinates for their registered Place IDs; no global Nearby Search is performed.
- Filters by geodesic distance within 5 km and sorts by that distance; there is no 20-hospital candidate limit.
- Uses stored coordinates to exclude distant hospitals before querying Google. If Google details fail, a registered hospital with valid stored coordinates can still appear with local name/address and null ratings. If neither stored nor Google coordinates are usable, it is omitted because its distance cannot be determined. A registered Place ID is required by the selection contract.
- Place Details is requested once per candidate not already excluded by stored distance; ratings remain live and unpersisted.
- Returns only hospitals available for attention (`disponible=true` — at least one `SesionAtencionMedica.ACTIVA` for the requested specialty). When no hospital satisfies this, the response is an empty list and the frontend must show "no hay hospitales disponibles".
- Sorting is controlled by `ordenarPor` (optional, defaults to `distancia` when omitted or empty; valid values in `ORDENES_VALIDOS`: `distancia`, `tiempo-atencion`, `valoracion`, combinados con `|` como `distancia|tiempo-atencion` o `distancia|tiempo-atencion|valoracion`, orden indistinto). Values are case-sensitive: `Valoracion` or any other spelling returns 400, and a combination with any invalid or duplicated criterion returns 400 even when the other criteria are valid:
  - `distancia` — keeps geodesic distance order of registered hospitals.
  - `tiempo-atencion` — orders by lower estimated attention wait time (see `docs/04-queue-and-estimation.md#end-of-queue-estimate-for-hospital-ranking`); ties are broken by `tiempoEstimadoArriboMejorRuta` (nulls last) then `nombre`.
  - `valoracion` — orders by `valoracionPromedio` descending (Google Places rating, 1.0–5.0); hospitals without rating (`null`) are treated as 0 and go last without being excluded; ties break by `cantidadValoraciones` descending (null = 0) then `nombre` (the name returned by Google) ascending, keeping geodesic distance order among equal names.
  - Combinados (ej. `valoracion|distancia`, `distancia|tiempo-atencion|valoracion`) — orden combinado por suma de rankings: para cada criterio se calcula la posición del hospital en su orden individual (distancia = posición por distancia geodésica, tiempo = minutos de espera, valoración = puntaje) y se suman; el menor puntaje va primero, los empates se rompen por `nombre` y luego por el orden por distancia geodésica (extensible agregando nuevos valores a `ORDENES_VALIDOS`).
- `transporte` is optional and defaults to `transporte-publico`. Valid values:
  - `transporte-publico`: Public transit (buses, trains, etc.)
  - `vehiculo`: Driving/car
  - `vehiculo-dos-ruedas`: Two-wheel vehicles (motorcycles)
  - `caminar`: Walking
  - `bicicleta`: Bicycling
- Each hospital includes `tiempoEstimadoArriboMejorRuta`, the estimated arrival time of the best route (the route labeled `DEFAULT_ROUTE` by Google) for the requested transport mode. It is `null` when Google cannot compute a route for that hospital.
- Each hospital also exposes the estimated attention wait computed for a new arrival at the end of the queue:
  - `pacientesEnCola` — count of `EntradaCola.EN_COLA` for `hospital+especialidad`
  - `minutosEsperaEstimados` — `bloquesEspera * minutosPromedioAtencion`
  - `fechaHoraAtencionEstimada` — `now + minutosEsperaEstimados`
  - `disponible` — `medicosActivos > 0` (only `true` entries are returned)
- Each hospital also exposes its Google Places rating (requested live on every call; there is no persistence of ratings):
  - `valoracionPromedio` — average rating on Google's 1.0–5.0 scale, or `null` when Google provides no rating (absence of data, not an error).
  - `cantidadValoraciones` — number of registered reviews, or `null` when Google provides none.
- Patient reviews the list and selects one.

### 2. Select Hospital

```http
POST /api/atencion/hospital
```

- Patient confirms their hospital choice.
- Creates or updates the consultation with the selected hospital.
- Assigns a sector to the consultation (`AsignacionSectorService`: active sector of
  the hospital+specialty with the fewest `EN_COLA` entries and at least one active room).
- Enters the consultation into the sector queue (`EN_COLA` + `EntradaCola` for
  `hospital+especialidad+sector`) immediately with default priority (`NORMAL`).
- The AI triage chat is optional: if the patient completes it later, the existing `EntradaCola` priority is updated with the pretriage result.

### 2.5 Get Selected Hospital

```http
GET /api/atencion/hospital
```

- Returns the hospital selected in the active consultation of the authenticated patient: `idHospital`, `placeId`, `nombre`, and the formatted `direccion` (street, number, floor, postal code, city, province, joined with `, `, skipping empty components).
- `direccion` is `null` when the hospital has no stored `Direccion`.
- When the consultation has an assigned sector, the response also includes
  `sectorId`, `nombreSector` and the sector's `salas` (active `Sala` list with
  `id` and `nombre`).
- Returns an error if the patient has no consultation with a selected hospital.

### 3. Get Arrival Time Estimates

```http
GET /api/hospitales/{idHospital}/tiempo-arribo?latitud={lat}&longitud={lon}&transporte={transporte}
```

- Returns multiple route options to reach the selected hospital.
- `transporte` is required and must be one of the following valid values:
  - `transporte-publico`: Public transit (buses, trains, etc.)
  - `vehiculo`: Driving/car
  - `vehiculo-dos-ruedas`: Two-wheel vehicles (motorcycles)
  - `caminar`: Walking
  - `bicicleta`: Bicycling
- Each route includes estimated duration and details.

Example response:

```json
[
  {
    "tiempoEstimadoArribo": "00:35:45",
    "idHospital": 1,
    "distanciaMetros": 9331,
    "PolylineCode": "vbtrEvkccJsDcFaAwAs@h@a@{@...",
    "combinacionesLineas": [
      {
        "tipoTransporte": "caminar",
        "indicaciones": "Dirígete al nordeste por Independencia hacia Cnel. Dorrego",
        "nombreLinea": null
      },
      {
        "tipoTransporte": "transporte-publico",
        "indicaciones": "Autobús en dirección a 116 (Rojo): Once",
        "nombreLinea": "Plaza Once (98 - 3n, 3v) - Calle 153, 2457"
      },
      {
        "tipoTransporte": "caminar",
        "indicaciones": "Dirígete al norte por Av. Hipólito Yrigoyen/RN205 hacia Riobamba",
        "nombreLinea": null
      }
    ],
    "transporte": "transporte-publico"
  },
  {
    "tiempoEstimadoArribo": "00:38:41",
    "idHospital": 1,
    "distanciaMetros": 9987,
    "PolylineCode": "vbtrHAwAs@h@a@{@...",
    "combinacionesLineas": [
      {
        "tipoTransporte": "caminar",
        "indicaciones": "Dirígete al suroeste por Independencia hacia Tte. Coronel Luis María Campos",
        "nombreLinea": null
      },
      {
        "tipoTransporte": "transporte-publico",
        "indicaciones": "Autobús en dirección a R1 - Wilde - Villa Del Parque (X Ctro. Avellaneda)",
        "nombreLinea": "Nazarre Y Cuenca - San Carlos 2070"
      }
    ],
    "transporte": "transporte-publico"
  }
]
```

## Response Fields

- `tiempoEstimadoArribo`: Estimated travel time in HH:MM:SS format.
- `idHospital`: The destination hospital ID for this route.
- `distanciaMetros`: Total distance of the route in meters.
- `PolylineCode`: Google Maps encoded polyline string representing the route path. This encoding compresses multiple latitude/longitude coordinate points into a single string format that can be decoded to reconstruct the exact route geometry on a map.
- `combinacionesLineas`: One item per step of the route (`legs[].steps[]`), regardless of transport mode. Each item has:
  - `tipoTransporte`: the system transport key for the step (`caminar`, `transporte-publico`, `vehiculo`, `bicicleta`, `vehiculo-dos-ruedas`).
  - `indicaciones`: the navigation instruction text for the step.
  - `nombreLinea`: transit line name (`transitDetails.transitLine.name`), present only for `transporte-publico` steps; `null` otherwise.
- `transporte`: The transport mode used for this route (as requested in the query).

## Frontend Responsibilities

The frontend must:

- **Display route options**: Show all available routes from the backend response.
- **User selection**: Allow the patient to choose which route to follow.
- **No persistence**: The selected route is NOT sent to the backend.
- **Arrival confirmation**: When the patient physically arrives, the frontend calls the queue entry endpoint.
- **Display hospital rating**: Show `valoracionPromedio` and `cantidadValoraciones` next to each hospital. When Google provides no rating (`valoracionPromedio` is `null`), show a "sin valoraciones" indication instead of a score.
- **No rated hospitals message**: When every hospital in the list has `valoracionPromedio` equal to `null`, the frontend shows an appropriate message (e.g. "los hospitales cercanos aún no tienen valoraciones"); the backend does not signal this case — it always returns the full list.

## Backend Limitations

The backend:

- **Does not store route choice**: No database field records which route the patient selected.
- **Does not enforce arrival time**: Queue entry is NOT based on the estimated arrival time.
- **Provides options only**: The `/tiempo-arribo` endpoint is informational, not prescriptive.

## Queue Entry

The consultation enters the queue at hospital selection (`POST /api/atencion/hospital`):

- The consultation becomes `EN_COLA` and an `EntradaCola` is created with default priority in the assigned sector's `GestorDeCola`.
- Queue position and estimated attention time are calculated based on current queue state of that sector.
- The AI triage chat is optional; when it finishes, the `EntradaCola` priority is updated with the pretriage result.

Patients who temporarily left the queue return through (`PacienteEsperaController.reincorporarseACola`):

```http
POST /api/paciente/consulta/cola/reincorporar
```

Formerly `POST /api/paciente/consulta/llegue`.

- This is independent of the arrival time estimation.
- The patient can return to the queue regardless of whether they followed the estimated time.
- Queue position and estimated attention time are recalculated at this moment based on current queue state.

## Public Transport Considerations

When `transporte` indicates public transport:

- Multiple routes may be returned (different bus lines, combinations, etc.).
- Real-time delays are not accounted for in estimates.
- The patient must decide which route best fits their current situation.
- The backend cannot predict actual arrival time due to traffic, delays, or patient decisions.

## Key Principle

The backend provides information; the frontend and patient make decisions. The system does not track or validate the patient's actual route or arrival time against the estimates.
