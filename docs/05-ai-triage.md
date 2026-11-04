# AI Triage

Text, Live voice and continuation corrections share the
[interview and symptom-quantification policy](17-simple-chat-questions.md): one
short question about one datum or concept per turn, as instructed to Gemini, in everyday language, with no long symptom
lists. Interview progress tracks resolved data for each reported symptom, with one
open invitation. Separate technical budgets allow 24 total patient turns, three
attempts per datum and four consecutive replies without progress; reaching a budget
is recorded as partial closure, not complete data. It can ask about onset or duration and how a reported symptom affects the
patient. Pain may be quantified on a 0–10 scale or by its effect on daily activities;
temperature is requested only when the patient measured it. Unknown or unmeasured
values stay unknown without repeated prompts. Information about one symptom is never
applied to another, and the bot does not introduce unrelated symptoms. Saying there
are no other symptoms stops the search for new symptoms but still permits bounded
quantification of those already reported. Saying they do not want to continue ends
the interview. The open invitation is never repeated; a clear alarm closes immediately.
The result schema has no dedicated temperature or impact fields: a measured temperature
with its unit is retained in symptoms or observations, and impact in observations.
Mental and emotional distress is within triage scope even when no physical symptom is
reported. The interview must not demand a physical symptom to continue. Interpret phrases
such as “un vacío en el pecho” in context; that metaphor alone does not establish chest
pain, an alarm sign or a diagnosis. When the meaning is unclear, start with an open
clarification in the patient's own words, without offering physical or emotional
categories.

## Purpose

AI triage collects initial symptoms and produces a structured result. It does not replace medical evaluation.

The [chat hardening policy](20-chat-hardening.md) handles off-topic and incoherent
input before accepting clinical facts. Rejected text remains visible in history
with an internal `Mensaje.excluidoDelTriage` marker and is excluded from clinical
evidence. Excluded turns still count locally toward attempts and resource budgets;
their text is not resent to the provider. BOT replies associated with an excluded
patient turn are omitted from that projection too, until the next admitted patient
turn; they cannot indirectly reintroduce the excluded expression. Admitted patient
turns and their interviewer questions retain their contextual role. The final chat
classification requires patient citations and an independent semantic check within the remaining
operation budget; this does not change reception's existing fallback.

An admitted pronoun without an admitted patient antecedent does not establish a
symptom mentioned only by BOT. Final generation and verification also preserve
explicit relative onset even if the patient does not know the exact date, while
leaving evolution unknown unless a change was described. They do not infer dates,
durations or a physical symptom from an emotional context.

## Model

Text chat, final voice classification and reception forms use `GeminiJsonClient`
against the Gemini REST `generateContent` API. Speech interaction remains on Gemini Live.

```properties
pretriage.ia.gemini.api-key=${GEMINI_API_KEY:}
pretriage.ia.gemini.model=${GEMINI_CHAT_MODEL:gemini-3.5-flash-lite}
pretriage.ia.gemini.base-url=${GEMINI_API_BASE_URL:https://generativelanguage.googleapis.com/v1beta}
pretriage.ia.gemini.connect-timeout=5s
pretriage.ia.gemini.request-timeout=${GEMINI_REQUEST_TIMEOUT:45s}
pretriage.ia.gemini.max-output-tokens=${GEMINI_MAX_OUTPUT_TOKENS:8192}
pretriage.ia.gemini.max-attempts=${GEMINI_MAX_ATTEMPTS:2}
pretriage.ia.gemini.attempt-timeout=${GEMINI_ATTEMPT_TIMEOUT:20s}
pretriage.ia.gemini.retry-initial-backoff=${GEMINI_RETRY_INITIAL_BACKOFF:500ms}
```

The key is sent in `x-goog-api-key` and stays in the backend. Requests use
temperature zero and `generationConfig.responseFormat.text.mimeType=APPLICATION_JSON`
with JSON Schema. The REST client makes up to two HTTP attempts per Gemini request
within the operation budget. The normal budget is 45 seconds; a corrective request
uses the time remaining from the same budget. Eligible transient transport/HTTP
errors are retried under the bounded policy in [Gemini REST retries](19-gemini-retries.md).
Quota exhaustion without a valid `Retry-After`, missing configuration, blocked or
truncated responses and invalid JSON remain provider errors in chat; reception forms
retain their local fallback. Response bodies and clinical text are not logged.
Temperature zero does not guarantee identical classifications across calls.

REST output is bounded to 8192 tokens by default (previously fixed at 2048),
configurable through `GEMINI_MAX_OUTPUT_TOKENS` between 1 and 65536; the configured
model can impose a lower limit. A non-`STOP` candidate is always rejected, even if
its partial text parses as JSON. Logs identify the allowlisted `finishReason`,
numeric output/thought token counts and the configured limit; missing counters
are `-1`. Unknown reason values are replaced by `UNKNOWN`, and missing ones by
`MISSING`. Neither provider `finishMessage`, response bodies nor clinical text
are logged. Increasing the limit provides more headroom but does not repair safety
blocks or guarantee provider completion. Safety, authentication, invalid JSON and
semantic errors are not HTTP retries; one bounded remote correction can address an
invalid or repeated interview question, and final-result validation can request one
semantic correction. No local chat fallback is added.

Reception evaluates the highest reported pain intensity and retains its existing
deterministic low-risk guard and fallback. Spring AI/Ollama dependencies are removed.

## Chat Flow

`Chat.paciente` is `ManyToOne`: a patient can retain several historical chats
and create a new interview after cancelling a consultation. Closing a chat
does not delete its messages. The former `OneToOne` mapping created a unique
constraint on `chat.paciente_id`, which prevented a second interview even when
the first one was finalized. Existing PostgreSQL schemas require the migration
documented in [local development](07-local-development.md#chat-history-schema-migration);
`ddl-auto=update` alone does not remove that old unique constraint.

1. Patient starts a chat.
2. Backend creates `Chat` associated with patient.
3. Patient sends messages.
4. Backend sends conversation context to AI.
5. The backend decides whether to keep interviewing or request final classification.
   Intermediate text responses use `mensaje` and `estadoEntrada`; they cannot
   set a finalization flag or classification. If Gemini returns an incompatible
   intermediate response or repeats a question, the backend requests at most
   one remote correction for that patient turn, then validates the corrected message
   itself. A relevant continuation is also checked for clinical scope and cannot thank
   the patient for a completed registration, announce that answers were recorded, or
   otherwise close the interview. Punctuation does not determine validity: zero, two or Unicode question
   marks do not cause an error or a format correction, and Gemini's message is
   preserved as returned for relevant turns. Ambiguous/off-topic turns receive a
   fixed backend clarification or redirection, with no classification or queue update.
6. On finalization, backend stores the structured result and classification origin
   in `Chat.resultadoTriageJson`.
7. Result priority maps to `NivelDeGravedad`.
8. The existing queue entry's priority is updated. Hospital selection already
   entered the consultation into its hospital/specialty/sector queue.

The chat preserves a valid final model classification. It does not replace that
result with a locally generated summary just because the conversation contains
enough information. Follow-up questions fill gaps that matter to classification;
alarm evidence, per-datum progress and independent resource budgets inform backend closure.

`ChatTurnResponse.origenRespuesta` identifies the source of each response:
`GEMINI` for successful clinical model turns; `CONTROL_CONTEXTO` for scope handling
(a verified Gemini clarification for ambiguity, or fixed off-topic redirection).
`CIERRE_SIN_DATOS` identifies a neutral terminal response without a classification.
The final JSON additionally contains
`origenClasificacion=GEMINI` for completed classifications; a scope guard produces
no final result. A successful HTTP response alone does
not prove that Gemini REST supplied the classification. These fields are additive;
`respuesta` and `atencionEstimada` retain their existing meanings.

## Unified final classification and interview state

`finalizado` means that the interview has ended, not that Gemini necessarily produced
a result. `Chat.estadoClasificacion` distinguishes `NO_SOLICITADA`, `PENDIENTE`,
`COMPLETADA` and `FALLIDA`. Legacy null states infer `COMPLETADA` only if a result
already exists; otherwise they read as `NO_SOLICITADA`. The new JPA column is nullable
for existing rows and is added by `ddl-auto=update` without rewriting messages.

Once final classification is requested, a provider failure preserves the answers and
closes the interview with `FALLIDA`; it never assigns a local priority or updates the
queue with an invalid result. Text failures before interview closure still roll back
undelivered answers and permit continued interviewing. Voice hang-up before accepted
closure likewise preserves an unfinished interview. Transport failure after accepted
closure preserves a closed failed interview, without classifying an incomplete snapshot.

Text and voice use `ChatService.errorClasificacionFinal`: valid final structure,
priority 1-5, affirmative literal patient alarms or affirmative final-result alarms
requiring priority >=4 plus immediate attention, and no negation placeholders such as
`Ninguna` in affirmative allergy/medication/history lists. The Live summary is guidance
for extraction; it does not apply an independent priority threshold to final voice output.
Patient evidence for text alarms now uses the same literal validator as voice.
Final-result alarms must first pass patient citation and semantic validation. Text
continuations labelled relevant also undergo a separate scope check before they are
shown; an invalid candidate uses the existing single correction within the remaining
45-second operation budget.

A structurally parsed but incoherent final response requests at most one Gemini
correction with a final-output schema and the remaining 45-second budget. The same
validator checks that correction. No backend-generated clinical summary/priority is
substituted. Each REST request gets at most two HTTP attempts; a separate correction
request shares the original operation's 45-second total budget. Safety, authentication,
invalid JSON and semantic failures do not trigger HTTP retries. If correction is invalid
or time expires, state remains `FALLIDA` and result stays absent. Successful
classification stores the result and sets `COMPLETADA`, updating the queue once.
Provider exceptions after closure commit the closed failed interview via
`@Transactional(noRollbackFor=ProveedorIaException.class)`.

`POST /api/chat/{id}/clasificacion` retries processing a closed `FALLIDA` interview
without adding a patient message or asking more questions. It requires ownership and
the latest chat; a pessimistic row lock serializes retries. Completed or historical
chats cannot be reclassified by this operation. Mobile reads `estadoClasificacion`,
blocks text/voice after interview closure and offers a neutral `Reintentar` action.
The reception form fallback remains unchanged.

## Clinical language normalization

Patient-facing text and spoken replies must not announce preclassification, classification
or a priority calculation, nor promise a result on screen. These are internal clinical
fields; their DTO/schema and queue behavior are unchanged. The closing prompt and Live
farewell use `Gracias. Ya registré tus respuestas para el equipo médico`. Urgent-care
recommendations remain available. Live failure copy refers to completing the interview
without reopening a completed interview. Mobile uses neutral registration/attention labels and offers processing retry when needed.

Text interviews, corrective questions, Gemini Live interviews and final classification share
`LenguajeClinicoChat` instructions. Gemini corrects unambiguous spelling/transcription errors
in the structured summary, using Spanish from Argentina. Clinical facts (negations, location,
laterality, onset, intensity and units) must stay unchanged. Normalization must not turn a
symptom into a diagnosis or guess medication names, doses, allergies or diseases.

During the interview an unclear or unknown term should prompt a neutral clarification,
including spelling a medication name when necessary. Clear colloquialisms and regional
variants do not require clarification. If a term remains unresolved at forced closure
(alarm or answer limit), the model must quote it in `observaciones` as `termino no aclarado`,
without presenting it as a confirmed symptom, disease, medication or allergy. Other clear
patient facts and alarm handling remain available; spelling clarification must not delay
an alarm closure. This is contextual model guidance, not a medical terminology dictionary
or a guarantee that every semantic error or invented word will be detected.
If the only motive remains incomprehensible, the scope guard requests clarification
without generating a result. Unresolved terms can appear in observations only when
other clear patient facts support the classification. Once closure has actually
been accepted, a failed classification keeps the interview closed without inventing
clinical data to satisfy the schema.

Before persisting a valid final classification, a narrow deterministic canonicalization
replaces the regional variant `moratón`/`moraton` (and plurals) with `moretón`/`moretones`
in `motivoConsulta`, `sintomas` and `signosAlarma`. It never changes priority, medications,
allergies, history, observations (which can contain literal quotes) or other clinical facts.
Language normalization itself adds no provider request or local classification fallback; the shared final-result validator can request a semantic correction.

Original text messages and voice transcriptions are preserved verbatim. Transcription
precedence means clinical fact precedence, not a requirement to copy spelling errors.
Existing finalized chats are not rewritten; the policy applies to subsequent responses.
Endpoint paths, DTO shapes and queue state transitions remain unchanged.

Verification on 2026-10-13: 88 focused tests passed with
`./mvnw.cmd "-Dtest=LenguajeClinicoChatTest,ChatServiceTest,TriageIaClientTest,SesionVozChatTest,SesionVozIntegracionTest,AdmisionRecepcionServiceTest" test`.
Synthetic calls against real Gemini confirmed voice-finalization normalization of
`moratón`, text correction of `cabesa` and a neutral clarification of invented
`flurbitis`. Repositories were mocked; no real patient data was modified. This verifies
the final voice classification path, not microphone capture or live spoken behavior.

## Voice Chat (Gemini Live)

`WS /api/chat/{id}/voz` offers a speech-to-speech interview for the same chat
(model `gemini-3.8-live`, configurable). Gemini Live talks directly with the
patient; Gemini REST produces the final preclassification:

1. The backend (`SesionVozChat`) proxies client audio to Gemini Live over
   `BidiGenerateContent`, so the API key never reaches the browser.
2. Gemini's system instruction follows the same brief, evidence-led interview as
   text: no minimum answer count or full checklist, no diagnosis or priority, and
   only focused questions for information that matters. It allows at most four
   quantification questions about symptoms already named, plus one open invitation;
   each question covers one concept. A patient who says there are no other symptoms
   stops the search for new symptoms but may still be asked bounded questions about
   the symptoms already named. An explicit wish not to continue ends the interview.
   Unknown measurements are left unknown without repetition, and a clear alarm closes
   the interview immediately.
   If the chat already has text answers, Gemini receives that conversation and
   continues without repeating questions.
3. The backend accumulates Gemini's input/output transcriptions as patient/bot
   turns. The backend reconstructs per-datum progress from the prior and pending
   transcript, with the same resource budgets used for text.
4. When Gemini has enough data it calls `finalizar_entrevista` with a structured
    summary (`ResumenEntrevistaVoz`). `ValidadorCierreEntrevistaVoz` checks required
    text/list fields, pain range and patient evidence in saved and pending turns. There
    is no minimum response count and no checklist of mandatory topics;
    meaningful spontaneous context is retained. Closure follows the shared text and
    voice policy: one open invitation, resolved or explicitly unknown/declined data
    for reported symptoms, bounded clarification attempts and total resources, and
    immediate closure for a clear alarm or an explicit wish to stop.
    The validator still requires a structurally valid
    summary and patient evidence. Rejected calls return a corrective tool error and
    keep the interview open.
   `ChatService.validarAlcanceCierreVoz` also performs a read-only REST scope check
   before closure is accepted. Ambiguous/off-topic cases return a corrective tool
   response and keep the voice interview open, unless an independent resource limit
   or explicit stop requires administrative closure without a clinical result.
   If new patient input arrives during
   the check, the old snapshot cannot authorize closure. The final classification
   still waits for late transcriptions and normal transport close.
5. The backend stops sending microphone audio and signals `audioStreamEnd`.
   After Gemini's goodbye turn it drains late input transcriptions, requests
   transport closure and waits for normal close confirmation. Only then
   `ChatService.finalizarEntrevistaVoz` sends the summary plus the patient
   transcription to Gemini REST (`FINAL_SYSTEM_PROMPT` + voice clause: on conflict the
   transcription prevails). Once closure is accepted, it adds the transcription,
   closes the interview and starts classification. A provider failure commits the
   closed interview and `FALLIDA` state without a result or queue update.
6. The Gemini REST result goes through the same validation, priority mapping,
   `Chat.resultadoTriageJson` and queue update as the text chat. If Gemini REST fails,
   a valid but incoherent result may request one correction within the remaining
   45-second budget. Text and voice use the same final-result rules: literal patient
   alarms or validated affirmative final-result alarms require priority >=4 and
   immediate attention. The Live summary guides extraction; it does not independently
   impose a different priority floor. Invalid final structure and negation placeholders
   in affirmative history/medication/allergy lists are also rejected. If correction
   fails, the interview remains closed with state `FALLIDA`, no local replacement
   and no classification queue update. Transcript persistence is idempotent.

There is no provider event proving that every input transcription has arrived.
The normal close boundary preserves all frames received before its acknowledgement;
the bounded drain reduces the chance of cutting off delayed transcriptions.
An abnormal close or a close timeout after accepted closure saves the received
transcript as a closed failed interview without classifying and emits an error.

If the voice session ends before `finalizar_entrevista` (patient hangs up,
Gemini disconnects), the backend saves the unfinished transcription with
`ChatService.registrarTurnosVoz`, permitting continued interviewing. After accepted
closure, a classification failure uses `registrarCierreVozFallido` instead and never
reopens interviewing; processing can be retried separately. The stored patient text is Gemini's transcription,
so recognition errors reach the classification.
Protocol details: `docs/06-api-reference.md#voice-chat-gemini-live`.
For the full mobile/backend/Gemini interaction, sequence diagram and client
responsibilities, see [Chat de voz: interacción completa](14-chat-voz-gemini-live.md).

## Structured Result

The result contains fields such as:

- `motivoConsulta`
- `sintomas`
- `inicio`
- `evolucion`
- `intensidadDolor`
- `signosAlarma`
- `antecedentesRelevantes`
- `medicamentos`
- `alergias`
- `posibilidadEmbarazo`
- `observaciones`
- `nivelPrioridad`
- `requiereAtencionInmediata`
- `recomendacionSeguridad`

## Chat Output Validation

`TriageIaClient` validates Gemini REST output before the chat uses it. For an
intermediate text turn, Gemini's schema contains `mensaje` and `estadoEntrada`; it has no field
for finalization or priority. The backend decides whether to keep interviewing or
request a final classification. A final result must have a valid priority from 1
to 5 and meaningful clinical content. Invalid provider output is handled as a
provider failure; the chat never substitutes a local clinical result. Fixed scope
redirections are documented in [chat hardening](20-chat-hardening.md). The final schema is used
only after the backend decides that the interview can close: the reported symptoms
have resolved objectives (answered, explicitly unknown or declined), the patient
explicitly asks to stop, an alarm requires closure, or an independent resource
budget is exhausted. `ProgresoEntrevista` replaces the eight-answer/four-question
closure conditions with per-symptom data states, up to three attempts per datum,
24 total patient turns and four consecutive replies without progress. Budget
exhaustion is a partial-closure reason, not evidence that the missing data are known.
See [closure by resolved data](21-cierre-por-datos-resueltos.md).
Saying there are no other symptoms stops discovery of new symptoms; it does not
replace duration or impact for the symptoms already reported.
The final instruction explicitly derives `motivoConsulta` from reported symptoms
instead of allowing `"no informado"` for a described complaint, preserves the
reported onset, and separates current alarm symptoms from medical history.
Validation failures log fixed `field` and `reason` identifiers without logging
the patient's text or raw model response.

Final semantic verification accepts the required boolean `valido` and an optional
allowlisted rejection code (`SIN_RESPALDO`, `NEGACION`, `INFERENCIA`, `COMUNICACION`,
or `INTENSIDAD_NO_DOLOR`); `motivo` may also be null or omitted. Existing verifier
responses containing only `valido` remain valid. The code can guide one bounded repair request, but is never copied
from free-form provider text or stored in the clinical result. A true verdict with
a non-null rejection code is inconsistent and is rejected. A reported scale for a physical
"vacío" does not become `intensidadDolor` unless the patient explicitly reports pain;
the literal sensation is not normalized into chest pain or presumed emotional after
the patient clarifies it is physical.

Final structured responses require nonblank `motivoConsulta`, `inicio`,
`evolucion` and `posibilidadEmbarazo` strings, using `no informado` for unavailable
information as instructed by the final prompt. `observaciones` may be blank when
there are no additional notes. Required fields with absent keys, nulls or other
types are rejected. No clinical facts or filler notes are generated locally to
satisfy the schema. The result must still meet the final meaningful-complaint/symptom,
numeric-priority and safety checks.

`recomendacionSeguridad` is required and nonblank in a final result and every
immediate-attention response. Missing, null or non-string values are rejected.
No replacement recommendation is generated locally. The final-only schema requires
nonblank clinical descriptive fields while allowing empty additional notes, matching
the validator.

The shared text/voice closure check no longer requires alarm exploration or a
minimum of three answers. Short answers retain the preceding question as context;
a denied symptom does not become an affirmative alarm or a locally assigned
priority. A global refusal to add information permits closure even when the
complaint falls outside the local vocabulary, leaving unclear facts for the final
Gemini result to mark as unknown.

When a valid final result requires immediate attention, the chat response also
includes its `recomendacionSeguridad`, even if the model's `mensaje` omits it.

Each intermediate text request asks Gemini for `mensaje` and `estadoEntrada`; the backend decides
whether the interview remains open or moves to final classification. The prompt
instructs Gemini to ask about one datum per turn, and `LenguajeClinicoChat.REGLAS_PREGUNTA_TEXTO`
keeps closure instructions out of the question-writing task. An incompatible
intermediate response or repeated question can receive at most one remote correction for that turn. Question-mark
count and punctuation are not validity checks and do not trigger a format correction
or HTTP 500. Relevant Gemini questions are preserved; scope decisions select a
fixed clarification/redirection without clinical claims. A failed semantic correction returns HTTP 500
(`ProveedorIaException`). Each Gemini REST invocation allows up to two HTTP attempts;
a correction request uses the remaining time of the same 45-second operation budget.
Transient HTTP and transport retries follow [the bounded Gemini retry policy](19-gemini-retries.md).
Safety blocks, authentication errors and invalid JSON are not retried as HTTP
requests. While collecting answers,
failed text turns still roll back the undelivered patient message. Once final classification
is requested, provider failure preserves the last answer, closes the interview and marks
`FALLIDA`; no failed result or classification queue update is persisted.

## Priority Mapping

Priority then maps to medical severity:

```text
5 -> RIESGO_VITAL_INMEDIATO
4 -> MUY_URGENTE
3 -> URGENTE
2 -> NORMAL
1 -> NO_URGENTE
```

## Debugging

Use the real E2E script instead of mocked AI tests when tuning behavior:

```powershell
python scripts\e2e_chat.py --backend-url http://localhost:18080 --db-name pretriage_chat_e2e --messages-file scripts\chat_case_example.txt --debug-log target\debug_case.json
```

The debug log includes:

- Patient messages.
- Bot messages.
- Stored structured triage JSON.
- Assigned severity and queue priority.
- Queue state.

## Interview closure and brief answers

Text and voice share `ValidadorCierreEntrevistaVoz` patient-evidence checks.
Traumatic symptoms such as moratón/moretón, hematoma, swelling, tingling,
numbness, wounds and injuries also supply symptom evidence. Answers such as
`12 horas`, `un ocho` and `no` are interpreted only in the context
of the preceding onset, pain-scale, alarm or history question. An unrelated `no`
does not count as an alarm denial. The backend decides when to request final
classification. Intermediate text output contains `mensaje` and `estadoEntrada`, so it cannot
finalize the interview or set priority. If an intermediate response contains an
invalid or repeated question, the backend requests at most one remote correction
for that turn and validates it; it never generates a local question, summary or
classification. Voice uses the same brief interview policy, without a minimum
answer count or topic checklist. Historical results with
`origenClasificacion=OLLAMA` remain unchanged in storage.

## Chat policy updated on 2026-10-12

Chat text and final voice classification use Gemini exclusively. Local fallback
questions and priority generation have been removed from `ChatService`. Schema,
closure, alarm and repetition checks still reject invalid provider responses.
The reception web form continues to use `TriageFormularioService.resultadoFallback`
through `AdmisionRecepcionService`; its rules are unchanged. Historical results
with `FALLBACK_LOCAL` or `OLLAMA` remain readable and are not rewritten.

## Historical verification of the Ollama Chat Fix

The 2026-09-29 verification used `llama3.2:3b` and an isolated PostgreSQL database:

- Full Java suite: 256 tests passed; Python E2E helper suite: 11 tests passed.
- The four-message example completed with final origin `OLLAMA`, preserved
  `inicio="desde ayer"` and `intensidadDolor=5`, and stored priority 2 consistently
  in the result, consultation severity and queue. Intermediate questions can
  still come from the explicitly identified local fallback.
- The initial synthetic chest-pain/breathing-difficulty alarm used local fallback
  because Ollama supplied `motivoConsulta="no informado"` despite reported symptoms.
  After correcting the final instruction, the real API case completed in one
  turn with origin `OLLAMA`, priority 5, alarm signs and immediate attention,
  preserving the reported onset of ten minutes. Its visible chat message also
  includes the urgent safety recommendation. No `--allow-fallback` was used.

These checks verify integration and persistence, not clinical accuracy. The
model could still return invalid or incomplete content, so the former fallback remained
necessary and visible. Use explicit case expectations when evaluating clinical
classification; the E2E does not assume the old local priority is the correct
model classification.
