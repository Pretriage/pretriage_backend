# Agent Context

## Project
Pretriage backend is a Spring Boot system for medical pre-triage, hospital selection, specialty queues, doctor sessions, and AI-assisted triage.

## Code Discovery
Prefer codebase-memory-mcp for code discovery:
1. search_graph
2. trace_path
3. get_code_snippet
4. query_graph
5. get_architecture

Use grep/rg only for configs, scripts, literals, or when graph results are insufficient.

## Source Of Truth
- Queue state: `EntradaCola` (per `GestorDeCola` of hospital + specialty + sector).
- Sector assignment: `AsignacionSectorService` (active sector with fewest `EN_COLA`, ties by name ASC, requires an active room).
- Estimated attention time: `EstimacionAtencionService` (per-patient scoped to the patient's sector; hospital-ranking estimate is hospital+specialty wide).
- AI triage structured result: `Chat.resultadoTriageJson`.
- Doctor active capacity: `SesionAtencionMedica` with `EstadoSesionMedica.ACTIVA` (per hospital+especialidad+sector).
- Hospital specialty availability: `Hospital.especialidades`.
- Reception admission: `AdmisionRecepcion` + `SesionRecepcion`; it does not use `Chat`.
- Shared queue entry for digital and reception flows: `IngresoColaService`.

## Domain Rules
- Patients choose a medical specialty before choosing a hospital.
- Hospitals are filtered by distance and selected specialty.
- Queue is per hospital, specialty and sector through `GestorDeCola`.
- Every active queued consultation should have an `EntradaCola` and an assigned `ConsultaMedica.sector`.
- New estimation logic must not use `GestorDeCola.consultasEnEspera`.
- Only `EntradaCola.EN_COLA` counts for estimated attention time.
- `EN_ESPERA`, `ATRASADO`, `CANCELADA`, and `FINALIZADA` do not count as waiting queue entries.
- Queue order is priority DESC, ordenRelativo ASC, fechaHoraIngreso ASC.
- Priority is numeric: 5 is highest, 1 is lowest.
- If no doctors are active, estimate with one virtual doctor and expose `hayMedicosActivos=false`.
- A doctor can use only one room at a time; a room can be used by only one doctor at a time.
- A doctor does not change specialty inside an active session; they must close the current session and start another.
- If a doctor pauses, that session is not active capacity for estimation.
- If a patient marked `ATRASADO` returns, they go to first place within their priority level.
- If a patient manually goes to `EN_ESPERA`, they return to their previous relative position.

## Verification
- Compile: `./mvnw.cmd test -DskipTests`
- Focused estimation tests: `./mvnw.cmd "-Dtest=AtencionHospitalServiceTest,EstimacionAtencionServiceTest,AsignacionSectorServiceTest,IngresoColaServiceTest" test`
- Full suite needs Docker Desktop access: `python scripts/test_backend_isolated.py`. Never run Spring integration tests against the app database. Docker Compose must be disabled because its connection details override an explicit datasource URL.
- Real chat E2E: `python scripts/e2e_chat.py --backend-url http://localhost:18080 --db-name pretriage_chat_e2e --messages-file scripts/chat_case_example.txt`
- Troubleshooting: if tests or `spring-boot:run` fail with `ClassFormatError: Invalid method Code length 0` (or any `ClassNotFoundException`/`ClassFormatError` while loading the Spring context), `target/classes` has corrupted `.class` files — usually caused by concurrent compilation (e.g., the VS Code Java extension writing to `target/classes` while Maven runs). Run `./mvnw.cmd clean` and rebuild. Avoid starting a Maven build while VS Code shows a Java "Building…" status.

## Documentation Maintenance
- Documentation is part of the definition of done.
- Any change to domain entities, relationships, states, business rules, flows, endpoints, DTO contracts, configuration, or verification commands must update the corresponding files under `docs/` in the same change.
- Regenerate `docs/generated/domain-model.md` and `docs/generated/domain-model.mmd` after changing JPA entities by running `python scripts/generate_domain_diagram.py`.
- Before finishing, verify documented endpoint paths and state transitions against the implementation.
- Do not leave documentation updates for a later task.
## Useful Docs
- Human overview: `docs/00-overview.md`
- Reception-assisted admission: `docs/09-reception-admission.md`
- Agent deep context: `docs/ai-agent-context.md`
- Queue and estimation: `docs/04-queue-and-estimation.md`
- E2E chat debugging: `docs/08-e2e-chat-debugging.md`
