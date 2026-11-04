# Development integration into voice chat

28 October 2026. The requested direction is `origin/desarrollo` into the current
voice-chat branches. Development/main branches are not merge targets, and this
operation does not push to the remote.

## Source revisions and retained work

| Repository | Voice branch | Development source | Result |
| --- | --- | --- | --- |
| Backend | `feature/chat-voz-gemini-live` | `892960b` | Import metrics demo runner; retain durable text/voice work committed as `8c6221a` |
| Mobile | `feature/chat-voz-gemini-live` | `5487f62` | Source already included; commit pending text/voice recovery as `c588cf2` |
| Web | `codex/chat-voz-gemini-live` | `23dbcbe` | Two incoming development commits merged without conflicts as `2fcfa16` |

The web metrics dashboard was already present in the voice branch. The two newly
imported web commits add and refine the staff password-change flow, including
the account screen, authenticated BFF proxy and documented specification. The
existing dashboard BFF route and response field types match the current backend's
`GET /api/admin/hospitales/{hospitalId}/metricas?desde=...&hasta=...` contract.

The incoming backend runner previously activated unconditionally on startup and
could insert 310 synthetic patients/queue entries into the configured datasource.
Integration gates it behind `pretriage.seed.metricas-demo.enabled=true` and requires
an explicit demo hospital place ID. Normal startup leaves it inactive. The runner
remains available for disposable demonstration environments; it is not executed
against the application database during verification.

## Cross-client compatibility

The backend's durable text submission endpoint returns `202 ChatDTO`. Mobile
handles this response, restores pending state through GET, and retains support
for legacy immediate `200 ChatTurnResponse`. The web application does not submit
patient chat messages through that endpoint; its generated legacy response type
therefore does not change active web behavior. Voice closure and final clinical
classification retain their existing guarded processing.

## Verification

Backend verification uses `python -B scripts/test_backend_isolated.py`, with
Docker Compose disabled and an independently created and deleted PostgreSQL
database. New configuration tests check that the metrics runner is absent by
default and when disabled, and exists only after explicit activation without
performing SQL during context creation.

Mobile verification uses `:composeApp:assembleDebug :composeApp:testDebugUnitTest`
with `-PBACKEND_BASE_URL=http://192.168.100.241:8080`, logging to a temporary file and
stopping Gradle afterward. Web contract tests, typecheck and production build
passed; lint completed with zero errors and one navigation warning in the existing
invitation screen.

Final checks on 28 October: backend full suite registered 759 tests, with 741
passed, 18 opt-in tests skipped and no failures/errors. All 15 Python E2E helper
tests passed. Mobile build succeeded and its 52 unit tests remain passing;
unchanged compile/test tasks were correctly reused by Gradle. Gradle was stopped
after verification. Web passed three contract tests, typecheck and production
build, with the single non-blocking lint warning noted above.

No real patient data was sent to Gemini, no synthetic metrics seed was run
against the application database, and no phone or authenticated browser E2E
session was performed. Local `desarrollo` and `main` refs remain unchanged.
