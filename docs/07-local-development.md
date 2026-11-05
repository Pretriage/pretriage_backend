# Local Development

For the isolated AWS container profile and temporary hosting, see
[AWS runtime](27-aws-backend-runtime.md) and
[demo deployment](28-aws-demo-deployment.md). `compose.aws.yaml` is separate from
the developer database. `python scripts/test_aws_container.py --build` verifies
the image with a disposable PostgreSQL and no real provider credentials.

## Requirements

- Java 21
- Docker Desktop
- Run integration/full tests with `python scripts/test_backend_isolated.py`; it creates and deletes a disposable DB and disables Docker Compose. Never use the app DB for `create-drop` tests.
- PostgreSQL through `compose.yaml`
- Internet access and a Gemini API key (`GEMINI_API_KEY`)
- `.env` with Auth0 and test credentials

## Important Properties

```properties
pretriage.ia.gemini.model=${GEMINI_CHAT_MODEL:gemini-3.5-flash-lite}
pretriage.ia.gemini.api-key=${GEMINI_API_KEY:}
pretriage.ia.gemini.request-timeout=${GEMINI_REQUEST_TIMEOUT:45s}
pretriage.ia.gemini.max-output-tokens=${GEMINI_MAX_OUTPUT_TOKENS:8192}
pretriage.ia.gemini.max-attempts=${GEMINI_MAX_ATTEMPTS:2}
pretriage.ia.gemini.attempt-timeout=${GEMINI_ATTEMPT_TIMEOUT:20s}
pretriage.ia.gemini.retry-initial-backoff=${GEMINI_RETRY_INITIAL_BACKOFF:500ms}
pretriage.estimacion.minutos-promedio-atencion=10
pretriage.cambio-contrasenia.expiracion-minutos=${PRETRIAGE_CAMBIO_CONTRASENIA_EXPIRACION:15}
pretriage.cambio-contrasenia.max-solicitudes-por-hora=${PRETRIAGE_CAMBIO_MAX_POR_HORA:3}
pretriage.cambio-contrasenia.ventana-horas=${PRETRIAGE_CAMBIO_VENTANA_HORAS:1}
pretriage.cambio-contrasenia.email.mode=${PRETRIAGE_CAMBIO_EMAIL_MODE:${PRETRIAGE_INVITATIONS_EMAIL_MODE:local}}
pretriage.cambio-contrasenia.email.from=${PRETRIAGE_CAMBIO_EMAIL_FROM:${PRETRIAGE_INVITATIONS_EMAIL_FROM:no-reply@pretriage.local}}
spring.config.import=optional:file:.env[.properties]
spring.jpa.hibernate.ddl-auto=${JPA_DDL_AUTO:create-drop}
pretriage.voz.gemini.api-key=${GEMINI_API_KEY:}
pretriage.voz.gemini.modelo=${GEMINI_LIVE_MODEL:models/gemini-3.8-live}
pretriage.voz.gemini.voz=${GEMINI_LIVE_VOICE:Kore}
pretriage.voz.gemini.idioma=${GEMINI_LIVE_LANGUAGE:es-US}
pretriage.voz.origenes-permitidos=${PRETRIAGE_VOZ_ORIGENES:*}
```

The estimated-attention SSE endpoint sends `tiempo-estimado` and `heartbeat`
events. Closing the client connection is an expected end of that stream: its
emitter is removed from the notifier and the backend does not try to write a
JSON error response to the closed connection.

The voice chat (`/api/chat/{id}/voz`) is optional: without `GEMINI_API_KEY` its
handshake returns `503`; text classification returns a provider error and reception retains its local fallback. Set the key in `.env`
to enable it, and restrict `PRETRIAGE_VOZ_ORIGENES` (comma-separated origin
patterns) outside local development.

`GEMINI_MAX_OUTPUT_TOKENS` bounds Gemini REST output (default 8192, application
range 1-65536; model limits also apply). It affects text chat, final voice
classification and reception classification, not the Gemini Live audio session.
Restart the backend after changing it. Non-`STOP` responses remain provider errors;
reception retains its existing fallback and chat does not generate a local result.
Gemini REST allows two HTTP attempts per invocation within a 45-second total
operation budget; a correction request shares the remaining time from that budget.
The attempt timeout and retry backoff are configurable as shown above. See
[Gemini REST retries](19-gemini-retries.md) for retryable statuses and limits.
The chat interview quantifies duration and severity/impact only for reported
symptoms, with at most four quantification questions, one open invitation and eight
patient answers. It has no minimum answer count or symptom checklist. Unknown or
declined measurements are not asked again; see the
[interview policy](17-simple-chat-questions.md) for closure rules.

Set `JPA_DDL_AUTO=update` in the ignored local `.env` before starting a development
instance whose data must persist. The current fallback is `create-drop`: it
drops the schema at shutdown and recreates it at startup.

Spring DevTools watches `target/classes`. Maven compilation (including `test`
and `package`) can restart a running backend and erase its data when that
instance uses `create-drop`. Before compiling, verify the running instance uses
a persistent schema mode, or stop it after backing up its data. Editing `.env`
alone does not protect the shutdown of a process that already loaded
`create-drop`. Tests that need a datasource must use an isolated database.

## Optional hospital metrics demo data

`MetricasHospitalTestInyection` is disabled by default. It is imported from the
development branch for manually exercising the hospital metrics dashboard, and
must not populate the normal application database implicitly.

To use it in an explicitly disposable demo environment, configure both properties:

```properties
pretriage.seed.metricas-demo.enabled=true
pretriage.seed.metricas-demo.hospital-place-id=<existing-demo-hospital-place-id>
```

The hospital and `CLINICA_MEDICA` specialty must already exist. The runner inserts
310 synthetic queue entries across 1–23 October 2026, with patients, consultations,
rooms, a doctor and supporting attendance data. It creates no authenticated users
or hospital-admin memberships. An existing `MTR-%` consultation skips the seed;
this is not a general migration or repair tool. Fixed demo document numbers must
not collide with other data. Leave activation absent or `false` for normal startup
and isolated regression tests. See [the development integration](25-development-integration.md).

## Invitation email through Brevo

The SMTP adapter is already included in the backend. Configure the ignored `.env`
in the backend working directory, then restart the process to load it:

```properties
PRETRIAGE_INVITATIONS_EMAIL_MODE=smtp
PRETRIAGE_INVITATIONS_EMAIL_FROM=no-reply@pretriage.com.ar
PRETRIAGE_FRONTEND_BASE_URL=http://localhost:3000
SMTP_HOST=smtp-relay.brevo.com
SMTP_PORT=587
SMTP_USERNAME=<SMTP login from Brevo>
SMTP_PASSWORD=<SMTP key from Brevo, not an API key>
SMTP_AUTH=true
SMTP_STARTTLS=true
spring.mail.properties.mail.smtp.starttls.required=true
spring.mail.properties.mail.smtp.connectiontimeout=10000
spring.mail.properties.mail.smtp.timeout=10000
spring.mail.properties.mail.smtp.writetimeout=10000
PRETRIAGE_CAMBIO_EMAIL_MODE=local
```

Use the frontend's public HTTPS URL when recipients open links outside the local
development machine. Sender/domain verification is managed in Brevo. Authentication
alone does not verify sender authorization or inbox delivery. SMTP mode does not
return an invitation secret to the administrative frontend; failed sends remain
pending and can be resent from the invitation list.

Password recovery otherwise inherits the invitation email mode. The explicit
`PRETRIAGE_CAMBIO_EMAIL_MODE=local` keeps its existing behavior until separately
enabled. Never commit the SMTP key or enable mail protocol debugging with real
credentials. A connection check may authenticate with STARTTLS and quit without
issuing `MAIL`, `RCPT` or `DATA`; sending a real test invitation is a separate step.

Provider reference: [Brevo transactional SMTP](https://help.brevo.com/hc/en-us/articles/7924908994450-Send-transactional-emails-using-Brevo-SMTP).

## Start without an IDE (Windows)

Double-click `iniciar-backend.cmd` in the repository root. It runs
`scripts/start_backend.ps1` using Windows PowerShell 5.1 or newer and a
process-only execution policy; it does not change your machine's policy.
Keep Docker Desktop running for the default PostgreSQL setup and configure
the root `.env` as usual. A JDK 21 or newer must be available through
`JAVA_HOME`, or through `javac.exe` on `PATH` with a standard JDK layout.
Maven is downloaded by the repository's wrapper when necessary; a separate
Maven installation is not required.

From a PowerShell terminal, you can also run:

```powershell
.\iniciar-backend.cmd
.\iniciar-backend.cmd -Port 8081 -MaxHeapMb 1024
.\iniciar-backend.cmd -NoDockerCompose
.\iniciar-backend.cmd -CheckOnly
```

`-CheckOnly` validates the launcher and JDK without starting Maven, Docker,
or the backend. `-NoDockerCompose` expects PostgreSQL to be running already
and uses the application's configured datasource instead of Compose connection
details. You can invoke `.\scripts\start_backend.ps1` directly if your
PowerShell execution policy permits it. Both launchers work from other working
directories because the script switches to the repository root.

The launcher runs `spring-boot:run`, skips test compilation/execution, disables
DevTools restart/LiveReload, and defaults to a backend heap of 128-768 MB.
Maven has a separate JVM with a default heap of 64-512 MB; an existing
`MAVEN_OPTS` is respected. Heap limits do not cap total process memory.
Environment changes are restored when the script returns. No IDE, debugger,
seed, or database reset is started.

The application arguments explicitly select `ddl-auto=update`, even if `.env`
or environment variables contain `create-drop`. They also disable SQL logging
and listen on `0.0.0.0` at port 8080 by default. Use `http://localhost:8080` on
the PC, `http://10.0.2.2:8080` in the Android emulator, or the PC's LAN IP on
a physical phone on the same network; Windows Firewall must allow access.
Changing `-Port` requires a matching frontend base URL.

Stop the backend with **Ctrl+C**. Compose uses `start-only`, so stopping the
backend does not stop PostgreSQL. Startup logs stay in the terminal; the
double-click launcher pauses on an error so its output can be read. The
script does not print or parse `.env` secrets. Spring loads the file normally.

References: [Spring Boot Maven run parameters](https://docs.spring.io/spring-boot/4.0/maven-plugin/run.html)
and [application configuration precedence](https://docs.spring.io/spring-boot/reference/features/external-config.html).

## Compile

```powershell
.\mvnw.cmd test -DskipTests
```

## Focused Tests

```powershell
.\mvnw.cmd "-Dtest=AtencionHospitalServiceTest,EstimacionAtencionServiceTest" test
.\mvnw.cmd "-Dtest=HospitalConfigurationServiceTest,HospitalConfigurationControllerTest" test
```

## Full Tests

```powershell
python scripts/test_backend_isolated.py
```

Full tests need Docker Desktop access. In restricted environments, this can fail with Docker pipe permission errors.

Do not run Spring integration tests with `create-drop` against the application's database.
Docker Compose connection details override `spring.datasource.url` unless Compose is
disabled. The safe runner disables it and owns the complete temporary DB lifecycle.
See [the verification incident](15-test-isolation-incident.md).

## Real Chat E2E

Create a disposable database and start the backend against it before running
the script; see [isolated setup](08-e2e-chat-debugging.md#isolated-setup).
The backend and script must use the same database. The seed removes the test
patient's previous chats and cancels their active consultations.

```powershell
python scripts\e2e_chat.py --backend-url http://localhost:18080 --db-name pretriage_chat_e2e --messages-file scripts\chat_case_example.txt
```

The script reads credentials from `.env`:

- `AUTH0_TEST_USERNAME`
- `AUTH0_TEST_PASSWORD`

It seeds patient/hospital/specialty/sector/room data through Docker, performs
real login, calls backend endpoints and Gemini, and validates the persisted
classification, its origin, priority and both queue states. It rejects early
closure and Gemini provenance by default.

## Voice Chat Regression Tests

Run the voice session, shared close validator, provider failure and authentication regressions:

```powershell
.\mvnw.cmd "-Dtest=SesionVozChatTest,SesionVozIntegracionTest,ValidadorCierreEntrevistaVozTest,ChatVozHandshakeInterceptorTest,ChatServiceTest,TriageIaClientTest" test
```

These tests simulate Gemini Live and Gemini REST. They do not verify microphone capture, audio playback
or provider availability. See [the voice integration flow](14-chat-voz-gemini-live.md).

Full application context tests use `SpringBootTest.WebEnvironment.RANDOM_PORT`:
the voice configuration requires a real servlet WebSocket `ServerContainer`, which
the default mock servlet context does not provide. `BackendApplicationTests`
also checks the configured binary/text buffer limits and idle timeout.

## Chat History Schema Migration

`Chat.paciente` now maps many chats to one patient, preserving previous interview
history after a new consultation starts. On an existing PostgreSQL database,
remove the unique constraint left by the old `OneToOne` mapping:

```powershell
Get-Content -Raw scripts/migrations/20261011_chat_patient_history.sql | docker exec -i postgre psql -X -U myuser -d pretriage_db -v ON_ERROR_STOP=1 -f -
```

Apply this migration together with the new backend mapping. Hibernate schema
`update` does not automatically remove existing unique constraints. The script
only removes single-column uniqueness constraints on `chat.paciente_id`, retains
the foreign key and all chats/messages, and creates a non-unique lookup index.
It is transactional, uses a five-second lock timeout, and can be repeated.
Fresh schemas generated from the new mapping do not have that unique constraint.

The real PostgreSQL regression `ChatHistoryPersistenceTest` persists a closed
chat and a new chat for the same patient and verifies that both histories remain.
Use an isolated database when running it; see the datasource guidance above.

Verified on October 11, 2026: `ChatHistoryPersistenceTest`, `ChatServiceTest`
and `EsperaPacienteServiceTest` passed (46 tests) with an isolated PostgreSQL
database. The migration also removed a recreated legacy constraint in that
database and succeeded when repeated. It was then applied to the local
`pretriage_db` with authorization; its existing chat and all nine messages
remained intact and the patient foreign key was preserved.

## Local Health Insurance Catalog

The [integral demo seed](31-demo-data-seeding.md) already includes this same
16-name catalog. Use the standalone helper below only when you want to populate
the catalog without creating the rest of the demo data.

After the backend has created its schema, populate the catalog used by the
mobile credential form:

```powershell
python -B scripts/seed_dev_obras_sociales.py
```

This adds 16 initial names (including OSDE, Swiss Medical, Galeno, IOMA, PAMI
and DOSUBA) to `obra_social` with `virgente=true` (the existing column spelling).
Defaults are Docker container `postgre`, user `myuser` and database `pretriage_db`;
override them with `--db-container`, `--db-user` and `--db-name`.
Use `--dry-run` to inspect SQL without accessing the database.

The transaction inserts only missing names, compares names ignoring case and
surrounding whitespace, and preserves existing IDs and inactive entries. It does
not delete or change patients, credentials, chats, hospitals, or consultations.
A short catalog table lock prevents concurrent insertions from creating duplicates.
It can be run again after a deliberate schema reset; it does not run automatically
at startup or change the configured schema lifecycle.

`GET /api/obrasocial` returns active names immediately; no backend restart or APK
rebuild is required. Reopen the mobile credentials screen or select **Reintentar**
to reload. Mobile treats an empty catalog as an error. The plan is free text, so
no plan rows are required. These are local catalog entries, not integrations with
insurers or confirmation of a patient's coverage; the current backend uses its
demo credential validator. See the health insurance API in
[06-api-reference.md](06-api-reference.md#health-insurance-patient).

Verified locally on October 21, 2026: the catalog was empty before loading and
contained 16 distinct active names afterward. A second run inserted zero rows;
user, patient, chat, hospital and credential counts stayed unchanged.

## Local Hospital Development Data

After the backend creates its schema, populate repeatable synthetic hospital data:

```powershell
python -B scripts/seed_dev_hospital.py --with-active-doctors
```

The script defaults to Docker container `postgre`, user `myuser`, database
`pretriage_db`, and hospital name `Hospital de Prueba - PreTriage`. Override them
with `--db-container`, `--db-user`, `--db-name`, and `--hospital-name`.
It reuses a hospital with that exact name or the supplied Place ID and creates missing specialty links,
one development sector and room per specialty, and their queue managers.
With `--with-active-doctors` it also creates five synthetic local doctor
identities, specialty assignments, and active sessions. These identities are
not Auth0 accounts and cannot sign in. This option is for local development only;
the sessions represent simulated capacity and remain active until closed.
Omit the option to create only hospital infrastructure.

The synthetic sessions reserve the seed consultorios just like any other doctor:
an `ACTIVA` or `PAUSADA` session prevents a second doctor from starting in that
room. For a real staff demo, omit `--with-active-doctors`, create another active
room under the same active sector, or close only the synthetic session after
checking that it has no called patient or attention in progress. Deactivating a
room does not close its session; configuration now rejects that operation while
the reservation exists.

To release those reservations without editing the database, sign in as the
hospital administrator, open the hospital's sector configuration, find the
**Sesiones médicas** list, and confirm **Finalizar sesión** for the intended
doctor/room. This also handles paused sessions and sessions in inactive sectors.
This list contains only `ACTIVA`/`PAUSADA`. After closure the row disappears from
the refreshed list while the finalized session remains stored in the database;
the panel does not expose a finalized-session history list through this endpoint.
The backend checks for called/in-attention patients, preserves history and writes
an audit event. No sessions are automatically closed at startup. Restart the
backend after installing the code change and reload the web panel to use the new
routes. A sector with historical sessions must subsequently be deactivated;
finalizing sessions does not permit physical deletion of that history.

The doctor web flow must load active sectors with
`GET /api/hospitales/{hospitalId}/sectores?codigoEspecialidad=...`, then active
rooms with `GET /api/hospitales/{hospitalId}/sectores/{sectorId}/salas?...`.
The old hospital-only room path is not supported. An empty room list can also
mean all configured rooms are inactive; inspect their state in administration.
Do not remove patients, queue history or sessions to make a sector deletable:
never-used sectors can be deleted, while sectors with references must be
deactivated after resolving pending work.

Configuration regressions can be checked without the app database:

```powershell
python -B scripts/test_backend_isolated.py --test HospitalConfigurationServiceTest
```

On October 21, 2026, the local Clinical Medicine consultorio had been deactivated
while its synthetic seed session remained active. The repair closed only that
synthetic session (with no ongoing attention) and reactivated its room; no
patient consultation or history was deleted.

The seed is transactional, does not delete patient/chats/consultations, and
can be repeated without duplicating its data. It does not reactivate paused
doctors or inactive rooms/sectors, or displace another doctor's occupied room.
Existing paused sessions reserve their room and prevent a second seeded session.
Use `--dry-run` to inspect SQL without accessing the database.

`Hospital` has no `activo` field. Operational availability requires specialty
links, an active sector/room and an active doctor session. Patient nearby search
starts from registered hospitals matching the selected specialty and filters by
geodesic radius 5 km. Google Place Details enriches their registered Place IDs;
global Nearby Search does not choose candidates and its 20-result limit no longer
affects this endpoint. A registered Place ID is required by the selection contract.
Provide valid stored coordinates or a Place ID resolvable by Google. Set a real ID:

```powershell
python -B scripts/seed_dev_hospital.py --with-active-doctors --place-id "REAL_GOOGLE_PLACE_ID"
```

For the local Hospital de Clínicas José de San Martín (UBA) demo, Google Places
verified `ChIJwwfedJTKvJURtfBmDu6LFm0` at Av. Córdoba 2351, CABA on October 11,
2026. The [UBA hospital directory](https://www.uba.ar/redhospitales) also confirms
the name and street address. Restore this local setup after a deliberate schema
reset with:

```powershell
python -B scripts/seed_dev_hospital.py --hospital-name "Hospital de Clínicas José de San Martín" --place-id ChIJwwfedJTKvJURtfBmDu6LFm0
python -B scripts/configure_dev_clinicas.py
```

Add `--with-active-doctors` to the first command only when synthetic doctor
capacity is intended; those sessions reserve the seed rooms. The second command
updates only the existing hospital's name, structured address and coordinates.
It resolves exactly one hospital by the registered Place ID and preserves its ID,
memberships, consultations, sectors, rooms and sessions. It creates missing
address/coordinate records once, refuses shared location records, and is safe to
repeat. Use its `--dry-run` option to inspect SQL without accessing the database;
container/user/database overrides match the generic hospital seed.

The earlier seed attached the real Place ID but kept `Hospital de Prueba - PreTriage`
and left `id_direccion` null. That produced the generic name in both clients and
“Dirección no disponible” on mobile. Re-running the generic seed with the same
Place ID now reuses the existing hospital even after the display name is corrected;
it does not create another hospital or revert its name. If name and Place ID
identify different existing hospitals, the seed fails rather than merging them.

The location is latitude `-34.5984007`, longitude `-58.3998358`.
For mobile testing, set the emulator location near CABA;
The app excludes this hospital when the selected patient coordinates are more than 5 km away.

The former implementation asked Google for the nearest 20 hospitals first,
then filtered by registration. On October 12, 2026 this excluded the registered
Hospital de Clínicas from `-34.602612,-58.427962` (Corrientes 4450 area), despite
being within the radius. The endpoint now queries registered hospitals first.
A real request against the updated backend in an isolated database returned
HTTP 200 with Clínicas available from those same coordinates. The full Java
suite passed 377 tests, including radius, more than 20 registered candidates,
ordering, specialty and doctor availability. Google HTTP test stubs verify that
the registered flow never calls `places:searchNearby`.

The GPS and `fused` providers can retain different last coordinates in an
emulator, while mobile reads Google Play Services' cached last location. Verify
both providers after changing coordinates and recreate the location-selection
screen; restart only the app if its screen still retains old coordinates.
During the October 12 diagnosis, the saved mobile session returned HTTP 200
with the registered hospital at the demo coordinates. The separate Auth0 test
account returned HTTP 403 because it had no matching local patient. Do not use
that account's authorization failure to diagnose another mobile user's session.

The script refuses to replace a different existing Place ID or attach an ID
already assigned to another hospital. It does not call Google, infer an ID from
the hospital name, or create memberships/admin accounts.

To retain these data between backend runs, use `JPA_DDL_AUTO=update` in the backend
root `.env` and start the app with that directory as its working directory.
An environment variable or IntelliJ VM option overriding the setting can still
select `create`/`create-drop`; check those if the schema keeps being recreated.
The current `.env` is read at startup. If a running instance loaded `create-drop`,
changing the file alone cannot prevent that instance from dropping its schema
on shutdown; back up needed data before stopping it. Re-run the seed after a
deliberate schema reset.

Verified on October 11, 2026: the local database initially had one hospital but
no sectors, rooms or doctor sessions. Running the seed created one active sector,
room and doctor session for each of the five specialties; running it again
inserted no duplicate seed rows.

## Common Local Issues

### Docker Permission Denied

If tests fail with access to `npipe:////./pipe/dockerDesktopLinuxEngine`, run from a shell/session that has Docker Desktop access.

### WebSocket container missing in an integration test

Full-context service integration tests use
`@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)`.
The default mock servlet environment has no Jakarta WebSocket `ServerContainer`,
so it cannot initialize the voice configuration. Keep database access isolated
with `python scripts/test_backend_isolated.py`, including for password-change tests.

### Gemini unavailable

Set `GEMINI_API_KEY` in the ignored `.env` and optionally `GEMINI_CHAT_MODEL`.
The REST default is `gemini-3.5-flash-lite`; the audio default is `models/gemini-3.8-live`.
Restart the backend after configuration changes. Keys are never needed in mobile.
Missing keys, exhausted transient retries, quota exhaustion without a usable
`Retry-After`, safety blocks or invalid output fail text/final voice classification
without producing local output. Gemini REST retries eligible timeouts and selected
HTTP statuses up to two attempts per request; a message correction shares the same
45-second operation budget, including the final semantic verification. A failed
question allows a manual retry. Failure during final classification keeps the chat
closed in `FALLIDA`, with its transcript preserved; retry processing through the
classification retry endpoint without adding another patient message. See
[the retry policy](19-gemini-retries.md) for status handling and configuration.
Only reception forms retain `FALLBACK_LOCAL` behavior. A Live connection itself requires
an available Live model and key. Availability and quotas depend on the account;
the E2E precheck reads model metadata and the full flow then verifies generation.

### Chat scope and semantic checks

See [chat hardening](20-chat-hardening.md) for the synthetic case matrix, isolated
regression command and opt-in Gemini evaluation. The latter uses synthetic text
and does not start Spring or connect to the application database. Deploying this
change requires restarting the backend and adding the nullable boolean column
`mensaje.excluido_del_triage` (`ddl-auto=update` does this in local development).
Existing rows remain admitted when the value is null. No mobile build or API key
change is required.

### Backend Not Running

The E2E script expects:

```text
http://localhost:8080
```

Override with:

```powershell
python scripts\e2e_chat.py --db-name pretriage_chat_e2e --backend-url http://localhost:8081
```

## Reception HTTP E2E

With the backend running, provide a valid Auth0 access token for a receptionist:

```powershell
$env:E2E_RECEPCION_TOKEN="<token>"
python scripts/e2e_recepcion.py --mode finalize
python scripts/e2e_recepcion.py --mode cancel
```

Use `--hospital-id` when the receptionist is assigned to several hospitals. The script reuses an
active reception session or starts one, creates a unique patient/admission, verifies open-admission
recovery, performs the requested terminal action, and reads the final detail back.
