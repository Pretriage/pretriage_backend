# API Reference

This is a practical reference for the main flows. It is not a full OpenAPI replacement.

## HTTP errors

Errors handled by the API use a JSON body with an `error` field unless an
endpoint documents a validation field map. For requests that reach MVC, a
missing resource returns a JSON `404` with `{"error":"Recurso no encontrado."}`,
including when the client requested an SSE response; authentication can reject
a request before MVC. A request whose `Content-Type`
is not accepted by the endpoint returns `415` with
`{"error":"Tipo de contenido no soportado."}`. These generic responses do
not include request paths or internal exception details. Other endpoint-specific
status codes and error bodies are documented with their respective flows.
If a client closes an SSE connection, the backend ends that stream without
trying to send a JSON error body on the closed `text/event-stream` response.

## Auth

### Register

```http
POST /api/register
```

Public self-registration. Only patient accounts are allowed; staff or admin
roles are rejected with `403`.

`rol` is a required `RolSistema` enum, validated with `@NotNull`. If it is
omitted or `null`, the endpoint returns `400` with
`{"rol":"Es obligatorio ingresar el rol del usuario"}` before creating any
Auth0 account or local patient. A patient registration must send `"rol":"USER"`.

Body:

```json
{
  "nombre": "Juan",
  "apellido": "Perez",
  "numeroDocumento": "30111222",
  "tipoDocumento": "DNI",
  "tipoUsuario": "Paciente",
  "email": "user@example.com",
  "password": "secret",
  "rol": "USER"
}
```

Creates the Auth0 account and the local patient record. The password must
contain between 8 and 72 characters including uppercase, lowercase, numeric and
symbol characters.

When the Auth0 account already exists, the backend verifies the supplied credentials.
A retry for the same Auth0 identity returns `200` if the patient record already
exists and preserves that record. If the Auth0 account exists without a local
patient record, the request can complete the registration.

### Login

```http
POST /api/login
```

Body:

```json
{
  "email": "user@example.com",
  "password": "secret"
}
```

Returns token data. The response (`LoginResponseDTO`) contains `token` (`access_token`), `refreshToken`, and `renovarTokenEn` (seconds until `access_token` expiry, from Auth0 `expires_in`). Login requests `offline_access` scope so Auth0 always returns a rotating `refresh_token`.

### Renovar Sesión (Refresh con Rotación)

```http
POST /api/renovar
```

Público (no requiere Bearer). Implementa refresh token con rotación delegada a Auth0: cada uso genera un nuevo `refresh_token` e invalida el anterior.

Body (`RefreshTokenRequest`):

```json
{
  "refreshToken": "v1.Mk2x...long-refresh-token"
}
```

Validación: `refreshToken` es `@NotBlank` -> `400` con `{ "refreshToken": "es obligatorio el uso de refreshToken para renovar el acceso" }` si está vacío/blanco/ausente.

Success `200`:

```json
{
  "token": "eyJhbGciOi...",
  "refreshToken": "v1.NuevoRefreshTokenRotado",
  "renovarTokenEn": 86400
}
```

Errores:

* `401 { "error": "El refresh token es inválido o expiró. Iniciá sesión nuevamente." }` cuando Auth0 responde `invalid_grant` / `invalid_request` / `Unknown or invalid refresh token` (token expirado, revocado o ya rotado). Mapeado por `AuthService.renovarTokenUsuario` -> `RefreshTokenInvalidoException` -> `GlobalExceptionHandler` `401`.
* `400` para otros fallos de Auth0 (configuración, red) con mensaje de `NoSePudoCrearUsuario`.

Frontend debe guardar `refreshToken` de login y de cada `/renovar` (rotación) y reemplazar el anterior. Ante `401` debe forzar re-login y descartar el refresh almacenado. No hay persistencia local de refresh en backend (stateless proxy a Auth0).

### Solicitar Token de Cambio de Contraseña

```http
POST /api/auth/cambio-contrasenia/solicitar-token
```

Público (no requiere Bearer). No enumera usuarios: siempre responde `200` genérico por privacidad.

Body (`SolicitarTokenCambioContraseniaRequest`):

```json
{
  "email": "user@example.com"
}
```

Validación: `@NotBlank @Email` -> `400` si formato inválido.

Success `200` (siempre, exista o no el usuario):

```json
{
  "message": "Si el correo existe, le enviamos un token al correo indicado, por favor, revise su mail e ingrese el token"
}
```

Cuando el email existe: valida rate limit (`pretriage.cambio-contrasenia.max-solicitudes-por-hora` en ventana `pretriage.cambio-contrasenia.ventana-horas`, defecto `3/1h`) -> `429 { "error": "Superaste el límite..." }` si se supera; invalida `PENDIENTE` previos a `INVALIDADO`, crea `CambioContraseniaToken` (`PENDIENTE`, `fechaHoraCreacion=now`, `fechaHoraExpiracion=now+pretriage.cambio-contrasenia.expiracion-minutos` defecto `15`), token opaco `TokenService` (SecureRandom 32 bytes base64url), y envía email vía `PasswordResetEmailPort` (`smtp`/`local`) con token, fecha de vencimiento `dd/MM/yyyy HH:mm America/Argentina/Buenos_Aires` y aviso "si no fuiste vos ignorá / no compartas". Cuando el email no existe: no crea token ni envía email, pero responde el mismo `200`.

El token se guarda en claro (plain) para que el aviso del email tenga sentido; no se hashea.

### Validar Token de Cambio de Contraseña

```http
GET /api/auth/cambio-contrasenia/validar?token={token}
```

Público. Valida estado y expiración.

Success `200`:

```json
{
  "valido": true,
  "message": "Token válido"
}
```

Errores `400 { "error": "el token no es válido o venció, por favor, solicite un nuevo token o revise si escribió bien el token" }` cuando: token inexistente, `estado != PENDIENTE` (`CAMBIADO`/`EXPIRO`/`INVALIDADO`), o `expiro()==true` (se marca `EXPIRO` y persiste). El frontend debe mostrar el error en rojo y ofrecer botón "solicitar nuevo token".

### Cambiar Contraseña

```http
POST /api/auth/cambio-contrasenia
```

Público. El frontend debe verificar que ambas contraseñas coincidan y enviar solo la nueva.

Body (`CambiarContraseniaRequest`):

```json
{
  "token": "43-char-base64url-token",
  "nuevaContrasenia": "NuevaPass123!"
}
```

Validación: `token @NotBlank`, `nuevaContrasenia @NotBlank @Size(8,72)` -> `400` con mapa de campos.

Success `200`:

```json
{
  "message": "Contraseña cambiada con éxito"
}
```

Flujo backend (`CambioContraseniaService.cambiarContraseña`): `obtenerCambioContraseniaToken` -> `validarToken` (`expiro()` -> `EXPIRO`) -> `llamarApiCambioContrasenia(auth0Id, nuevaPass)` (`PATCH /api/v2/users/{auth0Id}` con `{ "password": nuevaPass, "connection": "Username-Password-Authentication" }` y M2M `Bearer` de `AuthService` pattern `client_credentials` -> `update:users` scope). Mapea `PasswordStrengthError` -> `400` con mensaje amigable. Si OK: `estado=CAMBIADO`, persiste, `INVALIDADO` para otros `PENDIENTE` del usuario. El backend toma `auth0Id` del token, no del body.

Errores:

* `400 { "error": "el token no es válido o venció..." }` (mismo que validar)
* `400 { "error": "La contraseña es demasiado débil..." }`
* `500/400 { "error": "No se pudo cambiar la contraseña..." }` para fallos Auth0 de red/config.

Luego el frontend redirige a login con mensaje de éxito.

Config (`application.properties`):

```properties
pretriage.cambio-contrasenia.expiracion-minutos=${PRETRIAGE_CAMBIO_CONTRASENIA_EXPIRACION:15}
pretriage.cambio-contrasenia.max-solicitudes-por-hora=${PRETRIAGE_CAMBIO_MAX_POR_HORA:3}
pretriage.cambio-contrasenia.ventana-horas=${PRETRIAGE_CAMBIO_VENTANA_HORAS:1}
pretriage.cambio-contrasenia.email.mode=${PRETRIAGE_CAMBIO_EMAIL_MODE:${PRETRIAGE_INVITATIONS_EMAIL_MODE:local}}
pretriage.cambio-contrasenia.email.from=${PRETRIAGE_CAMBIO_EMAIL_FROM:${PRETRIAGE_INVITATIONS_EMAIL_FROM:no-reply@pretriage.local}}
```

Endpoints públicos en `SpringSecurityConfig` (`/api/auth/cambio-contrasenia/solicitar-token`, `/api/auth/cambio-contrasenia/validar`, `/api/auth/cambio-contrasenia`). El endpoint autenticado `/api/auth/cambio-contrasenia/actual` NO es público (ver debajo).

### Cambiar Contraseña (autenticado)

```http
POST /api/auth/cambio-contrasenia/actual
```

Requiere JWT (`Authorization: Bearer ...`): NO está en los `permitAll` de `SpringSecurityConfig`, cae en `.anyRequest().authenticated()`. El usuario siempre envía la contraseña actual y la nueva. El sujeto del JWT (`jwt.getSubject()`) debe corresponder a un `UsuarioAuth` local.

Body (`CambiarContraseniaAutenticadaRequest`):

```json
{
  "contraseniaActual": "ContraActual123!",
  "contraseniaNueva": "NuevaPass123!"
}
```

Reglas de la contraseña nueva (anotaciones estándar de Bean Validation sobre `CambiarContraseniaAutenticadaRequest`: `@NotBlank` + un `@Pattern` por regla): 8-32 caracteres (contados por punto de código Unicode, `ñ` y acentos cuentan como 1), al menos una mayúscula, al menos una minúscula y al menos un número o carácter especial (todo carácter que no sea letra, ni dígito, ni espacio). La validación se ejecuta en el controller con `@Valid`, antes de la lógica de cambio: los fallos de fortaleza NO cuentan como intento fallido.

Success `200`:

```json
{
  "message": "Contraseña cambiada con éxito"
}
```

Errores:

* `401` sin cabecera `Authorization`, con token inválido o expirado (resource server).
* `400 { "contraseniaNueva": "<mensaje de la regla incumplida>" }` cuando la nueva no cumple fortaleza o está vacía: la validación vive en las anotaciones del DTO (`@NotBlank` + `@Pattern` por regla) y se resuelve con `@Valid` en el controller ANTES de invocar la lógica de cambio; estos fallos de fortaleza NO cuentan como intento fallido.
* `400 { "contraseniaNueva": "La contraseña nueva debe ser distinta de la contraseña actual" }` o `400 { "contraseniaActual": "La contraseña actual es incorrecta" }`: llegan a la lógica de cambio y SÍ cuentan como intento fallido.
* `400 { "error": "La contraseña es demasiado débil..." }` cuando Auth0 rechaza el guardado (`NoSePudoCambiarContraseniaException`); también cuenta como intento fallido.
* `423 { "error": "El cambio de contraseña está bloqueado por intentos fallidos. Podrás intentarlo nuevamente en N minuto(s)." }` tras superar el máximo de intentos (`CambioContraseniaBloqueadoException` -> `423 Locked`).
* `500 { "error": "Ocurrió un error inesperado. Intentá nuevamente más tarde." }` cuando el JWT es válido pero no existe el `UsuarioAuth` local (divergencia Auth0/base local; se registra log WARN, sin detalles internos en la respuesta).

Flujo backend: el controller valida el DTO con `@Valid` (anotaciones estándar `@NotBlank` + `@Pattern` por regla de fortaleza; un fallo responde `400` sin invocar al servicio y sin contar como intento fallido) y luego `CambioContraseniaAutenticadoService.cambiarContrasenia` resuelve `UsuarioAuth` por `jwt.getSubject()` -> verifica bloqueo (`BloqueoCambioContrasenia.estaBloqueado`; vencido el bloqueo el contador vuelve a 0) -> rechaza que la nueva sea igual a la actual -> verifica la contraseña actual contra Auth0 (`AuthService.obtenerTokenParaLogearUsuario`, grant `password-realm`; el token se descarta) -> guarda la nueva vía `AuthService.guardarContraseniaEnAuth0` (`PATCH /api/v2/users/{id}` con M2M `client_credentials`, método compartido con el flujo por token). Cualquier intento que llegue a la lógica de cambio y no termine en éxito incrementa `intentosFallidos` (una fila por usuario en `bloqueo_cambio_contrasenia`); al alcanzar `max-intentos` se bloquea `horas-bloqueo` desde el último fallo. El éxito reinicia el contador. El bloqueo aplica SOLO a este endpoint: no afecta el resto de la aplicación ni el flujo por token.

Config (`application.properties`):

```properties
pretriage.cambio-contrasenia-autenticado.max-intentos=${PRETRIAGE_CAMBIO_AUTENTICADO_MAX_INTENTOS:3}
pretriage.cambio-contrasenia-autenticado.horas-bloqueo=${PRETRIAGE_CAMBIO_AUTENTICADO_HORAS_BLOQUEO:24}
```

## Specialties

### List Specialties

```http
GET /api/especialidades
```

Returns available medical specialties sorted by name.

## Hospitals

### Nearby Hospitals Filtered By Specialty

```http
GET /api/hospitales/cercanos?latitud=-34.6&longitud=-58.4&codigoEspecialidad=CLINICA_MEDICA&transporte=transporte-publico&ordenarPor=distancia
```

Returns only hospitals registered in the app that support the selected specialty and lie within 5 km (geodesic distance). Candidates come from `RepoHospitales.findDistinctByEspecialidadesCodigo`; Google Place Details enriches only those registered Place IDs. Nearby Search and its 20-result limit do not select candidates. Only hospitals available for attention (`disponible=true` — at least one `SesionAtencionMedica.ACTIVA` for that specialty) are returned; an empty list means the frontend must show "no hay hospitales disponibles" and is not an error. Each hospital includes `tiempoEstimadoArriboMejorRuta` (estimated arrival time of the best route for the transport mode; `transporte` is optional, defaults to `transporte-publico`, and the field is null when no route can be computed) plus the estimated attention wait for a new arrival: `pacientesEnCola`, `minutosEsperaEstimados`, `fechaHoraAtencionEstimada`, `disponible` (always `true` in the response), and its Google Places rating: `valoracionPromedio` (float on Google's 1.0–5.0 scale, or `null` when Google provides no rating — absence of data, not an error) and `cantidadValoraciones` (review count, or `null`); ratings are fetched live from Google on every call and never persisted. `ordenarPor` is optional, defaults to `distancia` when omitted or empty (geodesic distance from patient coordinates); valid values live in `AtencionHospitalService.ORDENES_VALIDOS` (`distancia`, `tiempo-atencion`, `valoracion` y combinados con `|` como `distancia|tiempo-atencion` o `distancia|tiempo-atencion|valoracion`, orden indistinto). Values are case-sensitive (only exact lowercase, e.g. `Valoracion` returns `400`), and a combination containing any invalid or duplicated criterion returns `400` even if the other criteria are valid. `tiempo-atencion` orders by lower `minutosEsperaEstimados` (see `docs/04-queue-and-estimation.md#end-of-queue-estimate-for-hospital-ranking`), tie-broken by arrival time then name; `valoracion` orders by `valoracionPromedio` descending treating `null` as 0 (hospitals without rating go last and are never excluded), tie-broken by `cantidadValoraciones` descending (null = 0) then the Google `nombre` ascending; el combinado ordena por suma de rankings (distancia geodésica + tiempo + valoración) con desempate por `nombre` y luego orden por distancia geodésica. Invalid values return `400`. The response is always a plain list (no wrapper): when every hospital has `valoracionPromedio = null`, the frontend shows an appropriate "sin valoraciones" message — the backend does not signal this case. `transporte` valid values: `transporte-publico`, `vehiculo`, `vehiculo-dos-ruedas`, `caminar`, `bicicleta` (see `docs/12-hospital-selection-and-arrival.md`).

### Select Hospital

```http
POST /api/atencion/hospital
```

Body:

```json
{
  "placeId": "google-place-id",
  "codigoEspecialidad": "CLINICA_MEDICA"
}
```

Assigns the hospital and specialty to the active consultation and enters it into
the queue (`EN_COLA` + `EntradaCola`) with default priority (`NORMAL`). The AI
triage chat is optional; when it finishes, the queue priority is updated with the
pretriage result.

Returns `204 No Content`. The dynamic estimate is available via `GET /api/atencion/tiempo-estimado` (`TiempoEstimadoAtencionResponse` with `consultaId`, `fechaHoraAtencionEstimada`, `posicionEnCola`, `pacientesAntes`, `minutosPromedioAtencion`, `hayMedicosActivos`, `medicosActivos`, `medicosParaEstimacion`, `sectorId`, `nombreSector` from `ConsultaMedica.sector`, `codigoSala` from `Sala.nombre` — `null` until the doctor calls the patient — and `mensaje` when `hayMedicosActivos=false`).

### Get Selected Hospital

```http
GET /api/atencion/hospital
```

Returns the hospital selected in the active consultation of the authenticated
patient, together with its formatted address. `direccion` is `null` when the
hospital has no stored `Direccion`.

Both `nombre` and `direccion` come from the registered hospital record; this
endpoint does not fill them from Google Places at read time. A stored Place ID
alone does not create a structured address. Mobile shows “Dirección no disponible”
for a null address. The local Clínicas data repair and repeatable load are
documented in [07-local-development.md](07-local-development.md#local-hospital-development-data).

Example response:

```json
{
  "idHospital": 1,
  "placeId": "google-place-id",
  "nombre": "Hospital Central",
  "direccion": "Av. Siempre Viva 742, CABA, Buenos Aires"
}
```

### Calculate Arrival Time to Hospital

```http
GET /api/hospitales/{idHospital}/tiempo-arribo?transporte=transporte-publico&latitud=-34.6&longitud=-58.4
```

Returns list with routes with estimated travel time from patient's location to the specified hospital.

## User Profile

### Get Profile

```http
GET /api/perfil
```

Returns the authenticated patient profile: identity, document, birth date,
genders, contact, stored address, weight and height.

### Update Profile

```http
PUT /api/perfil
```

Body:

```json
{
  "nombre": "Juan",
  "apellido": "Perez",
  "tipoDocumento": "DNI",
  "numeroDocumento": "30111222",
  "fechaNacimiento": "1990-05-10",
  "generoBiologico": "MASCULINO",
  "generoConElQueSeIdentifica": "MASCULINO",
  "email": "user@example.com",
  "telefono": "1155551234",
  "calle": "Av. Siempre Viva",
  "alturaDireccion": "742",
  "piso": "3",
  "codigoPostal": "1414",
  "ciudad": "CABA",
  "provincia": "Buenos Aires",
  "peso": 75.5,
  "alturaPersona": 180
}
```

If the patient has no stored address, a `Direccion` is created; otherwise the
stored address is updated with the submitted values.

## Estimated Attention Time

```http
GET /api/atencion/tiempo-estimado
```

Returns dynamic estimate based on `EntradaCola` (`EN_COLA` only, ordered by `prioridad DESC`, `ordenRelativo ASC`, `fechaHoraIngreso ASC`) and active doctor sessions (`SesionAtencionMedica` `ACTIVA`; if none, estimates with one virtual doctor and `hayMedicosActivos=false`). Response is `TiempoEstimadoAtencionResponse` with `consultaId`, `fechaHoraAtencionEstimada`, `posicionEnCola`, `pacientesAntes`, `minutosPromedioAtencion`, `hayMedicosActivos`, `medicosActivos`, `medicosParaEstimacion`, `sectorId`, `nombreSector` (`ConsultaMedica.sector`), `codigoSala` (`Sala.nombre`, `null` until `LLAMADO`/`EN_ATENCION` when a room is assigned), and `mensaje` when no doctors are active. See also `POST /api/atencion/hospital` which returns the same payload at queue entry.

## Chat

### Start Chat

```http
POST /api/chat
```

Always creates a **new** chat (`ChatService.iniciarChat`); a patient can have many chats over time (`Chat.paciente` is `@ManyToOne`). Before creating it:

1. **Pending-attention check**: if the patient has a `ConsultaMedica` with a linked chat (`ConsultaMedica.chat IS NOT NULL`) whose `EntradaCola` (`RepoEntradasCola.findByConsultaMedicaId`) is in a state other than `FINALIZADA`/`CANCELADA`, the request fails with `400 { "error": "Ya tenés una atención pendiente. Esperá a que finalice o cancelala antes de iniciar un nuevo pretriage." }` (`AtencionPendienteException`). Nothing is created and previous chats are not modified. Reception admissions (no chat) and chats still in progress do not block.
2. **Close previous open chats**: every chat of the patient with `finalizado=false` (`RepoChat.findAllByPacienteUsuarioAuthIdAndFinalizadoFalse`) is marked `finalizado=true`, so at most one chat stays open. A client still holding the id of a closed chat gets `400 ChatFinalizadoException` on `POST /api/chat/{id}/mensajes` and must start a new chat.

Success `200 ChatDTO` with the bot greeting as first message.

### Send Message

```http
POST /api/chat/{id}/mensajes
```

Body:

```json
{
  "contenido": "Tengo fiebre desde ayer",
  "solicitudId": "b8ce94e9-83fd-48c1-92e3-36b7ae45cc77"
}
```

Returns **`202 ChatDTO`** after committing the patient message and durable
`estadoTurnoTexto=PENDIENTE`. `solicitudId` is an optional UUID; current clients
send one stable key per submission. A known key and identical content return the
current snapshot without adding another message, including after subsequent turns.
Reusing a key with different content, or sending a new key while `PENDIENTE` or
`NO_DISPONIBLE`, returns `409`. Invalid UUID format returns `400`.

The backend processes the saved turn asynchronously. Clients poll GET; they do
not replay the POST after losing its response. `ChatDTO` includes `estadoTurnoTexto`
(`SIN_SOLICITAR`, `PENDIENTE`, `COMPLETADA`, `NO_DISPONIBLE`),
`solicitudTurnoTextoId`, `intentosTurnoTexto`, `cierreParcial`, `estadoRecuperacion`,
`proximoReintentoEn` and `versionEstado`. State and messages survive screen
navigation and process restart. `COMPLETADA` describes the text turn; final triage
must still be checked through `finalizado` and `estadoClasificacion`.
See [durable text turns](24-durable-text-turns.md).

Internally a verified turn uses `ChatTurnResponse` with `respuesta`,
`atencionEstimada` and `origenRespuesta`; this is no longer the HTTP response body
of this endpoint. When a valid triage result finalizes, the existing queue entry's
priority is updated. Partial classification preserves the provisional initial
priority and queue position. When the bot finalizes, the patient's `ConsultaMedica`
is linked to this chat (`ConsultaMedica.chat`, FK `consulta_medica.id_chat`); metrics
count it as "pretriage realizado" only for `COMPLETADA` or a legacy null state with a
stored result. The final stored `Chat.resultadoTriageJson` includes
`origenClasificacion` with the source of that classification, plus backend-owned
`seguimientoEntrevista` metadata containing closure reason and datum states/attempts
(without duplicating values or citations). Historical results without it remain valid.
An HTTP success may also represent scope handling (`origenRespuesta=CONTROL_CONTEXTO`)
after Gemini marks input `AMBIGUO` or `FUERA_DE_CONTEXTO`: ambiguity uses a verified
clarification, off-topic input a fixed redirection. These turns have
`atencionEstimada=null`, keep the chat open and store no classification.
Excluded patient text remains visible locally and counts toward technical resource
limits, but is not resent as provider context or accepted as clinical evidence.
Provider failures and invalid model responses are recorded by the asynchronous
worker. Technical failures and provider outages use the durable policy in
[automatic chat recovery](34-automatic-chat-recovery.md): internal failures follow
the persisted workflow budget; typed transient API outages use persisted backoff
until recovery or consultation invalidation. `NO_DISPONIBLE` remains a client
cache/compatibility state, not a terminal server failure. If interviewing cannot
continue, saved answers are closed and classification is attempted from available
facts. Failure to obtain a valid classification ends in `PARCIAL`, without an AI
result or queue-priority update.
During interviewing, Gemini receives a question schema (`mensaje`, `estadoEntrada`); the
backend decides when to request final classification. The internal continuation
input also carries backend-generated `objetivoDelTurno` (type, canonical symptom
ID and dimension, without values or excluded text). Both generation and semantic
verification use it; recognizable local mismatches permit one correction. This
does not add fields to the public `ChatTurnResponse`. A relevant or ambiguous candidate is
checked independently for scope and consistency with patient facts before it is
shown. A malformed, out-of-scope or repeated question permits one remote correction.
A successful question returns a normal
`GEMINI` turn with `atencionEstimada=null`. The shared policy tracks objectives for
each reported symptom and datum, plus one open invitation. It allows three attempts
per datum, 24 total patient turns and four consecutive replies without progress;
the old eight-answer/four-question conditions do not establish completion. Each turn asks about one
concept. Saying there are no other symptoms stops the search for new symptoms while
allowing bounded follow-up about reported symptoms; saying they do not want to
continue ends the interview. Unknown or unmeasured values remain unknown without
repetition, and a clear alarm closes immediately. See the [interview policy](17-simple-chat-questions.md).
When resources are exhausted without comprehensible clinical facts, a terminal
response uses `origenRespuesta=CIERRE_SIN_DATOS`, `atencionEstimada=null`,
`finalizado=true` and `estadoClasificacion=NO_SOLICITADA` in the authoritative GET.
No result or queue update is created. Clients synchronize GET after that origin;
they must not infer `COMPLETADA` from interview closure alone. See
[closure by resolved data](21-cierre-por-datos-resueltos.md).
Historical retry details are in [Gemini REST retries](19-gemini-retries.md).
Current recovery, including retry budgets and durable API backoff, is defined in
[automatic chat recovery](34-automatic-chat-recovery.md). The patient does not
resend a saved message; final-classification exhaustion closes as `PARCIAL` with no
result or queue-priority update.
Existing
historical chat results may still contain `FALLBACK_LOCAL` or `OLLAMA`.
For a final result requiring immediate attention, `respuesta.contenido` includes
the structured `recomendacionSeguridad` so the urgent advice is visible in chat.

Intermediate question-only responses contain no clinical result. In structured
results, `observaciones` may be an empty string when no additional
notes are available. During interviewing, pending descriptive strings
(`motivoConsulta`, `inicio`, `evolucion`, `posibilidadEmbarazo`) may also be empty;
they must be nonblank in a final result. Keys and string types remain required.
The safety recommendation may be blank only in an unfinished non-urgent turn;
it remains nonblank for every final or immediate-attention response.

### Get Chat

```http
GET /api/chat/{id}
```

Returns messages in `fechaHoraEnvio ASC, id ASC` order. The message ID is an
internal tie-breaker and is not added to the DTO. Voice transcripts can share the
same batch-persistence timestamp; equal timestamps must preserve saved turn order
instead of relying on database row order. The same ordering is used when the
backend reconstructs interview progress, voice context and classification input.
This read order also applies to existing chats without rewriting their messages.
The returned text-turn status is authoritative even if the POST response was lost
or the client left the screen while the provider was answering.

### Retry Processing a Closed Interview

This endpoint remains for compatibility with legacy chats in state `FALLIDA`.
Current mobile does not expose a reprocessing button. New voice closures and final
text classification work are recovered by the backend; automatic API outage retries
use persisted backoff, while internal failures follow the workflow budget. Automatic
work never replays a patient message. See [automatic chat recovery](34-automatic-chat-recovery.md).

```http
POST /api/chat/{id}/clasificacion
```

Authenticated patient, no request body. Processes saved answers without adding a
message. Requires the caller's latest chat in legacy `FALLIDA` classification state.
An owned automatic `PENDIENTE` job is not accepted by this public retry endpoint.
Returns `200 ChatTurnResponse` on success; only a valid Gemini result updates the
queue. Completed/open/historical chats are rejected with `ChatFinalizadoException`
(`400`); another patient's chat is not accessible. Concurrent retries are serialized
by a database row lock. New failures are handled by automatic recovery and close as
`PARCIAL` without a result when no valid classification can be produced. This endpoint
retains `FALLIDA` handling for legacy compatibility only.

`GET /api/chat/{id}` and chat creation include `estadoClasificacion`:
`NO_SOLICITADA`, `PENDIENTE`, `COMPLETADA`, legacy `FALLIDA`, or `PARCIAL`;
`cierreParcial` marks partial closure. `estadoRecuperacion` is `SIN_RECUPERACION`,
`REINTENTANDO`, or `ESPERANDO_API`; `proximoReintentoEn` is nullable, and
`versionEstado` increases on confirmed state transitions. `finalizado=true` means
the interview is closed, not that it has a stored classification. After closure,
`POST /mensajes` rejects additional input; clients follow backend-owned processing
with GET. No clinical priority is fabricated on failure and the receptionist form
fallback is unaffected.

### Voice Chat (Gemini Live)

```http
GET /api/chat/{id}/voz?access_token={jwt}   (WebSocket upgrade)
```

Speech-to-speech interview for an existing open chat: Gemini Live asks the
questions; when it has enough data the session ends and Gemini REST preclassifies the
collected summary (see `docs/05-ai-triage.md#voice-chat-gemini-live`).
The JWT may be sent as `Authorization: Bearer` or, because browsers cannot set
headers on a WebSocket handshake, as the `access_token` query parameter
(accepted only on this path). Handshake errors: `401` unauthenticated, `404`
chat missing or owned by another patient, `409` chat already finalized or text
turn `PENDIENTE`/`NO_DISPONIBLE`, `503`
Gemini Live not configured (`GEMINI_API_KEY` empty).

Client -> server:

- Binary frames: raw PCM 16-bit mono little-endian at 16 kHz (max 256 KB per frame).
- Text `{"tipo":"fin_audio"}`: microphone paused (sends `audioStreamEnd`).
- Text `{"tipo":"cerrar"}`: closes the mobile channel. Before the interview is
  accepted as complete, the backend attempts to save the transcription without finalizing the chat.
  After acceptance, backend closure processing continues; mobile can retrieve
  the persisted chat state by HTTP when reconnecting.

Server -> client:

- Binary frames: PCM 16-bit mono little-endian at 24 kHz spoken by Gemini.
- `{"tipo":"listo"}`: Gemini is ready; start streaming audio.
- `{"tipo":"transcripcion_paciente","texto":...}` /
  `{"tipo":"transcripcion_bot","texto":...}`: incremental transcription chunks.
- `{"tipo":"interrumpido"}`: patient spoke over Gemini; drop queued playback.
- `{"tipo":"procesando_respuesta","activo":true|false}`: transient waiting for a
  useful assistant response. Show a neutral processing indicator while active;
  keep the microphone/interruption available. This event does not mark interview
  closure or clinical-classification completion. Clear it on accepted closure,
  error, session end or a useful assistant question.
- `{"tipo":"turno_completo"}`, `{"tipo":"sesion_por_expirar"}`,
  `{"tipo":"error","mensaje":...}`.
- `{"tipo":"entrevista_finalizada","resumen":ResumenEntrevistaVoz}`: the backend
  confirmed interview closure; microphone audio is ignored from now on. `resumen`
  is optional: it is absent when the backend recovers a missing Live tool call
  using the saved transcript and the existing final-classification processor.
- `{"tipo":"triage_finalizado","respuesta":MensajeDTO,"atencionEstimada":...,"origenRespuesta":...}`:
  Gemini REST classification succeeded, with the same meaning as the final
  internal `ChatTurnResponse` used to persist a completed text turn.
- `{"tipo":"estado_chat","chat":ChatDTO}`: the canonical chat snapshot while
  server-owned processing continues or recovers. `ChatDTO` includes
  `estadoClasificacion`, `cierreParcial`, `estadoRecuperacion`,
  `proximoReintentoEn` and `versionEstado`.
- `{"tipo":"procesamiento_finalizado","chat":ChatDTO}`: classification ended
  without a valid AI result; the snapshot carries `estadoClasificacion=PARCIAL`.
- `{"tipo":"fin"}`: session over; the server then closes the WebSocket.

Keep the backend socket open after `entrevista_finalizada`: classification is
still pending. `triage_finalizado` is sent only after successful classification
and persistence. A partial outcome sends `procesamiento_finalizado` with the
authoritative snapshot; clients can still GET the chat after reconnecting. Legacy
`FALLIDA` may be present in historical records, while current failed processing
closes as `PARCIAL`. A rejected summary keeps the interview open and does not emit
`entrevista_finalizada`.

  When shared progress is terminal, one tool-call reminder plus bounded timers
  can recover closure without another patient response. This applies to resolved
  data, alarms, patient stop requests and partial resource/no-progress endings.
  An unclarified additional contribution first receives a bounded clarification;
  it is not silently treated as resolved data. Pending data, an unchanged rejected
  scope preflight or late patient input prevent automatic classification.
  Transcript-only recovery uses
the existing bounded Gemini REST classification and verification; it creates no
local clinical summary or priority. See the interaction guide for drain timing.

Voice and text turns share the same `Chat`.
See [the interaction guide](14-chat-voz-gemini-live.md) for the full sequence,
mobile responsibilities, persistence and connection recovery semantics.

## Patient Queue State

All endpoints in this section are under `PacienteEsperaController` (`EsperaPacienteService`) and operate on the active `EntradaCola` of the authenticated patient. Every response is `EstadoConsultaPacienteDTO` (`consultaId`, `estadoConsulta`, `estadoEntradaCola`, `tipoPausa`, `fechaHoraLimiteRespuesta`, `sectorId`, `nombreSector` from `ConsultaMedica.sector`, `tiempoEstimadoAtencion` which is non-null only when `estadoEntradaCola == EN_COLA`).

```http
GET /api/paciente/consulta/estado
```

Returns the current queue/consultation state for the authenticated patient (no state change). Valid for `EN_COLA`, `LLAMADO`, `EN_ESPERA`, `ATRASADO`, `EN_ATENCION` (see `EsperaPacienteService.obtenerEntradaActivaPaciente`). When the entry is `EN_COLA`, the payload includes the dynamic estimate from `EstimacionAtencionService.calcularPara` (`TiempoEstimadoAtencionResponse`). Example: `docs/04-queue-and-estimation.md`.

### Pausar Cola Manualmente (salida voluntaria)

```http
POST /api/paciente/consulta/cola/pausa-manual
```

Patient voluntarily leaves the queue. Requires `EntradaCola.EN_COLA`, transitions to `EN_ESPERA` with `TipoPausaCola.ESPERA_MANUAL`, sets `ConsultaMedica.EN_ESPERA` and `fechaHoraSalidaTemporal=now`. While `EN_ESPERA` the entry does not count for estimation (`docs/04-queue-and-estimation.md#entries-counted-for-estimation`). Automatically cancelled to `CANCELADA` after 60 minutes without return (`EsperaPacienteService.cancelarEsperasVencidas`, `MINUTOS_MAXIMOS_EN_ESPERA=60`). Return path: `POST /api/paciente/consulta/cola/reincorporar` restores the previous relative position (`docs/02-patient-flow.md#waiting-and-absence-rules`). Replaces the former `POST /api/paciente/consulta/ausentarme`. No body; authenticated by `Jwt.getSubject()`.

### Confirmar Atraso (tras ser marcado ausente)

```http
POST /api/paciente/consulta/cola/atraso/confirmar
```

Patient confirms they are delayed after the doctor marked them absent on call. Requires `EN_ESPERA` with `AUSENTE_AL_LLAMADO`, transitions to `ATRASADO` with `ATRASADO_CONFIRMADO`, sets `ConsultaMedica.ATRASADO`, `fechaHoraUltimaRepregunta=now`, `fechaHoraLimiteRespuesta=now+30m` (`MINUTOS_REPREGUNTA_ATRASADO=30`). While `ATRASADO` the entry does not count for estimation. Automatically cancelled after 30 minutes without renewal (`EsperaPacienteService.cancelarAtrasadosSinRespuesta`). Replaces `POST /api/paciente/consulta/estoy-atrasado`.

### Renovar Confirmación de Atraso (sigo asistiendo)

```http
POST /api/paciente/consulta/cola/atraso/renovar
```

Patient still intends to attend while in `ATRASADO`. Requires `ATRASADO` (`ATRASADO_CONFIRMADO`), extends `fechaHoraUltimaRepregunta=now` and `fechaHoraLimiteRespuesta=now+30m`. Does not change `estado`/`tipoPausa`. If not renewed within 30 minutes the entry is cancelled. Replaces `POST /api/paciente/consulta/sigo-asistiendo`.

### Reincorporarse a la Cola (confirmar llegada)

```http
POST /api/paciente/consulta/cola/reincorporar
```

Patient physically arrives and re-enters `EN_COLA`. Accepts two origins (both documented in `docs/02-patient-flow.md` and `docs/12-hospital-selection-and-arrival.md`):
* From `ATRASADO` → `EN_COLA` at **first place within its priority level** (`ordenRelativo = min(ordenRelativo) - 1` for that `prioridad` via `obtenerOrdenParaPrimerLugarDePrioridad`), clears `tipoPausa`, `fechaHoraLimiteRespuesta`, resets `fechaHoraIngreso=now`, `ConsultaMedica.EN_COLA`.
* From `EN_ESPERA(ESPERA_MANUAL)` → `EN_COLA` restoring the **previous relative position** (keeps `ordenRelativo`).
Other states are rejected with `IllegalStateException`. On success `tiempoEstimadoAtencion` is recalculated dynamically. Replaces `POST /api/paciente/consulta/llegue`.

### Cancel Hospital Selection

```http
POST /api/paciente/consulta/cancelar
```

Patient voluntarily and definitively abandons their **active hospital selection** at any point of the attention flow (`EsperaPacienteService.cancelarSeleccion`). The client requests confirmation (dialog) before calling this endpoint, which is **idempotent** and safe to retry. The endpoint deterministically resolves the patient's current selection: it operates on the most recent `EntradaCola` (`RepoEntradasCola.findFirstByConsultaMedicaPacienteIdOrderByIdDesc`). Historical `CANCELADA`/`FINALIZADA` entries from previous selections are ignored, so they can neither shadow the active selection nor produce a spurious `409`/no-op response.

Allowed `EntradaCola` states of the resolved (most recent) entry:

* `EN_COLA`, `LLAMADO`, `EN_ESPERA`, `ATRASADO` → entry transitions to `CANCELADA` (clears `tipoPausa`, `fechaHoraLimiteRespuesta`, `fechaHoraUltimaRepregunta`); `ConsultaMedica` transitions to `CANCELADA` (clears `medico` and `sala`). The entry stops counting for estimation and availability, the doctor can no longer call or re-see the patient (`listarPacientesDisponibles`/`llamarProximo` only read `EN_COLA`, `obtenerSesionActual` only `LLAMADO`/`EN_ATENCION`), and any open AI triage chat is finalized (`RepoChat.findFirstByPacienteUsuarioAuthIdAndFinalizadoFalse`).
* `CANCELADA` → no-op success (idempotent retry).

Rejected with `409 ConflictoDeEstadoException`:

* `EN_ATENCION` → `"No puede cancelar una consulta con atencion en curso"` (consultation in progress).
* `FINALIZADA` → `"La consulta ya finalizo y no se puede cancelar"`.

Rejected with `400 NoSuchElementException` when the patient has no active hospital selection (e.g. only a `PENDIENTE` consultation or no record). No body; authenticated by `Jwt.getSubject()`. Returns `EstadoConsultaPacienteDTO` (`estadoEntradaCola=CANCELADA`, `estadoConsulta=CANCELADA`, `tiempoEstimadoAtencion=null`).

## Doctor

### List Assignments

```http
GET /api/medico/asignaciones
```

Returns the authenticated doctor's hospital and specialty assignments.

### List Sectors

```http
GET /api/hospitales/{hospitalId}/sectores?codigoEspecialidad={codigoEspecialidad}
```

Returns the active sectors (`Sector.activa=true`) of a hospital for the given specialty, ordered by name ASC.

### List Rooms

```http
GET /api/hospitales/{hospitalId}/sectores/{sectorId}/salas?codigoEspecialidad={codigoEspecialidad}
```

Returns the active rooms of the given sector for the given specialty.

### Recover Current Session

```http
GET /api/medico/sesiones/actual
```

Returns the authenticated doctor's active or paused session together with the
currently called or in-attention consultation. Both values are nullable.

### Start Session

```http
POST /api/medico/sesiones
```

Doctor selects hospital/specialty/sector/room (`hospitalId`, `codigoEspecialidad`, `sectorId` and `salaId` in the request body).

### Pause Session

```http
POST /api/medico/sesiones/{id}/pausar
```

### Resume Session

```http
POST /api/medico/sesiones/{id}/reanudar
```

### Close Session

```http
POST /api/medico/sesiones/{id}/cerrar
```

### Call Next Patient

```http
POST /api/medico/sesiones/{id}/llamar-proximo
```

### Mark Patient Absent

```http
POST /api/medico/sesiones/{sesionId}/consultas/{consultaId}/ausente
```

### List Available Patients

```http
GET /api/medico/sesiones/{sesionId}/pacientes-disponibles?dni={dni}
```

Returns ordered `EntradaCola.EN_COLA` consultations for the session hospital, specialty and sector,
including the effective priority, patient name and surname, document (`numeroDocumento`/`tipoDocumento`) and `estadoConsulta`. For queued patients the effective priority is the preliminary backend classification. Room fields remain null until the consultation is called.

`dni` is optional. When provided, the backend filters by exact match on `Paciente.numeroDocumento` (trimmed, `String` exact equals) within the same hospital/specialty/sector queue and ordered by `prioridad DESC, ordenRelativo ASC, fechaHoraIngreso ASC`. The filtering is executed at DB level via `RepoEntradasCola.findByGestorDeColaIdAndEstadoAndConsultaMedicaPacienteNumeroDocumento...` for acceptable response times. Blank or missing `dni` returns the full ordered queue. Non-matching `dni` returns `[]` (empty JSON array, `200 OK`) — the frontend must show the “no coincidences” message.

Example: `GET /api/medico/sesiones/42/pacientes-disponibles?dni=30111222`

Response item (`ConsultaLlamadaDTO`):

```json
{
  "consultaId": 5,
  "codigoLlamado": "A-005",
  "pacienteId": 4,
  "nombrePaciente": "Juan",
  "apellidoPaciente": "Perez",
  "numeroDocumento": "30111222",
  "tipoDocumento": "DNI",
  "prioridad": "URGENTE",
  "estadoConsulta": "EN_COLA"
}
```

### Attention History

```http
GET /api/medico/atenciones
```

### Confirm Patient Present

```http
POST /api/medico/sesiones/{sesionId}/consultas/{consultaId}/presente
```

Creates an `AtencionMedica.EN_CURSO` historical record.

### Read Pretriage And Priority Review

```http
GET /api/medico/sesiones/{sesionId}/consultas/{consultaId}/pretriaje
```

Available only to the doctor/session that owns an `EN_ATENCION` consultation.
Returns the normalized clinical summary, preliminary and effective priorities,
and `PENDIENTE`, `CONFIRMADA`, or `CORREGIDA` review state. It also returns
`estadoClasificacion`, `cierreParcial`, and the literal `mensajesConversacion`.
When a chat closed partially without a valid AI result, `resumenClinico` is null,
`estadoClasificacion` is `PARCIAL`, and the doctor sees the conversation in the
same consultation view. The queue's initial priority is provisional in that case;
the existing doctor review remains available. Authorization remains limited to
the owning active session and its `EN_ATENCION` consultation.

### Confirm Or Correct Priority

```http
PUT /api/medico/sesiones/{sesionId}/consultas/{consultaId}/revision-prioridad
```

Confirm with `{ "decision": "CONFIRMAR" }`. Correct with
`{ "decision": "CORREGIR", "prioridad": "NORMAL", "motivo": "optional" }`.
The corrected priority must differ from the preliminary priority. The operation
is idempotent for an identical payload and preserves every genuine change.

### Finish Attention

```http
POST /api/medico/sesiones/{sesionId}/consultas/{consultaId}/finalizar
```

Finalizes the consultation, queue entry, and historical attention in one operation.
Returns `409` until priority has been reviewed.

### Clinical History Access

During attention, doctors can view patient's previous medical records:

#### Get All Medical Records

```http
GET /api/medico/pacientes/{pacienteId}/historial-clinico
```

Retrieves all medical records (PDFs, images) for a specific patient. Returns list of medical histories with metadata.

#### Download Specific Record

```http
GET /api/medico/pacientes/{pacienteId}/historial-clinico/{estudioId}/archivo
```

Downloads or previews a specific medical record file (e.g., radiology scans, previous reports).

#### Get Record Metadata

```http
GET /api/medico/pacientes/{pacienteId}/historial-clinico/{estudioId}/reporte
```

Retrieves structured information about a specific medical record (type, date, description).

#### Get Recent Records

```http
GET /api/medico/pacientes/{pacienteId}/ultimos-reportes?limite=5
```

Gets up to `limite` most recent active medical records for quick triage reference, ordered by `fechaSubida` DESC. `limite` is optional, defaults to `5`; only studies with `activo=true` are returned. Values `< 1` return `400`.

Pause, close, and call-next operations are rejected while the doctor has a patient `LLAMADO` or `EN_ATENCION`.

## Real-Time Estimated Attention

```http
GET /api/atencion/tiempos/suscribirse/{consultaId}
Accept: text/event-stream
```

Authenticated SSE stream with `tiempo-estimado` and `heartbeat` events. The patient must own the consultation. When the client disconnects, the stream is removed from the active notifier connections; no response body is written for the closed connection.

## Reception Admission

See docs/09-reception-admission.md for the complete reception API and rules.

## Health Insurance (Patient)

> Credential load and update operations validate the health insurance in runtime:
> `CredencialService` resolves the validator for the requested obra social through
> `FabricaValidadoresCredencialesObraSocial` and passes it to
> `ValidacionCredencialObraSocialService` (polymorphism by parameter).
> Each implementation of `ValidadorCredencialObraSocial` declares the obra social
> it covers via `getObraSocial()`. Real integrations with each health insurance
> are out of scope; the only implementation is `MockValidadorCredencialObraSocial`
> (demo, declares `OSDE` and always accepts the credential). The current factory
> returns the first registered validator for every requested name; provider-specific
> selection and the missing-validator exception are in commented-out code. Adding
> catalog names therefore does not verify coverage with an insurer.
> If the selected validator rejects the credential, the API responds `400` with
> `{ "error": "La credencial de obra social no es válida" }` and nothing is persisted.

#### Add a real validator

1. Implement `ValidadorCredencialObraSocial` (e.g. `ValidadorCredencialIOMA`).
2. Annotate it with `@Component`/`@Service` and return the obra social name from
   `getObraSocial()` (e.g. `"IOMA"`).
3. Enable provider-specific selection in `FabricaValidadoresCredencialesObraSocial`
   (currently commented out) and remove the unconditional demo validator selection.

### List Health Insurance Catalog

```http
GET /api/obrasocial
```

Returns the active catalog (`virgente=true`) as `[{ "nombre": "OSDE" }, ...]`.
No catalog entries yields HTTP 200 with `[]`; mobile displays an error for that
empty result. The local seed and reload instructions are documented in
[07-local-development.md](07-local-development.md#local-health-insurance-catalog).
Credential plans are free text, not a separate catalog endpoint.

### List Credentials

```http
GET /api/obrasocial/credenciales
```

Returns all health insurance credentials for the authenticated patient.

### Add Credential

```http
POST /api/obrasocial/credenciales
```

Body:

```json
{
  "numeroAfiliado": "12345678",
  "nombreObraSocial": "OSDE",
  "plan": "classic",
  "fechaVencimiento": "2045-12-31"
}
```

### Delete Credential

```http
DELETE /api/obrasocial/credenciales/{idCredencial}
```

### Update Credential

```http
PUT /api/obrasocial/credenciales/{idCredencial}
```

Body:

```json
{
  "numeroAfiliado": "12345678",
  "nombreObraSocial": "OSDE",
  "plan": "classic",
  "fechaVencimiento": "2045-12-31"
}
```

## Health Insurance (Admin)

### Add Health Insurance

```http
POST /api/obrasocial
```

Body:

```json
{
  "nombre": "OSDE"
}
```

### Delete Health Insurance

```http
DELETE /api/obrasocial/{idObraSocial}
```

## Health Insurance (Receptionist)

> Applies the same runtime-resolved credential validation as the patient flow on
> load and update operations. See the patient section above for behavior and error
> format.

### List Patient Credentials

```http
GET /api/pacientes/{idPaciente}/obrasocial/credenciales
```

### Add Patient Credential

```http
POST /api/pacientes/{idPaciente}/obrasocial/credencial
```

Body:

```json
{
  "numeroAfiliado": "12345678",
  "nombreObraSocial": "OSDE",
  "plan": "classic",
  "fechaVencimiento": "2045-12-31"
}
```

### Delete Patient Credential

```http
DELETE /api/pacientes/{idPaciente}/obrasocial/credenciales/{idCredencial}
```

### Update Patient Credential

```http
PUT /api/pacientes/{idPaciente}/obrasocial/credenciales/{idCredencial}
```

Body:

```json
{
  "numeroAfiliado": "12345678",
  "nombreObraSocial": "OSDE",
  "plan": "classic",
  "fechaVencimiento": "2045-12-31"
}
```

## Medical Studies (Patient)

### Upload Study

```http
POST /api/estudios
```

Multipart form with file and JSON body:

```json
{
  "tipoArchivo": "Radiografía",
  "descripcion": "Radiografía de tórax"
}
```

### Delete Study

```http
DELETE /api/estudios/{idEstudio}
```

### List All Studies

```http
GET /api/estudios
```

Returns metadata for all medical studies of the authenticated patient.

### Get Study Metadata

```http
GET /api/estudios/{idEstudio}
```

### Download Study File

```http
GET /api/estudios/{idEstudio}/file
```

Downloads the actual file (PDF, image, etc.) for the specified study.

## Hospital Configuration (Hospital Admin)

All operations are scoped to the hospital in the URL and require an active
`ADMIN_HOSPITAL` membership for that hospital.

### Get Configuration

```http
GET /api/admin/hospitales/{hospitalId}/configuracion
```

Returns the hospital's enabled specialties, rooms and sectors. Response is `ConfiguracionHospitalResponse` with `especialidades`, `salas` and `sectores` (`SectorHospitalResponse` list, ordered by `nombre` ASC).

Example `sectores` item:

```json
{
  "id": 10,
  "nombre": "Sector Rojo",
  "especialidadId": 1,
  "especialidadCodigo": "CLINICA_MEDICA",
  "especialidadNombre": "Clínica médica"
}
```

### Enable or Disable Specialty

```http
POST   /api/admin/hospitales/{hospitalId}/configuracion/especialidades/{especialidadId}
DELETE /api/admin/hospitales/{hospitalId}/configuracion/especialidades/{especialidadId}
```

`DELETE` disables the specialty for the whole hospital; it requires no sector or request body. `ADMIN_HOSPITAL` authorization is checked against the hospital. It returns `200 ConfiguracionHospitalResponse`, including when the specialty is already disabled (no duplicate save or audit). Unknown hospital or specialty returns `404`.

If the specialty is enabled and has any active room in this hospital, the operation returns `409 { "error": "Desactivá las salas de la especialidad antes de quitarla del hospital" }`. The check uses `RepoSalas.existsByHospitalIdAndEspecialidadIdAndActivaTrue`, covers every sector and legacy rooms with no sector, and ignores rooms of other hospitals or specialties. A hospital with no sectors can disable a specialty.

Disabling removes only the `Hospital.especialidades` association and audits `ESPECIALIDAD_DESHABILITADA`. It preserves the global catalog, sectors, rooms and historical references; it neither deletes nor deactivates them in cascade. It stops offering the specialty to new admissions and does not cancel consultations or close existing medical sessions. Session/consultation state is not an additional deletion guard. The old sector-scoped `DELETE` route is removed; clients must use the hospital-scoped route.

### Create Room

```http
POST /api/admin/hospitales/{hospitalId}/configuracion/sectores/{sectorId}/salas
```

Rooms are created inside a sector, so `{sectorId}` is required in the path. The room is assigned to that sector and its specialty must match the sector's specialty (`409 { "error": "La especialidad de la sala debe coincidir con la especialidad del sector" }`). Room name uniqueness is now `hospitalId + sectorId + nombre` case-insensitive (`RepoSalas.existsByHospitalIdAndSectorIdAndNombreIgnoreCase`) -> `409 { "error": "Ya existe una sala con ese nombre en el sector" }`.

Body:

```json
{
  "nombre": "Consultorio 3",
  "especialidadId": 1
}
```

### Update Room

```http
PUT /api/admin/hospitales/{hospitalId}/configuracion/sectores/{sectorId}/salas/{salaId}
```

Uses the same body as room creation. The room must belong to the sector in the URL (`404 { "error": "La sala no pertenece al sector" }`) and its specialty must match the sector's specialty. Name uniqueness is `hospitalId + sectorId + nombre` excluding self (`RepoSalas.existsByHospitalIdAndNombreIgnoreCaseAndSectorIdAndIdNot`) -> `409`.

### Activate or Deactivate Room

```http
PATCH /api/admin/hospitales/{hospitalId}/configuracion/sectores/{sectorId}/salas/{salaId}/estado
```

The room must belong to the sector in the URL; activating re-validates that the room's specialty is still enabled for the hospital. Deactivation returns `409` if the room is reserved by an `ACTIVA` or `PAUSADA` medical session; the room stays active.

Body:

```json
{
  "activa": false
}
```

Room responses (`SalaHospitalResponse`) include the sector: `sectorId` and `sectorNombre` in addition to the specialty fields.

### Create Sector

```http
POST /api/admin/hospitales/{hospitalId}/configuracion/sectores
```

Hospital admin only (`ADMIN_HOSPITAL` via `StaffAccessService.exigirAdminHospital`). Creates a sector that groups rooms of a single specialty. The specialty must already be enabled for the hospital; otherwise `409`.

Body (`GuardarSectorRequest`):

```json
{
  "nombre": "Sector Rojo",
  "especialidadId": 1
}
```

Validation: `nombre` `@NotBlank @Size(max=100)` (trimmed before uniqueness check), `especialidadId` `@NotNull` -> `400` with field map on violation. Uniqueness is `hospitalId + nombre` case-insensitive (`RepoSectores.existsByHospitalIdAndNombreIgnoreCase`) -> `409 { "error": "Ya existe un sector con ese nombre en el hospital" }`. `404` if hospital or specialty not found. Audits `SECTOR_CREADO` via `AuditoriaHospital`.

Success `200` (`SectorHospitalResponse`):

```json
{
  "id": 10,
  "nombre": "Sector Rojo",
  "activa": true,
  "especialidadId": 1,
  "especialidadCodigo": "CLINICA_MEDICA",
  "especialidadNombre": "Clínica médica"
}
```

### Update Sector

```http
PUT /api/admin/hospitales/{hospitalId}/configuracion/sectores/{sectorId}
```

Hospital admin only. Updates `nombre`, `especialidad` and `activa`. Specialty must be enabled for the hospital. Name uniqueness is `hospitalId + nombre` case-insensitive excluding self (`RepoSectores.existsByHospitalIdAndNombreIgnoreCaseAndIdNot`) -> `409`. Trimmed before check.

Body (`ActualizarSectorRequest`):

```json
{
  "nombre": "Sector Rojo - Ala Norte",
  "especialidadId": 1,
  "activa": true
}
```

Validation: `nombre` `@NotBlank @Size(max=100)`, `especialidadId` `@NotNull`, `activa` `@NotNull` -> `400`. `404` if hospital/sector/specialty not found or sector does not belong to hospital (`findByIdAndHospitalId`). A consultation referencing the sector directly or one of its rooms with `estadoConsulta NOT IN (FINALIZADA,CANCELADA)` returns `409 { "error": "No se puede modificar o eliminar un sector con pacientes pendientes o en atención" }`. This includes queued patients without a room yet. Deactivating also rejects medical sessions in `ACTIVA` or `PAUSADA`, checking both sector and room references. Otherwise deactivating a sector deactivates its rooms in the same transaction. Audits `SECTOR_ACTUALIZADO`.

Success `200` (`SectorHospitalResponse` with updated `activa`).

### Delete Sector

```http
DELETE /api/admin/hospitales/{hospitalId}/configuracion/sectores/{sectorId}
```

Hospital admin only. Returns `204 No Content` on success.

Blocking rules (`409` if violated):

* Same pending-patient check as `PUT`, including consultations assigned directly
  to the sector before a room is selected.
* Medical sessions referencing the sector or its rooms in `ACTIVA` or `PAUSADA`
  return `409 { "error": "No se puede modificar o eliminar un sector con sesiones de atención activas o pausadas" }`.
* Any historical consultation/session referencing the sector or its rooms, or a
  patient still linked through `id_sector_asignado`, prevents physical deletion.
  The conflict explains that the sector should be deactivated to preserve history.
* A queue manager with consultations or queue-entry history also prevents deletion,
  including entries that no longer count as waiting patients.

For a never-used sector, an empty queue manager is removed first, followed by its
rooms and the sector, in one transaction. No consultation, patient, session or
queue-entry history is deleted. `404` if sector not in hospital. Audits each
`SALA_ELIMINADA` and `SECTOR_ELIMINADO`. Clients must accept the successful `204`
without parsing a JSON body, then refetch configuration and audit.

### List Medical Sessions Of A Sector

```http
GET /api/admin/hospitales/{hospitalId}/configuracion/sectores/{sectorId}/sesiones
```

Requires `ADMIN_HOSPITAL` access to `{hospitalId}`. Returns `200` with an array of
`SesionMedicaAdminResponse` for `ACTIVA` or `PAUSADA` sessions, newest first
(`fechaHoraInicio DESC`, then `id DESC`). A sector can be inactive. Legacy sessions
without a direct sector are included through their room's sector. No matching
sessions returns `[]`; this endpoint does not return finalized history.
After a successful close, refetching this list excludes the closed session even
though its record remains in the database. Only the close response returns that
session in `FINALIZADA`; the list endpoint still excludes it.

Each item contains `id`, `medicoId`, `medicoNombre`, `estado`, `sectorId`,
`sectorNombre`, `salaId`, `salaNombre`, `codigoEspecialidad`,
`nombreEspecialidad`, and `fechaHoraInicio` (local ISO date-time). Room fields can
be `null` if the session has no room. No patient details are included.
Unauthenticated access returns `401`; missing hospital-admin access returns `403`.
A sector outside the requested hospital or an unknown sector returns `404`.

### Close A Medical Session As Hospital Admin

```http
POST /api/admin/hospitales/{hospitalId}/configuracion/sectores/{sectorId}/sesiones/{sesionId}/cerrar
```

No body. Uses the same hospital-admin authorization and sector scope as listing.
The session must also belong to the hospital and sector in the URL (`404`
otherwise, including a session in another hospital). Returns `200` with
`SesionMedicaAdminResponse` in `FINALIZADA`, sets `fechaHoraFin`, and audits
`SESION_MEDICA_CERRADA_POR_ADMIN` with actor, hospital and `objetivo=sesion:{id}`.
Closing an already finalized session is idempotent: the existing end time is kept
and no duplicate audit is written.

Returns `409 { "error": "No se puede cerrar la sesión mientras tenga una consulta llamada o en atención" }`
if the doctor has an `EntradaCola` in `LLAMADO`/`EN_ATENCION` or the session has an
`AtencionMedica.EN_CURSO`. It never cancels that consultation. Administrative
closure and doctor state changes share a pessimistic session lock and validate
state after acquiring it. The closure releases the doctor/room reservation and
removes active capacity from estimation, preserving all history and queued
patients. It does not make a previously used sector physically deletable;
deactivate it once all pending patients and sessions have been resolved.

The web panel confirms closure per session, then refetches the sector sessions,
hospital configuration and audit. Mutation controls remain pending through that
refresh; a rejected close leaves the dialog open with the backend error.

## Hospital Metrics (Hospital Admin)

### Get Hospital Metrics

```http
GET /api/admin/hospitales/{hospitalId}/metricas?desde=2026-09-01&hasta=2026-09-30
```

`MetricasHospitalController` → `MetricasHospitalService`. Requires an active `ADMIN_HOSPITAL` membership for `{hospitalId}` (`StaffAccessService.exigirAdminHospital`, checked first) → otherwise `403`. Unauthenticated → `401`.

Query params (both required, ISO `yyyy-MM-dd`, inclusive): `desde`, `hasta`. They are converted to `[desde 00:00, hasta+1 00:00)`. `desde > hasta` → `400` (`IllegalArgumentException`); missing or malformed dates → `400`.

**Funnel semantics**: every metric is anchored on `EntradaCola.fechaHoraIngreso` of the hospital's queues inside the range. `pacientesAtendidos` are, among those entries, the ones whose `AtencionMedica` is `FINALIZADA` (even if it finished after `hasta`). Patients who entered near the end of the period and were not yet attended count as `pacientesNoAtendidos`. Wait time = `AtencionMedica.fechaHoraInicio - EntradaCola.fechaHoraIngreso`, averaged only over attended patients. "Pretriage realizado" counts chats classified `COMPLETADA`, plus legacy rows whose classification state is null and whose `resultadoTriageJson` is present. Partial, failed, pending, reception-only and unlinked consultations count in `pretriageNoRealizado`. `distribucionGravedadBot` only counts valid completed AI classifications; its explicit `SIN_CLASIFICACION_IA` bucket includes every queue arrival without one, so provisional `NORMAL` is not presented as an AI result. Medical and operational metrics continue to count all arrivals. To compare periods, call the endpoint twice with different ranges.

Canonical `200` response:

```json
{
  "hospitalId": 1,
  "desde": "2026-09-01",
  "hasta": "2026-09-02",
  "ingresaronACola": 60,
  "pacientesAtendidos": 42,
  "pacientesNoAtendidos": 18,
  "esperaPromedioMinutos": 37.5,
  "porcentajeAtendidos": 70.0,
  "distribucionGravedadBot": [
    { "nivel": "RIESGO_VITAL_INMEDIATO", "cantidad": 3,  "porcentaje": 5.0 },
    { "nivel": "MUY_URGENTE",            "cantidad": 6,  "porcentaje": 10.0 },
    { "nivel": "URGENTE",                "cantidad": 15, "porcentaje": 25.0 },
    { "nivel": "NORMAL",                 "cantidad": 15, "porcentaje": 25.0 },
    { "nivel": "NO_URGENTE",             "cantidad": 6,  "porcentaje": 10.0 },
    { "nivel": "SIN_CLASIFICACION_IA",    "cantidad": 15, "porcentaje": 25.0 }
  ],
  "distribucionGravedadMedico": [
    { "nivel": "RIESGO_VITAL_INMEDIATO", "cantidad": 2,  "porcentaje": 3.3 },
    { "nivel": "MUY_URGENTE",            "cantidad": 4,  "porcentaje": 6.7 },
    { "nivel": "URGENTE",                "cantidad": 15, "porcentaje": 25.0 },
    { "nivel": "NORMAL",                 "cantidad": 17, "porcentaje": 28.3 },
    { "nivel": "NO_URGENTE",             "cantidad": 12, "porcentaje": 20.0 },
    { "nivel": "SIN_REVISION",           "cantidad": 10, "porcentaje": 16.7 }
  ],
  "pretriageRealizado": 45,
  "pretriageNoRealizado": 15,
  "porcentajePretriageRealizado": 75.0,
  "serieDiaria": [
    { "fecha": "2026-09-01", "ingresados": 60, "atendidos": 42, "esperaPromedioMinutos": 37.5 },
    { "fecha": "2026-09-02", "ingresados": 0,  "atendidos": 0,  "esperaPromedioMinutos": null }
  ]
}
```

| Field | Type | Null? | Notes |
|---|---|---|---|
| `hospitalId` | number | no | Echo of the path. |
| `desde`, `hasta` | string `yyyy-MM-dd` | no | Echo of the request. |
| `ingresaronACola` | integer ≥ 0 | no | `EntradaCola` of the hospital with `fechaHoraIngreso` in range. |
| `pacientesAtendidos` | integer ≥ 0 | no | Of those, with `AtencionMedica.estado = FINALIZADA`. |
| `pacientesNoAtendidos` | integer ≥ 0 | no | `ingresaronACola - pacientesAtendidos`. |
| `esperaPromedioMinutos` | number (1 decimal) | yes | `null` when `pacientesAtendidos = 0`. |
| `porcentajeAtendidos` | number 0–100 (1 decimal) | yes | `null` when `ingresaronACola = 0`. |
| `distribucionGravedadBot` | array of `{nivel, cantidad, porcentaje}` | no | The 5 `NivelDeGravedad` values from valid completed AI results, plus `SIN_CLASIFICACION_IA` last for partial/unclassified arrivals. Shares use all queue arrivals as denominator. |
| `distribucionGravedadMedico` | array of `{nivel, cantidad, porcentaje}` | no | Same 5 levels from `nivelDeGravedadMedico` + `SIN_REVISION` last (no doctor review yet). |
| `…[].porcentaje` | number 0–100 (1 decimal) | yes | `null` when `ingresaronACola = 0`. |
| `pretriageRealizado` / `pretriageNoRealizado` | integer ≥ 0 | no | Completed AI classification (including legacy null state with non-null result) / its complement. |
| `porcentajePretriageRealizado` | number 0–100 (1 decimal) | yes | `null` when `ingresaronACola = 0`. |
| `serieDiaria` | array of `{fecha, ingresados, atendidos, esperaPromedioMinutos}` | no | One item per calendar day `desde..hasta` ascending, zero-filled; grouped by the day of `fechaHoraIngreso`; `esperaPromedioMinutos` is `null` on days without attended patients. |

Invariants (closed by construction): `pacientesNoAtendidos = ingresaronACola - pacientesAtendidos`; `pretriageRealizado + pretriageNoRealizado = ingresaronACola`; sum of `cantidad` of each distribution `= ingresaronACola`; `sum(serieDiaria.ingresados) = ingresaronACola`; `sum(serieDiaria.atendidos) = pacientesAtendidos`.

Suggested metric → chart mapping:

| Metric | Chart |
|---|---|
| `ingresaronACola`, `pacientesAtendidos`, `esperaPromedioMinutos`, `porcentajeAtendidos` | KPI tiles |
| `ingresaronACola` → `pacientesAtendidos` / `pacientesNoAtendidos` | Funnel or stacked bar |
| `distribucionGravedadBot` vs `distribucionGravedadMedico` | Grouped bars (one group per `nivel`) |
| `pretriageRealizado` / `pretriageNoRealizado` | Donut |
| `serieDiaria.ingresados` + `serieDiaria.atendidos` | Line/area over `fecha` |
| `serieDiaria.esperaPromedioMinutos` | Line over `fecha` (gaps on `null`) |
