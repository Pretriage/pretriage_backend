# Staff identity, hospital memberships, and invitations

Status: memberships, scoped roles, capability discovery, hospital invitations,
acceptance, resend with token rotation, local/SMTP email adapters,
suspension/reactivation, last-admin protection and basic audit contracts are
available. Universal Login/PKCE, persistent email outbox, MFA, platform hospital
creation, room/specialty management and patient account claiming remain follow-up
work.

## Purpose

PreTriage needs one person to keep a single identity while acting in different
capacities at one or more hospitals. Staff access must be granted by an authorized
hospital administrator instead of public self-registration. Patients remain global
users and are not administrative members of a hospital.

The design separates four concepts:

1. Authentication: proving who the person is, delegated to Auth0.
2. Global identity: the local `UsuarioAuth` associated with the stable Auth0 subject.
3. Hospital membership: the relationship between that identity and one hospital.
4. Authorization: roles and permissions scoped to that membership.

## Agreed principles

- One person has one Auth0 account and one local identity.
- A person can belong to several hospitals.
- Roles are scoped per hospital and are additive within that hospital.
- Staff cannot self-assign medical, reception, or administrative access.
- New staff choose their own password through a single-use invitation flow.
- Existing users reuse their account when invited to another hospital.
- The backend remains authoritative for hospital membership, clinical permissions,
  assignments, status, and audit history even if Auth0 Organizations is adopted.
- The public registration contract must never accept an administrative role chosen
  by the caller.
- Hospital and platform administration are different levels of authority.

## Roles and permissions

### Platform administrator

Global, exceptional role operated by the organization responsible for PreTriage.
It can create hospitals, invite the first hospital administrator, suspend a hospital,
and perform audited recovery operations. It cannot be granted by a hospital admin.

### Hospital administrator

Scoped to one hospital. It can manage that hospital's staff, invitations,
medical assignments, specialties, rooms, and operational configuration. Medical
coordination is part of this role. It cannot modify another hospital or create a
platform administrator.

### Doctor

Scoped through hospital and specialty assignments. It can start a medical session,
consume its authorized queue, call patients, record presence or absence, provide
care, and finish consultations.

### Receptionist

Scoped to one or more hospitals. It can start a reception session and perform the
assisted-admission workflow only for an assigned hospital.

### Patient

Global self-service role. A patient owns their personal account and consultations
but is not a member of a hospital's administrative organization.

## Proposed domain model

### Global identity

`UsuarioAuth` continues to use the Auth0 `sub` as its stable identifier. Email is a
verified contact and discovery attribute, not the relational primary key because it
may change.

### Hospital membership

Introduce `MembresiaHospital`:

```text
id
usuario_auth_id
hospital_id
estado: INVITADA | ACTIVA | SUSPENDIDA | REVOCADA
fecha_creacion
fecha_aceptacion
fecha_suspension
creada_por_usuario_auth_id
```

The pair `(usuario_auth_id, hospital_id)` must be unique.

Introduce either a join table or entity for membership roles:

```text
membresia_id
rol: ADMIN_HOSPITAL | MEDICO | RECEPCIONISTA
```

Membership roles remain assigned when individually suspended. The separate
`membresia_hospital_rol_suspendido` collection records suspended role IDs;
effective roles are assigned roles minus suspended roles. Hospital authorization,
including administration and staff workspace access, uses effective roles. The
`/api/staff/me` context exposes effective roles in `roles` and omits memberships
with no effective roles. Administrative personnel and mutation responses retain
the assigned role list in `roles`. A targeted suspension preserves the
membership state and other roles. Role
reactivation restores the existing assignment. Existing PostgreSQL deployments
must apply `scripts/migrations/20261109_membresia_rol_suspendido.sql` before
starting the updated application.

The former `COORDINADOR_MEDICO` role is consolidated into `ADMIN_HOSPITAL`. An
idempotent compatibility migration converts existing memberships and pending
invitations without removing any other roles.

This replaces role inference from the mere existence of a `Medico` or
`Recepcionista` row. `RolSistema` should represent platform-level authority only;
it must not encode hospital-scoped work profiles.

### Doctor profile and credentials

A doctor profile belongs to the global identity and is reused across hospitals.
Professional credentials should support jurisdiction-specific registrations:

```text
PerfilMedico(usuario_auth_id, datos_profesionales...)
CredencialProfesional(perfil_medico_id, numero, tipo, jurisdiccion, estado_verificacion)
AsignacionMedicoHospital(membresia_id, especialidad_id)
```

Credential uniqueness must be defined by the applicable jurisdiction and type, not
only by a free-form registration string.

The first implementation now persists `CredencialProfesional` with:

```text
numero
tipo: NACIONAL | PROVINCIAL
jurisdiccion (NACION for national registrations; province for provincial ones)
estado: PENDIENTE_VERIFICACION | VERIFICADA | SUSPENDIDA | VENCIDA
```

One doctor may own several credentials. The tuple `(numero, tipo, jurisdiccion)` is
unique. The former `Medico.matricula` column is retained temporarily as a legacy
compatibility field and must not be used as the future source of truth.

### Reception profile

Reception normally needs no global professional profile. Its access is represented
by an active membership carrying the `RECEPCIONISTA` role. This also replaces the
current one-directional hospital/receptionist association and permits multi-hospital
assignments explicitly.

### Invitations

Introduce `InvitacionHospital`:

```text
id
hospital_id
email_normalizado
estado: PENDIENTE | ACEPTADA | EXPIRADA | REVOCADA
roles_solicitados
token_hash
vence_en
invitada_por
aceptada_por_usuario_auth_id
datos_profesionales_pendientes_json (temporary, minimized)
fecha_creacion
fecha_aceptacion
```

Only a hash of the invitation secret is stored. Reissuing an invitation revokes the
previous active secret. Pending professional data must be minimized and removed or
transferred after acceptance.

## Staff invitation flows

### New identity

1. A hospital administrator enters email, requested roles, and role-specific data.
2. The backend verifies the administrator's active membership and permissions.
3. It validates the hospital, specialties, credentials, and duplicate invitations.
4. It creates a pending invitation and sends an expiring single-use link.
5. The recipient sees the inviting hospital and requested role before accepting.
6. Auth0 Universal Login lets the recipient create an account and choose a password.
7. The callback verifies that the authenticated, verified email matches the invite.
8. In one transaction the backend creates or links `UsuarioAuth`, activates the
   membership, persists professional data, and marks the invitation accepted.

No password is generated or sent by PreTriage.

### Existing identity

1. The same invitation is sent to the existing verified email.
2. The recipient authenticates with the existing account.
3. Acceptance adds or updates only the target hospital membership, roles, and
   assignments.
4. Existing credentials and memberships at other hospitals remain unchanged.

An administrator should not silently grant active access without acceptance. Until
the recipient accepts, the invitation remains pending and authorizes nothing.

### Role-specific information

For doctors, the invitation can request professional credential, jurisdiction, and
initial specialty assignments. The recipient should confirm this information;
verification status is controlled by the hospital or platform process.

For receptionists, only identity and hospital role are required unless the product
later introduces local employee identifiers.

## Patient registration and identity linking

### Digital patient

A patient may self-register through Universal Login, verify email, and complete the
patient profile. Staff roles are never offered in this flow.

### Reception-created patient

Reception may create a patient without `UsuarioAuth`, as it does today. A later
"claim account" invitation can link a verified identity to the existing patient.
Knowledge of a DNI alone is insufficient. Linking must require additional proof,
such as a verified contact channel or an audited in-person confirmation, and must
guard against duplicate patient records.

## Bootstrap of the first administrators

### First platform administrator

The first platform administrator is provisioned out of band through a one-time,
audited deployment command or migration using the `sub` of an existing verified
Auth0 identity. There is no public bootstrap endpoint and no default password.

The bootstrap operation must either run only when no platform admin exists or
require a separately protected operational credential. A break-glass identity must
be controlled operationally, protected with MFA, and audited when used.

### First administrator of a hospital

1. The institution requests onboarding outside the normal staff UI.
2. A platform operator verifies the institution and responsible person.
3. A platform administrator creates the hospital.
4. The platform administrator sends the first hospital-admin invitation.
5. After acceptance, that hospital admin can invite additional admins and staff
   only within the same hospital.

Self-claiming a hospital or becoming its first admin based only on an email domain is
not allowed. The system must also prevent revoking or suspending the last active
hospital administrator.

## Proposed API surface

Identity and workspace discovery:

```http
GET /api/staff/me
```

The response returns identity display fields and every active hospital membership,
role, and permitted workspace. It replaces frontend role probing.

Platform administration:

```http
POST /api/platform/hospitales
POST /api/platform/hospitales/{hospitalId}/primer-admin/invitaciones
```

Hospital administration:

```http
GET    /api/admin/hospitales/{hospitalId}/personal
GET    /api/admin/hospitales/{hospitalId}/invitaciones
POST   /api/admin/hospitales/{hospitalId}/invitaciones
POST   /api/admin/hospitales/{hospitalId}/invitaciones/{id}/reenviar
DELETE /api/admin/hospitales/{hospitalId}/invitaciones/{id}
PATCH  /api/admin/hospitales/{hospitalId}/membresias/{id}
PUT    /api/admin/hospitales/{hospitalId}/membresias/{id}/roles
PUT    /api/admin/hospitales/{hospitalId}/medicos/{id}/asignaciones
POST   /api/admin/hospitales/{hospitalId}/salas
PATCH  /api/admin/hospitales/{hospitalId}/salas/{salaId}
```

Invitation acceptance:

```http
GET  /api/invitaciones/{token}/resumen
POST /api/invitaciones/{token}/aceptar
```

Responses need typed `400`, `401`, `403`, `404`, `409`, and `410` error bodies.
The raw invitation token must never be logged.

## Frontend experience

- A single authentication form is used for every staff profile.
- After authentication, one available workspace redirects automatically.
- Multiple memberships or profiles open a workspace selector grouped by hospital.
- `/recepcion` and `/medico` check membership, not a single inferred global role.
- The staff shell exposes a workspace switcher without storing clinical or identity
  data in localStorage.
- Hospital admins receive a panel for staff, invitations, assignments, rooms, and
  audit history.
- Invitation acceptance shows hospital, inviter, requested roles, expiry, and the
  information the recipient is confirming.

The selected route does not grant authority; every backend operation revalidates the
authenticated subject, active membership, role, hospital, and resource ownership.

### Membership status and staff sessions

An existing membership is authoritative during lazy legacy access migration:
`SUSPENDIDA` and `REVOCADA` memberships stay disabled, and migration does not
restore roles that an administrator removed. Legacy profile relationships are
still accepted when no membership row exists. Starting a doctor or reception
session requires an existing membership to be `ACTIVA` and include the matching
hospital role; those session starts continue to use legacy profile authorization
when the membership row is absent. Accepting an older pending invitation cannot
reactivate a `SUSPENDIDA` membership; a hospital administrator must reactivate it.
A `REVOCADA` membership can become active again when the invited user accepts a
new valid invitation.

Session starts and hospital membership status/role changes serialize on the
selected hospital row. Suspending or revoking a membership returns `409` while
the person has an active or paused medical session, or an active reception
session, in that same hospital. Removing the corresponding medical or reception
role also returns `409` while that role has an open session there. Close the
session before changing access. These checks do not affect sessions or
memberships at other hospitals.

## Security and audit requirements

- Replace the provisional password-realm/ID-token integration with Authorization
  Code + PKCE and access tokens intended for this API.
- Require MFA for platform and hospital administrators.
- Apply least privilege to Management API machine credentials.
- Prevent public staff or admin registration and ignore client-supplied global roles.
- Rate-limit invitation creation, resend, login, and acceptance.
- Use generic responses where email enumeration is possible.
- Audit hospital creation, invitations, acceptance, role changes, assignments,
  room changes, suspensions, revocations, and last-admin protection failures.
- Never include passwords, tokens, full clinical information, or invitation secrets
  in logs or analytics.
- Decide whether a single identity may hold active reception and medical sessions
  simultaneously. The recommended rule is to reject the second session with `409`.

## Migration from the current model

1. Secure or remove public staff registration before exposing admin endpoints.
2. Create membership, role, invitation, credential, and audit tables.
3. Backfill memberships from existing receptionist/hospital and doctor assignment
   relationships.
4. Backfill doctor profiles and professional credentials.
5. Introduce `/api/staff/me` while keeping current endpoints temporarily.
6. Migrate service authorization to membership checks.
7. Remove cascade operations from role profiles to the shared `UsuarioAuth`.
8. Move the frontend from role probing to membership-aware navigation.
9. Add the hospital administration and invitation UI.
10. Remove compatibility paths after contract and data verification.

## Delivery phases

1. Authentication hardening and public-registration closure.
2. Membership schema, authorization service, and migration.
3. Platform bootstrap and first-hospital-admin flow.
4. Hospital staff invitation and acceptance.
5. Admin UI for people, roles, assignments, specialties, and rooms.
6. Multi-workspace staff login and navigation.
7. Patient account claiming and duplicate resolution.
8. MFA enforcement, audit reports, E2E tests, and operational recovery.

## Implemented contract (2026-07-14)

The current implementation adds `MembresiaHospital`, `InvitacionHospital`,
`CredencialProfesional` and `AuditoriaHospital`, including hashed single-use
invitation secrets and seven-day expiry. Legacy receptionist/hospital and
doctor/assignment relationships are backfilled lazily when `/api/staff/me` is
requested, so existing development data continues to work during the transition.

Medical invitations require registration number, national/provincial type and a
jurisdiction for provincial credentials. National credentials are normalized to
the `NACION` jurisdiction. Newly accepted credentials begin as
`PENDIENTE_VERIFICACION`; granting hospital access does not represent official
REFEPS verification. This follows the federal credential data model, which exposes
the registration number and its issuing jurisdiction:
https://www.argentina.gob.ar/salud/matricula-digital-de-profesionales-de-la-salud

Available endpoints:

```http
GET    /api/staff/me
GET    /api/admin/hospitales/{hospitalId}/personal
GET    /api/admin/hospitales/{hospitalId}/invitaciones
POST   /api/admin/hospitales/{hospitalId}/invitaciones
POST   /api/admin/hospitales/{hospitalId}/invitaciones/{invitacionId}/reenviar
DELETE /api/admin/hospitales/{hospitalId}/invitaciones/{invitacionId}
PATCH  /api/admin/hospitales/{hospitalId}/membresias/{membresiaId}
PUT    /api/admin/hospitales/{hospitalId}/membresias/{membresiaId}/roles
PATCH  /api/admin/hospitales/{hospitalId}/membresias/{membresiaId}/roles/{rol}
GET    /api/admin/hospitales/{hospitalId}/auditoria
GET    /api/platform/hospitales
POST   /api/platform/hospitales/{hospitalId}/primer-admin/invitaciones
GET    /api/invitaciones/{token}/resumen
POST   /api/invitaciones/{token}/registro
POST   /api/invitaciones/{token}/aceptar
GET    /api/admin/hospitales/{hospitalId}/configuracion
POST   /api/admin/hospitales/{hospitalId}/configuracion/sectores
PUT    /api/admin/hospitales/{hospitalId}/configuracion/sectores/{sectorId}
DELETE /api/admin/hospitales/{hospitalId}/configuracion/sectores/{sectorId}
GET    /api/admin/hospitales/{hospitalId}/configuracion/sectores/{sectorId}/sesiones
POST   /api/admin/hospitales/{hospitalId}/configuracion/sectores/{sectorId}/sesiones/{sesionId}/cerrar
POST   /api/admin/hospitales/{hospitalId}/configuracion/especialidades/{especialidadId}
DELETE /api/admin/hospitales/{hospitalId}/configuracion/especialidades/{especialidadId}
POST   /api/admin/hospitales/{hospitalId}/configuracion/sectores/{sectorId}/salas
PUT    /api/admin/hospitales/{hospitalId}/configuracion/sectores/{sectorId}/salas/{salaId}
PATCH  /api/admin/hospitales/{hospitalId}/configuracion/sectores/{sectorId}/salas/{salaId}/estado
```

The specialty catalog is global; a hospital admin manages which catalog entries are
offered by their hospital. Rooms belong to one hospital, one enabled specialty and
one sector: they are created/updated/activated under `/sectores/{sectorId}/salas`
and their specialty must match the sector's specialty. Rooms are activated or
deactivated rather than deleted to preserve historical references, and disabling a
specialty requires first deactivating all its rooms in the hospital, including rooms without a sector. No sector selection is required. The operation only removes the hospital-specialty association, preserves the global catalog and existing sectors/rooms, and does not cancel consultations or close medical sessions. Repeating it returns the current configuration without duplicating the audit event.

An active or paused medical session prevents deactivating its room or sector.
The hospital administrator can list those sessions under each sector, including
inactive sectors, and confirm their finalization. The API checks hospital-admin
access before loading sessions, enforces hospital and sector ownership (`404`
for a mismatch), and rejects closure (`409`) if a patient is called/in attention
or the session has an attention in progress. It releases the reservation by
transitioning to `FINALIZADA`, preserves history and audits
`SESION_MEDICA_CERRADA_POR_ADMIN`; repeated closure does not duplicate the audit.
Pausing a session is not enough to release its room. The list exposes staff,
specialty, room and session metadata only, without patient data.

Sector changes also reject pending consultations assigned directly to the sector,
even before they have a room. Physical sector deletion is limited to never-used
sectors: an empty queue manager and their unreferenced rooms can be removed in
the same transaction. Deleting a sector with historical references archives it
and its rooms out of configuration, preserving those references. Pending
patients and active or paused sessions return `409` until resolved. Successful
deletion returns an empty `204` response.

The public `/api/register` endpoint now rejects doctor, receptionist and admin
registration. Invitation registration derives hospital roles from the stored
invitation and never accepts them from the public caller. New-account registration
requires a password between 8 and 72 characters, including uppercase, lowercase,
numeric and symbol characters; password confirmation belongs to the frontend and
is never sent to or persisted by the backend.

If Auth0 already contains the invited identity but the local account does not exist,
registration authenticates the supplied password and reuses the verified Auth0
subject to finish the local account and membership. A local document conflict is
checked before any Auth0 write to avoid creating another external orphan.

Email delivery follows Ports and Adapters. `InvitationEmailPort` is implemented by
`LocalInvitationEmailAdapter` for the one-time development handoff and by
`SmtpInvitationEmailAdapter` for actual delivery. SMTP mode never returns the raw
secret. Reissuing rotates the secret and renews the seven-day expiry. A persistent
outbox remains required before production so provider outages can be retried and
observed independently of request processing.

## Acceptance criteria

The targeted role endpoint accepts `{"estado":"SUSPENDIDA"}` or
`{"estado":"ACTIVA"}`. Personnel responses expose assigned `roles` and
`rolesSuspendidos`. Staff context memberships expose effective `roles`.
The endpoint changes one assigned role only, and the backend
rejects suspending the final effective administrator or suspending a medical or
reception role while its hospital session is open.

- An existing receptionist can accept a doctor invitation without a second account.
- A doctor can belong to multiple hospitals with different specialties and roles.
- An invitation authorizes nothing until the verified recipient accepts it.
- A hospital admin cannot affect another hospital or create a platform admin.
- The last active hospital admin cannot be removed.
- A new staff member chooses and confirms their password; PreTriage never emails a password.
- Public registration cannot create staff or administrative access.
- Every privileged change is attributable to an actor, hospital, timestamp, and
  resulting state.
