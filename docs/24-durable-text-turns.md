# Durable text interview turns

Updated 28 October 2026. This lifecycle applies to `POST /api/chat/{id}/mensajes`;
voice closure and legacy final-classification recovery are described in
[server-owned closure](23-server-owned-voice-closure.md).

## Failure observed

The 14:26–14:31 logs show REST transport timeouts, followed in some requests by
insufficient remaining time for question correction. A semantic rejection with
`COMUNICACION` is a rejected candidate, not a delivered BOT message. Generation,
independent verification and one correction share a 45-second operation budget.
The former synchronous HTTP request could fail after the mobile screen had gone
away; neither its pending state nor its delivery outcome survived that navigation.

The stored chat 42 also contains a mixed pain-intensity/functional-impact question.
The local tracker now recognizes explicit pain intensity before functional-impact
wording, so the answer `8` resolves intensity without inventing functional impact.
This does not remove semantic verification or change the provider/model.

## API and states

The client sends `contenido` and an optional UUID `solicitudId`. New mobile creates
one UUID per submission. The backend commits exactly one patient message and
`estadoTurnoTexto=PENDIENTE` before returning HTTP `202` with `ChatDTO`. Provider
work runs in a bounded executor, outside the acceptance request.

`ChatDTO`, including GET and chat creation, adds `estadoTurnoTexto`,
`solicitudTurnoTextoId` and `intentosTurnoTexto`:

| State | Meaning | Client behavior |
| --- | --- | --- |
| `SIN_SOLICITAR` | No tracked text submission | Input available if interview open |
| `PENDIENTE` | Saved patient message awaiting its verified answer | Show waiting; disable text and voice |
| `COMPLETADA` | Verified answer and turn completion committed together | Render canonical history; check final classification separately |
| `NO_DISPONIBLE` | Automatic processing ended without an acceptable answer | Stop waiting; disable text and voice; no resend button |

The UUID is stored on each patient `Mensaje`, with unique `(chat_id, solicitud_id)`.
Replaying a known key with the same content returns the current snapshot without
adding input or scheduling work. Reusing it for different content returns `409`.
A new key while pending or unavailable also returns `409`. Optional keys preserve
request-body compatibility, but clients requiring idempotency must supply one.
Both the WebSocket handshake and voice persistence enforce the text-state guard.

## Worker and recovery

The worker reserves its attempt in a separate committed transaction before calling
Gemini. Only typed transient transport/provider failures permit a second workflow
attempt. There are at most two 45-second operations, retaining the existing bounded
HTTP retries and semantic correction within each operation. Invalid responses,
semantic rejection, credentials, TLS and safety failures do not open retry loops.
No local clinical answer, priority or diagnosis is generated on failure.

The existing patient message is processed directly. A successful BOT response,
queue update when applicable, and `COMPLETADA` commit atomically. Final classification
and interview completion remain separate states; failure to answer an interview
question does not claim that clinical triage completed.

A startup scan and a 30-second scheduled scan recover only tracked pending turns.
A persisted claim expires after 150 seconds; claims and attempt budgets survive
restart. Request ID and attempt number fence processing and claim updates, so an
older worker cannot release a newer attempt. An executor rejection leaves
committed work eligible for a later scan.
Historical messages without tracking keys are never enrolled automatically.

## Mobile recovery

On navigation back into a chat, GET restores the server state and canonical history.
The screen polls status while pending, without repeating the POST. Transport
uncertainty blocks additional input until status can be reconciled. Polling is
bounded; loss of connectivity becomes an explicit unknown state with a status-only
refresh, rather than indefinite typing or a resend action. Backend terminal failure
shows a short unavailable message and stops loading.

This removes the long-lived HTTP dependency; it does not make a slow external
provider instantaneous. Restart the backend and install the matching mobile build
to activate the new response contract.

## Schema and verification

For externally managed schemas, apply
`scripts/migrations/20261028_durable_text_turns.sql` before running the new backend.
It adds nullable request IDs and text-state columns without processing old chats.
Hibernate-managed local schemas obtain the same fields through their configured
schema update. The migration is not run automatically by this task.

Persistence tests use a disposable PostgreSQL database and mocked provider calls;
they check visibility during a blocked request, idempotency, retry budgets and
terminal input blocking. Run `python scripts/test_backend_isolated.py`; never run
Spring integration tests against the application database.

Verified locally on 28 October: full backend suite, 755 recorded / 737 passed /
18 optional skipped / zero failures or errors; then seven focused persistence
tests passed after adding the final-classification failure regression. The 15
Python E2E helper tests passed without external calls. No historical chat was
rewritten or sent to the provider, and no phone microphone session was exercised.

Android `assembleDebug` and all 52 unit tests passed with zero failures, errors or
skips. The final APK was generated at 14:59 -03:00 using
`http://192.168.100.241:8080`; Gradle was stopped afterward. The client tests cover
pending recovery, unavailable state, late previous-turn snapshots, canonical
terminal history and continued polling during final-classification recovery.
Neither physical phone behavior nor an iOS build was verified in this run.
If polling expires during a final text classification, its client presentation
becomes unknown rather than continuing the processing banner. Status-only refresh
can reconcile the unchanged server job afterward.
