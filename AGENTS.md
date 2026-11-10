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
- AWS Agent Toolkit setup: `docs/26-aws-agent-toolkit.md`


<!-- BEGIN AWS Agent Toolkit rules -->
# AWS Guidance for the new AWS experience

This user has signed up for the new AWS experience. This experience lets you sign into AWS using a social provider and requires the following additional context.

Where this guidance conflicts with the project's own instructions, the project's instructions take precedence.

## Context

### Terminology:

- Say "project" instead of "account" — a project contains an AWS account and settings for sharing with other collaborators
- Say "team member" instead of "IAM user" — users are invited by email, not created or federated in IAM
- Say "AWS Settings" when referring to management tasks at [settings.aws.com](https://settings.aws.com/) (project management, billing, team members, spend limits). Users view their actual AWS resources in the AWS Management Console.
- Say "selected Region" when referring to the user's Region — not "home Region"
- The user has a managed IAM experience. This includes a managed service control policies (SCP) and resource control policies (RCP) that govern the use of AWS. They will still need to use IAM to create policies to let services work with each other. If there are questions about the SCPs or RCPs, go to the documentation at https://docs.aws.amazon.com/accounts/latest/reference/scps-and-rcps-for-projects.html

### Constraints:

- All projects share a single AWS Region determined by the user's contact address. Resources cannot be created in other Regions
- When developing:
  - MUST create all Regional resources in the project's assigned Region
  - You CAN create AWS WAF and Cloudwatch Logs resources in us-east-1 when there are global resources (like a global WAF instance) that require a connection to dependencies in us-east-1. You should not use these for any other reason, because resources in the selected Region will provide lower cost (due to no cross-Region traffic), increased availability (due to no cross-Region traffic), and easier manageability (due to not needing to look in another Region). When you need to do an inventory of resources, you need to look in both the selected Region and us-east-1 for Cloudwatch Logs or WAF resources.
  - MUST NOT attempt to create Lambda, API Gateway, or other Regional resources in any other Region
  - MUST direct users to confirm their Region in AWS Settings > View all projects > Overview > Additional Info > Region. If the user cannot confirm their Region, check in ~/.aws/config
  - MUST NOT use Lambda@Edge — excluded from both Lambda and CloudFront
  - MUST NOT use CloudFormation StackSets — no multi-account or multi-Region deployments
  - MUST NOT attempt cross-Region actions — no cross-Region replication for DynamoDB/S3/RDS, no multi-Region KMS keys
  - MUST NOT use Route 53 cross-Region routing — geolocation, latency-based, and failover routing policies are not available
  - CloudFront is a global service and its actions ARE allowed in `us-east-1`. A user can create a CloudFront distribution pointing to their project-region Lambda function URL or API Gateway. However, Lambda and API Gateway themselves MUST NOT be created in `us-east-1` — they must be in the project Region.
  - Reduced availability in `eu-north-1` specifically: Amazon Rekognition, Amazon Textract, Amazon Personalize, AWS App Runner are not available in that Region.
- IAM permissions for human access are managed by AWS. Don't assign roles to team members unless absolutely necessary
- The user may have a spend limit if they are on the paid plan. The limit that pauses their project if it's exceeded. If resources suddenly become inaccessible, ask if they have a spend limit configured. Only project owners can modify a spend limit.
- When developing:
  - MUST ask about spend limit status if the user reports sudden "Access Denied" errors on operations that previously worked
  - MUST direct users to check spend status in AWS Settings > Billing
  - MUST check if a user has upgraded their account to the paid plan
  - MUST ask the user if they want to clean up the successfully created resources or keep them to reduce cost
- The user sets up billing, creates spend limits, and retrieves and pays invoices in AWS Settings. The user creates budgets and optimizes their costs in the AWS Billing and Cost Management console
- Not all AWS services are available. If a service isn't working, do the following:
  1. Run the command `aws freetier get-account-plan-state`
  2. If accountPlanType": "FREE", check the [Free Tier supported services list](https://docs.aws.amazon.com/accounts/latest/reference/supported-services-sign-up-new.html#supported-services-free-tier) next,
  3. If accountPlanType": "PAID", check the [Paid Tier supported services list](https://docs.aws.amazon.com/accounts/latest/reference/supported-services-sign-up-new.html#supported-services-paid-plan).
  4. If neither list shows the service, check the [Not supported for this experience list](https://docs.aws.amazon.com/accounts/latest/reference/supported-services-sign-up-new.html#unsupported-services). The user will need to activate advanced features to access this service.
- Users can activate advanced AWS services and capabilities for their account.
- For any AWS task or question — building, configuring, migrating, debugging, or operating a service, writing SDK code, authoring CDK/CloudFormation, or answering how a service works — first call `aws___search_documentation` with topic `agent_skills` and load the matching skill with `aws___retrieve_skill`. Do this even when you believe you know the answer: skills carry current service behavior that your training may not. Prefer the skill's guidance over general knowledge. Skip it only for work that does not involve AWS.

### Help level

- help_level (required): LOW, MEDIUM, or HIGH. While a user is building, you MUST ask the user: "How much guidance would you like from me? Low (I only flag security risks), medium (I ask a couple of clarifying questions if something seems off), or high (I explain what I'm doing, suggest alternatives, and flag best practices)."

You CAN update this rule file to save a user's help_level.

Constraints for each level:

**LOW:**

- MUST follow all constraints in this context file
- MUST execute the user’s request without modification
- MUST NOT ask clarifying questions unless the action would create a security vulnerability
- MUST NOT suggest alternatives or improvements

**MEDIUM:**

- MUST execute the user's request
- MAY ask up to two clarifying questions per task if the request has an ambiguity or a potential issue
- MUST NOT repeat a question or suggestion the user has already dismissed
- MUST NOT explain trade-offs or alternatives unless the user asks

**HIGH:**

- MUST explain what each step does and why before executing it
- MUST suggest alternatives when a better approach exists
- MUST flag best practices and explain trade-offs
- MUST still execute the user's choice if they disagree with a suggestion
<!-- END AWS Agent Toolkit rules -->
