# AWS backend runtime profile

The `aws` Spring profile packages the backend without depending on the local
`application.properties` or `.env` files. Start it with
`SPRING_PROFILES_ACTIVE=aws` and provide runtime configuration through the
container environment or its secret manager.

## Required runtime configuration

| Environment variable | Purpose |
| --- | --- |
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC URL for the AWS database |
| `SPRING_DATASOURCE_USERNAME` | PostgreSQL runtime user |
| `SPRING_DATASOURCE_PASSWORD` | PostgreSQL runtime password |
| `AUTH0_ISSUER_URI` | Exact JWT issuer URL expected by the resource server |
| `PRETRIAGE_VOZ_ORIGENES` | Comma-separated exact HTTPS origins allowed to open voice WebSockets |
| `AWS_S3_BUCKET_NAME` | Bucket holding clinical study files |
| `AWS_S3_REGION` | Bucket Region; `AWS_REGION` is used if this is omitted |

The `AUTH0_BASE_PATH`, `AUTH0_M2M_CLIENT_ID`, `AUTH0_M2M_CLIENT_SECRET`,
`AUTH0_M2M_SCOPE`, and `AUTH0_APP_CLIENT_ID` settings are also needed by the
corresponding Auth0 flows. Set `PRETRIAGE_FRONTEND_BASE_URL` to the deployed
frontend URL when invitations or password reset links should point to the
frontend.

`GEMINI_API_KEY` enables text and voice AI. `GOOGLE_MAPS_API_KEY` enables the
Google Places and routing features. SMTP is optional while invitation email is
in local handoff mode; for real email, set `PRETRIAGE_INVITATIONS_EMAIL_MODE=smtp`
and configure `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`,
`SMTP_AUTH`, and `SMTP_STARTTLS` through secrets/configuration.

## S3 permissions

The clinical study service downloads, uploads, and removes objects. Grant the
backend compute role `s3:GetObject`, `s3:PutObject`, and `s3:DeleteObject` for the
required object ARNs in the configured bucket. The backend does not need
`ListBucket` for these operations.

The AWS SDK default credential chain is used when both legacy properties
`aws.accessKeyId` and `aws.secretAccessKey` are blank or absent. The AWS profile
leaves these properties blank so the SDK can read standard AWS credentials,
including session tokens, or use EC2 instance profiles and ECS task roles. The
legacy static key configuration remains available for local development; the
backend rejects a partial key pair.

## Browser and WebSocket origins

`PRETRIAGE_CORS_ALLOWED_ORIGINS` optionally configures exact origins for direct
browser HTTP calls. It is empty by default, which denies cross-origin browser
requests. Wildcards are rejected. `PRETRIAGE_VOZ_ORIGENES` is a separate,
required allowlist for `/api/chat/{id}/voz`; set it to the deployed frontend
origin(s). Mobile clients that do not send an `Origin` header do not need to be
listed for HTTP CORS.

The voice path uses its own CORS configuration before the general HTTP rule,
so an HTTP allowlist left empty does not override the voice allowlist. Local
development retains the existing voice origin default; the AWS profile requires
an explicit value.

Voice requires a WebSocket upgrade at `/api/chat/{id}/voz`; clients must connect
over `wss://` in deployed environments. The handshake carries its bearer token
as the `access_token` query parameter because browser WebSocket APIs cannot set
an Authorization header. Configure the proxy and access logs to preserve the
upgrade and redact query strings. The application closes an idle WebSocket after
five minutes, so proxy idle timeouts should be compatible with that limit.

## Health and persistence

Only Spring Boot Actuator's `health` endpoint is exposed, with details hidden.
`GET /actuator/health` is public for load balancer checks; liveness and readiness
groups are available under `/actuator/health/liveness` and
`/actuator/health/readiness`. Readiness includes the database check. Mail health
is disabled because SMTP is an optional feature and may not be configured.

Multipart upload limits are 10 MiB per file and 12 MiB per request. The Hikari
connection pool defaults to five connections, with one idle connection, to keep
small temporary deployments bounded.

The profile disables Docker Compose auto-start and DevTools restart. It defaults
`JPA_DDL_AUTO` to `update` so stopping and restarting a temporary demo does not
drop its schema. `update` is a demo convenience, not a production schema
migration strategy. Once versioned migrations cover the schema, run with
`JPA_DDL_AUTO=validate` and apply migrations as a separate deployment step.
