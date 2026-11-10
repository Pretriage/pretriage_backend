# E2E Chat Debugging

> Política vigente desde el 5/11/2026: [recuperación automática y cierre parcial](34-automatic-chat-recovery.md). Las referencias siguientes a bloqueos técnicos terminales, `FALLIDA` y reintentos manuales describen comportamientos anteriores.

`scripts/e2e_chat.py` exercises real login, backend endpoints, Gemini REST and PostgreSQL.
Use synthetic messages and a disposable database: the seed cancels the test
patient's existing consultations and deletes their previous chats and messages.

## Isolated Setup

Create a test database in the local PostgreSQL container:

```powershell
docker exec postgre psql -U myuser -d postgres -c "CREATE DATABASE pretriage_chat_e2e"
```

Start a separate backend against that database. Pass these program arguments to
the application, retaining the local `.env` for credentials:

```text
--server.port=18080
--spring.datasource.url=jdbc:postgresql://localhost:5432/pretriage_chat_e2e
--spring.jpa.hibernate.ddl-auto=update
--spring.docker.compose.enabled=false
```

The backend must point to the same database given to the script. Do not run
`create-drop` against data that must survive startup, shutdown or DevTools
restarts. The script requires `--db-name` (or `DB_NAME`); the usual `pretriage_db`
also requires `--allow-destructive-seed`.

## Run

```powershell
python scripts/e2e_chat.py --backend-url http://localhost:18080 --db-name pretriage_chat_e2e --messages-file scripts/chat_case_example.txt --expected-intensity 5 --expected-onset ayer --debug-log target/chat-e2e.json
```

Credentials are `AUTH0_TEST_USERNAME` and `AUTH0_TEST_PASSWORD` in `.env` or the
environment. The seed creates the patient, hospital, specialty, active sector
and active room required for hospital selection.

Message files are UTF-8 with or without BOM, one message per line. Empty lines
and lines beginning with `#` are ignored. Repeated `--message` arguments override
the file. The example reports measured temperature up front, answers the pain scale
and then declines to add more:

```text
Tengo fiebre de 38 grados desde ayer. También me duele la garganta desde ayer. No tengo dificultad para respirar ni dolor de pecho.
5 de 10.
No tengo nada más que agregar.
```

## Success Criteria

- The Gemini API key can access a model supporting `generateContent`. This metadata precheck does not prove generation quota availability.
- Login, hospital selection and chat endpoints succeed.
- The backend follows the short evidence-led flow: no minimum answer count or
  fixed topic checklist, uses one open invitation for other symptoms or useful
  context, keeps relevant context volunteered earlier, and closes when the patient
  has nothing more to add and the relevant data are resolved or explicitly unknown.
  Eight replies no longer force closure. Independent limits bound attempts per
  datum, replies without progress and total turns; see
  [closure by resolved data](21-cierre-por-datos-resueltos.md).
- The stored result has meaningful motive/symptoms and the expected fields.
- Final `origenClasificacion` is `GEMINI` by default. The per-turn response exposes
  `origenRespuesta` (`GEMINI`).
- Chat and consultation belong to the authenticated patient; consultation and
  queue entry both have `EN_COLA`, and the assigned sector is consistent.
- Result priority, consultation severity and queue priority agree.
- Expected symptoms match the case (default: `fiebre`). If supplied,
  `--expected-priority`, `--expected-intensity` and `--expected-onset` must match
  too. Without a specified expected priority, the test verifies range and
  consistency with severity and queue priority; it does not prescribe a clinical
  classification for the model. These are test expectations, not a clinical benchmark.

The script reads structured SQL JSON. The patient is already queued before the
optional chat, so queue membership alone never proves AI classification.

## Custom Cases

```text
--expected-priority 5
--expected-intensity 8
--expected-onset "hace una hora"
--expected-symptom "dolor de pecho"
--min-turns 1
--allow-early-finalization
--skip-gemini-check
```

Use `--allow-early-finalization` for cases intentionally expected to close
immediately, such as an alarm scenario. `--min-turns` sets a test-specific minimum;
it does not change the backend interview policy.
`--expected-symptom` can be repeated. Chat has no local fallback; the E2E requires
Gemini provenance and fails if generation fails or the response is rejected.
`--skip-gemini-check` only skips the availability precheck.

## Alarm Case with Gemini REST

`scripts/chat_case_alarm.txt` contains a single synthetic message reporting
chest pain and difficulty breathing. Run it against the isolated backend:

```powershell
python scripts/e2e_chat.py --backend-url http://localhost:18080 --db-name pretriage_chat_e2e --messages-file scripts/chat_case_alarm.txt --expected-symptom "dolor" --expected-priority 5 --expect-immediate-attention --debug-log target/chat-alarm-e2e.json
```

`--expect-immediate-attention` requires immediate attention, nonempty alarm signs,
urgent priority and `GEMINI` origin in both the final HTTP response and stored
classification. It also checks that the final patient-facing message includes
the stored safety recommendation. It does not accept a local fallback. Repeatable `--expected-alarm TERM` can additionally check
specific terms in the stored alarm signs. This one-message fixture must close
in the first turn without any extra early-finalization allowance.

## Text and Voice Consistency

Run `python -B scripts/test_backend_isolated.py` against its disposable PostgreSQL
database. The backend regressions cover mixed clinical and off-topic turns, exact
question objectives, both final output branches and alarm priority. Voice tests
exercise silence, tool closure and late transcriptions. Hold REST pending and
check for one `aviso_urgente` before classification. For an alarm known before
closure, the notice must precede `entrevista_finalizada`. After releasing REST,
confirm one classification and one queue update.

For provider verification, run the synthetic `ChatHardeningGeminiSmokeTest` and
`SesionVozContinuidadGeminiSmokeTest` with `GEMINI_CHAT_MODEL=gemini-3.8-flash` and
`-Dpretriage.gemini.hardening.live=true`. Then run the normal and alarm HTTP cases
above in sequence with the dedicated test account and disposable database. These
tests exercise Gemini REST. The synthetic voice events simulate the Live transport.

For Android, build and run `:composeApp:testDebugUnitTest` and `:composeApp:assembleDebug`
with `-PBACKEND_BASE_URL=http://10.0.2.2:18080` for a local emulator and the backend
started as described above. Compare the same synthetic report by text, by voice
and after switching from text to voice. Capture the urgent notice while the result
is pending and check that it remains visible through recovery of the same chat.
Check a separate chat to confirm isolation. Test real Live audio separately on an
Android test device with a normal case, an interruption and an alarm. Use only
synthetic data and compare the facts, final state and queue entry across modalities.

## Diagnostics and Regression Tests

Mobile distinguishes backend HTTP failures from transport failures when sending a
text message. A 5xx reports that the assistant could not respond, 401/403 indicates
access/session trouble, 404 reports a missing chat, and 429 requests a pause. It
keeps the draft and manual retry when delivery cannot be confirmed; it does not
automatically repeat a POST. After an ambiguous failure (network, 5xx or 409), the
updated mobile client checks GET up to four times with short request timeouts and
bounded backoff. It matches new messages after the ordered pre-send history,
using author and trimmed content rather than any historical equal text. A confirmed
new patient/BOT pair or a closed chat clears the resend warning. A saved patient
turn without a reply exposes a read-only update action instead of another POST.
Closed interviews recover authoritative classification state and offer processing
retry on `FALLIDA` without another patient message. Other 4xx errors retain the
original access/validation error without this polling. An unfinished failed turn is removed by
`ChatService`, so its absence from message history cannot identify the failure;
correlate the original backend log at the time of the attempt. Gemini's internal
REST retries do not replay a mobile patient's POST.

The generic `ProveedorIaException` HTTP warning is not the underlying diagnosis.
For incomplete Gemini REST responses, the log now includes allowlisted
`finishReason`, `outputTokens`, `thoughtTokens` and `maxOutputTokens`. Token counts
of `-1` mean unavailable. According to the [Gemini FinishReason reference](https://ai.google.dev/api/generate-content#FinishReason),
`MAX_TOKENS` indicates the request's output limit was reached; `SAFETY` is a
different rejection and must not be bypassed. Provider messages and clinical
response content are never included in these diagnostics. The old generic
`Gemini no completo la respuesta` alone cannot distinguish these conditions.

On 2026-10-13 chat 17 produced that old generic error. A synthetic equivalent
completed with `STOP` at both 2048 and 8192 output limits, so its historical
finish reason could not be established. The default limit was increased to 8192
as headroom, with `GEMINI_MAX_OUTPUT_TOKENS` configuration and safer diagnostics.
97 focused tests passed with
`./mvnw.cmd "-Dtest=GeminiChatPropertiesTest,GeminiJsonClientTest,TriageIaClientTest,ChatServiceTest,SesionVozChatTest,SesionVozIntegracionTest,AdmisionRecepcionServiceTest" test`.
No saved classification was manufactured or historical chat rewritten.

Read the preceding diagnostics: `Gemini rejected reason=...` identifies safe
technical transport/configuration errors (including HTTP status). Eligible
transient REST errors can be attempted twice per Gemini request, counting the
original request; 429 needs a valid `Retry-After`, and TLS/certificate, safety,
authentication, JSON and semantic failures are not HTTP retries. Each request
uses the remaining part of the 45-second operation budget. A classification
correction is a separate Gemini request within the same budget. See
[Gemini REST retries](19-gemini-retries.md) for details.
`Triage IA rejected category=... field=... reason=...` identifies schema/content
validation errors. `Chat IA rejected chatId=... reason=...` identifies service
rejection: `invalid_response`, `invalid_classification`,
`premature_or_unsafe_closure`, `required_closure_missing`, `repeated_question` or
`invalid_voice_classification` or `correction_deadline_exceeded`.
An intermediate text response uses a JSON Schema containing only `mensaje`;
Gemini cannot set closure or priority there. The backend decides whether to keep
interviewing or start final classification. The prompt asks for one datum per turn,
while `LenguajeClinicoChat.REGLAS_PREGUNTA_TEXTO` keeps closure instructions out of
the question-writing task. Incompatible intermediate responses or repeated questions can receive at most
one remote correction per patient turn. Punctuation is not part of format validation:
zero, two or Unicode question marks do not cause an error or format correction, and
Gemini's returned text is preserved. No local question or clinical replacement is
generated. If a semantic correction fails, the text turn rolls back. These messages do not contain credentials,
patient text or provider response bodies. Before interview closure, a failed text
turn is rolled back and its content is not recoverable from history. Once final
classification is requested, the last answer is preserved and `finalizado=true`
with `estadoClasificacion=FALLIDA` is committed on provider failure. A final result
with semantic inconsistencies can request one bounded correction. The Gemini REST
client may retry eligible transient transport/status failures up to two attempts per
request; quota 429 requires a valid `Retry-After`, and safety, authentication,
invalid JSON and semantic failures are not HTTP retries. Processing retry uses
`POST /api/chat/{id}/clasificacion`, not another patient message.
If the final-result verifier supplies a safe rejection category, one correction request
can use that category as feedback; the original rejection text and any provider citations
are never copied into the correction prompt or logs. No queue entry is created when that
correction fails.

The exact local validation failure `field=sintomas reason=missing_or_uninformative`
also supplies the allowlisted correction code `SINTOMAS_AUSENTES`. It asks Gemini
to preserve reported descriptive health facts, including emotional experiences,
without inventing physical symptoms. `Chat IA correction accepted` means that
the replacement passed validation; it does not establish HTTP delivery or commit
success. Inspect the persisted chat status and client error before concluding
that a recoverable warning forced a manual retry.

`field=estadoEntrada reason=non_relevant_must_not_classify` means a proposed
response marked the input as ambiguous or out of scope while also setting
`finalizado=true`, supplying a result, or supplying evidence. The parser rejects
that contradiction; the log alone does not identify which incompatible field was
present. Do not normalize it into a successful clinical classification.

For voice closure and processing retries, administrative closure without local
clinical facts now checks the complete admitted interview with the scope-only
request first. An ambiguous/out-of-scope decision closes as `CIERRE_SIN_DATOS`
before requesting a classification. Recognized clinical facts or a relevant scope
decision keep the clinical path; provider errors, missing scope and later
contradictions remain failures. A previously failed latest chat can reach this
administrative outcome through its normal retry endpoint after restarting the
backend, without resending or duplicating its patient turns. No historic record
is automatically rewritten during deployment.

Verification of this voice administrative-closure change: the isolated suite
recorded 705 tests, no failures or errors, and 16 skipped tests. The focused run
recorded 93 tests, including three opt-in real-Gemini checks with synthetic text.
A fictitious failed voice chat reached `CIERRE_SIN_DATOS` through the normal retry
service after exactly one scope request, without classification, queue entry or
duplicate patient turns. Separate checks kept colloquial and emotional complaints
in scope. Local regressions cover known clinical facts, relevant scope followed by
a contradictory final response, scope timeout and the shared 45-second budget.
The real application's chat records were not modified. These tests exercise the
backend processing path, not Android audio capture or speech recognition.

```powershell
./mvnw.cmd "-Dtest=ChatHardeningGeminiSmokeTest#reintentoRealDeVozSinHechosCierraSinPedirClasificacion+alcanceRealDeCierreConservaRelatosClinicosComprensibles" "-Dpretriage.gemini.hardening.live=true" "-Dspring.docker.compose.enabled=false" test
```

At the start of an ambiguous interview, local
`ACLARACION_FUERA_DE_OBJETIVO` alternating with semantic `INFERENCIA` can indicate
that the proposed clarification itself introduces a category or quantifies an
unclear complaint. The generation, correction and verification policies now all
start with an open clarification in the patient's words. This is separate from
the final-classification correction. `Chat IA question correction accepted`
identifies an automatically recovered question rejection; it contains only safe
codes. Failed interviewing turns still roll back, so inspect the draft/client
error instead of inferring their text from the saved history. In this failure
path, a chat containing only the greeting means the attempted patient turn was
not committed.

Verified on 2026-10-25 after the open-clarification correction: the isolated suite
reported 677 tests, no failures or errors, and 11 skipped tests. A separate opt-in
Gemini check used a fictitious ambiguous complaint, an emotional clarification
and a final negative reply. It reached `COMPLETADA` with `DATOS_RESUELTOS`; one
rejected proposal was recovered automatically without resending a patient turn.
The real chat message was tested locally with a simulated provider, not sent by
this diagnostic to Gemini. The application database was read only. Restart the
backend to load this correction; no mobile rebuild is needed.

```powershell
python -B scripts/test_backend_isolated.py
./mvnw.cmd "-Dtest=ChatHardeningGeminiSmokeTest#chatRealAclaraAbiertamenteAntesDeRegistrarUnMotivoEmocional" "-Dpretriage.gemini.hardening.live=true" "-Dspring.docker.compose.enabled=false" test
```

The opt-in check needs `GEMINI_API_KEY` in the process environment and makes real
provider requests with synthetic data. It uses mocked repositories and queue
services, without starting Spring or connecting to the application database.

If the first ambiguous turn and open clarification are already saved but the
following reply fails twice with `COMUNICACION`, inspect the controlled objective
instead of treating this as another failed initial clarification. An emotional
clarification can legitimately advance to `INVITACION`. Its correction must keep
that invitation, not request a new clinical datum or announce completion.
`objectiveType=INVITACION` in correction logs identifies this branch without
logging clinical text. The verifier accepts a brief neutral acknowledgment only
when followed immediately by a valid invitation; announcements of registration
and closure remain invalid. Rejected proposals are not retained, so these logs
alone do not identify the exact wording or prove a false positive.

Verified on 2026-10-26: the final isolated suite reported 688 tests, no failures
or errors, and 12 skipped tests. The exact reported clarification was replayed
locally with a simulated provider. Three opt-in checks with real Gemini and
synthetic text passed: acknowledgment plus invitation accepted, a registration
announcement rejected, and a three-turn emotional interview classified after
one final negative reply. Local regressions also cover a valid invitation
paraphrase outside the original phrase pattern, unrelated questions, missing
data, and excluded replies. No application records were rewritten, and no mobile
rebuild is required. These checks do not guarantee every future provider output.

```powershell
./mvnw.cmd "-Dtest=ChatHardeningGeminiSmokeTest#chatRealAclaraAbiertamenteAntesDeRegistrarUnMotivoEmocional+verificadorRealAceptaReconocimientoBreveAntesDeInvitacionEmocional+verificadorRealRechazaAnuncioDeRegistroComoPregunta" "-Dpretriage.gemini.hardening.live=true" "-Dspring.docker.compose.enabled=false" test
```

When transport errors coexist with `invalid_response`, `repeated_question` or
`premature_or_unsafe_closure`, do not attribute all delays to the provider. A
single message correction or final-result correction adds another Gemini request
within the shared budget.
In the historical chat 8 trace, `invalid_question_format` appeared repeatedly, but
the chat reached `COMPLETADA` after a manual retry. Treat that as a record of the
older punctuation validation behavior: punctuation no longer causes this rejection,
and a format-only issue does not trigger a correction or truncate Gemini's message.
The fix passed 458 Java tests on an isolated database. A synthetic check using real
Gemini and the real chat/provider services, with repositories simulated, accepted
an imperative invitation containing zero question marks in about 3.4 seconds.
The probe forced that style to reproduce the rejected case; it did not test the
HTTP endpoint or use patient data, and its timing is not a latency guarantee.
An earlier October 21, 2026 diagnosis also found two local contract/coverage mismatches:
blank `recomendacionSeguridad` rejected during an unfinished non-urgent turn, and
a denied `debilidad repentina` question not recognized by the completeness check.
The recommendation contract was fixed, and the later brief-interview change
removed the checklist requirement entirely. Final safety requirements, priority
rules, and provider transport errors remain distinct. Rejected raw responses are not stored, so a
generic closure rejection alone does not identify its exact underlying reason.

Verified on 2026-10-21: the new regressions reproduced both mismatches before
the fix. The 47 focused tests and then all 406 Java tests passed using
`python scripts/test_backend_isolated.py`, with an independently created
disposable database and Compose disabled. The application database was read
only for diagnosis; no chat or classification was rewritten. Restart the running
backend to load the changes; no mobile rebuild is needed for these server fixes.

The additional `field=observaciones reason=invalid_text` report prompted a review
of the descriptive-string contract. Additional notes may now be blank in any
phase, and pending clinical descriptive strings may be blank only during the
interview. Required keys/types and final clinical/safety checks are preserved;
an invalid-text log alone cannot distinguish a blank value from a null or a
wrong type because rejected provider content is deliberately not logged.
The additional regressions reproduced the failures before the descriptive-field
fix. Afterward, 105 focused tests and all 410 Java tests passed on disposable
databases with Compose disabled. No application data or model configuration
was changed during this follow-up.

Final verification on 2026-10-13: 397 Java tests passed with
`python scripts/test_backend_isolated.py`; the runner created and removed its own
database with Compose disabled. A synthetic real-provider correction also completed
without adding a patient message. See [the database verification incident](15-test-isolation-incident.md)
for the earlier shared-database loss and the isolation safeguard introduced afterwards.

The final classification input includes interviewer questions associated with
admitted patient turns strictly as context, so short replies can be associated
with those questions. BOT replies associated with excluded patient turns are
omitted too: quoting a rejected expression must not reintroduce it into provider
input. Interviewer text is not patient evidence. The symptom validator also
recognizes traumatic complaints such as moratón, hematoma and hormigueo.

Verified on 2026-10-26 after correcting indirect reuse of excluded text and onset
loss: 697 tests were recorded by the isolated suite, with no failures or errors
and 14 skipped tests. The local regressions check REST and Live history projection,
preservation of admitted questions, and affirmed versus uncertain/negated onset.
Three opt-in checks with real Gemini and entirely synthetic text also passed:
a BOT-only symptom was rejected despite a literal citation from an unrelated
patient statement, an omitted explicit onset produced `OMISION_INICIO`, and a
complete emotional interview preserved relative onset without inventing evolution.
The reported phrase was replayed only in local tests with simulated providers.
No completed chat, priority, symptom list or application record was rewritten.
Restart the backend to load the change; no mobile rebuild is needed.

For repeated questions in voice interviews, check `seguimientoEntrevista` as well
as the literal transcript: a shared onset question must resolve the symptoms it
referred to, temperature spellings such as `38° y medio` must preserve the half
degree, and thermometer paraphrases must share one attempt budget. A later symptom
must not inherit that earlier onset. An affirmed colloquial breathing alarm permits
closure even if other data remain pending; when Live omits its tool, the bounded
closure recovery may process the saved transcript once. See the current regression
commands and verification limits in [the progress policy](21-cierre-por-datos-resueltos.md).

Read-only diagnosis on 2026-10-28 found that chats 35, 36 and 37 were all persisted
as `COMPLETADA`, despite the reported voice warning. Each contained an additional
invitation after the first negative reply. STT variants `No, no.`, `No. Nada.` and
`No, nada.` were not recognized as declines and triggered an unnecessary
clarification. The local matcher now accepts those complete short negatives;
it preserves additional complaints, excluded-turn filtering and unresolved data.
Chat 36 also contains an out-of-range pain-scale transcription; accepting a
negative invitation does not turn that value into a valid intensity.

The lack of an error log does not identify the cause of a client voice warning.
Mobile now preserves an in-memory navigation snapshot, avoids replacing it with
a shorter pending server history, and automatically reads `GET /api/chat/{id}`
while confirming voice closure. Only a prolonged unconfirmed result exposes a
manual state check. A completed server result clears voice warnings; a late
socket failure cannot replace a received successful result. This requires an
updated APK and does not change historical chats or send their contents to Gemini.

Verified on 2026-10-28: 184 focused Java tests passed in
`ProgresoEntrevistaTest`, `EntrevistaVozBucleRegresionTest`, `SesionVozChatTest` and
`ValidadorCierreEntrevistaVozTest`. The initial regression reproduced the rejected
oral negatives before the matcher change. The session regression closes after one
`No, nada.` without another patient answer. These are unit tests with simulated
providers; no Spring integration context or application database is used. Mockito
was loaded as a startup Java agent to avoid a blocked dynamic attach in the sandbox.

Android `assembleDebug` and `testDebugUnitTest` passed with 38 unit tests, zero
failures/errors/skips, and Gradle stopped afterward. Nine recovery regressions cover
navigation snapshots, old/short GET histories, authoritative terminal results,
transient failures while classifying, read failures, bounded polling and cancellation.
The generated APK uses `http://192.168.100.241:8080`. This does not verify a new
phone recording or iOS compilation. Restart the backend and install the updated APK
to exercise both sides; no application process was restarted by the diagnostic.

The next read-only voice diagnosis found chat 38 closed as `FALLIDA`, with no
structured result. One negative was transcribed as `Nona`, followed by a request
for clarification and a second clear negative. Preserve that literal transcript;
do not silently reinterpret an unclear word as a clinical denial. All voice turns
had the batch-persistence timestamp, so the database cannot identify the exact
post-answer delay or prove which closure/provider step failed.

Two mobile regressions reproduced a separate display bug: `EstadoVoz.PROCESANDO`
unconditionally hid a persisted failure even after an explicit processing error
or the polling deadline. The revised precedence accepts those confirmed failures,
leaves the voice state inactive and permits processing retry. If no result can be
confirmed by the deadline, the UI stops loading and offers a state check without
inventing failure or reopening the interview. Cancellation of that exhausted client
channel must not reset the state to `PENDIENTE` in its `finally` block.

For a new attempt, correlate the safe `Voice closure ...`, `Voice provider ...`
and `Voice classification ...` records using chat ID. They include technical phase
timing and an explicit warning on incomplete closure, without logging patient
content. An incomplete closure could previously persist `FALLIDA` without a warning
from that branch. The new diagnostics do not establish the historic chat's cause.

Verification on 2026-10-28: both new stuck-processing regressions failed before
the display fix. The final Android build and all 42 unit tests passed, including
four new checks for server-confirmed failure with an open socket, a persisted
failure after the deadline, releasing the client voice state, and unknown results
without reopening/truncating the interview. Gradle was stopped afterward. The 184
focused Java tests passed with the phase diagnostics added. No provider request
used the historic chat, no chat was rewritten or reclassified, and no new phone
recording was tested. The latest APK was generated at 11:29 -03:00 with the existing
LAN backend URL; restart the backend for the new diagnostics and install that APK.

The subsequent report of shuffled messages when reopening the chat exposed a
separate persistence-order defect. `Chat.mensajes` ordered only by timestamp,
while all voice turns in a batch could have an identical timestamp. A PostgreSQL
regression with synthetic turns and reversed physical row order returned the
entire equal-time batch in reverse order before the fix. JPA now orders by
`fechaHoraEnvio ASC, id ASC`, stabilizing both the DTO and histories used by voice
and classification. A second regression preserves timestamp precedence over ID.
This does not establish why a historic classification failed, but removes an
unstable input order from later processing. No historical row is rewritten and
no result is manufactured. The reprocessing button remains appropriate for a
persisted `FALLIDA` chat; it retries classification of the saved responses.

Verified on 2026-10-28: the equal-timestamp PostgreSQL test failed before the
tie-breaker (`[5,6,7,8,9,10]` expected, reversed IDs returned). After the change,
260 focused tests passed without failures, errors or skips, including the three
history persistence tests and the chat/progress/voice regressions. Both runs used
`scripts/test_backend_isolated.py` with independent temporary databases and Compose
disabled; both temporary databases were removed by the runner. The domain diagrams
were regenerated and have no structural changes. The ordering fix needs a backend
restart and no additional mobile rebuild beyond the latest APK already generated.

```powershell
python -B scripts/test_backend_isolated.py --test ChatHistoryPersistenceTest,ChatServiceTest,ChatClasificacionCierreTest,ProgresoEntrevistaTest,EntrevistaVozBucleRegresionTest,SesionVozChatTest,ValidadorCierreEntrevistaVozTest
```

For a silent or generic assistant response, inspect `procesando_respuesta` separately
from `entrevista_finalizada`: the first keeps the interview/microphone open, while
the second confirms closure. A bot acknowledgement or a transcription chunk must
not clear server-owned processing. A stalled open transcript is saved before the
session offers resume; it must not invoke classification retry. Optional synthetic
session tests exercise omitted tools with a real REST classifier and mocked
repositories/transport, without using a microphone or the application database:

```powershell
./mvnw.cmd "-Dtest=SesionVozContinuidadGeminiSmokeTest" "-Dpretriage.gemini.hardening.live=true" "-Dspring.docker.compose.enabled=false" test
```

```powershell
./mvnw.cmd "-Dtest=ChatHardeningGeminiSmokeTest#chatRealAclaraAbiertamenteAntesDeRegistrarUnMotivoEmocional+verificadorRealRechazaSintomaSoloEnBotAunqueLaCitaSeaDelPaciente+verificadorRealRechazaInicioOmitidoPeseAReferenciaRelativaAfirmada" "-Dpretriage.gemini.hardening.live=true" "-Dspring.docker.compose.enabled=false" test
```

The shared onset validator recognizes `anoche`, `esta mañana`, `esta tarde`
and `esta noche`, in addition to existing dates/durations. A complete interview
using these expressions must not be rejected as lacking onset. Explicitly
unknown dates such as `no recuerdo si fue anoche` do not supply onset evidence.

`--debug-log` writes JSON on success or failure with the stage, masked user,
scripted turns, response origin and persisted validation details when available.
Do not commit logs containing real patient information. Store diagnostic files
under ignored `target/`; Maven clean removes them.

```powershell
python -m unittest discover -s scripts -p test_e2e_chat.py
./mvnw.cmd "-Dtest=ChatServiceTest,TriageIaClientTest,TriageResultDTOTest,AtencionHospitalServiceTest" test
```

After stopping the isolated backend, remove the disposable database:

```powershell
docker exec postgre psql -U myuser -d postgres -c "DROP DATABASE pretriage_chat_e2e"
```

## Cierre y recuperación del chat 39 (28/10/2026)

El flujo vigente está documentado en `23-server-owned-voice-closure.md`: cierre del
servidor en la frontera de turno con datos resueltos, clasificación pendiente
persistida y recuperación automática acotada sin botón de reprocesar en mobile.
Verificación final: 748 pruebas backend registradas (730 aprobadas, 18 opcionales
omitidas), cero fallos/errores; 43 pruebas Android aprobadas y APK generado. Se
regeneraron los diagramas del dominio. No se alteró ni reclasificó el chat 39 y no
se probaron nuevas grabaciones de teléfono ni se usaron historiales reales en Gemini.

## Turnos de texto y timeouts del chat 42 (28/10/2026)

`POST /api/chat/{id}/mensajes` ahora devuelve `202 ChatDTO`: guarda el turno antes
de procesarlo y GET expone `estadoTurnoTexto`, UUID e intentos. El script E2E envía
una sola vez con UUID y espera por GET hasta 180 segundos; no repite el POST.
Conserva compatibilidad con la respuesta síncrona de servidores anteriores.
`NO_DISPONIBLE` o clasificación `FALLIDA` fallan la prueba; la procedencia clínica
se comprueba en el resultado persistido, no se deduce de una respuesta HTTP 202.

Para diagnosticar un turno, correlacionar `Text turn completed`,
`Text turn attempt failed` y `Text turn stopped` con chatId, requestId, intento,
categoría y tiempo. No registrar el contenido clínico. Los timeouts de transporte
pueden habilitar un segundo intento automático; `COMUNICACION` es el rechazo de
una propuesta de pregunta y conserva una sola corrección dentro del mismo plazo.
Ver [turnos durables de texto](24-durable-text-turns.md).

Verificación backend: suite completa de 755 pruebas registradas, 737 aprobadas y
18 opcionales omitidas, sin fallos ni errores. Después se agregó una regresión del
cierre final fallido y pasaron las siete pruebas PostgreSQL de
`TextoTurnoServicePersistenceTest`. Los 15 tests Python de los helpers E2E pasan.
Todas las pruebas de persistencia usaron bases temporales eliminadas por el runner.
El historial del chat 42 se consultó sin modificarlo ni enviarlo a Gemini.
El APK Android final se generó a las 14:59 -03:00 con la URL LAN existente; pasaron
las 52 pruebas unitarias, sin fallos/errores/omisiones, y se detuvo Gradle. No se
probó un teléfono ni se compiló iOS.

## Recuperación automática del 5/11/2026

El backend completó 792 pruebas aisladas: 773 aprobadas, 19 omitidas y cero
fallos/errores. El runner usó una base desechable y la eliminó al terminar;
el log de esta ejecución es `target/isolated-tests.log`.

La validación Android con backend QA confirmó tanto la recuperación automática
tras una indisponibilidad de API inyectada por el harness como la recuperación
de un chat legado activo en `NO_DISPONIBLE`. También se verificó en la app el
cierre parcial: el snapshot `PARCIAL` quedó finalizado con `cierreParcial=true`,
siete mensajes únicos y sin composer, spinner ni acción de reintento. La consulta
1 siguió vinculada al chat 1717 y conservó la misma entrada de cola, sector,
prioridad 2 y orden 1 en `EN_COLA`; no se generaron `resultadoTriageJson` ni resumen.

Durante la prueba se corrigió el polling móvil para que un snapshot `PENDIENTE`
con `cierreParcial=true` no sustituya al snapshot terminal más reciente. La
reconciliación usa `versionEstado`; pasaron 63 pruebas Android con el ajuste. El
comportamiento vigente y sus campos están en
[recuperación automática y cierre parcial](34-automatic-chat-recovery.md).

Antes de iniciar esta versión contra una base PostgreSQL existente, aplicar
`scripts/migrations/20261105_chat_recovery.sql`. La migración agrega columnas y
el valor `PARCIAL` al CHECK de clasificación, que `ddl-auto=update` no garantiza
actualizar.
